/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.parsing.asciidoc.AsciiDocAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.gitcommit.GitCommitAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.html.HtmlAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.latex.LatexAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.markdown.MarkdownAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.nop.NopAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.org.OrgAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.plaintext.PlaintextAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.program.ElispAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.program.ProgramAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.program.ProgramCommentRegexs
import org.bsplines.ltexls.parsing.restructuredtext.RestructuredtextAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.typst.TypstAnnotatedTextBuilder
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.languagetool.markup.AnnotatedText
import org.languagetool.markup.AnnotatedTextBuilder
import org.languagetool.markup.TextPart

abstract class CodeAnnotatedTextBuilder(
  val codeLanguageId: String,
) : AnnotatedTextBuilder() {
  protected var curText = StringBuilder()
  protected var curMarkup = StringBuilder()
  protected var curInterpretAs = StringBuilder()
  protected var curType: TextPart.Type? = null

  abstract fun addCode(code: String): CodeAnnotatedTextBuilder

  open fun addComment(
    code: Array<String>,
    markups: Array<Triple<String, Int, Int>>,
  ): CodeAnnotatedTextBuilder {
    for ((index, markup) in markups.withIndex()) {
      this.addMarkup(markup.first, "\n")
      this.addCode(code[index])
    }

    return this
  }

  @Suppress("UNUSED_PARAMETER")
  open fun setSettings(settings: Settings) {
  }

  override fun addText(text: String?): CodeAnnotatedTextBuilder {
    if (text?.isNotEmpty() == true) {
      if (curType == TextPart.Type.MARKUP) {
        finalizeCurrentPart()
      }
      curType = TextPart.Type.TEXT
      curText.append(text)
    }

    return this
  }

  override fun addMarkup(markup: String?): CodeAnnotatedTextBuilder {
    if (markup?.isNotEmpty() == true) {
      if (curType == TextPart.Type.TEXT) {
        finalizeCurrentPart()
      }
      curType = TextPart.Type.MARKUP
      curMarkup.append(markup)
    }

    return this
  }

  override fun addMarkup(
    markup: String?,
    interpretAs: String?,
  ): CodeAnnotatedTextBuilder {
    if (interpretAs?.isNotEmpty() == true) {
      if (curType == TextPart.Type.TEXT) {
        finalizeCurrentPart()
      }
      curType = TextPart.Type.MARKUP
      curMarkup.append(markup ?: "")
      curInterpretAs.append(interpretAs)
    } else {
      addMarkup(markup)
    }

    return this
  }

  override fun build(): AnnotatedText {
    finalizeCurrentPart()
    return super.build()
  }

  private fun finalizeCurrentPart() {
    if (curType == TextPart.Type.MARKUP) {
      super.addMarkup(curMarkup.toString(), curInterpretAs.toString())
      curMarkup.clear()
      curInterpretAs.clear()
    }
    if (curType == TextPart.Type.TEXT) {
      super.addText(curText.toString())
      curText.clear()
    }
  }

  companion object {
    @Suppress("ComplexMethod")
    fun create(codeLanguageId: String): CodeAnnotatedTextBuilder =
      when (codeLanguageId) {
        "bib",
        "bibtex",
        -> {
          LatexAnnotatedTextBuilder(codeLanguageId)
        }

        "git-commit",
        "gitcommit",
        -> {
          GitCommitAnnotatedTextBuilder(codeLanguageId)
        }

        "html",
        "xhtml",
        -> {
          HtmlAnnotatedTextBuilder(codeLanguageId)
        }

        "context",
        "context.tex",
        "latex",
        "plaintex",
        "rsweave",
        "tex",
        -> {
          LatexAnnotatedTextBuilder(codeLanguageId)
        }

        "elisp",
        "emacs-lisp",
        -> {
          ElispAnnotatedTextBuilder(codeLanguageId)
        }

        "markdown",
        "mdx",
        "quarto",
        "rmd",
        -> {
          MarkdownAnnotatedTextBuilder(codeLanguageId)
        }

        "nop" -> {
          NopAnnotatedTextBuilder(codeLanguageId)
        }

        "org",
        "neorg",
        -> {
          OrgAnnotatedTextBuilder(codeLanguageId)
        }

        "plaintext" -> {
          PlaintextAnnotatedTextBuilder(codeLanguageId)
        }

        "restructuredtext" -> {
          RestructuredtextAnnotatedTextBuilder(codeLanguageId)
        }

        "typst" -> {
          TypstAnnotatedTextBuilder(codeLanguageId)
        }

        "asciidoc" -> {
          AsciiDocAnnotatedTextBuilder(codeLanguageId)
        }

        else -> {
          if (ProgramCommentRegexs.isSupportedCodeLanguageId(codeLanguageId)) {
            ProgramAnnotatedTextBuilder(codeLanguageId)
          } else {
            Logging.LOGGER.warning(I18n.format("unsupportedCodeLanguageId", codeLanguageId))
            PlaintextAnnotatedTextBuilder("plaintext")
          }
        }
      }
  }
}
