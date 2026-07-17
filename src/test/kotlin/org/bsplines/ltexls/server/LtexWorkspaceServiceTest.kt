/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.ExecuteCommandParams
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LtexWorkspaceServiceTest {
  @Test
  fun testMiscellaneous() {
    val server = LtexLanguageServer()
    val service = LtexWorkspaceService(server)
    val settings = JsonObject()
    settings.add("ltex", JsonObject())
    service.didChangeConfiguration(DidChangeConfigurationParams(settings))

    service.didChangeWatchedFiles(DidChangeWatchedFilesParams())

    val params = ExecuteCommandParams("_ltex.foobar", emptyList())
    val result: JsonObject = (service.executeCommand(params).get() as JsonElement).asJsonObject
    assertFalse(result["success"].asBoolean)
  }

  @Test
  fun testCheckDocument() {
    assertCheckDocumentResult("invalid_uri", false)
    assertCheckDocumentResult("file:///non_existent_path", false)

    for (extension: String in listOf(".bib", ".md", ".org", ".Rnw", ".rst", ".tex", ".typ")) {
      val tmpFile: File = File.createTempFile("ltex-", extension)

      try {
        assertCheckDocumentResult(tmpFile.toURI().toString(), true)
      } finally {
        if (!tmpFile.delete()) {
          Logging.LOGGER.warning(
            I18n.format("couldNotDeleteTemporaryFile", tmpFile.toPath().toString()),
          )
        }
      }
    }
  }

  @Test
  fun testGetServerStatus() {
    val server = LtexLanguageServer()
    val service = LtexWorkspaceService(server)
    val params = ExecuteCommandParams("_ltex.getServerStatus", emptyList())
    val result: JsonObject = (service.executeCommand(params).get() as JsonElement).asJsonObject

    assertTrue(result["success"].asBoolean)
    assertTrue(result["processId"].asLong >= 0)
    assertTrue(result["wallClockDuration"].asDouble >= 0)
    if (result.has("cpuUsage")) assertTrue(result["cpuUsage"].asDouble >= 0)
    if (result.has("cpuDuration")) assertTrue(result["cpuDuration"].asDouble >= 0)
    assertTrue(result["usedMemory"].asDouble >= 0)
    assertTrue(result["totalMemory"].asDouble >= 0)
  }

  @Test
  fun testGetCommandNamesGatesAddToDictionary() {
    val addToDictionary = "_ltex.addToDictionary"
    // Editor-managed (default): command NOT advertised, init response unchanged.
    assertFalse(LtexWorkspaceService.getCommandNames().contains(addToDictionary))
    assertFalse(LtexWorkspaceService.getCommandNames(false).contains(addToDictionary))
    // Server-managed: command advertised so thin clients can invoke it.
    assertTrue(LtexWorkspaceService.getCommandNames(true).contains(addToDictionary))
  }

  @Test
  fun testAddToDictionaryCommandAppendsToExternalFile() {
    val dictionaryFile: File = File.createTempFile("ltex-dict-", ".txt")
    dictionaryFile.writeText("existingword\n")

    try {
      val server = LtexLanguageServer()
      server.settingsManager.settings =
        Settings.fromJson(buildDictionarySettings(":" + dictionaryFile.absolutePath), null, true)
      val service = LtexWorkspaceService(server)

      val arguments: JsonObject = buildAddWordsArguments("newword")
      val response: Any = service.executeAddToDictionaryCommand(arguments).get()
      val result: JsonObject = (response as JsonElement).asJsonObject

      assertTrue(result["success"].asBoolean)
      val content: String = dictionaryFile.readText()
      assertTrue(content.contains("existingword"))
      assertTrue(content.contains("newword"))
    } finally {
      dictionaryFile.delete()
    }
  }

  @Test
  fun testAddToDictionaryCommandFailsWithoutExternalFile() {
    val server = LtexLanguageServer()
    // Dictionary has only a literal word, no ":"-prefixed external file entry.
    server.settingsManager.settings =
      Settings.fromJson(buildDictionarySettings("plainword"), null, true)
    val service = LtexWorkspaceService(server)

    val arguments: JsonObject = buildAddWordsArguments("newword")
    val response: Any = service.executeAddToDictionaryCommand(arguments).get()
    val result: JsonObject = (response as JsonElement).asJsonObject

    assertFalse(result["success"].asBoolean)
  }

  companion object {
    private fun buildDictionarySettings(vararg enUsEntries: String): JsonObject {
      val entries = JsonArray()
      for (entry: String in enUsEntries) entries.add(entry)
      val dictionary = JsonObject()
      dictionary.add("en-US", entries)
      val jsonSettings = JsonObject()
      jsonSettings.add("dictionary", dictionary)
      return jsonSettings
    }

    private fun buildAddWordsArguments(vararg words: String): JsonObject {
      val wordArray = JsonArray()
      for (word: String in words) wordArray.add(word)
      val wordsObject = JsonObject()
      wordsObject.add("en-US", wordArray)
      val arguments = JsonObject()
      arguments.addProperty("uri", "file:///demo")
      arguments.add("words", wordsObject)
      return arguments
    }

    private fun assertCheckDocumentResult(
      uri: String,
      expected: Boolean,
    ) {
      val server = LtexLanguageServer()
      val service = LtexWorkspaceService(server)

      val argument = JsonObject()
      argument.addProperty("uri", uri)

      val range = JsonObject()

      val rangeStart = JsonObject()
      rangeStart.addProperty("line", 0)
      rangeStart.addProperty("character", 1)

      val rangeEnd = JsonObject()
      rangeEnd.addProperty("line", 2)
      rangeEnd.addProperty("character", 3)

      range.add("start", rangeStart)
      range.add("end", rangeEnd)

      argument.add("range", range)

      val params = ExecuteCommandParams("_ltex.checkDocument", listOf(argument))
      val result: JsonObject = (service.executeCommand(params).get() as JsonElement).asJsonObject

      assertFalse(result["success"].asBoolean)
      assertEquals(!expected, result.has("errorMessage"))
    }
  }
}
