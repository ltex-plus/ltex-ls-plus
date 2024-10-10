/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.html

import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlFragmentizerTest {
  @Test
  fun testValid() {
    val code =
      """
      <!DOCTYPE html>
      text0
      <!-- LTeX:language=de-DE -->
      text
      <!-- just a comment -->
      <!-- > -->
      text"
      <img href="h\"alfjaflajfal<fjfalj>\"">Hallo</img>
      "text"<br><br>a<br>
      <!----><!---->a<!---->
      """.trimIndent()
    val fragmentizer: CodeFragmentizer = CodeFragmentizer.create("html")
    val codeFragments: List<CodeFragment> = fragmentizer.fragmentize(code, Settings())

    var i = 0
    assertEquals("en-US", codeFragments[0].settings.languageShortCode)
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <!DOC..
    assertEquals("html", codeFragments[i++].codeLanguageId) // \ntext0\n
    assertEquals("nop", codeFragments[i].codeLanguageId) // de-DE
    assertEquals("de-DE", codeFragments[i++].settings.languageShortCode)
    assertEquals("html", codeFragments[i].codeLanguageId) // \ntext\n
    assertEquals("de-DE", codeFragments[i++].settings.languageShortCode)
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <!-- just a..
    assertEquals("html", codeFragments[i++].codeLanguageId) // \n
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <!-- > -->
    assertEquals("html", codeFragments[i++].codeLanguageId) // \ntext"\n
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <img..
    assertEquals("html", codeFragments[i++].codeLanguageId) // Hallo
    assertEquals("nop", codeFragments[i++].codeLanguageId) // </img>
    assertEquals("html", codeFragments[i++].codeLanguageId) // \n"text"
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <br>
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <br>
    assertEquals("html", codeFragments[i++].codeLanguageId) // a
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <br>
    assertEquals("html", codeFragments[i++].codeLanguageId) // \n
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <!---->
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <!---->
    assertEquals("html", codeFragments[i++].codeLanguageId) // a
    assertEquals("nop", codeFragments[i++].codeLanguageId) // <!---->
    assertEquals(i, codeFragments.size)
  }

  @Test
  fun testUnclosedTag() {
    val code =
      """
      text
      <unclosed-tag
      text
      """.trimIndent()
    testUnclosed(code)
  }

  @Test
  fun testUnclosedQuote() {
    val code =
      """
      text
      <tag=">
      text
      """.trimIndent()
    testUnclosed(code)
  }

  @Test
  fun testUnclosedEscapedQuote() {
    val code =
      """
      text
      <tag="hallo\"welt\"
      text
      """.trimIndent()
    testUnclosed(code)
  }

  @Test
  fun testUnclosedComment() {
    val code =
      """
      text
      <!--unclosed comment--
      text
      """.trimIndent()
    testUnclosed(code)
  }

  fun testUnclosed(code: String) {
    val fragmentizer: CodeFragmentizer = CodeFragmentizer.create("html")
    val codeFragments: List<CodeFragment> = fragmentizer.fragmentize(code, Settings())

    assertEquals(1, codeFragments.size)
    assertEquals("html", codeFragments[0].codeLanguageId)
  }
}
