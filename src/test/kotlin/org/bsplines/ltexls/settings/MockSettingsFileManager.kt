/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.settings

import org.bsplines.ltexls.server.LtexLanguageServer
import java.nio.file.Path

class MockSettingsFileManager : SettingsFileManager.Rooted {
  override fun loadFile(path: Path): List<String> =
    throw UnsupportedOperationException("MockSettingsFileManager does not support loading files")

  override fun resolve(path: String): LtexLanguageServer.Canonical<String> =
    throw UnsupportedOperationException("MockSettingsFileManager does not support resolving paths")

  override fun rooted(root: Path): SettingsFileManager.Rooted =
    throw UnsupportedOperationException("MockSettingsFileManager does not support explicit roots")
}
