/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging

open class RegexCodeFragmentizer(
  codeLanguageId: String,
  val regex: Regex,
) : CodeFragmentizer(codeLanguageId) {
  override fun fragmentize(
    code: String,
    originalSettings: Settings,
  ): List<CodeFragment> {
    val codeFragments = ArrayList<CodeFragment>()
    var curSettings: Settings = originalSettings
    var curPos = 0

    for (matchResult: MatchResult in this.regex.findAll(code)) {
      var lastPos: Int = curPos
      curPos = matchResult.range.first
      var lastCode: String = code.substring(lastPos, curPos)
      var lastSettings: Settings = curSettings
      codeFragments.add(CodeFragment(codeLanguageId, lastCode, lastPos, lastSettings))

      var settingsLine: String? = null

      for (groupIndex in 1 until matchResult.groups.size) {
        if (matchResult.groups[groupIndex] != null) {
          settingsLine = matchResult.groupValues[groupIndex]
          break
        }
      }

      if (settingsLine == null) {
        Logging.LOGGER.warning(I18n.format("couldNotFindSettingsInMatch"))
        continue
      }

      curSettings = SettingsParser.createUpdatedSettings(settingsLine, curSettings)

      lastPos = curPos
      curPos = matchResult.range.last + 1
      lastCode = code.substring(lastPos, curPos)
      lastSettings = curSettings
      codeFragments.add(CodeFragment("nop", lastCode, lastPos, lastSettings))
    }

    codeFragments.add(
      CodeFragment(codeLanguageId, code.substring(curPos), curPos, curSettings),
    )

    return codeFragments
  }
}
