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

class ProgramAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("") {
  @Test
  fun testJava() {
    assertPlainText(
      """
      Sentence 1 - no check // Sentence 2 - no check
      //Sentence 3 - no check
      // Sentence 4 -
      // check
      
      Sentence 5 - no check /* Sentence 6 - no check */
      /* Sentence 7 - no check */ Sentence 8 - no check
      /*Sentence 9 - no check */
      /* Sentence 10 - check */
      /** Sentence 11 -
        * check */
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck\n\n\nSentence 10 - check\n\n\nSentence 11 -\ncheck",
      "java",
    )
  }

  @Test
  fun testPython() {
    assertPlainText(
      "Sentence 1 - no check # Sentence 2 - no check\n#Sentence 3 - no check\n# Sentence 4 -\n" +
        "# check\n\nSentence 5 - no check \"\"\" Sentence 6 - no check \"\"\"\n" +
        "\"\"\" Sentence 7 - no check \"\"\" Sentence 8 - no check\n" +
        "\"\"\"Sentence 9 - no check \"\"\"\n\"\"\" Sentence 10 - check \"\"\"\n" +
        "\"\"\" Sentence 11 -\ncheck \"\"\"\n",
      "\n\n\nSentence 4 -\ncheck\n\n\nSentence 10 - check\n\n\n\nSentence 11 -\ncheck\n",
      "python",
    )
  }

  @Test
  fun testPowerShell() {
    assertPlainText(
      """
      Sentence 1 - no check # Sentence 2 - no check
      #Sentence 3 - no check
      # Sentence 4 -
      # check
      
      Sentence 5 - no check <# Sentence 6 - no check #>
      <# Sentence 7 - no check #> Sentence 8 - no check
      <#Sentence 9 - no check #>
      <# Sentence 10 - check #>
      <# Sentence 11 -
       # check #>
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck\n\n\nSentence 10 - check\n\n\nSentence 11 -\ncheck",
      "powershell",
    )
  }

  @Test
  fun testJulia() {
    assertPlainText(
      """
      Sentence 1 - no check # Sentence 2 - no check
      #Sentence 3 - no check
      ## Sentence 4 -
      ## check
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck",
      "julia",
    )
  }

  @Test
  fun testLua() {
    assertPlainText(
      """
      Sentence 1 - no check -- Sentence 2 - no check
      --Sentence 3 - no check
      --- Sentence 4 -
      --- check
      
      Sentence 5 - no check --[[ Sentence 6 - no check ]]
      --[[ Sentence 7 - no check ]] Sentence 8 - no check
      --[[Sentence 9 - no check ]]
      --[[ Sentence 10 - check ]]
      --[[ Sentence 11 -
      check ]]
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck\n\n\nSentence 10 - check\n\n\nSentence 11 -\ncheck",
      "lua",
    )
  }

  @Test
  fun testHaskell() {
    assertPlainText(
      """
      Sentence 1 - no check -- Sentence 2 - no check
      --Sentence 3 - no check
      --- Sentence 4 -
      --- check
      
      Sentence 5 - no check {- Sentence 6 - no check -}
      {- Sentence 7 - no check -} Sentence 8 - no check
      {-Sentence 9 - no check -}
      {- Sentence 10 - check -}
      {- Sentence 11 -
      check -}
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck\n\n\nSentence 10 - check\n\n\nSentence 11 -\ncheck",
      "haskell",
    )
  }

  @Test
  fun testSql() {
    assertPlainText(
      """
      Sentence 1 - no check -- Sentence 2 - no check
      --Sentence 3 - no check
      --- Sentence 4 -
      --- check
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck",
      "sql",
    )
  }

