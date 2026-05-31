/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.settings.Settings
import org.languagetool.markup.AnnotatedTextBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class FragmentCacheTest {
  private fun cachedFragment(): CachedFragment =
    CachedFragment(AnnotatedTextBuilder().build(), emptyList(), "en-US")

  @Test
  fun testGetPutRefreshesTimestamp() {
    var now = 1000L
    val cache = FragmentCache { now }
    val key = FragmentCacheKey("uri", "hash")
    val entry: CachedFragment = cachedFragment()

    cache.put(key, entry)
    assertEquals(1000L, entry.lastAccessMillis)

    now = 5000L
    assertSame(entry, cache.get(key))
    assertEquals(5000L, entry.lastAccessMillis, "get() must refresh lastAccess")
  }

  @Test
  fun testGetMissReturnsNull() {
    val cache = FragmentCache { 0L }
    assertNull(cache.get(FragmentCacheKey("uri", "absent")))
  }

  @Test
  fun testEvictIdleOlderThan() {
    var now = 0L
    val cache = FragmentCache { now }
    val stale = FragmentCacheKey("uri", "stale")
    val fresh = FragmentCacheKey("uri", "fresh")

    cache.put(stale, cachedFragment()) // lastAccess = 0
    now = 10_000L
    cache.put(fresh, cachedFragment()) // lastAccess = 10000

    now = 12_000L
    cache.evictIdleOlderThan(5_000L) // cutoff = 7000: stale(0) evicted, fresh(10000) kept

    assertNull(cache.get(stale))
    assertNotNull(cache.get(fresh))
  }

  @Test
  fun testRefreshedEntrySurvivesEviction() {
    var now = 0L
    val cache = FragmentCache { now }
    val key = FragmentCacheKey("uri", "k")
    cache.put(key, cachedFragment())

    now = 10_000L
    cache.get(key) // refresh to 10000

    now = 12_000L
    cache.evictIdleOlderThan(5_000L) // cutoff = 7000; entry at 10000 survives
    assertNotNull(cache.get(key))
  }

  @Test
  fun testRemoveByUriIsolation() {
    val cache = FragmentCache { 0L }
    val a1 = FragmentCacheKey("uriA", "1")
    val a2 = FragmentCacheKey("uriA", "2")
    val b1 = FragmentCacheKey("uriB", "1")
    cache.put(a1, cachedFragment())
    cache.put(a2, cachedFragment())
    cache.put(b1, cachedFragment())
    assertEquals(3, cache.size)

    cache.removeByUri("uriA")

    assertEquals(1, cache.size)
    assertNull(cache.get(a1))
    assertNull(cache.get(a2))
    assertNotNull(cache.get(b1))
  }

  @Test
  fun testMakeKeyUriScoping() {
    val fragment = CodeFragment("plaintext", "hello world", 0, Settings())
    val keyA: FragmentCacheKey = FragmentCache.makeKey("uriA", fragment)
    val keyB: FragmentCacheKey = FragmentCache.makeKey("uriB", fragment)
    assertNotEquals(keyA, keyB)
    // Same content, different URI field: identical hash, distinct key.
    assertEquals(keyA.contentHash, keyB.contentHash)
  }

  @Test
  fun testMakeKeyContentSensitivity() {
    val k1 = FragmentCache.makeKey("uri", CodeFragment("plaintext", "hello", 0, Settings()))
    val k2 = FragmentCache.makeKey("uri", CodeFragment("plaintext", "world", 0, Settings()))
    assertNotEquals(k1, k2)
  }

  @Test
  fun testMakeKeyIgnoresFromPos() {
    // fromPos must NOT be part of the key — that is what lets a shifted but
    // otherwise unchanged fragment stay a cache hit.
    val k1 = FragmentCache.makeKey("uri", CodeFragment("plaintext", "hello", 0, Settings()))
    val k2 = FragmentCache.makeKey("uri", CodeFragment("plaintext", "hello", 999, Settings()))
    assertEquals(k1, k2)
  }

  @Test
  fun testMakeKeySettingsSensitivity() {
    val key = { settings: Settings ->
      FragmentCache.makeKey("uri", CodeFragment("plaintext", "x", 0, settings))
    }
    val base = Settings()
    assertNotEquals(key(base), key(Settings(_languageShortCode = "de-DE")))
    assertNotEquals(key(base), key(Settings(_allDictionaries = mapOf("en-US" to setOf("x")))))
  }

  @Test
  fun testMakeKeyExcludesTtl() {
    // TTL is retention-only and must not invalidate cached results.
    val k1 = FragmentCache.makeKey("uri", CodeFragment("plaintext", "x", 0, Settings()))
    val k2 =
      FragmentCache.makeKey(
        "uri",
        CodeFragment("plaintext", "x", 0, Settings(_paragraphCacheTtlMinutes = 999L)),
      )
    assertEquals(k1, k2)
  }

  @Test
  fun testMakeKeyOrderIndependentForRebuiltSettings() {
    // Settings are rebuilt from JSON on every check; collections may differ in
    // iteration order. The fingerprint must be order-independent.
    val s1 = Settings(_enabled = linkedSetOf("a", "b", "c"))
    val s2 = Settings(_enabled = linkedSetOf("c", "b", "a"))
    assertEquals(
      FragmentCache.makeKey("uri", CodeFragment("plaintext", "x", 0, s1)),
      FragmentCache.makeKey("uri", CodeFragment("plaintext", "x", 0, s2)),
    )
  }
}
