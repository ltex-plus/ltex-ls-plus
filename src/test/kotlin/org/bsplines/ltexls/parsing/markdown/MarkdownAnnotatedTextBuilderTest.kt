/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.markdown

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilderTest
import org.bsplines.ltexls.settings.Settings
import kotlin.test.Test

class MarkdownAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("markdown") {
  @Test
  fun testBasicMarkdown() {
    assertPlainText(
      """
      # Heading
      Paragraph with
      multiple lines and [link](example.com)

      """.trimIndent(),
      "Heading\nParagraph with multiple lines and link\n",
    )
    assertPlainText(
      "This is a  \r\ntest.\r\n",
      "This is a test.\n",
    )
    assertPlainText(
      """
      # This is a &copy; Test
      Another [day &ndash; another](example.com) sentence

      """.trimIndent(),
      "This is a \u00a9 Test\nAnother day \u2013 another sentence\n",
    )
    assertPlainText(
      "This is a test: `inline code`.\n\n```\ncode block\n```\n\nThis is another sentence.\n",
      "This is a test: Dummy0.\n\n\n\n\n\nThis is another sentence.\n",
    )
    val markdownNodes: MutableMap<String, String> = HashMap()
    markdownNodes["Code"] = "default"
    markdownNodes["FencedCodeBlock"] = "default"
    assertPlainText(
      "This is a test: `inline code`.\n\n```\ncode block\n```\n\nThis is another sentence.\n",
      "This is a test: inline code.\n\n\ncode block\n\n\nThis is another sentence.\n",
      Settings(_markdownNodes = markdownNodes),
    )
  }

  @Test
  fun testAngleBracketedLinkUrlWithSpaces() {
    // CommonMark allows spaces (and slashes) inside angle-bracketed link
    // destinations. The link text must replace the whole `[text](<url>)` so
    // surrounding punctuation does not leak into spell-checked text.
    assertPlainText(
      "- Quantum mechanics ([notes](<01 Lectures/Lecture notes 04 - Schroedinger equation.pdf>))\n",
      "Quantum mechanics (notes)\n",
    )
    assertPlainText(
      "See [the file](<path with spaces.pdf>) for details.\n",
      "See the file for details.\n",
    )
    // Windows-style backslash paths: backslashes before non-punctuation
    // characters are preserved literally per CommonMark, so the link parses.
    assertPlainText(
      "Open [the report](<C:\\Users\\My Documents\\report.pdf>) please.\n",
      "Open the report please.\n",
    )
  }

  @Test
  fun testDefinitionExtension() {
    assertPlainText(
      """
      Term1
      : Das ist die Definition von *Term1*.

      Term2

      : Das ist die erste Definition von *Term2*.
      : Das ist die zweite Definition von *Term2*.

      : Das ist die dritte Definition von *Term2*.

      """.trimIndent(),
      """
      Term1.
      Das ist die Definition von Term1.

      Term2.

      Das ist die erste Definition von Term2.
      Das ist die zweite Definition von Term2.

      Das ist die dritte Definition von Term2.

      """.trimIndent(),
    )
  }

  @Test
  fun testGitLabExtension() {
    assertPlainText(
      "This is a test: \$`E = mc^2`\$.\n\n```math\na^2 + b^2 = c^2\n```\n\nThis is another test.\n",
      "This is a test: Dummy0.\n\n\n\n\n\nThis is another test.\n",
    )
  }

  @Test
  fun testStrikethroughExtension() {
    assertPlainText(
      "This is a ~~test~~.\n",
      "This is a test.\n",
    )
  }

  @Test
  fun testTablesExtension() {
    assertPlainText(
      """
      This is a test.

      | First Column | Second Column |
      | ------------ | ------------- |
      | Interesting  | Super         |

      This is another sentence.

      """.trimIndent(),
      """
      This is a test.

      First Column Second Column

      Interesting Super

      This is another sentence.

      """.trimIndent(),
    )
  }

  @Test
  fun testYamlFrontMatterExtension() {
    assertPlainText(
      """
      ---
      # This is YAML front matter
      - test
      ---

      # Heading
      Test sentence

      """.trimIndent(),
      "\n\n\n\n\nHeading\nTest sentence\n",
    )
    assertPlainText(
      """
      ---
      test: This is a test.
      ---

      # Heading
      Test sentence

      """.trimIndent(),
      "\n\n\n\nHeading\nTest sentence\n",
    )
  }

  @Test
  fun testLtexMarkdownExtension() {
    assertPlainText(
      "This is ${"$"}a$, and this is ${"$"}b$.\n",
      "This is Dummy0, and this is Dummy1.\n",
    )
    assertPlainText(
      """
      This is a test: ${"$"}E = mc^2
      $.
      The book is $3, not $5.

      """.trimIndent(),
      "This is a test: Dummy0. The book is $3, not $5.\n",
    )
    assertPlainText(
      "Interesting: $1 \$2 3$.\n",
      "Interesting: Dummy0.\n",
    )
    assertPlainText(
      """
      This is a test.

      ${"$"}$
      a^2 + b^2 = c^2

      ${"$"}$

      This is another test.

      """.trimIndent(),
      "This is a test.\n\n\n\n\n\n\nThis is another test.\n",
    )
    assertPlainText(
      """
      This is a test.

      ${'$'}${'$'}a^2 + b^2 = c^2${'$'}${'$'}

      This is another test.

      ${'$'}${'$'}
      E = mc^2
      ${'$'}${'$'}

      """.trimIndent(),
      "This is a test.\n\nDummy0\n\nThis is another test.\n\n\n\n\n",
    )
  }
}