  @Test
  fun testLisp() {
    assertPlainText(
      """
      Sentence 1 - no check ; Sentence 2 - no check
      ;Sentence 3 - no check
      ;; Sentence 4 -
      ;; check

      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck",
      "lisp",
    )
  }

  @Test
  fun testElisp() {
    // Unlike "lisp"/"clojure" (testLisp), elisp routes through
    // ElispAnnotatedTextBuilder, which also checks trailing/inline comments
    // (line 1's "Sentence 2 - check"). See ElispAnnotatedTextBuilderTest for the
    // full elisp coverage.
    assertPlainText(
      """
      Sentence 1 - no check ; Sentence 2 - check
      ;Sentence 3 - no check
      ;; Sentence 4 -
      ;; check

      """.trimIndent(),
      "\n\n\nSentence 2 - check\n\n\nSentence 4 -\ncheck",
      "elisp",
    )
  }

  @Test
  fun testEmacsLisp() {
    assertPlainText(
      """
      Sentence 1 - no check ; Sentence 2 - check
      ;Sentence 3 - no check
      ;; Sentence 4 -
      ;; check

      """.trimIndent(),
      "\n\n\nSentence 2 - check\n\n\nSentence 4 -\ncheck",
      "emacs-lisp",
    )
  }

  @Test
  fun testElispEmacsQuotedIdentifierRewritten() {
    // Emacs convention: `name' is a quoted symbol reference (Texinfo legacy).
    // Without the rewrite, LanguageTool sees the trailing `'` as an unpaired
    // quote and the identifier as a misspelled word. The MarkdownAnnotatedText-
    // Builder's enableEmacsQuoteRewriting flag substitutes the closing `'`
    // with a backtick before flexmark parses, so the region is recognized as
    // inline code and replaced with a Dummy token. Same shape as the existing
    // markdown inline-code tests (cf. testBasicMarkdown's `inline code`
    // → Dummy0 case).
    assertPlainText(
      ";; LSP clients in Emacs need `lsp-mode' to work.\n",
      "\n\n\nLSP clients in Emacs need Dummy0 to work.",
      "elisp",
    )
    assertPlainText(
      ";; LSP clients in Emacs need `lsp-mode' to work.\n",
      "\n\n\nLSP clients in Emacs need Dummy0 to work.",
      "emacs-lisp",
    )
  }

  @Test
  fun testLispDoesNotRewriteEmacsQuotedIdentifier() {
    // Common Lisp has no standardised `name' convention, so the rewrite is
    // not enabled for "lisp" / "clojure". The literal characters survive
    // through to the plain text so LT can do whatever it would normally do.
    // Regression guard for the gate.
    assertPlainText(
      ";; LSP clients in Emacs need `lsp-mode' to work.\n",
      "\n\n\nLSP clients in Emacs need `lsp-mode' to work.",
      "lisp",
    )
    assertPlainText(
      ";; LSP clients in Emacs need `lsp-mode' to work.\n",
      "\n\n\nLSP clients in Emacs need `lsp-mode' to work.",
      "clojure",
    )
  }

  @Test
  fun testElispBareCommentLine() {
    // Regression: a ';;' content line followed by a bare ';;' line produced
    // an empty trailing code segment in MarkdownAnnotatedTextBuilder.addComment,
    // causing addMarkup(code.length) to bump newPos past code.length and throw
    // StringIndexOutOfBoundsException. The executor in
    // LtexTextDocumentService.didOpen only catches ExecutionException /
    // InterruptedException, so the exception was silently dropped and the LSP
    // server hung waiting on publishDiagnostics that never came.
    assertPlainText(
      ";; Hello world.\n;;\n",
      "\n\n\nHello world.\n",
      "elisp",
    )
  }

  @Test
  fun testMatlab() {
    assertPlainText(
      """
      Sentence 1 - no check % Sentence 2 - no check
      %Sentence 3 - no check
      %% Sentence 4 -
      %% check
      
      Sentence 5 - no check %{ Sentence 6 - no check %}
      %{ Sentence 7 - no check %} Sentence 8 - no check
      %{Sentence 9 - no check %}
      %{ Sentence 10 - check %}
      %{ Sentence 11 -
      check %}
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck\n\n\nSentence 10 - check\n\n\nSentence 11 -\ncheck",
      "matlab",
    )
  }

  @Test
  fun testErlang() {
    assertPlainText(
      """
      Sentence 1 - no check % Sentence 2 - no check
      %Sentence 3 - no check
      %% Sentence 4 -
      %% check
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck",
      "erlang",
    )
  }

  @Test
  fun testFortran() {
    assertPlainText(
      """
      Sentence 1 - no check c Sentence 2 - no check
      cSentence 3 - no check
      c Sentence 4 -
      c check
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck",
      "fortran-modern",
    )
  }

  @Test
  fun testVisualBasic() {
    assertPlainText(
      """
      Sentence 1 - no check ' Sentence 2 - no check
      'Sentence 3 - no check
      '' Sentence 4 -
      '' check
      
      """.trimIndent(),
      "\n\n\nSentence 4 -\ncheck",
      "vb",
    )
  }

  @Test
  fun testJavaCrlf() {
    val lfInput =
      """
      Sentence 1 - no check // Sentence 2 - no check
      //Sentence 3 - no check
      // Sentence 4 -
      // check

      Sentence 5 - no check /* Sentence 6 - no check */
      /* Sentence 7 - no check */ Sentence 8 - no check
      /*Sentence 9 - no check */
      /* Sentence 10 - check */
      /** Sentence 11 -
        * check */

      """.trimIndent()
    assertPlainText(
      lfInput.replace("\n", "\r\n"),
      "\n\n\nSentence 4 -\ncheck\n\n\nSentence 10 - check\n\n\nSentence 11 -\ncheck",
      "java",
    )
  }

  @Test
  fun testLispCrlf() {
    val lfInput =
      """
      Sentence 1 - no check ; Sentence 2 - no check
      ;Sentence 3 - no check
      ;; Sentence 4 -
      ;; check

      """.trimIndent()
    assertPlainText(
      lfInput.replace("\n", "\r\n"),
      "\n\n\nSentence 4 -\ncheck",
      "lisp",
    )
  }

  @Test
  fun testRust() {
    assertPlainText(
      """
      Sentence 1 - no check # Sentence 2 - no check
      #Sentence 3 - no check
      //! hello
      //! # two
      //! 3333, `444`
      //!
      //! 444
      
      test
      
      /// Sentence 4 -
      ///
      /// ```
      /// let a = 2;
      ///
      /// let b = a;
      /// ```
      ///
      /// ```rust
      /// let a = 2;
      ///
      /// let b = a;
      /// ```
      ///
      /// check
      ///
      /// | First Column | Second Column |
      /// | ------------ | ------------- |
      /// | Interesting  |    Super      |
      /// | Foo          |     Bar       |
      ///
      /// test
      
      """.trimIndent(),
      """
      
      
      
      hello
      two
      3333, Dummy0
      
      444
      
      
      Sentence 4 -
      
      
      
      
      
      
      
      
      
      
      
      
      
      check
      
      First Column Second Column
      
      Interesting Super
      Foo Bar
      
      test
      """.trimIndent(),
      "rust",
    )
  }
}
