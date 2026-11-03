/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.parsing.FragmentCache
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What happens when the LanguageTool HTTP server cannot be reached. A host name
// that cannot be resolved stands in for a laptop that is offline: the route
// check fails before anything is sent. A free local port with nothing listening
// on it stands in for a connection that cannot be opened.
class DocumentCheckerHttpServerUnreachableTest {
  // Offline, a check ends at once with no matches instead of waiting on the
  // open connection.
  @Test
  fun testRouteCheckEndsCheckAtOnce() {
    val start: Long = System.currentTimeMillis()
    val (matches, _) = check(settings("http://nonexistent-host.invalid"))
    val elapsedMillis: Long = System.currentTimeMillis() - start
    assertEquals(emptyList(), matches)
    assertTrue(elapsedMillis < 5_000, "check took $elapsedMillis ms")
  }

  @Test
  fun testRefusedConnectionReportsNoMatches() {
    val (matches, _) = check(settings("http://127.0.0.1:${freePort()}"))
    assertEquals(emptyList(), matches)
  }

  companion object {
    private fun settings(uri: String): Settings =
      Settings(_languageShortCode = "en-US", _languageToolHttpServerUri = uri)

    private fun check(
      settings: Settings,
    ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
      val checker = DocumentChecker(SettingsManager(settings), FragmentCache())
      return checker.check(
        LtexTextDocumentItem(
          LtexLanguageServer(),
          "untitled:unreachable.md",
          "markdown",
          1,
          "This is a sentence with a qwertyunknown word in it.\n",
        ),
      )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
  }
}
