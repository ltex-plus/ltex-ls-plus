/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bsplines.ltexls.client.LtexLanguageClient
import org.bsplines.ltexls.parsing.FragmentCache
import org.bsplines.ltexls.settings.SettingsManager
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.ExecuteCommandOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ServerInfo
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.WindowClientCapabilities
import org.eclipse.lsp4j.WorkspaceFoldersOptions
import org.eclipse.lsp4j.WorkspaceServerCapabilities
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class LtexLanguageServer :
  LanguageServer,
  LanguageClientAware {
  var languageClient: LtexLanguageClient? = null
  val singleThreadExecutorService: ExecutorService = Executors.newSingleThreadScheduledExecutor()
  val settingsManager = SettingsManager()

  // Shared across all documents; swept periodically and cleared per-document on
  // didClose.
  val fragmentCache = FragmentCache()
  private val fragmentCacheSweeper: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
      // Daemon so an un-shut-down sweeper (e.g. in tests) never pins the JVM.
      Thread(runnable, "ltex-fragment-cache-sweeper").apply { isDaemon = true }
    }
  val documentChecker = DocumentChecker(this.settingsManager, this.fragmentCache)
  val codeActionProvider = CodeActionProvider(this.settingsManager)
  val completionListProvider = CompletionListProvider(this.settingsManager)
  val ltexTextDocumentService = LtexTextDocumentService(this)
  val ltexWorkspaceService = LtexWorkspaceService(this)
  val startupInstant: Instant = Instant.now()

  var clientSupportsWorkDoneProgress: Boolean = false
    private set
  var clientSupportsWorkspaceSpecificConfiguration: Boolean = false
    private set

  // When true, the server itself reads/writes the external setting files
  // (e.g. an external dictionary file referenced with a ":" prefix). Driven by
  // the client opt-in ltex.externalFiles.managedByEditor: the default (true)
  // means the editor manages those files, so the server stays out and behaves
  // exactly as before; only a client sending managedByEditor=false flips this on.
  // Read at initialize because it gates executeCommandProvider (see below).
  var serverManagesExternalFiles: Boolean = false
    private set

  init {
    // Sweep idle fragment-cache entries on a fixed 60 s cadence. Entries idle
    // longer than ltex.paragraphCacheTtlMinutes are dropped; every cache hit
    // refreshes an entry, so actively edited documents stay warm.
    this.fragmentCacheSweeper.scheduleAtFixedRate(
      { sweepFragmentCache() },
      FRAGMENT_CACHE_SWEEP_INTERVAL_SECONDS,
      FRAGMENT_CACHE_SWEEP_INTERVAL_SECONDS,
      TimeUnit.SECONDS,
    )
  }

  // Wrapped so a stray exception never cancels the recurring scheduled task.
  // internal (not private) so the sweep path is unit-testable without waiting
  // for the 60 s scheduler.
  @Suppress("TooGenericExceptionCaught")
  internal fun sweepFragmentCache() {
    try {
      val ttlMillis: Long =
        this.settingsManager.settings.paragraphCacheTtlMinutes * MILLIS_PER_MINUTE
      this.fragmentCache.evictIdleOlderThan(ttlMillis)
    } catch (e: RuntimeException) {
      Logging.LOGGER.warning("Fragment cache sweep failed: $e")
    }
  }

  override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
    val ltexLsPackage: Package? = LtexLanguageServer::class.java.getPackage()
    val ltexLsVersion: String = ltexLsPackage?.implementationVersion ?: "null"
    Logging.LOGGER.info(I18n.format("initializingLtexLs", ltexLsVersion))

    val clientCapabilities: ClientCapabilities? = params.capabilities
    this.clientSupportsWorkDoneProgress = false

    if (clientCapabilities != null) {
      val windowClientCapabilities: WindowClientCapabilities? = clientCapabilities.window

      if ((windowClientCapabilities != null) && (windowClientCapabilities.workDoneProgress)) {
        this.clientSupportsWorkDoneProgress = true
      }
    }

    var localeLanguage: String? = params.locale
    val initializationOptions: JsonElement? = params.initializationOptions as JsonElement?

    if ((initializationOptions != null) && initializationOptions.isJsonObject) {
      localeLanguage =
        applyInitializationOptions(initializationOptions.asJsonObject, localeLanguage)
    }

    if (localeLanguage != null) I18n.setLocale(Locale.forLanguageTag(localeLanguage))

    val serverCapabilities = ServerCapabilities()

    serverCapabilities.codeActionProvider =
      Either.forRight(CodeActionOptions(CodeActionProvider.getCodeActionKinds()))
    serverCapabilities.completionProvider = CompletionOptions().apply { resolveProvider = false }
    serverCapabilities.executeCommandProvider =
      ExecuteCommandOptions(LtexWorkspaceService.getCommandNames(this.serverManagesExternalFiles))
    serverCapabilities.textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

    val workspaceFoldersOptions = WorkspaceFoldersOptions()
    workspaceFoldersOptions.supported = true
    workspaceFoldersOptions.setChangeNotifications(Either.forRight(true))
    serverCapabilities.workspace = WorkspaceServerCapabilities(workspaceFoldersOptions)

    // Advertise the server's identity and version to the client via serverInfo so it can,
    // e.g., gate features on a minimum version. The version is the JAR manifest's
    // Implementation-Version, stamped at build time as <label>.<commitsSinceReleaseTag>+g<hash>
    // for nightlies (see pom.xml / git-commit-id-maven-plugin) and the plain release for releases.
    val serverInfo = ServerInfo("ltex-ls-plus", ltexLsPackage?.implementationVersion)
    return CompletableFuture.completedFuture(InitializeResult(serverCapabilities, serverInfo))
  }

  // Reads locale and the custom/opt-in flags from initializationOptions, returning
  // the (possibly updated) locale. Split out of initialize() to keep that method's
  // complexity and nesting within bounds.
  private fun applyInitializationOptions(
    initializationOptionsObject: JsonObject,
    currentLocale: String?,
  ): String? {
    // make it possible to set locale when using LSP 3.15 (that's what we currently require
    // as minimum version; InitializeParams.locale was added in LSP 3.16)
    val localeLanguage: String? =
      if (initializationOptionsObject.has("locale")) {
        initializationOptionsObject.get("locale").asString
      } else {
        currentLocale
      }

    if (initializationOptionsObject.has("customCapabilities")) {
      val customCapabilities: JsonObject =
        initializationOptionsObject.getAsJsonObject("customCapabilities")

      if (customCapabilities.has("workspaceSpecificConfiguration")) {
        this.clientSupportsWorkspaceSpecificConfiguration =
          customCapabilities.get("workspaceSpecificConfiguration").asBoolean
      }
    }

    this.serverManagesExternalFiles = readServerManagesExternalFiles(initializationOptionsObject)
    return localeLanguage
  }

  // ltex.externalFiles.managedByEditor (default true). Thin clients that cannot
  // manage external setting files themselves (e.g. Zed, Helix) send false to hand
  // that responsibility to the server. Delivered via initializationOptions because
  // the value must be known at initialize time to decide whether
  // _ltex.addToDictionary is advertised as a server command.
  private fun readServerManagesExternalFiles(initializationOptionsObject: JsonObject): Boolean {
    val ltexObject: JsonObject? =
      initializationOptionsObject.takeIf { it.has("ltex") }?.getAsJsonObject("ltex")
    val externalFilesObject: JsonObject? =
      ltexObject?.takeIf { it.has("externalFiles") }?.getAsJsonObject("externalFiles")

    return (externalFilesObject != null) &&
      externalFilesObject.has("managedByEditor") &&
      !externalFilesObject.get("managedByEditor").asBoolean
  }

  override fun shutdown(): CompletableFuture<Any> {
    Logging.LOGGER.info(I18n.format("shuttingDownLtexLs"))
    this.fragmentCacheSweeper.shutdownNow()
    this.singleThreadExecutorService.shutdown()

    // should return null according to LSP specification, but return empty object instead,
    // see https://github.com/eclipse/lsp4j/issues/18
    return CompletableFuture.completedFuture(Any())
  }

  override fun exit() {
    Logging.LOGGER.info(I18n.format("exitingLtexLs"))
    exitProcess(0)
  }

  override fun connect(languageClient: LanguageClient) {
    this.languageClient = languageClient as LtexLanguageClient
  }

  override fun getTextDocumentService(): TextDocumentService = this.ltexTextDocumentService

  override fun getWorkspaceService(): WorkspaceService = this.ltexWorkspaceService

  companion object {
    private const val FRAGMENT_CACHE_SWEEP_INTERVAL_SECONDS = 60L
    private const val MILLIS_PER_MINUTE = 60_000L
  }
}
