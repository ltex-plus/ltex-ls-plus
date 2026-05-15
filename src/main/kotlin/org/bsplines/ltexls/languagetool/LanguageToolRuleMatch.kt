/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.server.LtexTextDocumentItem
import org.bsplines.ltexls.tools.Tools
import org.eclipse.lsp4j.Range
import org.languagetool.rules.RuleMatch

data class LanguageToolRuleMatch(
  val ruleId: String?,
  val sentence: String?,
  val fromPos: Int,
  val toPos: Int,
  val message: String,
  val suggestedReplacements: List<String>,
  val type: RuleMatch.Type,
  val languageShortCode: String,
) {
  fun isIntersectingWithRange(
    range: Range,
    document: LtexTextDocumentItem,
  ): Boolean =
    Tools.areRangesIntersecting(
      Range(document.convertPosition(this.fromPos), document.convertPosition(this.toPos)),
      range,
    )

  fun isUnknownWordRule(): Boolean = isUnknownWordRule(this.ruleId)

  companion object {
    private val TWO_OR_MORE_SPACES_REGEX = Regex("[ \n]{2,}")

    fun fromLanguageTool(
      match: RuleMatch,
      annotatedTextFragment: AnnotatedTextFragment,
    ): LanguageToolRuleMatch =
      fromLanguageTool(
        match.rule?.id,
        match.sentence?.text,
        match.fromPos,
        match.toPos,
        match.message,
        match.suggestedReplacements,
        match.type,
        annotatedTextFragment,
      )

    fun fromLanguageTool(
      jsonMatch: JsonObject,
      annotatedTextFragment: AnnotatedTextFragment,
    ): LanguageToolRuleMatch {
      val fromPos: Int = jsonMatch.get("offset").asInt
      val suggestedReplacements = ArrayList<String>()

      for (replacement: JsonElement in jsonMatch.get("replacements").asJsonArray) {
        suggestedReplacements.add(replacement.asJsonObject.get("value").asString)
      }

      return fromLanguageTool(
        jsonMatch
          .get("rule")
          .asJsonObject
          .get("id")
          .asString,
        jsonMatch.get("sentence").asString,
        fromPos,
        fromPos + jsonMatch.get("length").asInt,
        jsonMatch.get("message").asString,
        suggestedReplacements,
        RuleMatch.Type.Hint,
        annotatedTextFragment,
      )
    }

    @Suppress("LongParameterList")
    fun fromLanguageTool(
      ruleId: String?,
      sentence: String?,
      fromPos: Int,
      toPos: Int,
      languageToolMessage: String,
      suggestedReplacements: List<String>,
      type: RuleMatch.Type,
      annotatedTextFragment: AnnotatedTextFragment,
    ): LanguageToolRuleMatch {
      val messageBuilder = StringBuilder()

      if (isUnknownWordRule(ruleId)) {
        messageBuilder.append("'")
        messageBuilder.append(annotatedTextFragment.getSubstringOfPlainText(fromPos, toPos))
        messageBuilder.append("': ")
      }

      messageBuilder.append(languageToolMessage)
      val message = messageBuilder.toString().replace(TWO_OR_MORE_SPACES_REGEX, " ").trim()

      return LanguageToolRuleMatch(
        ruleId,
        sentence,
        fromPos,
        toPos,
        message,
        suggestedReplacements,
        type,
        annotatedTextFragment.codeFragment.languageShortCode,
      )
    }

    // Name parses as "is this a rule [that flags an] unknown word", not
    // "is this an unknown [word rule]". The "unknown" refers to the *word*
    // being unknown to LanguageTool (i.e. a spell-check match) — not to the
    // rule being unknown to us. Returns true for spell-check rule families
    // (Morfologik, Hunspell, language-specific _SPELLER_/_SPELLING_ rules,
    // Slovak gender-suffix rules, Premium ORTHOGRAPHY rules, common-typo
    // _SIMPLE_REPLACE_ rules); false for grammar/style/punctuation rules.
    //
    // A clearer name would be `isSpellCheckRule` — not renamed because it
    // would touch every call site and review burden outweighs the
    // readability gain. Leaving the rename as a note in case someone has
    // reason to revisit naming in this file.
    fun isUnknownWordRule(ruleId: String?): Boolean =
      (
        (ruleId != null) &&
          (
            ruleId.startsWith("MORFOLOGIK_") ||
              ruleId.startsWith("HUNSPELL_") ||
              ruleId.endsWith("_SPELLER_RULE") ||
              ruleId.endsWith("_SPELLING_RULE") ||
              (ruleId == "MUZSKY_ROD_NEZIV_A") ||
              (ruleId == "ZENSKY_ROD_A") ||
              (ruleId == "STREDNY_ROD_A") ||
              // Premium/HTTP rule families emitted by api.languagetoolplus.com that
              // don't use the legacy MORFOLOGIK_/HUNSPELL_ prefixes but consistently
              // carry ORTHOGRAPHY in the rule id for spell-check matches (e.g.
              // QB_NEW_EN_ORTHOGRAPHY_ERROR_IDS_1,
              // QB_NEW_DE_OTHER_ERROR_IDS_REPLACEMENT_ORTHOGRAPHY_SPELLING,
              // AI_DE_GGEC_REPLACEMENT_ORTHOGRAPHY_SPELL).
              ruleId.contains("ORTHOGRAPHY") ||
              // Anonymous-tier common-typo rules such as ES_SIMPLE_REPLACE_SIMPLE_ESTAVA.
              ruleId.contains("_SIMPLE_REPLACE_")
          )
      )

    // Premium HTTP rule families (QB_NEW_*, AI_*) sometimes emit spell-check
    // spans that include adjacent punctuation, e.g. `amazng!` (length 7),
    // `"amazng"` (length 8), or multi-word collapses like `recieved teh`.
    // MORFOLOGIK_*, HUNSPELL_*, and *_SPELLER_RULE always emit bare
    // single-word spans, so for those rule families a stored bare-word entry
    // already matches verbatim and no normalization is needed.
    //
    // The dictionary add path (CodeActionProvider.getAddWordToDictionaryCodeAction)
    // and check path (LanguageToolInterface.isCoveredByDictionary) consult this
    // predicate to decide whether to normalize the span before persisting or
    // looking up. Mirrors the rule-family allowlist convention used by
    // isUnknownWordRule above; if Premium adds new rule families that include
    // adjacent punctuation, extend this allowlist accordingly.
    fun isPremiumPunctuationAdjacentSpanRule(ruleId: String?): Boolean =
      (ruleId != null) &&
        (ruleId.startsWith("QB_NEW_") || ruleId.startsWith("AI_"))
  }
}
