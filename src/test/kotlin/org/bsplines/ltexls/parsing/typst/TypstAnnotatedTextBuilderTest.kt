/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilderTest
import kotlin.test.Test

class TypstAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("typst") {
  @Test
  fun testLists() {
    assertPlainText(
      """
      This is a test.
      - This is a list
      + And this is numbered list

      """.trimIndent(),
      "This is a test.\nThis is a list\nAnd this is numbered list\n",
    )
  }

  @Test
  fun testHeadings() {
    assertPlainText(
      """
      == Heading in Typst
      More text

      """.trimIndent(),
      "Heading in Typst\nMore text\n",
    )
  }

  @Test
  fun testComments() {
    assertPlainText(
      """
      This is a test.
        //	Comment
      // Another comment
      This is another test.

      """.trimIndent(),
      "This is a test.\n\n\nThis is another test.\n",
    )
  }

  @Test
  fun testMultiLineComments() {
    assertPlainText(
      """
      This is a test.
      /* Comment
      Comment
      */
      More text after the comment.

      """.trimIndent(),
      "This is a test.\n\n\nMore text after the comment.\n",
    )
  }

  @Test
  fun testMarkup() {
    assertPlainText(
      """      
      This is *bold* text.
      This is _emphasis_ text.

      """.trimIndent(),
      "This is bold text.\nThis is emphasis text.\n",
    )
  }

  @Test
  fun testMathMode() {
    assertPlainText(
      """
      This is the math mode $ A = pi r^2 $
      in Typst.
      This is also math $ "exercice" 3 + 4$ in Typst.
      This is the multi line math mode
      $
        A = pi r^2
      $
      in Typst.
      """.trimIndent(),
      """
      This is the math mode 
      in Typst.
      This is also math "exercice" in Typst.
      This is the multi line math mode
      
      in Typst.
      """.trimIndent(),
    )
  }

  @Test
  fun testVariables() {
    assertPlainText(
      """
      #let val = "Joe"
      More text

      """.trimIndent(),
      " \"Joe\"\nMore text\n",
    )
  }

  @Test
  fun testImportStatement() {
    assertPlainText(
      """
      #import "@preview/basic-resume:0.1.3": *
      Text

      """.trimIndent(),
      "\nText\n",
    )
  }

  @Test
  fun testShowStatement() {
    assertPlainText(
      """
      #show: resume.with(
          author: name,
      )
      More text

      """.trimIndent(),
      "\nMore text\n",
    )
  }

  @Test
  fun testCode() {
    assertPlainText(
      """
      Text
      #work(
        title: "Paper12",
      )

      """.trimIndent(),
      "Text\n\nPaper12\n\n",
    )
  }

  @Test
  fun testEscapeCharacter() {
    assertPlainText(
      """
      The amount is \$5
      including VAT. This is a last backslash: \
      """.trimIndent(),
      "The amount is $5\nincluding VAT. This is a last backslash: \\",
    )
  }
}
