/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

class CharacterBasedCodeAnnotatedHeadingParser(
  private val textBuilder: CharacterBasedCodeAnnotatedTextBuilder,
) {
  private var headingMode = false
  var headingSubsequentMarkup = false

  fun processHeading() {
    if (textBuilder.isStartOfLine && textBuilder.matchFromPosition(HEADING_REGEX) != null) {
      textBuilder.addMarkup(HEADING_REGEX)
      headingMode = true
    }
    if (headingMode) {
      if (textBuilder.matchFromPosition(HEADING_END_REGEX) != null) {
        if (textBuilder.curString == "?") {
          textBuilder.addMarkup(HEADING_END_REGEX, "?\n")
        } else if (textBuilder.curString == "!") {
          textBuilder.addMarkup(HEADING_END_REGEX, "!\n")
        } else {
          textBuilder.addMarkup(HEADING_END_REGEX, ".\n")
        }
        headingMode = false
        headingSubsequentMarkup = true
      }
    }
  }

  companion object {
    private val HEADING_REGEX = Regex("^=+\\s")
    private val HEADING_END_REGEX = Regex("^\\.?\\??\\!?\r?\n")
  }
}
