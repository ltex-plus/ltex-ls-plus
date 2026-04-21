/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageToolRuleMatchTest {
  @Test
  fun testIsUnknownWordRuleLegacyPrefixes() {
    // MORFOLOGIK_ / HUNSPELL_ prefixes — produced by LanguageTool's local Java
    // engine and anonymous HTTP tier across all Morfologik-backed languages.
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("MORFOLOGIK_RULE_EN_US"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("MORFOLOGIK_RULE_EN_GB"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("MORFOLOGIK_RULE_DE_DE"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("MORFOLOGIK_RULE_PT_BR"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("HUNSPELL_RULE"))
  }

  @Test
  fun testIsUnknownWordRuleLegacySuffixes() {
    // *_SPELLER_RULE / *_SPELLING_RULE — German, Swiss German, Austrian German,
    // French spelling rules.
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("GERMAN_SPELLER_RULE"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("AUSTRIAN_GERMAN_SPELLER_RULE"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("SWISS_GERMAN_SPELLER_RULE"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("FR_SPELLING_RULE"))
  }

  @Test
  fun testIsUnknownWordRuleSlovakSpecialCases() {
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("MUZSKY_ROD_NEZIV_A"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("ZENSKY_ROD_A"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("STREDNY_ROD_A"))
  }

  @Test
  fun testIsUnknownWordRulePremiumQbNewFamily() {
    // QB_NEW_*_ORTHOGRAPHY_* — Premium spell-check families empirically
    // observed via api.languagetoolplus.com for en-US, de-DE, es, fr, nl, pt-BR.
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("QB_NEW_EN_ORTHOGRAPHY_ERROR_IDS_1"))
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("QB_NEW_EN_ORTHOGRAPHY_ERROR_IDS_42"))
    assertTrue(
      LanguageToolRuleMatch.isUnknownWordRule(
        "QB_NEW_DE_OTHER_ERROR_IDS_REPLACEMENT_ORTHOGRAPHY_SPELLING",
      ),
    )
    assertTrue(
      LanguageToolRuleMatch.isUnknownWordRule(
        "QB_NEW_ES_OTHER_ERROR_IDS_REPLACEMENT_ORTHOGRAPHY_SPELLING",
      ),
    )
    assertTrue(
      LanguageToolRuleMatch.isUnknownWordRule(
        "QB_NEW_FR_OTHER_ERROR_IDS_REPLACEMENT_ORTHOGRAPHY_SPELLING",
      ),
    )
    assertTrue(
      LanguageToolRuleMatch.isUnknownWordRule(
        "QB_NEW_NL_OTHER_ERROR_IDS_REPLACEMENT_ORTHOGRAPHY_SPELLING",
      ),
    )
    assertTrue(
      LanguageToolRuleMatch.isUnknownWordRule(
        "QB_NEW_PT_OTHER_ERROR_IDS_REPLACEMENT_ORTHOGRAPHY_SPELLING",
      ),
    )
  }

  @Test
  fun testIsUnknownWordRulePremiumAiFamily() {
    // AI_*_GGEC_REPLACEMENT_ORTHOGRAPHY_* — Premium GGEC rules for de-AT and es-AR.
    // Note the trailing "_SPELL" vs "_SPELLING" variant — both caught via the
    // ORTHOGRAPHY substring.
    assertTrue(
      LanguageToolRuleMatch.isUnknownWordRule("AI_DE_GGEC_REPLACEMENT_ORTHOGRAPHY_SPELL"),
    )
    assertTrue(
      LanguageToolRuleMatch.isUnknownWordRule("AI_ES_GGEC_REPLACEMENT_ORTHOGRAPHY_SPELLING"),
    )
  }

  @Test
  fun testIsUnknownWordRuleSimpleReplaceFamily() {
    // ES_SIMPLE_REPLACE_* — anonymous-tier Spanish common-typo table that
    // carries issueType=misspelling but no MORFOLOGIK_/HUNSPELL_ prefix.
    assertTrue(LanguageToolRuleMatch.isUnknownWordRule("ES_SIMPLE_REPLACE_SIMPLE_ESTAVA"))
  }

  @Test
  fun testIsUnknownWordRuleNegatives() {
    // Null / empty / bare prefix — anchor sanity.
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule(null))
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule(""))
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule("MORFOLOGIK"))

    // Grammar / punctuation / style rules that MUST NOT trigger "Add to dictionary".
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule("QB_NEW_EN_OTHER"))
    assertFalse(
      LanguageToolRuleMatch.isUnknownWordRule(
        "QB_NEW_FR_OTHER_ERROR_IDS_REPLACEMENT_VERB_FORM",
      ),
    )
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule("PLACE_DE_LA_VIRGULE"))
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule("KOMMA_MAAR"))
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule("VERB_COMMA_CONJUNCTION"))
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule("AUX_ETRE_VCONJ"))
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule("ZWEI_INFORMATIONSEINHEITEN_PRO_SATZ"))
  }

  @Test
  fun testIsUnknownWordRuleDocumentedMisses() {
    // Known limitations — spell-check matches we deliberately do NOT catch
    // because their rule IDs contain neither ORTHOGRAPHY/SPELLING nor
    // SIMPLE_REPLACE. If LanguageTool changes the naming or we add an
    // issueType/Type-aware overload later, these assertions should flip to
    // assertTrue.
    //
    // AI_DE_MERGED_MATCH: observed on de-DE-x-simple-language Premium when a
    // cluster of misspellings is merged into one match.
    assertFalse(LanguageToolRuleMatch.isUnknownWordRule("AI_DE_MERGED_MATCH"))
    // QB_NEW_PT_OTHER_ERROR_IDS_REPLACEMENT_OTHER: observed on pt-BR Premium
    // for some misspellings routed under the generic _OTHER replacement tail.
    assertFalse(
      LanguageToolRuleMatch.isUnknownWordRule(
        "QB_NEW_PT_OTHER_ERROR_IDS_REPLACEMENT_OTHER",
      ),
    )
  }
}
