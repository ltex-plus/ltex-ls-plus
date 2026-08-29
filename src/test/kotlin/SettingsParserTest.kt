/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsOverride
import org.bsplines.ltexls.tools.Logging
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsParserTest {
  private class CapturingHandler : Handler() {
    val messages = ArrayList<String>()

    override fun publish(record: LogRecord?) {
      record?.message?.let { this.messages.add(it) }
    }

    override fun flush() = Unit

    override fun close() = Unit
  }

  // Runs one magic-comment settings line, returning both the warnings it logged
  // and the resulting settings, so a test can assert on either.
  private fun parse(settingsLine: String): Pair<List<String>, Settings> {
    val handler = CapturingHandler()
    val override = SettingsOverride(Settings())
    Logging.LOGGER.addHandler(handler)

    try {
      SettingsParser.updateSettingsOverride(settingsLine, override)
    } finally {
      Logging.LOGGER.removeHandler(handler)
    }

    return Pair(handler.messages, override.toSettings())
  }

  /**
   * A cumulative setting written without its operator used to fall through to the
   * "unknown setting" branch, telling the user that a supported setting does not
   * exist. Regression test for
   * https://github.com/ltex-plus/ltex-ls-plus/discussions/205.
   */
  @Test
  fun testDictionaryWithoutOperatorNamesTheOperators() {
    for (key: String in listOf("dictionary", "ltex.dictionary", "DICTIONARY")) {
      val (warnings: List<String>, _) = parse("$key=Kryptographie")

      assertEquals(1, warnings.size, key)
      assertContains(warnings[0], key)
      assertContains(warnings[0], "'+='")
      assertContains(warnings[0], "'-='")
      assertContains(warnings[0], "'#='")
      assertFalse(warnings[0].contains("unknown", ignoreCase = true), warnings[0])
    }
  }

  @Test
  fun testRulesWithoutOperatorNamesTheOperators() {
    val (warnings: List<String>, _) = parse("rules=EN_A_VS_AN")

    assertEquals(1, warnings.size)
    assertContains(warnings[0], "rules")
    assertContains(warnings[0], "'+='")
    assertFalse(warnings[0].contains("unknown", ignoreCase = true), warnings[0])
  }

  @Test
  fun testGenuinelyUnknownSettingIsStillReportedAsUnknown() {
    val (warnings: List<String>, _) = parse("dictionaries=Kryptographie")

    assertEquals(1, warnings.size)
    assertContains(warnings[0], "unknown")
  }

  /**
   * The JSON form of `ltex.dictionary` pasted into a magic comment, as reported in
   * https://github.com/ltex-plus/ltex-ls-plus/discussions/205. Values are separated
   * by whitespace, so the JSON is split at the first space and its tail cannot be
   * parsed; the warning now says what a magic comment does expect.
   */
  @Test
  fun testJsonValueIsRejectedWithAdvice() {
    val (warnings: List<String>, settings: Settings) =
      parse("""dictionary={"en-US": ["adaptivity"], "de-DE": ["B-Splines"]}""")

    val malformed: String =
      warnings.single { it.contains("Ignoring malformed inline setting '[") }
    assertContains(malformed, "NAME=VALUE")
    assertContains(malformed, "JSON")

    // Nothing from the JSON is mistaken for a dictionary entry.
    val dictionary: Set<String> = settings.dictionary
    assertTrue(
      dictionary.none { it.contains("adaptivity") },
      dictionary.toString(),
    )
  }

  @Test
  fun testDictionaryWithOperatorStillWorksSilently() {
    val (warnings: List<String>, settings: Settings) = parse("dictionary+=Kryptographie")

    assertTrue(warnings.isEmpty(), warnings.toString())
    assertContains(settings.dictionary, "Kryptographie")
  }
}
