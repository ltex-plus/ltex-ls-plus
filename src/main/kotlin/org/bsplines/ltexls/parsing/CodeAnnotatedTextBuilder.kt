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

  // Dummy-token state, shared by every subclass so that markup dummies (e.g.
  // inline math `$x$`) and dictionary-masking dummies draw from one monotonic
  // counter — two identical adjacent dummy tokens would otherwise trip
  // LanguageTool's repeated-word rule.
  protected var language: String = "en-US"
  protected var dummyGenerator: DummyGenerator = DummyGenerator.getInstance()
  protected var dummyCounter = 0

  // null when the per-language dictionary is empty (the common case), so the
  // TEXT fast path stays a single super.addText() with zero overhead.
  protected var dictionaryMasker: DictionaryMasker? = null

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

  open fun setSettings(settings: Settings) {
    this.language = settings.languageShortCode
    // Normally mask only the current language's dictionary. Under
    // ltex.language="auto" the language is resolved later (server-side on the
    // HTTP path), so settings.dictionary — keyed on the literal "auto" — is
    // empty at build time; fall back to masking the union of every language's
    // entries so a user-added word is still honoured on the first check.
    val dictionary: Set<String> =
      if (settings.languageShortCode == "auto") {
        settings.allDictionaries.values
          .flatten()
          .toSet()
      } else {
        settings.dictionary
      }
    val masker = DictionaryMasker(dictionary)
    this.dictionaryMasker = if (masker.isEmpty) null else masker
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
      emitTextWithDictionaryMasking(curText.toString())
      curText.clear()
    }
  }

  // Emit a finalized TEXT part, masking any user-dictionary occurrences as
  // markup with a dummy interpretation (the same mechanism inline math `$x$`
  // uses), so LanguageTool never sees the dictionary word — single- and
  // multi-word entries alike — while a real typo after a masked span still
  // reprojects to the correct source position (the dummy contributes plain-text
  // length but zero source offset).
  private fun emitTextWithDictionaryMasking(text: String) {
    val masker: DictionaryMasker? = this.dictionaryMasker

    if (masker == null) {
      super.addText(text)
      return
    }

    for (segment: DictionaryMasker.Segment in masker.split(text)) {
      if (segment.masked) {
        super.addMarkup(
          segment.text,
          this.dummyGenerator.generate(this.language, this.dummyCounter++),
        )
      } else {
        super.addText(segment.text)
      }
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
