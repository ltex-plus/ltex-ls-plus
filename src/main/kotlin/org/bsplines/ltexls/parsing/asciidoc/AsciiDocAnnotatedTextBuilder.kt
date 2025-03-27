/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.asciidoc

import org.bsplines.ltexls.parsing.CharacterBasedCodeAnnotatedTextBuilder

class AsciiDocAnnotatedTextBuilder(
  codeLanguageId: String,
) : CharacterBasedCodeAnnotatedTextBuilder(codeLanguageId) {
  private var escapeModeBackslash = false
  private var escapeModePlus = false
  private var escapeModeIndent = false

  override fun processCharacter() {
    if (this.isStartOfLine) {
      addMarkup(CODE_BLOCK_PLUS_REGEX, "\n")
    }
    processEscapeCharacter()
    postProcessHeading()
    processCodeMode()
    processHeading()

    if (this.isStartOfLine) {
      addMarkup(LIST_REGEX)
      addMarkup(LEADING_WHITESPACE_REGEX)
      addMarkup(DOT_REGEX)
      addMarkup(CODE_BLOCK_REGEX, "\n")
      addMarkup(CODE_BLOCK_MINUS_REGEX, "\n")
      addMarkup(MULTILINELINE_COMMENT_REGEX, "\n")
      addMarkup(LINE_COMMENT_REGEX, "\n")
      addMarkup(CODE_SQUARE_BRACKETS_REGEX)
      addMarkup(TABLE_REGEX, "\n")
    }

    addMarkup(IMAGE_REGEX, "", false, true)
    addMarkup(LINK_REGEX, "", false, true)
    addMarkup(HTTP_REGEX, "", false, true)
    addMarkup(MAILTO_REGEX, "", false, true)
    addMarkup(MARKUP_REGEX)
    addMarkup(FORCED_LINEBREAK_REGEX, "\n")
    addMarkup(CURLY_BRACKETS_REGEX, "", true)

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

  private fun processEscapeCharacter() {
    if (characterProcessed) return
    if (this.curString != "+") {
      if (escapeModeBackslash || escapeModePlus || escapeModeIndent) {
        addText(this.curString)
      }
    }
    when (this.curString) {
      "\\" -> {
        addMarkup(this.curString)
        escapeModeBackslash = true
      }
      " " -> escapeModeBackslash = false
      "+" -> {
        addMarkup(this.curString)
        escapeModePlus = !escapeModePlus
      }
    }

    // Check for indent
    if (matchFromPosition(EMPTY_LINE_INDENT_REGEX) != null) {
      addMarkup(this.curString)
      escapeModeIndent = true
    } else if (matchFromPosition(EMPTY_LINE_NON_INDENT_REGEX) != null) {
      escapeModeIndent = false
    }
  }

  private fun postProcessHeading() {
    // Text after heading is markup
    if (headingSubsequentMarkup) {
      if (matchFromPosition(EMPTY_LINE_REGEX) == null) {
        addMarkup(SUBSEQUENT_TEXT_REGEX)
      }
      headingSubsequentMarkup = false
    }
  }

  private fun processCodeMode() {
    if (!codeMode || characterProcessed) return
    if (this.curString == "]") {
      addMarkup(this.curString)
      codeMode = false
    } else {
      addText(this.curString)
    }
  }

  companion object {
    private val CODE_BLOCK_PLUS_REGEX = Regex("^\\+{3,}(.|\r?\n)*?\\+{3,}")
    private val LIST_REGEX = Regex("^\\s*?(\\.+|\\-|\\*+)\\s+")
    private val LEADING_WHITESPACE_REGEX = Regex("^\\s")
    private val DOT_REGEX = Regex("^\\.")
    private val CODE_BLOCK_REGEX = Regex("^\\[source.*?\\]\\s*?\r?\n[^\\-](.|\r?\n)*?\r?\n\r?\n")
    private val CODE_BLOCK_MINUS_REGEX = Regex("^-{3,}(.|\r?\n)*?-{3,}")
    private val MULTILINELINE_COMMENT_REGEX = Regex("^\\/{4,}(.|\r?\n)*?\\/{4,}")
    private val LINE_COMMENT_REGEX = Regex("^\\/\\/.*?\r?\n")
    private val CODE_SQUARE_BRACKETS_REGEX = Regex("^\\[.*?\\]")
    private val TABLE_REGEX = Regex("^\\|={3,}(.|\r?\n)*?\\|={3,}")
    private val IMAGE_REGEX = Regex("^image:.*?\\[")
    private val LINK_REGEX = Regex("^link:.*?\\[")
    private val HTTP_REGEX = Regex("^http.*?\\[")
    private val MAILTO_REGEX = Regex("^mailto:.*?\\[")
    private val MARKUP_REGEX = Regex("^(\\*|\\_|\\^|~|:{2,}|;;)")
    private val FORCED_LINEBREAK_REGEX = Regex("^\\s\\+\\s*?\r?\n")
    private val CURLY_BRACKETS_REGEX = Regex("^\\{.*?\\}")
    private val EMPTY_LINE_INDENT_REGEX = Regex("^\r?\n\\s*?\r?\n(?=\\s)")
    private val EMPTY_LINE_NON_INDENT_REGEX = Regex("^\r?\n\\s*?\r?\n(?=\\S)")
    private val EMPTY_LINE_REGEX = Regex("^\\s*?\r?\n")
    private val SUBSEQUENT_TEXT_REGEX = Regex("^(.|\r?\n)*?\r?\n\r?\n")
  }
}
