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
  fun test() {
    val fragmentizer: CodeFragmentizer = CodeFragmentizer.create("html")
    val code = """<!DOCTYPE html>
    <head>
    <title>Test</title>
    </head>
    <body>
    <h1>Sentence 1</h>

      <!-- ltex: language=de-DE-->

    <p> !--Sentence 2</p>

    <!--			ltex:				language=en-US		-->

    <p>Sentence 3
    </p>
    </body>
    """
    val codeFragments: List<CodeFragment> = fragmentizer.fragmentize(code, Settings())
    assertEquals(5, codeFragments.size)

    assertEquals("html", codeFragments[0].codeLanguageId)
    assertEquals("en-US", codeFragments[0].settings.languageShortCode)
    assertEquals(0, codeFragments[0].fromPos)

    assertEquals("nop", codeFragments[1].codeLanguageId)
    assertEquals("de-DE", codeFragments[1].settings.languageShortCode)

    assertEquals("html", codeFragments[2].codeLanguageId)
    assertEquals("de-DE", codeFragments[2].settings.languageShortCode)

    assertEquals("nop", codeFragments[3].codeLanguageId)
    assertEquals("en-US", codeFragments[3].settings.languageShortCode)

    assertEquals("html", codeFragments[4].codeLanguageId)
    assertEquals("en-US", codeFragments[4].settings.languageShortCode)
  }
}
