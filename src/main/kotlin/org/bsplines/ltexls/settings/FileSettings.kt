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
import org.bsplines.ltexls.server.LtexLanguageServer.Canonical
import kotlin.collections.setOf
import kotlin.text.startsWith
import kotlin.text.substring

data class FileSettings<T>(
  val items: List<Item<T>>,
) {
  sealed interface Item<T> {
    data class Literal<T>(
      val value: T,
      val subtract: Boolean = false,
    ) : Item<T> {
      companion object {
        fun parse(string: String): Literal<String> =
          if (string.startsWith("-")) {
            Literal(string.substring(1), true)
          } else {
            Literal(string, false)
          }

        fun <T> parse(
          string: String,
          parser: (String) -> T,
        ) = with(parse(string)) { Literal(parser(value), subtract) }
      }
    }

    data class File<T>(
      val path: Canonical<String>,
      val values: List<Literal<T>>,
    ) : Item<T>
  }

  val values: Set<T> =
    items
      .asSequence()
      .flatMap {
        when (it) {
          is Item.File<T> -> it.values.asSequence()
          is Item.Literal<T> -> sequenceOf(it)
        }
      }.fold(setOf()) { values, literal ->
        if (literal.subtract) {
          values - literal.value
        } else {
          values + literal.value
        }
      }

  // Subtle: The emptiness of the file settings is determined by the **reduced values**,
  // not by the raw items:
  //  this.isEmpty() implies this.items.isEmpty(),
  //    but not necessarily the other way around
  //
  // For example, a set like [ "hello", "-hello" ] will be considered empty, as will one pointing
  // to a single empty file.
  fun isEmpty(): Boolean = this.values.isEmpty()

  fun isNotEmpty(): Boolean = !this.isEmpty()

  companion object {
    fun <T> fromListOfStrings(
      listOfStrings: List<String>,
      settingsFileManager: SettingsFileManager.Rooted,
      parser: (String) -> T,
    ): FileSettings<T> =
      listOfStrings
        .mapNotNull {
          parseItemFromJsonString(it, settingsFileManager, parser)
        }.let { FileSettings(it) }

    fun fromListOfStrings(
      listOfStrings: List<String>,
      settingsFileManager: SettingsFileManager.Rooted,
    ): FileSettings<String> = fromListOfStrings(listOfStrings, settingsFileManager) { it }

    /**
     * Parse FileSettings from a heterogeneous JSON string.
     *
     * The entries in the JSON array can either be JSON objects or JSON strings. The corresponding
     * parser is then called for each value, handling literals (including subtraction) and file
     * paths as expected.
     *
     * This function mainly facilitates loading hiddenFalsePositive settings in a way where the user
     * can combine strings (either as a JSON string of the hidden false positive, possibly prefixed
     * with - to subtract it, or for filenames) together with actual JSON objects.
     *
     * That way, they can write hiddenFalsePositives naturally as an array of JSON objects, but
     * intersperse subtracted objects of file paths.
     *
     * If specified in a file, the file is expected to contain newline-separated JSON strings
     * of the hiddenFalsePositives (possibly subtracted). For example:
     * ```
     * // hiddenFalsePositives.en.txt
     * { "rule": "SOME_RULE", "sentence": "My sentence" }
     * -{ "rule": "SUBTRACT_RULE", "sentence": "This one will be subtracted" }
     * ```
     */
    fun <T> fromJsonArray(
      jsonArray: JsonArray,
      settingsFileManager: SettingsFileManager.Rooted,
      stringParser: (String) -> T,
      objectParser: (JsonObject) -> T,
    ): FileSettings<T> {
      // TODO allow assigning IDs to hiddenFalsePositives rules and subtracting them by ID? how?
      return jsonArray
        .mapNotNull {
          when {
            it.isJsonObject -> {
              Item.Literal(objectParser(it.asJsonObject))
            }

            it.isJsonPrimitive && it.asJsonPrimitive.isString -> {
              parseItemFromJsonString(
                it.asJsonPrimitive.asString,
                settingsFileManager,
                stringParser,
              )
            }

            else -> {
              null
            }
          }
        }.let { FileSettings(it) }
    }

    private fun <T> parseItemFromJsonString(
      string: String,
      settingsFileManager: SettingsFileManager.Rooted,
      parser: (String) -> T,
    ): Item<T>? =
      if (string.startsWith(":")) {
        val path = settingsFileManager.resolve(string.substring(1)) ?: return null
        val values = settingsFileManager.loadSettings(path.canonicalPath, parser)
        Item.File(path, values)
      } else {
        Item.Literal.parse(string, parser)
      }

    fun <T> fromSet(sequence: Set<T>): FileSettings<T> =
      FileSettings(sequence.map { Item.Literal(it) })

    fun <T> of(vararg vals: T): FileSettings<T> = FileSettings(vals.map { Item.Literal(it) })
  }
}
