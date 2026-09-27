/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.parsing.CharacterBasedCodeAnnotatedTextBuilder.BracketType

class CodeModeHandler {
  var bracketsCounter = 0
    private set
  var stringCounter = 0
  var squareBracketscounter = 0
  var codeModeString = false
  var codeModeContentBlock = false
  var mode = false

  /**
   * Stack of the code blocks that are currently open, innermost last.
   *
   * A single counter is not enough to remember which bracket belongs to which
   * block: a call nested in a content block (`#grid([#text(x)[y]])`) switches
   * the delimiter from `(`/`)` to `{`/`}`, so the closing bracket of the outer
   * block would never be recognized again. Each entry records the bracket
   * counter at the time the block was opened, which is exactly the value the
   * counter has to fall back to for that block to close.
   */
  private val openBlocks = ArrayDeque<OpenBlock>()

  val codeBlockDelimiter: BracketType
    get() = this.openBlocks.lastOrNull()?.delimiter ?: BracketType.RoundBracket

  fun openBlock(delimiter: BracketType) {
    this.bracketsCounter++
    this.openBlocks.addLast(
      OpenBlock(
        level = this.bracketsCounter,
        delimiter = delimiter,
        squareBracketscounter = this.squareBracketscounter,
        inContentBlock = this.codeModeContentBlock,
      ),
    )
    this.mode = true
  }

  fun adjustBracketsCounter(delta: Int) {
    if (this.codeModeString) return
    this.bracketsCounter += delta
    if (delta < 0) {
      while (this.openBlocks.isNotEmpty() && this.openBlocks.last().level > this.bracketsCounter) {
        this.openBlocks.removeLast()
      }
    }
    if (this.bracketsCounter <= 0) {
      this.bracketsCounter = 0
      this.mode = false
      this.openBlocks.clear()
      this.squareBracketscounter = 0
      this.codeModeContentBlock = false
    }
  }

  /**
   * Whether the current position of an open content block is code rather than
   * prose, i.e. whether it lies inside a call that was started from within the
   * content block (`#text(size: 12pt)` in `[#text(size: 12pt)[Ahoj]]`).
   *
   * Arguments of such a call are code and must not be spell-checked, while the
   * content block the call was opened from and any content block the call
   * itself receives (`[Ahoj]`) are prose.
   */
  fun isContentBlockCode(): Boolean {
    if (!this.codeModeContentBlock) return false
    val block = this.openBlocks.lastOrNull { it.inContentBlock } ?: return false
    return this.squareBracketscounter <= block.squareBracketscounter
  }

  private class OpenBlock(
    val level: Int,
    val delimiter: BracketType,
    val squareBracketscounter: Int,
    val inContentBlock: Boolean,
  )
}
