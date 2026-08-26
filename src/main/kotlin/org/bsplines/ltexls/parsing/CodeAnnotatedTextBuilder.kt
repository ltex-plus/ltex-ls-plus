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

  // Dummy-token state for markup stand-ins (inline math `$x$`, an ignored LaTeX
  // command, a Typst filename), lifted to the base class so every subclass draws
  // from one monotonic counter — two identical adjacent dummy tokens would
  // otherwise trip LanguageTool's repeated-word rule.
  //
  // Dictionary entries do not use this. They are never replaced by an invented
  // token: a single-word entry is left alone, and a multi-word one is joined
  // into a token derived from the user's own text. See build().
  protected var language: String = "en-US"
  protected var dummyGenerator: DummyGenerator = DummyGenerator.getInstance()
  protected var dummyCounter = 0

  // Entries from settings plus format-specific entries discovered while parsing.
  // The masker is created in build(), after subclasses have seen the whole document.
  private var configuredDictionary: Set<String> = emptySet()
  private val additionalDictionaryEntries = mutableSetOf<String>()

  // Entries a subclass discovered while parsing (Typst abbreviation labels).
  // Unlike the configured dictionary these are not in Settings, so the check
  // path cannot look them up there; DocumentChecker carries them on the
  // AnnotatedTextFragment instead. Read after build().
  val additionalDictionary: Set<String>
    get() = this.additionalDictionaryEntries

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

  // Join each multi-word dictionary occurrence into a single token before the
  // text reaches LanguageTool: `GreenTeam Penciltest` is emitted as markup
  // interpreted as `GreenTeamPenciltest`. LanguageTool then reports at most one
  // match, spanning exactly the entry, which the check path suppresses (see
  // LanguageToolInterface.isCoveredByDictionary). Without the join it tokenizes
  // the phrase and flags `Penciltest` alone — a span that matches no entry,
  // which is why phrase entries were not honoured before.
  //
  // Single-word entries are deliberately NOT touched. They need no join, and
  // leaving them in place is what keeps LanguageTool's grammar judgements sound:
  // the words it sees are the user's own, with their real number, gender,
  // initial sound and capitalization. Substituting an invented token (the former
  // `Dummy<n>`) made LanguageTool Premium report the resulting disagreement
  // anywhere in the sentence — an article before it, a verb many words later —
  // where no post-filter can tell an invented complaint from a real one.
  //
  // The joined token is likewise derived from the user's text rather than
  // invented, so it inherits those same properties and only its spelling is
  // novel. Its plain-text length differs from the source, which is exactly what
  // interpretAs exists for; offsets outside the joined span stay exact.
  override fun build(): AnnotatedText {
    finalizeCurrentPart()

    val multiWordEntries: Set<String> =
      (configuredDictionary + additionalDictionaryEntries)
        .filterTo(mutableSetOf()) { it != DictionaryMasker.collapseSeparators(it) }

    val dictionaryMasker: DictionaryMasker? =
      if (multiWordEntries.isEmpty()) null else DictionaryMasker(multiWordEntries)

    val outParts: List<DictionaryMasker.Part> =
      dictionaryMasker?.maskParts(this.parts) { DictionaryMasker.collapseSeparators(it) }
        ?: this.parts

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
