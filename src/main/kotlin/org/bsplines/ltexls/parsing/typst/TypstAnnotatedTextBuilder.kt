/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.CharacterBasedCodeAnnotatedTextBuilder

class TypstAnnotatedTextBuilder(
  codeLanguageId: String,
) : CharacterBasedCodeAnnotatedTextBuilder(codeLanguageId) {
  private var mathMode = false
  private var mathModeString = false

  @Suppress("ReturnCount", "ComplexMethod")
  override fun processCharacter() {
    if (processEscapeCharacter()) return
    if (processMathBlock()) return

    if (this.isStartOfLine) {
      if (addMarkupInternal(LIST_REGEX)) return
      if (addMarkupInternal(LEADING_WHITESPACE_REGEX)) return
      if (addMarkupInternal(HEADING_REGEX)) return
    }

    if (addMarkupInternal(LINE_COMMENT_REGEX, "\n")) return
    if (addMarkupInternal(MULTILINELINE_COMMENT_REGEX, "\n")) return
    if (addMarkupInternal(MARKUP_REGEX)) return
    if (addMarkupInternal(LET_REGEX)) return
    if (addMarkupInternal(IMPORT_REGEX, "\n")) return
    if (addMarkupInternal(SHOW_REGEX, "\n(")) return
    if (addMarkupInternal(CODE_REGEX, "\n(")) return
    if (addMarkupInternal(CLOSING_PARENTHESIS_REGEX, ")\n")) return

    addText(this.curString)
  }

  private fun addMarkupInternal(
    regex: Regex,
    interpretAs: String = "",
  ): Boolean {
    var matchResult: MatchResult?
    matchResult = matchFromPosition(regex)
    if (matchResult != null) {
      addMarkup(matchResult.value, interpretAs)
      return true
    }
    return false
  }

  private fun processEscapeCharacter(): Boolean {
    // Check for backslash escape character
    if (this.curString == "\\") {
      addMarkup(this.curString)
      // Add subsequent char as text if available
      if (this.code.length > this.pos) {
        addText(this.code[this.pos].toString())
        return true
      }
    }
    return false
  }

  private fun processMathBlock(): Boolean {
    if (this.curString == "$") {
      // Start or end of math mode
      mathMode = !mathMode
      addMarkup(this.curString)
      return true
    } else if (mathMode) {
      if (this.curString == "\"") {
        // Start or end of String within math mode
        mathModeString = !mathModeString
        addText(this.curString)
        return true
      }
      if (mathModeString) {
        // String within math mode to be spell checked
        addText(this.curString)
      } else {
        addMarkup(this.curString)
      }
      return true
    } else {
      return false
    }
  }

  companion object {
    private val LIST_REGEX = Regex("^\\s*[+|\\-|\\/]\\s")
    private val LEADING_WHITESPACE_REGEX = Regex("^\\s*")
    private val HEADING_REGEX = Regex("^=+\\s")

    private val LINE_COMMENT_REGEX = Regex("^\\/\\/.*(\r?\n|$)")
    private val MULTILINELINE_COMMENT_REGEX = Regex("^\\/\\*(.|\r?\n)*\\*\\/")
    private val MARKUP_REGEX = Regex("^(\\$|\\*|\\_)")
    private val LET_REGEX = Regex("^#let\\s*[-a-z]*\\s*\\w*\\s*\\=")
    private val IMPORT_REGEX = Regex("^\\s*(#import|include).*\r?\n")
    private val SHOW_REGEX = Regex("^#show:\\s\\w*.with\\(\\s*\r?\n")
    private val CODE_REGEX = Regex("^#\\w*\\(\\s*\r?\n?")
    private val CLOSING_PARENTHESIS_REGEX = Regex("^,?\r?\n\\s*\\)(\r?\n|$)")
  }
}
