/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import SettingsParser
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsOverride
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
    val magicComments = ArrayList<MagicComment>()

    for (matchResult: MatchResult in this.regex.findAll(code)) {
      var settingsLine: String? = null

      for (groupIndex in 1 until matchResult.groups.size) {
        if (matchResult.groups[groupIndex] != null) {
          settingsLine = matchResult.groupValues[groupIndex]
          break
        }
      }

      magicComments.add(
        MagicComment(matchResult.range.first, matchResult.range.last + 1, settingsLine),
      )
    }

    return buildFragments(codeLanguageId, code, originalSettings, magicComments)
  }

  /**
   * A located `ltex:` magic comment: the source span to excise (from [start] to
   * [endExclusive]) and the settings text it carries ([settingsLine], or null
   * when a magic comment was matched but its settings group could not be
   * isolated).
   */
  class MagicComment(
    val start: Int,
    val endExclusive: Int,
    val settingsLine: String?,
  )

  companion object {
    /**
     * Splits [code] at the given [magicComments], threading the cumulative
     * `ltex:` settings overrides through the resulting fragments. Magic comments
     * must be supplied in source order and must not overlap.
     *
     * Shared by regex-driven fragmentizers and scanner-driven ones (e.g. Emacs
     * Lisp via [org.bsplines.ltexls.parsing.program.ElispFragmentizer]) so the
     * fragment/settings bookkeeping has a single implementation; only the way
     * magic comments are *located* differs.
     */
    fun buildFragments(
      codeLanguageId: String,
      code: String,
      originalSettings: Settings,
      magicComments: List<MagicComment>,
    ): List<CodeFragment> {
      val codeFragments = ArrayList<CodeFragment>()
      val settingsOverride = SettingsOverride(originalSettings)
      var curSettings: Settings = settingsOverride.toSettings()
      var curPos = 0

      for (magicComment: MagicComment in magicComments) {
        var lastPos: Int = curPos
        curPos = magicComment.start
        var lastCode: String = code.substring(lastPos, curPos)
        codeFragments.add(CodeFragment(codeLanguageId, lastCode, lastPos, curSettings))

        val settingsLine: String? = magicComment.settingsLine

        if (settingsLine == null) {
          Logging.LOGGER.warning(I18n.format("couldNotFindSettingsInMatch"))
          continue
        }

        SettingsParser.updateSettingsOverride(settingsLine, settingsOverride)
        curSettings = settingsOverride.toSettings()

        lastPos = curPos
        curPos = magicComment.endExclusive
        lastCode = code.substring(lastPos, curPos)
        codeFragments.add(CodeFragment("nop", lastCode, lastPos, curSettings))
      }

      codeFragments.add(
        CodeFragment(codeLanguageId, code.substring(curPos), curPos, curSettings),
      )

      return codeFragments
    }
  }
}
