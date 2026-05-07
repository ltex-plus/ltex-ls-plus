/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.org

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilderTest
import kotlin.test.Test

@Suppress("LargeClass")
class OrgAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("org") {
  @Test
  fun testHeadlinesAndSections() {
    assertPlainText(
      "* \n\n" +
        """
        ** DONE

        *** This is a test

        **** TODO [#A] COMMENT Another test :tag:a2%:

        **** TODO [#A] Final test :tag:a2%:

        """.trimIndent(),
      "\n\n\n\n\n\n\n\n\nThis is a test\n\n\n\n\n\n\nFinal test\n\n",
    )
  }

  @Test
  fun testAffiliatedKeywords() {
    assertPlainText(
      """
      This is a test.

      #+HEADER: test
      #+NAME: some-name
      Second sentence.

        #+ATTR_foo01_bar: BOOM
      Final sentence.

      """.trimIndent(),
      "This is a test.\n\n\n\nSecond sentence.\n\n\nFinal sentence.\n",
    )
  }

  @Test
  fun testCaptions() {
    assertPlainText(
      """
      This is a test.
      #+CAPTION: A short caption.
      This is another test.

      """.trimIndent(),
      "This is a test.\nA short caption.\nThis is another test.\n",
    )
    assertPlainText(
      """
      This is a test.
      #+CAPTION[short]: A longer caption description.
      This is another test.

      """.trimIndent(),
      "This is a test.\nA longer caption description.\nThis is another test.\n",
    )
    assertPlainText(
      "This is a test.\n#+CAPTION: A caption with *bold* and [[https://example.com][a link]].\n",
      "This is a test.\nA caption with bold and a link.\n",
    )
    assertPlainText(
      "#+CAPTION[short alt]: Here is an example [code] with square brackets in it.\n",
      "Here is an example [code] with square brackets in it.\n",
    )
  }

