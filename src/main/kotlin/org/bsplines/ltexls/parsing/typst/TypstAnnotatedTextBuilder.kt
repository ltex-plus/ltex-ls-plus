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

open class TypstAnnotatedTextBuilder(
  codeLanguageId: String,
) : CharacterBasedCodeAnnotatedTextBuilder(codeLanguageId) {
  val headingParser = CharacterBasedCodeAnnotatedHeadingParser(this)
  val modeHandler = TypstModeHandler(this)

  override fun processCharacter() {
    addMarkup(NO_TEXT_INLINE_MATH_REGEX, "", true)
    modeHandler.processMathBlock()
    processEscapeCharacter()
    modeHandler.processCodeMode()
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
    addMarkup(SQUARE_BRACKETS_REGEX_MID, "\n")
    addMarkup(FOR_WHILE_IF_REGEX)
    addMarkup(ELSE_REGEX)
    addMarkup(BRACKETS_REGEX, "\n")

    addBasicMarkup()

    addText(this.curString)
  }

  override fun addText(text: String?): CharacterBasedCodeAnnotatedTextBuilder {
    if (characterProcessed) return this
    return super.addText(text)
  }

  override fun addMarkup(markup: String?): CharacterBasedCodeAnnotatedTextBuilder {
    if (characterProcessed) return this
    return super.addMarkup(markup)
  }

  fun addBasicMarkup() {
    addMarkup(LINE_COMMENT_REGEX, "\n")
    addMarkup(MULTILINELINE_COMMENT_REGEX, "\n")
    addMarkup(MARKUP_REGEX)
    addMarkup(IMPORT_REGEX, "\n")
    addMarkup(SHOW_REGEX, "\n")
    addMarkup(LABEL_REGEX, " ", true)
    addMarkup(LABEL_REF_REGEX)
    addMarkup(VARIABLE_REGEX, "", true)
    addMarkup(QUOTATION_MARK_REGEX)
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

  companion object {
    val NO_TEXT_INLINE_MATH_REGEX = Regex("^\\$[^\"$\n]*\\$")
    val LIST_REGEX = Regex("^\\s*[+|\\-|\\/]\\s")
    val LEADING_WHITESPACE_REGEX = Regex("^\\s*")
    val LET_STRING_REGEX = Regex("^#let\\s.*?=\\s*\"")
    val LET_CURLY_BRACKETS_REGEX = Regex("^#let\\s[^\$]*?=[^\$\r\n]*\\{")
    val LET_ROUND_BRACKETS_REGEX = Regex("^#let\\s[^\$]*?=[^\$\r\n]*\\(")
    val LET_SINGLE_LINE_REGEX = Regex("^#let\\s.*?=.*\r?\n")
    val RAW_CODE_REGEX_1 = Regex("^`{3,}(.|\r?\n)*?`{3,}")
    val RAW_CODE_REGEX_2 = Regex("^`(.|\r?\n)*?`")
    val RAW_CODE_REGEX_3 = Regex("^#raw\\((.|\r?\n)*?[^\\\\]\"\\)")
    val CITE_REGEX = Regex("^#cite\\(\\S+\\)")
    val FOOTNOTE_REGEX = Regex("^#footnote\\[(.|\r?\n)*?\\]")
    val CODE_REGEX = Regex("^#.*?\\(")
    val CODE_CURLY_BRACKETS_REGEX = Regex("^#\\{")
    val SQUARE_BRACKETS_REGEX_MID = Regex("^\\]\\[")
    val FOR_WHILE_IF_REGEX = Regex("^(#for|#while|#if)\\s.*?(\\[|\\{)")
    val ELSE_REGEX = Regex("^(\\]|\\})\\s*else.*?(\\[|\\{)")
    val BRACKETS_REGEX = Regex("^(\\{|\\}|\\[|\\])")
    val LINE_COMMENT_REGEX = Regex("^\\/\\/.*(\r?\n|$)")
    val MULTILINELINE_COMMENT_REGEX = Regex("^\\/\\*(.|\r?\n)*?\\*\\/")
    val MARKUP_REGEX = Regex("^(\\*|\\_)")
    val IMPORT_REGEX = Regex("^(#import|#include)\\s.*\r?\n")
    val SHOW_REGEX = Regex("^#show\\s.*\r?\n")
    val LABEL_REGEX = Regex("^\\s@[^\\s]*")
    val LABEL_REF_REGEX = Regex("^<[^\\s]*>")
    val VARIABLE_REGEX = Regex("^#\\w*")
    val QUOTATION_MARK_REGEX = Regex("^\"")
    val QUOTATION_MARK_WHITESPACE_REGEX = Regex("^\"\\s*")
    val WHITESPACE_QUOTATION_MARK_REGEX = Regex("^\\s*(?=\")")
    val FILENAME_REGEX = Regex("^.+\\.\\w{1,4}")
    val PROPERTY_REGEX =
      Regex(
        "^(font|fit|style|weight|top-edge|bottom-edge|lang|region|script|number-type|number-width)\\s?:\\s?\".*?\"",
      )
  }
}
