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
  private val abbreviationLabels = mutableSetOf<String>()

  private var lastAbbreviationPlaceholder = ""

  override fun processCharacter() {
    addMarkup(NO_TEXT_INLINE_MATH_REGEX, "", true)
    addMarkup(EMPTY_CONTENT_BLOCK)
    modeHandler.processMathBlock()
    processEscapeCharacter()
    modeHandler.processCodeMode()
    headingParser.processHeading()

    addMarkup(LET_STRING_REGEX)
    addMarkup(LET_CURLY_BRACKETS_REGEX, "", false, true, BracketType.CurlyBracket)
    addMarkup(LET_ROUND_BRACKETS_REGEX, "", false, true)
    addMarkup(LET_SINGLE_LINE_REGEX, "\n")
    addMarkup(RAW_CODE_REGEX_1, "", true)
    addMarkup(RAW_CODE_REGEX_2, "", true)
    addMarkup(RAW_CODE_REGEX_3, "", true)
    addMarkup(CITE_REGEX)
    addMarkup(ENUM_REGEX, "\n")
    processFootnote()
    registerAbbreviation()
    addMarkup(CODE_REGEX, "", false, true)
    addMarkup(CODE_CURLY_BRACKETS_REGEX, "", false, true, BracketType.CurlyBracket)
    addMarkup(SQUARE_BRACKETS_REGEX_MID, "\n")
    addMarkup(FOR_WHILE_IF_REGEX)
    addMarkup(ELSE_REGEX)
    addMarkup(BRACKETS_REGEX)

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
    if (this.isStartOfLine) {
      addMarkup(LIST_REGEX)
      addMarkup(LEADING_WHITESPACE_REGEX)
    }

    addMarkup(LINE_COMMENT_REGEX, "\n")
    addMarkup(MULTILINELINE_COMMENT_REGEX, "\n")
    addMarkup(MARKUP_REGEX)
    addMarkup(IMPORT_REGEX, "\n")
    addMarkup(SHOW_REGEX, "\n")
    addMarkup(STYLE_REGEX)
    processReference()
    addMarkup(LABEL_REGEX)
    addMarkup(VARIABLE_REGEX, "", true)
    addMarkup(QUOTATION_MARK_REGEX)
    addMarkup(CONDITIONAL_HYPHEN_REGEX)
  }

  private fun registerAbbreviation() {
    if (characterProcessed || this.curString != "#") return
    val match = matchFromPosition(ABBREVIATION_DEFINITION_REGEX) ?: return
    val label = match.groupValues[1]
    if (abbreviationLabels.add(label)) addDictionaryEntry(label)
  }

  private fun processFootnote() {
    if (characterProcessed || this.curString != "#") return
    if (this.pos > 0 && this.code[this.pos - 1] in HORIZONTAL_WHITESPACE) {
      addMarkup(FOOTNOTE_WITH_TRAILING_WHITESPACE_REGEX)
    }
    addMarkup(FOOTNOTE_REGEX)
  }

  private fun processReference() {
    if (characterProcessed || this.curString != "@") return
    val match = matchFromPosition(REFERENCE_REGEX) ?: return
    val reference = match.value.drop(1)
    val label = reference.substringBefore(':')
    var nextPos = this.pos + match.value.length
    while (nextPos < this.code.length && this.code[nextPos] in HORIZONTAL_WHITESPACE) nextPos++
    val citationContext =
      !reference.contains(':') &&
        (nextPos >= this.code.length || this.code[nextPos] in CITATION_FOLLOWING_CHARACTERS)
    val interpretAs =
      when {
        label in abbreviationLabels -> {
          abbreviationPlaceholder(
            label,
            reference.substringAfter(':', "") in PLURAL_ABBREVIATION_SPECIFIERS,
          )
        }

        citationContext -> {
          CITATION_PLACEHOLDER
        }

        else -> {
          generateDummy()
        }
      }
    addMarkup(match.value, interpretAs)
  }

  private fun abbreviationPlaceholder(
    label: String,
    plural: Boolean,
  ): String {
    val vowelSound = label.first().uppercaseChar() in VOWEL_SOUND_ABBREVIATION_INITIALS
    val candidates =
      when {
        vowelSound && plural -> VOWEL_SOUND_PLURAL_PLACEHOLDERS
        vowelSound -> VOWEL_SOUND_SINGULAR_PLACEHOLDERS
        plural -> CONSONANT_SOUND_PLURAL_PLACEHOLDERS
        else -> CONSONANT_SOUND_SINGULAR_PLACEHOLDERS
      }
    val placeholder = candidates.first { it != lastAbbreviationPlaceholder }
    lastAbbreviationPlaceholder = placeholder
    return if (isStartOfSentence()) {
      placeholder.replaceFirstChar { it.titlecaseChar() }
    } else {
      placeholder
    }
  }

  private fun isStartOfSentence(): Boolean {
    if (this.isStartOfLine) return true
    var previousPos = this.pos - 1
    while (previousPos >= 0 && this.code[previousPos].isWhitespace()) previousPos--
    return previousPos < 0 || this.code[previousPos] in SENTENCE_ENDINGS
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
    private val EMPTY_CONTENT_BLOCK = Regex("^\\[\\s*\\]")
    private val LIST_REGEX = Regex("^\\s*[+|\\-|\\/]\\s")
    val LEADING_WHITESPACE_REGEX = Regex("^\\s*")
    private val LET_STRING_REGEX = Regex("^#let\\s.*?=\\s*\"")
    private val LET_CURLY_BRACKETS_REGEX = Regex("^#let\\s[^\$]*?=[^\$\r\n]*\\{")
    private val LET_ROUND_BRACKETS_REGEX = Regex("^#let\\s[^\$]*?=[^\$\r\n]*\\(")
    private val LET_SINGLE_LINE_REGEX = Regex("^#let\\s.*?=.*\r?\n")
    private val RAW_CODE_REGEX_1 = Regex("^`{3,}[\\s\\S]*?`{3,}")
    private val RAW_CODE_REGEX_2 = Regex("^`[\\s\\S]*?`")
    private val RAW_CODE_REGEX_3 = Regex("^#raw\\([\\s\\S]*?[^\\\\]\"\\)")
    private val CITE_REGEX = Regex("^#cite\\(\\S+\\)")
    private val ENUM_REGEX = Regex("^#enum(\\([\\s\\S]*?\\)\\[|\\[)")
    private val FOOTNOTE_REGEX = Regex("^#footnote\\[[\\s\\S]*?\\]")
    private val FOOTNOTE_WITH_TRAILING_WHITESPACE_REGEX =
      Regex("^#footnote\\[[\\s\\S]*?\\][\\t ]+")
    private val CODE_REGEX = Regex("^#.*?\\(")
    private val CODE_CURLY_BRACKETS_REGEX = Regex("^#\\{")
    private val SQUARE_BRACKETS_REGEX_MID = Regex("^\\]\\[")
    private val FOR_WHILE_IF_REGEX = Regex("^(#for|#while|#if)\\s.*?(\\[|\\{)")
    private val ELSE_REGEX = Regex("^(\\]|\\})\\s*else.*?(\\[|\\{)")
    private val BRACKETS_REGEX = Regex("^(\\{|\\}|\\[|\\])")
    private val LINE_COMMENT_REGEX = Regex("^\\/\\/.*(\r?\n|$)")
    private val MULTILINELINE_COMMENT_REGEX = Regex("^\\/\\*[\\s\\S]*?\\*\\/")
    private val MARKUP_REGEX = Regex("^(\\*|\\_)")
    private val IMPORT_REGEX = Regex("^(#import|#include)\\s.*\r?\n")
    private val SHOW_REGEX = Regex("^#show(?::|\\s).*\r?\n")
    private val STYLE_REGEX =
      Regex("^#(highlight|lower|upper|overline|smallcaps|strike|sub|super|underline|strong)(?=\\[)")
    private val ABBREVIATION_DEFINITION_REGEX =
      Regex(
        "^#abbr\\.add\\(\\s*(?:short\\s*:\\s*)?\"([\\p{L}\\p{N}\\p{M}_-]+)\"" +
          "\\s*,\\s*(?:(?:long|entry)\\s*:\\s*)?\"([^\"]+)\"",
      )
    private val REFERENCE_REGEX =
      Regex("^@[\\p{L}\\p{N}\\p{M}_-](?:[\\p{L}\\p{N}\\p{M}_:.-]*[\\p{L}\\p{N}\\p{M}_-])?")
    private val LABEL_REGEX = Regex("^<[^\\s]*>")
    private val VARIABLE_REGEX = Regex("^#\\w*")
    private val QUOTATION_MARK_REGEX = Regex("^\"")
    private val CONDITIONAL_HYPHEN_REGEX = Regex("^-\\?")
    private val PLURAL_ABBREVIATION_SPECIFIERS = setOf("pls", "pll", "pllo", "pla")
    private const val CITATION_PLACEHOLDER = "(citation)"
    private val CITATION_FOLLOWING_CHARACTERS =
      charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '@')
    private val HORIZONTAL_WHITESPACE = charArrayOf(' ', '\t')
    private val VOWEL_SOUND_SINGULAR_PLACEHOLDERS = arrayOf("element", "object", "item")
    private val VOWEL_SOUND_PLURAL_PLACEHOLDERS = arrayOf("elements", "objects", "items")
    private val CONSONANT_SOUND_SINGULAR_PLACEHOLDERS = arrayOf("device", "system", "unit")
    private val CONSONANT_SOUND_PLURAL_PLACEHOLDERS = arrayOf("devices", "systems", "units")
    private val SENTENCE_ENDINGS = charArrayOf('.', '!', '?')
    private val VOWEL_SOUND_ABBREVIATION_INITIALS =
      setOf('A', 'E', 'F', 'H', 'I', 'L', 'M', 'N', 'O', 'R', 'S', 'X')
  }
}
