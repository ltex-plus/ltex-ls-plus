/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import org.languagetool.markup.AnnotatedText
import org.languagetool.markup.AnnotatedTextBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
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

  // Build an AnnotatedText matching what ProgramAnnotatedTextBuilder produces for
  //   /*\n\nThis is a mistace\n\n*/\n
  // followed by a block of plain code, then another comment with prose. The
  // structure is what triggers Premium's QB orthography rule to extend its
  // match span into the trailing markup until the next TEXT segment.
  private fun buildAnnotatedTextLikeProgramFragment(): AnnotatedText {
    val builder = AnnotatedTextBuilder()
    builder.addMarkup("/*\n\n", "\n\n\n\n\n")
    builder.addText("This is a mistace")
    builder.addMarkup(
      "\n\n*/\n\n#include <config.h>\n\n#include \"lisp.h\"\n",
      "\n\n",
    )
    builder.addMarkup("/* ", "\n\n")
    builder.addText("Marker used within call-interactively to refer to point.")
    builder.addMarkup("  */\n", "\n\n")
    return builder.build()
  }

  @Test
  fun testClampToPosToTextSegmentEndTrimsOverextendedSpan() {
    // The exact bug from Premium QB_NEW_*_ORTHOGRAPHY: LT returns offset/length
    // that map to source[14..190] — covering "mistace" plus all the intervening
    // markup up to just before the next TEXT segment "Marker…". Clamp should
    // trim toPos back to the end of the TEXT segment containing fromPos
    // (mistace's segment ends at source offset 21 = 4 + len("This is a mistace")).
    val annotatedText: AnnotatedText = buildAnnotatedTextLikeProgramFragment()
    val clamped: Int = LanguageToolRuleMatch.clampToPosToTextSegmentEnd(annotatedText, 14, 190)
    assertEquals(21, clamped)
  }

  @Test
  fun testClampToPosToTextSegmentEndLeavesInSegmentRangeUnchanged() {
    // Legitimate multi-word match entirely inside a TEXT segment, e.g.
    // "a mistace" at source[12..21] within "This is a mistace". No clamping
    // should occur because the toPos doesn't cross the segment boundary.
    val annotatedText: AnnotatedText = buildAnnotatedTextLikeProgramFragment()
    val clamped: Int = LanguageToolRuleMatch.clampToPosToTextSegmentEnd(annotatedText, 12, 21)
    assertEquals(21, clamped)
  }

  @Test
  fun testClampToPosToTextSegmentEndDoesNotShrinkBelowToPos() {
    // toPos already smaller than segment end: clamp must not extend it.
    val annotatedText: AnnotatedText = buildAnnotatedTextLikeProgramFragment()
    val clamped: Int = LanguageToolRuleMatch.clampToPosToTextSegmentEnd(annotatedText, 14, 17)
    assertEquals(17, clamped)
  }

  @Test
  fun testClampToPosToTextSegmentEndFromPosInMarkupIsNoOp() {
    // If fromPos lands inside a MARKUP part (shouldn't happen for a real
    // spell-check match against user prose, but the helper should be defensive
    // and not produce surprising clamps). The trailing markup begins at
    // source[21] — pick a fromPos inside it.
    val annotatedText: AnnotatedText = buildAnnotatedTextLikeProgramFragment()
    val clamped: Int = LanguageToolRuleMatch.clampToPosToTextSegmentEnd(annotatedText, 25, 190)
    assertEquals(190, clamped)
  }

  @Test
  fun testClampToPosToTextSegmentEndClampsToNearestTextSegment() {
    // fromPos in the second TEXT segment ("Marker used…") — clamp should snap
    // to that segment's end, not to the first one. Segment "Marker…" starts at
    // source offset 21 + 39 + 3 = 63 (markup len 39 + opening "/* " len 3) and
    // is 56 chars long, ending at source[119]. A toPos beyond that gets
    // clamped to 119.
    val annotatedText: AnnotatedText = buildAnnotatedTextLikeProgramFragment()
    val markerSegmentStart =
      4 + "This is a mistace".length +
        "\n\n*/\n\n#include <config.h>\n\n#include \"lisp.h\"\n".length + "/* ".length
    val markerSegmentEnd =
      markerSegmentStart +
        "Marker used within call-interactively to refer to point.".length
    val clamped: Int =
      LanguageToolRuleMatch.clampToPosToTextSegmentEnd(
        annotatedText,
        markerSegmentStart + 1,
        markerSegmentEnd + 50,
      )
    assertEquals(markerSegmentEnd, clamped)
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
