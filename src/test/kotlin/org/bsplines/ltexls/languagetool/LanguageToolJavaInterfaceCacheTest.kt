/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import org.bsplines.ltexls.server.DocumentChecker
import org.bsplines.ltexls.server.DocumentCheckerTest
import org.bsplines.ltexls.server.LtexTextDocumentItem
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertTrue

class LanguageToolJavaInterfaceCacheTest {
  // Covers the `sentenceCacheSize > 0` branch of LanguageToolJavaInterface (a real
  // ResultCache is built) and, with FINEST logging on, logResultCache's non-null
  // body too. The default is now 0 (cache disabled, resultCache == null), so without
  // this nothing exercises the cache-construction branch or that log path. Driven
  // through the normal SettingsManager -> DocumentChecker -> LanguageToolJavaInterface
  // route, like the other tests in this package.
  @Test
  fun testResultCacheCreatedWhenSentenceCacheSizePositive() {
    val matches =
      check(
        Settings(
          _languageShortCode = "en-US",
          _sentenceCacheSize = 1L,
          _logLevel = Level.FINEST,
        ),
      )
    assertTrue(matches.isNotEmpty())
  }

  // The default `sentenceCacheSize == 0` path (resultCache == null): checking still
  // works with LanguageTool's internal cache disabled.
  @Test
  fun testCheckWorksWithSentenceCacheDisabled() {
    val matches = check(Settings(_languageShortCode = "en-US", _sentenceCacheSize = 0L))
    assertTrue(matches.isNotEmpty())
  }

  private fun check(settings: Settings): List<LanguageToolRuleMatch> {
    val settingsManager = SettingsManager(settings)
    val documentChecker = DocumentChecker(settingsManager)
    val document: LtexTextDocumentItem =
      DocumentCheckerTest.createDocument("latex", "This is an test.\n")
    return documentChecker.check(document).first
  }
}
