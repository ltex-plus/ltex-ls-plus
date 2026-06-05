/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.program

import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.RegexCodeFragmentizer
import org.bsplines.ltexls.settings.Settings

class ProgramFragmentizer(
  codeLanguageId: String,
) : RegexCodeFragmentizer(
    codeLanguageId,
    ProgramCommentRegexs.fromCodeLanguageId(codeLanguageId).magicCommentRegex,
  ) {
  override fun fragmentize(
    code: String,
    originalSettings: Settings,
  ): List<CodeFragment> = augmentFragments(super.fragmentize(code, originalSettings))

  companion object {
    val DICTIONARY =
      setOf(
        "@param",
        "@return",
        "param",
      )

    val DISABLED_RULES =
      setOf(
        "COPYRIGHT",
        "DASH_RULE",
        "R_SYMBOL",
        "UPPERCASE_SENTENCE_START",
        "WHITESPACE_RULE",
      )

    /**
     * Applies the source-code comment defaults to already-split [fragments]:
     * adds the programmer-jargon [DICTIONARY] entries and disables the prose
     * [DISABLED_RULES] that misfire on code comments (unless the user explicitly
     * enabled them). Shared with [ElispFragmentizer] so Emacs Lisp gets the same
     * defaults as the regex-driven program languages.
     */
    fun augmentFragments(fragments: List<CodeFragment>): List<CodeFragment> {
      val result = ArrayList<CodeFragment>()

      for (oldCodeFragment: CodeFragment in fragments) {
        val settings: Settings = oldCodeFragment.settings

        val dictionary = HashSet<String>(settings.dictionary)
        dictionary.addAll(DICTIONARY)

        val disabledRules = HashSet<String>(settings.disabledRules)

        for (ruleId: String in DISABLED_RULES) {
          if (!settings.enabledRules.contains(ruleId)) {
            disabledRules.add(ruleId)
          }
        }

        result.add(
          oldCodeFragment.copy(
            settings =
              settings.copy(
                _allDictionaries = settings.getModifiedDictionary(dictionary),
                _allDisabledRules = settings.getModifiedDisabledRules(disabledRules),
              ),
          ),
        )
      }

      return result
    }
  }
}
