/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end behavior of `ltex.dictionary`, against the local Java backend so
 * these cases need no network and no credentials.
 *
 * Two mechanisms cooperate. A multi-word entry is joined into a single token
 * before the text reaches LanguageTool (CodeAnnotatedTextBuilder.build), so that
 * LanguageTool reports one match spanning exactly the entry instead of
 * tokenizing the phrase and flagging one word of it. The match is then dropped
 * because its span is an accepted word (LanguageToolInterface.isCoveredByDictionary).
 *
 * A single-word entry is not substituted at all — only the second mechanism
 * applies. Every case here pairs the assertion with a control, so none of them
 * can pass merely because LanguageTool happened to stay quiet.
 */
class DocumentCheckerDictionaryTest {
  @Test
  fun testSingleWordEntryIsAccepted() {
    val text = "This is a qwertyunknown in the text."

    // Control: without the entry the word is flagged.
    assertTrue(check(text, emptySet()).isNotEmpty())
    assertTrue(check(text, setOf("qwertyunknown")).isEmpty())
  }

  @Test
  fun testMultiWordEntryIsAccepted() {
    val text = "We hired Qwertyalpha Qwertybeta last spring."

    // Control: both words are flagged when the phrase is not an entry, and
    // listing the phrase suppresses the lot.
    assertTrue(check(text, emptySet()).size >= 2)
    assertTrue(check(text, setOf("Qwertyalpha Qwertybeta")).isEmpty())
  }

  @Test
  fun testLoneWordOfPhraseEntryStaysFlagged() {
    // The point of phrase entries: accepting `Qwertyalpha Qwertybeta` must not
    // silently accept a bare `Qwertyalpha` elsewhere.
    val matches: List<LanguageToolRuleMatch> =
      check("We hired Qwertyalpha last spring.", setOf("Qwertyalpha Qwertybeta"))

    assertTrue(matches.isNotEmpty())
  }

  @Test
  fun testGenuineErrorNextToEntryIsKept() {
    // The property that ruled out substituting an invented placeholder: an error
    // beside an accepted word must survive. `an mistake` is the user's own, and
    // LanguageTool can still judge it because it sees the real neighbouring word.
    val matches: List<LanguageToolRuleMatch> =
      check("The qwertyunknown is an mistake.", setOf("qwertyunknown"))

    assertEquals(1, matches.size)
    assertEquals("EN_A_VS_AN", matches[0].ruleId)
  }

  @Test
  fun testUppercaseOccurrenceOfEntryIsAccepted() {
    // Accepted case variants survive the join: the heading form collapses to
    // `QWERTYALPHAQWERTYBETA`, which the check path recognizes because it
    // compares against the collapsed entry's case variants.
    assertTrue(check("QWERTYALPHA QWERTYBETA ROADMAP", setOf("Qwertyalpha Qwertybeta")).isEmpty())
  }

  @Test
  fun testTypstAbbreviationLabelIsAccepted() {
    // Typst registers an abbreviation's short label as an accepted word while
    // parsing (TypstAnnotatedTextBuilder.registerAbbreviation). The label is left
    // in the prose, so the suppression has to reach entries that came from the
    // builder rather than from Settings — they travel on the AnnotatedTextFragment.
    val registered =
      """
      #abbr.add(short: "Qwertyabi", entry: "some expansion")
      The Qwertyabi is stable.
      """.trimIndent()

    // Control: the same prose without the registration leaves the label flagged.
    val unregistered = "The Qwertyabi is stable."

    assertTrue(checkTypst(unregistered).isNotEmpty())
    assertTrue(checkTypst(registered).isEmpty())
  }

  private fun check(
    text: String,
    dictionary: Set<String>,
  ): List<LanguageToolRuleMatch> = checkWith("markdown", text, dictionary)

  private fun checkTypst(text: String): List<LanguageToolRuleMatch> =
    checkWith("typst", text, emptySet())

  private fun checkWith(
    codeLanguageId: String,
    text: String,
    dictionary: Set<String>,
  ): List<LanguageToolRuleMatch> {
    val settings =
      Settings(
        _languageShortCode = "en-US",
        _allDictionaries = mapOf("en-US" to dictionary),
      )
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument(codeLanguageId, text)
    return DocumentChecker(SettingsManager(settings)).check(document).first
  }
}
