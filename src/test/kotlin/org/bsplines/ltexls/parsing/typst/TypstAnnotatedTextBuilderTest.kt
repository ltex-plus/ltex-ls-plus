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
        sum_(k=0)^n k
        &= 1 + ... + n \
        &= (n(n+1)) / 2
      $
      in Typst.
      This is the end at time $\t$.
      At time $ t_"end" "maybe" $ I go home.
      #let x = $"text"$
      Text is important.
      $
        dim(D_n) = cases(
          0 "                   if " n>1 " or " n<1,
          1 "                   if " n=1,
        )
      $
      More text for this test.
      """.trimIndent(),
      """
      This is the math mode Dummy0 in Typst.
      This is also math exercice in Typst.
      This is the multi line math mode
      
      in Typst.
      This is the end at time Dummy1.
      At time end maybe I go home.
      
      Text is important.
      if or if
      More text for this test.
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
      #let f() = {"dummy text"}
      #let date =  datetime.today().display("[year]")
      #let heading(
        heading: []
      ) = box(content: heading)
      #let add(x, y) = x + y
      #let colors = (
        blue: "blue-colored",
        red: "red-colored",
      )
      #let (x, y) = (1, 
      2)
      #val is the best.

      """.trimIndent(),
      "\nJoe\nWarning: Dummy0\ndummy text\n[year]\n\nblue-colored red-colored\n\nDummy44 is the best.\n",
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
      #show: abbr.show-rule
      More text.
      #show link: underline
      #show link: set text(rgb(0, 0, 255))
      More text.

      """.trimIndent(),
      "\n\nMore text.\n\n\nMore text.\n",
    )
  }

  @Test
  fun testCode() {
    assertPlainText(
      """
      Text
      #work(
        font: "New Computer Modern",
        title: "Paper12())",
      )
      Some text is #text("bold", weight: 800).
      #image("some_text_with_typos.svg")
      This is an image.
      #{
        heading("Title")
      }
      More text.
      """.trimIndent(),
      "Text\nPaper12\nSome text is bold.\nDummy17\nThis is an image.\nTitle\nMore text.",
    )
  }

  @Test
  fun testIfElse() {
    assertPlainText(
      """
      Conditionals.
      #if 1 = 2 [
      Text if true.
      ] else if 3 = 4 [
      Text if else true.
      ] else [
      Text if false.
      ]
      More text after if-else.
      """.trimIndent(),
      """
      Conditionals.

      Text if true.
      
      Text if else true.
      
      Text if false.

      More text after if-else.
      """.trimIndent(),
    )
  }

  @Test
  fun testLoops() {
    assertPlainText(
      """
      Loops.
      #while n < 12 {
        n = n + 1
      }
      #for c in "ABC" [
        #c
      ]
      More text after the loops.
      """.trimIndent(),
      """
      Loops.

      n = n + 1


      Dummy0

      More text after the loops.
      """.trimIndent(),
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
      "Text\nMy tasks are: \nGo shopping\nClean the porch\nMore text.",
    )
  }

  @Test
  fun testStyle() {
    assertPlainText(
      """
      Text #highlight[can] #upper[be] #sub[styled] in #strong[different] ways.
      """.trimIndent(),
      "Text can be styled in different ways.",
    )
  }

  @Test
  fun testTable1() {
    assertPlainText(
      """
      Tables.
      #table(
        columns: 2,
        [Column One], [Column Two],
        [
          First #strong[text].
        ],
        [
          Second text.
        ],
      )
      More text after the table.
      """.trimIndent(),
      "Tables.\nColumn One Column Two \nFirst text.\n \nSecond text.\n\nMore text after the table.",
    )
  }

  @Test
  fun testTable2() {
    assertPlainText(
      """
      This is an empty table: #table([], [], [])
      Dots do not get a leading whitespace: #table([TEST], [.])
      More text after the table.
      """.trimIndent(),
      "This is an empty table: \nDots do not get a leading whitespace: TEST.\nMore text after the table.",
    )
  }

  @Test
  fun testConditionalHyphen() {
    assertPlainText(
      "Cond-?itional hypens?",
      "Conditional hypens?",
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
      The sky is blue.#cite(label("DBLP:books/lib/Hoff2020"))
      The sea is blue#footnote[See $ a = (2+3)*8 $].
      More text.
      """.trimIndent(),
      "The sky is blue.\nThe sea is blue.\nMore text.",
    )
  }

  @Test
  fun testReferences() {
    assertPlainText(
      """
      See @source-one, @source.two; and @source-three.
      The Worm #footnote[a self-replicating program] @source-four. Next sentence.
      Attacks are documented by Aleph One's work, which sparked further investigation @burow2019.
      Multiple sources support this @first-source @second-source.
      Open @section:three for details.
      """.trimIndent(),
      """
      See (citation), (citation); and (citation).
      The Worm (citation). Next sentence.
      Attacks are documented by Aleph One's work, which sparked further investigation (citation).
      Multiple sources support this (citation) (citation).
      Open Dummy0 for details.
      """.trimIndent(),
    )
  }

  @Test
  fun testAbbreviationReferences() {
    assertPlainText(
      """
      #abbr.add(short: "ABI", entry: "application binary interface")
      #abbr.add("MOL", "method of lines")
      An @ABI protects memory.
      The @MOL is a procedure.
      @ABI:pls are interfaces.
      """.trimIndent(),
      """
      Dummy53 application binary interface
      Dummy54 method of lines
      An element protects memory.
      The object is a procedure.
      Elements are interfaces.
      """.trimIndent(),
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
