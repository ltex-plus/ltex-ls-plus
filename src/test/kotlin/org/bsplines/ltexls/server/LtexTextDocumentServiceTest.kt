/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LtexTextDocumentServiceTest {
  @Test
  fun test() {
    val server = LtexLanguageServer()
    val service = LtexTextDocumentService(server)
    service.completion(CompletionParams())

    val document = TextDocumentItem("untitled:test.md", "markdown", 1, "")
    val versionedDocument = VersionedTextDocumentIdentifier(document.uri, 2)

    service.didOpen(DidOpenTextDocumentParams(document))
    service.didChange(
      DidChangeTextDocumentParams(versionedDocument, listOf(TextDocumentContentChangeEvent("abc"))),
    )
    service.didSave(DidSaveTextDocumentParams(versionedDocument))
    service.didClose(DidCloseTextDocumentParams(versionedDocument))
  }

  @Test
  fun testCompletionEmptyResultIsCompletionListNotBareArray() {
    // Regression: empty completion responses must serialize as
    // `{"isIncomplete":false,"items":[]}` rather than a bare `[]` or
    // `{"isIncomplete":null,"items":[]}`. Per the LSP spec a bare array is already complete,
    // but some non-VS-Code clients misread it as incomplete and keep re-requesting; the
    // explicit object form with `isIncomplete=false` is unambiguous.
    //
    // The serialization to JSON is asserted explicitly because lsp4j's Gson handler
    // distinguishes "field never written" from "field set to false" for primitive booleans —
    // the 1-arg `CompletionList(items)` constructor leaves `isIncomplete` unwritten and yields
    // `null` on the wire even though `getIsIncomplete()` returns `false` from Java code.
    val server = LtexLanguageServer()
    val service = LtexTextDocumentService(server)
    val handler = MessageJsonHandler(emptyMap())

    fun assertEmptyCompletionListOnWire(result: Either<*, *>) {
      assertTrue(result.isRight, "expected CompletionList (Right), got bare array (Left)")
      val responseMessage = ResponseMessage()
      responseMessage.result = result.right as CompletionList
      val json = handler.serialize(responseMessage)
      assertTrue(
        json.contains(""""result":{"isIncomplete":false,"items":[]}"""),
        "expected result with isIncomplete=false, got: $json",
      )
    }

    // 1. completionEnabled=false branch
    assertEmptyCompletionListOnWire(service.completion(CompletionParams()).get())

    // Enable completion for the remaining branches.
    server.settingsManager.settings =
      server.settingsManager.settings.copy(_completionEnabled = true)

    // 2. missing textDocument branch
    assertEmptyCompletionListOnWire(service.completion(CompletionParams()).get())

    // 3. unknown document URI branch
    val unknownDocParams = CompletionParams()
    unknownDocParams.textDocument = TextDocumentIdentifier("untitled:does-not-exist.md")
    assertEmptyCompletionListOnWire(service.completion(unknownDocParams).get())
  }
}
