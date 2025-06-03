/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.DummyGenerator
import org.bsplines.ltexls.parsing.typst.TypstToken.*
import org.bsplines.ltexls.settings.Settings
import org.languagetool.markup.TextPart
import kotlin.io.path.Path
import kotlin.io.path.readText

class UnexpectedTokenException(
  actual: TypstToken,
  expect: String,
  before: String,
  after: List<TypstToken>,
) : Exception(
  """
Actual = $actual
Expect = $expect 
Processed:
---
$before
---
Remaining:
---
${after.joinToString()}
---
  """,
)

/**
 * A recursive descent annotated text builder for Typst.
 */
class TypstAnnotatedTextBuilder(
  codeLanguageId: String,
) : CodeAnnotatedTextBuilder(codeLanguageId) {

  private fun unexpect(
    token: TypstToken,
    expect: String = "...",
  ): Nothing {
    throw UnexpectedTokenException(
      token,
      expect,
      previewAll().lines().takeLast(32).joinToString("\n"),
      tokens.subList(cur, tokens.size).take(32),
    )
  }


  /**
   * Builds the original text. This method is for debugging purposes only and
   * should return the same text as the original code.
   */
  fun previewAll(): String {
    val text = this.build()
    return text.textWithMarkup
  }

  /**
   * Builds the plain text. This method is for debugging purposes only and
   * should return the text without markup.
   */
  fun previewText(): String {
    val text = this.build()
    return text.plainText
  }

  private var language = "en-US"

  override fun setSettings(settings: Settings) {
    super.setSettings(settings)
    this.language = settings.languageShortCode
  }

  private var dummyCounter = 0

  private fun generateDummy(): String {
    return DummyGenerator.getInstance().generate(language, dummyCounter++)
  }


  private var lastChar: Char? = null

  override fun addText(text: String?): CodeAnnotatedTextBuilder {
    super.addText(text)
    if (text?.isNotEmpty() == true) {
      lastChar = text.last()
    }
    return this
  }


  private val tokens = mutableListOf<TypstToken>()

  private var cur = 0


  /**
   * Returns the next token. If there are no more tokens, it throws an EOFSignal
   * to quickly exit the parsing loop.
   */
  private fun next(): TypstToken {
    if (cur >= tokens.size) {
      throw EOFSignal()
    }
    val token = tokens[cur++]
    when (token) {
      is Comment -> {
        addMarkup(token.value)
        return next()
      }

      else -> {
        return token
      }
    }
  }

  /**
   * Returns the next token that matches the given predicate. If the next token
   * does not match, it throws an UnexpectedTokenException. If there are no more
   * tokens, it throws an EOFSignal to quickly exit the parsing loop.
   */
  private fun next(expect: (TypstToken) -> Boolean): TypstToken {
    val token = next()
    if (!expect(token)) {
      unexpect(token, expect.toString())
    }
    return token
  }

  private fun peek(): TypstToken {
    if (cur >= tokens.size) {
      throw EOFSignal()
    }
    return tokens[cur]
  }

  /**
   * Recovers the last token. This is used to handle cases of "look ahead" in
   * the parsing. It allows the parser to backtrack and try a different path if
   * the current one fails.
   */
  private fun recover() {
    cur--
  }


  override fun addCode(code: String): TypstAnnotatedTextBuilder = apply {
    tokens.addAll(TypstTokenizer.tokenize(code))
    parse()
    assert(cur == tokens.size) {
      "Not all tokens were consumed. Remaining tokens: ${tokens.subList(cur, tokens.size)}"
    }
  }


  private var lineBeginning = true


  /**
   * Parses the remaining tokens as markup.
   *
   * @param topLevel It indicates if this is the top level of the document. This
   * is for debugging purposes.
   */
  private fun markup(topLevel: Boolean = false) {
    var headingLevels = 0

    while (true) {
      val token = next()
      when (token) {
        is Symbol if token.sym == "#" -> {
          addMarkup(token.value)
          when (codeExpr(codeCtx)) {
            CodeExprResult.Dummy -> addMarkup("", generateDummy())
            CodeExprResult.None -> {}
            CodeExprResult.ContextDependent -> when (peek()) {
              is Newline, EOF -> {}
              else -> addMarkup("", generateDummy())
            }
          }
        }

        is Symbol if token.sym.all { it == '=' } && lineBeginning -> {
          lineBeginning = false

          val nextToken = next()
          when (nextToken) {
            is Spaces, is EOF -> {
              addMarkup(token.value)
              addMarkup(nextToken.value)
              headingLevels = token.sym.length
            }

            else -> {
              addText(token.value)
              recover()
            }
          }
        }

        is Symbol if (token.sym == "+" || token.sym == "-") && lineBeginning -> {
          lineBeginning = false

          val nextToken = next()
          when (nextToken) {
            is Spaces -> {
              addMarkup(token.value)
              addMarkup(nextToken.value)
            }

            else -> {
              addText(token.value)
              recover()
            }
          }
        }

        is Symbol if token.sym == "*" -> {
          addMarkup(token.value)
        }

        is Symbol if token.sym == "_" -> {
          addMarkup(token.value)
        }

        is Dollar -> {
          addMarkup(token.value)
          math(mathCtx)
        }

        is RBracket -> {
          addMarkup(token.value, "\n")
          return
        }

        is Comment -> {
          addMarkup(token.value)
        }

        // Tag: <foo>
        is Symbol if token.sym == "<" -> {
          addMarkup(token.value)
          while (true) {
            val token = next()
            when (token) {
              is Symbol if token.sym == ">" -> {
                addMarkup(token.value)
                break
              }

              else -> {
                addMarkup(token.value)
              }
            }
          }
        }

        // Ref (to Tag): @foo
        is Symbol if token.sym == "@" -> {
          // @
          addMarkup(token.value)

          // foo
          val result = codeExpr(CodeCtx(inline = true, strAsText = false, hasText = false))
          when (result) {
            is CodeExprResult.None -> {
            }

            else -> {
              addMarkup("", generateDummy())
            }
          }
        }

        is Symbol if token.sym == "`" -> {
          addMarkup(token.value)
          raw()
          addMarkup("", generateDummy())
        }

        is Symbol if token.sym == "```" -> {
          addMarkup(token.value)
          rawBlock()
        }

        is Newline -> {
          if (headingLevels > 0) {
            headingLevels = 0
            if (lastChar?.isLetterOrDigit() == true) {
              // end the heading with a period if it is omitted
              addMarkup("", ".")
            }
          }

          addText(token.value)

          val nextToken = next()
          when (nextToken) {
            is Spaces -> {
              addMarkup(nextToken.value)
            }

            else -> {
              recover()
            }
          }

          lineBeginning = true
        }

        is Escaped if token.char == '\n' -> {
          addMarkup(token.value, token.char.toString())

          val nextToken = next()
          when (nextToken) {
            is Spaces -> {
              addMarkup(nextToken.value)
            }

            else -> {
              recover()
            }
          }
        }

        is Escaped -> {
          addMarkup(token.value, token.char.toString())
        }

        is EOF -> {
          if (headingLevels > 0) {
            // period to end the heading
            addMarkup("", ".")
          }
          return
        }

        else -> {
          addText(token.value)
        }
      }
    }
  }


  private sealed class CodeExprResult {
    object None : CodeExprResult()
    object Dummy : CodeExprResult()
    object ContextDependent : CodeExprResult()
  }

  private var inExpr = false

  private data class CodeCtx(
    val inline: Boolean,
    val strAsText: Boolean,
    val hasText: Boolean,
  )

  private val codeCtx
    get() = CodeCtx(
      inline = true,
      strAsText = false,
      hasText = false,
    )


  /**
   * Parses one code expression.
   *
   * The param `expectMore` indicates if the parser should expect the next token to be
   * a code expression. For example,
   *
   * ```typst
   * #sym. alef
   * ```
   *
   * The token `#` indicates that the following is a code expression, hence
   * `codeExpr` is called on the following token. It parses `sym` and then sees
   * a `.`. Then, whatever follows should also be a code expression. Hence, it
   * calls `codeExpr` again with `expectMore = true`.
   *
   * Note that it assumes the beginning token (hash) is already consumed.
   */
  private fun codeExpr(ctx: CodeCtx, optional: Boolean = false): CodeExprResult {
    val token = next()
    when (token) {
      is LParen -> {
        addMarkup(token.value)
        codeList(ctx)
      }

      is LBracket -> {
        addMarkup(token.value)
        markup()
      }

      is LBrace -> {
        addMarkup(token.value)
        codeBlock(ctx.copy(inline = false))
        return CodeExprResult.None
      }

      is Quote -> {
        addMarkup(token.value)
        codeString(ctx.strAsText)
      }

      is Spaces if inExpr -> {
        addMarkup(token.value)
      }

      is Spaces, is Newline, is EOF -> {
        recover()
        return CodeExprResult.Dummy
      }


      is Dollar -> {
        addMarkup(token.value)
        math(mathCtx)
      }

      is Content if token.text == "let" -> {
        // let name = <expr>
        // let name() = <expr>
        addMarkup(token.value) // let
        codeSpace()
        codeIdentifier() // name
        codeSpaceOptional()

        val nextToken = next()
        when (nextToken) {
          // let name = <expr>
          is Symbol if nextToken.sym == "=" -> {
            addMarkup(nextToken.value)
            codeSpaceOptional()
            codeExpr(ctx) // <expr>
          }
          // let name() = <expr>
          is LParen -> {
            addMarkup(nextToken.value)
            codeList(ctx)
            codeSpaceOptional()
            addMarkup(next { it is Symbol && it.sym == "=" }.value)
            codeSpaceOptional()
            codeExpr(ctx)
          }

          else -> unexpect(nextToken, "Symbol(=) or LParen")
        }
        return CodeExprResult.None
      }

      is Content if token.text == "set" -> {
        // set f(...)
        addMarkup(token.value) // set
        codeSpace()
        codeSel() // f
        addMarkup(next { it is LParen }.value) // (
        codeList(ctx) // ...)
        return CodeExprResult.None
      }

      is Content if token.text == "show" -> {
        // show: ...
        // show foo: ...
        addMarkup(token.value) // show
        codeSpaceOptional()

        val token = next()
        when (token) {
          // show: foo
          is Symbol if token.sym == ":" -> {
            addMarkup(token.value)
            codeSpaceOptional()
          }

          else -> {
            recover()
            codeExpr(ctx) // foo
            val token = next()
            when (token) {
              is Symbol if token.sym == ":" -> {
                addMarkup(token.value)
                codeSpaceOptional()
              }

              else -> {
                unexpect(token, ":")
              }
            }
          }
        }

        codeExpr(ctx)
        return CodeExprResult.None
      }

      is Content if token.text == "import" -> {
        // import "foo/bar": ...
        addMarkup(token.value) // import
        while (true) {
          val token = next()
          when (token) {
            is Newline -> {
              addMarkup(token.value)
              break
            }

            else -> {
              addMarkup(token.value)
            }
          }
        }
        return CodeExprResult.None
      }

      is Content if token.text == "cite" -> {
        addMarkup(token.value)
        addMarkup(next { it is LParen }.value)
        codeList(ctx)
        return CodeExprResult.None
      }

      is Content -> {
        recover()
        codeSel()
        while (true) when (peek()) {
          // function call
          // f(...)
          is LParen -> {
            addMarkup(next().value)
            codeList(ctx)
          }

          is LBracket -> {
            addMarkup(next().value)
            markup()
          }

          else -> {
            break
          }
        }
      }

      is Symbol if token.sym == "<" -> {
        TODO("Label in Code")
      }

      is RBracket -> {
        recover()
      }

      is Symbol if token.sym in setOf("+", "-", "*", "/", "%", "==", "!=", "=>") -> {
        addMarkup(token.value)
      }

      else -> {
        if (optional) {
          addMarkup(token.value)
        } else {
          unexpect(token)
        }
      }
    }
    return CodeExprResult.ContextDependent
  }

  private fun codeIdentifier() {
    val token = next()
    when (token) {
      is Content -> {
        addMarkup(token.value)
      }

      is Symbol if (token.sym == "-" || token.sym == "_") -> {
        addMarkup(token.value)
      }
      
      is Spaces, is Newline, is EOF -> {
        recover()
      }

      else -> {
        recover()
        unexpect(token, "Content or Symbol")
      }
    }

    while (true) {
      val token = next()
      when (token) {
        is Content -> {
          addMarkup(token.value)
        }

        is Symbol if (token.sym == "-" || token.sym == "_") -> {
          addMarkup(token.value)
        }

        else -> {
          recover()
          break
        }
      }
    }
  }

  private fun codeSel() {
    codeIdentifier()
    while (true) {
      val token = next()
      when (token) {
        is Symbol if token.sym == "." -> {
          addMarkup(token.value)
          codeIdentifier()
        }

        is Spaces if inExpr -> {
          addMarkup(token.value)
        }

        is Newline if inExpr -> {
          addMarkup(token.value)
        }

        else -> {
          recover()
          break
        }
      }
    }
  }

  private fun codeSpaceOptional() {
    val token = next()
    when (token) {
      is Spaces -> {
        addMarkup(token.value)
      }

      else -> {
        recover()
      }
    }
  }

  private fun codeWhiteSpaceOptional() {
    while (true) {
      val token = next()
      when (token) {
        is Spaces, is Newline -> {
          addMarkup(token.value)
        }

        else -> {
          recover()
          break
        }
      }
    }
  }

  private fun codeSpace() {
    val token = next()
    when (token) {
      is Spaces -> {
        addMarkup(token.value)
      }

      else -> {
        recover()
        unexpect(token, "Spaces")
      }
    }
  }

  /**
   * Parses a code block. A code block is a sequence of code expressions
   * enclosed in braces.
   *
   * Note that it assumes the beginning token (brace) is already consumed.
   */
  private fun codeBlock(ctx: CodeCtx): CodeCtx {
    if (ctx.inline) {
      return codeBlock(ctx.copy(inline = false))
    }
    val token = next()
    return when (token) {
      is LBracket -> {
        addMarkup(token.value)
        markup()
        codeBlock(ctx)
      }

      is LBrace -> {
        addMarkup(token.value)
        codeBlock(ctx)
        codeBlock(ctx)
      }

      is RBrace -> {
        addMarkup(token.value)
        ctx
      }

      is Quote -> {
        addMarkup(token.value)
        codeString(false)
        ctx
      }

      else -> {
        addMarkup(token.value)
        codeBlock(ctx)
      }
    }
  }

  /**
   * Parses a code string. A code string is a sequence of characters
   * enclosed in quotes in code mode.
   *
   * Note that it assumes the beginning token (quote) is already consumed.
   */
  private fun codeString(asText: Boolean) {
    while (true) {
      val token = next()
      when (token) {
        is Quote -> {
          addMarkup(token.value)
          return
        }

        else -> {
          if (asText) {
            addText(token.value)
          } else {
            addMarkup(token.value)
          }
        }
      }
    }
  }

  /**
   * Parses a code list or map. A code list or map is a sequence of code
   * expressions enclosed in parentheses, such as:
   *
   * ```typst
   * #(foo, bar)
   * ```
   *
   * ```typst
   * #(foo: bar)
   * ```
   *
   * Note that it assumes the beginning token (parenthesis) is already consumed.
   */
  private fun codeList(ctx: CodeCtx) {
    codeWhiteSpaceOptional()
    while (true) {
      codeExpr(ctx, optional = true)
      codeWhiteSpaceOptional()
      val token = next()
      when (token) {
        is RParen -> {
          addMarkup(token.value)
          return
        }

        is Symbol if token.sym == "," -> {
          addMarkup(token.value)
          codeWhiteSpaceOptional()

          // If this comma is the optional comma at the end of the list.
          if (peek() is RParen) {
            addMarkup(next().value)
            return
          }
        }

        is Symbol if token.sym == ":" -> {
          addMarkup(token.value)
          codeWhiteSpaceOptional()
        }

        else -> {
          recover()
        }
      }
    }
  }


  /**
   * Parses raw text. Raw text is a sequence of characters enclosed in
   * backticks, such as:
   *
   * ```typst
   * `foo`
   * ```
   *
   * Note that it assumes the beginning token (backtick) is already consumed.
   */
  private fun raw() {
    while (true) {
      val token = next()
      // Should inline raw text be text or markup?
      when (token) {
        is Symbol if token.sym == "`" -> {
          addMarkup(token.value)
          return
        }

        else -> {
          addMarkup(token.value)
        }
      }
    }
  }

  /**
   * Parses raw blocks. Raw blocks are a sequence of characters enclosed in
   * triple backticks. Note that it assumes the beginning token (triple
   * backticks) is already consumed.
   */
  private fun rawBlock() {
    while (true) {
      val token = next()
      when (token) {
        is Symbol if token.sym == "```" -> {
          addMarkup(token.value)
          return
        }

        else -> {
          addMarkup(token.value)
        }
      }
    }
  }


  private data class MathCtx(
    val inline: Boolean,
    val hasText: Boolean,
    val defaultText: () -> String,
  )

  private val mathCtx
    get() = MathCtx(
      inline = true,
      hasText = false,
      defaultText = ::generateDummy,
    )

  /**
   * Parses inline or block math. Math is a sequence of characters
   * enclosed in dollar signs, such as:
   *
   * ```typst
   * $foo$
   * ```
   *
   * ```typst
   * $
   *  foo
   * $
   * ```
   *
   * Note that it assumes the beginning token (dollar sign) is already consumed.
   */
  private fun math(ctx: MathCtx) {
    val token = next()
    when (token) {
      is Newline if ctx.inline -> {
        addMarkup(token.value)
        math(ctx.copy(inline = false))
      }

      is Dollar -> {
        if (ctx.hasText || !ctx.inline) {
          addMarkup(token.value)
        } else {
          addMarkup(token.value, ctx.defaultText())
        }
      }

      is Quote -> {
        when {
          ctx.hasText && ctx.inline -> addMarkup(token.value, " ")
          ctx.hasText && !ctx.inline -> addMarkup(token.value, "\n")
          else -> addMarkup(token.value)
        }
        codeString(asText = true)
        math(ctx.copy(hasText = true))
      }

      else -> {
        addMarkup(token.value)
        math(ctx)
      }
    }
  }


  fun parse(): TypstAnnotatedTextBuilder {
    try {
      markup(true)
    } catch (_: EOFSignal) {
    }
    return this
  }


  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      val source = Path("C:\\Users\\Flandia\\Typst\\COMP 3711\\test.typ").readText()
      val builder = TypstAnnotatedTextBuilder(source)
      builder.parse()
      val parts = builder.build().parts
      for (part in parts) {
        if (part.type == TextPart.Type.TEXT) {
          print("${part.part}")
        }
        if (part.type == TextPart.Type.FAKE_CONTENT) {
          print("[${part.part}]")
        }
      }
    }
  }

}
