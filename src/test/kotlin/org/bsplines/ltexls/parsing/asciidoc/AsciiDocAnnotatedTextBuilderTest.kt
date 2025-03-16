/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing.asciidoc

import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilderTest
import kotlin.test.Test

class AsciiDocAnnotatedTextBuilderTest : CodeAnnotatedTextBuilderTest("asciidoc") {
  @Test
  fun testLists() {
    assertPlainText(
      """
      This is a test.
      """.trimIndent(),
      "This is a test.",
    )
  }
}
