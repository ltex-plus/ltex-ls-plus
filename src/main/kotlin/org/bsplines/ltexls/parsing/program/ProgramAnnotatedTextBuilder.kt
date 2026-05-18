/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.program

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.markdown.MarkdownAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.restructuredtext.RestructuredtextAnnotatedTextBuilder
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.languagetool.markup.AnnotatedText

class ProgramAnnotatedTextBuilder(
  codeLanguageId: String,
) : CodeAnnotatedTextBuilder(codeLanguageId) {
  private val annotatedTextBuilder =
    when (codeLanguageId) {
      "python" -> {
        RestructuredtextAnnotatedTextBuilder("restructuredtext")
      }

      else -> {
        MarkdownAnnotatedTextBuilder(
          "markdown",
          // Only the Emacs / Emacs-Lisp branch documents `name' as a canonical
          // quoted-identifier marker. Common Lisp and Clojure do not use this
          // convention (Common Lisp varies; Clojure uses Markdown-style
          // matched backticks), so they keep the default of false to avoid
          // surprising users whose source legitimately contains the same
          // character sequence with a different meaning.
          enableEmacsQuoteRewriting = (codeLanguageId in EMACS_QUOTE_LANGUAGES),
        )
      }
    }

  private val commentRegexs = ProgramCommentRegexs.fromCodeLanguageId(codeLanguageId)
  private val commentBlockRegex: Regex = commentRegexs.commentBlockRegex
  private val lineCommentPatternString: String? = commentRegexs.lineCommentRegexString

  override fun addCode(code: String): CodeAnnotatedTextBuilder {
    var curPos = 0

    for (matchResult: MatchResult in commentBlockRegex.findAll(code)) {
      val lastPos: Int = curPos
      val isLineComment: Boolean = (matchResult.groups["lineComment"] != null)
      val commentGroupName: String = (if (isLineComment) "lineComment" else "blockComment")
      val commentGroup: MatchGroup? = matchResult.groups[commentGroupName]

      if (commentGroup == null) {
        Logging.LOGGER.warning(
          I18n.format("couldNotFindExpectedGroupInRegularExpressionMatch", commentGroupName),
        )
        continue
      }

      curPos = commentGroup.range.first
      annotatedTextBuilder.addMarkup(code.substring(lastPos, curPos), "\n\n")

      val comment: String = commentGroup.value
      addComment(comment, isLineComment)
      curPos = commentGroup.range.last + 1
    }

    if (curPos < code.length) annotatedTextBuilder.addMarkup(code.substring(curPos))
    return this
  }

  private fun addComment(
    comment: String,
    isLineComment: Boolean,
  ): CodeAnnotatedTextBuilder {
    val commonFirstCharacter: String = getCommonFirstCharacterInComment(comment)
    val lineContentsRegex =
      Regex(
        "[ \t]*" +
          (
            if (isLineComment &&
              (lineCommentPatternString != null)
            ) {
              lineCommentPatternString
            } else {
              ""
            }
          ) +
          "(?:" + Regex.escape(commonFirstCharacter) + ")?[ \t]*(.*?)(?:\r?\n|$)",
      )
    var curPos = 0

    var code = arrayOf<String>()
    var markups = arrayOf<Triple<String, Int, Int>>()

    for (matchResult: MatchResult in lineContentsRegex.findAll(comment)) {
      val matchGroup: MatchGroup = matchResult.groups[1] ?: continue

      markups +=
        Triple(
          comment.substring(curPos, matchGroup.range.first),
          curPos,
          matchGroup.range.first,
        )

      curPos = matchGroup.range.last + 1

      code += comment.substring(matchGroup.range.first, curPos)
    }

    annotatedTextBuilder.addComment(code, markups)

    if (curPos < comment.length) annotatedTextBuilder.addMarkup(comment.substring(curPos))

    return this
  }

  private fun getCommonFirstCharacterInComment(comment: String): String {
    var commonFirstCharacter = ""

    for (line: String in comment.split(LINE_SEPARATOR_REGEX)) {
      val firstCharacterMatchResult: MatchResult = FIRST_CHARACTER_REGEX.find(line) ?: continue

      if (firstCharacterMatchResult.groups[1] == null) {
        return ""
      }

      val firstCharacter: String = firstCharacterMatchResult.groupValues[1]

      if (commonFirstCharacter.isEmpty()) {
        commonFirstCharacter = firstCharacter
      } else if (firstCharacter != commonFirstCharacter) {
        return ""
      }
    }

    return commonFirstCharacter
  }

  override fun build(): AnnotatedText = annotatedTextBuilder.build()

  companion object {
    private val LINE_SEPARATOR_REGEX = Regex("\r?\n")
    private val FIRST_CHARACTER_REGEX = Regex("^[ \t]*(?:([#$%*+\\-/])|(.))")

    // Languages whose comment / docstring convention uses ``name'` (backtick
    // opener, straight-apostrophe closer) for quoted identifiers, inherited
    // from Texinfo via Emacs. Restricted to elisp / emacs-lisp because
    // Common Lisp has no standardised convention and Clojure uses
    // Markdown-style matched backticks. Extend cautiously: any language
    // added here will see literal `name'` sequences in its comments
    // silently reinterpreted as inline code.
    private val EMACS_QUOTE_LANGUAGES: Set<String> = setOf("elisp", "emacs-lisp")
  }
}
