/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import com.sun.net.httpserver.HttpServer
import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.parsing.FragmentCache
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// What happens when the LanguageTool HTTP server cannot be reached, with and
// without ltex.languageToolHttpServerOfflineFallback. A host name that cannot
// be resolved stands in for a laptop that is offline: the route check fails
// before anything is sent. A free local port with nothing listening on it
// stands in for a connection that cannot be opened.
class DocumentCheckerHttpServerUnreachableTest {
  @Test
  fun testFallbackChecksLocallyWhenServerCannotBeConnectedTo() {
    val (matches, _) = check(settings(unreachableUri(), fallback = true, language = "en-US"))
    assertTrue(matches.any { it.isUnknownWordRule() }, "expected a spelling match, got $matches")
  }

  // A host name that cannot be resolved, as for every host once the laptop is
  // offline and Java's cached address has expired. The route check fails
  // before anything is sent.
  @Test
  fun testFallbackWhenHostCannotBeResolved() {
    val (matches, _) =
      check(settings("http://nonexistent-host.invalid", fallback = true, language = "en-US"))
    assertTrue(matches.any { it.isUnknownWordRule() }, "expected a spelling match, got $matches")
  }

  // The route check runs for everyone using an HTTP server: offline, a check
  // ends at once with no matches instead of waiting on the open connection.
  @Test
  fun testRouteCheckEndsCheckAtOnce() {
    val start: Long = System.currentTimeMillis()
    val (matches, _) =
      check(settings("http://nonexistent-host.invalid", fallback = null, language = "en-US"))
    val elapsedMillis: Long = System.currentTimeMillis() - start
    assertEquals(emptyList(), matches)
    assertTrue(elapsedMillis < 5_000, "check took $elapsedMillis ms")
  }

  @Test
  fun testRefusedConnectionReportsNoMatches() {
    val (matches, _) = check(settings(unreachableUri(), fallback = null, language = "en-US"))
    assertEquals(emptyList(), matches)
  }

  // A server that accepts connections and never answers: the route check
  // passes, and the check falls back once
  // ltex.languageToolHttpServerTimeoutSeconds runs out.
  @Test
  fun testFallbackWhenConnectionStopsAnswering() {
    val (serverSocket, acceptedSockets) = startSilentServer()

    try {
      val start: Long = System.currentTimeMillis()
      val (matches, _) =
        check(
          settings(
            "http://127.0.0.1:${serverSocket.localPort}",
            fallback = true,
            language = "en-US",
            timeoutSeconds = 1,
          ),
        )
      val elapsedMillis: Long = System.currentTimeMillis() - start
      assertTrue(matches.any { it.isUnknownWordRule() }, "expected a spelling match, got $matches")
      assertTrue(elapsedMillis < 30_000, "fallback took $elapsedMillis ms")
    } finally {
      serverSocket.close()
      acceptedSockets.forEach { it.close() }
    }
  }

  // A slow check within ltex.languageToolHttpServerTimeoutSeconds (default
  // 60 s) waits for the server and does not fall back.
  @Test
  fun testNoFallbackWhenCheckIsSlow() {
    val server: HttpServer = startSlowCheckServer(4_000)

    try {
      val (matches, _) = check(settings(serverUri(server), fallback = true, language = "en-US"))
      // The bundled LanguageTool would report the misspelling; the server reports nothing.
      assertEquals(emptyList(), matches)
    } finally {
      stopServer(server)
    }
  }

  // ltex.languageToolHttpServerTimeoutSeconds with the fallback off: a check
  // whose answer never comes ends after the limit with no matches, instead of
  // waiting forever.
  @Test
  fun testTimeoutEndsCheckWithoutFallback() {
    val (serverSocket, acceptedSockets) = startSilentServer()

    try {
      val start: Long = System.currentTimeMillis()
      val (matches, _) =
        check(
          settings(
            "http://127.0.0.1:${serverSocket.localPort}",
            fallback = null,
            language = "en-US",
            timeoutSeconds = 1,
          ),
        )
      val elapsedMillis: Long = System.currentTimeMillis() - start
      assertEquals(emptyList(), matches)
      assertTrue(elapsedMillis < 10_000, "check took $elapsedMillis ms")
    } finally {
      serverSocket.close()
      acceptedSockets.forEach { it.close() }
    }
  }

  // With the fallback on, a check that is not answered within the limit is
  // checked with the bundled LanguageTool.
  @Test
  fun testTimeoutFallsBackWhenFallbackIsOn() {
    val server: HttpServer = startSlowCheckServer(4_000)

    try {
      val (matches, _) =
        check(
          settings(serverUri(server), fallback = true, language = "en-US", timeoutSeconds = 2),
        )
      assertTrue(matches.any { it.isUnknownWordRule() }, "expected a spelling match, got $matches")
    } finally {
      stopServer(server)
    }
  }

