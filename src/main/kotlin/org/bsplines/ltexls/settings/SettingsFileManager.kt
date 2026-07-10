/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.settings

import org.bsplines.ltexls.server.LtexLanguageServer.Canonical
import org.bsplines.ltexls.settings.FileSettings.Item.Literal
import org.bsplines.ltexls.tools.FileIo
import java.io.File
import java.nio.file.Path
import kotlin.io.path.pathString

interface SettingsFileManager {
  fun loadFile(path: Path): List<String>

  fun <T> loadSettings(
    path: Path,
    parser: (String) -> T,
  ): List<Literal<T>> =
    loadFile(path)
      .map { Literal.parse(it, parser) }

  fun rooted(root: Path): Rooted = DefaultRooted(this, root)

  interface Rooted : SettingsFileManager {
    fun resolve(path: String): Canonical<String>?
  }

  class DefaultRooted(
    val manager: SettingsFileManager,
    val root: Path,
  ) : Rooted {
    override fun resolve(path: String): Canonical<String>? =
      Canonical.fromPath(
        when {
          path.isNotEmpty() && path.first() in arrayOf('~', '/') -> FileIo.normalizePath(path)
          else -> root.resolve(path).pathString
        },
      )

    override fun loadFile(path: Path): List<String> = this.manager.loadFile(path)
  }
}

class BasicSettingsFileManager : SettingsFileManager {
  override fun loadFile(path: Path): List<String> = File(path.pathString).readLines().toList()
}
