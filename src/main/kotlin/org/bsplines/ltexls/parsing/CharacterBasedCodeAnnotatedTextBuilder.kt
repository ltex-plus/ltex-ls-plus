/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging

abstract class CharacterBasedCodeAnnotatedTextBuilder(
  codeLanguageId: String,
) : CodeAnnotatedTextBuilder(codeLanguageId) {
  protected var code = ""
  protected var pos = 0
  protected var curChar = '\u0000'
  protected var characterProcessed = false
  protected var codeBlockDelimiter = BracketType.RoundBracket
  protected val codeMode = CodeModeHandler()

  protected var dummyGenerator = DummyGenerator.getInstance()
  protected var dummyCounter = 0

  protected var language: String = "en-US"

  var isPreventingInfiniteLoops = false

  var curString = ""
    protected set
  var isStartOfLine = false
    protected set

  override fun setSettings(settings: Settings) {
    super.setSettings(settings)
    this.language = settings.languageShortCode
  }

  override fun addText(text: String?): CharacterBasedCodeAnnotatedTextBuilder {
    if (text?.isNotEmpty() == true) {
      super.addText(text)
      this.pos += text.length
      characterProcessed = true
    }

    return this
  }

  override fun addMarkup(markup: String?): CharacterBasedCodeAnnotatedTextBuilder {
    if (markup?.isNotEmpty() == true) {
      super.addMarkup(markup)
      this.pos += markup.length
      characterProcessed = true
    }

    return this
  }

  override fun addMarkup(
    markup: String?,
    interpretAs: String?,
  ): CharacterBasedCodeAnnotatedTextBuilder {
    if (interpretAs?.isNotEmpty() == true) {
      super.addMarkup((markup ?: ""), interpretAs)
      this.pos += (markup?.length ?: 0)
      characterProcessed = true
    } else {
      addMarkup(markup)
    }

    return this
  }

  fun addMarkup(
    regex: Regex,
    interpretAs: String = "",
    generateDummy: Boolean = false,
    startofCodeBlock: Boolean = false,
    delimiter: BracketType = BracketType.RoundBracket,
  ) {
    if (characterProcessed) return
    var matchResult: MatchResult?
    var interpretAsString = interpretAs
    matchResult = matchFromPosition(regex)
    if (matchResult != null) {
      if (generateDummy) {
        interpretAsString += generateDummy()
      }
      addMarkup(matchResult.value, interpretAsString)
      if (startofCodeBlock) {
        codeBlockDelimiter = delimiter
        codeMode.adjustBracketsCounter(1)
        codeMode.stringCounter = 0
        codeMode.mode = true
      }
    }
  }

  override fun addCode(code: String): CharacterBasedCodeAnnotatedTextBuilder {
    this.pos = this.code.length
    this.code += code

    while (this.pos < this.code.length) {
      val lastPos: Int = this.pos
      this.curChar = this.code[this.pos]
      this.curString = this.curChar.toString()
      this.isStartOfLine = ((this.pos == 0) || this.code[this.pos - 1] == '\n')
      characterProcessed = false
      processCharacter()

      if (this.pos <= lastPos) {
        if (this.isPreventingInfiniteLoops) {
          throw IllegalStateException(
            I18n.format("characterBasedCodeAnnotatedTextBuilderInfiniteLoop"),
          )
        } else {
          Logging.LOGGER.warning(
            I18n.format("characterBasedCodeAnnotatedTextBuilderPreventedInfiniteLoop"),
          )
          this.pos++
        }
      }
    }

    return this
  }

  protected abstract fun processCharacter()

  fun matchFromPosition(
    regex: Regex,
    pos: Int = this.pos,
  ): MatchResult? {
    val matchResult: MatchResult? = regex.find(this.code.substring(pos))
    return if ((matchResult != null) && matchResult.value.isNotEmpty()) matchResult else null
  }

  protected open fun generateDummy(): String =
    this.dummyGenerator.generate(this.language, this.dummyCounter++)

  enum class BracketType(
    val openingBracket: String,
    val closingBracket: String,
  ) {
    RoundBracket("(", ")"),
    CurlyBracket("{", "}"),
  }
}
