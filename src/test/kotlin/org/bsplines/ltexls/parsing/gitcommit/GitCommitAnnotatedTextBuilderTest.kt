/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.gitcommit

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilderTest
import kotlin.test.Test

class GitCommitAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("git-commit") {
  @Test
  fun test() {
    assertPlainText(
      """
      This is a test. # abc
        #	Comment
      # Another comment
      This is another test.

      """.trimIndent(),
      "This is a test. # abc\n\n\nThis is another test.\n",
    )
  }
}