  @Test
  fun testCenterBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN_CENTER
      Contents.
      #+END_CENTER
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nContents.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testQuoteBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN_QUOTE
      Contents.
      #+END_QUOTE
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nContents.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testCustomBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN_FOOBAR
      Contents.
      #+END_FOOBAR
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nContents.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testCommentBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN_COMMENT
      Contents.
      #+END_COMMENT
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testExampleBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN_EXAMPLE
      Contents.
      #+END_EXAMPLE
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testExportBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN_EXPORT
      Contents.
      #+END_EXPORT
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testSourceBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN_SRC
      Contents.
      #+END_SRC
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testVerseBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN_VERSE
      Contents.
      #+END_VERSE
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nContents.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testDrawers() {
    assertPlainText(
      """
      This is a test.
      :TEST_DRAWER:
      Contents.
      :END:
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nContents.\n\nThis is another test.\n",
    )
    assertPlainText(
      """
      This is a test.
      :PROPERTIES:
      Contents.
      :END:
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testDynamicBlocks() {
    assertPlainText(
      """
      This is a test.
      #+BEGIN: test-block :abc
      Contents.
      :END:
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nContents.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testFootnotes() {
    assertPlainText(
      """
      This is a test.
      [fn:1] Contents.
      This is another test.

      """.trimIndent(),
      "This is a test.\nContents.\nThis is another test.\n",
    )
  }

  @Test
  fun testLists() {
    assertPlainText(
      """
      1. Test 1.
      2. [X] Test 2.
         - Test tag :: Test 3.

      """.trimIndent(),
      "\nTest 1.\n\n\nTest 2.\n\n\nTest tag\nTest 3.\n\n",
    )
  }

  @Test
  fun testDescriptionListTerm() {
    // The term in "- Term :: body" must reach LanguageTool as text so that
    // typos in the term get caught.
    assertPlainText(
      "- Apple :: a red fruit\n",
      "\nApple\na red fruit\n\n",
    )
    assertPlainText(
      "- [X] Mitochondria :: the powerhouse of the cell\n",
      "\nMitochondria\nthe powerhouse of the cell\n\n",
    )
    // Bullet without a description term still works.
    assertPlainText(
      "- a plain bullet\n",
      "\na plain bullet\n\n",
    )
    // The same fix must apply to every other bullet type org accepts:
    // "+", indented "*", numbered, and alphabetical bullets.
    assertPlainText(
      "+ Apple :: a red fruit\n",
      "\nApple\na red fruit\n\n",
    )
    assertPlainText(
      "  * Apple :: a red fruit\n",
      "\nApple\na red fruit\n\n",
    )
    assertPlainText(
      "1. Apple :: a red fruit\n",
      "\nApple\na red fruit\n\n",
    )
    assertPlainText(
      "a) Apple :: a red fruit\n",
      "\nApple\na red fruit\n\n",
    )
  }

  @Test
  fun testWrappedListItems() {
    // Continuation lines indented to the bullet's body column belong to the
    // same item — they must not be split into a new paragraph, otherwise
    // LanguageTool flags the wrapped sentence as not starting with uppercase.
    assertPlainText(
      """
      + A long sentence that wraps. A long sentence
        that wraps.

      """.trimIndent(),
      "\nA long sentence that wraps. A long sentence\nthat wraps.\n\n",
    )
    assertPlainText(
      """
      + first
        continuation
      + second

      """.trimIndent(),
      "\nfirst\ncontinuation\n\n\nsecond\n\n",
    )
    assertPlainText(
      """
      + first
        continuation

      after

      """.trimIndent(),
      "\nfirst\ncontinuation\n\n\nafter\n",
    )
    // Same wrap behaviour must hold for every bullet type org accepts.
    // "-" (1-char body, continuation indent 2):
    assertPlainText(
      """
      - first
        continuation

      """.trimIndent(),
      "\nfirst\ncontinuation\n\n",
    )
    // Indented "*" (1-char body, continuation indent 4 because the bullet
    // itself is indented at column 2). Cannot use trimIndent here because it
    // would strip the leading whitespace and turn "  * first" into "* first",
    // which the parser would correctly classify as a headline rather than an
    // (indented) list item.
    assertPlainText(
      "  * first\n    continuation\n",
      "\nfirst\ncontinuation\n\n",
    )
    // Numbered bullet "1." (2-char body, continuation indent 3):
    assertPlainText(
      """
      1. first
         continuation

      """.trimIndent(),
      "\nfirst\ncontinuation\n\n",
    )
    // Alphabetical bullet "a)" (2-char body, continuation indent 3):
    assertPlainText(
      """
      a) first
         continuation

      """.trimIndent(),
      "\nfirst\ncontinuation\n\n",
    )
  }

  @Test
  fun testTables() {
    assertPlainText(
      """
      This is a test.
      | Test1 | Test2 | Test3 |
      |-------+-------+-------|
      | Test4 | Test5 | Test6 |
      | Test7 | Test8 | Test9 |
      This is another test.

      """.trimIndent(),
      """
      This is a test.

      Test1

      Test2

      Test3







      Test4

      Test5

      Test6




      Test7

      Test8

      Test9



      This is another test.

      """.trimIndent(),
    )
  }

  @Test
  fun testBabelCalls() {
    assertPlainText(
      """
      This is a test.
      #+CALL: Contents.
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testClocks() {
    assertPlainText(
      """
      This is a test.
      CLOCK: [1234-05-06 07:08]
      CLOCK: [1234-05-06 07:08-09:10] => 1:23
      CLOCK: [1234-05-06 07:08]--[1234-05-06 07:08] => 1:23
      This is another test.

      """.trimIndent(),
      "This is a test.\n\n\n\nThis is another test.\n",
    )
  }

  @Test
  fun testDiarySexps() {
    assertPlainText(
      """
      This is a test.
      %%(Contents.
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testPlannings() {
    assertPlainText(
      """
      * Test
      DEADLINE: [1234-05-06 Sat 07:08]
      This is a test.

      """.trimIndent(),
      "\nTest\n\n\nThis is a test.\n",
    )
  }

  @Test
  fun testComments() {
    assertPlainText(
      """
      This is a test.
        #	Comment
      # Another comment
      This is another test.

      """.trimIndent(),
      "This is a test.\nThis is another test.\n",
    )
  }

  @Test
  fun testFixedWidthLines() {
    assertPlainText(
      """
      This is a test.
        :
      : Contents.
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nContents.\nThis is another test.\n",
    )
  }

  @Test
  fun testHorizontalRules() {
    assertPlainText(
      """
      This is a test.
      -----
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testKeywords() {
    assertPlainText(
      """
      This is a test.
      #+TAGS: test1 test2
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testLatexEnvironments() {
    assertPlainText(
      """
      This is a test.
      \begin{test}
        Contents 1.
        \begin{equation}
          Contents 2.
        \end{equation}
        Contents 3.
      \end{test}
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testEntities() {
    assertPlainText(
      "This is a test: \\entityone, \\entitytwo{}.\n",
      "This is a test: Dummy0, Dummy1.\n",
    )
  }

  @Test
  fun testLatexFragments() {
    assertPlainText(
      "This is a test: \\test[abc][def]{ghi}.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: \\(E = mc^2\\).\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: \\[E = mc^2\\].\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: $\$E = mc^2$$.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: \$E$.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: \$E = mc^2$.\n",
      "This is a test: Dummy0.\n",
    )
  }

  @Test
  fun testExportSnippets() {
    assertPlainText(
      "This is a test: @@html:<b>@@Test@@html:</b>@@.\n",
      "This is a test: Test.\n",
    )
  }

  @Test
  fun testFootnoteReferences() {
    assertPlainText(
      "This is a test[fn:1].\n",
      "This is a test.\n",
    )
    assertPlainText(
      "This is a test[fn:1:contents].\n",
      "This is a test.\n",
    )
    assertPlainText(
      "This is a test[fn::contents].\n",
      "This is a test.\n",
    )
  }

  @Test
  fun testInlineBabelCalls() {
    assertPlainText(
      "This is a test: call_test(abc, def).\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: call_test[foo](abc, def)[bar].\n",
      "This is a test: Dummy0.\n",
    )
  }

  @Test
  fun testInlineSourceBlocks() {
    assertPlainText(
      "This is a test: src_abc{def}.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: src_abc[foo]{def}.\n",
      "This is a test: Dummy0.\n",
    )
  }

  @Test
  fun testLinks() {
    assertPlainText(
      "This is a test: <<<test>>>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: <<test>>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: <https://bsplines.org/>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: https://bsplines.org/.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: [[https://bsplines.org/]].\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: [[https://bsplines.org/][a *good* test]].\n",
      "This is a test: a good test.\n",
    )
  }

  @Test
  fun testMacros() {
    assertPlainText(
      "This is a test: {{{test(abc, def)}}}.\n",
      "This is a test: Dummy0.\n",
    )
  }

  @Test
  fun testStatisticsCookies() {
    assertPlainText(
      "This is a test: [50%], [1/3].\n",
      "This is a test: Dummy0, Dummy1.\n",
    )
  }

  @Test
  fun testTimestamps() {
    assertPlainText(
      "This is a test: <%%(abv)>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: <1234-05-06 Sat 07:08>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: <1234-05-06 Sat 07:08 +1w>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: <1234-05-06 Sat 07:08 -2d>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: <1234-05-06 Sat 07:08 +1w -2d>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: [1234-05-06 Sat 07:08].\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: <1234-05-06 Sat 07:08>--<1234-05-06 Sat 07:08>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: <1234-05-06 Sat 07:08-09:10>.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: [1234-05-06 Sat 07:08]--[1234-05-06 Sat 07:08].\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: [1234-05-06 Sat 07:08-09:10].\n",
      "This is a test: Dummy0.\n",
    )
  }

  @Test
  fun testTextMarkup() {
    assertPlainText(
      "This is a test: *Test*.\n",
      "This is a test: Test.\n",
    )
    assertPlainText(
      "This is a test: +Test+.\n",
      "This is a test: Test.\n",
    )
    assertPlainText(
      "This is a test: /Test/.\n",
      "This is a test: Test.\n",
    )
    assertPlainText(
      "This is a test: =Test=.\n",
      "This is a test: Dummy0.\n",
    )
    assertPlainText(
      "This is a test: _Test_.\n",
      "This is a test: Test.\n",
    )
    assertPlainText(
      "This is a test: ~Test~.\n",
      "This is a test: Dummy0.\n",
    )
  }
}
