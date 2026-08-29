/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import org.bsplines.ltexls.settings.SettingsOverride
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import java.util.logging.Level

object SettingsParser {
  class InlineSettingsParserException(
    message: String,
  ) : Exception(message)

  private val KEY_VALUE_REGEX =
    Regex("^[ \t\n\r]*(?<key>[^ \t]+)=(?<value>[^ \t\n\r]*)[ \t\n\r]*", RegexOption.MULTILINE)

  private val ENABLED_REGEX = Regex("^enabled$", RegexOption.IGNORE_CASE)
  private val LANGUAGE_SHORT_CODE_REGEX = Regex("^language$", RegexOption.IGNORE_CASE)
  private val DICTIONARY_REGEX =
    Regex("^(?:ltex\\.)?dictionary(?<op>[+#-])$", RegexOption.IGNORE_CASE)
  private val RULE_REGEX = Regex("^(?:ltex\\.)?rules(?<op>[+#-])$", RegexOption.IGNORE_CASE)

  // The two cumulative settings written without their required "+"/"-"/"#"
  // operator, e.g. "dictionary=Foo". Such a key matches neither regex above, so
  // without this it reaches the "unknown setting" branch and the warning claims a
  // supported setting does not exist -- the very confusion behind discussion #205.
  private val CUMULATIVE_WITHOUT_OPERATOR_REGEX =
    Regex("^(?:ltex\\.)?(?<name>dictionary|rules)$", RegexOption.IGNORE_CASE)

  @Suppress("UnusedPrivateProperty") // False positives are too complex to parse for now.
  private val FALSE_POSITIVE_REGEX =
    Regex("^(?:ltex\\.)?hiddenFalsePositives(?<op>[+#-])$", RegexOption.IGNORE_CASE)
  private val BIBTEX_FIELD_REGEX =
    Regex("^(?:ltex\\.)?bibtex\\.fields\\.(?<field>.+?)$", RegexOption.IGNORE_CASE)
  private val LATEX_COMMAND_REGEX =
    Regex("^(?:ltex\\.)?latex\\.commands\\.(?<command>.+?)$", RegexOption.IGNORE_CASE)
  private val LATEX_ENVIRONMENT_REGEX =
    Regex("^(?:ltex\\.)?latex\\.environments\\.(?<env>.+?)$", RegexOption.IGNORE_CASE)
  private val MARKDOWN_NODES_REGEX =
    Regex("^(?:ltex\\.)?markdown\\.nodes\\.(?<node>.+?)$", RegexOption.IGNORE_CASE)
  private val PICKY_RULES_REGEX =
    Regex("^(?:ltex\\.)?(additionalRules\\.)?enablePickyRules$", RegexOption.IGNORE_CASE)
  private val MOTHER_TONGUE_SHORTCODE_REGEX =
    Regex("^(?:ltex\\.)?(?:additionalRules\\.)?motherTongue$", RegexOption.IGNORE_CASE)
  private val LANGUAGE_MODEL_REGEX =
    Regex("^(?:ltex\\.)?(?:additionalRules\\.)?languagemodel$", RegexOption.IGNORE_CASE)
  private val LOG_LEVEL_REGEX =
    Regex("^(?:ltex\\.)?(?:ltex-ls\\.)?logLevel", RegexOption.IGNORE_CASE)

  private val NULL_REGEX = Regex("^(?:null|nil|clear|#)$", RegexOption.IGNORE_CASE)
  private val NULL_BOOL_REGEX =
    Regex("^(?<t>true|1|yes)|(?<f>false|0|no)|(?<n>null|nil|clear|#|)$", RegexOption.IGNORE_CASE)
  private val OP_REGEX = Regex("^(?<add>\\+)|(?<remove>-)|(?<keep>#)$", RegexOption.IGNORE_CASE)

  private fun parseSettings(settingsLine: String): Iterable<Pair<String, String>> {
    val settingsUpdates = ArrayList<Pair<String, String>>()

    var remainingLine = settingsLine

    fun nextMatch(): Pair<String, String>? {
      val match = KEY_VALUE_REGEX.matchAt(remainingLine, 0)
      return match?.let {
        remainingLine = remainingLine.substring(it.groupValues[0].length)
        return Pair(match.groups["key"]!!.value, match.groups["value"]!!.value)
      }
    }

    var kv = nextMatch()
    while (kv != null) {
      settingsUpdates.add(kv)
      kv = nextMatch()
    }

    if (remainingLine.isNotEmpty()) {
      Logging.LOGGER.warning(I18n.format("ignoringMalformedInlineSetting", remainingLine))
    }

    return settingsUpdates
  }

