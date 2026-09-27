/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.CharacterBasedCodeAnnotatedTextBuilder

class TypstModeHandler(
  private val typstTextBuilder: TypstAnnotatedTextBuilder,
) {
  private var mathMode = false
  private var mathModeString = false
  private var mathModeStringCounter = 0

  fun processMathBlock() {
    if (typstTextBuilder.characterProcessed) return
    if (typstTextBuilder.curString == "$") {
      // Start or end of math mode
      mathMode = !mathMode
      if (!mathMode) mathModeStringCounter = 0
      typstTextBuilder.addMarkup(typstTextBuilder.curString)
    } else if (mathMode) {
      processMathMode()
    }
  }

  private fun processMathMode() {
    if (typstTextBuilder.curString == "\"") {
      // Start or end of String within math mode
      mathModeString = !mathModeString
      if (mathModeString && mathModeStringCounter > 0) {
        typstTextBuilder.addMarkup(QUOTATION_MARK_WHITESPACE_REGEX, " ")
      } else {
        // First String of current math mode does not get a leading space
        typstTextBuilder.addMarkup(QUOTATION_MARK_WHITESPACE_REGEX)
      }
      mathModeStringCounter++
    } else if (mathModeString) {
      typstTextBuilder.addMarkup(WHITESPACE_QUOTATION_MARK_REGEX)
      typstTextBuilder.addMarkup(TypstAnnotatedTextBuilder.LEADING_WHITESPACE_REGEX, " ")
      // String within math mode to be spell checked
      typstTextBuilder.addText(typstTextBuilder.curString)
    } else {
      typstTextBuilder.addMarkup(typstTextBuilder.curString)
    }
  }

  fun processCodeMode() {
    if (!typstTextBuilder.codeMode.mode || typstTextBuilder.characterProcessed) return
    when (typstTextBuilder.curString) {
      typstTextBuilder.codeBlockDelimiter.openingBracket -> {
        processBracket(1)
      }

      typstTextBuilder.codeBlockDelimiter.closingBracket -> {
        processBracket(-1)
      }

      "\"" -> {
        processQuotationMark()
      }

      "[" -> {
        processOpeningSquareBracket()
      }

      "]" -> {
        processClosingSquareBracket()
      }
    }
    if (typstTextBuilder.codeMode.codeModeString) {
      typstTextBuilder.addMarkup(FILENAME_REGEX, typstTextBuilder.generateDummy())
      // String within code mode to be spell checked
      typstTextBuilder.addText(typstTextBuilder.curString)
    } else if (typstTextBuilder.codeMode.codeModeContentBlock &&
      !typstTextBuilder.codeMode.isContentBlockCode()
    ) {
      typstTextBuilder.addBasicMarkup()
      typstTextBuilder.addText(typstTextBuilder.curString)
    } else {
      typstTextBuilder.addMarkup(PROPERTY_REGEX)
      typstTextBuilder.addMarkup(typstTextBuilder.curString)
    }
  }

  private fun processOpeningSquareBracket() {
    if (!typstTextBuilder.codeMode.codeModeString) {
      if (typstTextBuilder.codeMode.stringCounter > 0 &&
        typstTextBuilder.codeMode.squareBracketscounter == 0
      ) {
        // No leading whitespace if 1st char is a dot
        typstTextBuilder.addMarkup(DOT_REGEX)
        addCurStringAsMarkupWithSpace()
      } else {
        // First content block/string of current code mode does not get a leading space
        typstTextBuilder.addMarkup(typstTextBuilder.curString)
      }
      typstTextBuilder.codeMode.stringCounter++
      typstTextBuilder.codeMode.squareBracketscounter++
      typstTextBuilder.codeMode.codeModeContentBlock = true
    }
  }

  private fun processClosingSquareBracket() {
    if (!typstTextBuilder.codeMode.codeModeString) {
      typstTextBuilder.addMarkup(typstTextBuilder.curString)
      typstTextBuilder.codeMode.squareBracketscounter--
      if (typstTextBuilder.codeMode.squareBracketscounter == 0) {
        typstTextBuilder.codeMode.codeModeContentBlock = false
      }
    }
  }

  private fun processBracket(counter: Int) {
    typstTextBuilder.codeMode.adjustBracketsCounter(counter)
    typstTextBuilder.addMarkup(typstTextBuilder.curString)
  }

  private fun processQuotationMark() {
    typstTextBuilder.codeMode.codeModeString = !typstTextBuilder.codeMode.codeModeString
    if (typstTextBuilder.codeMode.codeModeString &&
      typstTextBuilder.codeMode.stringCounter > 0
    ) {
      // No leading whitespace if 1st char is a dot
      typstTextBuilder.addMarkup(DOT_REGEX)
      addCurStringAsMarkupWithSpace()
    } else {
      // First content block/string of current code mode does not get a leading space
      typstTextBuilder.addMarkup(typstTextBuilder.curString)
    }
    typstTextBuilder.codeMode.stringCounter++
  }

  private fun addCurStringAsMarkupWithSpace() {
    if (!typstTextBuilder.characterProcessed) {
      typstTextBuilder.addMarkup(typstTextBuilder.curString, " ")
    }
  }

  /**
   * Markup that starts a code construct: a call, a code block, the argument of
   * `#label`, a chained method call, or a lambda arrow. Also used from inside a
   * content block of code mode, where the markup chain of
   * [TypstAnnotatedTextBuilder.processCharacter] is never reached.
   */
  fun processCodeStartMarkup() {
    typstTextBuilder.addMarkup(LABEL_FUNCTION_REGEX)
    typstTextBuilder.addMarkup(CODE_REGEX, "", false, true)
    typstTextBuilder.addMarkup(
      CODE_CURLY_BRACKETS_REGEX,
      "",
      false,
      true,
      CharacterBasedCodeAnnotatedTextBuilder.BracketType.CurlyBracket,
    )
    val curString = typstTextBuilder.curString
    val couldChain = curString == "." || curString == "=" || curString == " " || curString == "\t"
    if (typstTextBuilder.characterProcessed || !couldChain) return

    // Only a value a call can be chained onto may open code mode here, so that
    // prose like `2.5(...)` or `else => ...` stays prose.
    val previousChar = typstTextBuilder.previousNonWhitespaceCharacter()
    val endsValue =
      previousChar != null && (previousChar.isLetterOrDigit() || previousChar in ")]\"_")
    if (!endsValue) return

    if (curString == ".") {
      typstTextBuilder.addMarkup(METHOD_CALL_REGEX, "", false, true)
    } else {
      typstTextBuilder.addMarkup(LAMBDA_ARROW_REGEX)
    }
  }

  companion object {
    private val QUOTATION_MARK_WHITESPACE_REGEX = Regex("^\"\\s*")
    private val WHITESPACE_QUOTATION_MARK_REGEX = Regex("^\\s*(?=\")")

    // Never let the match run past the closing quotation mark: doing so used to
    // leave code mode's string state stuck on for the rest of the document
    // (e.g. `numbering("I.", n.pos())`), which made every following `#set` and
    // `#let` leak into the checked text.
    private val FILENAME_REGEX = Regex("^[^\"]+\\.\\w{1,4}")
    private val DOT_REGEX = Regex("^.(?=\\.)")
    private val PROPERTY_REGEX =
      Regex(
        "^(font|fit|style|weight|top-edge|bottom-edge|lang|region|script|number-type|number-width)\\s?:\\s?\".*?\"",
      )
    private val LABEL_FUNCTION_REGEX = Regex("^#label\\s*\\(\\s*\"[^\"]*\"\\s*\\)")
    private val CODE_REGEX = Regex("^#[^{}\\r\\n]*?\\(")

    // `#for`/`#while`/`#if` are left to FOR_WHILE_IF_REGEX, which drops only
    // their header and keeps the body spell-checked.
    private val CODE_CURLY_BRACKETS_REGEX = Regex("^#(?!(?:for|while|if)\\s)[^{}\\[\\]\\r\\n]*?\\{")
    private val METHOD_CALL_REGEX = Regex("^\\.\\w+\\s*\\(")
    private val LAMBDA_ARROW_REGEX = Regex("^[ \\t]*=>[^\\r\\n]*")
  }
}
