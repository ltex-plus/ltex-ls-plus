/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeActionProviderTest {
  // In server-managed mode a quick fix can only be carried out when the setting
  // has a ":"-prefixed external file for the document's language. Lean clients
  // discard a failed command result without showing it, so offering an action
  // that is certain to fail would look like success and silently do nothing.
  @Test
  fun testExternalFileCodeActionsAreWithheldWhenNoFileIsConfigured() {
    // Server-managed, no external file configured anywhere: withhold all three.
    assertEquals(emptySet(), persistingCommandsOffered(serverManaged = true, externalFile = null))

    val externalFile: File = File.createTempFile("ltex-guard-", ".txt")

    try {
      // Server-managed with a file for the document's language: offer them again.
      assertEquals(
        PERSISTING_COMMANDS,
        persistingCommandsOffered(serverManaged = true, externalFile = externalFile),
      )

      // Editor-managed (the default): the client performs the write, so a missing
      // external file must not withhold anything.
      assertEquals(
        PERSISTING_COMMANDS,
        persistingCommandsOffered(serverManaged = false, externalFile = null),
      )
    } finally {
      externalFile.delete()
    }
  }

  companion object {
    private val PERSISTING_COMMANDS: Set<String> =
      setOf("_ltex.addToDictionary", "_ltex.disableRules", "_ltex.hideFalsePositives")

    private val EXTERNAL_FILE_SETTINGS: List<String> =
      listOf("dictionary", "disabledRules", "hiddenFalsePositives")

    // Runs the code action generator over a document containing an unknown word
    // and returns which of the three persisting commands were offered.
    private fun persistingCommandsOffered(
      serverManaged: Boolean,
      externalFile: File?,
    ): Set<String> {
      val document: LtexTextDocumentItem =
        DocumentCheckerTest.createDocument("markdown", "This is an unknownword.\n")
      val settingsManager = SettingsManager()
      val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
        DocumentChecker(settingsManager).check(document)

      if (externalFile != null) {
        settingsManager.settings = Settings.fromJson(buildSettings(externalFile), null, true)
      }

      val params =
        CodeActionParams(
          TextDocumentIdentifier(document.uri),
          Range(Position(0, 0), Position(100, 0)),
          CodeActionContext(emptyList()),
        )

      val codeActionProvider = CodeActionProvider(settingsManager)
      codeActionProvider.serverManagesExternalFiles = serverManaged

      return codeActionProvider
        .generate(params, document, checkingResult)
        .mapNotNull { it.right?.command?.command }
        .filter { it.startsWith("_ltex.") }
        .toSet()
    }

    // { <setting>: { "en-US": [":<path>"] } } for each writable setting.
    private fun buildSettings(externalFile: File): JsonObject {
      val jsonSettings = JsonObject()

      for (settingName: String in EXTERNAL_FILE_SETTINGS) {
        val entries = JsonArray()
        entries.add(":" + externalFile.absolutePath)
        val byLanguage = JsonObject()
        byLanguage.add("en-US", entries)
        jsonSettings.add(settingName, byLanguage)
      }

      return jsonSettings
    }
  }
}