  @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
  public fun updateSettingsOverride(
    key: String,
    value: String,
    override: SettingsOverride,
  ) {
    try {
      when {
        ENABLED_REGEX
          .matchEntire(key)
          ?.let { override.updateEnabled(nullableBool(value, key)) } != null -> {
        }

        LANGUAGE_SHORT_CODE_REGEX
          .matchEntire(key)
          ?.let { override.updateLanguageShortCode(nullableString(value)) } != null -> {
        }

        DICTIONARY_REGEX.matchEntire(key)?.let {
          override.updateCurrentDictionary(value, opToIncluded(it.groups["op"]!!.value, key))
        } != null -> {
        }

        RULE_REGEX.matchEntire(key)?.let {
          override.updateCurrentRule(value, opToIncluded(it.groups["op"]!!.value, key))
        } != null -> {
        }

        // FALSE_POSITIVE_REGEX.matchEntire(key)?.let {
        //   // parsing is too complicated for now
        //   updateCurrentFalsePositive(???, nullableBool(value))
        // } != null -> {}
        BIBTEX_FIELD_REGEX.matchEntire(key)?.let {
          override.updateBibtexField(it.groups["field"]!!.value, nullableBool(value, key))
        } != null -> {
        }

        LATEX_COMMAND_REGEX.matchEntire(key)?.let {
          override.updateLatexCommand(it.groups["command"]!!.value, nullableString(value))
        } != null -> {
        }

        LATEX_ENVIRONMENT_REGEX.matchEntire(key)?.let {
          override.updateLatexEnvironment(it.groups["env"]!!.value, nullableString(value))
        } != null -> {
        }

        MARKDOWN_NODES_REGEX.matchEntire(key)?.let {
          override.updateMarkdownNode(it.groups["node"]!!.value, nullableString(value))
        } != null -> {
        }

        PICKY_RULES_REGEX
          .matchEntire(key)
          ?.let { override.updatePickyRules(nullableBool(value, key)) } != null -> {
        }

        MOTHER_TONGUE_SHORTCODE_REGEX
          .matchEntire(key)
          ?.let { override.updateMotherTongueShortCode(nullableString(value)) } != null -> {
        }

        LANGUAGE_MODEL_REGEX
          .matchEntire(key)
          ?.let { override.updateLanguageModelRulesDirectory(nullableString(value)) } != null -> {
        }

        LOG_LEVEL_REGEX.matchEntire(key)?.let {
          override.updateLogLevel(
            if (nullableString(value) == null) {
              null
            } else {
              try {
                Level.parse(value.uppercase())
              } catch (_: IllegalArgumentException) {
                throw InlineSettingsParserException(
                  I18n.format(
                    "ignoringUnknownInlineSettingValue",
                    value,
                  ),
                )
              }
            },
          )
        } != null -> {
        }

        CUMULATIVE_WITHOUT_OPERATOR_REGEX.matchEntire(key)?.let {
          Logging.LOGGER.warning(
            I18n.format("ignoringMalformedInlineSettingOperation", "", key),
          )
        } != null -> {
        }

        else -> {
          Logging.LOGGER.warning(I18n.format("ignoringUnknownInlineSetting", key, value))
        }
      }
    } catch (e: InlineSettingsParserException) {
      Logging.LOGGER.warning(e.message)
    }
  }

  public fun updateSettingsOverride(
    settingsLine: String,
    override: SettingsOverride,
  ) {
    val settingsUpdates = parseSettings(settingsLine)
    updateSettingsOverride(settingsUpdates, override)
  }

  public fun updateSettingsOverride(
    settingsUpdate: Iterable<Pair<String, String>>,
    override: SettingsOverride,
  ) {
    for ((settingKey: String, settingValue: String) in settingsUpdate) {
      updateSettingsOverride(settingKey, settingValue, override)
    }
  }

  fun nullableString(s: String): String? = if (NULL_REGEX.matchEntire(s) != null) null else s

  fun nullableBool(
    s: String,
    key: String,
  ): Boolean? {
    val m = NULL_BOOL_REGEX.matchEntire(s)
    return m?.let {
      return when {
        (m.groups["t"] != null) -> {
          true
        }

        (m.groups["f"] != null) -> {
          false
        }

        (m.groups["n"] != null) -> {
          null
        }

        else -> {
          throw InlineSettingsParserException(
            I18n.format(
              "ignoringMalformedInlineSettingBoolean",
              s,
              key,
            ),
          )
        }
      }
    }
  }

  fun opToIncluded(
    s: String,
    key: String,
  ): Boolean? {
    val m = OP_REGEX.matchEntire(s)
    return m?.let {
      return when {
        (m.groups["add"] != null) -> {
          true
        }

        (m.groups["remove"] != null) -> {
          false
        }

        (m.groups["keep"] != null) -> {
          null
        }

        else -> {
          throw InlineSettingsParserException(
            I18n.format(
              "ignoringMalformedInlineSettingOperation",
              s,
              key,
            ),
          )
        }
      }
    }
  }
}
