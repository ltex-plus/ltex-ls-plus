/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.gitcommit

import org.bsplines.ltexls.parsing.RegexCodeFragmentizer

class GitCommitFragmentizer(
  codeLanguageId: String,
) : RegexCodeFragmentizer(codeLanguageId, REGEX) {
  companion object {
    private val REGEX =
      Regex(
        "^[ \t]*#[ \t]+(?i)ltex(?-i):(.*?)[ \t]*$",
        RegexOption.MULTILINE,
      )
  }
}
