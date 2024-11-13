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
  private var codeMode = false
  private var codeModeString = false
  private var codeModeBracketsCounter = 0

  override fun processCharacter() {
    processEscapeCharacter()
    processMathBlock()
    processCodeMode()

    if (this.isStartOfLine) {
      addMarkupInternal(LIST_REGEX)
      addMarkupInternal(LEADING_WHITESPACE_REGEX)
      addMarkupInternal(HEADING_REGEX)
    }

    addMarkupInternal(CODE_REGEX, "", true)
    addMarkupInternal(LINE_COMMENT_REGEX, "\n")
    addMarkupInternal(MULTILINELINE_COMMENT_REGEX, "\n")
    addMarkupInternal(MARKUP_REGEX)
    addMarkupInternal(LET_REGEX)
    addMarkupInternal(IMPORT_REGEX, "\n")
    addMarkupInternal(SQUARE_BRACKETS_REGEX)

    addText(this.curString)
  }

  override fun addText(text: String?): CharacterBasedCodeAnnotatedTextBuilder {
    if (characterProcessed) return this
    return super.addText(text)
  }

  private fun addMarkupInternal(
    regex: Regex,
    interpretAs: String = "",
    startofCodeBlock: Boolean = false,
  ) {
    if (characterProcessed) return
    var matchResult: MatchResult?
    matchResult = matchFromPosition(regex)
    if (matchResult != null) {
      addMarkup(matchResult.value, interpretAs)
      if (startofCodeBlock) {
        codeModeBracketsCounter++
        codeMode = true
      }
    }
  }

  private fun processEscapeCharacter() {
    if (characterProcessed) return
    // Check for backslash escape character
    if (this.curString == "\\") {
      addMarkup(this.curString)
      // Add subsequent char as text if available
      if (this.code.length > this.pos) {
        characterProcessed = false
        addText(this.code[this.pos].toString())
      }
    }
  }

  private fun processMathBlock() {
    if (characterProcessed) return
    if (this.curString == "$") {
      // Start or end of math mode
      mathMode = !mathMode
      addMarkup(this.curString)
    } else if (mathMode) {
      if (this.curString == "\"") {
        // Start or end of String within math mode
        mathModeString = !mathModeString
        addText(this.curString)
      }
      if (mathModeString) {
        // String within math mode to be spell checked
        addText(this.curString)
      } else {
        addMarkup(this.curString)
      }
    }
  }

  private fun processCodeMode() {
    if (!codeMode || characterProcessed) return
    when (this.curString) {
      "(" -> {
        codeModeBracketsCounter++
        addMarkup(this.curString)
      }
      ")" -> {
        codeModeBracketsCounter--
        addMarkup(this.curString)
        // Last closing parenthesis?
        if (codeModeBracketsCounter == 0) codeMode = false
      } "\"" -> {
        codeModeString = !codeModeString
        addMarkup(this.curString, "\n")
      } else -> {
        if (codeModeString) {
          // String within code mode to be spell checked
          addText(this.curString)
        } else {
          addMarkup(this.curString)
        }
      }
    }
  }

  companion object {
    private val CODE_REGEX = Regex("^#.*\\(")

    private val LIST_REGEX = Regex("^\\s*[+|\\-|\\/]\\s")
    private val LEADING_WHITESPACE_REGEX = Regex("^\\s*")
    private val HEADING_REGEX = Regex("^=+\\s")

    private val LINE_COMMENT_REGEX = Regex("^\\/\\/.*(\r?\n|$)")
    private val MULTILINELINE_COMMENT_REGEX = Regex("^\\/\\*(.|\r?\n)*\\*\\/")
    private val MARKUP_REGEX = Regex("^(\\$|\\*|\\_)")
    private val LET_REGEX = Regex("^#let.*=")
    private val IMPORT_REGEX = Regex("^(#import|#include).*\r?\n")
    private val SQUARE_BRACKETS_REGEX = Regex("^(\\[\r?\n|\\](\r?\n|$))")
  }
}
