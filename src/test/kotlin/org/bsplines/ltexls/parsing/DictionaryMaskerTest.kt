/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.settings.Settings
import org.languagetool.markup.TextPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DictionaryMaskerTest {
  private fun matches(
    dictionary: Set<String>,
    text: String,
  ): List<String> = DictionaryMasker(dictionary).findMatches(text).map { text.substring(it) }

  private fun textPart(text: String): DictionaryMasker.Part =
    DictionaryMasker.Part(TextPart.Type.TEXT, text)

  private fun markupPart(
    code: String,
    interpretAs: String = "",
  ): DictionaryMasker.Part = DictionaryMasker.Part(TextPart.Type.MARKUP, code, interpretAs)

  private fun maskParts(
    dictionary: Set<String>,
    parts: List<DictionaryMasker.Part>,
  ): List<DictionaryMasker.Part> {
    var dummyCounter = 0
    return DictionaryMasker(dictionary).maskParts(parts) { "Dummy${dummyCounter++}" }
  }

  // --- findMatches: whole-token, case-sensitive, longest-match-wins scan ---

  @Test
  fun emptyDictionaryIsNoOp() {
    val masker = DictionaryMasker(emptySet())
    assertTrue(masker.isEmpty)
    assertTrue(masker.findMatches("hello world").isEmpty())

    val parts: List<DictionaryMasker.Part> = listOf(textPart("hello world"))
    assertEquals(parts, maskParts(emptySet(), parts))
  }

  @Test
  fun blankEntriesAreDropped() {
    // Includes a no-break-space-only entry, which is blank after space
    // normalization.
    assertTrue(DictionaryMasker(setOf("", "  ", "\t", "\u00a0")).isEmpty)
  }

  @Test
  fun spaceSeparatorsAreNormalizedForMatching() {
    // A no-break space (U+00A0) in the text matches a normal-space entry.
    assertEquals(
      listOf("GreenTeam\u00a0Penciltest"),
      matches(setOf("GreenTeam Penciltest"), "the GreenTeam\u00a0Penciltest firm"),
    )
    // Newlines are structural, not space separators: no match across them.
    assertTrue(matches(setOf("GreenTeam Penciltest"), "GreenTeam\nPenciltest").isEmpty())
  }

  @Test
  fun singleWordIsMatchedOnWholeTokenBoundaries() {
    assertEquals(listOf("GreenTeam"), matches(setOf("GreenTeam"), "I met GreenTeam today."))
  }

  @Test
  fun singleWordNotMatchedInsideLongerWord() {
    // "GreenTeam" must not match inside "GreenTeamer" / "aGreenTeam".
    assertTrue(matches(setOf("GreenTeam"), "a GreenTeamer b").isEmpty())
    assertTrue(matches(setOf("GreenTeam"), "a aGreenTeam b").isEmpty())
  }

  @Test
  fun multiWordPhraseIsMatchedWhenAdjacent() {
    assertEquals(
      listOf("GreenTeam Penciltest"),
      matches(setOf("GreenTeam Penciltest"), "the GreenTeam Penciltest firm"),
    )
  }

  @Test
  fun loneFirstWordNotMatchedWhenOnlyPhraseIsAnEntry() {
    // The whole point of multi-word support: a bare "GreenTeam" stays visible
    // unless "GreenTeam" is itself an entry.
    assertTrue(matches(setOf("GreenTeam Penciltest"), "a GreenTeam here").isEmpty())
  }

  @Test
  fun longestEntryWinsOnOverlap() {
    // Both "GreenTeam" and the phrase are entries; the phrase wins where present.
    assertEquals(
      listOf("GreenTeam Penciltest"),
      matches(setOf("GreenTeam", "GreenTeam Penciltest"), "GreenTeam Penciltest"),
    )
    // ...but a lone "GreenTeam" still matches because it is also an entry.
    assertEquals(
      listOf("GreenTeam"),
      matches(setOf("GreenTeam", "GreenTeam Penciltest"), "GreenTeam alone"),
    )
  }

  @Test
  fun matchingIsCaseSensitive() {
    assertTrue(matches(setOf("GreenTeam Penciltest"), "greenteam penciltest").isEmpty())
  }

  @Test
  fun lowercaseEntryMatchesSentenceInitialTitlecase() {
    // hunspell / LT-speller convention: a lowercase-initial entry also accepts
    // its titlecase variant (sentence start) — but no other case variant.
    assertEquals(listOf("Foobar"), matches(setOf("foobar"), "Foobar is here."))
    assertTrue(matches(setOf("foobar"), "fooBar is here.").isEmpty())
    // The reverse does not hold: a capitalized entry stays capitalized.
    assertTrue(matches(setOf("Foobar"), "foobar is here.").isEmpty())
  }

  @Test
  fun entryMatchesAllUppercaseVariant() {
    assertEquals(
      listOf("GREENTEAM PENCILTEST"),
      matches(setOf("GreenTeam Penciltest"), "GREENTEAM PENCILTEST ROADMAP"),
    )
  }

  @Test
  fun surroundingPunctuationIsExcluded() {
    assertEquals(
      listOf("GreenTeam Penciltest"),
      matches(setOf("GreenTeam Penciltest"), "(GreenTeam Penciltest)."),
    )
  }

  @Test
  fun unicodeWordBoundariesAreRespected() {
    // Accented letters are word characters (Char.isLetterOrDigit, not ASCII \b),
    // so "café" is not matched inside "cafés", but a standalone accented entry is.
    assertTrue(matches(setOf("café"), "les cafés ici").isEmpty())
    assertEquals(listOf("café"), matches(setOf("café"), "un café noir"))
  }

  // --- maskParts: masking over the assembled plain text of a part list ---

  @Test
  fun maskPartsMasksWithinSingleTextPart() {
    assertEquals(
      listOf(textPart("I met "), markupPart("GreenTeam", "Dummy0"), textPart(" today.")),
      maskParts(setOf("GreenTeam"), listOf(textPart("I met GreenTeam today."))),
    )
  }

  @Test
  fun maskPartsMasksMultipleOccurrencesWithDistinctDummies() {
    assertEquals(
      listOf(
        markupPart("GreenTeam", "Dummy0"),
        textPart(" and "),
        markupPart("GreenTeam", "Dummy1"),
      ),
      maskParts(setOf("GreenTeam"), listOf(textPart("GreenTeam and GreenTeam"))),
    )
  }

  @Test
  fun maskPartsMasksMarkupSplitPhrase() {
    // `LT<sub>E</sub>X LS` assembles to the plain text `LTEX LS`; the covered
    // parts coalesce into one markup part whose source is the original code.
    assertEquals(
      listOf(
        textPart("This is "),
        markupPart("LT<sub>E</sub>X LS", "Dummy0"),
        textPart("."),
      ),
      maskParts(
        setOf("LTEX LS"),
        listOf(
          textPart("This is LT"),
          markupPart("<sub>"),
          textPart("E"),
          markupPart("</sub>"),
          textPart("X LS."),
        ),
      ),
    )
  }

  @Test
  fun maskPartsMasksWordSplitByAccentCommand() {
    // LaTeX `M\"uller` becomes TEXT(M) + MARKUP(\"u -> u-umlaut) + TEXT(ller);
    // the entry matches the assembled plain text `Müller`.
    assertEquals(
      listOf(
        textPart("Herr "),
        markupPart("M\\\"uller", "Dummy0"),
        textPart(" kommt."),
      ),
      maskParts(
        setOf("Müller"),
        listOf(
          textPart("Herr M"),
          markupPart("\\\"u", "ü"),
          textPart("ller kommt."),
        ),
      ),
    )
  }

  @Test
  fun maskPartsMasksPhraseAcrossSoftLineBreak() {
    // A Markdown soft line break is markup interpreted as a space, so a phrase
    // wrapped over the break is contiguous only in the assembled plain text.
    assertEquals(
      listOf(
        textPart("the "),
        markupPart("GreenTeam\nPenciltest", "Dummy0"),
        textPart(" firm"),
      ),
      maskParts(
        setOf("GreenTeam Penciltest"),
        listOf(
          textPart("the GreenTeam"),
          markupPart("\n", " "),
          textPart("Penciltest firm"),
        ),
      ),
    )
  }

  @Test
  fun maskPartsMasksPlainSpaceEntryOverLatexTie() {
    // A LaTeX tie (`~`) puts a no-break space (U+00A0) into the plain text;
    // space separators are normalized to a plain space on both sides of the
    // comparison, so the normal-space entry masks the tied occurrence — and
    // an entry containing the no-break space works just the same.
    val parts: List<DictionaryMasker.Part> =
      listOf(
        textPart("the GreenTeam"),
        markupPart("~", "\u00a0"),
        textPart("Penciltest firm"),
      )
    val expected: List<DictionaryMasker.Part> =
      listOf(textPart("the "), markupPart("GreenTeam~Penciltest", "Dummy0"), textPart(" firm"))
    assertEquals(expected, maskParts(setOf("GreenTeam Penciltest"), parts))
    assertEquals(expected, maskParts(setOf("GreenTeam\u00a0Penciltest"), parts))
  }

  @Test
  fun maskPartsMasksEntryEqualToWholeInterpretAs() {
    // Match boundaries at part edges are fine even when the whole match lies
    // inside one markup's interpretAs.
    assertEquals(
      listOf(textPart("use "), markupPart("\\LaTeX", "Dummy0"), textPart(" now")),
      maskParts(
        setOf("LaTeX"),
        listOf(textPart("use "), markupPart("\\LaTeX", "LaTeX"), textPart(" now")),
      ),
    )
  }

  @Test
  fun maskPartsSkipsMatchWithBoundaryInsideInterpretAs() {
    // The match would start in the middle of the markup's interpretAs; its
    // source cannot be split at a plain-text position, so the match is skipped.
    val parts: List<DictionaryMasker.Part> =
      listOf(
        markupPart("\\shorthand{}", "e.g. GreenTeam"),
        textPart(" Penciltest here"),
      )
    assertEquals(parts, maskParts(setOf("GreenTeam Penciltest"), parts))
  }

  @Test
  fun maskPartsKeepsZeroPlainMarkupAtMatchEdgesOutside() {
    // Zero-plain-length markup exactly at a match boundary stays outside the
    // masked span (minimal span), on both sides.
    assertEquals(
      listOf(
        textPart("met "),
        markupPart("GreenTeam", "Dummy0"),
        markupPart("**"),
        textPart(" more"),
      ),
      maskParts(
        setOf("GreenTeam"),
        listOf(textPart("met GreenTeam"), markupPart("**"), textPart(" more")),
      ),
    )
    assertEquals(
      listOf(
        textPart("met "),
        markupPart("**"),
        markupPart("GreenTeam", "Dummy0"),
        textPart(" more"),
      ),
      maskParts(
        setOf("GreenTeam"),
        listOf(textPart("met "), markupPart("**"), textPart("GreenTeam more")),
      ),
    )
  }

  // --- builder-level wiring (PlaintextAnnotatedTextBuilder is verbatim, so the
  // plain text shows the dummy substituted for the masked span) ---

  private fun plainTextWithDictionary(
    code: String,
    dictionary: Set<String>,
  ): String {
    val builder: CodeAnnotatedTextBuilder = CodeAnnotatedTextBuilder.create("plaintext")
    builder.setSettings(Settings(_allDictionaries = mapOf(Pair("en-US", dictionary))))
    return builder.addCode(code).build().plainText
  }

  @Test
  fun builderMasksMultiWordEntryWithDummy() {
    assertEquals(
      "I met Dummy0 today.\n",
      plainTextWithDictionary("I met GreenTeam Penciltest today.\n", setOf("GreenTeam Penciltest")),
    )
  }

  @Test
  fun builderLeavesLoneWordUnmasked() {
    assertEquals(
      "I met GreenTeam today.\n",
      plainTextWithDictionary("I met GreenTeam today.\n", setOf("GreenTeam Penciltest")),
    )
  }

  @Test
  fun builderWithoutDictionaryIsUnchanged() {
    assertFalse(plainTextWithDictionary("I met GreenTeam today.\n", emptySet()).contains("Dummy"))
  }

  @Test
  fun builderAlwaysUsesDefaultDummy() {
    // Deliberately NOT the vowel-initial dummy ("Ina0") for vowel-initial
    // masked words: LanguageTool Premium's AI rules flag "Ina0" itself, which
    // would surface a diagnostic exactly on the masked dictionary word
    // (pinned by LanguageToolPremiumIntegrationTest).
    assertEquals(
      "I have an Dummy0 here.\n",
      plainTextWithDictionary("I have an iPhone here.\n", setOf("iPhone")),
    )
  }
}
