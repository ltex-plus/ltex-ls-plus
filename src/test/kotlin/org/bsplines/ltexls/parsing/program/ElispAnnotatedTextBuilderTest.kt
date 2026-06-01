/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.program

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilderTest
import kotlin.test.Test

class ElispAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("elisp") {
  @Test
  fun testComments() {
    // Trailing/inline comments after code (line 1) ARE checked for elisp.
    // `;text` with no space after the marker (line 2) is not checked.
    // Consecutive standalone `;;` lines (3-4) coalesce into one block.
    assertPlainText(
      """
      Sentence 1 - no check ; Sentence 2 - check
      ;Sentence 3 - no check
      ;; Sentence 4 -
      ;; check

      """.trimIndent(),
      "\n\n\nSentence 2 - check\n\n\nSentence 4 -\ncheck",
    )
  }

  @Test
  fun testTrailingCommentAfterCode() {
    assertPlainText(
      "(setq foo 1) ; increment the counter later\n",
      "\n\n\nincrement the counter later",
    )
  }

  @Test
  fun testTrailingCommentEmacsQuotedIdentifier() {
    assertPlainText(
      "(foo) ; see `bar' for details\n",
      "\n\n\nsee Dummy0 for details",
    )
  }

  @Test
  fun testSemicolonInStringIsNotInlineComment() {
    // The `;` inside the string is not a comment; the trailing `;` after the
    // closing paren is.
    assertPlainText(
      "(setq x \"a ; not a comment\") ; but this is\n",
      "\n\n\nbut this is",
    )
  }

  @Test
  fun testSectionHeadingsChecked() {
    // `;;;` section headings and `;;;;` file headers are prose and checked;
    // `;text` (no space after the marker) is not.
    assertPlainText(
      ";;; Code:\n;nospace\n;; Real comment here.\n",
      "\n\n\nCode:\n\n\nReal comment here.",
    )
  }

  @Test
  fun testFourSemicolonHeadingChecked() {
    assertPlainText(
      ";;;; File header summary.\n",
      "\n\n\nFile header summary.",
    )
  }

  @Test
  fun testBlankCommentLineSeparatesParagraphs() {
    // A bare `;;` line inside a coalesced comment block must survive as a blank
    // line so the two paragraphs are not merged into one (the checker would
    // otherwise run a sentence across the paragraph boundary). Lines within a
    // paragraph are joined with a single newline; the blank line becomes `\n\n`.
    assertPlainText(
      ";; First line\n" +
        ";; second line of the same paragraph\n" +
        ";;\n" +
        ";; New line of new paragraph\n" +
        ";; second line of second paragraph\n",
      "\n\n\nFirst line\nsecond line of the same paragraph" +
        "\n\nNew line of new paragraph\nsecond line of second paragraph",
    )
  }

  @Test
  fun testDefunDocstring() {
    assertPlainText(
      "(defun foo ()\n  \"This is a docstring.\"\n  nil)\n",
      "\n\n\nThis is a docstring.",
    )
  }

  @Test
  fun testDefcustomDocstring() {
    assertPlainText(
      "(defcustom foo nil\n  \"A short docstring.\"\n  :type 'boolean)\n",
      "\n\n\nA short docstring.",
    )
  }

  @Test
  fun testEmacsLispLanguageId() {
    assertPlainText(
      "(defun foo ()\n  \"This is a docstring.\"\n  nil)\n",
      "\n\n\nThis is a docstring.",
      "emacs-lisp",
    )
  }

  @Test
  fun testDocstringDirectivesNeutralized() {
    // `symbol\\=' (Emacs quoted identifier with self-quoting escape) and the
    // \\[command] key substitution are both collapsed to Dummy tokens so they
    // never become spelling/grammar false positives.
    assertPlainText(
      "(defun foo ()\n  \"Activate `foo-mode\\\\=' using \\\\[execute-command] now.\"\n  nil)\n",
      "\n\n\nActivate Dummy0 using Dummy1 now.",
    )
  }

  @Test
  fun testDefvarValueStringNotChecked() {
    // The string in the value slot (element 2) is not a docstring; only the
    // docstring slot (element 3) is checked.
    assertPlainText(
      "(defvar foo \"not a docstring\" \"The real docstring.\")\n",
      "\n\n\nThe real docstring.",
    )
  }

  @Test
  fun testReturnValueStringIsNotDocstring() {
    // A lone string body in a function form is the return value, not a
    // docstring, so nothing is checked.
    assertPlainText(
      "(defun foo () \"just returned\")\n",
      "",
    )
  }

  @Test
  fun testPlainStringLiteralNotChecked() {
    // Arbitrary string literals (e.g. a setq value) are not docstrings.
    assertPlainText(
      "(setq foo \"plain string value\")\n",
      "",
    )
  }

  @Test
  fun testDefineMinorModeDocstring() {
    assertPlainText(
      "(define-minor-mode foo-mode\n  \"Toggle Foo mode.\"\n  :init-value nil)\n",
      "\n\n\nToggle Foo mode.",
    )
  }

  @Test
  fun testDefineDerivedModeDocstring() {
    // The mode-line name ("Foo", element 3) is skipped; the docstring is
    // element 4.
    assertPlainText(
      "(define-derived-mode foo-mode prog-mode \"Foo\"\n  \"Major mode for Foo.\"\n  nil)\n",
      "\n\n\nMajor mode for Foo.",
    )
  }

  @Test
  fun testCommentAndDocstring() {
    assertPlainText(
      ";; A leading comment sentence.\n(defun foo ()\n  \"A docstring sentence.\"\n  nil)\n",
      "\n\n\nA leading comment sentence.\n\n\nA docstring sentence.",
    )
  }

  @Test
  fun testLeadingStarMarker() {
    // A leading `*` is the historical user-variable marker, not prose.
    assertPlainText(
      "(defvar foo nil \"*User option docstring.\")\n",
      "\n\n\nUser option docstring.",
    )
  }

  @Test
  fun testMultiParagraphDocstring() {
    assertPlainText(
      "(defun foo ()\n  \"First paragraph here.\n\nSecond paragraph here.\"\n  nil)\n",
      "\n\n\nFirst paragraph here.\n\nSecond paragraph here.",
    )
  }

  @Test
  fun testSemicolonInsideStringIsNotComment() {
    // A `;` at line start inside a string literal must not be treated as a
    // comment. Here the string is a plain value (not a docstring), so nothing
    // is checked at all.
    assertPlainText(
      "(setq foo \"line one\n;; looks like a comment but is not\")\n",
      "",
    )
  }

  @Test
  fun testEmacsQuotedIdentifierInDocstring() {
    // The plain `symbol' form (without escape) is recognised as inline code.
    assertPlainText(
      "(defun foo ()\n  \"See `bar' for details.\"\n  nil)\n",
      "\n\n\nSee Dummy0 for details.",
    )
  }
}
