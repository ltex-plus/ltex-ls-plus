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
import org.bsplines.ltexls.settings.BasicSettingsFileManager
import org.bsplines.ltexls.settings.SettingsFileManager
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
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersOptions
import org.eclipse.lsp4j.WorkspaceServerCapabilities
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.net.URI
import java.nio.file.Path
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
  val settingsFileManager: SettingsFileManager = BasicSettingsFileManager()

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

  var workspaceRoots: List<Canonical<WorkspaceFolder>> = listOf()

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
      val initializationOptionsObject: JsonObject = initializationOptions.asJsonObject

      // make it possible to set locale when using LSP 3.15 (that's what we currently require
      // as minimum version; InitializeParams.locale was added in LSP 3.16)
      if (initializationOptionsObject.has("locale")) {
        localeLanguage = initializationOptionsObject.get("locale").asString
      }

      if (initializationOptionsObject.has("customCapabilities")) {
        val customCapabilities: JsonObject =
          initializationOptionsObject.getAsJsonObject("customCapabilities")

        if (customCapabilities.has("workspaceSpecificConfiguration")) {
          this.clientSupportsWorkspaceSpecificConfiguration =
            customCapabilities.get("workspaceSpecificConfiguration").asBoolean
        }
      }
    }

    if (localeLanguage != null) I18n.setLocale(Locale.forLanguageTag(localeLanguage))

    val serverCapabilities = ServerCapabilities()

    serverCapabilities.codeActionProvider =
      Either.forRight(CodeActionOptions(CodeActionProvider.getCodeActionKinds()))
    serverCapabilities.completionProvider = CompletionOptions().apply { resolveProvider = false }
    serverCapabilities.executeCommandProvider =
      ExecuteCommandOptions(LtexWorkspaceService.getCommandNames())
    serverCapabilities.textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

    val workspaceFoldersOptions = WorkspaceFoldersOptions()
    workspaceFoldersOptions.supported = true
    workspaceFoldersOptions.changeNotifications = Either.forRight(true)
    serverCapabilities.workspace = WorkspaceServerCapabilities(workspaceFoldersOptions)

    // If the client supports workspace folders, then we only use those. Otherwise, we fall
    // back on the rootUri. Either way, it should be possible to get their canonical paths --
    // all of them should be file:// URIs pointing to real directories on the machine where the
    // language server is running.
    // If we do fail to get the canonical representation of any path, then we just log the reason
    // and ignore the path.
    if (clientCapabilities?.workspace?.workspaceFolders == true) {
      val folders: List<WorkspaceFolder>? = params.workspaceFolders
      this.workspaceRoots = folders
        ?.mapNotNull { Canonical.from(it) }
        ?: listOf()
    } else {
      // TODO Should we keep holding onto the rootUri? It feels like a reasonable fallback for
      //  clients lacking workspaceFolders support. Otoh it's been superseded by workspaceFolders
      //  since 2018.
      @Suppress("DEPRECATION")
      val path = Canonical.from(WorkspaceFolder(params.rootUri, "root"))
      this.workspaceRoots = listOfNotNull(path)
    }

    // Advertise the server's identity and version to the client via serverInfo so it can,
    // e.g., gate features on a minimum version. The version is the JAR manifest's
    // Implementation-Version, stamped at build time as <label>.<commitsSinceReleaseTag>+g<hash>
    // for nightlies (see pom.xml / git-commit-id-maven-plugin) and the plain release for releases.
    val serverInfo = ServerInfo("ltex-ls-plus", ltexLsPackage?.implementationVersion)
    return CompletableFuture.completedFuture(InitializeResult(serverCapabilities, serverInfo))
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

  /**
   * Guess the most appropriate workspace folder for the current file.
   * This folder will be used as the root for resolving relative file settings paths.
   *
   * For real files, this directory attempts to locate their corresponding workspace root.
   * If there are multiple candidates, it picks the deepest nested (most specific) one.
   *
   * For virtual (for example unnamed / unsaved) files, we use the first root of the workspace.
   *
   * If no suitable workspace folder is found, then the user's home directory is used as a last
   * resort fallback.
   */
  fun relativePathRoot(documentUri: String): Path {
    val documentPath = Canonical.fromUri(documentUri)?.canonicalPath

    if (documentPath != null) {
      val candidateRoots =
        this.workspaceRoots
          .map { it.canonicalPath }
          .filter { documentPath.startsWith(it) }

      candidateRoots
        .reduceOrNull { best, curr -> if (best.startsWith(curr)) best else curr }
        ?.let { return it }
    } else {
      // This is a virtual file (unnamed/unsaved). We give it the first available root.
      this.workspaceRoots.firstOrNull()?.canonicalPath?.let {
        Logging.LOGGER.info(I18n.format("workspaceFolderFallback", it, documentPath))
        return it
      }
    }

    // We did not find a suitable root for the file. If it was a real file, then it means it was
    // outside all current workspaces. If it was a virtual file, then it means the workspace itself
    // is empty. Either way, we need some sane last resort callback, which is the user's home.

    // TODO Do we want to send a window/showMessageRequest to indicate the root chosen?
    //  or even let the user choose a root if more are available
    val home = Path.of(System.getProperty("user.home")).toRealPath()
    Logging.LOGGER.info(I18n.format("workspaceFolderFallback", home, documentPath))
    return home
  }

  @ConsistentCopyVisibility
  data class Canonical<T> private constructor(
    val canonicalPath: Path,
    val originalUri: T,
  ) {
    companion object {
      // Ideally (https://youtrack.jetbrains.com/issue/KT-7128) we could multi-catch
      // URISyntaxException, FileSystemNotFoundException, SecurityException and
      // IOException. The handling would be the same in all cases though -- log the
      // error and return null, so I'm just suppressing the compiler error here.
      @Suppress("TooGenericExceptionCaught")
      fun fromUri(uriString: String): Canonical<String>? {
        try {
          val uri = URI(uriString)
          val path = Path.of(uri)
          val canonicalPath = path.normalize().toRealPath()
          return Canonical(canonicalPath, uriString)
        } catch (e: Exception) {
          Logging.LOGGER.warning(
            I18n.format("cannotCanonicalizeUri", e, uriString),
          )
          return null
        }
      }

      // Ideally (https://youtrack.jetbrains.com/issue/KT-7128) we could multi-catch
      // FileSystemNotFoundException, SecurityException and
      // IOException. The handling would be the same in all cases though -- log the
      // error and return null, so I'm just suppressing the compiler error here.
      @Suppress("TooGenericExceptionCaught")
      fun fromPath(pathString: String): Canonical<String>? {
        try {
          val path = Path.of(pathString)
          val canonicalPath = path.normalize().toRealPath()
          return Canonical(canonicalPath, pathString)
        } catch (e: Exception) {
          Logging.LOGGER.warning(
            I18n.format("cannotCanonicalizeUri", e, pathString),
          )
          return null
        }
      }

      fun from(workspaceFolder: WorkspaceFolder): Canonical<WorkspaceFolder>? =
        fromUri(workspaceFolder.uri)
          ?.let { Canonical(it.canonicalPath, workspaceFolder) }
    }
  }

  companion object {
    private const val FRAGMENT_CACHE_SWEEP_INTERVAL_SECONDS = 60L
    private const val MILLIS_PER_MINUTE = 60_000L
  }
}
