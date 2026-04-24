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
}
