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

  // Entries from settings plus format-specific entries discovered while parsing.
  // The masker is created in build(), after subclasses have seen the whole document.
  private var configuredDictionary: Set<String> = emptySet()
  private val additionalDictionaryEntries = mutableSetOf<String>()

  // Finalized parts awaiting emission into LanguageTool's AnnotatedTextBuilder.
  // Emission is deferred to build() so the dictionary masker can match entries
  // over the *assembled* plain text — a phrase wrapped over a Markdown soft
  // line break or a word containing a LaTeX accent command is contiguous only
  // there, never within a single TEXT part. Nothing observes the underlying
  // builder's state before build(), so the deferral is invisible.
  private val parts = mutableListOf<DictionaryMasker.Part>()

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
    this.configuredDictionary =
      if (settings.languageShortCode == "auto") {
        settings.allDictionaries.values
          .flatten()
          .toSet()
      } else {
        settings.dictionary
      }
  }

  protected fun addDictionaryEntry(entry: String) {
    additionalDictionaryEntries.add(entry)
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

  // Mask dictionary occurrences as markup with a dummy interpretation (the same
  // mechanism inline math `$x$` uses), so LanguageTool never sees the accepted
  // word while diagnostics after a masked span still map to the right source.
  override fun build(): AnnotatedText {
    finalizeCurrentPart()

    val dictionaryMasker: DictionaryMasker? =
      when {
        configuredDictionary.isEmpty() && additionalDictionaryEntries.isEmpty() -> null
        additionalDictionaryEntries.isEmpty() -> DictionaryMasker(configuredDictionary)
        configuredDictionary.isEmpty() -> DictionaryMasker(additionalDictionaryEntries)
        else -> DictionaryMasker(configuredDictionary + additionalDictionaryEntries)
      }

    // Always the default (consonant-initial) dummy: the vowel variant "Ina<n>"
    // is itself flagged by LanguageTool Premium's AI rules, which would surface
    // a diagnostic exactly on the masked dictionary word (caught by
    // LanguageToolPremiumIntegrationTest).
    val outParts: List<DictionaryMasker.Part> =
      dictionaryMasker?.maskParts(this.parts) {
        this.dummyGenerator.generate(this.language, this.dummyCounter++)
      } ?: this.parts

    for (part: DictionaryMasker.Part in outParts) {
      if (part.type == TextPart.Type.TEXT) {
        super.addText(part.code)
      } else {
        super.addMarkup(part.code, part.interpretAs)
      }
    }

    return super.build()
  }

  private fun finalizeCurrentPart() {
    if (curType == TextPart.Type.MARKUP) {
      parts.add(
        DictionaryMasker.Part(
          TextPart.Type.MARKUP,
          curMarkup.toString(),
          curInterpretAs.toString(),
        ),
      )
      curMarkup.clear()
      curInterpretAs.clear()
    }
    if (curType == TextPart.Type.TEXT) {
      parts.add(DictionaryMasker.Part(TextPart.Type.TEXT, curText.toString()))
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
