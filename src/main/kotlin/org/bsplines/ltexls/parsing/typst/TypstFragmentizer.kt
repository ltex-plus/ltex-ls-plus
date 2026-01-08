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
import java.beans.PropertyEditorManager

class TypstFragmentizer(
  codeLanguageId: String,
) : CodeFragmentizer(codeLanguageId) {
  private val commentFragmentizer = RegexCodeFragmentizer(codeLanguageId, COMMENT_REGEX)

  override fun fragmentize(
    code: String,
    originalSettings: Settings,
  ): List<CodeFragment> {
    var fragments = commentFragmentizer.fragmentize(code, originalSettings)
    // Create new code fragment before #table() function to prevent
    // false positive "Please add a punctuation mark at the end of paragraph."
    fragments = fragmentizeTable(fragments)
    return fragments
  }

  private fun fragmentizeTable(fragments: List<CodeFragment>): List<CodeFragment> {
    val codeFragments = ArrayList<CodeFragment>()

    for (fragment in fragments) {
      var lastPos = 0
      val matches = TABLE_REGEX.findAll(fragment.code).toList()

      if (matches.isEmpty()) {
        codeFragments.add(
          CodeFragment(fragment.codeLanguageId, fragment.code, fragment.fromPos, fragment.settings),
        )
        continue
      }

      for (matchResult in matches) {
        val matchStart = matchResult.range.first
        codeFragments.add(
          CodeFragment(
            fragment.codeLanguageId,
            fragment.code.substring(lastPos, matchStart),
            fragment.fromPos + lastPos,
            fragment.settings,
          ),
        )
        lastPos = matchResult.range.last + 1
      }

      codeFragments.add(
        CodeFragment(
          fragment.codeLanguageId,
          fragment.code.substring(lastPos),
          fragment.fromPos + lastPos,
          fragment.settings,
        ),
      )
    }

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
