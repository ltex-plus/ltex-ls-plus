/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.languagetool.markup.AnnotatedText
import org.languagetool.markup.AnnotatedTextBuilder
import org.languagetool.markup.TextPart

// Splits an already-built AnnotatedText into one piece per prose paragraph, for
// incremental checking. Markup understanding is NOT re-implemented here: the
// builder already stripped it, so a paragraph break is simply a blank line
// ("\n\n") in the plain text. A break can only occur in genuine prose — markup,
// verbatim, math, and ignored environments never reach the plain text — so this
// is correctness-neutral: the slices concatenate back to the original, and each
// is checked exactly as the whole would have been (minus cross-paragraph rules,
// which LanguageTool does not have).
//
// Slicing is unconditional (every paragraph becomes its own slice) so that the
// per-paragraph cache key is as fine-grained as possible. Batching slices back
// into larger LanguageTool requests is a separate concern handled by the caller
// (DocumentChecker), which merges contiguous cache misses via mergeAnnotatedTexts
// and splits the returned matches back per paragraph. Decoupling the cache unit
// (paragraph) from the request unit (run of misses) is what lets an edit
// re-check one paragraph while a fresh open still sends a single request.
//
// Both slicing and merging re-emit an AnnotatedText's parts as addText/addMarkup
// calls. The rule for turning raw parts back into those calls (which MARKUP owns
// which FAKE_CONTENT) lives in exactly one place — parseSegments — so the two
// paths cannot disagree. A Segment is one logical builder call:
//   - TextSegment(text)               -> addText(text)
//   - MarkupSegment(markup, interpretAs) -> addMarkup(markup, interpretAs)
// where a MarkupSegment with empty interpretAs is a plain addMarkup(markup) and
// one with empty markup is a bare fake (addMarkup("", interpretAs)). Source
// offsets are preserved because a Segment's sourceLength counts only its source
// characters (TextSegment's text, MarkupSegment's markup); FAKE_CONTENT, a
// synthetic stand-in present only in the plain text, contributes zero source.
object AnnotatedTextSlicer {
  data class Slice(
    val annotatedText: AnnotatedText,
    val sourceFromPos: Int,
  )

  fun slice(annotatedText: AnnotatedText): List<Slice> {
    val cutPoints: List<Int> = findCutPoints(annotatedText.plainText)
    if (cutPoints.isEmpty()) return listOf(Slice(annotatedText, 0))

    val state = SliceState()
    var cutIndex = 0

    for (segment: Segment in parseSegments(annotatedText.parts)) {
      // Boundary cuts at the current plain-text position close the slice before
      // this segment is emitted, so any markup here begins the next paragraph's
      // slice rather than trailing the previous one.
      while ((cutIndex < cutPoints.size) && (cutPoints[cutIndex] == state.plainPos)) {
        state.closeSlice(state.sourcePos)
        cutIndex++
      }
      cutIndex = state.emitSegment(segment, cutPoints, cutIndex)
    }

    state.finish()
    return state.slices
  }

  // Cut points are the end positions of maximal runs of >= 2 newlines, but only
  // where non-whitespace content precedes the run within the current segment (so
  // leading/standalone blank runs fold into the following slice) and never at
  // end-of-text (so a trailing blank run stays with the final paragraph).
  // Paragraph breaks reach the plain text normalized to runs of '\n'.
  private fun findCutPoints(plainText: String): List<Int> {
    val cutPoints = ArrayList<Int>()
    val length: Int = plainText.length
    var lastCut = 0
    var i = 0

    while (i < length) {
      if (plainText[i] != '\n') {
        i++
        continue
      }

      var runEnd: Int = i
      while ((runEnd < length) && (plainText[runEnd] == '\n')) runEnd++

      if (
        ((runEnd - i) >= 2) &&
        (runEnd < length) &&
        hasNonWhitespace(plainText, lastCut, i)
      ) {
        cutPoints.add(runEnd)
        lastCut = runEnd
      }

      i = runEnd
    }

    return cutPoints
  }

  // Concatenates the AnnotatedTexts of contiguous slices back into one, faithfully
  // preserving part types so the result is exactly the spanning sub-AnnotatedText
  // the builder would have produced for that source range. Used by DocumentChecker
  // to send a run of cache-miss paragraphs as a single LanguageTool request; the
  // returned matches are then split back per paragraph by source offset.
  fun mergeAnnotatedTexts(annotatedTexts: List<AnnotatedText>): AnnotatedText {
    val builder = AnnotatedTextBuilder()
    for (annotatedText: AnnotatedText in annotatedTexts) {
      for (segment: Segment in parseSegments(annotatedText.parts)) emit(builder, segment)
    }
    return builder.build()
  }

