/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.settings

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSettingsTest {
  @Test
  fun testPrecedenceLastWins() {
    val fileSettings1 =
      FileSettings(
        listOf(
          FileSettings.Item.Literal("FOO", subtract = false),
          FileSettings.Item.Literal("FOO", subtract = true),
        ),
      )
    assertTrue(fileSettings1.values.isEmpty())

    val fileSettings2 =
      FileSettings(
        listOf(
          FileSettings.Item.Literal("FOO", subtract = true),
          FileSettings.Item.Literal("FOO", subtract = false),
        ),
      )
    assertEquals(setOf("FOO"), fileSettings2.values)

    val fileSettings3 =
      FileSettings(
        listOf(
          FileSettings.Item.Literal("FOO", subtract = false),
          FileSettings.Item.Literal("FOO", subtract = true),
          FileSettings.Item.Literal("FOO", subtract = false),
        ),
      )
    assertEquals(setOf("FOO"), fileSettings3.values)
  }

  @Test
  fun testSubtractionOnEmpty() {
    val fileSettings =
      FileSettings(listOf(FileSettings.Item.Literal("BAR", subtract = true)))
    assertTrue(fileSettings.values.isEmpty())
  }

  @Test
  fun testEmptyPathHandling() {
    val tempDir = Files.createTempDirectory("ltex-empty-path-test")
    try {
      val manager = BasicSettingsFileManager().rooted(tempDir)
      assertNull(manager.resolve(""))
    } finally {
      Files.deleteIfExists(tempDir)
    }
  }

  @Test
  fun testRelativePathResolution() {
    val tempDir = Files.createTempDirectory("ltex-rel-path-test")
    val testFile = Files.createTempFile(tempDir, "dict", ".txt")
    try {
      val manager = BasicSettingsFileManager().rooted(tempDir)
      val resolved = manager.resolve(testFile.fileName.toString())
      assertEquals(testFile.toRealPath(), resolved?.canonicalPath)
    } finally {
      Files.deleteIfExists(testFile)
      Files.deleteIfExists(tempDir)
    }
  }

  @Test
  fun testFromJsonArray() {
    val tempDir = Files.createTempDirectory("ltex-json-array-test")
    val testFile = Files.createTempFile(tempDir, "dict", ".txt")
    Files.write(testFile, listOf("file-word-1", "-file-word-2", "-file-subtracted-word"))
    try {
      val manager = BasicSettingsFileManager().rooted(tempDir)
      val jsonArray = JsonArray()
      jsonArray.add("literal-word")
      jsonArray.add("-subtracted-word")
      jsonArray.add("file-subtracted-word")
      jsonArray.add(":${testFile.fileName}")

      val objectElement = JsonObject()
      objectElement.addProperty("value", "object-word")
      objectElement.addProperty("ignored", "ignored-word")
      jsonArray.add(objectElement)

      val fileSettings =
        FileSettings.fromJsonArray(
          jsonArray,
          manager,
          stringParser = { it },
          objectParser = { it.get("value").asJsonPrimitive.asString },
        )

      val expected = setOf("literal-word", "file-word-1", "object-word")
      assertEquals(expected, fileSettings.values)
    } finally {
      Files.deleteIfExists(testFile)
      Files.deleteIfExists(tempDir)
    }
  }
}
