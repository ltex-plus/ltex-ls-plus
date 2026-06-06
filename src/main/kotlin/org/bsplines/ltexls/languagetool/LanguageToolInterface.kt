/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import org.bsplines.ltexls.parsing.AnnotatedTextFragment

abstract class LanguageToolInterface {
  // Per-language buckets, keyed by `<lang>` or `<lang>-<REGION>`. The match-time
  // filter looks these up using `annotatedTextFragment.codeFragment.languageShortCode`
  // so that disabled-rule suppression keys correctly under the *detected*
  // language when ltex.language="auto" — including the HTTP path, where
  // detection happens server-side and the session-wide settings.languageShortCode
  // stays at the literal "auto".
  //
  // Note: the user dictionary is no longer consulted here. Dictionary words are
  // masked out of the text before it reaches LanguageTool (see
  // CodeAnnotatedTextBuilder / DictionaryMasker), so no match is ever produced
  // for them and there is nothing to suppress after the fact.
  var allDisabledRules: Map<String, Set<String>> = emptyMap()

  var languageToolOrgUsername = ""
  var languageToolOrgApiKey = ""

  fun check(annotatedTextFragment: AnnotatedTextFragment): List<LanguageToolRuleMatch> {
    val matches = ArrayList<LanguageToolRuleMatch>()

    for (match: LanguageToolRuleMatch in checkInternal(annotatedTextFragment)) {
      if (checkMatchValidity(annotatedTextFragment, match)) matches.add(match)
    }

    return matches
  }

  protected fun checkMatchValidity(
    annotatedTextFragment: AnnotatedTextFragment,
    match: LanguageToolRuleMatch,
  ): Boolean {
    val fragmentLanguage: String = annotatedTextFragment.codeFragment.languageShortCode
    val disabledRules: Set<String> = this.allDisabledRules[fragmentLanguage] ?: emptySet()
    return !disabledRules.contains(match.ruleId)
  }

  abstract fun isInitialized(): Boolean

  protected abstract fun checkInternal(
    annotatedTextFragment: AnnotatedTextFragment,
  ): List<LanguageToolRuleMatch>

  abstract fun activateDefaultFalseFriendRules()

  abstract fun activateLanguageModelRules(languageModelRulesDirectory: String)

  abstract fun enableRules(ruleIds: Set<String>)

  abstract fun enableEasterEgg()
}
