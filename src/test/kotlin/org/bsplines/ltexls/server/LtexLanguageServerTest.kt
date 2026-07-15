/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals

class LtexLanguageServerTest {
  @Test
  fun testDeepestWorkspaceRootSelection() {
    val server = LtexLanguageServer()

    val tempDir = Files.createTempDirectory("ltex-root-test")
    val subProjectDir = tempDir.resolve("subproject")
    subProjectDir.createDirectories()

    try {
      val root1 =
        LtexLanguageServer.Canonical
          .from(WorkspaceFolder(tempDir.toUri().toString(), "root"))!!
      val root2 =
        LtexLanguageServer.Canonical
          .from(WorkspaceFolder(subProjectDir.toUri().toString(), "sub"))!!

      server.workspaceRoots = listOf(root1, root2)

      // Nested file should resolve to the subproject root
      val nestedFile = subProjectDir.resolve("src/Main.kt")
      nestedFile.parent.createDirectories()
      Files.createFile(nestedFile)

      val resolvedRoot = server.relativePathRoot(nestedFile.toUri().toString())
      assertEquals(subProjectDir.toRealPath(), resolvedRoot.toRealPath())
    } finally {
      Files.deleteIfExists(subProjectDir.resolve("src/Main.kt"))
      Files.deleteIfExists(subProjectDir.resolve("src"))
      Files.deleteIfExists(subProjectDir)
      Files.deleteIfExists(tempDir)
    }
  }

  @Test
  fun testExternalFileFallback() {
    val server = LtexLanguageServer()

    val tempDir = Files.createTempDirectory("ltex-ext-test")
    val extDir = Files.createTempDirectory("ltex-ext-other")
    try {
      val root =
        LtexLanguageServer.Canonical
          .from(WorkspaceFolder(tempDir.toUri().toString(), "root"))!!

      server.workspaceRoots = listOf(root)

      val externalFile = extDir.resolve("doc.md")
      Files.createFile(externalFile)
      val resolvedRoot = server.relativePathRoot(externalFile.toUri().toString())

      // Should fall back to user home directory
      val expectedHome = Path.of(System.getProperty("user.home")).toRealPath()
      assertEquals(expectedHome, resolvedRoot.toRealPath())
    } finally {
      Files.deleteIfExists(extDir.resolve("doc.md"))
      Files.deleteIfExists(extDir)
      Files.deleteIfExists(tempDir)
    }
  }

  @Test
  fun testVirtualDocumentFallback() {
    val server = LtexLanguageServer()

    val tempDir = Files.createTempDirectory("ltex-virt-test")
    try {
      val root =
        LtexLanguageServer.Canonical
          .from(WorkspaceFolder(tempDir.toUri().toString(), "root"))!!

      server.workspaceRoots = listOf(root)
      val resolvedRoot = server.relativePathRoot("untitled:Untitled-1")
      assertEquals(tempDir.toRealPath(), resolvedRoot.toRealPath())
    } finally {
      Files.deleteIfExists(tempDir)
    }
  }
}
