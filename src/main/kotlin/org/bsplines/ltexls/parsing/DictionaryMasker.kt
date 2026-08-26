/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.languagetool.markup.TextPart

// Masks user-dictionary occurrences in a builder's part list so that masked
// spans can be replaced by a dummy token before the text reaches LanguageTool.
// This is what gives LTeX+ multi-word dictionary support: LanguageTool checks
// the text per token and never sees a phrase like `GreenTeam Penciltest` as a
// unit, so a phrase entry can only be honoured by removing its occurrences from
// the text up front.
//
// Matching runs over the *assembled plain text* — TEXT parts joined with the
// interpretAs of MARKUP parts — i.e. exactly the string LanguageTool checks.
// An entry therefore also matches when its occurrence is split across parts in
// the source: a phrase wrapped over a Markdown soft line break (the newline is
// markup interpreted as a space), a word containing a LaTeX accent command
// (`M\"uller`), or a phrase interrupted by inline tags (`LT<sub>E</sub>X LS`).
// The covered parts are coalesced into a single markup part whose source is the
// concatenation of the covered source pieces, so the plain-text-to-source
// mapping outside masked spans stays exact.
//
// A dictionary entry matches only as a whole token sequence: the characters
// immediately before and after the matched span must not be letters or digits
// (Unicode-aware, via Char.isLetterOrDigit() — not a regex `\b`, whose `\w` is
// ASCII-only and mishandles accented letters). So `GreenTeam` is not masked
// inside `GreenTeamer`, and surrounding punctuation (`(GreenTeam Penciltest).`)
// is left untouched. On overlap the longest entry wins, so a lone `GreenTeam`
// is masked only when `GreenTeam` is itself an entry — not as a by-product of
// the phrase entry `GreenTeam Penciltest`.
//
// Case handling follows the hunspell / LanguageTool-speller convention for
// accepted words: an entry matches its own case exactly, plus — when the entry
// begins with a lowercase letter — its sentence-initial titlecase variant
// (`foobar` also accepts `Foobar.` at a sentence start), plus its all-uppercase
// variant (`GreenTeam` also accepts `GREENTEAM` in headings). General
// case-insensitivity is deliberately not offered: adding `IT` must not accept
// `it`.
//
// Unicode space separators (e.g. the no-break space a LaTeX tie `~` puts into
// the plain text) are normalized to a plain space on both sides of the
// comparison — in the entries and in the checked text — so the entry
// `GreenTeam Penciltest` also masks `GreenTeam~Penciltest`. The replacement is
// one-for-one, so all offsets stay valid. Newlines and tabs are deliberately
// left alone: they are structural (a heading ending, a paragraph break), and a
// single-space entry must not match across them.
class DictionaryMasker(
  dictionary: Set<String>,
) {
  // One builder part awaiting emission into LanguageTool's AnnotatedTextBuilder.
  // TEXT: code is the plain text itself (interpretAs unused). MARKUP: code is
  // the source markup, interpretAs its (possibly empty) plain-text stand-in.
  data class Part(
    val type: TextPart.Type,
    val code: String,
    val interpretAs: String = "",
  ) {
    val plainText: String
      get() = if (type == TextPart.Type.TEXT) code else interpretAs
  }

  // Each entry, space-normalized, plus its generated case variants
  // (sentence-initial titlecase for lowercase-initial entries, all-uppercase),
  // matched literally. Longest first so the longest matching entry wins on
  // overlap; blank entries dropped (a space-only entry would otherwise try to
  // mask runs of space).
  private val entries: List<String> =
    buildSet {
      for (rawEntry: String in dictionary) {
        val entry: String = normalizeSpaceSeparators(rawEntry)
        if (entry.isBlank()) continue
        add(entry)
        if (entry.first().isLowerCase()) add(entry.replaceFirstChar { it.titlecaseChar() })
        add(entry.uppercase())
      }
    }.sortedByDescending { it.length }

  val isEmpty: Boolean = entries.isEmpty()

  // Non-overlapping match ranges in [text], ascending, each built with `until`
  // (half-open, so text.substring(range) yields the matched span). Matching
  // runs over the space-normalized text; normalization is one-for-one, so the
  // ranges are valid for the original [text] as well.
  fun findMatches(text: String): List<IntRange> {
    if (entries.isEmpty() || text.isEmpty()) return emptyList()

    val normalizedText: String = normalizeSpaceSeparators(text)
    val matches = ArrayList<IntRange>()
    var pos = 0

    while (pos < normalizedText.length) {
      val matchEnd: Int = matchAt(normalizedText, pos)

      if (matchEnd < 0) {
        pos++
      } else {
        matches.add(pos until matchEnd)
        pos = matchEnd
      }
    }

    return matches
  }

  // True iff [text] is in its entirety a single dictionary entry, i.e. one
  // match covering the whole string. Space normalization and the accepted case
  // variants apply exactly as in findMatches, so `GREENTEAM` and
  // `GreenTeam Penciltest` both qualify.
  //
  // The check path uses this against a masker built over the *collapsed* entry
  // forms, to recognize a LanguageTool match whose span is exactly an accepted
  // word — see LanguageToolInterface.isCoveredByDictionary.
  fun isEntry(text: String): Boolean {
    val matches: List<IntRange> = findMatches(text)
    return (matches.size == 1) && (matches[0].first == 0) && (matches[0].last == text.length - 1)
  }

  // Returns [parts] with every dictionary occurrence in the assembled plain
  // text coalesced into one markup part, interpreted as
  // replacementProvider(<matched plain text>). Matches whose start or end falls
  // strictly inside a markup's interpretAs are skipped (the markup source cannot
  // be split at a plain-text position); such a boundary requires an interpretAs
  // of length >= 2, which is rare.
  //
  // The provider is handed the matched plain text rather than nothing, so the
  // replacement can be *derived* from what the user wrote instead of invented.
  // That is what keeps LanguageTool's grammatical judgements honest: an invented
  // token carries its own number, gender, initial sound and capitalization, and
  // LanguageTool then reports the disagreement wherever the sentence puts it —
  // possibly far from the substitution, where no filter can attribute it. See
  // CodeAnnotatedTextBuilder.build for the replacement actually used.
  fun maskParts(
    parts: List<Part>,
    replacementProvider: (String) -> String,
  ): List<Part> {
    if (this.isEmpty || parts.isEmpty()) return parts

    val plainStarts: IntArray = computePlainStarts(parts)
    val plainText: String = parts.joinToString("") { it.plainText }
    val matches: List<IntRange> =
      findMatches(plainText).filter { isMaskable(it, parts, plainStarts) }
    if (matches.isEmpty()) return parts

    return PartRebuilder(parts, plainStarts, matches, replacementProvider).rebuild()
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

  // Rebuilds a part list with the given plain-text match ranges replaced by
  // dummy markup parts. TEXT parts straddling a match boundary are split; parts
  // covered by a match contribute their source (text or markup) to the masked
  // part's source, so the total source is preserved and offsets outside masked
  // spans stay exact.
  private class PartRebuilder(
    private val parts: List<Part>,
    private val plainStarts: IntArray,
    matches: List<IntRange>,
    private val replacementProvider: (String) -> String,
  ) {
    // (start, exclusive end) plain-text offsets of the pending matches.
    private val matchQueue = ArrayDeque(matches.map { Pair(it.first, it.last + 1) })
    private val result = ArrayList<Part>()
    private val maskedSource = StringBuilder()

    // The plain text the match covered, accumulated alongside the source so the
    // replacement can be derived from it. For a TEXT part the two are the same
    // string; for a MARKUP part the source is its code and the plain
    // contribution its interpretAs.
    private val maskedPlain = StringBuilder()

    fun rebuild(): List<Part> {
      for ((partIndex: Int, part: Part) in parts.withIndex()) {
        if (part.type == TextPart.Type.TEXT) {
          consumeTextPart(part, plainStarts[partIndex], plainStarts[partIndex + 1])
        } else {
          consumeMarkupPart(part, plainStarts[partIndex], plainStarts[partIndex + 1])
        }
      }

      return result
    }

    private fun consumeTextPart(
      part: Part,
      partStart: Int,
      partEnd: Int,
    ) {
      var pos: Int = partStart

      while (pos < partEnd) {
        val match: Pair<Int, Int>? = matchQueue.firstOrNull()

        if ((match == null) || (match.first >= partEnd)) {
          result.add(Part(TextPart.Type.TEXT, part.code.substring(pos - partStart)))
          pos = partEnd
        } else if (pos < match.first) {
          result.add(
            Part(TextPart.Type.TEXT, part.code.substring(pos - partStart, match.first - partStart)),
          )
          pos = match.first
        } else {
          val pieceEnd: Int = minOf(match.second, partEnd)
          maskedSource.append(part.code, pos - partStart, pieceEnd - partStart)
          maskedPlain.append(part.code, pos - partStart, pieceEnd - partStart)
          pos = pieceEnd
          if (pos == match.second) closeMask()
        }
      }
    }

    private fun consumeMarkupPart(
      part: Part,
      partStart: Int,
      partEnd: Int,
    ) {
      val match: Pair<Int, Int>? = matchQueue.firstOrNull()
      // A zero-plain-length markup (empty interpretAs) exactly at a match
      // boundary stays outside the mask (minimal span); with a nonzero plain
      // contribution the markup is inside whenever the match covers it (a match
      // boundary strictly inside its interpretAs was filtered out upstream).
      val insideMask: Boolean =
        (match != null) &&
          if (partStart == partEnd) {
            (match.first < partStart) && (partStart < match.second)
          } else {
            (match.first <= partStart) && (partEnd <= match.second)
          }

      if (insideMask) {
        maskedSource.append(part.code)
        maskedPlain.append(part.interpretAs)
        if (partEnd == match.second) closeMask()
      } else {
        result.add(part)
      }
    }

    private fun closeMask() {
      result.add(
        Part(
          TextPart.Type.MARKUP,
          maskedSource.toString(),
          replacementProvider(maskedPlain.toString()),
        ),
      )
      maskedSource.clear()
      maskedPlain.clear()
      matchQueue.removeFirst()
    }
  }

  companion object {
    // Join a multi-word entry or occurrence into a single token by dropping its
    // space separators: `GreenTeam Penciltest` -> `GreenTeamPenciltest`, and
    // likewise for a no-break space from a LaTeX tie. Both sides of the
    // mechanism call this — the builder to produce the replacement, the check
    // path to build the forms it compares against — so they cannot disagree.
    fun collapseSeparators(text: String): String =
      text.filterNot { (it == ' ') || (it.category == CharCategory.SPACE_SEPARATOR) }

    // One-for-one replacement of Unicode space separators (no-break space,
    // thin space, ...) by a plain space, preserving length and all offsets.
    // Returns [text] itself when nothing needs replacing (the common case).
    // Newlines and tabs are not space separators and pass through untouched.
    private fun normalizeSpaceSeparators(text: String): String {
      if (text.none(::isNonStandardSpaceSeparator)) return text

      return String(
        CharArray(text.length) { charIndex ->
          val curChar: Char = text[charIndex]
          if (isNonStandardSpaceSeparator(curChar)) ' ' else curChar
        },
      )
    }

    private fun isNonStandardSpaceSeparator(curChar: Char): Boolean =
      (curChar != ' ') && (curChar.category == CharCategory.SPACE_SEPARATOR)

    // plainStarts[i] is the plain-text offset where part i begins; the extra
    // trailing element is the total plain-text length.
    private fun computePlainStarts(parts: List<Part>): IntArray {
      val plainStarts = IntArray(parts.size + 1)

      for ((partIndex: Int, part: Part) in parts.withIndex()) {
        plainStarts[partIndex + 1] = plainStarts[partIndex] + part.plainText.length
      }

      return plainStarts
    }

    // A match is maskable iff neither boundary falls strictly inside a MARKUP
    // part's plain-text contribution (its interpretAs cannot be split at a
    // plain-text position — which half of the source would each piece map to?).
    private fun isMaskable(
      match: IntRange,
      parts: List<Part>,
      plainStarts: IntArray,
    ): Boolean =
      isSplittableBoundary(match.first, parts, plainStarts) &&
        isSplittableBoundary(match.last + 1, parts, plainStarts)

    private fun isSplittableBoundary(
      pos: Int,
      parts: List<Part>,
      plainStarts: IntArray,
    ): Boolean {
      for (partIndex: Int in parts.indices) {
        if (plainStarts[partIndex] >= pos) break

        if (pos < plainStarts[partIndex + 1]) {
          return parts[partIndex].type == TextPart.Type.TEXT
        }
      }

      return true
    }
  }
}
