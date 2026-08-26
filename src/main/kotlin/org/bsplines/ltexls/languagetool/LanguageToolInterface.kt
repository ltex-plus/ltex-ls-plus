/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.parsing.DictionaryMasker

abstract class LanguageToolInterface {
  // Per-language buckets, keyed by `<lang>` or `<lang>-<REGION>`. The match-time
  // filter looks these up using `annotatedTextFragment.codeFragment.languageShortCode`
  // so that disabled-rule suppression keys correctly under the *detected*
  // language when ltex.language="auto" — including the HTTP path, where
  // detection happens server-side and the session-wide settings.languageShortCode
  // stays at the literal "auto".
  //
  var allDisabledRules: Map<String, Set<String>> = emptyMap()

  // The user dictionary, per language. Multi-word entries are joined into one
  // token before the text reaches LanguageTool (CodeAnnotatedTextBuilder.build),
  // so what a match can ever span is the entry's *collapsed* form. Keep a
  // matcher over those forms, rebuilt whenever the dictionary changes: it also
  // supplies the accepted case variants, so `GREENTEAM PENCILTEST` in a heading
  // collapses to `GREENTEAMPENCILTEST` and is still recognized. For a
  // single-word entry collapsing is the identity, so the same matcher covers it.
  var allDictionaries: Map<String, Set<String>> = emptyMap()
    set(value) {
      field = value
      this.collapsedEntryMatchers =
        value.mapValues { (_, entries: Set<String>) ->
          val collapsed: Set<String> =
            entries.mapTo(mutableSetOf()) { DictionaryMasker.collapseSeparators(it) }
          DictionaryMasker(collapsed)
        }
    }

  private var collapsedEntryMatchers: Map<String, DictionaryMasker> = emptyMap()

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
    return !disabledRules.contains(match.ruleId) &&
      !isCoveredByDictionary(annotatedTextFragment, match, fragmentLanguage)
  }

  // Suppress a match whose span is nothing but an accepted word.
  //
  // The test is exact-span equality, and that is the whole safety property: the
  // span cannot include a neighbouring word, so a genuine error beside a
  // dictionary entry is never swallowed, and a complaint reported elsewhere in
  // the sentence is never attributed to us. Overlap or proximity tests were
  // tried and rejected for precisely that reason.
  //
  // Deliberately NOT gated on match.isUnknownWordRule(). LanguageTool Premium
  // reports a joined phrase under QB_NEW_EN_OTHER_ERROR_IDS_6, which carries no
  // `ORTHOGRAPHY` in its id and so fails that predicate; gating would leave
  // phrase entries flagged. Exact-span equality makes the gate unnecessary —
  // whatever rule fired, the diagnostic is about one accepted word and nothing
  // else.
  //
  // Premium's QB_NEW_ / AI_ families sometimes widen a span to take in adjacent
  // punctuation (`amazng!`, `"amazng"`), so the span is normalized first, exactly
  // as the "Add to dictionary" path normalizes before persisting. An
  // all-punctuation span normalizes to "" and is treated as not covered.
  private fun isCoveredByDictionary(
    annotatedTextFragment: AnnotatedTextFragment,
    match: LanguageToolRuleMatch,
    fragmentLanguage: String,
  ): Boolean {
    val matcher: DictionaryMasker? = this.collapsedEntryMatchers[fragmentLanguage]
    val configuredIsEmpty: Boolean = (matcher == null) || matcher.isEmpty
    if (configuredIsEmpty && annotatedTextFragment.additionalDictionary.isEmpty()) return false

    val span: String = annotatedTextFragment.getSubstringOfPlainText(match.fromPos, match.toPos)
    val word: String = DictionaryWord.normalize(span)
    if (word.isEmpty()) return false

    return ((matcher != null) && matcher.isEntry(word)) ||
      annotatedTextFragment.isAdditionalDictionaryEntry(word)
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
