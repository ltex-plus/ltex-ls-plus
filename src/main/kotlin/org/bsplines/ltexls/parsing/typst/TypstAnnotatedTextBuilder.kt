/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
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
  private var mathModeStringCounter = 0

  override fun processCharacter() {
    processEscapeCharacter()
    addMarkup(NO_TEXT_INLINE_MATH_REGEX, "", true)
    processMathBlock()
    processCodeMode()
    processHeading()

    if (this.isStartOfLine) {
      addMarkup(LIST_REGEX)
      addMarkup(LEADING_WHITESPACE_REGEX)
    }

    addMarkup(LET_CURLY_BRACKETS_REGEX)
    addMarkup(LET_CODE_REGEX, "", false, true)
    addMarkup(LET_REGEX)
    addMarkup(RAW_CODE_REGEX_1, "", true)
    addMarkup(RAW_CODE_REGEX_2, "", true)
    addMarkup(RAW_CODE_REGEX_3, "", true)
    addMarkup(CITE_REGEX)
    addMarkup(CODE_REGEX, "", false, true)
    addMarkup(LINE_COMMENT_REGEX, "\n")
    addMarkup(MULTILINELINE_COMMENT_REGEX, "\n")
    addMarkup(MARKUP_REGEX)
    addMarkup(IMPORT_REGEX, "\n")
    addMarkup(SHOW_REGEX, "\n")
    addMarkup(LABEL_REGEX, " ", true)
    addMarkup(LABEL_REF_REGEX)
    addMarkup(VARIABLE_REGEX, "", true)
    addMarkup(SQUARE_BRACKETS_REGEX_MID, "\n")
    addMarkup(SQUARE_BRACKETS_REGEX_START_END, "\n")

    addText(this.curString)
  }

  override fun addText(text: String?): CharacterBasedCodeAnnotatedTextBuilder {
    if (characterProcessed) return this
    return super.addText(text)
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
      if (!mathMode) mathModeStringCounter = 0
      addMarkup(this.curString)
    } else if (mathMode) {
      if (this.curString == "\"") {
        // Start or end of String within math mode
        mathModeString = !mathModeString
        if (mathModeString && mathModeStringCounter > 0) {
          addMarkup(this.curString, " ")
        } else {
          // First String of current math mode does not get a leading space
          addMarkup(this.curString)
        }
        mathModeStringCounter++
      } else if (mathModeString) {
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
        if (codeModeBracketsCounter == 0) {
          codeMode = false
        }
      } "\"" -> {
        codeModeString = !codeModeString
        if (codeModeString && codeModeStringCounter > 0) {
          addMarkup(this.curString, " ")
        } else {
          // First String of current code mode does not get a leading space
          addMarkup(this.curString)
        }
        codeModeStringCounter++
      } else -> {
        if (codeModeString) {
          addMarkup(FILENAME_REGEX, generateDummy())
          // String within code mode to be spell checked
          addText(this.curString)
        } else {
          addMarkup(PROPERTY_REGEX)
          if (!characterProcessed) addMarkup(this.curString)
        }
      }
    }
  }

  companion object {
    private val NO_TEXT_INLINE_MATH_REGEX = Regex("^\\$[^\"$\n]*\\$")
    private val LIST_REGEX = Regex("^\\s*[+|\\-|\\/]\\s")
    private val LEADING_WHITESPACE_REGEX = Regex("^\\s*")
    private val LET_CURLY_BRACKETS_REGEX = Regex("^#let.*=\\s*\\{(.|\r?\n)*?\\}")
    private val LET_CODE_REGEX = Regex("^#let.*?=\\s*?\\w*?\\(")
    private val LET_REGEX = Regex("^#let.*=\\s*")
    private val RAW_CODE_REGEX_1 = Regex("^`{3,}(.|\r?\n)*?`{3,}")
    private val RAW_CODE_REGEX_2 = Regex("^`(.|\r?\n)*?`")
    private val RAW_CODE_REGEX_3 = Regex("^#raw\\((.|\r?\n)*?[^\\\\]\"\\)")
    private val CITE_REGEX = Regex("^#cite\\(\\S+\\)")
    private val CODE_REGEX = Regex("^#.*?\\(")
    private val LINE_COMMENT_REGEX = Regex("^\\/\\/.*(\r?\n|$)")
    private val MULTILINELINE_COMMENT_REGEX = Regex("^\\/\\*(.|\r?\n)*?\\*\\/")
    private val MARKUP_REGEX = Regex("^(\\*|\\_)")
    private val IMPORT_REGEX = Regex("^(#import|#include).*\r?\n")
    private val SHOW_REGEX = Regex("^#show.*\r?\n")
    private val LABEL_REGEX = Regex("^\\s@[^\\s]*")
    private val LABEL_REF_REGEX = Regex("^<[^\\s]*>")
    private val VARIABLE_REGEX = Regex("^#\\w*")
    private val SQUARE_BRACKETS_REGEX_MID = Regex("^\\]\\[")
    private val SQUARE_BRACKETS_REGEX_START_END = Regex("^(\\[|\\])")
    private val FILENAME_REGEX = Regex("^.+\\.\\w{1,4}")
    private val PROPERTY_REGEX =
      Regex(
        "^(font|style|weight|top-edge|bottom-edge|lang|region|script|number-type|number-width)\\s?:\\s?\".*?\"",
      )
  }
}
