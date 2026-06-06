/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.markdown

import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ext.definition.DefinitionExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gitlab.GitLabExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.test.util.AstCollectingVisitor
import com.vladsch.flexmark.util.ast.Document
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.DataHolder
import com.vladsch.flexmark.util.data.MutableDataSet
import com.vladsch.flexmark.util.sequence.Escaping
import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.DummyGenerator
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.tools.Logging
import java.util.logging.Level

@Suppress("TooManyFunctions")
class MarkdownAnnotatedTextBuilder(
  codeLanguageId: String,
  // When true, `addComment` rewrites Emacs-style quoted-identifier markers
  // `` `name' `` to Markdown inline-code markers `` `name` `` in the
  // flexmark input. The substitution is length-preserving (`'` → `` ` ``,
  // one char for one char) so source-position arithmetic and the shadow-
  // markup mapping are unchanged. Flexmark then emits a Code node covering
  // the region, which the visit() pipeline replaces with a Dummy token in
  // plain text, so LanguageTool never sees the surrounding quote
  // characters and stops flagging the trailing `'` as an unpaired quote
  // (EN_UNPAIRED_QUOTES) or otherwise corrupting matches that overlap the
  // identifier. Opt-in: callers wrapping Markdown around a Lisp-family
  // comment block where the convention is canonical (currently
  // ProgramAnnotatedTextBuilder for elisp / emacs-lisp) set this true;
  // pure Markdown documents leave it false so a literal `` `foo' `` keeps
  // its original meaning.
  private val enableEmacsQuoteRewriting: Boolean = false,
  // When true, `addComment` additionally neutralises Emacs *docstring* markup
  // directives so they do not surface as grammar/spelling false positives:
  // key substitutions (`\\[command]`, `\\{keymap}`, `\\<keymap>`), the
  // self-quoting escape `\\=` (including the `` `symbol\\=' `` form), and a
  // leading `*` user-variable marker handled by the caller. Every replacement
  // is length-preserving, so the shadow-markup offset arithmetic that maps
  // matches back to the original source stays valid. Opt-in: only
  // ElispAnnotatedTextBuilder sets this true (for elisp / emacs-lisp); it is
  // independent of `enableEmacsQuoteRewriting` and both may be enabled
  // together.
  private val enableElispDocstringDirectives: Boolean = false,
) : CodeAnnotatedTextBuilder(codeLanguageId) {
  private val parser: Parser = Parser.builder(PARSER_OPTIONS).build()
  private var code = ""
  private var pos = 0
  private var firstCellInTableRow = false
  private val nodeTypeStack = ArrayDeque<String>()
  private var shadowMarkups = listOf<Triple<String, Int, Int>>()
  private var shadowOffset = 0
  private val nodeSignatures: MutableList<MarkdownNodeSignature> =
    ArrayList(
      MarkdownAnnotatedTextBuilderDefaults.DEFAULT_MARKDOWN_NODE_SIGNATURES,
    )

  private fun isInNodeType(nodeType: String): Boolean = this.nodeTypeStack.contains(nodeType)

  private fun isInIgnoredNodeType(): Boolean {
    var result = false

    for (nodeType: String in nodeTypeStack) {
      for (nodeSignature: MarkdownNodeSignature in this.nodeSignatures) {
        if (nodeSignature.name == nodeType) {
          result = (nodeSignature.action == MarkdownNodeSignature.Action.Ignore)
        }
      }
    }

    return result
  }

  private fun isDummyNodeType(nodeType: String): Boolean {
    var result = false

    for (nodeSignature: MarkdownNodeSignature in this.nodeSignatures) {
      if (nodeSignature.name == nodeType) {
        result = (nodeSignature.action == MarkdownNodeSignature.Action.Dummy)
      }
    }

    return result
  }

  private fun addMarkup(finalPos: Int) {
    var newPos = finalPos

    val inParagraph: Boolean = isInNodeType("Paragraph")

    while ((this.pos < this.code.length) && (this.pos < newPos)) {
      var curPos: Int = this.code.indexOf("\r\n", this.pos)
      if (curPos != -1) curPos += 1

      if ((curPos == -1) || (curPos >= newPos)) {
        curPos = this.code.indexOf('\n', this.pos)
        if ((curPos == -1) || (curPos >= newPos)) break
      }

      if (curPos > this.pos) super.addMarkup(this.code.substring(this.pos, curPos))
      this.pos = curPos
      val tmpShadowOffset = shadowOffset
      if (removeComment()) {
        newPos += (shadowOffset - tmpShadowOffset)
        if (newPos > this.code.length) newPos = this.code.length
      } else {
        super.addMarkup(this.code.substring(curPos, curPos + 1), (if (inParagraph) " " else "\n"))
        this.pos += 1
      }
    }

    if (newPos > pos) {
      super.addMarkup(this.code.substring(this.pos, newPos))
      this.pos = newPos
      removeComment()
    }
  }

  private fun addMarkup(
    node: Node,
    interpretAs: String,
  ) {
    addMarkup(node.startOffset + shadowOffset)
    val newPos: Int = node.endOffset + shadowOffset
    super.addMarkup(this.code.substring(this.pos, newPos), interpretAs)
    this.pos = newPos
  }

  private fun addText(newPos: Int) {
    if (newPos > pos) {
      super.addText(this.code.substring(this.pos, newPos))
      this.pos = newPos
    }
  }

  private fun generateDummy(): String =
    DummyGenerator.getInstance().generate(this.language, this.dummyCounter++)

  override fun addCode(code: String): MarkdownAnnotatedTextBuilder {
    val document: Document = this.parser.parse(code)

    if (Logging.LOGGER.isLoggable(Level.FINEST)) {
      Logging.LOGGER.finest(
        "flexmarkAst = " + AstCollectingVisitor().collectAndGetAstText(document),
      )
    }

    this.code = code
    this.pos = 0
    visitChildren(document)
    if (this.pos < this.code.length) addMarkup(this.code.length)

    return this
  }

  override fun addComment(
    code: Array<String>,
    markups: Array<Triple<String, Int, Int>>,
  ): CodeAnnotatedTextBuilder {
    var fullCode = ""
    var clearCode = ""

    for ((index, markup) in markups.withIndex()) {
      fullCode += markup.first + code[index]
      clearCode += rewriteEmacsQuotesIfEnabled(code[index])
      if (index < markups.lastIndex) {
        clearCode += "\n"
      }
    }

    this.code = fullCode
    this.pos = 0
    this.shadowMarkups = markups.toList()
    this.shadowOffset = 0

    visitChildren(this.parser.parse(clearCode))

    if (this.pos < this.code.length) addMarkup(this.code.length)

    return this
  }

  // Length-preserving substitution: rewrites `name' as `name` (backtick-and-
  // apostrophe → matched-backticks) so flexmark recognises the region as
  // inline code. Only touches `clearCode`; `fullCode` and the shadow-markup
  // mapping continue to reference the original source unchanged. The regex
  // is conservative: identifier body cannot contain backticks, apostrophes,
  // or whitespace, matching the Emacs convention of using this form only
  // around bare symbol names (`lsp-mode'`, `pp-buffer'`, …) and not around
  // longer phrases.
  private fun rewriteEmacsQuotesIfEnabled(segment: String): String {
    var result: String = segment

    if (this.enableEmacsQuoteRewriting) {
      result = result.replace(EMACS_QUOTED_IDENTIFIER_REGEX, "`$1`")
    }

    if (this.enableElispDocstringDirectives) {
      result = neutralizeElispDocstringDirectives(result)
    }

    return result
  }

  // Length-preserving neutralisation of Emacs docstring markup directives. Each
  // directive is rewritten to the same number of characters so the shadow-
  // markup mapping (which assumes `clearCode` and the original source share a
  // length) keeps pointing at the right source offsets. Key substitutions and
  // `` `symbol\\=' `` become flexmark inline code (collapsed to a Dummy token
  // downstream); a bare `\\=` becomes whitespace.
  private fun neutralizeElispDocstringDirectives(segment: String): String {
    var result: String = segment
    result =
      result.replace(ELISP_KEY_SUBSTITUTION_REGEX) {
        lengthPreservingInlineCode(it.value.length)
      }
    result =
      result.replace(ELISP_QUOTED_IDENTIFIER_WITH_ESCAPE_REGEX) {
        lengthPreservingInlineCode(it.value.length)
      }
    result =
      result.replace(ELISP_QUOTE_ESCAPE_REGEX) {
        " ".repeat(it.value.length)
      }
    return result
  }

  private fun lengthPreservingInlineCode(length: Int): String =
    when {
      length <= 0 -> ""
      length == 1 -> "`"
      length == 2 -> "``"
      else -> "`" + "x".repeat(length - 2) + "`"
    }

  private fun visit(node: Node) {
    val nodeType: String = node.javaClass.simpleName

    if ((nodeType == "TableRow")) {
      this.firstCellInTableRow = true
    } else if ((nodeType == "TableCell")) {
      if (this.firstCellInTableRow) {
        this.firstCellInTableRow = false
      } else {
        super.addMarkup("", " ")
      }
    }

    if (isInIgnoredNodeType()) {
      addMarkup(node.endOffset + shadowOffset)
    } else if (isDummyNodeType(nodeType)) {
      addMarkup(node, generateDummy())
    } else if (nodeType == "Text") {
      addMarkup(node.startOffset + shadowOffset)
      addText(node.endOffset + shadowOffset)
    } else if (nodeType == "HtmlEntity") {
      addMarkup(node, Escaping.unescapeHtml(node.chars))
    } else {
      if (nodeType == "Paragraph") {
        addMarkup(node.startOffset + shadowOffset)
      } else if (nodeType == "FencedCodeBlock") {
        val block = node as FencedCodeBlock
        addMarkup(pos + block.openingMarker.count() + block.info.count())
      }
      this.nodeTypeStack.addLast(nodeType)
      visitChildren(node)
      this.nodeTypeStack.removeLastOrNull()
      if (nodeType == "FencedCodeBlock") {
        addMarkup(pos + (node as FencedCodeBlock).closingMarker.count() - 1)
      }
      if (nodeType == "DefinitionTerm") super.addMarkup("", ".")
    }
  }

  private fun visitChildren(node: Node) {
    for (child: Node in node.children) {
      removeComment()
      visit(child)
    }
  }

  private fun removeComment(): Boolean {
    var removed = false

    while (shadowMarkups.isNotEmpty()) {
      val shadowMarkup = shadowMarkups.first()

      if (shadowMarkup.second == pos) {
        removed = true

        super.addMarkup(shadowMarkup.first, "\n")

        val offset = shadowMarkup.third - shadowMarkup.second
        val firstChar = shadowMarkup.first.firstOrNull()
        // we add new line in markdown code
        shadowOffset +=
          if (firstChar == '\n' || firstChar == '\r') {
            offset - 1
          } else {
            offset
          }

        pos += offset
        shadowMarkups = shadowMarkups.drop(1)
      } else {
        break
      }
    }

    return removed
  }

  override fun setSettings(settings: Settings) {
    super.setSettings(settings)

    for ((nodeName: String, actionString: String) in settings.markdownNodes) {
      var dummyGenerator: DummyGenerator = DummyGenerator.getInstance()

      val action: MarkdownNodeSignature.Action =
        when (actionString) {
          "default" -> {
            MarkdownNodeSignature.Action.Default
          }

          "ignore" -> {
            MarkdownNodeSignature.Action.Ignore
          }

          "dummy", "pluralDummy", "vowelDummy" -> {
            val plural: Boolean = (actionString == "pluralDummy")
            val vowel: Boolean = (actionString == "vowelDummy")
            dummyGenerator = DummyGenerator.getInstance(plural = plural, vowel = vowel)
            MarkdownNodeSignature.Action.Dummy
          }

          else -> {
            continue
          }
        }

      this.nodeSignatures.add(MarkdownNodeSignature(nodeName, action, dummyGenerator))
    }
  }

  companion object {
    // Matches Emacs-style quoted identifiers: a backtick, an identifier-like
    // body (no whitespace, no backticks, no apostrophes), and a closing
    // straight apostrophe. The body group is captured so the rewrite can
    // wrap it in matched backticks for flexmark.
    private val EMACS_QUOTED_IDENTIFIER_REGEX: Regex = Regex("`([^`'\\s]+)'")

    // Emacs docstring key substitutions `\\[command]`, `\\{keymap}` and
    // `\\<keymap>`. In source the backslash is doubled (the elisp string holds
    // a single backslash), so the pattern matches two literal backslashes.
    private val ELISP_KEY_SUBSTITUTION_REGEX: Regex = Regex("""\\\\[\[{<][^\]}>\n]*[\]}>]""")

    // The `` `symbol\\=' `` form: a quoted identifier whose closing apostrophe
    // is preceded by the self-quoting `\\=` escape. Handled before the bare
    // `\\=` rule so the whole construct collapses to a single inline-code span.
    private val ELISP_QUOTED_IDENTIFIER_WITH_ESCAPE_REGEX: Regex = Regex("""`[^`'\s]+\\\\='""")

    // A bare self-quoting escape `\\=` (two source backslashes plus `=`).
    private val ELISP_QUOTE_ESCAPE_REGEX: Regex = Regex("""\\\\=""")

    private val PARSER_OPTIONS: DataHolder =
      MutableDataSet()
        // CommonMark allows spaces inside angle-bracketed link destinations
        // (e.g. `[text](<file with spaces.pdf>)`). Flexmark gates this behind
        // an off-by-default option; without it the link is not recognized
        // and the surrounding `[` / `(` leak into spell-checked text.
        .set(Parser.SPACE_IN_LINK_URLS, true)
        .set(
          Parser.EXTENSIONS,
          listOf(
            DefinitionExtension.create(),
            GitLabExtension.create(),
            LtexMarkdownExtension.create(),
            StrikethroughExtension.create(),
            TablesExtension.create(),
            YamlFrontMatterExtension.create(),
          ),
        )
  }
}
