/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.eclipse.lsp4j.DiagnosticSeverity
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettingsTest {
  @Test
  @Suppress("LongMethod")
  fun testProperties() {
    var settings = Settings()
    var settings2 = Settings()

    settings = settings.copy(_enabled = setOf("markdown"))
    assertEquals(setOf("markdown"), settings.enabled)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_languageShortCode = "languageShortCode")
    assertEquals("languageShortCode", settings.languageShortCode)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_allDictionaries = settings.getModifiedDictionary(setOf("dictionary")))
    assertEquals(setOf("dictionary"), settings.dictionary)
    settings2 = compareSettings(settings, settings2, false)

    settings =
      settings.copy(
        _allDisabledRules = settings.getModifiedDisabledRules(setOf("disabledRules")),
      )
    assertEquals(setOf("disabledRules"), settings.disabledRules)
    settings2 = compareSettings(settings, settings2, false)

    settings =
      settings.copy(
        _allEnabledRules = settings.getModifiedEnabledRules(setOf("enabledRules")),
      )
    assertEquals(setOf("enabledRules"), settings.enabledRules)
    settings2 = compareSettings(settings, settings2, true)

    settings =
      settings.copy(
        _allHiddenFalsePositives =
          settings.getModifiedHiddenFalsePositives(
            setOf(HiddenFalsePositive("ruleId", "sentenceString")),
          ),
      )
    assertEquals(
      setOf(HiddenFalsePositive("ruleId", "sentenceString")),
      settings.hiddenFalsePositives,
    )
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_bibtexFields = mapOf(Pair("bibtexField", false)))
    assertEquals(mapOf(Pair("bibtexField", false)), settings.bibtexFields)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_latexCommands = mapOf(Pair("latexCommand", "ignore")))
    assertEquals(mapOf(Pair("latexCommand", "ignore")), settings.latexCommands)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_latexEnvironments = mapOf(Pair("latexEnvironment", "ignore")))
    assertEquals(mapOf(Pair("latexEnvironment", "ignore")), settings.latexEnvironments)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_markdownNodes = mapOf(Pair("markdownNode", "ignore")))
    assertEquals(mapOf(Pair("markdownNode", "ignore")), settings.markdownNodes)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_enablePickyRules = true)
    assertEquals(true, settings.enablePickyRules)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_motherTongueShortCode = "motherTongueShortCode")
    assertEquals("motherTongueShortCode", settings.motherTongueShortCode)
    settings2 = compareSettings(settings, settings2, true)

    // User entries merge with DEFAULT_PREFERRED_VARIANTS keyed by base language
    // (last-wins per base). The user-provided en-GB and de-AT override en-US and de-DE
    // respectively; pt-BR is not overridden so it is preserved.
    settings = settings.copy(_preferredVariants = listOf("en-GB", "de-AT"))
    assertEquals(listOf("en-GB", "de-AT", "pt-BR"), settings.preferredVariants)
    settings2 = compareSettings(settings, settings2, true)

    settings = settings.copy(_languageModelRulesDirectory = "languageModelRulesDirectory")
    assertEquals(
      "languageModelRulesDirectory",
      settings.languageModelRulesDirectory,
    )
    settings2 = compareSettings(settings, settings2, true)

    settings = settings.copy(_languageToolHttpServerUri = "languageToolHttpServerUri")
    assertEquals("languageToolHttpServerUri", settings.languageToolHttpServerUri)
    settings2 = compareSettings(settings, settings2, true)

    settings = settings.copy(_languageToolOrgUsername = "languageToolOrgUsername")
    assertEquals("languageToolOrgUsername", settings.languageToolOrgUsername)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_languageToolOrgApiKey = "languageToolOrgApiKey")
    assertEquals("languageToolOrgApiKey", settings.languageToolOrgApiKey)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_logLevel = Level.FINEST)
    assertEquals(Level.FINEST, settings.logLevel)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_sentenceCacheSize = 1337)
    assertEquals(1337, settings.sentenceCacheSize)
    settings2 = compareSettings(settings, settings2, true)

    settings = settings.copy(_diagnosticSeverity = mapOf(Pair("ruleId", DiagnosticSeverity.Error)))
    assertEquals(mapOf(Pair("ruleId", DiagnosticSeverity.Error)), settings.diagnosticSeverity)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_checkFrequency = Settings.CheckFrequency.Manual)
    assertEquals(Settings.CheckFrequency.Manual, settings.checkFrequency)
    settings2 = compareSettings(settings, settings2, false)

    settings = settings.copy(_clearDiagnosticsWhenClosingFile = false)
    assertEquals(false, settings.clearDiagnosticsWhenClosingFile)
    @Suppress("UNUSED_VALUE")
    settings2 = compareSettings(settings, settings2, false)
  }

  @Test
  fun testTildeExpansion() {
    var settings = Settings()
    val originalDirPath = "~/tildeExpansion"
    settings = settings.copy(_languageModelRulesDirectory = originalDirPath)
    val expandedDirPath = settings.languageModelRulesDirectory
    assertTrue(expandedDirPath.endsWith("tildeExpansion"))
    assertNotEquals(originalDirPath, expandedDirPath)
  }

  @Test
  fun testNormalizeLanguageShortCode() {
    assertEquals("auto", Settings.normalizeLanguageShortCode("auto"))
    assertEquals("auto", Settings.normalizeLanguageShortCode("Auto"))
    assertEquals("auto", Settings.normalizeLanguageShortCode("AUTO"))

    assertEquals("en-US", Settings.normalizeLanguageShortCode("en-US"))
    assertEquals("en-US", Settings.normalizeLanguageShortCode("en-us"))
    assertEquals("en-US", Settings.normalizeLanguageShortCode("EN-US"))
    assertEquals("de-DE", Settings.normalizeLanguageShortCode("de-de"))

    assertEquals("fr", Settings.normalizeLanguageShortCode("fr-FR"))
    assertEquals("fr", Settings.normalizeLanguageShortCode("FR-fr"))
    assertEquals("it", Settings.normalizeLanguageShortCode("it-IT"))

    assertEquals("ca-ES-valencia", Settings.normalizeLanguageShortCode("CA-es-valencia"))
    // The rest-tail (third component, e.g. a variant subtag like `valencia`) is
    // lowercased to match LanguageTool's registered form — otherwise LT's
    // tag-matching could miss the Valencian variant.
    assertEquals("ca-ES-valencia", Settings.normalizeLanguageShortCode("CA-ES-VALENCIA"))
    assertEquals("ca-ES-valencia", Settings.normalizeLanguageShortCode("ca-ES-Valencia"))

    assertEquals("en-US", Settings.normalizeLanguageShortCode("  en-US  "))
    assertEquals("auto", Settings.normalizeLanguageShortCode("  Auto  "))

    assertEquals("zz-ZZ", Settings.normalizeLanguageShortCode("zz-ZZ"))
  }

  @Test
  fun testCanonicalizeLanguageTag() {
    // Variant form must be preserved even when LT does not register that exact variant,
    // because preferredVariants values are forwarded to the LT HTTP server, not resolved
    // locally against Languages.isLanguageSupported.
    assertEquals("en-US", Settings.canonicalizeLanguageTag("en-us"))
    assertEquals("en-US", Settings.canonicalizeLanguageTag("  EN-us  "))
    assertEquals("fr-FR", Settings.canonicalizeLanguageTag("fr-fr"))
    assertEquals("fr-FR", Settings.canonicalizeLanguageTag("FR-FR"))
    // Three-component tags: lang lowercase, region uppercase, rest lowercase.
    // Matches LT's registered form for Valencian (ca-ES-valencia).
    assertEquals("ca-ES-valencia", Settings.canonicalizeLanguageTag("ca-ES-valencia"))
    assertEquals("ca-ES-valencia", Settings.canonicalizeLanguageTag("CA-ES-VALENCIA"))
    assertEquals("ca-ES-valencia", Settings.canonicalizeLanguageTag("ca-es-Valencia"))
    // Non-matching input is returned unchanged after trimming.
    assertEquals("not a tag", Settings.canonicalizeLanguageTag("  not a tag  "))
  }

  @Test
  fun testPromoteToPreferredVariant() {
    val variants = listOf("en-US", "de-DE", "pt-BR")
    // Bare -> matching variant.
    assertEquals("en-US", Settings.promoteToPreferredVariant("en", variants))
    assertEquals("de-DE", Settings.promoteToPreferredVariant("de", variants))
    assertEquals("pt-BR", Settings.promoteToPreferredVariant("pt", variants))
    // Bare without a match stays bare.
    assertEquals("fr", Settings.promoteToPreferredVariant("fr", variants))
    // Already a variant -> untouched (even if a different variant is in the list).
    assertEquals("en-GB", Settings.promoteToPreferredVariant("en-GB", variants))
    // Empty list -> no-op.
    assertEquals("en", Settings.promoteToPreferredVariant("en", emptyList()))
    // First match wins.
    assertEquals(
      "en-GB",
      Settings.promoteToPreferredVariant("en", listOf("en-GB", "en-US")),
    )
  }

  @Test
  fun testDefaultPreferredVariants() {
    assertEquals(listOf("en-US", "de-DE", "pt-BR"), Settings().preferredVariants)
  }

  @Test
  fun testMergePreferredVariants() {
    // Null / missing user input -> defaults unchanged.
    assertEquals(listOf("en-US", "de-DE", "pt-BR"), Settings.mergePreferredVariants(null))

    // Empty user list still yields defaults (explicit "[]" does not mean "strip all" —
    // users cannot lose variant coverage for spell-check-requiring bases).
    assertEquals(listOf("en-US", "de-DE", "pt-BR"), Settings.mergePreferredVariants(emptyList()))

    // Override at the base-language level; unrelated defaults are preserved in order.
    assertEquals(
      listOf("en-GB", "de-DE", "pt-BR"),
      Settings.mergePreferredVariants(listOf("en-GB")),
    )

    // Two overrides at once, third default preserved.
    assertEquals(
      listOf("en-GB", "de-AT", "pt-BR"),
      Settings.mergePreferredVariants(listOf("en-GB", "de-AT")),
    )

    // New base language is appended after defaults.
    assertEquals(
      listOf("en-US", "de-DE", "pt-BR", "nl-NL"),
      Settings.mergePreferredVariants(listOf("nl-NL")),
    )

    // Last user entry wins per base — matches LT server's iteration semantics.
    assertEquals(
      listOf("en-GB", "de-DE", "pt-BR"),
      Settings.mergePreferredVariants(listOf("en-US", "en-GB")),
    )

    // Bare entries are dropped — LT's /check rejects them with BadRequestException.
    assertEquals(
      listOf("en-US", "de-DE", "pt-BR"),
      Settings.mergePreferredVariants(listOf("en")),
    )
  }

  companion object {
    private fun compareSettings(
      settings: Settings,
      otherSettings: Settings,
      differenceRelevant: Boolean,
    ): Settings {
      val settings2: Settings = settings.copy()
      val otherSettings2: Settings = otherSettings.copy()

      assertEquals(settings.hashCode(), settings2.hashCode())
      assertEquals(otherSettings.hashCode(), otherSettings2.hashCode())

      assertEquals(settings2, settings)
      assertEquals(settings, settings2)
      assertEquals(otherSettings2, otherSettings)
      assertEquals(otherSettings, otherSettings2)
      assertNotEquals(otherSettings, settings)
      assertNotEquals(settings, otherSettings)
      assertNotEquals(otherSettings, settings2)
      assertNotEquals(settings2, otherSettings)
      assertNotEquals(otherSettings2, settings)
      assertNotEquals(settings, otherSettings2)
      assertNotEquals(otherSettings2, settings2)
      assertNotEquals(settings2, otherSettings2)

      assertTrue(settings.getDifferencesRelevantForLanguageTool(settings2).isEmpty())
      assertTrue(settings2.getDifferencesRelevantForLanguageTool(settings).isEmpty())
      assertTrue(otherSettings.getDifferencesRelevantForLanguageTool(otherSettings2).isEmpty())
      assertTrue(otherSettings2.getDifferencesRelevantForLanguageTool(otherSettings).isEmpty())
      assertEquals(
        !differenceRelevant,
        settings.getDifferencesRelevantForLanguageTool(otherSettings).isEmpty(),
      )
      assertEquals(
        !differenceRelevant,
        otherSettings.getDifferencesRelevantForLanguageTool(settings).isEmpty(),
      )
      assertEquals(
        !differenceRelevant,
        settings2.getDifferencesRelevantForLanguageTool(otherSettings).isEmpty(),
      )
      assertEquals(
        !differenceRelevant,
        otherSettings.getDifferencesRelevantForLanguageTool(settings2).isEmpty(),
      )
      assertEquals(
        !differenceRelevant,
        settings.getDifferencesRelevantForLanguageTool(otherSettings2).isEmpty(),
      )
      assertEquals(
        !differenceRelevant,
        otherSettings2.getDifferencesRelevantForLanguageTool(settings).isEmpty(),
      )
      assertEquals(
        !differenceRelevant,
        settings2.getDifferencesRelevantForLanguageTool(otherSettings2).isEmpty(),
      )
      assertEquals(
        !differenceRelevant,
        otherSettings2.getDifferencesRelevantForLanguageTool(settings2).isEmpty(),
      )

      return settings2
    }
  }

  @Test
  @Suppress("LongMethod")
  fun testFromJson() {
    val bibtexFields = JsonObject()
    bibtexFields.addProperty("bibtexField", false)

    val bibtex = JsonObject()
    bibtex.add("fields", bibtexFields)

    val latexEnvironments = JsonObject()
    latexEnvironments.addProperty("latexEnvironment", "ignore")

    val latexCommands = JsonObject()
    latexCommands.addProperty("\\latexCommand{}", "ignore")

    val latex = JsonObject()
    latex.add("commands", latexCommands)
    latex.add("environments", latexEnvironments)

    val markdownNodes = JsonObject()
    markdownNodes.addProperty("markdownNode", "ignore")

    val markdown = JsonObject()
    markdown.add("nodes", markdownNodes)

    val additionalRules = JsonObject()
    additionalRules.addProperty("enablePickyRules", true)

    val jsonSettings = JsonObject()
    jsonSettings.addProperty("enabled", false)
    jsonSettings.add("bibtex", bibtex)
    jsonSettings.add("latex", latex)
    jsonSettings.add("markdown", markdown)
    jsonSettings.add("additionalRules", additionalRules)

    val englishDictionary = JsonArray()
    englishDictionary.add("wordone")
    englishDictionary.add("wordtwo")
    englishDictionary.add("-wordone")

    val dictionary = JsonObject()
    dictionary.add("en-US", englishDictionary)

    val hiddenFalsePositiveRule = JsonObject()
    hiddenFalsePositiveRule.addProperty("rule", "rule")
    hiddenFalsePositiveRule.addProperty("sentence", "sentence")

    val englishHiddenFalsePositives = JsonArray()
    englishHiddenFalsePositives.add(hiddenFalsePositiveRule)

    var hiddenFalsePositives = JsonObject()
    hiddenFalsePositives.add("en-US", englishHiddenFalsePositives)

    var jsonWorkspaceSpecificSettings = JsonObject()
    jsonWorkspaceSpecificSettings.add("dictionary", dictionary)
    jsonWorkspaceSpecificSettings.add("hiddenFalsePositives", hiddenFalsePositives)

    var settings: Settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(emptySet<Any>(), settings.enabled)
    assertEquals(setOf("wordtwo"), settings.dictionary)
    assertEquals(setOf(HiddenFalsePositive("rule", "sentence")), settings.hiddenFalsePositives)
    assertEquals(mapOf(Pair("bibtexField", false)), settings.bibtexFields)
    assertEquals(mapOf(Pair("\\latexCommand{}", "ignore")), settings.latexCommands)
    assertEquals(mapOf(Pair("latexEnvironment", "ignore")), settings.latexEnvironments)
    assertEquals(mapOf(Pair("markdownNode", "ignore")), settings.markdownNodes)
    assertEquals(true, settings.enablePickyRules)

    // See https://github.com/ltex-plus/vscode-ltex-plus/issues/165
    val vscodeLTeXJSON = JsonArray()
    vscodeLTeXJSON.add("{\"rule\": \"rule\", \"sentence\": \"sentence\"}")
    hiddenFalsePositives = JsonObject()
    hiddenFalsePositives.add("en-US", vscodeLTeXJSON)
    jsonWorkspaceSpecificSettings = JsonObject()
    jsonWorkspaceSpecificSettings.add("hiddenFalsePositives", hiddenFalsePositives)
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(setOf(HiddenFalsePositive("rule", "sentence")), settings.hiddenFalsePositives)

    jsonSettings.addProperty("diagnosticSeverity", "error")
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(mapOf(Pair("default", DiagnosticSeverity.Error)), settings.diagnosticSeverity)

    jsonSettings.addProperty("diagnosticSeverity", "warning")
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(mapOf(Pair("default", DiagnosticSeverity.Warning)), settings.diagnosticSeverity)

    jsonSettings.addProperty("diagnosticSeverity", "information")
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(
      mapOf(Pair("default", DiagnosticSeverity.Information)),
      settings.diagnosticSeverity,
    )

    jsonSettings.addProperty("diagnosticSeverity", "hint")
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(mapOf(Pair("default", DiagnosticSeverity.Hint)), settings.diagnosticSeverity)

    val diagnosticSeverity = JsonObject()
    diagnosticSeverity.addProperty("ruleId", "warning")
    diagnosticSeverity.addProperty("default", "error")

    jsonSettings.add("diagnosticSeverity", diagnosticSeverity)
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(
      mapOf(Pair("ruleId", DiagnosticSeverity.Warning), Pair("default", DiagnosticSeverity.Error)),
      settings.diagnosticSeverity,
    )

    jsonSettings.addProperty("checkFrequency", "edit")
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(Settings.CheckFrequency.Edit, settings.checkFrequency)

    jsonSettings.addProperty("checkFrequency", "save")
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(Settings.CheckFrequency.Save, settings.checkFrequency)

    jsonSettings.addProperty("checkFrequency", "manual")
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    assertEquals(Settings.CheckFrequency.Manual, settings.checkFrequency)

    val preferredVariantsArray = JsonArray()
    preferredVariantsArray.add("en-gb")
    preferredVariantsArray.add("DE-at")
    jsonSettings.add("preferredVariants", preferredVariantsArray)
    settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    // Each entry is canonicalised (lang lowercase + region uppercase, variant preserved)
    // and merged with DEFAULT_PREFERRED_VARIANTS: en-GB and de-AT override the default
    // en-US and de-DE; the default pt-BR is kept since the user did not specify a
    // Portuguese variant.
    assertEquals(listOf("en-GB", "de-AT", "pt-BR"), settings.preferredVariants)
  }
}