  // The single place that decides how raw parts map back to builder calls: a
  // MARKUP optionally followed by its FAKE_CONTENT is one MarkupSegment; a
  // FAKE_CONTENT with no preceding MARKUP is a bare fake (empty markup). This is
  // the only knowledge both slice() and mergeAnnotatedTexts() share, so the
  // pairing rule can never drift between them.
  private fun parseSegments(parts: List<TextPart>): List<Segment> {
    val segments = ArrayList<Segment>()
    var i = 0

    while (i < parts.size) {
      val part: TextPart = parts[i]
      when (part.type) {
        TextPart.Type.TEXT -> {
          segments.add(TextSegment(part.part))
        }

        TextPart.Type.MARKUP -> {
          if ((i + 1 < parts.size) && (parts[i + 1].type == TextPart.Type.FAKE_CONTENT)) {
            segments.add(MarkupSegment(part.part, parts[i + 1].part))
            i++
          } else {
            segments.add(MarkupSegment(part.part, ""))
          }
        }

        TextPart.Type.FAKE_CONTENT -> {
          segments.add(MarkupSegment("", part.part))
        }

        null -> {}
      }
      i++
    }

    return segments
  }

  // The single place that turns a Segment back into a builder call. An empty
  // interpretAs collapses to a plain addMarkup(markup) (the FAKE_CONTENT part is
  // only emitted for a non-empty interpretAs), matching what the original build
  // produced.
  private fun emit(
    builder: AnnotatedTextBuilder,
    segment: Segment,
  ) {
    when (segment) {
      is TextSegment -> {
        builder.addText(segment.text)
      }

      is MarkupSegment -> {
        if (segment.interpretAs.isNotEmpty()) {
          builder.addMarkup(segment.markup, segment.interpretAs)
        } else {
          builder.addMarkup(segment.markup)
        }
      }
    }
  }

  private fun hasNonWhitespace(
    text: String,
    fromIndex: Int,
    toIndex: Int,
  ): Boolean {
    for (i in fromIndex until toIndex) {
      if (!text[i].isWhitespace()) return true
    }
    return false
  }

  // One logical builder call, normalized so slicing and merging share the same
  // emit and split logic regardless of the underlying part shape. sourceLength is
  // the number of source characters consumed; plainText is the contribution to
  // the checker-visible plain text.
  private sealed interface Segment {
    val sourceLength: Int
    val plainText: String

    // Splits the plain text at [index], returning (head, tail). Used only by the
    // slicer when a cut falls strictly inside this segment.
    fun splitAtPlain(index: Int): Pair<Segment, Segment>
  }

  private class TextSegment(
    val text: String,
  ) : Segment {
    override val sourceLength: Int get() = text.length
    override val plainText: String get() = text

    override fun splitAtPlain(index: Int): Pair<Segment, Segment> =
      Pair(TextSegment(text.substring(0, index)), TextSegment(text.substring(index)))
  }

  private class MarkupSegment(
    val markup: String,
    val interpretAs: String,
  ) : Segment {
    override val sourceLength: Int get() = markup.length
    override val plainText: String get() = interpretAs

    // The source markup belongs entirely to the head; the tail is a bare fake
    // continuation (empty markup, zero source). See emitSegment for why this is a
    // defensive path that real input does not exercise.
    override fun splitAtPlain(index: Int): Pair<Segment, Segment> =
      Pair(
        MarkupSegment(markup, interpretAs.substring(0, index)),
        MarkupSegment("", interpretAs.substring(index)),
      )
  }

  // Per-slice accumulation state. plainPos/sourcePos track the running position
  // at the start of the next segment; sourceFromPos is the current slice's source
  // start.
  private class SliceState {
    val slices = ArrayList<Slice>()
    var plainPos = 0
    var sourcePos = 0
    private var builder = AnnotatedTextBuilder()
    private var sourceFromPos = 0

    fun closeSlice(nextSourceFromPos: Int) {
      slices.add(Slice(builder.build(), sourceFromPos))
      builder = AnnotatedTextBuilder()
      sourceFromPos = nextSourceFromPos
    }

    fun finish() {
      slices.add(Slice(builder.build(), sourceFromPos))
    }

    // Emits one segment, splitting it at any cut points strictly inside its plain
    // text and closing a slice at each. Returns the advanced cut index.
    //
    // In practice only TextSegments are ever split: a cut is the end of a "\n\n"
    // run with content before it, and the builders only ever emit such a run as
    // genuine prose (a TextSegment) or as a whole fake equal to "\n\n" (whose cut
    // lands on the segment boundary, handled by the caller's boundary loop, not
    // here). A fake containing a paragraph break *followed by more content* — the
    // only way a cut could land strictly inside a MarkupSegment — is never
    // produced. splitAtPlain still handles that case correctly (markup stays with
    // the head, tail becomes a bare fake) so correctness does not depend on the
    // invariant; it just is not exercised by real input.
    fun emitSegment(
      segment: Segment,
      cutPoints: List<Int>,
      startCutIndex: Int,
    ): Int {
      var cutIndex: Int = startCutIndex
      var remaining: Segment = segment

      while (
        (cutIndex < cutPoints.size) &&
        (cutPoints[cutIndex] > plainPos) &&
        (cutPoints[cutIndex] < plainPos + remaining.plainText.length)
      ) {
        val localCut: Int = cutPoints[cutIndex] - plainPos
        val (head: Segment, tail: Segment) = remaining.splitAtPlain(localCut)
        emit(builder, head)
        plainPos += head.plainText.length
        sourcePos += head.sourceLength
        closeSlice(sourcePos)
        remaining = tail
        cutIndex++
      }

      emit(builder, remaining)
      plainPos += remaining.plainText.length
      sourcePos += remaining.sourceLength
      return cutIndex
    }
  }
}
