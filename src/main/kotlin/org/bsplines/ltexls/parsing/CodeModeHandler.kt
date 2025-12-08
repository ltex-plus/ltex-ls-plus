/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

class CodeModeHandler {
  var bracketsCounter = 0
    private set
  var stringCounter = 0
  var squareBracketscounter = 0
  var codeModeString = false
  var codeModeContentBlock = false
  var mode = false

  fun adjustBracketsCounter(delta: Int) {
    if (!codeModeString) {
      bracketsCounter += delta
    }
    if (bracketsCounter == 0) {
      mode = false
    }
  }
}
