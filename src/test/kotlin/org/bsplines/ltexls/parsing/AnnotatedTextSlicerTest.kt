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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnotatedTextSlicerTest {
  // A single paragraph (no blank line) is returned unchanged as one slice.
  @Test
  fun testSingleParagraphNotSplit() {
    val annotatedText: AnnotatedText = text("This is one paragraph.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(1, slices.size)
    assertEquals("This is one paragraph.", slices[0].annotatedText.plainText)
    assertEquals(0, slices[0].sourceFromPos)
  }

  // The realistic LaTeX/Org case: prose across a blank line is buffered into ONE
  // TEXT part, so the slicer must cut the part's string. The blank-line newlines
  // trail the preceding slice.
  @Test
  fun testSplitInsideSingleTextPart() {
    val annotatedText: AnnotatedText = text("First paragraph.\n\nSecond paragraph.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(2, slices.size)
    assertEquals("First paragraph.\n\n", slices[0].annotatedText.plainText)
    assertEquals("Second paragraph.", slices[1].annotatedText.plainText)
    assertEquals(0, slices[0].sourceFromPos)
    assertEquals("First paragraph.\n\n".length, slices[1].sourceFromPos)
  }

  // Three paragraphs in one TEXT part -> three slices.
  @Test
  fun testThreeParagraphs() {
    val annotatedText: AnnotatedText = text("Alpha.\n\nBeta.\n\nGamma.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(3, slices.size)
    assertEquals("Alpha.\n\n", slices[0].annotatedText.plainText)
    assertEquals("Beta.\n\n", slices[1].annotatedText.plainText)
    assertEquals("Gamma.", slices[2].annotatedText.plainText)
  }

  // A run of more than two newlines is one boundary; the whole run trails the
  // preceding slice.
  @Test
  fun testMultipleBlankLinesAreOneBoundary() {
    val annotatedText: AnnotatedText = text("A.\n\n\nB.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(2, slices.size)
    assertEquals("A.\n\n\n", slices[0].annotatedText.plainText)
    assertEquals("B.", slices[1].annotatedText.plainText)
  }

  // Markup before a paragraph counts toward the SOURCE offset but not the plain
  // text. The second slice's sourceFromPos must include the markup length so a
  // match reprojects to the correct source position.
  @Test
  fun testMarkupCountsTowardSourceOffset() {
    // source: "\\b{}First.\n\nSecond."  (markup "\\b{}" has no plain text)
    val builder = AnnotatedTextBuilder()
    builder.addMarkup("\\b{}")
    builder.addText("First.\n\nSecond.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(builder.build())

    assertEquals(2, slices.size)
    assertEquals("First.\n\n", slices[0].annotatedText.plainText)
    assertEquals("Second.", slices[1].annotatedText.plainText)
    assertEquals(0, slices[0].sourceFromPos)
    // "\\b{}" (4 source chars) + "First.\n\n" (8) = 12
    assertEquals(12, slices[1].sourceFromPos)
  }

  // A paragraph break emitted as its own part pair (MARKUP + FAKE_CONTENT whose
  // plain text is "\n\n", as the LaTeX builder does for some whitespace) is a
  // valid boundary; the markup attaches to the preceding slice.
  @Test
  fun testBoundaryAsFakeContentPart() {
    val builder = AnnotatedTextBuilder()
    builder.addText("First.")
    builder.addMarkup("\n\n", "\n\n") // MARKUP carrying FAKE_CONTENT "\n\n"
    builder.addText("Second.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(builder.build())

    assertEquals(2, slices.size)
    assertEquals("First.\n\n", slices[0].annotatedText.plainText)
    assertEquals("Second.", slices[1].annotatedText.plainText)
  }

  // A leading blank run has no preceding content, so it folds into the next
  // slice rather than forming a whitespace-only slice.
  @Test
  fun testLeadingBlankLineFoldsIntoNextSlice() {
    val annotatedText: AnnotatedText = text("\n\nText.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(1, slices.size)
    assertEquals("\n\nText.", slices[0].annotatedText.plainText)
    assertEquals(0, slices[0].sourceFromPos)
  }

  // A blank run between two paragraphs where the SECOND would-be segment also
  // starts with blanks still folds correctly: blanks never form their own slice.
  @Test
  fun testInteriorBlankRunNeverFormsEmptySlice() {
    val annotatedText: AnnotatedText = text("A.\n\n\n\nB.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(2, slices.size)
    assertEquals("A.\n\n\n\n", slices[0].annotatedText.plainText)
    assertEquals("B.", slices[1].annotatedText.plainText)
  }

  // Trailing blank line stays with the final paragraph.
  @Test
  fun testTrailingBlankLine() {
    val annotatedText: AnnotatedText = text("Only paragraph.\n\n")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(1, slices.size)
    assertEquals("Only paragraph.\n\n", slices[0].annotatedText.plainText)
  }

  // Empty input yields a single empty slice (mirrors the source-splitters'
  // empty-input contract).
  @Test
  fun testEmptyInput() {
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(text(""))

    assertEquals(1, slices.size)
    assertEquals("", slices[0].annotatedText.plainText)
    assertEquals(0, slices[0].sourceFromPos)
  }

  // Reassembly: concatenating every slice's plain text reproduces the original
  // plain text exactly (no prose lost or duplicated), and source ranges
  // contiguously partition [0, sourceLength).
  @Test
  fun testReassemblyAndPartitionInvariants() {
    val builder = AnnotatedTextBuilder()
    builder.addMarkup("\\section{")
    builder.addText("Title")
    builder.addMarkup("}")
    builder.addText("\n\nBody one has a typo.\n\nBody two.")
    val annotatedText: AnnotatedText = builder.build()
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(annotatedText.plainText, slices.joinToString("") { it.annotatedText.plainText })
    assertSlicesPartitionSource(annotatedText, slices)
  }

  // Regression: a MARKUP immediately followed by its FAKE_CONTENT interpretation
  // (e.g. inline math "$x$" -> dummy "Dummy0") that lands in a sliced paragraph must
  // be re-emitted as a single addMarkup(markup, interpretAs), NOT as
  // addMarkup(markup) + addMarkup("", interpretAs). The latter inserts a spurious
  // empty MARKUP part between the markup and its fake content, which desynchronizes
  // the one-part-lookahead markup<->fake pairing used by
  // AnnotatedTextFragment.invertAnnotatedText and shifts getSubstringOfPlainText.
  @Test
  fun testMarkupFakeContentPairNotSplitByEmptyMarkup() {
    val builder = AnnotatedTextBuilder()
    builder.addText("First paragraph of prose here.\n\n")
    builder.addText("Then ")
    builder.addMarkup("\$x\$", "Dummy0") // MARKUP "$x$" with FAKE_CONTENT "Dummy0"
    builder.addText(" gives an eigenspace here.")
    val annotatedText: AnnotatedText = builder.build()
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)

    assertEquals(2, slices.size)
    assertEquals("Then Dummy0 gives an eigenspace here.", slices[1].annotatedText.plainText)
    for (slice: AnnotatedTextSlicer.Slice in slices) {
      assertNoEmptyMarkupSeparatingFake(slice.annotatedText)
    }
    // The markup+fake pairing must not disturb source/plain accounting.
    assertEquals(annotatedText.plainText, slices.joinToString("") { it.annotatedText.plainText })
    assertSlicesPartitionSource(annotatedText, slices)
  }

  // Merging the slices of a sliced text reproduces the original plain text and
  // part structure — the inverse of slice(), used to batch a run of cache-miss
  // paragraphs into one request.
  @Test
  fun testMergeAnnotatedTextsReversesSlice() {
    val builder = AnnotatedTextBuilder()
    builder.addMarkup("\\section{")
    builder.addText("Title")
    builder.addMarkup("}")
    builder.addText("\n\nBody one has a typo.\n\nBody two.")
    val original: AnnotatedText = builder.build()

    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(original)
    val merged: AnnotatedText =
      AnnotatedTextSlicer.mergeAnnotatedTexts(slices.map { it.annotatedText })

    assertEquals(original.plainText, merged.plainText)
    // Source length is preserved (TEXT + MARKUP lengths; FAKE_CONTENT counts 0).
    assertEquals(sourceLength(original), sourceLength(merged))
  }

  // Merging a contiguous subset spans exactly those paragraphs.
  @Test
  fun testMergeAnnotatedTextsOfSubset() {
    val annotatedText: AnnotatedText = text("Alpha.\n\nBeta.\n\nGamma.")
    val slices: List<AnnotatedTextSlicer.Slice> = AnnotatedTextSlicer.slice(annotatedText)
    val merged: AnnotatedText =
      AnnotatedTextSlicer.mergeAnnotatedTexts(slices.take(2).map { it.annotatedText })

    assertEquals("Alpha.\n\nBeta.\n\n", merged.plainText)
  }

  companion object {
    private fun text(plainText: String): AnnotatedText =
      AnnotatedTextBuilder().addText(plainText).build()

    // Asserts the markup<->fake invariant relied on by AnnotatedTextFragment: every
    // FAKE_CONTENT part is immediately preceded by a (non-empty) MARKUP part, i.e.
    // there is no empty MARKUP wedged between a real markup and its fake content.
    private fun assertNoEmptyMarkupSeparatingFake(annotatedText: AnnotatedText) {
      val parts: List<TextPart> = annotatedText.parts
      for (i in parts.indices) {
        if (parts[i].type != TextPart.Type.FAKE_CONTENT) continue
        assertTrue(
          (i > 0) &&
            (parts[i - 1].type == TextPart.Type.MARKUP) &&
            parts[i - 1].part.isNotEmpty(),
          "FAKE_CONTENT at part $i must follow a non-empty MARKUP (no empty markup wedge)",
        )
      }
    }

    // Total source length = sum of TEXT and MARKUP part lengths (FAKE_CONTENT
    // exists only in plain text and contributes zero source characters).
    private fun sourceLength(annotatedText: AnnotatedText): Int =
      annotatedText.parts.sumOf {
        if (it.type == TextPart.Type.FAKE_CONTENT) 0 else it.part.length
      }

    // Asserts the slices' [sourceFromPos, nextSourceFromPos) ranges contiguously
    // cover [0, sourceLength) in order, each character in exactly one slice.
    private fun assertSlicesPartitionSource(
      annotatedText: AnnotatedText,
      slices: List<AnnotatedTextSlicer.Slice>,
    ) {
      assertTrue(slices.isNotEmpty())
      assertEquals(0, slices.first().sourceFromPos)

      for (i in 1 until slices.size) {
        assertTrue(
          slices[i].sourceFromPos > slices[i - 1].sourceFromPos,
          "slice source offsets must be strictly increasing",
        )
        val prevSourceLength: Int = sourceLength(slices[i - 1].annotatedText)
        assertEquals(
          slices[i - 1].sourceFromPos + prevSourceLength,
          slices[i].sourceFromPos,
          "slice $i must start exactly where slice ${i - 1} ends in source",
        )
      }

      val lastEnd: Int =
        slices.last().sourceFromPos + sourceLength(slices.last().annotatedText)
      assertEquals(sourceLength(annotatedText), lastEnd, "slices must cover all source")
    }
  }
}
