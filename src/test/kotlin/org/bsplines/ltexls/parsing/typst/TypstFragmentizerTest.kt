/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.restructuredtext.RestructuredtextFragmentizerTest
import org.bsplines.ltexls.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

class TypstFragmentizerTest {
  @Test
  fun testFragmentizer() {
    val fragmentizer = TypstFragmentizer("typst")
    var fragments =
      fragmentizer.fragmentize(
        """
        Sentence 1
        #table([A], [B], [C])
        // ltex: language=de-DE
        Sentence 2
        //	ltex:	language=en-GB
        #table([A], [B], [C])
        Sentence 3
        #grid([Test 1],[Test 2])
        Sentence 4
        """.trimIndent(),
        Settings(),
      )
    assertEquals(8, fragments.size)

    assertEquals("typst", fragments[0].codeLanguageId)
    assertEquals(0, fragments[0].fromPos)
    assertEquals(11, fragments[0].code.length)
    assertEquals("en-US", fragments[0].languageShortCode)

    assertEquals("typst", fragments[1].codeLanguageId)
    assertEquals(11, fragments[1].fromPos)
    assertEquals(22, fragments[1].code.length)
    assertEquals("en-US", fragments[1].languageShortCode)

    assertEquals("nop", fragments[2].codeLanguageId)
    assertEquals(33, fragments[2].fromPos)
    assertEquals(23, fragments[2].code.length)
    assertEquals("de-DE", fragments[2].languageShortCode)

    assertEquals("typst", fragments[3].codeLanguageId)
    assertEquals(56, fragments[3].fromPos)
    assertEquals(12, fragments[3].code.length)
    assertEquals("de-DE", fragments[3].languageShortCode)

    assertEquals("nop", fragments[4].codeLanguageId)
    assertEquals(68, fragments[4].fromPos)
    assertEquals(23, fragments[4].code.length)
    assertEquals("en-GB", fragments[4].languageShortCode)

    assertEquals("typst", fragments[5].codeLanguageId)
    assertEquals(91, fragments[5].fromPos)
    assertEquals(1, fragments[5].code.length)
    assertEquals("en-GB", fragments[5].languageShortCode)

    assertEquals("typst", fragments[6].codeLanguageId)
    assertEquals(92, fragments[6].fromPos)
    assertEquals(33, fragments[6].code.length)
    assertEquals("en-GB", fragments[6].languageShortCode)

    assertEquals("typst", fragments[7].codeLanguageId)
    assertEquals(125, fragments[7].fromPos)
    assertEquals(35, fragments[7].code.length)
    assertEquals("en-GB", fragments[7].languageShortCode)

    fragments = fragmentizer.fragmentize("Sentence 1\n#tab()\nSentence 2", Settings())
    assertEquals(1, fragments.size)
  }

  @Test
  fun testWrongSettings() {
    val fragmentizer = TypstFragmentizer("typst")
    fragmentizer.fragmentize("Sentence 1\n\n// ltex: languagede-DE\n\nSentence 2\n", Settings())
    fragmentizer.fragmentize("Sentence 1\n\n// ltex: unknownKey=abc\n\nSentence 2\n", Settings())
  }
}
