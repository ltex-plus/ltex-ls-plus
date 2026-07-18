/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.settings

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.bsplines.ltexls.tools.FileIo
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.eclipse.lsp4j.DiagnosticSeverity
import org.languagetool.Languages
import java.nio.file.Files
import java.nio.file.Paths
import java.util.logging.Level

data class Settings(
  private val _enabled: Set<String>? = null,
  private val _languageShortCode: String? = null,
  private val _allDictionaries: Map<String, Set<String>>? = null,
  private val _allDisabledRules: Map<String, Set<String>>? = null,
  private val _allEnabledRules: Map<String, Set<String>>? = null,
  private val _allHiddenFalsePositives: Map<String, Set<HiddenFalsePositive>>? = null,
  private val _bibtexFields: Map<String, Boolean>? = null,
  private val _latexCommands: Map<String, String>? = null,
  private val _latexEnvironments: Map<String, String>? = null,
  private val _markdownNodes: Map<String, String>? = null,
  private val _enablePickyRules: Boolean? = null,
  private val _motherTongueShortCode: String? = null,
  private val _preferredVariants: List<String>? = null,
  private val _languageModelRulesDirectory: String? = null,
  private val _languageToolHttpServerUri: String? = null,
  private val _languageToolOrgUsername: String? = null,
  private val _languageToolOrgApiKey: String? = null,
  private val _logLevel: Level? = null,
  private val _sentenceCacheSize: Long? = null,
  private val _completionEnabled: Boolean? = null,
  private val _diagnosticSeverity: Map<String, DiagnosticSeverity>? = null,
  private val _checkFrequency: CheckFrequency? = null,
  private val _clearDiagnosticsWhenClosingFile: Boolean? = null,
  private val _paragraphCacheEnabled: Boolean? = null,
  private val _paragraphCacheTtlMinutes: Long? = null,
  private val _maxRequestSize: Int? = null,
  // Resolved external setting files: settingName -> language -> first external
  // file path. Populated only when the server owns external files (client opted
  // out); used by the server-side quick-fix commands to know where to write.
  private val _externalSettingFiles: Map<String, Map<String, String>>? = null,
) {
  val enabled: Set<String>
    get() = (this._enabled ?: DEFAULT_ENABLED)
  val languageShortCode: String
    get() = (this._languageShortCode ?: "en-US")
  val dictionary: Set<String>
    get() = (this._allDictionaries?.get(this.languageShortCode) ?: setOf())
  val disabledRules: Set<String>
    get() = (this._allDisabledRules?.get(this.languageShortCode) ?: setOf())
  val enabledRules: Set<String>
    get() = (this._allEnabledRules?.get(this.languageShortCode) ?: setOf())
  val hiddenFalsePositives: Set<HiddenFalsePositive>
    get() = (this._allHiddenFalsePositives?.get(this.languageShortCode) ?: setOf())
  val allDictionaries: Map<String, Set<String>>
    get() = (this._allDictionaries ?: emptyMap())
  val allDisabledRules: Map<String, Set<String>>
    get() = (this._allDisabledRules ?: emptyMap())
  val allHiddenFalsePositives: Map<String, Set<HiddenFalsePositive>>
    get() = (this._allHiddenFalsePositives ?: emptyMap())

  // Path of the first external setting file for the given setting/language, or
  // null if none is configured (or the server is not managing external files).
  fun firstExternalSettingFile(
    settingName: String,
    language: String,
  ): String? = this._externalSettingFiles?.get(settingName)?.get(language)

  val bibtexFields: Map<String, Boolean>
    get() = (this._bibtexFields ?: mapOf())
  val latexCommands: Map<String, String>
    get() = (this._latexCommands ?: mapOf())
  val latexEnvironments: Map<String, String>
    get() = (this._latexEnvironments ?: mapOf())
  val markdownNodes: Map<String, String>
    get() = (this._markdownNodes ?: mapOf())
  val enablePickyRules: Boolean
    get() = (this._enablePickyRules ?: false)
  val motherTongueShortCode: String
    get() = (this._motherTongueShortCode ?: "")
  val preferredVariants: List<String>
    get() = mergePreferredVariants(this._preferredVariants)
  val languageModelRulesDirectory: String
    get() = FileIo.normalizePath(this._languageModelRulesDirectory ?: "")
  val languageToolHttpServerUri: String
    get() = (this._languageToolHttpServerUri ?: "")
  val languageToolOrgUsername: String
    get() = resolveEnvironmentVariableReference(this._languageToolOrgUsername ?: "")
  val languageToolOrgApiKey: String
    get() = resolveEnvironmentVariableReference(this._languageToolOrgApiKey ?: "")
  val logLevel: Level
    get() = (this._logLevel ?: Level.FINE)
  val sentenceCacheSize: Long
    get() = (this._sentenceCacheSize ?: DEFAULT_SENTENCE_CACHE_SIZE)
  val completionEnabled: Boolean
    get() = (this._completionEnabled ?: false)
  val diagnosticSeverity: Map<String, DiagnosticSeverity>
    get() = (this._diagnosticSeverity ?: DEFAULT_DIAGNOSTIC_SEVERITY)
  val checkFrequency: CheckFrequency
    get() = (this._checkFrequency ?: CheckFrequency.Edit)
  val clearDiagnosticsWhenClosingFile: Boolean
    get() = (this._clearDiagnosticsWhenClosingFile ?: true)
  val paragraphCacheEnabled: Boolean
    get() = (this._paragraphCacheEnabled ?: true)
  val paragraphCacheTtlMinutes: Long
    get() = (this._paragraphCacheTtlMinutes ?: DEFAULT_PARAGRAPH_CACHE_TTL_MINUTES)
  val maxRequestSize: Int
    get() = (this._maxRequestSize ?: DEFAULT_MAX_REQUEST_SIZE)

  /**
   * Returns differences between `this` and `other` that call for
   * ``SettingsManager.reinitializeLanguageToolInterface()``
   * */
  fun getDifferencesRelevantForLanguageTool(other: Settings?): Set<SettingsDifference> {
    val differences = HashSet<SettingsDifference>()

    if (other == null) {
      differences.add(SettingsDifference("settings", "non-null", "null"))
      return differences
    }

    if (enabledRules != other.enabledRules) {
      differences.add(SettingsDifference("enabledRules", this.enabledRules, other.enabledRules))
    }

    if (motherTongueShortCode != other.motherTongueShortCode) {
      differences.add(
        SettingsDifference(
          "additionalRules.motherTongue",
          this.motherTongueShortCode,
          other.motherTongueShortCode,
        ),
      )
    }

    if (preferredVariants != other.preferredVariants) {
      differences.add(
        SettingsDifference(
          "preferredVariants",
          this.preferredVariants,
          other.preferredVariants,
        ),
      )
    }

    if (languageModelRulesDirectory != other.languageModelRulesDirectory) {
      differences.add(
        SettingsDifference(
          "additionalRules.languageModel",
          this.languageModelRulesDirectory,
          other.languageModelRulesDirectory,
        ),
      )
    }

    if (languageToolHttpServerUri != other.languageToolHttpServerUri) {
      differences.add(
        SettingsDifference(
          "languageToolHttpServerUri",
          this.languageToolHttpServerUri,
          other.languageToolHttpServerUri,
        ),
      )
    }

    if (sentenceCacheSize != other.sentenceCacheSize) {
      differences.add(
        SettingsDifference("sentenceCacheSize", this.sentenceCacheSize, other.sentenceCacheSize),
      )
    }

    return differences
  }

  fun getModifiedDictionary(dictionary: Set<String>): Map<String, Set<String>> {
    val allDictionaries = HashMap<String, Set<String>>(this._allDictionaries ?: emptyMap())
    allDictionaries[this.languageShortCode] = dictionary
    return allDictionaries
  }

  fun getModifiedDisabledRules(disabledRules: Set<String>): Map<String, Set<String>> {
    val allDisabledRules = HashMap<String, Set<String>>(this._allDisabledRules ?: emptyMap())
    allDisabledRules[this.languageShortCode] = disabledRules
    return allDisabledRules
  }

  @Suppress("unused")
  fun getModifiedEnabledRules(enabledRules: Set<String>): Map<String, Set<String>> {
    val allEnabledRules = HashMap<String, Set<String>>(this._allEnabledRules ?: emptyMap())
    allEnabledRules[this.languageShortCode] = enabledRules
    return allEnabledRules
  }

  @Suppress("unused")
  fun getModifiedHiddenFalsePositives(
    hiddenFalsePositives: Set<HiddenFalsePositive>,
  ): Map<String, Set<HiddenFalsePositive>> {
    val allHiddenFalsePositives =
      HashMap<String, Set<HiddenFalsePositive>>(this._allHiddenFalsePositives ?: emptyMap())
    allHiddenFalsePositives[this.languageShortCode] = hiddenFalsePositives
    return allHiddenFalsePositives
  }

  enum class CheckFrequency {
    Edit,
    Save,
    Manual,
  }

  @Suppress("TooManyFunctions", "LargeClass")
  companion object {
    val DEFAULT_ENABLED =
      setOf(
        "bibtex",
        "latex",
        "git-commit",
        "html",
        "markdown",
        "mdx",
        "neorg",
        "org",
        "quarto",
        "rmd",
        "restructuredtext",
        "rsweave",
        "typst",
        "asciidoc",
      )

    // 0 disables LanguageTool's internal per-sentence cache; superseded by the
    // per-paragraph FragmentCache (see LanguageToolJavaInterface.resultCache).
    private const val DEFAULT_SENTENCE_CACHE_SIZE = 0L
    private const val DEFAULT_PARAGRAPH_CACHE_TTL_MINUTES = 30L

    // Caps the plain-text characters sent to LanguageTool per request (a run of
    // contiguous changed paragraphs is split across requests if it exceeds this;
    // a single paragraph is never split). 20000 is the free HTTP API's
    // per-request character limit, so this default is safe for every backend:
    // free HTTP (at the limit), Premium HTTP (limit is 60000), and the local
    // Java backend (no limit). Future: detect Premium (api.languagetoolplus.com
    // + credentials) and raise the effective cap to 60000.
    private const val DEFAULT_MAX_REQUEST_SIZE = 20000
    private val DEFAULT_DIAGNOSTIC_SEVERITY: Map<String, DiagnosticSeverity> =
      mapOf(Pair("default", DiagnosticSeverity.Information))
    val DEFAULT_PREFERRED_VARIANTS: List<String> = listOf("en-US", "de-DE", "pt-BR")

    // Prefix marking a setting entry as an external file path (LTeX convention).
    private const val EXTERNAL_FILE_PREFIX = ":"

    @Suppress("LongMethod")
    fun fromJson(
      jsonSettings: JsonElement,
      jsonWorkspaceSpecificSettings: JsonElement? = null,
      expandExternalFiles: Boolean = false,
    ): Settings {
      val jsonWorkspaceSpecificSettings2 = jsonWorkspaceSpecificSettings ?: jsonSettings

      val enabled: Set<String>? = getEnabledFromJson(jsonSettings)
      val languageShortCode: String? =
        getSettingFromJsonAsString(jsonSettings, "language")?.let { normalizeLanguageShortCode(it) }
      // When the server owns external files (client opted out), ":"-prefixed
      // entries in these settings are resolved here: the file's contents are
      // spliced in and the first file per language is recorded (below) so the
      // server-side quick fixes know where to write. Otherwise the values pass
      // through verbatim, exactly as before.
      val dictionaryFiles = HashMap<String, String>()
      val disabledRulesFiles = HashMap<String, String>()
      val enabledRulesFiles = HashMap<String, String>()
      val hiddenFalsePositivesFiles = HashMap<String, String>()

      val allDictionaries: Map<String, Set<String>>? =
        expandStringSetting(
          mergeMapOfListsIntoMapOfSets(
            convertJsonObjectToMapOfLists(
              getSettingFromJsonAsJsonObject(jsonWorkspaceSpecificSettings2, "dictionary"),
            ),
          ),
          expandExternalFiles,
          dictionaryFiles,
        )
      val allDisabledRules: Map<String, Set<String>>? =
        expandStringSetting(
          mergeMapOfListsIntoMapOfSets(
            convertJsonObjectToMapOfLists(
              getSettingFromJsonAsJsonObject(jsonWorkspaceSpecificSettings2, "disabledRules"),
            ),
          ),
          expandExternalFiles,
          disabledRulesFiles,
        )
      val allEnabledRules: Map<String, Set<String>>? =
        expandStringSetting(
          mergeMapOfListsIntoMapOfSets(
            convertJsonObjectToMapOfLists(
              getSettingFromJsonAsJsonObject(jsonWorkspaceSpecificSettings2, "enabledRules"),
            ),
          ),
          expandExternalFiles,
          enabledRulesFiles,
        )
      val allHiddenFalsePositives: Map<String, Set<HiddenFalsePositive>>? =
        getAllHiddenFalsePositivesFromJson(
          jsonWorkspaceSpecificSettings2,
          expandExternalFiles,
          hiddenFalsePositivesFiles,
        )

      val externalSettingFiles: Map<String, Map<String, String>>? =
        collectExternalSettingFiles(
          expandExternalFiles,
          mapOf(
            "dictionary" to dictionaryFiles,
            "disabledRules" to disabledRulesFiles,
            "enabledRules" to enabledRulesFiles,
            "hiddenFalsePositives" to hiddenFalsePositivesFiles,
          ),
        )
      val bibtexFields: Map<String, Boolean>? =
        convertJsonObjectToMapOfBooleans(
          getSettingFromJsonAsJsonObject(jsonSettings, "bibtex.fields"),
        )
      val latexCommands: Map<String, String>? =
        convertJsonObjectToMapOfStrings(
          getSettingFromJsonAsJsonObject(jsonSettings, "latex.commands"),
        )
      val latexEnvironments: Map<String, String>? =
        convertJsonObjectToMapOfStrings(
          getSettingFromJsonAsJsonObject(jsonSettings, "latex.environments"),
        )
      val markdownNodes: Map<String, String>? =
        convertJsonObjectToMapOfStrings(
          getSettingFromJsonAsJsonObject(jsonSettings, "markdown.nodes"),
        )
      val enablePickyRules: Boolean? =
        getSettingFromJsonAsBoolean(jsonSettings, "additionalRules.enablePickyRules")
      val motherTongueShortCode: String? =
        getSettingFromJsonAsString(jsonSettings, "additionalRules.motherTongue")
      val preferredVariants: List<String>? =
        getSettingFromJsonAsJsonElement(jsonSettings, "preferredVariants")
          ?.takeIf { it.isJsonArray }
          ?.asJsonArray
          ?.let { convertJsonArrayToList(it) }
          ?.map { canonicalizeLanguageTag(it) }
      val languageModelRulesDirectory: String? =
        getSettingFromJsonAsString(jsonSettings, "additionalRules.languageModel")

      val languageToolHttpServerUri: String? =
        getSettingFromJsonAsString(
          jsonSettings,
          "languageToolHttpServerUri",
        ).let {
          if (it?.isNotEmpty() == true) {
            it
          } else {
            // deprecated in 14.1.0
            getSettingFromJsonAsString(jsonSettings, "ltex-ls.languageToolHttpServerUri")
          }
        }

      val languageToolOrgUsername: String? =
        getSettingFromJsonAsString(
          jsonSettings,
          "languageToolOrg.username",
        ).let {
          if (it?.isNotEmpty() == true) {
            it
          } else {
            // deprecated in 14.1.0
            getSettingFromJsonAsString(jsonSettings, "ltex-ls.languageToolOrgUsername")
          }
        }

      val languageToolOrgApiKey: String? =
        getSettingFromJsonAsString(
          jsonSettings,
          "languageToolOrg.apiKey",
        ).let {
          if (it?.isNotEmpty() == true) {
            it
          } else {
            // deprecated in 14.1.0
            getSettingFromJsonAsString(jsonSettings, "ltex-ls.languageToolOrgApiKey")
          }
        }

      val logLevel: Level? =
        getSettingFromJsonAsEnum(
          jsonSettings,
          "ltex-ls.logLevel",
          arrayOf(
            Level.SEVERE,
            Level.WARNING,
            Level.INFO,
            Level.CONFIG,
            Level.FINE,
            Level.FINER,
            Level.FINEST,
          ),
        )
      val sentenceCacheSize: Long? = getSettingFromJsonAsLong(jsonSettings, "sentenceCacheSize")
      val completionEnabled: Boolean? =
        getSettingFromJsonAsBoolean(jsonSettings, "completionEnabled")
      val diagnosticSeverity: Map<String, DiagnosticSeverity>? =
        getDiagnosticSeverityFromJson(jsonSettings)
      val checkFrequency: CheckFrequency? =
        getSettingFromJsonAsEnum(
          jsonSettings,
          "checkFrequency",
          CheckFrequency::class.java.enumConstants,
        )
      val clearDiagnosticsWhenClosingFile: Boolean? =
        getSettingFromJsonAsBoolean(jsonSettings, "clearDiagnosticsWhenClosingFile")
      val paragraphCacheEnabled: Boolean? =
        getSettingFromJsonAsBoolean(jsonSettings, "paragraphCacheEnabled")
      val paragraphCacheTtlMinutes: Long? =
        getSettingFromJsonAsLong(jsonSettings, "paragraphCacheTtlMinutes")
      val maxRequestSize: Int? =
        getSettingFromJsonAsLong(jsonSettings, "maxRequestSize")?.toInt()

      return Settings(
        enabled,
        languageShortCode,
        allDictionaries,
        allDisabledRules,
        allEnabledRules,
        allHiddenFalsePositives,
        bibtexFields,
        latexCommands,
        latexEnvironments,
        markdownNodes,
        enablePickyRules,
        motherTongueShortCode,
        preferredVariants,
        languageModelRulesDirectory,
        languageToolHttpServerUri,
        languageToolOrgUsername,
        languageToolOrgApiKey,
        logLevel,
        sentenceCacheSize,
        completionEnabled,
        diagnosticSeverity,
        checkFrequency,
        clearDiagnosticsWhenClosingFile,
        paragraphCacheEnabled,
        paragraphCacheTtlMinutes,
        maxRequestSize,
        externalSettingFiles,
      )
    }

    fun normalizeLanguageShortCode(raw: String): String {
      val trimmed = raw.trim()
      if (trimmed.equals("auto", ignoreCase = true)) return "auto"

      val canonical = canonicalizeLanguageTag(trimmed)

      if (Languages.isLanguageSupported(canonical)) return canonical

      // The user asked for a regional variant (e.g. "fr-FR") that the bundled
      // LanguageTool does not register as a distinct tag, but it does register
      // the bare language (e.g. "fr"). This is the normal case for languages
      // that LT ships without regional splits (French, Italian, Spanish,
      // Swedish, ...): the bare code IS the full checker and provides both
      // spelling and grammar — the user loses nothing.
      //
      // The edge case is a typo on a language that DOES have registered
      // variants (e.g. "en-YZ" -> "en", "de-XX" -> "de"): there the bare code
      // is a grammar-only umbrella class with no spell dictionary. The log
      // line covers both cases neutrally so the user can tell what was used.
      val base = canonical.substringBefore("-")
      if (base != canonical && Languages.isLanguageSupported(base)) {
        Logging.LOGGER.info(I18n.format("demotedLanguageToBase", canonical, base))
        return base
      }

      return canonical
    }

    // Canonicalises a BCP-47-ish tag to
    // `<lang-lowercase>[-<REGION-uppercase>][-<rest-lowercase>]` but does NOT apply any
    // "is this supported by LanguageTool" demotion. Use this for inputs where variant
    // form must be preserved (e.g. ltex.preferredVariants entries that are sent to the
    // LT server for post-detection disambiguation). The rest-tail is lowercased to
    // match the registered LanguageTool form for variant subtags like `valencia` in
    // `ca-ES-valencia`.
    fun canonicalizeLanguageTag(raw: String): String {
      val trimmed = raw.trim()
      return LANGUAGE_TAG_REGEX.matchEntire(trimmed)?.destructured?.let { (lang, region, rest) ->
        val regionPart = if (region.isEmpty()) "" else "-${region.uppercase()}"
        "${lang.lowercase()}$regionPart${rest.lowercase()}"
      } ?: trimmed
    }

    // If `shortCode` is a bare language (no region), returns the first element of
    // `preferredVariants` whose base language matches. Otherwise returns `shortCode`
    // unchanged. Used to recover a concrete variant when ltex.language="auto" and the
    // local LanguageTool detector returned only a bare code like "en" — without a
    // variant, LT's spell-checker silently does nothing for English/German/Portuguese.
    fun promoteToPreferredVariant(
      shortCode: String,
      preferredVariants: List<String>,
    ): String {
      if (shortCode.contains('-')) return shortCode
      return preferredVariants.firstOrNull { it.substringBefore('-') == shortCode } ?: shortCode
    }

    // Merge semantics for ltex.preferredVariants: user entries override defaults at the
    // base-language level (last-wins within the merged result), new bases are appended.
    // This prevents a user from accidentally dropping a variant for a spell-check-
    // requiring language (en/de/pt) by writing a narrower list — e.g. setting
    // `["en-GB"]` gives them `["en-GB", "de-DE", "pt-BR"]` rather than stripping
    // German/Portuguese coverage and inviting bare codes in the server response. Bare
    // user entries (no dash) are dropped because LT's /check endpoint rejects them with
    // a BadRequestException.
    fun mergePreferredVariants(user: List<String>?): List<String> {
      val byBase = LinkedHashMap<String, String>()
      for (variant in DEFAULT_PREFERRED_VARIANTS) {
        byBase[variant.substringBefore('-')] = variant
      }
      if (user != null) {
        for (variant in user) {
          if (!variant.contains('-')) continue
          byBase[variant.substringBefore('-')] = variant
        }
      }
      return byBase.values.toList()
    }

    private val LANGUAGE_TAG_REGEX: Regex =
      Regex("""^([A-Za-z]{2,3})(?:-([A-Za-z]{2}))?(-.*)?$""")

    // Matches a setting whose *entire* value is a single environment-variable
    // reference, e.g. "${LANGUAGETOOL_API_KEY}". Whole-value only (no inline
    // interpolation) so there is nothing to escape and a literal value that
    // merely contains "${" is never mangled. The captured name is restricted
    // to the POSIX-portable identifier charset.
    private val ENVIRONMENT_VARIABLE_REFERENCE_REGEX: Regex =
      Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)}$""")

    /**
     * Resolves a setting value of the form `${ENV_VAR}` to the value of that
     * environment variable, looked up via [System.getenv] (never a shell, so
     * there is no injection surface). Any other value is returned verbatim.
     * A reference to an unset/empty variable resolves to the empty string and
     * logs a warning, so a misconfiguration degrades to "no credentials"
     * (anonymous checking) rather than sending the literal placeholder to the
     * LanguageTool server.
     */
    private fun resolveEnvironmentVariableReference(value: String): String {
      val name: String =
        ENVIRONMENT_VARIABLE_REFERENCE_REGEX.matchEntire(value)?.groupValues?.get(1) ?: return value
      val resolved: String? = System.getenv(name)

      if (resolved.isNullOrEmpty()) {
        Logging.LOGGER.warning(I18n.format("environmentVariableNotSet", name))
        return ""
      }

      return resolved
    }

    // Keeps only the non-empty per-setting file maps (settingName -> language ->
    // path), or null if none / not expanding. Recorded during expansion so the
    // server-side quick fixes know where to write.
    private fun collectExternalSettingFiles(
      expand: Boolean,
      filesBySetting: Map<String, Map<String, String>>,
    ): Map<String, Map<String, String>>? =
      if (expand) filesBySetting.filterValues { it.isNotEmpty() }.ifEmpty { null } else null

    // Resolves ":"-prefixed external files for a string-valued, language-keyed
    // setting (dictionary / disabledRules / enabledRules). When expanding, a ":"
    // entry is replaced by the file's lines (the marker itself is dropped), and
    // the first external file per language is recorded into `fileAccumulator` so
    // the server-side quick fixes know where to write. Not expanding → verbatim.
    private fun expandStringSetting(
      raw: Map<String, Set<String>>?,
      expand: Boolean,
      fileAccumulator: MutableMap<String, String>,
    ): Map<String, Set<String>>? {
      if (!expand || (raw == null)) return raw

      return raw.mapValues { (language: String, entries: Set<String>) ->
        val expanded = LinkedHashSet<String>()

        for (entry: String in entries) {
          if (entry.startsWith(EXTERNAL_FILE_PREFIX)) {
            recordAndReadExternalFile(entry, language, fileAccumulator) { expanded.add(it) }
          } else {
            expanded.add(entry)
          }
        }

        expanded
      }
    }

    // Records the first external file per language into `fileAccumulator` (even if
    // the file does not exist yet — the quick fix creates it on write), then feeds
    // each trimmed, non-empty line of an existing file to `consume`. A leading "~"
    // is expanded; the demo assumes "~"/absolute paths (no workspace-root resolution).
    private fun recordAndReadExternalFile(
      entry: String,
      language: String,
      fileAccumulator: MutableMap<String, String>,
      consume: (String) -> Unit,
    ) {
      val normalizedPath: String =
        FileIo.normalizePath(entry.substring(EXTERNAL_FILE_PREFIX.length))
      if (!fileAccumulator.containsKey(language)) fileAccumulator[language] = normalizedPath

      val path = Paths.get(normalizedPath)
      if (!Files.exists(path)) return

      FileIo.readFile(path)?.lines()?.forEach { line: String ->
        val trimmed: String = line.trim()
        if (trimmed.isNotEmpty()) consume(trimmed)
      }
    }

    // Parses a single hiddenFalsePositives entry, returning null (with a warning)
    // instead of throwing on anything malformed — a raw ":" marker that was not
    // expanded (e.g. a misconfigured client), broken JSON, or the wrong shape. So
    // one bad entry is skipped rather than breaking the whole check.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseHiddenFalsePositiveOrWarn(jsonString: String): HiddenFalsePositive? =
      try {
        HiddenFalsePositive.fromJsonString(jsonString)
      } catch (e: RuntimeException) {
        Logging.LOGGER.warning(I18n.format("ignoringMalformedHiddenFalsePositive", jsonString))
        null
      }

    private fun getEnabledFromJson(jsonSettings: JsonElement): Set<String>? {
      val jsonElement: JsonElement? = getSettingFromJsonAsJsonElement(jsonSettings, "enabled")

      return if (jsonElement == null) {
        null
      } else if (jsonElement.isJsonArray) {
        convertJsonArrayToSet(jsonElement.asJsonArray)
      } else if (jsonElement.isJsonPrimitive) {
        val jsonPrimitive: JsonPrimitive = jsonElement.asJsonPrimitive

        if (jsonPrimitive.isBoolean) {
          if (jsonPrimitive.asBoolean) DEFAULT_ENABLED else emptySet()
        } else {
          null
        }
      } else {
        null
      }
    }

    private fun getAllHiddenFalsePositivesFromJson(
      jsonWorkspaceSpecificSettings: JsonElement,
      expand: Boolean,
      fileAccumulator: MutableMap<String, String>,
    ): Map<String, Set<HiddenFalsePositive>>? {
      val objectMap: Map<String, Set<JsonObject>>? =
        convertJsonObjectToMapOfJsonObjects(
          getSettingFromJsonAsJsonObject(jsonWorkspaceSpecificSettings, "hiddenFalsePositives"),
        )
      val stringMap: Map<String, Set<String>>? =
        mergeMapOfListsIntoMapOfSets(
          convertJsonObjectToMapOfLists(
            getSettingFromJsonAsJsonObject(jsonWorkspaceSpecificSettings, "hiddenFalsePositives"),
          ),
        )

      if ((objectMap == null) && (stringMap == null)) return null

      val hiddenFalsePositivesMap = HashMap<String, HashSet<HiddenFalsePositive>>()

      objectMap?.forEach { (language: String, jsonObjectSet: Set<JsonObject>) ->
        val set: HashSet<HiddenFalsePositive> =
          hiddenFalsePositivesMap.getOrPut(language) { HashSet() }
        jsonObjectSet.forEach { set.add(HiddenFalsePositive.fromJsonObject(it)) }
      }

      stringMap?.forEach { (language: String, stringSet: Set<String>) ->
        val set: HashSet<HiddenFalsePositive> =
          hiddenFalsePositivesMap.getOrPut(language) { HashSet() }
        stringSet.forEach { entry: String ->
          addHiddenFalsePositiveStringEntry(entry, language, expand, fileAccumulator, set)
        }
      }

      return hiddenFalsePositivesMap
    }

    // A hiddenFalsePositives string entry is either a JSON object (the usual case)
    // or, when the server manages external files, a ":"-prefixed file whose lines
    // are each such a JSON object. Both routes go through the hardened parser so a
    // malformed/unexpanded entry is skipped, not thrown.
    private fun addHiddenFalsePositiveStringEntry(
      entry: String,
      language: String,
      expand: Boolean,
      fileAccumulator: MutableMap<String, String>,
      set: HashSet<HiddenFalsePositive>,
    ) {
      if (expand && entry.startsWith(EXTERNAL_FILE_PREFIX)) {
        recordAndReadExternalFile(entry, language, fileAccumulator) { line: String ->
          parseHiddenFalsePositiveOrWarn(line)?.let { set.add(it) }
        }
      } else {
        parseHiddenFalsePositiveOrWarn(entry)?.let { set.add(it) }
      }
    }

    private fun getDiagnosticSeverityFromJson(
      jsonSettings: JsonElement,
    ): Map<String, DiagnosticSeverity>? {
      val jsonElement: JsonElement? =
        getSettingFromJsonAsJsonElement(jsonSettings, "diagnosticSeverity")

      return if (jsonElement == null) {
        null
      } else if (jsonElement.isJsonObject) {
        convertJsonObjectToMapOfEnums(
          jsonElement.asJsonObject,
          DiagnosticSeverity::class.java.enumConstants,
        )
      } else if (jsonElement.isJsonPrimitive) {
        val jsonPrimitive: JsonPrimitive = jsonElement.asJsonPrimitive

        if (jsonPrimitive.isString) {
          val enumValue: DiagnosticSeverity? =
            convertStringToEnum(
              jsonPrimitive.asString,
              DiagnosticSeverity::class.java.enumConstants,
            )
          if (enumValue != null) mapOf(Pair("default", enumValue)) else null
        } else {
          null
        }
      } else {
        null
      }
    }

    private fun <T> getSettingFromJsonAsEnum(
      jsonSettings: JsonElement,
      name: String,
      enumValues: Array<T>,
    ): T? {
      val enumString: String = getSettingFromJsonAsString(jsonSettings, name) ?: return null
      return convertStringToEnum(enumString, enumValues)
    }

    private fun getSettingFromJsonAsJsonElement(
      jsonSettings: JsonElement,
      name: String,
    ): JsonElement? {
      if (!jsonSettings.isJsonObject) return null
      var curJsonSettings: JsonElement? = jsonSettings

      for (component: String in name.split(".")) {
        curJsonSettings =
          if ((curJsonSettings != null) && curJsonSettings.isJsonObject) {
            val curJsonSettingsObject: JsonObject = curJsonSettings.asJsonObject

            if (curJsonSettingsObject.has(component)) {
              curJsonSettingsObject.get(component)
            } else {
              null
            }
          } else {
            null
          }

        if (curJsonSettings == null) break
      }

      return if (curJsonSettings != null) {
        curJsonSettings
      } else {
        val jsonSettingsObject: JsonObject = jsonSettings.asJsonObject

        if (jsonSettingsObject.has(name)) {
          jsonSettingsObject.get(name)
        } else if (!name.startsWith("ltex.")) {
          getSettingFromJsonAsJsonElement(jsonSettings, "ltex.$name")
        } else {
          null
        }
      }
    }

    private fun getSettingFromJsonAsJsonObject(
      jsonSettings: JsonElement,
      name: String,
    ): JsonObject? {
      val jsonElement: JsonElement? = getSettingFromJsonAsJsonElement(jsonSettings, name)

      return if ((jsonElement != null) && jsonElement.isJsonObject) {
        jsonElement.asJsonObject
      } else {
        null
      }
    }

    private fun getSettingFromJsonAsJsonPrimitive(
      jsonSettings: JsonElement,
      name: String,
    ): JsonPrimitive? {
      val jsonElement: JsonElement? = getSettingFromJsonAsJsonElement(jsonSettings, name)

      return if ((jsonElement != null) && jsonElement.isJsonPrimitive) {
        jsonElement.asJsonPrimitive
      } else {
        null
      }
    }

    private fun getSettingFromJsonAsBoolean(
      jsonSettings: JsonElement,
      name: String,
    ): Boolean? {
      val jsonPrimitive: JsonPrimitive? = getSettingFromJsonAsJsonPrimitive(jsonSettings, name)

      return if ((jsonPrimitive != null) && jsonPrimitive.isBoolean) {
        jsonPrimitive.asBoolean
      } else {
        null
      }
    }

    private fun getSettingFromJsonAsLong(
      jsonSettings: JsonElement,
      name: String,
    ): Long? {
      val jsonPrimitive: JsonPrimitive? = getSettingFromJsonAsJsonPrimitive(jsonSettings, name)

      return if ((jsonPrimitive != null) && jsonPrimitive.isNumber) {
        jsonPrimitive.asLong
      } else {
        null
      }
    }

    private fun getSettingFromJsonAsString(
      jsonSettings: JsonElement,
      name: String,
    ): String? {
      val jsonPrimitive: JsonPrimitive? = getSettingFromJsonAsJsonPrimitive(jsonSettings, name)

      return if ((jsonPrimitive != null) && jsonPrimitive.isString) {
        jsonPrimitive.asString
      } else {
        null
      }
    }

    private fun convertJsonArrayToList(array: JsonArray?): List<String>? {
      if (array == null) return null
      val list = ArrayList<String>()

      for (element: JsonElement in array) {
        if (!element.isJsonPrimitive) return null
        val value: JsonPrimitive = element.asJsonPrimitive
        if (!value.isString) return null
        list.add(value.asString)
      }

      return list
    }

    private fun convertJsonArrayToSet(array: JsonArray?): Set<String>? {
      if (array == null) return null
      val set = HashSet<String>()

      for (element: JsonElement in array) {
        if (!element.isJsonPrimitive) return null
        val value: JsonPrimitive = element.asJsonPrimitive
        if (!value.isString) return null
        set.add(value.asString)
      }

      return set
    }

    private fun convertJsonObjectToMapOfStrings(obj: JsonObject?): Map<String, String>? {
      if (obj == null) return null
      val map = HashMap<String, String>()

      for (entry: Map.Entry<String, JsonElement> in obj.entrySet()) {
        if (!entry.value.isJsonPrimitive) return null
        val value: JsonPrimitive = entry.value.asJsonPrimitive
        if (!value.isString) return null
        map[entry.key] = entry.value.asString
      }

      return map
    }

    private fun convertJsonObjectToMapOfBooleans(obj: JsonObject?): Map<String, Boolean>? {
      if (obj == null) return null
      val map = HashMap<String, Boolean>()

      for (entry: Map.Entry<String, JsonElement> in obj.entrySet()) {
        if (!entry.value.isJsonPrimitive) return null
        val value: JsonPrimitive = entry.value.asJsonPrimitive
        if (!value.isBoolean) return null
        map[entry.key] = value.asBoolean
      }

      return map
    }

    private fun convertJsonObjectToMapOfLists(obj: JsonObject?): Map<String, List<String>>? {
      if (obj == null) return null
      val map = HashMap<String, List<String>>()

      for (entry: Map.Entry<String, JsonElement> in obj.entrySet()) {
        if (!entry.value.isJsonArray) return null
        val list: List<String> = convertJsonArrayToList(entry.value.asJsonArray) ?: return null
        map[entry.key] = list
      }

      return map
    }

    private fun <T> convertJsonObjectToMapOfEnums(
      obj: JsonObject?,
      enumValues: Array<T>,
    ): Map<String, T>? {
      if (obj == null) return null
      val map = HashMap<String, T>()

      for (entry: Map.Entry<String, JsonElement> in obj.entrySet()) {
        val enumValue: T? = convertJsonElementToEnum(entry.value, enumValues)
        if (enumValue != null) map[entry.key] = enumValue
      }

      return map
    }

    private fun convertJsonObjectToMapOfJsonObjects(
      obj: JsonObject?,
    ): Map<String, Set<JsonObject>>? {
      if (obj == null) return null
      val map = HashMap<String, Set<JsonObject>>()

      for (entry: Map.Entry<String, JsonElement> in obj.entrySet()) {
        if (!entry.value.isJsonArray) return null
        val set = HashSet<JsonObject>()

        for (element: JsonElement in entry.value.asJsonArray) {
          if (!element.isJsonObject) return null
          set.add(element.asJsonObject)
        }

        map[entry.key] = set
      }

      return map
    }

    private fun <T> convertJsonElementToEnum(
      jsonElement: JsonElement,
      enumValues: Array<T>,
    ): T? =
      if (jsonElement.isJsonPrimitive) {
        val jsonPrimitive: JsonPrimitive = jsonElement.asJsonPrimitive

        if (jsonPrimitive.isString) {
          convertStringToEnum(jsonPrimitive.asString, enumValues)
        } else {
          null
        }
      } else {
        null
      }

    private fun <T> convertStringToEnum(
      enumString: String,
      enumValues: Array<T>,
    ): T? {
      for (enumValue: T in enumValues) {
        if (enumValue.toString().equals(enumString, ignoreCase = true)) return enumValue
      }

      return null
    }

    private fun mergeMapOfListsIntoMapOfSets(
      mapOfLists: Map<String, List<String>>?,
    ): Map<String, Set<String>>? {
      if (mapOfLists == null) return null
      val mapOfSets = HashMap<String, HashSet<String>>()

      for ((key: String, set2: List<String>) in mapOfLists) {
        val set1 = HashSet<String>()

        for (string: String in set2) {
          if (string.startsWith("-")) {
            set1.remove(string.substring(1))
          } else {
            set1.add(string)
          }
        }

        mapOfSets[key] = set1
      }

      return mapOfSets
    }
  }
}
