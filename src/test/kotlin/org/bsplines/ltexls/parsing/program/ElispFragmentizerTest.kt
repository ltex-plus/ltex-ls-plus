/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.program

import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.parsing.restructuredtext.RestructuredtextFragmentizerTest
import org.bsplines.ltexls.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class ElispFragmentizerTest {
  @Test
  fun testStandaloneMagicComments() {
    // Same five-fragment contract as the Lisp regex path (ProgramFragmentizerTest
    // .testLisp): standalone `;` comments carrying `ltex:` toggle settings
    // identically, even though Emacs Lisp now uses the structural scanner.
    RestructuredtextFragmentizerTest.assertFragmentizer(
      "elisp",
      """
      Sentence 1

      ;  ltex: language=de-DE

      Sentence 2

      ;		ltex:	language=en-US

      Sentence 3

      """.trimIndent(),
    )
  }

  @Test
  fun testEmacsLispLanguageId() {
    RestructuredtextFragmentizerTest.assertFragmentizer(
      "emacs-lisp",
      """
      Sentence 1

      ;  ltex: language=de-DE

      Sentence 2

      ;		ltex:	language=en-US

      Sentence 3

      """.trimIndent(),
    )
  }

  @Test
  fun testDoubleSemicolonMagicComment() {
    // `;;` headings carry directives just like `;`, matching the former `;;?`.
    val fragmentizer: CodeFragmentizer = CodeFragmentizer.create("elisp")
    val fragments: List<CodeFragment> =
      fragmentizer.fragmentize("Sentence 1\n;; ltex: language=de-DE\nSentence 2\n", Settings())
    assertEquals(3, fragments.size)
    assertEquals("en-US", fragments[0].settings.languageShortCode)
    assertEquals("de-DE", fragments[2].settings.languageShortCode)
  }

  @Test
  fun testTripleSemicolonIsNotADirective() {
    // `;;;` (three semicolons) is a section heading, not a magic comment, just
    // as `;;?` only matched one or two semicolons.
    val fragmentizer: CodeFragmentizer = CodeFragmentizer.create("elisp")
    val fragments: List<CodeFragment> =
      fragmentizer.fragmentize("Sentence 1\n;;; ltex: language=de-DE\nSentence 2\n", Settings())
    assertEquals(1, fragments.size)
    assertEquals("en-US", fragments[0].settings.languageShortCode)
  }

  @Test
  fun testMagicCommentInStringIsNotADirective() {
    // The `; ltex:` sequence here is inside a string literal; the scanner
    // recognises that lexically and does not treat it as a directive (the old
    // line-comment regex would have).
    val fragmentizer: CodeFragmentizer = CodeFragmentizer.create("elisp")
    val fragments: List<CodeFragment> =
      fragmentizer.fragmentize("(setq x \"; ltex: language=de-DE\")\n", Settings())
    assertEquals(1, fragments.size)
    assertEquals("en-US", fragments[0].settings.languageShortCode)
  }

  @Test
  fun testTrailingMagicCommentIsNotADirective() {
    // An inline/trailing comment after code is not standalone, so it cannot
    // carry settings (mirroring the regex's `^[ \t]*` anchor).
    val fragmentizer: CodeFragmentizer = CodeFragmentizer.create("elisp")
    val fragments: List<CodeFragment> =
      fragmentizer.fragmentize("(foo) ; ltex: language=de-DE\n(bar)\n", Settings())
    assertEquals(1, fragments.size)
    assertEquals("en-US", fragments[0].settings.languageShortCode)
  }
}
