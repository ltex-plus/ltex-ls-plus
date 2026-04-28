/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import kotlin.test.Test
import kotlin.test.assertTrue

class CompletionListProviderTest {
  @Test
  fun testCreateCompletionList() {
    val languageServer = LtexLanguageServer()
    languageServer.settingsManager.settings =
      languageServer.settingsManager.settings.copy(
        _allDictionaries = mapOf(Pair("en-US", setOf("testfoobar"))),
      )

    val document =
      LtexTextDocumentItem(languageServer, "untitled:test.md", "markdown", 1, "This is a test.\n")
    val completionList: CompletionList =
      languageServer.completionListProvider.createCompletionList(document, Position(0, 14))

    assertTrue(completionList.items.size >= 10)
    var containsDictionaryWord = false

    for (completionItem: CompletionItem in completionList.items) {
      val entry: String = completionItem.label
      assertTrue(entry.startsWith("test"))
      if (entry == "testfoobar") containsDictionaryWord = true
    }

    assertTrue(containsDictionaryWord)
  }

  @Test
  fun testCreateCompletionListAtEndOfDocument() {
    // Regression: a cursor at the very last position of a document (no
    // trailing whitespace) used to return an empty list.
    val languageServer = LtexLanguageServer()
    val document =
      LtexTextDocumentItem(languageServer, "untitled:test.md", "markdown", 1, "wonder")
    val completionList: CompletionList =
      languageServer.completionListProvider.createCompletionList(document, Position(0, 6))

    assertTrue(completionList.items.isNotEmpty())
    for (completionItem: CompletionItem in completionList.items) {
      assertTrue(completionItem.label.startsWith("wonder"))
    }
  }
}
