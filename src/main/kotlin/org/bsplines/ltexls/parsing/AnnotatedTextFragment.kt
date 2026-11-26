/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.server.LtexTextDocumentItem
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.languagetool.markup.AnnotatedText
import org.languagetool.markup.TextPart

class AnnotatedTextFragment(
  val annotatedText: AnnotatedText,
  val codeFragment: CodeFragment,
  val document: LtexTextDocumentItem,
  // Accepted words the builder discovered while parsing this fragment (Typst
  // abbreviation labels), which are not in Settings and so cannot be looked up
  // by language. A set of entries is position-free, which is why it is carried
  // rather than a set of offsets: it survives the paragraph slicing and merging
  // that would invalidate stored positions.
  val additionalDictionary: Set<String> = emptySet(),
) {
  private var plainText: String? = null

  // Matched against the *collapsed* entry forms, exactly as the configured
  // dictionary is, so a multi-word label joined by the builder is recognized too.
  private val additionalEntryMatcher: DictionaryMasker by lazy {
    DictionaryMasker(
      this.additionalDictionary.mapTo(mutableSetOf()) { DictionaryMasker.collapseSeparators(it) },
    )
  }

  fun isAdditionalDictionaryEntry(word: String): Boolean =
    this.additionalDictionary.isNotEmpty() && this.additionalEntryMatcher.isEntry(word)

  // Returns the plain text between two source positions, such as the word a
  // match covers. The positions are relative to this fragment's annotated text.
  fun getSubstringOfPlainText(
    fromPos: Int,
    toPos: Int,
  ): String {
    val plainText: String =
      this.plainText ?: run {
        val plainText = this.annotatedText.plainText
        this.plainText = plainText
        plainText
      }

    val plainTextFromPos: Int = getPlainTextPosition(this.annotatedText, fromPos, false)
    val plainTextToPos: Int = getPlainTextPosition(this.annotatedText, toPos, true)

    return if (plainTextFromPos <= plainTextToPos) {
      plainText.substring(plainTextFromPos, plainTextToPos)
    } else {
      Logging.LOGGER.warning(
        I18n.format(
          "couldNotDeterminePlainTextPositions",
          fromPos,
          toPos,
          plainTextFromPos,
          plainTextToPos,
        ),
      )
      ""
    }
  }

  companion object {
    // Maps a source position to a plain-text position by walking the parts in
    // order. A TEXT part has the same length in the source and in the plain text,
    // so a position inside it maps one to one. A MARKUP part has its own length in
    // the source and the length of the FAKE_CONTENT that follows it (if any) in
    // the plain text. A position inside a markup part maps to the start of its
    // plain text for the start of a range, and to the end for the end of a range.
    // A markup part with no source, such as the "Dummy0" that AnnotatedTextSlicer
    // leaves when it cuts a paragraph inside a markup part, is skipped.
    //
    // An earlier version interpolated between the mapping points of an inverted
    // AnnotatedText. LanguageTool's AnnotatedTextBuilder keeps one mapping point
    // per position, so a markup part with no source added none, and the quoted
    // word after it was shifted (ltex-plus/ltex-ls-plus#226).
    fun getPlainTextPosition(
      annotatedText: AnnotatedText,
      sourcePos: Int,
      isToPos: Boolean,
    ): Int {
      var sourceStart = 0
      var plainStart = 0

      for (segment: Segment in getSegments(annotatedText.parts)) {
        val sourceEnd: Int = sourceStart + segment.sourceLength
        val isInSegment: Boolean =
          if (isToPos) (sourcePos <= sourceEnd) else (sourcePos < sourceEnd)

        if (isInSegment) {
          val offset: Int = sourcePos - sourceStart
          return plainStart +
            when {
              segment.isText -> offset
              isToPos && (offset > 0) -> segment.plainLength
              else -> 0
            }
        }

        sourceStart = sourceEnd
        plainStart += segment.plainLength
      }

      return plainStart
    }

    // A TEXT part, or a MARKUP part together with the FAKE_CONTENT that follows it.
    private data class Segment(
      val sourceLength: Int,
      val plainLength: Int,
      val isText: Boolean,
    )

    private fun getSegments(parts: List<TextPart>): List<Segment> {
      val segments = ArrayList<Segment>()
      var i = 0

      while (i < parts.size) {
        val part: TextPart = parts[i]

        when (part.type) {
          TextPart.Type.TEXT -> {
            segments.add(Segment(part.part.length, part.part.length, true))
          }

          TextPart.Type.MARKUP -> {
            var plainLength = 0
            if ((i + 1 < parts.size) && (parts[i + 1].type == TextPart.Type.FAKE_CONTENT)) {
              i++
              plainLength = parts[i].part.length
            }
            segments.add(Segment(part.part.length, plainLength, false))
          }

          TextPart.Type.FAKE_CONTENT -> {
            segments.add(Segment(0, part.part.length, false))
          }

          null -> {}
        }

        i++
      }

      return segments
    }
  }
}
