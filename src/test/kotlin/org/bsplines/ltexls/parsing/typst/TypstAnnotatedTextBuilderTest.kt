/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilderTest
import org.bsplines.ltexls.settings.Settings
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.languagetool.markup.AnnotatedText
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypstAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("typst") {
  
  fun assertAnnotation(
    code: String,
    expect: String,
    settings: Settings = Settings()
  ) {
    val annotatedText: AnnotatedText = buildAnnotatedText(code, settings)
    assertEquals(code, annotatedText.textWithMarkup)
    assertEquals(expect, annotatedText.plainText)
  }
  
  @Test
  fun testLists() {
    assertAnnotation(
      """
      This is a test.
      - This is a list
      + And this is numbered list

      """.trimIndent(),
      "This is a test.\nThis is a list\nAnd this is numbered list\n",
    )
  }

  @ParameterizedTest
  @MethodSource("provideHeadings")
  fun testHeadings(
    code: String,
    expected: String,
  ) {
    assertAnnotation(code, expected)
  }

  fun provideHeadings(): Stream<Arguments> {
    return Stream.of(
      Arguments.of(
        "= Heading 1",
        "Heading 1.",
      ),
      Arguments.of(
        "== Heading 2",
        "Heading 2.",
      ),
      Arguments.of(
        "=== Heading 3",
        "Heading 3.",
      ),
      Arguments.of(
        """
        = Heading 1
        == Heading 2
        === Heading 3
        """.trimIndent(),
        """
        Heading 1.
        Heading 2.
        Heading 3.
        """.trimIndent(),
      ),
      Arguments.of(
        "=",
        "",
      ),
      Arguments.of(
        """
        = #sym.lambda Calculus
        """.trimIndent(),
        """
        Dummy0 Calculus.
        """.trimIndent(),
      ),
      Arguments.of(
        """
        = ${"$"}lambda$ Calculus
        """.trimIndent(),
        """
        Dummy0 Calculus.
        """.trimIndent(),
      ),
      Arguments.of(
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
      ),
    )
  }

  @Test
  fun testComments() {
    assertAnnotation(
      """
      This is a test.
        //	Comment
      // Another comment
      This is another test.

      """.trimIndent(),
      """
        This is a test.
        This is another test.
        
      """.trimIndent(),
    )
  }

  @Test
  fun testMultiLineComments() {
    assertAnnotation(
      """
      This is a test.
      /* Comment
      Comment
      */
      More text after the comment.

      """.trimIndent(),
      """
      This is a test.

      More text after the comment.
      
      """.trimIndent(),
    )
  }

  @Test
  fun testRawCode() {
    assertAnnotation(
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
      
      You can do the same with the raw element.
      
      Single Dummy1 work, too.
      More text after the code block.
      """.trimIndent(),
    )
  }

  @Test
  fun testMarkup() {
    assertAnnotation(
      """      
      This is *bold* text.
      This is _emphasis_ text.

      """.trimIndent(),
      "This is bold text.\nThis is emphasis text.\n",
    )
  }

  @Test
  fun testMathMode() {
    assertAnnotation(
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
    assertAnnotation(
      """
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
      "\"Joe\"\nDummy0\n\nDummy1 is the best.\n",
    )
  }

  @Test
  fun testImportStatement() {
    assertAnnotation(
      """
      #import "@preview/basic-resume:0.1.3": *
      Text

      """.trimIndent(),
      """
        Text
        
        """.trimIndent(),
    )
  }

  @Test
  fun testShowStatement() {
    assertAnnotation(
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

  @ParameterizedTest
  @MethodSource("provideCodeCases")
  fun testCode(
    code: String,
    expected: String,
  ) {
    assertAnnotation(code, expected)
  }

  fun provideCodeCases(): Stream<Arguments> {
    return Stream.of(
      Arguments.of(
        """
          #sym
        """.trimIndent(),
        """
        """.trimIndent(),
      ),
      Arguments.of(
        """
          #sym.lambda
        """.trimIndent(),
        """
        """.trimIndent(),
      ),
      Arguments.of(
        """
          #sym.lambda(1)
        """.trimIndent(),
        """
        """.trimIndent(),
      ),
      Arguments.of(
        """
          #sym.lambda(1, 2)
        """.trimIndent(),
        """
        """.trimIndent(),
      ),
      Arguments.of(
        """
          #sym.lambda(1, 2)(1, 2)
        """.trimIndent(),
        """
        """.trimIndent(),
      ),
      Arguments.of(
        """
          #sym.lambda(1, 2)[Some Content]
        """.trimIndent(),
        """
          Some Content
          
        """.trimIndent(),
      ),
      Arguments.of(
        """
          #sym.lambda(1, 2)[Some Content](1, 2)
        """.trimIndent(),
        """
          Some Content
          
        """.trimIndent(),
      ),
      Arguments.of(
        """
          #sym.lambda(1, 2)[Some Content 1][Some Content 2]
        """.trimIndent(),
        """
          Some Content 1
          Some Content 2
          
        """.trimIndent(),
      ),
      Arguments.of(
        """
          Text
          #work(
            font: "New Computer Modern",
            title: "Paper12",
          )
          Some text is #text("bold", weight: 800).
          #image("some_text_with_typos.svg")
          This is an image.
          #function[ABC][AB][A]
          More text.
        """.trimIndent(),
        """
          Text
          Paper12
          Some text is bold.
          Dummy11
          This is an image.
          ABC
          AB
          A
          
          More text.
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun testEscapeCharacter() {
    assertAnnotation(
      """
      The amount is \$5
      including VAT. This is a last backslash: \
      """.trimIndent(),
      """
      The amount is $5
      including VAT. This is a last backslash: 
      
      """.trimIndent(),
    )
  }

  @Test
  fun testCite() {
    assertAnnotation(
      """
      The sky is blue.#cite(label("DBLP:books/lib/Hoff2020")) More text.
      """.trimIndent(),
      "The sky is blue. More text.",
    )
  }

  @Test
  fun testLabel() {
    assertAnnotation(
      """
      This is a @link to a label.
      = Heading 1<link>
      More text.
      """.trimIndent(),
      "This is a Dummy0 to a label.\nHeading 1.\nMore text.",
    )
  }
}
