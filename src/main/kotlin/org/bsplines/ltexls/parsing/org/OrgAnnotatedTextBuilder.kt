/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.org

import org.bsplines.ltexls.parsing.CharacterBasedCodeAnnotatedTextBuilder

@Suppress("TooManyFunctions")
class OrgAnnotatedTextBuilder(
  codeLanguageId: String,
) : CharacterBasedCodeAnnotatedTextBuilder(codeLanguageId) {
  private var indentation = -1
  private var appendAtEndOfLine = ""
  private var itemBodyIndent = -1
  private val elementTypeStack: ArrayDeque<ElementType> = ArrayDeque(listOf(ElementType.Paragraph))

  // Start offsets of the closing lines of the open blocks, drawers, dynamic blocks and LaTeX
  // environments, innermost last. Each entry belongs to one of these element types on
  // elementTypeStack.
  private val closingLineStartStack = ArrayDeque<Int>()
  private val objectTypeStack = ArrayDeque<ObjectType>()

  override fun processCharacter() {
    if (this.isStartOfLine) {
      processWhitespaceAtStartOfLine()
      if (this.pos >= this.code.length) return
    }

    if (this.objectTypeStack.contains(ObjectType.Verbatim)) {
      when (
        val matchResult: MatchResult? = matchInlineEndFromPosition(TEXT_MARKUP_VERBATIM_END_REGEX)
      ) {
        null -> {
          addMarkup(this.curString)
        }

        else -> {
          popObjectType()
          addMarkup(matchResult.value, generateDummy())
        }
      }
    } else if (this.objectTypeStack.contains(ObjectType.Code)) {
      when (
        val matchResult: MatchResult? = matchInlineEndFromPosition(TEXT_MARKUP_CODE_END_REGEX)
      ) {
        null -> {
          addMarkup(this.curString)
        }

        else -> {
          popObjectType()
          addMarkup(matchResult.value, generateDummy())
        }
      }
    } else if (this.isStartOfLine && processStartOfLine()) {
      // skip
    } else if (isInIgnoredElementType()) {
      addMarkup(this.curString)
    } else {
      processCharacterInternal()
    }
  }

  private fun processWhitespaceAtStartOfLine() {
    val whitespace: String = matchFromPosition(WHITESPACE_REGEX)?.value ?: ""
    this.indentation = whitespace.length
    addMarkup(whitespace)

    if (this.pos < this.code.length) {
      this.curChar = this.code[this.pos]
      this.curString = this.curChar.toString()
    }
  }

  @Suppress("ComplexMethod", "LongMethod")
  private fun processStartOfLine(): Boolean {
    var matchResult: MatchResult? = null
    var elementFound = true

    if (
      this.elementTypeStack.contains(ElementType.Table) &&
      (matchFromPosition(TABLE_ROW_REGEX) == null)
    ) {
      popElementType()
    }

    if (this.pos - this.indentation == this.closingLineStartStack.lastOrNull()) {
      closeContainerElement()
      addMarkup(this.code.substring(this.pos, findLineEnd(this.pos)))
    } else if (isInIgnoredElementType()) {
      addMarkup(this.curString)
    } else if (
      (this.indentation == 0) &&
      (matchFromPosition(HEADLINE_COMMENT_REGEX)?.also { matchResult = it } != null)
    ) {
      addMarkup(matchResult?.value, "\n")
    } else if (
      (this.indentation == 0) &&
      (matchFromPosition(HEADLINE_REGEX)?.also { matchResult = it } != null)
    ) {
      this.elementTypeStack.addLast(ElementType.Headline)
      this.appendAtEndOfLine = "\n"
      addMarkup(matchResult?.value, "\n")
    } else if (matchFromPosition(CAPTION_PREFIX_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (
      matchFromPosition(PROSE_KEYWORD_PREFIX_REGEX)?.also { matchResult = it } != null
    ) {
      // Like a headline: the value is prose, but it stands in its own paragraph.
      this.appendAtEndOfLine = "\n"
      addMarkup(matchResult?.value, "\n")
    } else if (
      matchFromPosition(AFFILIATED_KEYWORDS_REGEX)?.also { matchResult = it } != null
    ) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(BLOCK_BEGIN_REGEX)?.also { matchResult = it } != null) {
      val blockType: String? = matchResult?.groups?.get(1)?.value

      val elementType: ElementType =
        when {
          blockType == null -> ElementType.GreaterSpecialBlock
          blockType.equals("CENTER", ignoreCase = true) -> ElementType.GreaterCenterBlock
          blockType.equals("QUOTE", ignoreCase = true) -> ElementType.GreaterQuoteBlock
          blockType.equals("COMMENT", ignoreCase = true) -> ElementType.CommentBlock
          blockType.equals("EXAMPLE", ignoreCase = true) -> ElementType.ExampleBlock
          blockType.equals("EXPORT", ignoreCase = true) -> ElementType.ExportBlock
          blockType.equals("SRC", ignoreCase = true) -> ElementType.SourceBlock
          blockType.equals("VERSE", ignoreCase = true) -> ElementType.VerseBlock
          else -> ElementType.GreaterSpecialBlock
        }

      val closingRegex =
        Regex(
          "^[ \t]*#\\+END_" + Regex.escape(blockType ?: "") + "[ \t]*$",
          setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
      openContainerElement(elementType, matchResult, closingRegex)
    } else if (
      (matchFromPosition(BLOCK_END_REGEX)?.also { matchResult = it } != null) ||
      (matchFromPosition(DRAWER_END_REGEX)?.also { matchResult = it } != null) ||
      (matchFromPosition(DYNAMIC_BLOCK_END_REGEX)?.also { matchResult = it } != null)
    ) {
      // Closing line without a matching opening line.
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(DRAWER_BEGIN_REGEX)?.also { matchResult = it } != null) {
      val drawerName: String? = matchResult?.groups?.get(1)?.value

      val elementType: ElementType =
        if ((drawerName != null) && drawerName.equals("PROPERTIES", ignoreCase = true)) {
          ElementType.PropertyDrawer
        } else {
          ElementType.Drawer
        }

      openContainerElement(elementType, matchResult, DRAWER_CLOSING_REGEX)
    } else if (
      matchFromPosition(DYNAMIC_BLOCK_BEGIN_REGEX)?.also { matchResult = it } != null
    ) {
      openContainerElement(ElementType.DynamicBlock, matchResult, DYNAMIC_BLOCK_CLOSING_REGEX)
    } else if (
      matchFromPosition(FOOTNOTE_DEFINITION_REGEX)?.also { matchResult = it } != null
    ) {
      addMarkup(matchResult?.value)
    } else if (
      (matchFromPosition(RULE_TABLE_ROW_REGEX)?.also { matchResult = it } != null) ||
      (matchFromPosition(TABLE_ROW_REGEX)?.also { matchResult = it } != null)
    ) {
      if (!this.elementTypeStack.contains(ElementType.Table)) {
        this.elementTypeStack.addLast(ElementType.Table)
      }

      this.appendAtEndOfLine = "\n"
      addMarkup(matchResult?.value, "\n")
    } else if (matchFromPosition(ITEM_REGEX)?.also { matchResult = it } != null) {
      val bulletGroup: MatchGroup? = matchResult?.groups?.get(1)
      val bulletLength: Int = bulletGroup?.value?.length ?: 1
      this.itemBodyIndent = this.indentation + bulletLength + 1
      this.appendAtEndOfLine = "\n"
      addItemPrefixMarkup(matchResult)
    } else if (matchFromPosition(BABEL_CALL_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(CLOCK_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(DIARY_SEXP_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(PLANNING_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(COMMENT_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(FIXED_WIDTH_LINE_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(HORIZONTAL_RULE_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(KEYWORD_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (
      matchFromPosition(LATEX_ENVIRONMENT_BEGIN_REGEX)?.also { matchResult = it } != null
    ) {
      val closingRegex =
        Regex(
          "\\\\end\\{" + Regex.escape(matchResult?.groupValues?.get(1) ?: "") + "\\}[ \t]*$",
          setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
      openContainerElement(ElementType.LatexEnvironment, matchResult, closingRegex)
    } else {
      elementFound = false
    }

    return elementFound
  }

  // Org treats an opening line as a block, drawer or environment only if its closing line
  // follows before the next headline and inside the enclosing element. Otherwise the opening
  // line is an ordinary line, and the lines after it are parsed as usual.
  private fun openContainerElement(
    elementType: ElementType,
    openingMatchResult: MatchResult?,
    closingRegex: Regex,
  ) {
    val openingLine: String = openingMatchResult?.value ?: ""
    val lineStart: Int = this.pos - this.indentation
    val closingLineStart: Int? = findClosingLineStart(closingRegex, this.pos + openingLine.length)

    if (closingLineStart == null) {
      addMarkup(openingLine)
    } else if (closingLineStart == lineStart) {
      // A LaTeX environment that closes on its opening line.
      addMarkup(this.code.substring(this.pos, findLineEnd(this.pos)))
    } else {
      this.elementTypeStack.addLast(elementType)
      this.closingLineStartStack.addLast(closingLineStart)
      addMarkup(openingLine)
    }
  }

  private fun findClosingLineStart(
    closingRegex: Regex,
    searchStart: Int,
  ): Int? {
    var limit: Int = this.closingLineStartStack.lastOrNull() ?: this.code.length
    val headlineMatcher = HEADLINE_START_REGEX.toPattern().matcher(this.code)
    if (headlineMatcher.find(searchStart) && (headlineMatcher.start() < limit)) {
      limit = headlineMatcher.start()
    }

    if (searchStart > limit) return null
    val closingMatcher =
      closingRegex
        .toPattern()
        .matcher(this.code)
        .region(searchStart, limit)
        .useAnchoringBounds(false)
        .useTransparentBounds(true)
    if (!closingMatcher.find()) return null

    return this.code.lastIndexOf('\n', closingMatcher.start() - 1) + 1
  }

  private fun closeContainerElement() {
    while (
      (this.elementTypeStack.lastOrNull() == ElementType.Headline) ||
      (this.elementTypeStack.lastOrNull() == ElementType.Table)
    ) {
      popElementType()
    }

    popElementType()
    this.closingLineStartStack.removeLastOrNull()
  }

  private fun findLineEnd(start: Int): Int {
    var end: Int = start
    while ((end < this.code.length) && (this.code[end] != '\r') && (this.code[end] != '\n')) end++
    return end
  }

  @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
  private fun processCharacterInternal() {
    var matchResult: MatchResult? = null

    if (
      this.elementTypeStack.contains(ElementType.Headline) &&
      (matchFromPosition(HEADLINE_TAGS_REGEX)?.also { matchResult = it } != null)
    ) {
      addMarkup(matchResult?.value)
    } else if (
      this.elementTypeStack.contains(ElementType.Table) &&
      (matchFromPosition(TABLE_CELL_SEPARATOR_REGEX)?.also { matchResult = it } != null)
    ) {
      addMarkup(matchResult?.value, "\n\n")
    } else if (
      this.objectTypeStack.contains(ObjectType.RegularLinkDescription) &&
      (
        matchFromPosition(REGULAR_LINK_DESCRIPTION_END_REGEX)?.also { matchResult = it }
          != null
      )
    ) {
      popObjectType()
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(LATEX_FRAGMENT_REGEX1)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(LATEX_FRAGMENT_REGEX2)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(LATEX_FRAGMENT_REGEX3)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(LATEX_FRAGMENT_REGEX4)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      (this.isStartOfLine || (this.code[this.pos - 1] != '$')) &&
      (matchFromPosition(LATEX_FRAGMENT_REGEX5)?.also { matchResult = it } != null)
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      (this.isStartOfLine || (this.code[this.pos - 1] != '$')) &&
      (matchFromPosition(LATEX_FRAGMENT_REGEX6)?.also { matchResult = it } != null)
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(EXPORT_SNIPPET_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(FOOTNOTE_REFERENCE_REGEX1)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(FOOTNOTE_REFERENCE_REGEX2)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value)
    } else if (matchFromPosition(INLINE_BABEL_CALL_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(INLINE_SOURCE_BLOCK_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(MACRO_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(STATISTICS_COOKIE_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(DIARY_TIMESTAMP_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      matchFromPosition(ACTIVE_TIMESTAMP_RANGE_REGEX1)?.also { matchResult = it } != null
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      matchFromPosition(ACTIVE_TIMESTAMP_RANGE_REGEX2)?.also { matchResult = it } != null
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      matchFromPosition(INACTIVE_TIMESTAMP_RANGE_REGEX1)?.also { matchResult = it } != null
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      matchFromPosition(INACTIVE_TIMESTAMP_RANGE_REGEX2)?.also { matchResult = it } != null
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(ACTIVE_TIMESTAMP_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(INACTIVE_TIMESTAMP_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      (matchFromPosition(RADIO_TARGET_REGEX)?.also { matchResult = it } != null) &&
      (
        this.isStartOfLine ||
          (LINK_PRECEDING_REGEX.find(this.code[this.pos - 1].toString()) != null)
      )
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      (matchFromPosition(TARGET_REGEX)?.also { matchResult = it } != null) &&
      (
        this.isStartOfLine ||
          (LINK_PRECEDING_REGEX.find(this.code[this.pos - 1].toString()) != null)
      )
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (matchFromPosition(ANGLE_LINK_REGEX)?.also { matchResult = it } != null) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      (matchFromPosition(PLAIN_LINK_REGEX)?.also { matchResult = it } != null) &&
      (
        this.isStartOfLine ||
          (LINK_PRECEDING_REGEX.find(this.code[this.pos - 1].toString()) != null)
      )
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      matchFromPosition(REGULAR_LINK_WITHOUT_DESCRIPTION_REGEX)?.also { matchResult = it }
      != null
    ) {
      addMarkup(matchResult?.value, generateDummy())
    } else if (
      matchFromPosition(REGULAR_LINK_WITH_DESCRIPTION_REGEX)?.also { matchResult = it } != null
    ) {
      this.objectTypeStack.add(ObjectType.RegularLinkDescription)
      addMarkup(matchResult?.value)
    } else if (
      (matchInlineStartFromPosition(TEXT_MARKUP_MARKER_REGEX)?.also { matchResult = it } != null) ||
      (matchInlineEndFromPosition(TEXT_MARKUP_MARKER_REGEX)?.also { matchResult = it } != null)
    ) {
      val textMarkupMarker: String? = matchResult?.value
      val objectType: ObjectType? =
        when (textMarkupMarker) {
          "*" -> ObjectType.Bold
          "+" -> ObjectType.Strikethrough
          "/" -> ObjectType.Italic
          "=" -> ObjectType.Verbatim
          "_" -> ObjectType.Underline
          "~" -> ObjectType.Code
          else -> null
        }

      if (objectType != null) {
        toggleObjectType(objectType)
        addMarkup(textMarkupMarker)
      } else {
        addText(textMarkupMarker)
      }
    } else if (this.curChar == '\n') {
      if (this.itemBodyIndent >= 0) {
        if (isNextLineItemContinuation()) {
          // Wrapped continuation of the current list item: don't insert a
          // paragraph break, otherwise LanguageTool sees the continuation as a
          // fresh sentence and flags it (e.g., for not starting with uppercase).
          this.appendAtEndOfLine = ""
        } else {
          this.itemBodyIndent = -1
          if (this.appendAtEndOfLine.isEmpty()) this.appendAtEndOfLine = "\n"
        }
      }
      addMarkup("\n", "\n" + this.appendAtEndOfLine)
      this.appendAtEndOfLine = ""
      if (this.elementTypeStack.contains(ElementType.Headline)) popElementType()
    } else {
      addText(this.curString)
    }
  }

  @Suppress("ComplexCondition")
  private fun matchInlineStartFromPosition(
    @Suppress("SameParameterValue") regex: Regex,
  ): MatchResult? {
    if (
      (this.pos > 0) &&
      (matchFromPosition(TEXT_MARKUP_START_PRECEDING_REGEX, this.pos - 1) == null)
    ) {
      return null
    }

    val matchResult: MatchResult? = matchFromPosition(regex)

    return if (
      (matchResult == null) ||
      (this.pos == 0) ||
      (this.pos >= this.code.length - 1) ||
      (
        matchFromPosition(TEXT_MARKUP_START_FOLLOWING_REGEX, this.pos + matchResult.value.length)
          != null
      )
    ) {
      matchResult
    } else {
      null
    }
  }

  private fun matchInlineEndFromPosition(regex: Regex): MatchResult? {
    if (
      (this.pos == 0) ||
      (matchFromPosition(TEXT_MARKUP_END_PRECEDING_REGEX, this.pos - 1) == null)
    ) {
      return null
    }

    val matchResult: MatchResult? = matchFromPosition(regex)

    return if (
      (matchResult == null) ||
      (
        matchFromPosition(TEXT_MARKUP_END_FOLLOWING_REGEX, this.pos + matchResult.value.length)
          != null
      )
    ) {
      matchResult
    } else {
      null
    }
  }

  private fun addItemPrefixMarkup(matchResult: MatchResult?) {
    val matchValue: String = matchResult?.value ?: ""
    val descriptionGroup: MatchGroup? = matchResult?.groups?.get(ITEM_DESCRIPTION_TERM_GROUP)

    if (descriptionGroup != null) {
      // Description list item: "- Term :: body". Emit the term as plain text
      // so LanguageTool can spell-check it; keep the bullet, " ::" and
      // surrounding whitespace as markup. A single "\n" replacement on the
      // separator keeps term and body in the same LT paragraph, so the body's
      // first letter isn't flagged for not being uppercase.
      val termMatch: MatchResult? = DESCRIPTION_TERM_REGEX.find(descriptionGroup.value)
      val term: String? = termMatch?.groupValues?.get(2)

      if (!term.isNullOrEmpty()) {
        val leadingWsLen: Int = termMatch.groupValues[1].length
        val termStart: Int = descriptionGroup.range.first + leadingWsLen
        val termEnd: Int = termStart + term.length
        addMarkup(matchValue.substring(0, termStart), "\n")
        addText(term)
        addMarkup(matchValue.substring(termEnd), "\n")
        return
      }
    }

    addMarkup(matchValue, "\n")
  }

  private fun isNextLineItemContinuation(): Boolean {
    var p: Int = this.pos + 1
    var indent = 0

    while (p < this.code.length) {
      val c: Char = this.code[p]
      if ((c == ' ') || (c == '\t')) {
        indent++
        p++
      } else if ((c == '\n') || (c == '\r')) {
        return false
      } else {
        if (indent < this.itemBodyIndent) return false
        // A new bullet on the next line starts a fresh item (possibly nested),
        // not a wrapped continuation.
        val matchResult: MatchResult? = ITEM_REGEX.find(this.code.substring(p))
        return (matchResult == null) || (matchResult.range.first != 0)
      }
    }

    return false
  }

  private fun isInIgnoredElementType(): Boolean =
    (
      this.elementTypeStack.contains(ElementType.CommentBlock) ||
        this.elementTypeStack.contains(ElementType.ExampleBlock) ||
        this.elementTypeStack.contains(ElementType.ExportBlock) ||
        this.elementTypeStack.contains(ElementType.SourceBlock) ||
        this.elementTypeStack.contains(ElementType.PropertyDrawer) ||
        this.elementTypeStack.contains(ElementType.LatexEnvironment)
    )

  private fun popElementType() {
    this.elementTypeStack.removeLastOrNull()
    if (this.elementTypeStack.isEmpty()) this.elementTypeStack.addLast(ElementType.Paragraph)
  }

  private fun popObjectType() {
    this.objectTypeStack.removeLastOrNull()
  }

  private fun toggleObjectType(objectType: ObjectType) {
    if (this.objectTypeStack.lastOrNull() == objectType) {
      popObjectType()
    } else {
      this.objectTypeStack.addLast(objectType)
    }
  }

  private enum class ElementType {
    Headline,
    GreaterCenterBlock,
    GreaterQuoteBlock,
    GreaterSpecialBlock,
    CommentBlock,
    ExampleBlock,
    ExportBlock,
    SourceBlock,
    VerseBlock,
    Drawer,
    PropertyDrawer,
    DynamicBlock,
    LatexEnvironment,
    Table,
    Paragraph,
  }

  private enum class ObjectType {
    RegularLinkDescription,
    Bold,
    Strikethrough,
    Italic,
    Verbatim,
    Underline,
    Code,
  }

  companion object {
    private const val REGULAR_LINK_PATH_REGEX_STRING = (
      "[ \\-/0-9A-Z\\\\a-z]+" +
        "|[A-Za-z]+:(//)?[^\r\n\\[\\]]+" +
        "|id:[-0-9A-Fa-f]+" +
        "|#[^\r\n\\[\\]]+" +
        "|\\([^\r\n\\[\\]]+\\)" +
        "|[^\r\n\\[\\]]+"
    )

    private const val TIMESTAMP_REGEX_STRING = (
      "[0-9]{4}-[0-9]{2}-[0-9]{2}[ \t]+[^ \t\r\n+\\-0-9>\\]]+" +
        "([ \t]+[0-9]{1,2}:[0-9]{2})?" +
        "([ \t]+(\\+|\\+\\+|\\.\\+|-|--)[0-9]+[dhmwy]){0,2}"
    )
    private const val TIMESTAMP_RANGE_REGEX_STRING = (
      "[0-9]{4}-[0-9]{2}-[0-9]{2}[ \t]+[^ \t\r\n+\\-0-9>\\]]+" +
        "[ \t]+[0-9]{1,2}:[0-9]{2}-[0-9]{1,2}:[0-9]{2}" +
        "([ \t]+(\\+|\\+\\+|\\.\\+|-|--)[0-9]+[dhmwy]){0,2}"
    )

    private val WHITESPACE_REGEX = Regex("^[ \t]*", RegexOption.IGNORE_CASE)

    private val HEADLINE_REGEX =
      Regex(
        "^(\\*+(?= ))([ \t]+(?-i:TODO|DONE))?([ \t]+\\[#[A-Za-z]])?[ \t]*",
        RegexOption.IGNORE_CASE,
      )
    private val HEADLINE_COMMENT_REGEX =
      Regex(
        "^(\\*+(?= ))([ \t]+(?-i:TODO|DONE))?([ \t]+\\[#[A-Za-z]])?" +
          "[ \t]+COMMENT(?=[ \t]|\r?\n|$)[^\r\n]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )
    private val HEADLINE_TAGS_REGEX =
      Regex(
        "^[ \t]*((:[#%0-9@A-Z_a-z]+)+:)?[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val CAPTION_PREFIX_REGEX =
      Regex(
        "^#\\+CAPTION(\\[[^\r\n]*?])?: ",
        RegexOption.IGNORE_CASE,
      )

    private val PROSE_KEYWORD_PREFIX_REGEX =
      Regex(
        "^#\\+(TITLE|SUBTITLE|DESCRIPTION):[ \t]*",
        RegexOption.IGNORE_CASE,
      )

    private val AFFILIATED_KEYWORDS_REGEX =
      Regex(
        "^#\\+((HEADER|NAME|PLOT|RESULTS)|" +
          "(RESULTS\\[[^\r\n]*?])|ATTR_[-0-9A-Z_a-z]+): [^\r\n]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val BLOCK_BEGIN_REGEX =
      Regex(
        "^#\\+BEGIN_([^ \t\r\n]+)([ \t]+[^\r\n]*?)?[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )
    private val BLOCK_END_REGEX =
      Regex(
        "^#\\+END_([^ \t\r\n]+)[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val DRAWER_BEGIN_REGEX =
      Regex(
        "^:([-A-Z_a-z]+):[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )
    private val DRAWER_END_REGEX =
      Regex(
        "^:END:[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val DYNAMIC_BLOCK_BEGIN_REGEX =
      Regex(
        "^#\\+BEGIN: ([^ \t\r\n]+)([ \t]+[^\r\n]*?)[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )
    private val DRAWER_CLOSING_REGEX =
      Regex(
        "^[ \t]*:END:[ \t]*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
      )

    private val DYNAMIC_BLOCK_CLOSING_REGEX =
      Regex(
        "^[ \t]*#\\+END:?[ \t]*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
      )

    private val HEADLINE_START_REGEX = Regex("^\\*+ ", RegexOption.MULTILINE)

    private val DYNAMIC_BLOCK_END_REGEX =
      Regex(
        "^#\\+END:[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val FOOTNOTE_DEFINITION_REGEX =
      Regex(
        "^\\[fn:([0-9]+|[-A-Z_a-z]+)][ \t]*",
        RegexOption.IGNORE_CASE,
      )

    private val ITEM_REGEX =
      Regex(
        "^(\\*|-|\\+|([0-9]+)[.)])(?=[ \t]|$)([ \t]+\\[@([0-9]+|[A-Za-z])])?" +
          "([ \t]+\\[[- \tX]])?([ \t]+[^\r\n]*?[ \t]+::)?[ \t]*",
        RegexOption.IGNORE_CASE,
      )

    // Capture-group index in ITEM_REGEX of the optional description term
    // (the "<term> ::" portion of "- term :: body").
    private const val ITEM_DESCRIPTION_TERM_GROUP = 6
    private val DESCRIPTION_TERM_REGEX =
      Regex(
        "^([ \t]+)(.*?)([ \t]+)::$",
        RegexOption.IGNORE_CASE,
      )

    private val TABLE_ROW_REGEX =
      Regex(
        "^\\|[ \t]*",
        RegexOption.IGNORE_CASE,
      )
    private val RULE_TABLE_ROW_REGEX =
      Regex(
        "^\\|-[^\r\n]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )
    private val TABLE_CELL_SEPARATOR_REGEX =
      Regex(
        "^[ \t]*\\|[ \t]*",
        RegexOption.IGNORE_CASE,
      )

    private val BABEL_CALL_REGEX =
      Regex(
        "^#\\+CALL:[ \t]*([^\r\n]+?)[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val CLOCK_REGEX =
      Regex(
        "^CLOCK:[ \t]*([^\r\n]+?)[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )
    private val DIARY_SEXP_REGEX =
      Regex(
        "^%%\\(([^\r\n]*)",
        RegexOption.IGNORE_CASE,
      )
    private val PLANNING_REGEX =
      Regex(
        "^(DEADLINE|SCHEDULED|CLOSED):[ \t]*(" +
          "<%%\\([^\r\n>]+\\)>" +
          "|<" + TIMESTAMP_REGEX_STRING + ">" +
          "|\\[" + TIMESTAMP_REGEX_STRING + "]" +
          "|<" + TIMESTAMP_REGEX_STRING + ">--<" + TIMESTAMP_REGEX_STRING + ">" +
          "|<" + TIMESTAMP_RANGE_REGEX_STRING + ">" +
          "|\\[" + TIMESTAMP_REGEX_STRING + "]\\[" + TIMESTAMP_REGEX_STRING + "]" +
          "|\\[" + TIMESTAMP_RANGE_REGEX_STRING + "]" +
          ")]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val COMMENT_REGEX =
      Regex(
        "^#([ \t]+[^\r\n]*?)?(\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val FIXED_WIDTH_LINE_REGEX =
      Regex(
        "^:([ \t]+|(?=\r?\n|$))",
        RegexOption.IGNORE_CASE,
      )

    private val HORIZONTAL_RULE_REGEX =
      Regex(
        "^-{5,}[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val KEYWORD_REGEX =
      Regex(
        "^#\\+([^ \t\r\n]+?):[ \t]*([^\r\n]+?)[ \t]*(?=\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val LATEX_ENVIRONMENT_BEGIN_REGEX =
      Regex(
        "^\\\\begin\\{([*0-9A-Za-z]+)}[ \t]*",
        RegexOption.IGNORE_CASE,
      )

    private val LATEX_FRAGMENT_REGEX1 =
      Regex(
        "^\\\\[A-Za-z]+(\\[[^\r\n{}\\[\\]]*]|\\{[^\r\n{}]*})*",
        RegexOption.IGNORE_CASE,
      )
    private val LATEX_FRAGMENT_REGEX2 =
      Regex(
        "^\\\\\\([\\s\\S]*?\\\\\\)",
        RegexOption.IGNORE_CASE,
      )
    private val LATEX_FRAGMENT_REGEX3 =
      Regex(
        "^\\\\\\[[\\s\\S]*?\\\\]",
        RegexOption.IGNORE_CASE,
      )
    private val LATEX_FRAGMENT_REGEX4 =
      Regex(
        "^\\$\\$[\\s\\S]*?\\$\\$",
        RegexOption.IGNORE_CASE,
      )
    private val LATEX_FRAGMENT_REGEX5 =
      Regex(
        "^\\$[^ \t\r\n\"',.;?]\\$(?=[ \t\"'(),.;<>?\\[\\]]|\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )
    private val LATEX_FRAGMENT_REGEX6 =
      Regex(
        "^\\$[^ \t\r\n$,.;]([^\r\n$]|\r?\n)*[^ \t\r\n$,.]\\$(?=[ \t!\"'(),.;<>?\\[\\]]|\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val EXPORT_SNIPPET_REGEX =
      Regex(
        "^@@[-0-9A-Za-z]+:[^\r\n]*?@@",
        RegexOption.IGNORE_CASE,
      )

    private val FOOTNOTE_REFERENCE_REGEX1 =
      Regex(
        "^\\[fn:[-0-9A-Z_a-z]*]",
        RegexOption.IGNORE_CASE,
      )
    private val FOOTNOTE_REFERENCE_REGEX2 =
      Regex(
        "^\\[fn:([-0-9A-Z_a-z]*)?:[^\r\n]*?]",
        RegexOption.IGNORE_CASE,
      )

    private val INLINE_BABEL_CALL_REGEX =
      Regex(
        "^call_[^ \t\r\n()]+(\\[[^\r\n]*?])?\\([^\r\n]*?\\)(\\[[^\r\n]*?])?",
        RegexOption.IGNORE_CASE,
      )
    private val INLINE_SOURCE_BLOCK_REGEX =
      Regex(
        "^src_[^ \t\r\n]+(\\[[^\r\n]*?])?\\{[^\r\n]*?}",
        RegexOption.IGNORE_CASE,
      )

    private val MACRO_REGEX =
      Regex(
        "^\\{\\{\\{[A-Za-z][-0-9A-Z_a-z]*(\\([^\r\n]*?\\))?}}}",
        RegexOption.IGNORE_CASE,
      )

    private val STATISTICS_COOKIE_REGEX =
      Regex(
        "^\\[[0-9]*(%|/[0-9]*)]",
        RegexOption.IGNORE_CASE,
      )

    private val DIARY_TIMESTAMP_REGEX =
      Regex(
        "^<%%\\([^\r\n>]+\\)>",
        RegexOption.IGNORE_CASE,
      )
    private val ACTIVE_TIMESTAMP_RANGE_REGEX1 =
      Regex(
        "^<$TIMESTAMP_REGEX_STRING>--<$TIMESTAMP_REGEX_STRING>",
        RegexOption.IGNORE_CASE,
      )
    private val ACTIVE_TIMESTAMP_RANGE_REGEX2 =
      Regex(
        "^<$TIMESTAMP_RANGE_REGEX_STRING>",
        RegexOption.IGNORE_CASE,
      )
    private val INACTIVE_TIMESTAMP_RANGE_REGEX1 =
      Regex(
        "^\\[$TIMESTAMP_REGEX_STRING]--\\[$TIMESTAMP_REGEX_STRING]",
        RegexOption.IGNORE_CASE,
      )
    private val INACTIVE_TIMESTAMP_RANGE_REGEX2 =
      Regex(
        "^\\[$TIMESTAMP_RANGE_REGEX_STRING]",
        RegexOption.IGNORE_CASE,
      )
    private val ACTIVE_TIMESTAMP_REGEX =
      Regex(
        "^<$TIMESTAMP_REGEX_STRING>",
        RegexOption.IGNORE_CASE,
      )
    private val INACTIVE_TIMESTAMP_REGEX =
      Regex(
        "^\\[$TIMESTAMP_REGEX_STRING]",
        RegexOption.IGNORE_CASE,
      )

    private val ANGLE_LINK_REGEX =
      Regex(
        "^<[A-Za-z]+:[^\r\n<>\\]]+>",
        RegexOption.IGNORE_CASE,
      )
    private val PLAIN_LINK_REGEX =
      Regex(
        "^[A-Za-z]+:[^ \t\r\n()<>]+(?<=[A-Za-z]|[^ \t\r\n!,.;?]/)(?=[^\r\n0-9A-Za-z]|\r?\n|$)",
        RegexOption.IGNORE_CASE,
      )

    private val LINK_PRECEDING_REGEX =
      Regex(
        "^[^\r\n0-9A-Za-z]",
        RegexOption.IGNORE_CASE,
      )
    private val RADIO_TARGET_REGEX =
      Regex(
        "^<<<(?![ \t])[^\r\n<>]+(?<![ \t])>>>",
        RegexOption.IGNORE_CASE,
      )
    private val TARGET_REGEX =
      Regex(
        "^<<(?![ \t])[^\r\n<>]+(?<![ \t])>>",
        RegexOption.IGNORE_CASE,
      )

    private val REGULAR_LINK_WITHOUT_DESCRIPTION_REGEX =
      Regex(
        "^\\[\\[($REGULAR_LINK_PATH_REGEX_STRING)]]",
        RegexOption.IGNORE_CASE,
      )
    private val REGULAR_LINK_WITH_DESCRIPTION_REGEX =
      Regex(
        "^\\[\\[($REGULAR_LINK_PATH_REGEX_STRING)]\\[(?=[^\r\n\\[\\]]+]])",
        RegexOption.IGNORE_CASE,
      )
    private val REGULAR_LINK_DESCRIPTION_END_REGEX =
      Regex(
        "^]]",
        RegexOption.IGNORE_CASE,
      )

    private val TEXT_MARKUP_START_PRECEDING_REGEX =
      Regex(
        "^[ \t\r\n\"'(\\-{]",
        RegexOption.IGNORE_CASE,
      )
    private val TEXT_MARKUP_START_FOLLOWING_REGEX =
      Regex(
        "^[^ \t\r\n]",
        RegexOption.IGNORE_CASE,
      )
    private val TEXT_MARKUP_END_PRECEDING_REGEX =
      Regex(
        "^[^ \t\r\n]",
        RegexOption.IGNORE_CASE,
      )
    private val TEXT_MARKUP_END_FOLLOWING_REGEX =
      Regex(
        "^([ \t\r\n!\"'),\\-.:;?\\[}]|$)",
        RegexOption.IGNORE_CASE,
      )
    private val TEXT_MARKUP_MARKER_REGEX =
      Regex(
        "^[*+/=_~]",
        RegexOption.IGNORE_CASE,
      )
    private val TEXT_MARKUP_VERBATIM_END_REGEX =
      Regex(
        "^=",
        RegexOption.IGNORE_CASE,
      )
    private val TEXT_MARKUP_CODE_END_REGEX =
      Regex(
        "^~",
        RegexOption.IGNORE_CASE,
      )
  }
}
