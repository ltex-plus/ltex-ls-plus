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
  protected var curString = ""
  protected var isStartOfLine = false
  protected var characterProcessed = false
  protected var codeMode = false
  protected var codeModeString = false
  protected var codeModeBracketsCounter = 0
  protected var codeModeStringCounter = 0
  protected var headingMode = false
  protected var headingSubsequentMarkup = false

  protected var dummyGenerator = DummyGenerator.getInstance()
  protected var dummyCounter = 0

  protected var language: String = "en-US"

  var isPreventingInfiniteLoops = false

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

  protected fun addMarkup(
    regex: Regex,
    interpretAs: String = "",
    generateDummy: Boolean = false,
    startofCodeBlock: Boolean = false,
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
        codeModeBracketsCounter++
        codeModeStringCounter = 0
        codeMode = true
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

  protected fun processHeading() {
    if (this.isStartOfLine && matchFromPosition(HEADING_REGEX) != null) {
      addMarkup(HEADING_REGEX)
      headingMode = true
    }
    if (headingMode) {
      if (matchFromPosition(HEADING_END_REGEX) != null) {
        if (this.curString == "?") {
          addMarkup(HEADING_END_REGEX, "?\n")
        } else if (curString == "!") {
          addMarkup(HEADING_END_REGEX, "!\n")
        } else {
          addMarkup(HEADING_END_REGEX, ".\n")
        }
        headingMode = false
        headingSubsequentMarkup = true
      }
    }
  }

  protected abstract fun processCharacter()

  protected fun matchFromPosition(
    regex: Regex,
    pos: Int = this.pos,
  ): MatchResult? {
    val matchResult: MatchResult? = regex.find(this.code.substring(pos))
    return if ((matchResult != null) && matchResult.value.isNotEmpty()) matchResult else null
  }

  protected open fun generateDummy(): String =
    this.dummyGenerator.generate(this.language, this.dummyCounter++)

  companion object {
    private val HEADING_REGEX = Regex("^=+\\s")
    private val HEADING_END_REGEX = Regex("^\\.?\\??\\!?\r?\n")
  }
}
