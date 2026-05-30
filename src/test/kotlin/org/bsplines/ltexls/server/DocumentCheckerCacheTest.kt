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

  companion object {
    private fun sharedChecker(): DocumentChecker =
      DocumentChecker(SettingsManager(Settings(_logLevel = Level.FINEST)), FragmentCache())

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
