/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.parsing.FragmentCache
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentCheckerCacheTest {
  // A re-check of an unchanged document reuses every fragment's result: the
  // cached AnnotatedText instances come back identical (===), proving neither
  // the parse nor the LanguageTool call re-ran.
  @Test
  fun testReuseOnIdenticalRecheck() {
    val checker = sharedChecker()
    val document = createDocument("untitled:reuse.md", "markdown", "This is qwertyunknown.\n")

    val first = checker.check(document)
    val second = checker.check(document)

    assertEquals(first.second.size, second.second.size)
    for (i in first.second.indices) {
      assertSame(
        first.second[i].annotatedText,
        second.second[i].annotatedText,
        "fragment $i should be served from cache",
      )
    }
    assertEquals(first.first.size, second.first.size)
  }

  // Inserting text above an unchanged fragment keeps that fragment a cache hit
  // (same AnnotatedText instance) while its match offsets are reprojected to the
  // new absolute position.
  @Test
  fun testOffsetReprojectionAfterEditAbove() {
    val checker = sharedChecker()
    val uri = "untitled:reproject.tex"
    val tail = "% ltex: language=de-DE\nDies ist Qwertyzuiopc.\n"

    val before = checker.check(createDocument(uri, "latex", "This is qwertyzuiopa.\n$tail"))
    val after =
      checker.check(createDocument(uri, "latex", "This is qwertyzuiopa and qwertyzuiopb.\n$tail"))

    val germanBefore: AnnotatedTextFragment = germanFragment(before.second)
    val germanAfter: AnnotatedTextFragment = germanFragment(after.second)
    assertSame(
      germanBefore.annotatedText,
      germanAfter.annotatedText,
      "the unchanged German fragment must be reused",
    )

    // The reprojected match still points at the German word in both versions.
    assertEquals("Qwertyzuiopc", spanText(before, germanMatch(before.first)))
    assertEquals("Qwertyzuiopc", spanText(after, germanMatch(after.first)))
  }

  // Adding the flagged word to the dictionary changes the settings fingerprint,
  // so the stale (match-present) cache entry is not reused and the squiggle goes
  // away. Guards against the stale-after-dictionary-add bug.
  @Test
  fun testDictionaryChangeInvalidates() {
    val checker = sharedChecker()
    val document = createDocument("untitled:dict.md", "markdown", "This is qwertyunknown.\n")

    assertEquals(1, checker.check(document).first.size)

    checker.settingsManager.settings =
      Settings(
        _logLevel = Level.FINEST,
        _allDictionaries = mapOf("en-US" to setOf("qwertyunknown")),
      )

    assertEquals(0, checker.check(document).first.size)
  }

  // didClose drops exactly the closed document's fragments.
  @Test
  fun testDidCloseDropsEntries() {
    val server = LtexLanguageServer()
    val service = LtexTextDocumentService(server)
    val uri = "untitled:close.md"
    val code = "This is qwertyunknown.\n"

    service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "markdown", 1, code)))
    // didOpen does not populate the cache without a connected client; populate it
    // directly through the server's shared checker.
    server.documentChecker.check(LtexTextDocumentItem(server, uri, "markdown", 1, code))
    assertTrue(server.fragmentCache.size > 0)

    service.didClose(DidCloseTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2)))
    assertEquals(0, server.fragmentCache.size)
  }

  // The periodic sweep keeps entries that are still within the (default 30 min)
  // TTL. This exercises the server's sweep path without waiting for the timer.
  @Test
  fun testSweepKeepsFreshEntries() {
    val server = LtexLanguageServer()
    val code = "This is qwertyunknown.\n"
    server.documentChecker.check(
      LtexTextDocumentItem(server, "untitled:sweep.md", "markdown", 1, code),
    )
    val sizeBefore: Int = server.fragmentCache.size
    assertTrue(sizeBefore > 0)

    server.sweepFragmentCache()

    assertEquals(sizeBefore, server.fragmentCache.size)
  }

  // A plaintext document split into paragraphs where editing one paragraph
  // reuses the others' cached results, with offsets reprojected for the shifted
  // survivors.
  @Test
  fun testPlaintextParagraphIncrementalReuse() {
    val checker =
      DocumentChecker(
        SettingsManager(Settings(_minFragmentSize = 1, _logLevel = Level.FINEST)),
        FragmentCache(),
      )
    val uri = "untitled:para.txt"
    val tail = "\n\nSecond paragraph stays the same.\n"

    val before = checker.check(createDocument(uri, "plaintext", "First paragraph.$tail"))
    val after =
      checker.check(createDocument(uri, "plaintext", "First paragraph, now much longer.$tail"))

    assertTrue(before.second.size >= 2)
    val secondBefore = before.second.first { it.codeFragment.code.contains("Second") }
    val secondAfter = after.second.first { it.codeFragment.code.contains("Second") }
    assertSame(
      secondBefore.annotatedText,
      secondAfter.annotatedText,
      "the unchanged second paragraph must be reused after editing the first",
    )
  }

  // Safety guarantee for Markdown block fragmentation: the diagnostics for a
  // document must be identical whether it is checked as one fragment or split at
  // block boundaries. The fenced code block (with a typo and an internal blank
  // line) must stay ignored in both modes — proving block splitting never cuts a
  // block open.
  @Test
  fun testMarkdownBlockSplittingPreservesDiagnostics() {
    val code =
      "This is an eror in prose.\n\n```\nthis is also an eror but in code\n```\n\n" +
        "Another mistteak appears here.\n"

    val whole: List<Triple<String?, Int, Int>> = matchKeys(checkMarkdown(code, 1_000_000))
    val split: List<Triple<String?, Int, Int>> = matchKeys(checkMarkdown(code, 1))

    assertEquals(whole, split)
    assertTrue(whole.isNotEmpty(), "prose typos should be flagged")
    // None of the matches fall inside the fenced code block.
    val fenceStart: Int = code.indexOf("```")
    val fenceEnd: Int = code.lastIndexOf("```") + 3
    assertTrue(
      whole.none {
        it.second in fenceStart until fenceEnd
      },
      "code-block text must not be flagged",
    )
  }

  @Test
  fun testMarkdownBlockIncrementalReuse() {
    val checker =
      DocumentChecker(
        SettingsManager(Settings(_minFragmentSize = 1, _logLevel = Level.FINEST)),
        FragmentCache(),
      )
    val uri = "untitled:blocks.md"
    val tail = "\n\nSecond paragraph stays the same.\n"

    val before = checker.check(createDocument(uri, "markdown", "First paragraph.$tail"))
    val after =
      checker.check(createDocument(uri, "markdown", "First paragraph, now much longer.$tail"))

    val secondBefore = before.second.first { it.codeFragment.code.contains("Second") }
    val secondAfter = after.second.first { it.codeFragment.code.contains("Second") }
    assertSame(
      secondBefore.annotatedText,
      secondAfter.annotatedText,
      "the unchanged second block must be reused after editing the first",
    )
  }

  // Org safety guarantee: identical diagnostics whether checked as one fragment
  // or block-split. The #+BEGIN_SRC block (typo + internal blank line) must stay
  // ignored in both modes — proving block splitting never cuts a source block.
  @Test
  fun testOrgBlockSplittingPreservesDiagnostics() {
    val code =
      "This is an eror in prose.\n\n#+BEGIN_SRC python\nteh eror lives in code\n\nmore code\n" +
        "#+END_SRC\n\nAnother mistteak appears here.\n"

    val whole: List<Triple<String?, Int, Int>> = matchKeys(check("org", code, 1_000_000))
    val split: List<Triple<String?, Int, Int>> = matchKeys(check("org", code, 1))

    assertEquals(whole, split)
    assertTrue(whole.isNotEmpty(), "prose typos should be flagged")
    val srcStart: Int = code.indexOf("#+BEGIN_SRC")
    val srcEnd: Int = code.indexOf("#+END_SRC") + "#+END_SRC".length
    assertTrue(
      whole.none { it.second in srcStart until srcEnd },
      "source-block text must not be flagged",
    )
  }

  // LaTeX safety guarantee: identical diagnostics whether checked as one fragment
  // or block-split, for a full document (\begin{document}...) containing a
  // verbatim block. The verbatim text (typo + internal blank line) stays ignored
  // in both modes — proving splitting never cuts the verbatim open and that
  // splitting inside `document` is sound.
  @Test
  fun testLatexBlockSplittingPreservesDiagnostics() {
    val code =
      "\\documentclass{article}\n\\begin{document}\n\nThis is an eror in prose.\n\n" +
        "\\begin{verbatim}\nteh eror lives in code\n\nmore code\n\\end{verbatim}\n\n" +
        "Another mistteak appears here.\n\n\\end{document}\n"

    val whole: List<Triple<String?, Int, Int>> = matchKeys(check("latex", code, 1_000_000))
    val split: List<Triple<String?, Int, Int>> = matchKeys(check("latex", code, 1))

    assertEquals(whole, split)
    assertTrue(whole.isNotEmpty(), "prose typos should be flagged")
    val verbatimStart: Int = code.indexOf("\\begin{verbatim}")
    val verbatimEnd: Int = code.indexOf("\\end{verbatim}") + "\\end{verbatim}".length
    assertTrue(
      whole.none { it.second in verbatimStart until verbatimEnd },
      "verbatim text must not be flagged",
    )
  }

  @Test
  fun testLatexBlockIncrementalReuse() {
    val checker =
      DocumentChecker(
        SettingsManager(Settings(_minFragmentSize = 1, _logLevel = Level.FINEST)),
        FragmentCache(),
      )
    val uri = "untitled:doc.tex"
    val tail = "\n\nSecond paragraph stays the same.\n"

    val before = checker.check(createDocument(uri, "latex", "First paragraph.$tail"))
    val after =
      checker.check(createDocument(uri, "latex", "First paragraph, now much longer.$tail"))

    val secondBefore = before.second.first { it.codeFragment.code.contains("Second") }
    val secondAfter = after.second.first { it.codeFragment.code.contains("Second") }
    assertSame(
      secondBefore.annotatedText,
      secondAfter.annotatedText,
      "the unchanged second paragraph must be reused after editing the first",
    )
  }

  companion object {
    private fun sharedChecker(): DocumentChecker =
      DocumentChecker(SettingsManager(Settings(_logLevel = Level.FINEST)), FragmentCache())

    private fun checkMarkdown(
      code: String,
      minFragmentSize: Int,
    ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      check("markdown", code, minFragmentSize)

    private fun check(
      codeLanguageId: String,
      code: String,
      minFragmentSize: Int,
    ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
      val checker =
        DocumentChecker(
          SettingsManager(Settings(_minFragmentSize = minFragmentSize, _logLevel = Level.FINEST)),
          FragmentCache(),
        )
      return checker.check(createDocument("untitled:safety.$codeLanguageId", codeLanguageId, code))
    }

    private fun matchKeys(
      result: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>>,
    ): List<Triple<String?, Int, Int>> =
      result.first.map { Triple(it.ruleId, it.fromPos, it.toPos) }.sortedBy { it.second }

    private fun createDocument(
      uri: String,
      codeLanguageId: String,
      code: String,
    ): LtexTextDocumentItem =
      LtexTextDocumentItem(LtexLanguageServer(), uri, codeLanguageId, 1, code)

    private fun germanFragment(fragments: List<AnnotatedTextFragment>): AnnotatedTextFragment =
      fragments.first { it.codeFragment.code.contains("Qwertyzuiopc") }

    private fun germanMatch(matches: List<LanguageToolRuleMatch>): LanguageToolRuleMatch =
      matches.first { it.ruleId == "GERMAN_SPELLER_RULE" }

    private fun spanText(
      result: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>>,
      match: LanguageToolRuleMatch,
    ): String {
      val document: LtexTextDocumentItem = result.second[0].document
      return document.text.substring(match.fromPos, match.toPos)
    }
  }
}
