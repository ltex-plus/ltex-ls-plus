/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import kotlin.test.Test
import kotlin.test.assertEquals

class DictionaryWordTest {
  @Test
  fun testNormalizeLeavesPlainWordUntouched() {
    assertEquals("amazng", DictionaryWord.normalize("amazng"))
  }

  @Test
  fun testNormalizeStripsSurroundingPunctuation() {
    assertEquals("amazng", DictionaryWord.normalize("\"amazng\""))
    assertEquals("amazng", DictionaryWord.normalize("amazng."))
    assertEquals("amazng", DictionaryWord.normalize(",amazng,"))
    assertEquals("amazng", DictionaryWord.normalize("…amazng…"))
    assertEquals("amazng", DictionaryWord.normalize("«amazng»"))
    assertEquals("amazng", DictionaryWord.normalize("(amazng)"))
  }

  @Test
  fun testNormalizePreservesInternalApostrophe() {
    assertEquals("don't", DictionaryWord.normalize("don't"))
    assertEquals("l'ami", DictionaryWord.normalize("l'ami"))
    assertEquals("c'est", DictionaryWord.normalize("c'est"))
  }

  @Test
  fun testNormalizePreservesInternalHyphen() {
    assertEquals("state-of-the-art", DictionaryWord.normalize("state-of-the-art"))
    assertEquals("COVID-19", DictionaryWord.normalize("COVID-19"))
  }

  @Test
  fun testNormalizeStripsLeadingAndTrailingApostropheHyphen() {
    // Intentional tradeoff: closing-quote case is common, truncated-colloquial
    // ("lookin'") is the rare casualty — we accept the latter loss.
    assertEquals("lookin", DictionaryWord.normalize("lookin'"))
    assertEquals("hello", DictionaryWord.normalize("'hello"))
    assertEquals("word", DictionaryWord.normalize("-word-"))
  }

  @Test
  fun testNormalizeHandlesNonLatinScripts() {
    assertEquals("Привет", DictionaryWord.normalize("«Привет»"))
    assertEquals("世界", DictionaryWord.normalize("「世界」"))
    assertEquals("café", DictionaryWord.normalize("café."))
  }

  @Test
  fun testNormalizeKeepsDigits() {
    assertEquals("42", DictionaryWord.normalize("42"))
    assertEquals("iPhone12", DictionaryWord.normalize("iPhone12"))
  }

  @Test
  fun testNormalizePreservesInternalWhitespaceForPhraseEntries() {
    // Regex anchors at ^ and $, so internal whitespace is left alone. This
    // keeps a hand-typed multi-word entry like "LTEX LS" matchable against
    // the corresponding span (relevant for the local Java backend's all-caps
    // Morfologik collapse on `LTEX LS` and for any hand-edited dictionary).
    assertEquals("LTEX LS", DictionaryWord.normalize("LTEX LS"))
    assertEquals("New York", DictionaryWord.normalize("«New York»"))
  }

  @Test
  fun testNormalizeReturnsEmptyForPurelyPunctuation() {
    assertEquals("", DictionaryWord.normalize("..."))
    assertEquals("", DictionaryWord.normalize("—"))
    assertEquals("", DictionaryWord.normalize("\"'\""))
    assertEquals("", DictionaryWord.normalize(""))
  }
}