  // The HTTP backend sends "auto" to the server; the bundled LanguageTool cannot
  // take it, so the fallback must detect the language itself.
  @Test
  fun testFallbackDetectsLanguageForAuto() {
    val (matches, fragments) = check(settings(unreachableUri(), fallback = true, language = "auto"))
    assertTrue(matches.any { it.isUnknownWordRule() }, "expected a spelling match, got $matches")
    assertEquals("en-US", fragments.single().codeFragment.languageShortCode)
  }

  // Wrong credentials are answered with HTTP 403: the server was reached, so the
  // error is reported as before and the bundled LanguageTool is not used.
  @Test
  fun testNoFallbackOnHttpErrorResponse() {
    val server = startServer(403, "AuthException")
    try {
      val (matches, _) = check(settings(serverUri(server), fallback = true, language = "en-US"))
      assertEquals(emptyList(), matches)
    } finally {
      server.stop(0)
    }
  }

  // Matches checked offline are cached under the same key as matches from the
  // server. Once the server answers again, the cache is cleared so unchanged
  // paragraphs are sent to the server on the next check.
  @Test
  fun testCacheIsClearedWhenServerIsReachableAgain() {
    val port: Int = freePort()
    val uri = "http://127.0.0.1:$port"
    val cache = FragmentCache()
    val checker =
      DocumentChecker(
        SettingsManager(settings(uri, fallback = true, language = "en-US")),
        cache,
      )
    val offlineText = "This is qwertyunknownone.\n\nThis is qwertyunknowntwo.\n"

    val (offlineMatches, _) = checker.check(createDocument(offlineText))
    assertEquals(2, offlineMatches.count { it.isUnknownWordRule() })
    assertEquals(2, cache.size)

    val requestCount = AtomicInteger()
    val server = startServer(200, EMPTY_RESPONSE, port, requestCount)
    try {
      // Only the edited second paragraph misses the cache and reaches the server.
      checker.check(createDocument("This is qwertyunknownone.\n\nThis is fine.\n"))
      assertEquals(1, requestCount.get())
      assertEquals(1, cache.size)

      // The first paragraph's offline result was dropped, so it is sent now.
      val (onlineMatches, _) =
        checker.check(createDocument("This is qwertyunknownone.\n\nThis is fine.\n"))
      assertEquals(2, requestCount.get())
      assertEquals(emptyList(), onlineMatches)
    } finally {
      server.stop(0)
    }
  }

  companion object {
    private const val EMPTY_RESPONSE = """{"language":{"code":"en-US"},"matches":[]}"""

    private fun settings(
      uri: String,
      fallback: Boolean?,
      language: String,
      timeoutSeconds: Long? = null,
    ): Settings =
      Settings(
        _languageShortCode = language,
        _languageToolHttpServerUri = uri,
        _languageToolHttpServerOfflineFallback = fallback,
        _languageToolHttpServerTimeoutSeconds = timeoutSeconds,
      )

    // Accepts connections and never answers, like an open connection after the
    // laptop lost its network.
    private fun startSilentServer(): Pair<ServerSocket, MutableList<Socket>> {
      val serverSocket = ServerSocket(0)
      val acceptedSockets: MutableList<Socket> =
        java.util.Collections.synchronizedList(ArrayList<Socket>())
      val acceptor =
        Thread {
          try {
            while (true) acceptedSockets.add(serverSocket.accept())
          } catch (ignored: java.io.IOException) {
            // serverSocket closed at the end of the test
          }
        }
      acceptor.isDaemon = true
      acceptor.start()
      return Pair(serverSocket, acceptedSockets)
    }

    // Answers a check after delayMillis.
    private fun startSlowCheckServer(delayMillis: Long): HttpServer {
      val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.executor = Executors.newCachedThreadPool()
      server.createContext("/v2/check") { exchange ->
        try {
          Thread.sleep(delayMillis)
          val bytes: ByteArray = EMPTY_RESPONSE.toByteArray()
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        } catch (ignored: InterruptedException) {
          // server stopped at the end of the test
        } catch (ignored: java.io.IOException) {
          // the client closed the connection after its timeout
        }
      }
      server.start()
      return server
    }

    private fun stopServer(server: HttpServer) {
      server.stop(0)
      (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }

    private fun check(
      settings: Settings,
    ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
      val checker = DocumentChecker(SettingsManager(settings), FragmentCache())
      return checker.check(
        createDocument(
          "This is a sentence with a qwertyunknown word in it. " +
            "The rest of this paragraph is written in plain English.\n",
        ),
      )
    }

    private fun createDocument(code: String): LtexTextDocumentItem =
      LtexTextDocumentItem(LtexLanguageServer(), "untitled:offline.md", "markdown", 1, code)

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun unreachableUri(): String = "http://127.0.0.1:${freePort()}"

    private fun serverUri(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"

    private fun startServer(
      statusCode: Int,
      body: String,
      port: Int = 0,
      requestCount: AtomicInteger = AtomicInteger(),
    ): HttpServer {
      val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
      server.createContext("/v2/check") { exchange ->
        requestCount.incrementAndGet()
        val bytes: ByteArray = body.toByteArray()
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.start()
      return server
    }
  }
}
