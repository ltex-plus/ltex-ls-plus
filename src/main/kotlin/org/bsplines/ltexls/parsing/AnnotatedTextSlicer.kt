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

    for (part: TextPart in annotatedText.parts) {
      // Boundary cuts at the current plain-text position close the slice before
      // this part is emitted, so any markup here begins the next paragraph's
      // slice rather than trailing the previous one.
      while ((cutIndex < cutPoints.size) && (cutPoints[cutIndex] == state.plainPos)) {
        state.closeSlice(state.sourcePos)
        cutIndex++
      }
      cutIndex = state.emitPart(part, cutPoints, cutIndex)
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

    private fun addPiece(
      isText: Boolean,
      piece: String,
    ) {
      if (isText) builder.addText(piece) else builder.addMarkup("", piece)
    }
  }
}
