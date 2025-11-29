/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.CharacterBasedCodeAnnotatedHeadingParser
import org.bsplines.ltexls.parsing.CharacterBasedCodeAnnotatedTextBuilder

class TypstAnnotatedTextBuilder(
  codeLanguageId: String,
) : CharacterBasedCodeAnnotatedTextBuilder(codeLanguageId) {
  private var mathMode = false
  private var mathModeString = false
  private var mathModeStringCounter = 0
  private val headingParser = CharacterBasedCodeAnnotatedHeadingParser(this)

  override fun processCharacter() {
    addMarkup(NO_TEXT_INLINE_MATH_REGEX, "", true)
    processMathBlock()
    processEscapeCharacter()
    processCodeMode()
    headingParser.processHeading()

    if (this.isStartOfLine) {
      addMarkup(LIST_REGEX)
      addMarkup(LEADING_WHITESPACE_REGEX)
    }

    addMarkup(LET_STRING_REGEX)
    addMarkup(LET_CURLY_BRACKETS_REGEX, "", false, true, BracketType.CurlyBracket)
    addMarkup(LET_ROUND_BRACKETS_REGEX, "", false, true)
    addMarkup(LET_SINGLE_LINE_REGEX, "\n")
    addMarkup(RAW_CODE_REGEX_1, "", true)
    addMarkup(RAW_CODE_REGEX_2, "", true)
    addMarkup(RAW_CODE_REGEX_3, "", true)
    addMarkup(CITE_REGEX)
    addMarkup(FOOTNOTE_REGEX)
    addMarkup(CODE_REGEX, "", false, true)
    addMarkup(CODE_CURLY_BRACKETS_REGEX, "", false, true, BracketType.CurlyBracket)
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
    addMarkup(QUOTATION_MARK_REGEX)

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
          addMarkup(QUOTATION_MARK_WHITESPACE_REGEX, " ")
        } else {
          // First String of current math mode does not get a leading space
          addMarkup(QUOTATION_MARK_WHITESPACE_REGEX)
        }
        mathModeStringCounter++
      } else if (mathModeString) {
        addMarkup(WHITESPACE_QUOTATION_MARK_REGEX)
        addMarkup(LEADING_WHITESPACE_REGEX, " ")
        // String within math mode to be spell checked
        addText(this.curString)
      } else {
        addMarkup(this.curString)
      }
    }
  }

  private fun processCodeMode() {
    if (!codeMode.mode || characterProcessed) return
    when (this.curString) {
      codeBlockDelimiter.openingBracket -> {
        codeMode.adjustBracketsCounter(1)
        addMarkup(this.curString)
      }

      codeBlockDelimiter.closingBracket -> {
        codeMode.adjustBracketsCounter(-1)
        addMarkup(this.curString)
      }

      "\"" -> {
        codeMode.codeModeString = !codeMode.codeModeString
        if (codeMode.codeModeString && codeMode.stringCounter > 0) {
          addMarkup(this.curString, " ")
        } else {
          // First String of current code mode does not get a leading space
          addMarkup(this.curString)
        }
        codeMode.stringCounter++
      }

      else -> {
        if (codeMode.codeModeString) {
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
    private val LET_STRING_REGEX = Regex("^#let\\s.*?=\\s*\"")
    private val LET_CURLY_BRACKETS_REGEX = Regex("^#let\\s[^\$]*?=[^\$\r\n]*\\{")
    private val LET_ROUND_BRACKETS_REGEX = Regex("^#let\\s[^\$]*?=[^\$\r\n]*\\(")
    private val LET_SINGLE_LINE_REGEX = Regex("^#let\\s.*?=.*\r?\n")
    private val RAW_CODE_REGEX_1 = Regex("^`{3,}(.|\r?\n)*?`{3,}")
    private val RAW_CODE_REGEX_2 = Regex("^`(.|\r?\n)*?`")
    private val RAW_CODE_REGEX_3 = Regex("^#raw\\((.|\r?\n)*?[^\\\\]\"\\)")
    private val CITE_REGEX = Regex("^#cite\\(\\S+\\)")
    private val FOOTNOTE_REGEX = Regex("^#footnote\\[(.|\r?\n)*?\\]")
    private val CODE_REGEX = Regex("^#.*?\\(")
    private val CODE_CURLY_BRACKETS_REGEX = Regex("^#\\{")
    private val LINE_COMMENT_REGEX = Regex("^\\/\\/.*(\r?\n|$)")
    private val MULTILINELINE_COMMENT_REGEX = Regex("^\\/\\*(.|\r?\n)*?\\*\\/")
    private val MARKUP_REGEX = Regex("^(\\*|\\_)")
    private val IMPORT_REGEX = Regex("^(#import|#include)\\s.*\r?\n")
    private val SHOW_REGEX = Regex("^#show\\s.*\r?\n")
    private val LABEL_REGEX = Regex("^\\s@[^\\s]*")
    private val LABEL_REF_REGEX = Regex("^<[^\\s]*>")
    private val VARIABLE_REGEX = Regex("^#\\w*")
    private val SQUARE_BRACKETS_REGEX_MID = Regex("^\\]\\[")
    private val SQUARE_BRACKETS_REGEX_START_END = Regex("^(\\[|\\])")
    private val QUOTATION_MARK_REGEX = Regex("^\"")
    private val QUOTATION_MARK_WHITESPACE_REGEX = Regex("^\"\\s*")
    private val WHITESPACE_QUOTATION_MARK_REGEX = Regex("^\\s*(?=\")")
    private val FILENAME_REGEX = Regex("^.+\\.\\w{1,4}")
    private val PROPERTY_REGEX =
      Regex(
        "^(font|fit|style|weight|top-edge|bottom-edge|lang|region|script|number-type|number-width)\\s?:\\s?\".*?\"",
      )
  }
}
