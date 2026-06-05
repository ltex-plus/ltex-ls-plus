/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.parsing.asciidoc.AsciiDocFragmentizer
import org.bsplines.ltexls.parsing.bibtex.BibtexFragmentizer
import org.bsplines.ltexls.parsing.gitcommit.GitCommitFragmentizer
import org.bsplines.ltexls.parsing.html.HtmlFragmentizer
import org.bsplines.ltexls.parsing.latex.LatexFragmentizer
import org.bsplines.ltexls.parsing.markdown.MarkdownFragmentizer
import org.bsplines.ltexls.parsing.nop.NopFragmentizer
import org.bsplines.ltexls.parsing.org.OrgFragmentizer
import org.bsplines.ltexls.parsing.plaintext.PlaintextFragmentizer
import org.bsplines.ltexls.parsing.program.ElispFragmentizer
import org.bsplines.ltexls.parsing.program.ProgramCommentRegexs
import org.bsplines.ltexls.parsing.program.ProgramFragmentizer
import org.bsplines.ltexls.parsing.restructuredtext.RestructuredtextFragmentizer
import org.bsplines.ltexls.parsing.typst.TypstFragmentizer
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging

abstract class CodeFragmentizer(
  val codeLanguageId: String,
) {
  abstract fun fragmentize(
    code: String,
    originalSettings: Settings,
  ): List<CodeFragment>

  fun fragmentize(fragments: List<CodeFragment>): List<CodeFragment> {
    val newFragments = ArrayList<CodeFragment>()

    for (oldFragment: CodeFragment in fragments) {
      val curNewFragments: List<CodeFragment> = fragmentize(oldFragment.code, oldFragment.settings)

      for (newFragment: CodeFragment in curNewFragments) {
        newFragments.add(newFragment.copy(fromPos = newFragment.fromPos + oldFragment.fromPos))
      }
    }

    return newFragments
  }

  companion object {
    @Suppress("ComplexMethod")
    fun create(codeLanguageId: String): CodeFragmentizer =
      when (codeLanguageId) {
        "asciidoc" -> {
          AsciiDocFragmentizer(codeLanguageId)
        }

        "bib",
        "bibtex",
        -> {
          BibtexFragmentizer(codeLanguageId)
        }

        "elisp",
        "emacs-lisp",
        -> {
          ElispFragmentizer(codeLanguageId)
        }

        "git-commit",
        "gitcommit",
        -> {
          GitCommitFragmentizer(codeLanguageId)
        }

        "html",
        "xhtml",
        -> {
          HtmlFragmentizer(codeLanguageId)
        }

        "context",
        "context.tex",
        "latex",
        "plaintex",
        "rsweave",
        "tex",
        -> {
          LatexFragmentizer(codeLanguageId)
        }

        "markdown",
        "mdx",
        "quarto",
        "rmd",
        -> {
          MarkdownFragmentizer(codeLanguageId)
        }

        "nop" -> {
          NopFragmentizer(codeLanguageId)
        }

        "neorg" -> {
          OrgFragmentizer(codeLanguageId)
        }

        "org" -> {
          OrgFragmentizer(codeLanguageId)
        }

        "plaintext" -> {
          PlaintextFragmentizer(codeLanguageId)
        }

        "restructuredtext" -> {
          RestructuredtextFragmentizer(codeLanguageId)
        }

        "typst" -> {
          TypstFragmentizer(codeLanguageId)
        }

        else -> {
          if (ProgramCommentRegexs.isSupportedCodeLanguageId(codeLanguageId)) {
            ProgramFragmentizer(codeLanguageId)
          } else {
            Logging.LOGGER.warning(I18n.format("unsupportedCodeLanguageId", codeLanguageId))
            PlaintextFragmentizer("plaintext")
          }
        }
      }
  }
}
