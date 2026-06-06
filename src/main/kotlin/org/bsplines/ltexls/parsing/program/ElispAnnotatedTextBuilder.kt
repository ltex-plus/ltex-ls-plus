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
import org.bsplines.ltexls.settings.Settings
import org.languagetool.markup.AnnotatedText

/**
 * Annotated-text builder for Emacs Lisp.
 *
 * Beyond the `;` / `;;` line comments that [ProgramAnnotatedTextBuilder] already
 * extracts for the Lisp family, this builder also spell-/grammar-checks
 * **docstrings** — the string literal that appears at a fixed position inside a
 * definition form such as `defun`, `defcustom`, `defvar` or
 * `define-minor-mode`. Locating those strings requires a structural scan of the
 * source (a minimal s-expression reader, [ElispReader]) rather than a regex,
 * because the docstring position is form-specific and string boundaries depend
 * on escape and character-literal syntax.
 *
 * Comments are detected structurally by [ElispReader], not by the
 * [ProgramCommentRegexs] line-comment regex: a `;` inside a string or character
 * literal is recognised lexically and never reported as a comment. The contents
 * of comments and docstrings are interpreted as Markdown, with Emacs quote
 * rewriting and docstring-directive neutralisation enabled (see
 * [MarkdownAnnotatedTextBuilder]).
 */
