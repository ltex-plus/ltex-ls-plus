/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
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
      = Heading in Typst
      More text
      == A question heading? Is it a problem?
      Even more text
      === That is a bold statement!
      More text to read
      === A heading with a dot.
      More text
      """.trimIndent(),
      """
      Heading in Typst.
      More text
      A question heading? Is it a problem?
      Even more text
      That is a bold statement!
      More text to read
      A heading with a dot.
      More text
      """.trimIndent(),
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
  fun testRawCode() {
    assertPlainText(
      """
      Raw code should not be `spell checked`.
      ```php
      function main() {
          echo("Hello World!");
      }
      ```
      You can do the same with the raw element.
      #raw("function main() {echo(\"Hello World!\");}", lang: "php")
      Single `backticks` work, too.
      More text after the code block.
      """.trimIndent(),
      """
      Raw code should not be Dummy0.
      Dummy1
      You can do the same with the raw element.
      Dummy2
      Single Dummy3 work, too.
      More text after the code block.
      """.trimIndent(),
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
      This is the math mode $ A = pi r^2 $ in Typst.
      This is also math $ "exercice" 3 + 4$ in Typst.
      This is the multi line math mode
      $
        A = pi r^2
      $
      in Typst.
      This is the end at time $\t$.
      At time $ t_"end" "maybe" $ I go home.
      """.trimIndent(),
      """
      This is the math mode Dummy0 in Typst.
      This is also math exercice in Typst.
      This is the multi line math mode
      
      in Typst.
      This is the end at time Dummy1.
      At time end maybe I go home.
      """.trimIndent(),
    )
  }

  @Test
  fun testVariables() {
    assertPlainText(
      """
      #set text(lang: "en")
      #let val = "Joe"
      #let json = json("test.json")
      #let alertBox(body, fill: red) = {
        set align(left)  
        set text(white)
        rect(
          fill: fill,
          radius: 2pt,
          inset: 4pt,
          [*Warning:\ #body*],
        )
      }
      #val is the best.

      """.trimIndent(),
      "\nJoe\nDummy0\n\nDummy1 is the best.\n",
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
      More text.
      #show link: underline
      #show link: set text(rgb(0, 0, 255))
      More text.

      """.trimIndent(),
      "\nMore text.\n\n\nMore text.\n",
    )
  }

  @Test
  fun testCode() {
    assertPlainText(
      """
      Text
      #work(
        font: "New Computer Modern",
        title: "Paper12",
      )
      Some text is #text("bold", weight: 800).
      #image("some_text_with_typos.svg")
      This is an image.
      #{
        heading("Title")
      }
      More text.
      """.trimIndent(),
      "Text\nPaper12\nSome text is bold.\nDummy11\nThis is an image.\nTitle\nMore text.",
    )
  }

  @Test
  fun testEnum() {
    assertPlainText(
      """
      Text
      My tasks are: #enum(start: 2)[Go shopping][Clean the porch]
      More text.
      """.trimIndent(),
      "Text\nMy tasks are: \nGo shopping\nClean the porch\n\nMore text.",
    )
  }

  @Test
  fun testEscapeCharacter() {
    assertPlainText(
      """
      The amount is \$5
      including VAT. This is a last backslash: \
      """.trimIndent(),
      "The amount is $5\nincluding VAT. This is a last backslash: ",
    )
  }

  @Test
  fun testCite() {
    assertPlainText(
      """
      The sky is blue.#cite(label("DBLP:books/lib/Hoff2020")) More text.
      """.trimIndent(),
      "The sky is blue. More text.",
    )
  }

  @Test
  fun testLabel() {
    assertPlainText(
      """
      This is a @link to a label.
      = Heading 1<link>
      More text.
      """.trimIndent(),
      "This is a Dummy0 to a label.\nHeading 1.\nMore text.",
    )
  }
}
