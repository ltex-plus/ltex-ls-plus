/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging

object SettingsParser {
  private val SPLIT_SETTINGS_REGEX = Regex("[ \t]+")

  private fun parseSettings(settingsLine: String): Map<String, String> {
    val settingsMap = HashMap<String, String>()

    for (settingsChange: String in settingsLine.trim().split(SPLIT_SETTINGS_REGEX)) {
      val settingKeyLength: Int = settingsChange.indexOf('=')

      if (settingKeyLength == -1) {
        Logging.LOGGER.warning(I18n.format("ignoringMalformedInlineSetting", settingsChange))
        continue
      }

      val settingKey: String = settingsChange.substring(0, settingKeyLength).trim()
      val settingValue: String = settingsChange.substring(settingKeyLength + 1).trim()
      settingsMap[settingKey] = settingValue
    }

    return settingsMap
  }

  /*
    Return the original settings when no valid settings pair was found else it creates a modified copy
   */
  public fun createUpdatedSettings(
    settingsMap: Map<String, String>,
    currentSettings: Settings,
  ): Settings {
    var newSettings = currentSettings
    for ((settingKey: String, settingValue: String) in settingsMap) {
      when {
        settingKey.equals("enabled", ignoreCase = true) -> {
          newSettings =
            newSettings.copy(
              _enabled = (if (settingValue == "true") Settings.DEFAULT_ENABLED else emptySet()),
            )
        }
        settingKey.equals("language", ignoreCase = true) -> {
          newSettings = newSettings.copy(_languageShortCode = settingValue)
        }
        else -> {
          Logging.LOGGER.warning(
            I18n.format("ignoringUnknownInlineSetting", settingKey, settingValue),
          )
        }
      }
    }
    return newSettings
  }

  public fun createUpdatedSettings(
    settingsLine: String,
    currentSettings: Settings,
  ): Settings {
    val settingsMap: Map<String, String> = SettingsParser.parseSettings(settingsLine)
    return SettingsParser.createUpdatedSettings(settingsMap, currentSettings)
  }
}