class ElispAnnotatedTextBuilder(
  codeLanguageId: String,
) : CodeAnnotatedTextBuilder(codeLanguageId) {
  private val annotatedTextBuilder =
    MarkdownAnnotatedTextBuilder(
      "markdown",
      enableEmacsQuoteRewriting = true,
      enableElispDocstringDirectives = true,
    )

  // Like ProgramAnnotatedTextBuilder, this builder only wraps the inner Markdown
  // builder (addCode and build delegate to it), so settings must be forwarded
  // there — otherwise ltex.language (used to pick dummy tokens) and
  // ltex.markdownNodes never reach the prose in comments and docstrings.
  override fun setSettings(settings: Settings) {
    annotatedTextBuilder.setSettings(settings)
  }

  override fun addCode(code: String): CodeAnnotatedTextBuilder {
    val reader = ElispReader(code)
    reader.read()

    val segments: MutableList<Segment> = ArrayList()
    segments += buildCommentSegments(code, reader.commentSpans)

    for (docstring: ElispReader.DocstringSpan in reader.docstringSpans) {
      segments += DocstringSegment(docstring)
    }

    segments.sortBy { it.start }

    var curPos = 0

    for (segment: Segment in segments) {
      // Defensive: a malformed reader/regex result could in principle yield an
      // out-of-order or overlapping segment; skip it rather than slice badly.
      if (segment.start < curPos) continue

      // Always emit the preceding code as markup (even an empty gap), mirroring
      // ProgramAnnotatedTextBuilder so the leading paragraph break is identical.
      annotatedTextBuilder.addMarkup(code.substring(curPos, segment.start), "\n\n")

      when (segment) {
        is CommentSegment -> {
          addComment(code.substring(segment.start, segment.endExclusive), segment.isLineComment)
        }

        is DocstringSegment -> {
          addDocstring(code, segment.span)
        }
      }

      curPos = segment.endExclusive
    }

    if (curPos < code.length) annotatedTextBuilder.addMarkup(code.substring(curPos))
    return this
  }

  // Turns the reader's raw comment spans into checked segments. Consecutive
  // standalone (line-leading) comment lines are coalesced into a single block so
  // a sentence wrapping across `;;` lines is checked as one unit; trailing/inline
  // comments (code before the `;`) become individual single-line segments.
  // Non-checkable comments (`;text`, `;;;sections`, …) are dropped here and so
  // remain inert markup.
  private fun buildCommentSegments(
    code: String,
    comments: List<ElispReader.CommentSpan>,
  ): List<CommentSegment> {
    val result: MutableList<CommentSegment> = ArrayList()
    var blockStart = -1
    var blockEnd = -1

    fun flushBlock() {
      if (blockStart >= 0) {
        result += CommentSegment(blockStart, blockEnd, isLineComment = true)
        blockStart = -1
      }
    }

    for (comment: ElispReader.CommentSpan in comments) {
      when {
        !comment.checkable -> {
          flushBlock()
        }

        !comment.lineLeading -> {
          flushBlock()
          result += CommentSegment(comment.start, comment.end, isLineComment = true)
        }

        (blockStart >= 0) &&
          BETWEEN_COMMENT_LINES_REGEX.matches(code.substring(blockEnd, comment.start)) -> {
          blockEnd = comment.end
        }

        else -> {
          flushBlock()
          blockStart = comment.start
          blockEnd = comment.end
        }
      }
    }

    flushBlock()
    return result
  }

  private fun addDocstring(
    code: String,
    span: ElispReader.DocstringSpan,
  ) {
    // Opening delimiter (`"`).
    annotatedTextBuilder.addMarkup(code.substring(span.fullStart, span.contentStart))

    val content: String = code.substring(span.contentStart, span.contentEnd)
    // A leading `*` is the historical user-variable marker, not prose.
    val skipLength: Int = (if (content.startsWith("*")) 1 else 0)

    // Feed the whole docstring as one Markdown comment chunk. Directive
    // neutralisation happens length-preservingly inside the Markdown builder,
    // so source offsets stay intact without splitting the text into fragments.
    annotatedTextBuilder.addComment(
      arrayOf(content.substring(skipLength)),
      arrayOf(Triple(content.substring(0, skipLength), 0, skipLength)),
    )

    // Closing delimiter (`"`).
    annotatedTextBuilder.addMarkup(code.substring(span.contentEnd, span.fullEnd))
  }

  // --- Comment handling, kept byte-for-byte compatible with --------------
  // --- ProgramAnnotatedTextBuilder so existing elisp behaviour is unchanged.

  private fun addComment(
    comment: String,
    isLineComment: Boolean,
  ) {
    val commonFirstCharacter: String = getCommonFirstCharacterInComment(comment)
    val lineContentsRegex =
      Regex(
        "[ \t]*" +
          (if (isLineComment) LINE_COMMENT_MARKER else "") +
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

  private sealed class Segment {
    abstract val start: Int
    abstract val endExclusive: Int
  }

  private class CommentSegment(
    override val start: Int,
    override val endExclusive: Int,
    val isLineComment: Boolean,
  ) : Segment()

  private class DocstringSegment(
    val span: ElispReader.DocstringSpan,
  ) : Segment() {
    override val start: Int get() = span.fullStart
    override val endExclusive: Int get() = span.fullEnd
  }

  companion object {
    private val LINE_SEPARATOR_REGEX = Regex("\r?\n")
    private val FIRST_CHARACTER_REGEX = Regex("^[ \t]*(?:([#$%*+\\-/])|(.))")

    // Emacs Lisp comment markers are any run of semicolons (`;`, `;;`, `;;;`
    // section headings, `;;;;` file headers, …); all leading semicolons are
    // stripped before the prose is checked.
    private const val LINE_COMMENT_MARKER = ";+"

    // Matches the gap between two standalone comment lines that should coalesce:
    // exactly one line terminator followed by the next line's indentation.
    private val BETWEEN_COMMENT_LINES_REGEX = Regex("\r?\n[ \t]*")
  }
}

/**
 * Minimal s-expression reader that records, for an Emacs Lisp source string,
 * every `;` comment span and the string literals that are docstrings.
 *
 * It is intentionally a *scanner*, not a full reader: it tracks parenthesis
 * nesting, string/char-literal/comment lexical state, and the head symbol plus
 * element index of each list, which is everything needed to apply the
 * [DOCSTRING_FORMS] position table. It does not build an AST or evaluate
 * anything. Because comments are recognised lexically, a `;` inside a string or
 * character literal is never reported as a comment.
 */
internal class ElispReader(
  private val code: String,
) {
  val commentSpans: MutableList<CommentSpan> = ArrayList()
  val docstringSpans: MutableList<DocstringSpan> = ArrayList()

  /** A docstring string literal, with delimiter-inclusive and content ranges. */
  class DocstringSpan(
    val fullStart: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val fullEnd: Int,
  )

  /**
   * A `;`-introduced comment. [start] is the first `;`; [end] is the line
   * terminator (or end of input), excluding any `\r`/`\n`. [checkable] is true
   * when the comment's run of leading semicolons is followed by whitespace or
   * end-of-line (the convention for prose comments — `;`, `;;`, `;;;` headings
   * and `;;;;` file headers all qualify; `;text` with no space does not).
   * [lineLeading] is true when only
   * whitespace precedes the comment on its line (a standalone comment, as
   * opposed to a trailing/inline comment after code).
   */
  class CommentSpan(
    val start: Int,
    val end: Int,
    val checkable: Boolean,
    val lineLeading: Boolean,
  )

  private class Form {
    var head: String? = null
    var childCount: Int = 0
    val childStrings: MutableList<ChildString> = ArrayList()
  }

  private class ChildString(
    val elementIndex: Int,
    val fullStart: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val fullEnd: Int,
    val quoted: Boolean,
  )

  @Suppress("CyclomaticComplexMethod")
  fun read() {
    val n: Int = code.length
    val stack = ArrayDeque<Form>()
    var i = 0
    var pendingPrefix = false

    while (i < n) {
      val c: Char = code[i]

      when {
        c == ';' -> {
          var j: Int = i + 1
          while ((j < n) && (code[j] != '\n') && (code[j] != '\r')) j++
          recordComment(i, j)
          i = j
        }

        c == '"' -> {
          i = readString(i, stack, pendingPrefix)
          pendingPrefix = false
        }

        c == '?' -> {
          // Character literal, e.g. `?a`, `?\n`, `?\(`, `?\;`, `?\"`. Consume the
          // (optionally backslash-escaped) char so its content is never mistaken
          // for a string/comment delimiter, then run on through the rest of the
          // atom.
          registerElement(stack)
          pendingPrefix = false
          i++
          if ((i < n) && (code[i] == '\\')) i++
          if (i < n) i++
          while ((i < n) && isSymbolChar(code[i])) i++
        }

        (c == '(') || (c == '[') -> {
          registerElement(stack)
          pendingPrefix = false
          val form = Form()
          if (c == '[') form.head = VECTOR_SENTINEL
          stack.addLast(form)
          i++
        }

        (c == ')') || (c == ']') -> {
          val form: Form? = stack.removeLastOrNull()
          if (form != null) detectDocstring(form)
          pendingPrefix = false
          i++
        }

        (c == '\'') || (c == '`') || (c == ',') -> {
          pendingPrefix = true
          i++
          if ((c == ',') && (i < n) && (code[i] == '@')) i++
        }

        c == '#' -> {
          pendingPrefix = true
          i++
        }

        c.isWhitespace() -> {
          i++
        }

        else -> {
          val start: Int = i
          val top: Form? = stack.lastOrNull()
          val elementIndex: Int = registerElement(stack)
          pendingPrefix = false
          i++
          while ((i < n) && isSymbolChar(code[i])) i++
          if ((top != null) && (elementIndex == 0) && (top.head == null)) {
            top.head = code.substring(start, i)
          }
        }
      }
    }
  }

  private fun readString(
    quoteIndex: Int,
    stack: ArrayDeque<Form>,
    pendingPrefix: Boolean,
  ): Int {
    val n: Int = code.length
    val fullStart: Int = quoteIndex
    val contentStart: Int = quoteIndex + 1
    var j: Int = contentStart

    while (j < n) {
      val cj: Char = code[j]
      when {
        // A backslash escapes the next character (e.g. `\"`), so skip both.
        cj == '\\' -> j += 2

        cj == '"' -> break

        else -> j++
      }
    }

    val contentEnd: Int = if (j < n) j else n
    val fullEnd: Int = if (j < n) j + 1 else n

    val top: Form? = stack.lastOrNull()
    if (top != null) {
      val elementIndex: Int = top.childCount
      top.childCount++
      top.childStrings +=
        ChildString(elementIndex, fullStart, contentStart, contentEnd, fullEnd, pendingPrefix)
    }

    return fullEnd
  }

  /** Registers the start of a direct child datum of the current list, returning its index. */
  private fun registerElement(stack: ArrayDeque<Form>): Int {
    val top: Form = stack.lastOrNull() ?: return -1
    val index: Int = top.childCount
    top.childCount++
    return index
  }

  /**
   * Records the comment spanning [start] (the first `;`) up to [end] (the line
   * terminator or end of input), classifying it as checkable and line-leading.
   * Reached only from the reader's lexical comment branch, so a `;` inside a
   * string or character literal never gets here.
   */
  private fun recordComment(
    start: Int,
    end: Int,
  ) {
    var semicolons = 0
    while ((start + semicolons < end) && (code[start + semicolons] == ';')) semicolons++

    // Any run of semicolons is a valid comment marker (`;;;` headings, `;;;;`
    // file headers, …); it is prose only when followed by whitespace or EOL.
    val afterMarker: Int = start + semicolons
    val checkable: Boolean =
      (afterMarker >= end) || (code[afterMarker] == ' ') || (code[afterMarker] == '\t')

    var lineLeading = true
    var b: Int = start - 1
    while ((b >= 0) && (code[b] != '\n')) {
      if ((code[b] != ' ') && (code[b] != '\t') && (code[b] != '\r')) {
        lineLeading = false
        break
      }
      b--
    }

    commentSpans += CommentSpan(start, end, checkable, lineLeading)
  }

  private fun detectDocstring(form: Form) {
    val head: String = form.head ?: return
    val spec: DocSpec = DOCSTRING_FORMS[head] ?: return

    for (childString: ChildString in form.childStrings) {
      if (childString.quoted || (childString.elementIndex != spec.index)) continue

      // For function-like forms a string in the docstring slot is only a
      // docstring if a body follows it; otherwise it is the return value.
      if (spec.requireBody && (form.childCount <= spec.index + 1)) return

      docstringSpans +=
        DocstringSpan(
          childString.fullStart,
          childString.contentStart,
          childString.contentEnd,
          childString.fullEnd,
        )
      return
    }
  }

  private data class DocSpec(
    val index: Int,
    val requireBody: Boolean,
  )

  companion object {
    private const val VECTOR_SENTINEL: String = " vector"

    private fun isSymbolChar(c: Char): Boolean = !c.isWhitespace() && (c !in SYMBOL_DELIMITERS)

    private const val SYMBOL_DELIMITERS: String = "()[]\"';`,"

    // Definition forms whose docstring sits at a fixed element index (the head
    // symbol is element 0). `requireBody` marks function-like forms where a
    // trailing string is the return value rather than a docstring. Restricted to
    // forms whose docstring position is unambiguous from the head symbol alone.
    private val DOCSTRING_FORMS: Map<String, DocSpec> =
      mapOf(
        // (head name arglist DOC . body)
        "defun" to DocSpec(3, requireBody = true),
        "defmacro" to DocSpec(3, requireBody = true),
        "defsubst" to DocSpec(3, requireBody = true),
        "define-inline" to DocSpec(3, requireBody = true),
        "cl-defun" to DocSpec(3, requireBody = true),
        "cl-defmacro" to DocSpec(3, requireBody = true),
        "cl-defsubst" to DocSpec(3, requireBody = true),
        "ert-deftest" to DocSpec(3, requireBody = true),
        // (head name value DOC ...)
        "defvar" to DocSpec(3, requireBody = false),
        "defvar-local" to DocSpec(3, requireBody = false),
        "defconst" to DocSpec(3, requireBody = false),
        "defcustom" to DocSpec(3, requireBody = false),
        "defparameter" to DocSpec(3, requireBody = false),
        "defconstant" to DocSpec(3, requireBody = false),
        "defgroup" to DocSpec(3, requireBody = false),
        "defface" to DocSpec(3, requireBody = false),
        "defalias" to DocSpec(3, requireBody = false),
        // (define-minor-mode name DOC ...)
        "define-minor-mode" to DocSpec(2, requireBody = false),
        // (define-derived-mode child parent name DOC ...)
        "define-derived-mode" to DocSpec(4, requireBody = false),
      )
  }
}
