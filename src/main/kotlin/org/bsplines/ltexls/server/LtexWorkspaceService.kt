/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import com.google.gson.JsonObject
import com.sun.management.OperatingSystemMXBean
import org.bsplines.ltexls.tools.FileIo
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.bsplines.ltexls.tools.Tools
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.services.WorkspaceService
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Suppress("TooManyFunctions")
class LtexWorkspaceService(
  val languageServer: LtexLanguageServer,
) : WorkspaceService {
  override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
    recheckAllDocuments()
  }

  // Re-check and re-publish diagnostics for every open document. Used both when
  // configuration changes and after the server mutates an external setting file
  // (so a freshly added dictionary word clears its diagnostic on the next check,
  // which re-reads and re-expands the external file).
  private fun recheckAllDocuments() {
    this.languageServer.ltexTextDocumentService
      .executeFunctionForEachDocument { document: LtexTextDocumentItem ->
        if (document.beingChecked) document.cancelCheck()

        this.languageServer.singleThreadExecutorService.execute {
          var exception: Exception? = null

          try {
            document.checkAndPublishDiagnosticsWithoutCache()
            document.raiseExceptionIfCanceled()
          } catch (e: ExecutionException) {
            exception = e
          } catch (e: InterruptedException) {
            exception = e
          }

          if (exception != null) {
            Tools.rethrowCancellationException(exception)
            Logging.LOGGER.warning(I18n.format(exception))
          }
        }
      }
  }

  override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
  }

  override fun executeCommand(params: ExecuteCommandParams): CompletableFuture<Any> =
    when (params.command) {
      CHECK_DOCUMENT_COMMAND_NAME -> {
        commandArgument(params)?.let(::executeCheckDocumentCommand)
          ?: failCommand(I18n.format("invalidCommandArguments", params.command))
      }

      GET_SERVER_STATUS_COMMAND_NAME -> {
        executeGetServerStatusCommand()
      }

      ADD_TO_DICTIONARY_COMMAND_NAME -> {
        executeAddToDictionaryCommand(params.arguments[0] as JsonObject)
      }

      DISABLE_RULES_COMMAND_NAME -> {
        executeDisableRulesCommand(params.arguments[0] as JsonObject)
      }

      HIDE_FALSE_POSITIVES_COMMAND_NAME -> {
        executeHideFalsePositivesCommand(params.arguments[0] as JsonObject)
      }

      else -> {
        failCommand(I18n.format("unknownCommand", params.command))
      }
    }

  // The three server-side quick fixes ("Add to dictionary", "Disable rule", "Hide
  // false positive"), active only when the client opted into server-managed
  // external files. Each command carries { uri, <key>: { lang: [entry, ...] } };
  // they differ only in the argument key and which setting's external file to
  // write, so they all delegate to executeAppendToExternalFileCommand.
  fun executeAddToDictionaryCommand(arguments: JsonObject): CompletableFuture<Any> =
    executeAppendToExternalFileCommand(arguments, "dictionary", "words")

  fun executeDisableRulesCommand(arguments: JsonObject): CompletableFuture<Any> =
    executeAppendToExternalFileCommand(arguments, "disabledRules", "ruleIds")

  fun executeHideFalsePositivesCommand(arguments: JsonObject): CompletableFuture<Any> =
    executeAppendToExternalFileCommand(arguments, "hiddenFalsePositives", "falsePositives")

  // Appends each language's entries to the first external file configured for that
  // setting/language (resolved during settings expansion, see Settings), then
  // re-checks open documents. No external file for a language → warning; if nothing
  // could be written, the command fails.
  private fun executeAppendToExternalFileCommand(
    arguments: JsonObject,
    settingName: String,
    argumentKey: String,
  ): CompletableFuture<Any> {
    val entriesByLanguage: JsonObject = arguments.getAsJsonObject(argumentKey)
    val settings = this.languageServer.settingsManager.settings

    var appendedAny = false
    var languageWithoutExternalFile: String? = null

    for ((language: String, element) in entriesByLanguage.entrySet()) {
      val entries: List<String> = element.asJsonArray.map { it.asString }
      val filePath: String? = settings.firstExternalSettingFile(settingName, language)

      if (filePath == null) {
        languageWithoutExternalFile = language
        Logging.LOGGER.warning(I18n.format("noExternalFileForSetting", settingName, language))
        continue
      }

      if (appendEntriesToExternalFile(Paths.get(filePath), entries)) appendedAny = true
    }

    if (!appendedAny && (languageWithoutExternalFile != null)) {
      return failCommand(
        I18n.format("noExternalFileForSetting", settingName, languageWithoutExternalFile),
      )
    }

    if (appendedAny) recheckAllDocuments()

    val jsonObject = JsonObject()
    jsonObject.addProperty("success", true)
    return CompletableFuture.completedFuture(jsonObject)
  }

  // A command payload is a single JSON object passed as the first argument. All
  // three ways a client can deviate from that — omitting `arguments` entirely
  // (the field is optional per the LSP spec), sending an empty list, or sending a
  // non-object — used to abort the request with an InternalError instead of a
  // failed command result.
  private fun commandArgument(params: ExecuteCommandParams): JsonObject? =
    params.arguments?.firstOrNull() as? JsonObject

  // Appends the given entries to the external file. Mirrors the format of
  // vscode-ltex-plus's ExternalFileManager.appendToFile so a file stays compatible
  // across editors: the existing content is preserved verbatim, a trailing line
  // separator is ensured, and each new entry is appended on its own line using the
  // platform line separator. Like the VS Code extension, this does not deduplicate
  // (duplicate lines collapse anyway when the file is read back into the set-valued
  // setting). Returns true if the file was modified.
  private fun appendEntriesToExternalFile(
    path: Path,
    newEntries: List<String>,
  ): Boolean {
    if (newEntries.isEmpty()) return false

    val lineSeparator: String = System.lineSeparator()
    val existingContents: String = if (Files.exists(path)) (FileIo.readFile(path) ?: "") else ""

    val builder = StringBuilder(existingContents)
    // Match the extension: only add a separator when the file has real content and
    // does not already end with one (treating space/CR/LF as ignorable).
    val hasContent: Boolean = existingContents.any { (it != ' ') && (it != '\r') && (it != '\n') }
    if (hasContent && !existingContents.endsWith(lineSeparator)) builder.append(lineSeparator)
    builder.append(newEntries.joinToString(lineSeparator)).append(lineSeparator)

    path.parent?.let { Files.createDirectories(it) }
    FileIo.writeFile(path, builder.toString())
    Logging.LOGGER.info(
      I18n.format("addedEntriesToExternalSettingFile", newEntries.size, path.toString()),
    )
    return true
  }

  fun executeCheckDocumentCommand(arguments: JsonObject): CompletableFuture<Any> {
    val uriStr: String =
      arguments.get("uri")?.takeIf { it.isJsonPrimitive }?.asString
        ?: return failCommand(
          I18n.format("invalidCommandArguments", CHECK_DOCUMENT_COMMAND_NAME),
        )
    var codeLanguageId: String? = arguments.get("codeLanguageId")?.asString
    var text: String? = arguments.get("text")?.asString

    if ((codeLanguageId == null) || (text == null)) {
      val path: Path =
        try {
          Paths.get(URI(uriStr))
        } catch (e: IllegalArgumentException) {
          return failCommand(I18n.format("couldNotParseDocumentUri", e))
        } catch (e: URISyntaxException) {
          return failCommand(I18n.format("couldNotParseDocumentUri", e))
        }

      if (text == null) {
        text = FileIo.readFile(path)
        if (text == null) return failCommand(I18n.format("couldNotReadFile", path.toString()))
      }

      codeLanguageId = codeLanguageId ?: FileIo.getCodeLanguageIdFromPath(path)
      codeLanguageId = codeLanguageId ?: "plaintext"
    }

    val document = LtexTextDocumentItem(this.languageServer, uriStr, codeLanguageId, 1, text)

    val range: Range? =
      if (arguments.has("range")) {
        val jsonRange: JsonObject = arguments.getAsJsonObject("range")
        val jsonStart: JsonObject = jsonRange.getAsJsonObject("start")
        val jsonEnd: JsonObject = jsonRange.getAsJsonObject("end")
        Range(
          Position(jsonStart.get("line").asInt, jsonStart.get("character").asInt),
          Position(jsonEnd.get("line").asInt, jsonEnd.get("character").asInt),
        )
      } else {
        null
      }

    if (document.beingChecked) document.cancelCheck()

    return CompletableFutures.computeAsync(
      this.languageServer.singleThreadExecutorService,
    ) { lspCancelChecker: CancelChecker ->
      document.lspCancelChecker = lspCancelChecker

      try {
        val success: Boolean = document.checkAndPublishDiagnosticsWithoutCache(range)
        val jsonObject = JsonObject()
        jsonObject.addProperty("success", success)
        document.raiseExceptionIfCanceled()
        jsonObject
      } catch (e: ExecutionException) {
        Tools.rethrowCancellationException(e)
        Logging.LOGGER.warning(I18n.format(e))
        emptyList<Any>()
      } catch (e: InterruptedException) {
        Tools.rethrowCancellationException(e)
        Logging.LOGGER.warning(I18n.format(e))
        emptyList<Any>()
      }
    }
  }

  @Suppress("SwallowedException")
  fun executeGetServerStatusCommand(): CompletableFuture<Any> {
    val processId: Long = ProcessHandle.current().pid()
    val wallClockDuration: Double =
      Duration
        .between(
          this.languageServer.startupInstant,
          Instant.now(),
        ).toMillis() / MILLISECONDS_PER_SECOND
    val cpuDuration: Double?
    var cpuUsage: Double?
    val totalMemory: Double = Runtime.getRuntime().totalMemory().toDouble()
    val usedMemory: Double = totalMemory - Runtime.getRuntime().freeMemory()

    if (ManagementFactory.getOperatingSystemMXBean() is OperatingSystemMXBean) {
      val operatingSystemMxBean: OperatingSystemMXBean =
        ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
      cpuUsage = operatingSystemMxBean.processCpuLoad
      if (cpuUsage == -1.0) cpuUsage = null
      val cpuDurationLong: Long = operatingSystemMxBean.processCpuTime
      cpuDuration = if (cpuDurationLong != -1L) (cpuDurationLong / NANOSECONDS_PER_SECOND) else null
    } else {
      cpuDuration = null
      cpuUsage = null
    }

    val singleThreadTestFuture: Future<Boolean> =
      this.languageServer.singleThreadExecutorService.submit(Callable { true })
    val isChecking: Boolean =
      try {
        !singleThreadTestFuture.get(CHECK_CHECKING_STATUS_MILLISECONDS, TimeUnit.MILLISECONDS)
      } catch (e: ExecutionException) {
        true
      } catch (e: InterruptedException) {
        true
      } catch (e: TimeoutException) {
        true
      }

    val documentUriBeingChecked: String? =
      if (isChecking) {
        languageServer.documentChecker.lastCheckedDocument?.uri
      } else {
        null
      }

    val jsonObject = JsonObject()
    jsonObject.addProperty("success", true)
    jsonObject.addProperty("processId", processId)
    jsonObject.addProperty("wallClockDuration", wallClockDuration)
    if (cpuUsage != null) jsonObject.addProperty("cpuUsage", cpuUsage)
    if (cpuDuration != null) jsonObject.addProperty("cpuDuration", cpuDuration)
    jsonObject.addProperty("usedMemory", usedMemory)
    jsonObject.addProperty("totalMemory", totalMemory)
    jsonObject.addProperty("isChecking", isChecking)

    if (documentUriBeingChecked != null) {
      jsonObject.addProperty("documentUriBeingChecked", documentUriBeingChecked)
    }

    return CompletableFuture.completedFuture(jsonObject)
  }

  companion object {
    private const val CHECK_DOCUMENT_COMMAND_NAME = "_ltex.checkDocument"
    private const val GET_SERVER_STATUS_COMMAND_NAME = "_ltex.getServerStatus"
    private const val ADD_TO_DICTIONARY_COMMAND_NAME = "_ltex.addToDictionary"
    private const val DISABLE_RULES_COMMAND_NAME = "_ltex.disableRules"
    private const val HIDE_FALSE_POSITIVES_COMMAND_NAME = "_ltex.hideFalsePositives"

    private const val CHECK_CHECKING_STATUS_MILLISECONDS = 10L
    private const val MILLISECONDS_PER_SECOND = 1e3
    private const val NANOSECONDS_PER_SECOND = 1e9

    private fun failCommand(errorMessage: String): CompletableFuture<Any> {
      val jsonObject = JsonObject()
      jsonObject.addProperty("success", false)
      jsonObject.addProperty("errorMessage", errorMessage)
      return CompletableFuture.completedFuture(jsonObject)
    }

    // The external-file quick-fix commands are advertised as server commands only
    // when the client delegates external-file management to the server. Editor-
    // managed clients (the default) keep handling these quick fixes themselves, so
    // the advertised command set — and the init response — stay byte-identical.
    fun getCommandNames(serverManagesExternalFiles: Boolean = false): List<String> {
      val commandNames: MutableList<String> =
        mutableListOf(CHECK_DOCUMENT_COMMAND_NAME, GET_SERVER_STATUS_COMMAND_NAME)
      if (serverManagesExternalFiles) {
        commandNames.add(ADD_TO_DICTIONARY_COMMAND_NAME)
        commandNames.add(DISABLE_RULES_COMMAND_NAME)
        commandNames.add(HIDE_FALSE_POSITIVES_COMMAND_NAME)
      }
      return commandNames
    }
  }
}
