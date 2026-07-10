/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.server.DocumentChecker
import org.bsplines.ltexls.server.DocumentCheckerTest
import org.bsplines.ltexls.server.LtexTextDocumentItem
import org.bsplines.ltexls.settings.FileSettings
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageToolJavaInterfaceTest {
  @Test
  fun testCheck() {
    assertMatches(Settings(), true)
  }

  @Test
  fun testEasterEgg() {
    val settings = Settings(_allDictionaries = mapOf(Pair("en-US", FileSettings.of("BsPlInEs"))))
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument(
        "latex",
        "Hat functions is for a beginner.\n",
      )
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      documentChecker.check(document)
    assertEquals(3, checkingResult.first.size)
  }

  @Test
  fun testOtherMethods() {
    val settingsManager = SettingsManager()
    val ltInterface: LanguageToolInterface? = settingsManager.languageToolInterface
    assertNotNull(ltInterface)
    ltInterface.activateDefaultFalseFriendRules()
    ltInterface.activateLanguageModelRules("foobar")
    ltInterface.enableEasterEgg()
  }

  @Test
  fun testAutoLanguagePromotesBareCategory1CodeToDefaultVariant() {
    // Tripwire: on the Java backend, ltex.language="auto" runs SimpleLanguageIdentifier
    // locally, which returns bare codes for English/German/Portuguese. DocumentChecker
    // must then promote the bare code to the first matching entry in preferredVariants
    // (default `en-US`) so the Java LanguageToolInterface is built with a variant that
    // carries a spell dictionary (see CLAUDE.md "LanguageTool language-tag quirks").
    // If this goes red, either SimpleLanguageIdentifier changed its return shape, the
    // promotion path in DocumentChecker.kt regressed, or Languages.getLanguageForShortCode
    // ("en-US") stopped mapping to AmericanEnglish.
    val matches =
      checkAutoDocument(
        preferredVariants = null,
        text = "This is a testt sentence with a mispeling.\n",
      )
    assertTrue(
      matches.first.any { it.ruleId == "MORFOLOGIK_RULE_EN_US" },
      "Expected MORFOLOGIK_RULE_EN_US match; got rule ids: " +
        matches.first.mapNotNull { it.ruleId },
    )
    assertEquals(
      "en-US",
      matches.second
        .first()
        .codeFragment.languageShortCode,
    )
  }

  @Test
  fun testAutoLanguageBareCategory2RunsSpellCheckOnJavaBackend() {
    // Tripwire mirror of the HTTP Category 2 test: the local LanguageToolJavaInterface
    // built with bare `es` must still run MORFOLOGIK_RULE_ES — i.e. the bare `Spanish`
    // Language class registered in languagetool-core is a full spell + grammar checker,
    // so DEFAULT_PREFERRED_VARIANTS correctly omits es-*. If this goes red, LT has
    // reclassified Spanish and DEFAULT_PREFERRED_VARIANTS needs an es-XX entry (same
    // silent-spell-check-disable bug we fixed for en/de/pt). See CLAUDE.md.
    // Unambiguously-Spanish paragraph with one misspelling (`gatitto`). Shorter or
    // typo-heavier Spanish text can make SimpleLanguageIdentifier misfire to Galician
    // (`gl-ES`) — the local detector is considerably weaker than LT server's ngram
    // detector, so reliable Java-backend tests need enough context for the simple
    // detector to lock on.
    val matches =
      checkAutoDocument(
        preferredVariants = null, // -> merged to DEFAULT_PREFERRED_VARIANTS (no es-*)
        text =
          "Cuando el gatitto pequeño despertó por la mañana, corría rápidamente por el " +
            "jardín persiguiendo a las mariposas. Era una escena muy divertida que hacía " +
            "sonreír a todos los vecinos del barrio.\n",
      )
    // The rule id family differs between backends (HTTP server: MORFOLOGIK_RULE_ES;
    // local Java: HUNSPELL_RULE). What matters for the tripwire is simply that some
    // spell-check rule fires for bare Spanish — LanguageToolRuleMatch.isUnknownWordRule()
    // abstracts over the backend-specific naming.
    assertTrue(
      matches.first.any { it.isUnknownWordRule() },
      "Expected at least one unknown-word (spell-check) match for bare `es`; " +
        "got rule ids: " + matches.first.mapNotNull { it.ruleId },
    )
    assertEquals(
      "es",
      matches.second
        .first()
        .codeFragment.languageShortCode,
    )
  }

  @Test
  fun testAutoLanguageCategory2OptInVariantPromotesOnJavaBackend() {
    // Tripwire: on the Java backend, adding es-AR to preferredVariants causes
    // Settings.promoteToPreferredVariant to upgrade the detected bare `es` to `es-AR`
    // before the Java LanguageToolInterface is built, so LT runs with Argentinian
    // Spanish (voseo grammar). If this goes red, either LT dropped the es-AR Language
    // class or our promoteToPreferredVariant / base-matching logic regressed.
    val matches =
      checkAutoDocument(
        preferredVariants = listOf("es-AR"),
        text = "El gatito persigue a una mariposa en el jardín.\n",
      )
    assertEquals(
      "es-AR",
      matches.second
        .first()
        .codeFragment.languageShortCode,
    )
  }

  private fun checkAutoDocument(
    preferredVariants: List<String>?,
    text: String,
  ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
    // Java backend: languageToolHttpServerUri stays empty, so DocumentChecker runs
    // SimpleLanguageIdentifier locally, applies promoteToPreferredVariant, and builds
    // LanguageToolJavaInterface with the resolved variant.
    val settings =
      Settings(
        _languageShortCode = "auto",
        _preferredVariants = preferredVariants,
      )
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem = DocumentCheckerTest.createDocument("latex", text)
    return documentChecker.check(document)
  }

  companion object {
    private fun checkDocument(
      settings: Settings,
      code: String,
    ): List<LanguageToolRuleMatch> {
      val settingsManager = SettingsManager(settings)
      val documentChecker = DocumentChecker(settingsManager)
      val document: LtexTextDocumentItem = DocumentCheckerTest.createDocument("latex", code)
      val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
        documentChecker.check(document)
      return checkingResult.first
    }

    private fun assertMatchesCompare(
      oldSettings: Settings,
      newSettings: Settings,
      oldNumberOfMatches: Int,
      newNumberOfMatches: Int,
      code: String,
    ) {
      var matches = checkDocument(oldSettings, code)
      assertEquals(oldNumberOfMatches, matches.size)
      matches = checkDocument(newSettings, code)
      assertEquals(newNumberOfMatches, matches.size)
    }

    fun assertMatches(
      settings: Settings,
      checkMotherTongue: Boolean,
    ) {
      val matches: List<LanguageToolRuleMatch> =
        checkDocument(
          settings,
          "This is an \\textbf{test.}\n% LTeX: language=de-DE\nDies ist eine \\textbf{Test.}\n",
        )
      DocumentCheckerTest.assertMatches(matches, 8, 10, 58, 75)

      assertMatchesCompare(
        settings,
        settings.copy(
          _allDisabledRules = settings.getModifiedDisabledRules(setOf("UPPERCASE_SENTENCE_START")),
        ),
        1,
        0,
        "this is a test.\n",
      )

      assertMatchesCompare(
        settings,
        settings.copy(_allEnabledRules = settings.getModifiedDisabledRules(setOf("CAN_NOT"))),
        0,
        1,
        "You can not use the keyboard to select an item.\n",
      )

      assertMatchesCompare(
        settings,
        settings.copy(_enablePickyRules = true),
        0,
        1,
        "Tom isn't saved by Mary.\n",
      )

      if (checkMotherTongue) {
        // mother tongue requires loading false-friends.xml, but loading of custom rules doesn't
        // seem to be supported by servers (only via the Java API)
        assertMatchesCompare(
          settings,
          settings.copy(_motherTongueShortCode = "de-DE", _enablePickyRules = true),
          0,
          1,
          "I'm holding my handy.\n",
        )
      }
    }
  }
}
