package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.typst.TypstToken.*
import org.languagetool.markup.AnnotatedTextBuilder
import org.languagetool.markup.TextPart
import kotlin.io.path.Path
import kotlin.io.path.readText

class EOFSignal : Exception()

class UnexpectedTokenException(token: TypstToken) : Exception("Unexpected token $token")

class NewTypstAnnotatedTextBuilder(
  val source: String,
) : AnnotatedTextBuilder() {

  fun previewAll(): String {
    val text = this.build()
    return text.textWithMarkup
  }

  fun previewText(): String {
    val text = this.build()
    return text.plainText
  }


  private val tokens = TypstTokenizer(source).asSequence().toList()

  private var cur = 0

  fun next(): TypstToken {
    if (cur >= tokens.size) {
      throw EOFSignal()
    }
    return tokens[cur++]
  }

  fun next(expect: (TypstToken) -> Boolean): TypstToken {
    if (cur >= tokens.size) {
      throw EOFSignal()
    }
    val token = tokens[cur++]
    if (!expect(token)) {
      throw IllegalStateException("Unexpected token $token")
    }
    return token
  }

  fun peek(): TypstToken {
    if (cur >= tokens.size) {
      throw EOFSignal()
    }
    return tokens[cur]
  }

  fun recover() {
    cur--
  }


  fun markup(topLevel: Boolean = false) {
    while (true) {
      val token = next()
      when (token) {
        is Symbol if token.sym == "#" -> {
          addMarkup(token.value)
          codeExpr(expectMore = true, consecutiveCall = false)
        }

        is Symbol if token.sym == "*" -> {
          addMarkup(token.value)
        }

        is Symbol if token.sym == "_" -> {
          addMarkup(token.value)
        }

        is Dollar -> {
          addMarkup(token.value)
          math()
        }

        is RBracket, is EOF -> {
          addMarkup(token.value)
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
          addMarkup(token.value)
          addMarkup(next { it is Content }.value, "REF")
          codeExpr(expectMore = false, consecutiveCall = true)
        }

        is Symbol if token.sym == "`" -> {
          addMarkup(token.value)
          raw()
        }

        is Symbol if token.sym == "```" -> {
          addMarkup(token.value)
          rawBlock()
        }

        else -> {
          addText(token.value)
        }
      }
    }
  }

  fun codeExpr(expectMore: Boolean, consecutiveCall: Boolean) {
    val token = next()
    when (token) {
      is LParen -> {
        addMarkup(token.value)
        codeListOrMap()
        codeExpr(expectMore = false, consecutiveCall = true)
      }

      is LBracket -> {
        addMarkup(token.value)
        markup()
        codeExpr(expectMore = false, consecutiveCall = true)
      }

      is LBrace -> {
        addMarkup(token.value)
        codeBlock()
      }

      is Quote -> {
        addMarkup(token.value)
        codeString()
      }

      is WhiteSpace if consecutiveCall -> {
        recover()
      }

      is WhiteSpace -> {
        addMarkup(token.value)
        codeExpr(expectMore = expectMore, consecutiveCall = false)
      }

      is Dollar -> {
        addMarkup(token.value)
        math()
      }

      is Content if token.text == "let" -> {
        // let name = ...
        addMarkup(token.value) // let
        codeExpr(expectMore = true, consecutiveCall = false)
      }

      is Content if token.text == "set" -> {
        // set f(...)
        addMarkup(token.value) // set
        codeExpr(expectMore = true, consecutiveCall = false)
      }

      is Content if token.text == "show" -> {
        // show foo: ...
        addMarkup(token.value) // show
        codeExpr(expectMore = true, consecutiveCall = false)
      }

      is Content if token.text == "import" -> {
        // import "foo/bar": ...
        addMarkup(token.value) // import
        while (true) {
          val token = next()
          when (token) {
            is WhiteSpace if token.containsNewLine() -> {
              recover()
              break
            }

            else -> {
              addMarkup(token.value)
            }
          }
        }
      }

      is Symbol if token.sym == "." || token.sym == "=" || token.sym == "=>" || token.sym == ":" -> {
        addMarkup(token.value)
        codeExpr(expectMore = true, consecutiveCall = false)
      }

      is Content if expectMore -> {
        addMarkup(token.value)
        codeExpr(expectMore = false, consecutiveCall = false)
      }

      is Content -> {
        recover()
      }

      is Symbol if token.sym == "#" || token.sym == "<" -> {
        recover()
      }

      is RParen, RBracket, RBrace -> {
        recover()
      }

      is Symbol if token.sym == "," -> {
        addMarkup(token.value)
      }

      else -> {
        addMarkup(token.value)
      }
    }
  }

  fun codeBlock() {
    val token = next()
    when (token) {
      is LParen -> {
        addMarkup(token.value)
        codeListOrMap()
        codeBlock()
      }

      is LBracket -> {
        addMarkup(token.value)
        markup()
        codeBlock()
      }

      is LBrace -> {
        addMarkup(token.value)
        codeBlock()
        codeBlock()
      }

      is RBrace -> {
        addMarkup(token.value)
      }

      else -> {
        addMarkup(token.value)
        codeBlock()
      }
    }
  }

  fun codeString() {
    while (true) {
      val token = next()
      when (token) {
        is Quote -> {
          addMarkup(token.value)
          return
        }

        else -> {
          addMarkup(token.value)
        }
      }
    }
  }

  fun codeListOrMap() {
    while (true) {
      codeExpr(expectMore = true, consecutiveCall = false)
      val token = next()
      if (token is RParen) {
        addMarkup(token.value)
        return
      } else {
        recover()
      }
    }
  }


  fun raw() {
    while (true) {
      val token = next()
      // Should inline raw text be text or markup?
      when (token) {
        is Symbol if token.sym == "`" -> {
          addMarkup(token.value)
          return
        }

        else -> {
          addText(token.value)
        }
      }
    }
  }

  fun rawBlock() {
    while (true) {
      val token = next()
      when (token) {
        is Symbol if token.sym == "```" -> {
          addMarkup(token.value, "RAW_BLOCK")
          return
        }

        else -> {
          addMarkup(token.value)
        }
      }
    }
  }


  fun math() {
    val token = next()
    when (token) {
      is Dollar -> {
        addMarkup(token.value, "MATH")
      }

      else -> {
        addMarkup(token.value)
        math()
      }
    }
  }


  fun parse(): NewTypstAnnotatedTextBuilder {
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
      val builder = NewTypstAnnotatedTextBuilder(source)
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
