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
  var characterProcessed = false
    protected set
  var codeBlockDelimiter = BracketType.RoundBracket
    protected set
  val codeMode = CodeModeHandler()

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
    // Anchor the regex at `pos` via Matcher.region(...) instead of allocating
    // a fresh substring of (code.length - pos) bytes on every call. The driving
    // loop in addCode() invokes matchFromPosition tens of times per character,
    // so the previous "regex.find(this.code.substring(pos))" was O(N²) in the
    // document length. With region() we stay O(N).
    //
    // The regexes used by every CharacterBasedCodeAnnotatedTextBuilder subclass
    // begin with "^"; with the default useAnchoringBounds(true), "^" matches
    // at the region start (i.e., at `pos`), preserving the original semantics.
    if (pos < 0 || pos >= this.code.length) return null
    val matcher: java.util.regex.Matcher = regex.toPattern().matcher(this.code)
    matcher.region(pos, this.code.length)
    if (!matcher.lookingAt()) return null
    if (matcher.end() == matcher.start()) return null
    return AnchoredMatchResult.snapshot(matcher, this.code)
  }

  /**
   * Adapter that exposes a [java.util.regex.Matcher] match as a Kotlin
   * [MatchResult] using coordinates **relative to the match start**, so that
   * existing callers — which were written against `regex.find(substring)` and
   * therefore see "0" as the match start — continue to work unchanged.
   */
  private class AnchoredMatchResult(
    private val input: CharSequence,
    private val matchAbsoluteStart: Int,
    private val groupStarts: IntArray,
    private val groupEnds: IntArray,
  ) : MatchResult {
    override val range: IntRange
      get() = groupStarts[0] until groupEnds[0]

    override val value: String
      get() =
        input.substring(
          matchAbsoluteStart + groupStarts[0],
          matchAbsoluteStart + groupEnds[0],
        )

    override val groups: MatchGroupCollection =
      object : MatchGroupCollection {
        override val size: Int
          get() = groupStarts.size

        override fun isEmpty(): Boolean = false

        override fun iterator(): Iterator<MatchGroup?> =
          (0 until size).asSequence().map { get(it) }.iterator()

        override fun contains(element: MatchGroup?): Boolean = throw UnsupportedOperationException()

        override fun containsAll(elements: Collection<MatchGroup?>): Boolean =
          throw UnsupportedOperationException()

        override fun get(index: Int): MatchGroup? {
          val s: Int = groupStarts[index]
          val e: Int = groupEnds[index]
          return if (s < 0) {
            null
          } else {
            MatchGroup(
              input.substring(matchAbsoluteStart + s, matchAbsoluteStart + e),
              s until e,
            )
          }
        }
      }

    override val groupValues: List<String> by lazy {
      List(groupStarts.size) { i ->
        val s: Int = groupStarts[i]
        val e: Int = groupEnds[i]
        if (s < 0) "" else input.substring(matchAbsoluteStart + s, matchAbsoluteStart + e)
      }
    }

    override fun next(): MatchResult? = null

    companion object {
      fun snapshot(
        matcher: java.util.regex.Matcher,
        input: CharSequence,
      ): AnchoredMatchResult {
        val matchStart: Int = matcher.start()
        val groupCount: Int = matcher.groupCount()
        val starts = IntArray(groupCount + 1)
        val ends = IntArray(groupCount + 1)

        for (i in 0..groupCount) {
          val s: Int = matcher.start(i)
          val e: Int = matcher.end(i)
          starts[i] = if (s < 0) -1 else s - matchStart
          ends[i] = if (e < 0) -1 else e - matchStart
        }

        return AnchoredMatchResult(input, matchStart, starts, ends)
      }
    }
  }

  open fun generateDummy(): String =
    this.dummyGenerator.generate(this.language, this.dummyCounter++)

  enum class BracketType(
    val openingBracket: String,
    val closingBracket: String,
  ) {
    RoundBracket("(", ")"),
    CurlyBracket("{", "}"),
  }
}
