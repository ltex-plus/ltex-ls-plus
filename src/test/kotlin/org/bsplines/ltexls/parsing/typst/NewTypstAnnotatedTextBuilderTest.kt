/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import net.arnx.jsonic.JSON
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class NewTypstAnnotatedTextBuilderTest {

  private fun getResourcePath(name: String): Path =
    Path.of(object {}.javaClass.getResource(name)!!.toURI())

  private fun read(path: String): String =
    getResourcePath(path).readText()

  private fun assertTokenized(name: String) {
    val source = read("/typst/$name.typ")
    val tokenizedText = JSON.encode(TypstTokenizer(source).asSequence().toList(), true)
    val expectedTokenizedText = read("/typst/${name}.tokenized.txt")

    assertEquals(
      expectedTokenizedText,
      tokenizedText,
    )
  }

  private fun assertAnnotated(name: String) {
    val source = read("/typst/$name.typ")
    val annotatedText = NewTypstAnnotatedTextBuilder(source).parse().previewText()
    val expectedAnnotatedText = read("/typst/${name}.annotated.txt")

    assertEquals(
      expectedAnnotatedText,
      annotatedText,
    )
  }


  private fun assertRebuildSuccessful(name: String) {
    val source = read("/typst/$name.typ")
    val tokenizedRebuild = TypstTokenizer(source).asSequence().map { it.value }.joinToString("")
    val annotatedRebuild = NewTypstAnnotatedTextBuilder(source).parse().previewAll()
    assertEquals(
      source,
      tokenizedRebuild,
    )
    assertEquals(
      source,
      annotatedRebuild,
    )
  }


  @Test
  fun testCtheoremManualTokenized() {
    assertTokenized("typst-theorems-manual")
  }
  
  @Test
  fun testCtheoremManualAnnotated() {
    assertAnnotated("typst-theorems-manual")
  }
  
  @Test
  fun testCtheoremManualRebuild() {
    assertRebuildSuccessful("typst-theorems-manual")
  }

}
