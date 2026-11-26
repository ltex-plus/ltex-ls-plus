/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.server.DocumentChecker
import org.bsplines.ltexls.server.LtexLanguageServer
import org.bsplines.ltexls.server.LtexTextDocumentItem
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import org.languagetool.markup.AnnotatedText
import org.languagetool.markup.AnnotatedTextBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotatedTextFragmentTest {
  private fun createFragment(
    annotatedText: AnnotatedText,
    code: String,
  ): AnnotatedTextFragment {
    val settings = Settings()
    val document =
      LtexTextDocumentItem(LtexLanguageServer(), "untitled:test.md", "markdown", 1, code)
    val codeFragment = CodeFragment("markdown", code, 0, settings)
    return AnnotatedTextFragment(annotatedText, codeFragment, document)
  }

  // The parts of a sliced and merged paragraph that starts with inline code: the
  // markup part keeps all the source, and "Dummy0" is left as a markup part with
  // no source (ltex-plus/ltex-ls-plus#226).
  @Test
  fun testMarkupWithNoSource() {
    val annotatedText: AnnotatedText =
      AnnotatedTextBuilder()
        .addText("Today.")
        .addMarkup("\n\n`code`", "\n\n")
        .addMarkup("", "Dummy0")
        .addText(" runs autouse per class.")
        .build()
    val code = "Today.\n\n`code` runs autouse per class."
    val fragment: AnnotatedTextFragment = createFragment(annotatedText, code)
    val fromPos: Int = code.indexOf("autouse")

    assertEquals("autouse", fragment.getSubstringOfPlainText(fromPos, fromPos + "autouse".length))
    assertEquals(" runs", fragment.getSubstringOfPlainText(code.indexOf(" runs"), fromPos - 1))
    assertEquals("Today.", fragment.getSubstringOfPlainText(0, "Today.".length))
  }

  @Test
  fun testGetPlainTextPosition() {
    val annotatedText: AnnotatedText =
      AnnotatedTextBuilder()
        .addText("ab")
        .addMarkup("\$xyz\$", "Dummy0")
        .addMarkup("<br>")
        .addText("cd")
        .build()
    // source: "ab$xyz$<br>cd", plain: "abDummy0cd"

    assertEquals(0, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 0, false))
    assertEquals(0, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 0, true))
    assertEquals(1, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 1, false))

    // The start of the inline math, as the start and as the end of a range.
    assertEquals(2, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 2, false))
    assertEquals(2, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 2, true))

    // Inside the inline math: its start for the start of a range, its end for the end.
    assertEquals(2, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 4, false))
    assertEquals(8, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 4, true))

    // Inside "<br>", which has no plain text.
    assertEquals(8, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 9, false))
    assertEquals(8, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 9, true))

    assertEquals(8, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 11, false))
    assertEquals(9, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 12, true))
    assertEquals(10, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 13, true))
    assertEquals(10, AnnotatedTextFragment.getPlainTextPosition(annotatedText, 13, false))
  }

  // ltex-plus/ltex-ls-plus#226: a paragraph that starts with inline code is cut
  // inside the markup part that holds the blank line and the code. When the
  // paragraphs are checked in one request, the quoted word was taken from the
  // wrong slice of plain text ('runs frobn'), so the dictionary entry never
  // matched it.
  @Test
  fun testDictionaryAfterInlineCodeAtParagraphStart() {
    val inlineCodes: List<Pair<String, String>> =
      listOf(
        Pair("markdown", "`Service.disable_all_features()`"),
        Pair("org", "~Service.disable~"),
        Pair("restructuredtext", "``Service.disable``"),
      )

    for ((codeLanguageId: String, inlineCode: String) in inlineCodes) {
      val document: LtexTextDocumentItem =
        LtexTextDocumentItem(
          LtexLanguageServer(),
          "untitled:test.txt",
          codeLanguageId,
          1,
          "The weather is nice, and the team works well together today.\n\n" +
            "$inlineCode runs frobnitz per class.\n",
        )
      var settings = Settings()
      var matches: List<LanguageToolRuleMatch> = checkDocument(document, settings)
      assertEquals(1, matches.size, codeLanguageId)
      assertEquals(document.text.indexOf("frobnitz"), matches[0].fromPos, codeLanguageId)
      assertTrue(matches[0].message.startsWith("'frobnitz': "), matches[0].message)

      settings = settings.copy(_allDictionaries = mapOf(Pair("en-US", setOf("frobnitz"))))
      matches = checkDocument(document, settings)
      assertEquals(0, matches.size, codeLanguageId)
    }
  }

  private fun checkDocument(
    document: LtexTextDocumentItem,
    settings: Settings,
  ): List<LanguageToolRuleMatch> = DocumentChecker(SettingsManager(settings)).check(document).first
}
