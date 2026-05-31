/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.parsing

import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.settings.Settings
import org.languagetool.markup.AnnotatedText
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

// A single, long-lived, thread-safe cache of per-fragment check results, keyed
// on (document URI, content+settings hash). Reusing an unchanged fragment's
// result skips both the AnnotatedText build and the LanguageTool check. This is
// correctness-neutral relative to the previous behavior: DocumentChecker already
// checks each fragment in isolation, so caching changes only *which* fragments
// are re-run, not what any single check sees.
//
// Document scoping: the URI is a separate key field (not folded into the hash)
// so removeByUri can drop a closed document's entries on didClose. Two documents
// containing the same fragment never share an entry.
//
// Eviction: the server sweeps periodically via evictIdleOlderThan; every get()
// refreshes the entry's lastAccess, so fragments of actively edited documents
// stay warm while idle documents age out. Keeping superseded entries within the
// TTL window is what makes undo/redo and revisiting earlier content cache hits.

data class FragmentCacheKey(
  val uri: String,
  val contentHash: String,
)

// A snapshot of cache occupancy for debug logging: how many fragments are held
// and the total length of the checked plain text across them (UTF-16 code units,
// i.e. String.length, not bytes).
data class CacheStats(
  val entryCount: Int,
  val plainTextChars: Long,
)

class CachedFragment(
  val annotatedText: AnnotatedText,
  // FRAGMENT-RELATIVE match offsets, post-filter (ignored matches already
  // removed). The caller re-adds the fragment's current fromPos on every use,
  // so an unchanged fragment that merely shifted in the document is still a hit.
  val relativeMatches: List<LanguageToolRuleMatch>,
  // The language actually used to check this fragment. For ltex.language="auto"
  // this is the detected language; restoring it on a hit lets us skip detection.
  val detectedLanguageShortCode: String,
) {
  @Volatile
  var lastAccessMillis: Long = 0
}

