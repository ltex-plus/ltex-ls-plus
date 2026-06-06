/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DictionaryMaskerTest {
  private fun split(
    dictionary: Set<String>,
    text: String,
  ): List<Pair<String, Boolean>> =
    DictionaryMasker(dictionary).split(text).map { Pair(it.text, it.masked) }

  @Test
  fun emptyDictionaryIsNoOp() {
    val masker = DictionaryMasker(emptySet())
    assertTrue(masker.isEmpty)
    assertEquals(listOf(Pair("hello world", false)), split(emptySet(), "hello world"))
  }

  @Test
  fun blankEntriesAreDropped() {
    assertTrue(DictionaryMasker(setOf("", "  ", "\t")).isEmpty)
  }

  @Test
  fun singleWordIsMaskedOnWholeTokenBoundaries() {
    assertEquals(
      listOf(Pair("I met ", false), Pair("GreenTeam", true), Pair(" today.", false)),
      split(setOf("GreenTeam"), "I met GreenTeam today."),
    )
  }

  @Test
  fun singleWordNotMaskedInsideLongerWord() {
    // "GreenTeam" must not match inside "GreenTeamer" / "aGreenTeam".
    assertEquals(
      listOf(Pair("a GreenTeamer b", false)),
      split(setOf("GreenTeam"), "a GreenTeamer b"),
    )
    assertEquals(listOf(Pair("a aGreenTeam b", false)), split(setOf("GreenTeam"), "a aGreenTeam b"))
  }

  @Test
  fun multiWordPhraseIsMaskedWhenAdjacent() {
    assertEquals(
      listOf(Pair("the ", false), Pair("GreenTeam Penciltest", true), Pair(" firm", false)),
      split(setOf("GreenTeam Penciltest"), "the GreenTeam Penciltest firm"),
    )
  }

  @Test
  fun loneFirstWordNotMaskedWhenOnlyPhraseIsAnEntry() {
    // The whole point of multi-word support: a bare "GreenTeam" stays visible
    // unless "GreenTeam" is itself an entry.
    assertEquals(
      listOf(Pair("a GreenTeam here", false)),
      split(setOf("GreenTeam Penciltest"), "a GreenTeam here"),
    )
  }

  @Test
  fun longestEntryWinsOnOverlap() {
    // Both "GreenTeam" and the phrase are entries; the phrase wins where present.
    assertEquals(
      listOf(Pair("GreenTeam Penciltest", true)),
      split(setOf("GreenTeam", "GreenTeam Penciltest"), "GreenTeam Penciltest"),
    )
    // ...but a lone "GreenTeam" is still masked because it is also an entry.
    assertEquals(
      listOf(Pair("GreenTeam", true), Pair(" alone", false)),
      split(setOf("GreenTeam", "GreenTeam Penciltest"), "GreenTeam alone"),
    )
  }

  @Test
  fun matchingIsCaseSensitive() {
    assertEquals(
      listOf(Pair("greenteam penciltest", false)),
      split(setOf("GreenTeam Penciltest"), "greenteam penciltest"),
    )
  }

  @Test
  fun surroundingPunctuationIsPreserved() {
    assertEquals(
      listOf(Pair("(", false), Pair("GreenTeam Penciltest", true), Pair(").", false)),
      split(setOf("GreenTeam Penciltest"), "(GreenTeam Penciltest)."),
    )
  }

  @Test
  fun unicodeWordBoundariesAreRespected() {
    // Accented letters are word characters (Char.isLetterOrDigit, not ASCII \b),
    // so "café" is not masked inside "cafés", but a standalone accented entry is.
    assertEquals(listOf(Pair("les cafés ici", false)), split(setOf("café"), "les cafés ici"))
    assertEquals(
      listOf(Pair("un ", false), Pair("café", true), Pair(" noir", false)),
      split(setOf("café"), "un café noir"),
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
}
