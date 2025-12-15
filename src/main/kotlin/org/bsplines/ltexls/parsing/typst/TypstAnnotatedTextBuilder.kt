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
  private val headingParser = CharacterBasedCodeAnnotatedHeadingParser(this)
  private val modeHandler = TypstModeHandler(this)

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
    private val NO_TEXT_INLINE_MATH_REGEX = Regex("^\\$[^\"$\n]*\\$")
    private val LIST_REGEX = Regex("^\\s*[+|\\-|\\/]\\s")
    val LEADING_WHITESPACE_REGEX = Regex("^\\s*")
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
    private val SQUARE_BRACKETS_REGEX_MID = Regex("^\\]\\[")
    private val FOR_WHILE_IF_REGEX = Regex("^(#for|#while|#if)\\s.*?(\\[|\\{)")
    private val ELSE_REGEX = Regex("^(\\]|\\})\\s*else.*?(\\[|\\{)")
    private val BRACKETS_REGEX = Regex("^(\\{|\\}|\\[|\\])")
    private val LINE_COMMENT_REGEX = Regex("^\\/\\/.*(\r?\n|$)")
    private val MULTILINELINE_COMMENT_REGEX = Regex("^\\/\\*(.|\r?\n)*?\\*\\/")
    private val MARKUP_REGEX = Regex("^(\\*|\\_)")
    private val IMPORT_REGEX = Regex("^(#import|#include)\\s.*\r?\n")
    private val SHOW_REGEX = Regex("^#show\\s.*\r?\n")
    private val LABEL_REGEX = Regex("^\\s@[^\\s]*")
    private val LABEL_REF_REGEX = Regex("^<[^\\s]*>")
    private val VARIABLE_REGEX = Regex("^#\\w*")
    private val QUOTATION_MARK_REGEX = Regex("^\"")
  }
}