class FragmentCache(
  // Injectable for deterministic TTL/eviction tests; defaults to wall-clock.
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val map = ConcurrentHashMap<FragmentCacheKey, CachedFragment>()

  val size: Int
    get() = this.map.size

  fun get(key: FragmentCacheKey): CachedFragment? =
    this.map[key]?.also { it.lastAccessMillis = this.clock() }

  fun put(
    key: FragmentCacheKey,
    entry: CachedFragment,
  ) {
    entry.lastAccessMillis = this.clock()
    this.map[key] = entry
  }

  // Called on didClose to drop a closed document's fragments eagerly. O(n) over
  // the map, the same cost as the sweeper's pass.
  fun removeByUri(uri: String) {
    this.map.keys.removeIf { it.uri == uri }
  }

  fun evictIdleOlderThan(ttlMillis: Long) {
    val cutoff: Long = this.clock() - ttlMillis
    this.map.entries.removeIf { it.value.lastAccessMillis < cutoff }
  }

  // Debug/observability only (see DocumentChecker's FINEST cache log). Walks the
  // whole map summing checked plain-text lengths; not optimized because it runs
  // only when FINEST logging is on. Pass null for uri to measure the entire cache.
  fun stats(uri: String? = null): CacheStats {
    var entryCount = 0
    var plainTextChars = 0L

    for ((key: FragmentCacheKey, entry: CachedFragment) in this.map) {
      if (uri != null && key.uri != uri) continue
      entryCount++
      plainTextChars += entry.annotatedText.plainText.length
    }

    return CacheStats(entryCount, plainTextChars)
  }

  companion object {
    // ASCII US (Unit Separator, 0x1F): joins fields into the hash input. US is
    // the ASCII control code defined precisely for separating fields within a
    // record, and it cannot appear in the fields it joins (codeLanguageId and
    // ruleId are identifier-like), so distinct field tuples can never concatenate
    // to the same string (which would be a cache-key collision). Written as a
    // \u escape, not a literal control byte, so it stays readable in source.
    private const val FIELD_SEPARATOR = '\u001F'

    fun makeKey(
      uri: String,
      codeFragment: CodeFragment,
    ): FragmentCacheKey = FragmentCacheKey(uri, computeContentHash(codeFragment))

    private fun computeContentHash(codeFragment: CodeFragment): String {
      val builder = StringBuilder()
      builder.append(codeFragment.codeLanguageId).append(FIELD_SEPARATOR)
      builder.append(codeFragment.code).append(FIELD_SEPARATOR)
      appendSettingsFingerprint(builder, codeFragment.settings)
      val digest: ByteArray =
        MessageDigest.getInstance("SHA-256").digest(builder.toString().toByteArray(Charsets.UTF_8))
      return HexFormat.of().formatHex(digest)
    }

    // A safe, order-independent fingerprint of every setting that can affect a
    // fragment's check result. Collections are sorted so two equal Settings
    // instances (rebuilt from JSON on each check) produce the same string.
    //
    // The fingerprint deliberately errs toward over-inclusion: result-irrelevant
    // settings (logLevel, completionEnabled, diagnosticSeverity, checkFrequency,
    // clearDiagnosticsWhenClosingFile), the retention-only
    // paragraphCacheTtlMinutes, and the cache-control paragraphCacheEnabled are
    // excluded; everything else that plausibly changes matches or text extraction
    // is included. Over-inclusion only costs an occasional extra recheck, never a
    // stale result. Narrowing is a future optimization; any narrowing MUST keep
    // everything a magic command can override so inline ltex: settings still
    // invalidate correctly.
    //
    // maxRequestSize is also excluded: it only changes how contiguous cache
    // misses are batched into LanguageTool requests, never the per-paragraph
    // cache unit or its result. Checking a paragraph alone vs inside a batch
    // yields identical matches (LanguageTool has no cross-paragraph rules), so a
    // changed value must NOT invalidate cached paragraphs.
    private fun appendSettingsFingerprint(
      builder: StringBuilder,
      settings: Settings,
    ) {
      builder.append("lang=").append(settings.languageShortCode).append('\n')
      builder.append("enabled=").append(settings.enabled.sorted()).append('\n')
      builder.append("dictionary=").append(settings.dictionary.sorted()).append('\n')
      builder.append("disabledRules=").append(settings.disabledRules.sorted()).append('\n')
      builder.append("enabledRules=").append(settings.enabledRules.sorted()).append('\n')
      builder
        .append("hiddenFalsePositives=")
        .append(
          settings.hiddenFalsePositives
            .map { "${it.ruleId}$FIELD_SEPARATOR${it.sentenceRegexString}" }
            .sorted(),
        ).append('\n')
      builder.append("enablePickyRules=").append(settings.enablePickyRules).append('\n')
      builder.append("motherTongue=").append(settings.motherTongueShortCode).append('\n')
      builder.append("preferredVariants=").append(settings.preferredVariants).append('\n')
      builder.append("bibtexFields=").append(sortedMapString(settings.bibtexFields)).append('\n')
      builder.append("latexCommands=").append(sortedMapString(settings.latexCommands)).append('\n')
      builder
        .append("latexEnvironments=")
        .append(sortedMapString(settings.latexEnvironments))
        .append('\n')
      builder.append("markdownNodes=").append(sortedMapString(settings.markdownNodes)).append('\n')
      builder
        .append("languageModelRulesDirectory=")
        .append(settings.languageModelRulesDirectory)
        .append('\n')
      builder
        .append("languageToolHttpServerUri=")
        .append(settings.languageToolHttpServerUri)
        .append('\n')
      builder
        .append("languageToolOrgUsername=")
        .append(settings.languageToolOrgUsername)
        .append('\n')
      builder.append("languageToolOrgApiKey=").append(settings.languageToolOrgApiKey).append('\n')
      builder.append("sentenceCacheSize=").append(settings.sentenceCacheSize).append('\n')
    }

    private fun sortedMapString(map: Map<String, *>): String =
      map.entries
        .map { "${it.key}=${it.value}" }
        .sorted()
        .toString()
  }
}
