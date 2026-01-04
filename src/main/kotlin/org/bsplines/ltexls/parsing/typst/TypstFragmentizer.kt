/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.parsing.RegexCodeFragmentizer
import org.bsplines.ltexls.settings.Settings

class TypstFragmentizer(
  codeLanguageId: String,
) : CodeFragmentizer(codeLanguageId) {
  private val commentFragmentizer = RegexCodeFragmentizer(codeLanguageId, COMMENT_REGEX)

  override fun fragmentize(
    code: String,
    originalSettings: Settings,
  ): List<CodeFragment> {
    // Create new code fragment before #table() function to prevent
    // false positive "Please add a punctuation mark at the end of paragraph."
    var fragments = fragmentizeTable(code, originalSettings)
    fragments = commentFragmentizer.fragmentize(fragments)
    return fragments
  }

  private fun fragmentizeTable(
    code: String,
    settings: Settings,
  ): List<CodeFragment> {
    val codeFragments = ArrayList<CodeFragment>()
    var curPos = 0

    for (matchResult: MatchResult in TABLE_REGEX.findAll(code)) {
      val lastPos: Int = curPos
      curPos = matchResult.range.first
      val lastCode: String = code.substring(lastPos, curPos)
      codeFragments.add(CodeFragment(codeLanguageId, lastCode, lastPos, settings))
    }

    // Add the remaining code after the last match
    val remainingCode = code.substring(curPos)
    codeFragments.add(CodeFragment(codeLanguageId, remainingCode, curPos, settings))

    return codeFragments
  }

  companion object {
    private val COMMENT_REGEX =
      Regex(
        "^[ \t]*\\/\\/[ \t]*(?i)ltex(?-i):(.*?)[ \t]*$",
        RegexOption.MULTILINE,
      )
    private val TABLE_REGEX = Regex("(?=#table\\()")
  }
}
