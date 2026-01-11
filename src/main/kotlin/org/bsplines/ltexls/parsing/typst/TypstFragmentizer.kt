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
    // Create new code fragment before #table() and #grid() function to prevent
    // false positive "Please add a punctuation mark at the end of paragraph."
    fragments = fragmentizeAtFunction(fragments)
    return fragments
  }

  private fun fragmentizeAtFunction(fragments: List<CodeFragment>): List<CodeFragment> {
    val codeFragments = ArrayList<CodeFragment>()

    for (fragment in fragments) {
      var lastPos = 0

      for (matchResult in FUNCTION_REGEX.findAll(fragment.code)) {
        val matchPos = matchResult.range.first
        codeFragments.add(
          CodeFragment(
            fragment.codeLanguageId,
            fragment.code.substring(lastPos, matchPos),
            fragment.fromPos + lastPos,
            fragment.settings,
          ),
        )
        lastPos = matchPos
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
    private val FUNCTION_REGEX = Regex("(#table\\(|#grid\\()")
  }
}
