package org.bsplines.ltexls.parsing.typst

import org.bsplines.ltexls.parsing.typst.TypstToken.*
import java.io.EOFException
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.text.RegexOption.DOT_MATCHES_ALL
import kotlin.text.RegexOption.MULTILINE

sealed class TypstToken {
  data class Content(val text: String) : TypstToken() {
    override val value: String = text
  }

  data class Comment(val content: String) : TypstToken() {
    override val value: String = content
  }

  data object LParen : TypstToken() {
    override val value: String = "("
  }

  data object RParen : TypstToken() {
    override val value: String = ")"
  }

  data object LBracket : TypstToken() {
    override val value: String = "["
  }

  data object RBracket : TypstToken() {
    override val value: String = "]"
  }

  data object LBrace : TypstToken() {
    override val value: String = "{"
  }

  data object RBrace : TypstToken() {
    override val value: String = "}"
  }

  data object Quote : TypstToken() {
    override val value: String = "\""
  }

  data object Dollar : TypstToken() {
    override val value: String = "$"
  }

  data class Escaped(val char: Char) : TypstToken() {
    override val value: String = "\\$char"
  }

  data class WhiteSpace(val text: String) : TypstToken() {
    fun containsNewLine() = text.count { it == '\n' } >= 1
    fun containsNewParagraph() = text.count { it == '\n' } >= 2
    override val value: String = text
  }

  data object BOF : TypstToken() {
    override val value: String = ""
  }

  data object EOF : TypstToken() {
    override val value: String = ""
  }

  data class Symbol(val sym: String) : TypstToken() {
    override val value: String = sym
  }

  abstract val value: String
}

class TypstTokenizer(val source: String) : Iterator<TypstToken> {

  private var cur = 0

  private fun nextChar(): Char {
    if (cur >= source.length) {
      throw EOFException()
    }
    return source[cur++]
  }

  private fun peekChar(): Char {
    if (cur >= source.length) {
      throw EOFException()
    }
    return source[cur]
  }

  private fun match(regex: Regex): String? {
    val matchResult = regex.matchAt(source, cur)
    if (matchResult != null) {
      cur += matchResult.value.length
      return matchResult.value
    }
    return null
  }

  private fun match(char: Char): String? {
    if (cur < source.length && source[cur] == char) {
      cur++
      return char.toString()
    }
    return null
  }

  private fun match(predicate: (Char) -> Boolean): String? {
    if (cur < source.length && predicate(source[cur])) {
      cur++
      return source[cur - 1].toString()
    }
    return null
  }

  private fun match(string: String): String? {
    if (cur + string.length <= source.length && source.substring(
        cur,
        cur + string.length,
      ) == string
    ) {
      cur += string.length
      return string
    }
    return null
  }


  override fun hasNext(): Boolean {
    return cur < source.length || token != null
  }

  fun rest(): String {
    return source.substring(cur)
  }

  private var token: TypstToken? = null
  private val buf: StringBuilder = StringBuilder()

  private var inString = false
  private var inRaw = false
  private var inRawBlock = false

  private fun tryNext(): TypstToken? {
    match('(')?.let {
      return LParen
    }
    match(')')?.let {
      return RParen
    }
    match('[')?.let {
      return LBracket
    }
    match(']')?.let {
      return RBracket
    }
    match('{')?.let {
      return LBrace
    }
    match('}')?.let {
      return RBrace
    }
    match('"')?.let {
      inString = !inString
      return Quote
    }
    if (!inString) {
      match("```")?.let {
        inRawBlock = !inRawBlock
        return Symbol(it)
      }
    }
    if (!inRawBlock && !inString) {
      match("`")?.let {
        inRaw = !inRaw
        return Symbol(it)
      }
    }
    match('$')?.let {
      return Dollar
    }
    match('\\')?.let {
      val next = peekChar()
      if (next.isWhitespace()) {
        return WhiteSpace("\n")
      }
      nextChar()
      return Escaped(next)
    }
    if (!inString && !inRaw && !inRawBlock) {
      match(Regex("//.*$\r?\n", setOf(MULTILINE)))?.let {
        return Comment(it)
      }
    }
    if (!inString && !inRaw && !inRawBlock) {
      match(Regex("/\\*.*?\\*/", setOf(MULTILINE, DOT_MATCHES_ALL)))?.let {
        return Comment(it)
      }
    }
    match(Regex("\\s+"))?.let {
      return WhiteSpace(it)
    }
    match("=>")?.let {
      return Symbol(it)
    }
    match("_")?.let {
      return Symbol(it)
    }
    match { !it.isJavaIdentifierPart() && it != '-' }?.let {
      return Symbol(it)
    }
    buf.append(nextChar())
    return null
  }

  override fun next(): TypstToken {
    if (token != null) {
      return token!!.let {
        token = null
        it
      }
    }
    if (cur >= source.length) {
      return EOF
    }
    do {
      val t = tryNext()
      if (t != null) {
        if (buf.isEmpty()) {
          return t
        } else {
          token = t
          val t = Content(buf.toString())
          buf.clear()
          return t
        }
      }
    } while (true)
  }

}

object Main {
  @JvmStatic
  fun main(args: Array<String>) {
    val source = Path("C:\\Users\\Flandia\\Typst\\COMP 3711\\test.typ").readText()
    val builder = TypstTokenizer(source)
    for (token in builder) {
      println(token)
    }
  }
}
