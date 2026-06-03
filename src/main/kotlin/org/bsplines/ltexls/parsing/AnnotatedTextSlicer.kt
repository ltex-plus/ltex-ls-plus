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
// Source offsets are preserved so matches reproject correctly: TEXT and MARKUP
// parts each contribute their length to the source position; FAKE_CONTENT (a
// synthetic stand-in present only in the plain text) contributes zero.
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
    val parts: List<TextPart> = annotatedText.parts
    var partIndex = 0

    while (partIndex < parts.size) {
      val part: TextPart = parts[partIndex]

      // Boundary cuts at the current plain-text position close the slice before
      // this part is emitted, so any markup here begins the next paragraph's
      // slice rather than trailing the previous one.
      while ((cutIndex < cutPoints.size) && (cutPoints[cutIndex] == state.plainPos)) {
        state.closeSlice(state.sourcePos)
        cutIndex++
      }

      // A MARKUP immediately followed by its FAKE_CONTENT interpretation is the
      // result of a single addMarkup(markup, interpretAs) call and must be re-emitted
      // as one (exactly as mergeAnnotatedTexts/appendParts does). Emitting them as
      // separate addMarkup(markup) + addMarkup("", interpretAs) calls would insert a
      // spurious empty MARKUP part between them, desynchronizing downstream
      // markup<->fake pairing (e.g. AnnotatedTextFragment.invertAnnotatedText, which
      // looks only one part ahead) and corrupting plain<->source position mapping.
      if (
        (part.type == TextPart.Type.MARKUP) &&
        (partIndex + 1 < parts.size) &&
        (parts[partIndex + 1].type == TextPart.Type.FAKE_CONTENT)
      ) {
        cutIndex =
          state.emitMarkupWithFakeContent(part.part, parts[partIndex + 1].part, cutPoints, cutIndex)
        partIndex += 2
      } else {
        cutIndex = state.emitPart(part, cutPoints, cutIndex)
        partIndex++
      }
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
  // returned matches are then split back per paragraph by source offset. A MARKUP
  // part optionally followed by its FAKE_CONTENT interpretation is re-emitted as
  // the original addMarkup(markup, interpretedAs) call; FAKE_CONTENT never appears
  // without a preceding MARKUP (the slicer emits it via addMarkup("", piece)).
  fun mergeAnnotatedTexts(annotatedTexts: List<AnnotatedText>): AnnotatedText {
    val builder = AnnotatedTextBuilder()
    for (annotatedText: AnnotatedText in annotatedTexts) appendParts(builder, annotatedText.parts)
    return builder.build()
  }

  // Re-emits one AnnotatedText's parts into the builder. A MARKUP part optionally
  // followed by its FAKE_CONTENT interpretation is re-emitted as the original
  // addMarkup(markup, interpretedAs) call (and the FAKE_CONTENT part skipped); a
  // bare FAKE_CONTENT is emitted via addMarkup("", piece), as the slicer does.
  private fun appendParts(
    builder: AnnotatedTextBuilder,
    parts: List<TextPart>,
  ) {
    var i = 0
    while (i < parts.size) {
      val part: TextPart = parts[i]
      val nextIsFakeContent: Boolean =
        (i < parts.size - 1) && (parts[i + 1].type == TextPart.Type.FAKE_CONTENT)

      when (part.type) {
        TextPart.Type.TEXT -> {
          builder.addText(part.part)
        }

        TextPart.Type.FAKE_CONTENT -> {
          builder.addMarkup("", part.part)
        }

        TextPart.Type.MARKUP -> {
          if (nextIsFakeContent) {
            builder.addMarkup(part.part, parts[i + 1].part)
            i++
          } else {
            builder.addMarkup(part.part)
          }
        }

        null -> {}
      }
      i++
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

  // Per-slice accumulation state. plainPos/sourcePos track the running position
  // at the start of the next part; sourceFromPos is the current slice's source
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

    // Emits one part, splitting it at any cut points strictly inside its plain
    // text. Returns the advanced cut index. MARKUP carries no plain text and so
    // is never split; TEXT advances source 1:1 with plain text, FAKE_CONTENT
    // advances source by zero.
    fun emitPart(
      part: TextPart,
      cutPoints: List<Int>,
      startCutIndex: Int,
    ): Int {
      val string: String = part.part
      val isText: Boolean = (part.type == TextPart.Type.TEXT)
      val isFakeContent: Boolean = (part.type == TextPart.Type.FAKE_CONTENT)

      if (!isText && !isFakeContent) {
        // MARKUP (or null): source only, no plain text, never a split site.
        if (part.type == TextPart.Type.MARKUP) builder.addMarkup(string)
        sourcePos += string.length
        return startCutIndex
      }

      var cutIndex: Int = startCutIndex
      var localStart = 0
      val plainLength: Int = string.length

      while (
        (cutIndex < cutPoints.size) &&
        (cutPoints[cutIndex] > plainPos) &&
        (cutPoints[cutIndex] < plainPos + plainLength)
      ) {
        val localCut: Int = cutPoints[cutIndex] - plainPos
        addPiece(isText, string.substring(localStart, localCut))
        // TEXT: source advances with plain text; FAKE_CONTENT: source unchanged.
        closeSlice(if (isText) sourcePos + localCut else sourcePos)
        localStart = localCut
        cutIndex++
      }

      addPiece(isText, string.substring(localStart))
      plainPos += plainLength
      if (isText) sourcePos += plainLength
      return cutIndex
    }

    // Emits a MARKUP part together with its FAKE_CONTENT interpretation as a single
    // addMarkup(markup, interpretAs) call, so the slice has no spurious empty MARKUP
    // part separating them. The markup carries source (its length) but no plain text;
    // the interpretAs carries plain text but no source. A cut can only fall inside the
    // interpretAs (the markup itself is never a split site); in that case the real
    // markup stays with the first piece and any later pieces are bare fake
    // continuations, which is the one situation where an empty markup is correct.
    fun emitMarkupWithFakeContent(
      markup: String,
      interpretAs: String,
      cutPoints: List<Int>,
      startCutIndex: Int,
    ): Int {
      var cutIndex: Int = startCutIndex
      var localStart = 0
      val plainLength: Int = interpretAs.length
      var markupPending = true

      while (
        (cutIndex < cutPoints.size) &&
        (cutPoints[cutIndex] > plainPos) &&
        (cutPoints[cutIndex] < plainPos + plainLength)
      ) {
        val localCut: Int = cutPoints[cutIndex] - plainPos
        builder.addMarkup(
          if (markupPending) markup else "",
          interpretAs.substring(localStart, localCut),
        )
        // The markup's source belongs to the first piece's slice; account for it
        // before closing so the next slice starts after the markup.
        if (markupPending) {
          sourcePos += markup.length
          markupPending = false
        }
        closeSlice(sourcePos)
        localStart = localCut
        cutIndex++
      }

      builder.addMarkup(if (markupPending) markup else "", interpretAs.substring(localStart))
      if (markupPending) sourcePos += markup.length
      plainPos += plainLength
      return cutIndex
    }

    private fun addPiece(
      isText: Boolean,
      piece: String,
    ) {
      if (isText) builder.addText(piece) else builder.addMarkup("", piece)
    }
  }
}
