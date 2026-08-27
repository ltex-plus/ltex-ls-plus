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
  fun testCheckDocumentRejectsMissingOrIncompatibleArguments() {
    val service = LtexWorkspaceService(LtexLanguageServer())

    val nonPrimitiveUri = JsonObject()
    nonPrimitiveUri.add("uri", JsonArray())

    val invalidArgumentLists: List<List<Any>?> =
      listOf(
        null,
        emptyList(),
        listOf(JsonArray()),
        listOf(JsonObject()),
        listOf(nonPrimitiveUri),
      )

    for (arguments in invalidArgumentLists) {
      val params = ExecuteCommandParams("_ltex.checkDocument", arguments)
      val result = (service.executeCommand(params).get() as JsonElement).asJsonObject

      assertFalse(result["success"].asBoolean)
      assertEquals(
        I18n.format("invalidCommandArguments", "_ltex.checkDocument"),
        result["errorMessage"].asString,
      )
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
  fun testGetCommandNamesGatesExternalFileCommands() {
    val externalFileCommands =
      listOf("_ltex.addToDictionary", "_ltex.disableRules", "_ltex.hideFalsePositives")
    // Editor-managed (default): commands NOT advertised, init response unchanged.
    for (command: String in externalFileCommands) {
      assertFalse(LtexWorkspaceService.getCommandNames().contains(command))
      assertFalse(LtexWorkspaceService.getCommandNames(false).contains(command))
    }
    // Server-managed: all three advertised so thin clients can invoke them.
    for (command: String in externalFileCommands) {
      assertTrue(LtexWorkspaceService.getCommandNames(true).contains(command))
    }
  }

  @Test
  fun testAddToDictionaryCommandAppendsToExternalFile() {
    val dictionaryFile: File = File.createTempFile("ltex-dict-", ".txt")
    dictionaryFile.writeText("existingword\n")

    try {
      val server = LtexLanguageServer()
      server.settingsManager.settings =
        Settings.fromJson(
          buildSettings("dictionary", ":" + dictionaryFile.absolutePath),
          null,
          true,
        )
      val service = LtexWorkspaceService(server)

      val arguments: JsonObject = buildCommandArguments("words", "newword")
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
  fun testDisableRulesCommandAppendsToExternalFile() {
    val rulesFile: File = File.createTempFile("ltex-rules-", ".txt")

    try {
      val server = LtexLanguageServer()
      server.settingsManager.settings =
        Settings.fromJson(
          buildSettings("disabledRules", ":" + rulesFile.absolutePath),
          null,
          true,
        )
      val service = LtexWorkspaceService(server)

      val arguments: JsonObject = buildCommandArguments("ruleIds", "SOME_RULE")
      val response: Any = service.executeDisableRulesCommand(arguments).get()
      val result: JsonObject = (response as JsonElement).asJsonObject

      assertTrue(result["success"].asBoolean)
      assertTrue(rulesFile.readText().contains("SOME_RULE"))
    } finally {
      rulesFile.delete()
    }
  }

  @Test
  fun testHideFalsePositivesCommandAppendsToExternalFile() {
    val falsePositivesFile: File = File.createTempFile("ltex-fp-", ".txt")

    try {
      val server = LtexLanguageServer()
      server.settingsManager.settings =
        Settings.fromJson(
          buildSettings("hiddenFalsePositives", ":" + falsePositivesFile.absolutePath),
          null,
          true,
        )
      val service = LtexWorkspaceService(server)

      val falsePositive = "{\"rule\":\"SOME_RULE\",\"sentence\":\"^Foo\$\"}"
      val arguments: JsonObject = buildCommandArguments("falsePositives", falsePositive)
      val response: Any = service.executeHideFalsePositivesCommand(arguments).get()
      val result: JsonObject = (response as JsonElement).asJsonObject

      assertTrue(result["success"].asBoolean)
      assertTrue(falsePositivesFile.readText().contains("SOME_RULE"))
    } finally {
      falsePositivesFile.delete()
    }
  }

  @Test
  fun testAddToDictionaryCommandFailsWithoutExternalFile() {
    val server = LtexLanguageServer()
    // Dictionary has only a literal word, no ":"-prefixed external file entry.
    server.settingsManager.settings =
      Settings.fromJson(buildSettings("dictionary", "plainword"), null, true)
    val service = LtexWorkspaceService(server)

    val arguments: JsonObject = buildCommandArguments("words", "newword")
    val response: Any = service.executeAddToDictionaryCommand(arguments).get()
    val result: JsonObject = (response as JsonElement).asJsonObject

    assertFalse(result["success"].asBoolean)
  }

  companion object {
    // Builds { <settingName>: { "en-US": [entries...] } } — the ltex settings
    // section as the server receives it via workspace/configuration.
    private fun buildSettings(
      settingName: String,
      vararg enUsEntries: String,
    ): JsonObject {
      val entries = JsonArray()
      for (entry: String in enUsEntries) entries.add(entry)
      val setting = JsonObject()
      setting.add("en-US", entries)
      val jsonSettings = JsonObject()
      jsonSettings.add(settingName, setting)
      return jsonSettings
    }

    // Builds { uri, <argumentKey>: { "en-US": [entries...] } } — an external-file
    // quick-fix command's argument payload.
    private fun buildCommandArguments(
      argumentKey: String,
      vararg entries: String,
    ): JsonObject {
      val entryArray = JsonArray()
      for (entry: String in entries) entryArray.add(entry)
      val byLanguage = JsonObject()
      byLanguage.add("en-US", entryArray)
      val arguments = JsonObject()
      arguments.addProperty("uri", "file:///demo")
      arguments.add(argumentKey, byLanguage)
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
