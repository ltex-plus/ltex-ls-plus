/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

// Splits a run of plain text into verbatim and "masked" segments based on the
// user dictionary, so that masked segments can be replaced by a dummy token
// before the text reaches LanguageTool. This is what gives LTeX+ multi-word
// dictionary support: LanguageTool checks the text per token and never sees a
// phrase like `GreenTeam Penciltest` as a unit, so a phrase entry can only be
// honoured by removing its occurrences from the text up front.
//
// A dictionary entry matches only as a whole token sequence: the characters
// immediately before and after the matched span must not be letters or digits
// (Unicode-aware, via Char.isLetterOrDigit() — not a regex `\b`, whose `\w` is
// ASCII-only and mishandles accented letters). So `GreenTeam` is not masked
// inside `GreenTeamer`, and surrounding punctuation (`(GreenTeam Penciltest).`)
// is left untouched. Matching is case-sensitive, preserving the exact semantics
// of the former `dictionary.contains(span)` suppression. On overlap the longest
// entry wins, so a lone `GreenTeam` is masked only when `GreenTeam` is itself an
// entry — not as a by-product of the phrase entry `GreenTeam Penciltest`.
class DictionaryMasker(
  dictionary: Set<String>,
) {
  data class Segment(
    val text: String,
    val masked: Boolean,
  )

  // Longest first so the longest matching entry wins on overlap; blank entries
  // dropped (a whitespace-only entry would otherwise try to mask runs of space).
  private val entries: List<String> =
    dictionary.filter { it.isNotBlank() }.sortedByDescending { it.length }

  val isEmpty: Boolean = entries.isEmpty()

  fun split(text: String): List<Segment> {
    if (entries.isEmpty() || text.isEmpty()) return listOf(Segment(text, false))

    val segments = ArrayList<Segment>()
    var emittedUpTo = 0
    var pos = 0

    while (pos < text.length) {
      val matchEnd: Int = matchAt(text, pos)

      if (matchEnd < 0) {
        pos++
      } else {
        if (pos > emittedUpTo) segments.add(Segment(text.substring(emittedUpTo, pos), false))
        segments.add(Segment(text.substring(pos, matchEnd), true))
        emittedUpTo = matchEnd
        pos = matchEnd
      }
    }

    if (emittedUpTo < text.length) segments.add(Segment(text.substring(emittedUpTo), false))
    return segments
  }

  // Exclusive end offset of the longest entry matching [text] at [start] on
  // whole-token boundaries, or -1 if none matches there. `entries` is sorted
  // longest-first, so the first match found is the longest one.
  private fun matchAt(
    text: String,
    start: Int,
  ): Int {
    if ((start > 0) && text[start - 1].isLetterOrDigit()) return -1
    val entry: String? = entries.firstOrNull { entryMatchesAt(text, start, it) }
    return if (entry != null) (start + entry.length) else -1
  }

  // Does [entry] occur literally at [start] in [text] with a trailing token
  // boundary? (The leading boundary is already guaranteed by the caller.)
  private fun entryMatchesAt(
    text: String,
    start: Int,
    entry: String,
  ): Boolean {
    val end: Int = start + entry.length
    if (end > text.length) return false
    val matchesLiterally: Boolean =
      text.regionMatches(start, entry, 0, entry.length, ignoreCase = false)
    val trailingBoundaryOk: Boolean = (end >= text.length) || !text[end].isLetterOrDigit()
    return matchesLiterally && trailingBoundaryOk
  }
}
