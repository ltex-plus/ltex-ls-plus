/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.html

import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.parsing.RegexCodeFragmentizer
import org.bsplines.ltexls.settings.Settings

class HtmlFragmentizer(
  codeLanguageId: String,
) : CodeFragmentizer(codeLanguageId) {
  private val commentFragmentizer = RegexCodeFragmentizer(codeLanguageId, COMMENT_REGEX)

  override fun fragmentize(
    code: String,
    originalSettings: Settings,
  ): List<CodeFragment> = commentFragmentizer.fragmentize(code, originalSettings)

  companion object {
    public const val COMMENT_PATTERN =
      "^[ \t]*<!--[ \t]*(?i)ltex(?-i):(.*?)[ \t]*-->[ \t]*$"
    private val COMMENT_REGEX =
      Regex(COMMENT_PATTERN, RegexOption.MULTILINE)
  }
}
