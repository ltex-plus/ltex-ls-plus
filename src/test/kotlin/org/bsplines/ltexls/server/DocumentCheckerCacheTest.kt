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

  // paragraphCacheEnabled = false disables only result reuse: nothing is stored
  // (cache stays empty) and a re-check rebuilds every fragment from scratch (no
  // === reuse). Slicing and maxRequestSize batching still run. A multi-paragraph
  // document with a small maxRequestSize forces the disabled path through the same
  // slice -> batch -> check machinery the cache uses.
  //
  // Spelling and grammar are batch-neutral and must be identical regardless of
  // caching. A few stylistic rules that span a sequence of sentences (e.g.
  // ENGLISH_WORD_REPEAT_BEGINNING_RULE) see neighbours only within one request, so
  // they can fire differently depending on batching boundaries. This is accepted
  // (see PR.md "Is it safe?"): batching is mandatory anyway — an oversized single
  // request is rejected by the HTTP backend — and the drift is limited to
  // non-spelling/non-grammar stylistic matches, mostly on a document's first scan.
  @Test
  fun testParagraphCacheDisabledStillSlicesBatchesAndChecks() {
    val code =
      "This is qwertyunknownone.\n\nThis is qwertyunknowntwo.\n\nThis is qwertyunknownthree.\n"

    val cache = FragmentCache()
    val checker =
      DocumentChecker(
        SettingsManager(
          Settings(
            _paragraphCacheEnabled = false,
            _maxRequestSize = 30,
            _logLevel = Level.FINEST,
          ),
        ),
        cache,
      )
    val document = createDocument("untitled:nocache.md", "markdown", code)

    val first = checker.check(document)
    val second = checker.check(document)

    assertEquals(0, cache.size, "cache must stay empty when disabled")
    assertEquals(3, first.second.size, "the region must still slice into 3 paragraphs")
    assertTrue(
      first.first.size >= 3,
      "every paragraph's typo must be flagged (slicing + batching still ran)",
    )
    // Spelling/grammar diagnostics are batch-neutral, so they must match the
    // cache-enabled path regardless of how paragraphs are batched into requests.
    // (Cross-paragraph stylistic rules are deliberately NOT compared — see above.)
    assertEquals(
      spellingKeys(checkEnabled(code)),
      spellingKeys(first),
      "spelling diagnostics must be identical regardless of caching or batching",
    )
    // Each check rebuilds every fragment (no reuse), and re-checking is stable.
    assertEquals(first.first.size, second.first.size)
    assertTrue(
      first.second[0].annotatedText !== second.second[0].annotatedText,
      "without the cache, each check must rebuild the fragment",
    )
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
        SettingsManager(Settings(_logLevel = Level.FINEST)),
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
        SettingsManager(Settings(_logLevel = Level.FINEST)),
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
        SettingsManager(Settings(_logLevel = Level.FINEST)),
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

  // Paragraphs in different magic-command language regions must never be bundled
  // into the same merged request (and so never checked/cached under one shared
  // language). A `% ltex: language=de-DE` command splits the document into two
  // CodeFragments; because the merge loop runs per CodeFragment, an English and a
  // German paragraph can never land in one request — the language boundary is the
  // merge boundary. Each region here has two paragraphs and maxRequestSize is
  // huge, so each region becomes one merged multi-paragraph request. The German
  // speller rule firing on the German word proves the German region was checked as
  // German (the English checker has no such rule), and every fragment carries its
  // own region's language — neither region's paragraphs were stamped with the
  // other's language.
  @Test
  fun testMagicCommandLanguagesNotBundledInMergedRun() {
    val code =
      "This is qwertyunknown.\n\nThis is a clean sentence.\n\n" +
        "% ltex: language=de-DE\nDies ist Qwertyzuiopc.\n\nDies ist ein sauberer Satz.\n"

    val result = check("latex", code, 1_000_000)

    // German region was checked as German, not folded into the English request.
    assertEquals("Qwertyzuiopc", spanText(result, germanMatch(result.first)))
    assertEquals(
      "de-DE",
      germanFragment(result.second).codeFragment.languageShortCode,
      "German paragraphs must stay under de-DE",
    )
    val englishFragment: AnnotatedTextFragment =
      result.second.first { it.codeFragment.code.contains("qwertyunknown") }
    assertEquals(
      "en-US",
      englishFragment.codeFragment.languageShortCode,
      "English paragraphs must stay under en-US",
    )
  }

  // With ltex.language="auto" on the Java backend, language is detected once on the
  // merged text of a multi-paragraph miss-run and that one concrete variant is
  // stamped on every paragraph in the run (SimpleLanguageIdentifier returns bare
  // codes, promoted to the preferredVariants default en-US). maxRequestSize is huge
  // so the whole English region is one merged request. All resulting fragments must
  // carry en-US (not the literal "auto"), proving the detected language propagated to
  // each paragraph, and the spell error must still be found.
  @Test
  fun testAutoLanguageDetectedOnMergedRunPropagatesToAllParagraphs() {
    val checker =
      DocumentChecker(
        SettingsManager(Settings(_languageShortCode = "auto", _logLevel = Level.FINEST)),
        FragmentCache(),
      )
    val code =
      "This is a perfectly ordinary English paragraph with enough words for the " +
        "detector to lock on, and it also contains qwertyunknown as a typo.\n\n" +
        "Here is a second ordinary English paragraph that the detector should " +
        "recognize without any trouble at all.\n"
    val result = checker.check(createDocument("untitled:auto.md", "markdown", code))

    assertTrue(result.second.size >= 2, "the region should slice into >= 2 paragraphs")
    for (fragment: AnnotatedTextFragment in result.second) {
      assertEquals(
        "en-US",
        fragment.codeFragment.languageShortCode,
        "every paragraph in the merged auto run must carry the detected variant",
      )
    }
    assertTrue(
      result.first.any { it.isUnknownWordRule() },
      "the typo must still be flagged after merged-run auto detection",
    )
  }

  // After a merged auto run, the per-paragraph cache entries are keyed under the
  // detected variant, so editing one paragraph reuses the untouched one (same
  // AnnotatedText instance) without re-detecting or re-checking it, and it still
  // carries en-US.
  @Test
  fun testAutoLanguageMergedRunCachesPerParagraphForReuse() {
    val checker =
      DocumentChecker(
        SettingsManager(Settings(_languageShortCode = "auto", _logLevel = Level.FINEST)),
        FragmentCache(),
      )
    val uri = "untitled:auto-reuse.md"
    val tail =
      "\n\nHere is a second ordinary English paragraph that stays the same across " +
        "both checks and should be reused from the cache.\n"
    val first =
      "This is the first ordinary English paragraph with plenty of words to detect.$tail"
    val second =
      "This is the first ordinary English paragraph, now edited and even longer.$tail"

    val before = checker.check(createDocument(uri, "markdown", first))
    val after = checker.check(createDocument(uri, "markdown", second))

    val stayingBefore = before.second.first { it.codeFragment.code.contains("stays the same") }
    val stayingAfter = after.second.first { it.codeFragment.code.contains("stays the same") }
    assertSame(
      stayingBefore.annotatedText,
      stayingAfter.annotatedText,
      "the unchanged paragraph must be reused after editing the other",
    )
    assertEquals("en-US", stayingAfter.codeFragment.languageShortCode)
  }

  companion object {
    private fun sharedChecker(): DocumentChecker =
      DocumentChecker(SettingsManager(Settings(_logLevel = Level.FINEST)), FragmentCache())

    private fun checkMarkdown(
      code: String,
      maxRequestSize: Int,
    ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      check("markdown", code, maxRequestSize)

    // maxRequestSize = 1 forces one request per paragraph (split); a huge value
    // batches the whole document into one merged request (whole). The two must
    // give identical diagnostics, exercising the merged-match decomposition.
    private fun check(
      codeLanguageId: String,
      code: String,
      maxRequestSize: Int,
    ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
      val checker =
        DocumentChecker(
          SettingsManager(Settings(_maxRequestSize = maxRequestSize, _logLevel = Level.FINEST)),
          FragmentCache(),
        )
      return checker.check(createDocument("untitled:safety.$codeLanguageId", codeLanguageId, code))
    }

    private fun matchKeys(
      result: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>>,
    ): List<Triple<String?, Int, Int>> =
      result.first.map { Triple(it.ruleId, it.fromPos, it.toPos) }.sortedBy { it.second }

    // Like matchKeys but only unknown-word (spelling) matches, which are
    // batch-neutral — unaffected by how paragraphs are grouped into requests.
    private fun spellingKeys(
      result: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>>,
    ): List<Triple<String?, Int, Int>> =
      result.first
        .filter { it.isUnknownWordRule() }
        .map { Triple(it.ruleId, it.fromPos, it.toPos) }
        .sortedBy { it.second }

    private fun createDocument(
      uri: String,
      codeLanguageId: String,
      code: String,
    ): LtexTextDocumentItem =
      LtexTextDocumentItem(LtexLanguageServer(), uri, codeLanguageId, 1, code)

    // Checks a markdown document with the cache enabled (default settings), for
    // comparing diagnostics against a cache-disabled run.
    private fun checkEnabled(
      code: String,
    ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
      val checker = DocumentChecker(SettingsManager(Settings()), FragmentCache())
      return checker.check(createDocument("untitled:enabled.md", "markdown", code))
    }

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
