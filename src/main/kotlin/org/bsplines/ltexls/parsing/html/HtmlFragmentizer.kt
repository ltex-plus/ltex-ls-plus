/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.html

import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.settings.Settings

class HtmlFragmentizer(
  codeLanguageId: String,
) : CodeFragmentizer(codeLanguageId) {
  /*
    Only text in between tags should be checked
    (e.g: <Not checked>Checked</Not checked>).
    Therefore the codeLanguageID of text in between "< >" is set to "nop".
   */
  override fun fragmentize(
    code: String,
    originalSettings: Settings,
  ): List<CodeFragment> {
    val codeFragments = ArrayList<CodeFragment>()
    var currentSettings: Settings = originalSettings

    var currentIndex = 0

    while (currentIndex < code.length) {
      val openingTagIndex = code.indexOf('<', currentIndex)
      if (openingTagIndex == -1) {
        val rest = code.substring(currentIndex, code.length)
        codeFragments.add(CodeFragment(codeLanguageId, rest, currentIndex, currentSettings))
        return codeFragments // Finished
      }
      if (currentIndex != openingTagIndex) {
        val content = code.substring(currentIndex, openingTagIndex)
        codeFragments.add(CodeFragment(codeLanguageId, content, currentIndex, currentSettings))
      }

      // Handle comments
      val isComment =
        code
          .substring(
            openingTagIndex,
            openingTagIndex + COMMENT_START_IDENTIFIER.length,
          ).equals(COMMENT_START_IDENTIFIER)

      if (isComment) {
        val commentInnerStart = openingTagIndex + COMMENT_START_IDENTIFIER.length
        val commentEndIdentifierIndex = code.indexOf(COMMENT_END_IDENTIFIER, commentInnerStart)
        if (commentEndIdentifierIndex == -1) {
          return codeFragments // Comment was not closed
        }
        val commentInnerEnd = commentEndIdentifierIndex
        val commentContent = code.substring(commentInnerStart, commentInnerEnd)
        val closingTagIndex = commentInnerEnd + COMMENT_END_IDENTIFIER.length - 1
        val comment = code.substring(openingTagIndex, closingTagIndex + 1)

        // Check for magic comment
        val regex = Regex("[ \t]*[Ll][Tt][Ee][Xx]:(.*?)[ \t]*")
        val matchResult = regex.matchEntire(commentContent)
        if (matchResult != null) {
          val settingsLine = matchResult.groups[1]!!.value
          currentSettings = SettingsParser.createUpdatedSettings(settingsLine, currentSettings)
        }

        codeFragments.add(CodeFragment("nop", comment, openingTagIndex, currentSettings))

        currentIndex = closingTagIndex + 1
      } else {
        currentIndex = handleTag(code, openingTagIndex, codeFragments, currentSettings)
        if (currentIndex == -1) {
          return codeFragments
        }
      }
    }

    return codeFragments
  }

  /*
    Handle the opened tag.
    Requires some special attention because strings in tags may contain '>'
   */
  fun handleTag(
    code: String,
    openingTagIndex: Int,
    codeFragments: ArrayList<CodeFragment>,
    currentSettings: Settings,
  ): Int {
    // It would be possible to additionally check for a script tag and change the current
    // codeLanguageId accordingly.
    var currentIndex = openingTagIndex + 1
    while (true) {
      val openingQuoteIndex = code.indexOf('"', currentIndex)
      val closingTagIndex = code.indexOf('>', currentIndex)

      if (closingTagIndex == -1) {
        return -1 // Tag was not closed
      }

      val stringStarted = openingQuoteIndex != -1 && openingQuoteIndex < closingTagIndex
      if (stringStarted) {
        currentIndex = closingQuoteIndex(code, openingQuoteIndex)
        if (currentIndex == -1) {
          return -1 // Quote was not closed
        }
        // val quote = code.substring(openingQuoteIndex, currentIndex - 1)
      } else {
        val tag = code.substring(openingTagIndex, closingTagIndex + 1)
        codeFragments.add(CodeFragment("nop", tag, openingTagIndex, currentSettings))

        currentIndex = closingTagIndex + 1
        break
      }
    }
    return currentIndex
  }

  fun closingQuoteIndex(
    code: String,
    openingQuoteIndex: Int,
  ): Int {
    var currentIndex = openingQuoteIndex + 1
    do {
      val nextQuote = code.indexOf('"', currentIndex)
      if (nextQuote == -1) {
        return -1 // Quote was not closed
      }
      currentIndex = nextQuote + 1
      // Skip escaped quotes (\")
    } while (code[nextQuote - 1] == '\\')
    return currentIndex
  }

  companion object {
    private const val COMMENT_START_IDENTIFIER = "<!--"
    private const val COMMENT_END_IDENTIFIER = "-->"
  }
}
