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
import org.bsplines.ltexls.settings.HiddenFalsePositive
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.languagetool.server.HTTPServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LanguageToolHttpInterfaceTest {
  private var serverThread: Thread? = null
  private var defaultSettings = Settings()

  @BeforeAll
  fun setUp() {
    val serverThread = Thread { HTTPServer.main(arrayOf("--port", "8081", "--allow-origin", "*")) }
    serverThread.start()
    this.serverThread = serverThread

    // wait until LanguageTool has initialized itself
    Thread.sleep(5000)
    this.defaultSettings =
      defaultSettings.copy(
        _languageToolHttpServerUri = "http://localhost:8081",
      )
  }

  @AfterAll
  fun tearDown() {
    this.serverThread?.interrupt()
  }

  @Test
  fun testConstructor() {
    assertTrue(LanguageToolHttpInterface("http://localhost:8081", "en-US", "").isInitialized())
    assertTrue(LanguageToolHttpInterface("http://localhost:8081/", "en-US", "").isInitialized())
    assertTrue(
      LanguageToolHttpInterface("http://localhost:8081/", "en-US", "").getURIString() ==
        "http://localhost:8081/v2/check",
    )
    assertTrue(
      LanguageToolHttpInterface("http://localhost:8081", "en-US", "").getURIString() ==
        "http://localhost:8081/v2/check",
    )
    assertFalse(LanguageToolHttpInterface("http://localhost:80:81/", "en-US", "").isInitialized())
  }

  @Test
  fun testCheck() {
    LanguageToolJavaInterfaceTest.assertMatches(this.defaultSettings, false)
  }

  @Test
  fun testOtherMethods() {
    val settingsManager = SettingsManager(this.defaultSettings)
    val ltInterface: LanguageToolInterface? = settingsManager.languageToolInterface
    assertNotNull(ltInterface)
    ltInterface.activateDefaultFalseFriendRules()
    ltInterface.activateLanguageModelRules("foobar")
    ltInterface.enableEasterEgg()
  }

  @Test
  fun testAutoLanguageWithPreferredVariantsEnablesSpellCheck() {
    // Regression test for the silent spell-check disable when language=auto is used
    // with an HTTP backend: `auto` must propagate to the server together with
    // preferredVariants so the server picks a variant and runs its spell-check dict.
    val settings: Settings =
      this.defaultSettings.copy(
        _languageShortCode = "auto",
        _preferredVariants = listOf("en-US"),
      )
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument(
        "latex",
        "This is a testt sentence with a mispeling.\n",
      )
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      documentChecker.check(document)
    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertTrue(
      matches.any { it.ruleId?.startsWith("MORFOLOGIK_") == true },
      "Expected at least one MORFOLOGIK_* spell-check match; got rule ids: " +
        matches.mapNotNull { it.ruleId },
    )
    // The server picked a concrete variant from preferredVariants and LTeX back-filled it
    // onto the CodeFragment so downstream consumers (code actions, per-language dictionary
    // keys) no longer see the literal "auto".
    val fragments: List<AnnotatedTextFragment> = checkingResult.second
    assertTrue(fragments.isNotEmpty())
    for (fragment in fragments) {
      assertNotEquals("auto", fragment.codeFragment.languageShortCode)
    }
    assertEquals("en-US", fragments.first().codeFragment.languageShortCode)
  }

  @Test
  fun testAutoLanguageHonoursUserOverrideWithinCategory1() {
    // Tripwire: asserts the LT server still honours `preferredVariants` for swapping
    // within Category 1 bases. User has overridden the default en-US with en-GB; the
    // server must pick en-GB for English text and fire the British-English spell dict.
    // If this goes red, LT's /check has stopped respecting preferredVariants (or
    // changed how it iterates the list) — re-read CLAUDE.md's "LanguageTool language-
    // tag quirks" section and TextChecker.detectLanguageOfString before touching the
    // assertion.
    val result =
      checkAutoWithPreferredVariants(
        preferredVariants = listOf("en-GB"),
        text = "This is a testt sentence with a mispeling.\n",
      )
    assertTrue(
      result.first.any { it.ruleId == "MORFOLOGIK_RULE_EN_GB" },
      "Expected MORFOLOGIK_RULE_EN_GB match; got rule ids: " +
        result.first.mapNotNull { it.ruleId },
    )
    assertEquals(
      "en-GB",
      result.second
        .first()
        .codeFragment.languageShortCode,
    )
  }

  @Test
  fun testAutoLanguageBareCategory2StillRunsSpellCheck() {
    // Tripwire: asserts LT's Category 2 contract — bare codes like `es` / `fr` / `it`
    // are full spell + grammar checkers, so we don't need to ship variants for them in
    // DEFAULT_PREFERRED_VARIANTS. Here preferredVariants contains no es-*; the server
    // returns bare `es`; MORFOLOGIK_RULE_ES must still fire. If this goes red, LT has
    // reclassified Spanish as Category 1 (bare-insufficient) and DEFAULT_PREFERRED_-
    // VARIANTS needs an es-XX entry — otherwise every Spanish user silently loses
    // spell-check, same bug we fixed for en/de/pt. See CLAUDE.md for the Category 1/2/3
    // taxonomy.
    val result =
      checkAutoWithPreferredVariants(
        preferredVariants = null, // -> merged to DEFAULT_PREFERRED_VARIANTS
        text = "El gatitto persigue a una maripossa en el jardín.\n",
      )
    assertTrue(
      result.first.any { it.ruleId == "MORFOLOGIK_RULE_ES" },
      "Expected MORFOLOGIK_RULE_ES match (bare es is expected to be a full checker); " +
        "got rule ids: " + result.first.mapNotNull { it.ruleId },
    )
    assertEquals(
      "es",
      result.second
        .first()
        .codeFragment.languageShortCode,
    )
  }

  @Test
  fun testAutoLanguageDictionarySuppressesMatchOnFirstCheck() {
    // Regression test for the per-language filter bug in auto+HTTP mode:
    // settings.languageShortCode stays at the literal "auto" (the HTTP path lets the
    // server detect the language), so a settings.dictionary lookup keyed off
    // languageShortCode used to return _allDictionaries["auto"] = empty — meaning a word
    // the user had explicitly added under e.g. "en-US" was *never* filtered.
    //
    // The fix moves the lookup from settings-level to fragment-level: the bucket is
    // keyed by `annotatedTextFragment.codeFragment.languageShortCode`, which by the time
    // checkMatchValidity runs has been back-filled to the variant the server picked.
    //
    // Symmetric-by-construction: disabledRules and hiddenFalsePositives flow through the
    // same fragment-keyed lookup (in LanguageToolInterface.checkMatchValidity and
    // DocumentChecker.removeIgnoredMatches respectively), so verifying the dictionary
    // path here covers all three.
    val settings: Settings =
      this.defaultSettings.copy(
        _languageShortCode = "auto",
        _preferredVariants = listOf("en-US"),
        _allDictionaries = mapOf("en-US" to FileSettings.of("testt", "mispeling")),
      )
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument(
        "latex",
        "This is a testt sentence with a mispeling.\n",
      )
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      documentChecker.check(document)
    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertFalse(
      matches.any { it.isUnknownWordRule() },
      "Dictionary entries 'testt' and 'mispeling' under en-US should suppress the spell-" +
        "check matches, but got: " +
        matches.mapNotNull { it.ruleId } +
        " on tokens " +
        matches.map { it.fromPos.toString() + ".." + it.toPos.toString() },
    )
  }

  @Test
  fun testAutoLanguageDictionarySuppressesGermanMatchOnFirstCheck() {
    // German variant of testAutoLanguageDictionarySuppressesMatchOnFirstCheck — the
    // user-reported flow was a German document, so cover de-DE explicitly. Uses default
    // preferredVariants so the merged list contains de-DE and the server picks it for
    // German text. Dictionary entries are keyed under "de-DE" (the variant the server
    // back-fills onto the fragment), and both German misspellings must be suppressed
    // on the first check.
    //
    // Sentence carries unambiguous German signal (Gestern, Schwester, München) so the
    // server's ngram detector locks onto German rather than Dutch, which it can mistake
    // when a typo happens to look like a Dutch word (e.g. "Pakket"). Misspellings are
    // chosen to be uniquely German-shaped to avoid the same trap.
    val settings: Settings =
      this.defaultSettings.copy(
        _languageShortCode = "auto",
        _allDictionaries = mapOf("de-DE" to FileSettings.of("gegesssen", "wunderschöön")),
      )
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument(
        "latex",
        "Gestern Abend habe ich mit meiner Schwester in München zusammen gegesssen " +
          "und es war wunderschöön.\n",
      )
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      documentChecker.check(document)
    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertEquals(
      "de-DE",
      checkingResult.second
        .first()
        .codeFragment.languageShortCode,
      "Server should have detected de-DE for German text under default preferredVariants",
    )
    assertFalse(
      matches.any { it.isUnknownWordRule() },
      "Dictionary entries 'gegesssen' and 'wunderschöön' under de-DE should suppress the " +
        "spell-check matches, but got: " +
        matches.mapNotNull { it.ruleId } +
        " on tokens " +
        matches.map { it.fromPos.toString() + ".." + it.toPos.toString() },
    )
  }

  @Test
  fun testAutoLanguageDisabledRulesSuppressMatchOnFirstCheck() {
    // Companion to testAutoLanguageDictionarySuppressesMatchOnFirstCheck for
    // ltex.disabledRules. The fix moves the lookup from settings.languageShortCode
    // (which stays "auto" forever on the HTTP path) to the fragment's resolved
    // language code; if that lookup were still keyed on the session, the EN_A_VS_AN
    // entry under "en-US" would never be consulted and the grammar match would still
    // fire. Without this explicit test, the "by construction" argument only covers
    // dictionary suppression — a future refactor could silently break disabledRules
    // suppression while leaving dictionary working.
    val settings: Settings =
      this.defaultSettings.copy(
        _languageShortCode = "auto",
        _preferredVariants = listOf("en-US"),
        _allDisabledRules = mapOf("en-US" to FileSettings.of("EN_A_VS_AN")),
      )
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument(
        "latex",
        "This is a apple in the garden today.\n",
      )
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      documentChecker.check(document)
    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertFalse(
      matches.any { it.ruleId == "EN_A_VS_AN" },
      "EN_A_VS_AN under en-US should suppress the grammar match, but got rule ids: " +
        matches.mapNotNull { it.ruleId },
    )
    assertEquals(
      "en-US",
      checkingResult.second
        .first()
        .codeFragment.languageShortCode,
    )
  }

  @Test
  fun testAutoLanguageHiddenFalsePositivesSuppressMatchOnFirstCheck() {
    // Companion to testAutoLanguageDictionarySuppressesMatchOnFirstCheck for
    // ltex.hiddenFalsePositives. Suppression happens in DocumentChecker.removeIgnoredMatches,
    // which now reads settings.allHiddenFalsePositives by the fragment's resolved
    // language rather than by settings.languageShortCode. Mirrors the disabled-rules
    // test but exercises the second filter path.
    val sentence = "This is a apple in the garden today."
    val settings: Settings =
      this.defaultSettings.copy(
        _languageShortCode = "auto",
        _preferredVariants = listOf("en-US"),
        _allHiddenFalsePositives =
          mapOf("en-US" to FileSettings.of(HiddenFalsePositive("EN_A_VS_AN", "^\\Q$sentence\\E$"))),
      )
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument("latex", "$sentence\n")
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      documentChecker.check(document)
    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertFalse(
      matches.any { it.ruleId == "EN_A_VS_AN" },
      "HiddenFalsePositive entry under en-US should suppress the grammar match, " +
        "but got rule ids: " + matches.mapNotNull { it.ruleId },
    )
    assertEquals(
      "en-US",
      checkingResult.second
        .first()
        .codeFragment.languageShortCode,
    )
  }

  @Test
  fun testAutoLanguageMagicCommentSwitchesFragmentToAuto() {
    // A magic comment like "% ltex: language=auto" must switch *subsequent* fragments
    // to auto-detection while leaving the prior block under its explicit language. This
    // is the per-fragment story the rest of the auto+HTTP fix relies on: language
    // resolution and dictionary lookup both operate per-CodeFragment, so a single
    // document can mix an explicitly-tagged block with an auto-detected one without
    // cross-contamination.
    //
    // Setup: initial setting is en-US (explicit). The document is two paragraphs split
    // by a "% ltex: language=auto" comment — the first is English (stays en-US), the
    // second is German (the magic comment switches the fragment to auto; the server
    // detects de-DE under default preferredVariants and back-fills it onto the
    // fragment).
    val settings: Settings =
      this.defaultSettings.copy(
        _languageShortCode = "en-US",
      )
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument(
        "latex",
        "This is an English sentence about cats and dogs.\n" +
          "% ltex: language=auto\n" +
          "Gestern Abend habe ich mit meiner Schwester in München zusammen gegessen " +
          "und es war wunderschön.\n",
      )
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      documentChecker.check(document)
    val checkedLanguages: List<String> =
      checkingResult.second
        .map { it.codeFragment.languageShortCode }
        .filter { it != "auto" }
    assertTrue(
      checkedLanguages.contains("en-US"),
      "Expected an en-US fragment from the explicit-tagged block; got: $checkedLanguages",
    )
    assertTrue(
      checkedLanguages.contains("de-DE"),
      "Expected a de-DE fragment from the auto-tagged German block (back-filled by the " +
        "server under default preferredVariants); got: $checkedLanguages",
    )
  }

  @Test
  fun testAutoLanguageCategory2OptInVariantIsRouted() {
    // Tripwire: asserts Category 2 opt-in variants (e.g. es-AR for Argentinian voseo
    // grammar) are still routed through the server when the user adds them to
    // preferredVariants alongside the merged defaults. A user writing Argentinian
    // Spanish adds "es-AR"; mergePreferredVariants appends it to the Cat 1 defaults;
    // the server must pick es-AR for Spanish text. If this goes red, either LT dropped
    // the es-AR variant registration, or our merge ordering / wire format stopped
    // matching LT's iteration semantics in TextChecker.detectLanguageOfString.
    val result =
      checkAutoWithPreferredVariants(
        preferredVariants = listOf("es-AR"),
        text = "El gatito persigue a una mariposa en el jardín.\n",
      )
    assertEquals(
      "es-AR",
      result.second
        .first()
        .codeFragment.languageShortCode,
    )
  }

  private fun checkAutoWithPreferredVariants(
    preferredVariants: List<String>?,
    text: String,
  ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
    val settings: Settings =
      this.defaultSettings.copy(
        _languageShortCode = "auto",
        _preferredVariants = preferredVariants,
      )
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument("latex", text)
    return documentChecker.check(document)
  }
}
