/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.asciidoc

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilderTest
import kotlin.test.Test

class AsciiDocAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("asciidoc") {
  @Test
  fun testList() {
    assertPlainText(
      """
      * item 1
      * item 2
        - item 2.1
        - item 2.2
        ** item 2.2.1
      * item 3
       . item 3.1
       . item 3.2
      """.trimIndent(),
      """
      item 1
      item 2
      item 2.1
      item 2.2
      item 2.2.1
      item 3
      item 3.1
      item 3.2
      """.trimIndent(),
    )
  }

  @Test
  fun testLeadingDot() {
    assertPlainText(
      """
      .Title
       More text
      """.trimIndent(),
      """
      Title
      More text
      """.trimIndent(),
    )
  }

  @Test
  fun testMarkup() {
    assertPlainText(
      """
      _italic_, *bold*, +*not bold*+, \_notItalic_, ^super^, ~sub~, forced +
      line break
      More text
      """.trimIndent(),
      """
      italic, bold, *not bold*, _notItalic_, super, sub, forced
      line break
      More text
      """.trimIndent(),
    )
  }

  @Test
  fun testIndent() {
    assertPlainText(
      "Text\n\nNot *indented*\n\n Indented *text*\ndisables markup",
      "Text\nNot indented\n Indented *text*\ndisables markup",
    )
  }

  @Test
  fun testTable() {
    assertPlainText(
      """
      .CSV data
      [format="csv",cols="3"]
      |===
      1,2,3
      a,b,c
      A,B,C
      |===
      More text
      """.trimIndent(),
      """
      CSV data



      More text
      """.trimIndent(),
    )
  }

  @Test
  fun testTipImportantWarningCaution() {
    assertPlainText(
      """
      TIP: This is a tip text.

      IMPORTANT: This is an important text.

      WARNING: This is a warning text.

      CAUTION: This is a caution text.

      Text. Not a CAUTION: text.
      """.trimIndent(),
      """
      This is a tip text.
      This is an important text.
      This is a warning text.
      This is a caution text.
      Text. Not a CAUTION: text.
      """.trimIndent(),
    )
  }

  @Test
  fun testAttribute() {
    assertPlainText(
      """
      The {value} is contained in the attribute.
      """.trimIndent(),
      """
      The Dummy0 is contained in the attribute.
      """.trimIndent(),
    )
  }

  @Test
  fun testComments() {
    assertPlainText(
      """
      This is a test.
      //	Comment
      This is another test.

      """.trimIndent(),
      "This is a test.\n\nThis is another test.\n",
    )
  }

  @Test
  fun testMultiLineComments() {
    assertPlainText(
      """
      This is a test.
      ////
      Comment
      ////
      More text after the comment.

      """.trimIndent(),
      "This is a test.\n\n\nMore text after the comment.\n",
    )
  }

  @Test
  fun testHeadings() {
    assertPlainText(
      """
      = Heading in AsciiDoc
      
      More text
      
      == A question heading? Is it a problem?
      
      Even more text
      
      === That is a bold statement!
      :test:
      
      More text to read
      
      === A heading with a dot and more attributes.
      :test:
      :test:
      
      More text
      """.trimIndent(),
      """
      Heading in AsciiDoc.
      More text
      A question heading? Is it a problem?
      Even more text
      That is a bold statement!
      More text to read
      A heading with a dot and more attributes.
      More text
      """.trimIndent(),
    )
  }

  @Test
  fun testCodeBlock() {
    assertPlainText(
      """
      Text
      ++++
      <p>test</p>
      ++++
      More text
      [source,php]
      ----

      <?php

      echo "Hello World!";
      
      ?>
      
      ----
      This is PHP code
      This is Java code:
      [source,java]
      class HelloWorld {
        public static void main(String[] args)
        {
            System.out.println("Hello world");
        }
      }
      
      Even more text
      """.trimIndent(),
      "Text\n\n\nMore text\n\n\n\nThis is PHP code\nThis is Java code:\n\nEven more text",
    )
  }

  @Test
  fun testLinks() {
    assertPlainText(
      """
      image:images/picture.png[Alt text]
      link:document.adoc[A document]
      http://google.com[Google search engine]
      mailto:info@email.com[info email]
      More text
      """.trimIndent(),
      "Alt text\nA document\nGoogle search engine\ninfo email\nMore text",
    )
  }
}
