/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.program

import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.parsing.RegexCodeFragmentizer
import org.bsplines.ltexls.settings.Settings

/**
 * Fragmentizer for Emacs Lisp. It locates `; ltex: …` / `;; ltex: …` magic
 * comments **structurally** via [ElispReader] rather than with a line-comment
 * regex, so a `; ltex:` sequence inside a string or character literal is never
 * mistaken for a settings directive. This keeps Emacs Lisp self-standing: it no
 * longer relies on the shared Lisp-family entry in [ProgramCommentRegexs].
 *
 * The fragment/settings bookkeeping ([RegexCodeFragmentizer.buildFragments]) and
 * the source-code comment defaults ([ProgramFragmentizer.augmentFragments]) are
 * reused, so toggling behaviour and the programmer-jargon defaults are identical
 * to the regex path for ordinary standalone comments.
 */
class ElispFragmentizer(
  codeLanguageId: String,
) : CodeFragmentizer(codeLanguageId) {
  override fun fragmentize(
    code: String,
    originalSettings: Settings,
  ): List<CodeFragment> =
    ProgramFragmentizer.augmentFragments(
      RegexCodeFragmentizer.buildFragments(
        codeLanguageId,
        code,
        originalSettings,
        findMagicComments(code),
      ),
    )

  private fun findMagicComments(code: String): List<RegexCodeFragmentizer.MagicComment> {
    val reader = ElispReader(code)
    reader.read()
    return reader.commentSpans.mapNotNull { comment -> toMagicComment(code, comment) }
  }

  private fun toMagicComment(
    code: String,
    comment: ElispReader.CommentSpan,
  ): RegexCodeFragmentizer.MagicComment? {
    // Only standalone comments can carry settings, mirroring the `^[ \t]*` anchor
    // of the former regex; a trailing/inline `; ltex:` after code is not a
    // directive.
    if (!comment.lineLeading) return null

    val settingsLine: String = parseMagicComment(code, comment) ?: return null

    // Extend the excised span back to the start of the line so the leading
    // indentation is consumed together with the directive, exactly as the
    // `^[ \t]*` regex did. `lineLeading` guarantees only blanks precede it.
    var lineStart: Int = comment.start
    while ((lineStart > 0) && (code[lineStart - 1] != '\n') && (code[lineStart - 1] != '\r')) {
      lineStart--
    }

    return RegexCodeFragmentizer.MagicComment(lineStart, comment.end, settingsLine)
  }

  // Mirrors the former magic-comment regex `^[ \t]*;;?[ \t]*(?i)ltex(?-i):(.*?)[ \t]*$`
  // applied to a single comment span: one or two leading semicolons, optional
  // blanks, then a case-insensitive `ltex:`. Returns the directive text (with
  // trailing blanks stripped, leading blanks kept as the regex did) or null when
  // the comment is not a magic comment.
  private fun parseMagicComment(
    code: String,
    comment: ElispReader.CommentSpan,
  ): String? {
    val end: Int = comment.end
    var i: Int = comment.start

    var semicolons = 0
    while ((i < end) && (code[i] == ';')) {
      semicolons++
      i++
    }
    if (semicolons !in 1..2) return null

    while ((i < end) && ((code[i] == ' ') || (code[i] == '\t'))) i++

    if (!code.regionMatches(i, MAGIC_KEYWORD, 0, MAGIC_KEYWORD.length, ignoreCase = true)) {
      return null
    }
    i += MAGIC_KEYWORD.length

    var contentEnd: Int = end
    while ((contentEnd > i) && ((code[contentEnd - 1] == ' ') || (code[contentEnd - 1] == '\t'))) {
      contentEnd--
    }

    return code.substring(i, contentEnd)
  }

  companion object {
    private const val MAGIC_KEYWORD = "ltex:"
  }
}
