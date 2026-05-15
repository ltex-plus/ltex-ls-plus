/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

object DictionaryWord {
  // Precompiled once at class-load time (Kotlin `Regex` constructor calls
  // java.util.regex.Pattern.compile under the hood; the val initializers
  // run exactly once for this singleton object). Anchored at `^` / `$` so
  // a no-op match on a bare word is O(1).
  private val LEADING_NON_WORD_CHARS: Regex = Regex("""^[^\p{L}\p{N}]+""")
  private val TRAILING_NON_WORD_CHARS: Regex = Regex("""[^\p{L}\p{N}]+$""")

  // Strip leading/trailing Unicode non-letter-non-digit characters. Internal
  // apostrophes and hyphens survive (\p{L}\p{N} excludes them but they are
  // not at the ends in cases like `don't`, `state-of-the-art`, `COVID-19`).
  //
  // An entirely-punctuation input returns "". Both call sites treat that
  // as "no word": the add path's `if (word.isEmpty()) continue` skips
  // persistence (the matched text is effectively not recognized as a
  // user-provided dictionary word), and the check path's
  // `lookupKey.isNotEmpty()` guard blocks suppression. Exotic in practice
  // — no real spell-check rule emits an all-punctuation span — but the
  // guard makes the boundary explicit and prevents an empty stored entry
  // from accidentally matching every empty lookup key.
  fun normalize(word: String): String =
    word.replace(LEADING_NON_WORD_CHARS, "").replace(TRAILING_NON_WORD_CHARS, "")
}
