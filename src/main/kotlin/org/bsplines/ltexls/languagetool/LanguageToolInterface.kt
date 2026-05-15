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
  // The user dictionary is stored verbatim. The add path (CodeActionProvider)
  // sends bare words to the client in the "Add to dictionary" command — for
  // matches from rule families that emit punctuation-adjacent spans (Premium
  // QB_NEW_* / AI_*) the matched substring is stripped via
  // DictionaryWord.normalize before being persisted, so the on-disk entry is
  // the bare word. For every other rule family the substring is already bare
  // and is persisted as-is. The server cannot enforce what the client
  // actually writes to disk, but the reference clients write what we send.
  //
  // Hand-edited entries are intentionally left untouched at load time: the
  // file on disk is the user's data and may carry punctuation or whitespace
  // by deliberate choice. Normalization on the check side happens only when
  // the match's rule id falls into the Premium punctuation-adjacent family —
  // see isCoveredByDictionary below.
  var dictionary: Set<String> = emptySet()
  var disabledRules: Set<String> = emptySet()

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
  ): Boolean =
    !this.disabledRules.contains(match.ruleId) &&
      (!match.isUnknownWordRule() || !isCoveredByDictionary(annotatedTextFragment, match))

  // Suppress a match if the user's dictionary contains the flagged word.
  //
  // The lookup key depends on which rule family fired the match. Premium's
  // QB_NEW_* / AI_* families sometimes emit spans that include adjacent
  // punctuation, e.g. `amazng!` (length 7) or `"amazng"` (length 8); for
  // those rules we strip leading/trailing non-letter-non-digit characters
  // via DictionaryWord.normalize so a single stored entry `amazng` suppresses
  // every punctuation-context variant.
  //
  // For every other rule family (Morfologik / Hunspell / *_SPELLER_RULE /
  // Slovak gender rules / _SIMPLE_REPLACE_) the span is already bare and we
  // look it up literally — gaining a stronger no-behavior-change guarantee
  // for free-LT and local-Java users: not just a no-op normalize call but
  // no normalize call at all on those code paths.
  //
  // Multi-word phrase entries (e.g. a hand-typed `"LTEX LS"`, or the local
  // Java backend's all-caps Morfologik collapse on `LTEX LS`) still suppress:
  // those matches fall in the literal branch, and the dictionary holds the
  // phrase verbatim.
  //
  // An entirely-punctuation span normalizes to "" (Premium branch) and is
  // treated as not covered; the diagnostic stays visible. The user can
  // disable the rule or fix the text in that exotic case.
  private fun isCoveredByDictionary(
    annotatedTextFragment: AnnotatedTextFragment,
    match: LanguageToolRuleMatch,
  ): Boolean {
    val span: String = annotatedTextFragment.getSubstringOfPlainText(match.fromPos, match.toPos)
    val lookupKey: String =
      if (LanguageToolRuleMatch.isPremiumPunctuationAdjacentSpanRule(match.ruleId)) {
        DictionaryWord.normalize(span)
      } else {
        span
      }
    return lookupKey.isNotEmpty() && this.dictionary.contains(lookupKey)
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
