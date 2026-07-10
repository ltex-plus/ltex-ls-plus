/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.languagetool

import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.server.DocumentChecker
import org.bsplines.ltexls.server.DocumentCheckerTest
import org.bsplines.ltexls.server.LtexTextDocumentItem
import org.bsplines.ltexls.settings.FileSettings
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Premium-endpoint integration tests.
 *
 * These tests hit api.languagetoolplus.com using real Premium credentials
 * and burn API quota, so they run only when both LANGUAGETOOL_USERNAME and
 * LANGUAGETOOL_API_KEY are present in the environment. Missing either
 * variable makes the whole class auto-skip via [RequirePremiumCredentials];
 * no failure, no CI noise.
 *
 * If these tests fail in CI, the maintainer has two options:
 *
 *   - Rotate the credentials (renewed subscription, API key cycled, ...).
 *   - Delete LANGUAGETOOL_USERNAME and LANGUAGETOOL_API_KEY from the repository
 *     secrets. The tests will then auto-skip, keeping CI green without any
 *     code change.
 *
 * The assertion-failure message produced by [withPremiumFailureHint] repeats
 * this so a future maintainer doesn't need to open the source to recover.
 *
 * The behavior under test is Premium's QB_NEW_* rule family returning spans
 * that include adjacent punctuation (e.g. `amazng!` length 7, bare `amazng`
 * length 6, `"amazng"` length 8 — all for the same misspelling in different
 * contexts). The DictionaryWord.normalize call on the add path and on the
 * check-path lookup key together ensure that a single stored entry `amazng`
 * suppresses every variant.
 */
@ExtendWith(RequirePremiumCredentials::class)
class LanguageToolPremiumIntegrationTest {
  @Test
  fun testPremiumEmitsSpansWithAdjacentPunctuation() =
    withPremiumFailureHint {
      val spans: List<String> = collectMatchSpans(buildSettings())

      assertTrue(
        spans.size >= 2,
        "Expected the Premium endpoint to return at least two matches for " +
          "\"$SAMPLE_TEXT\" (the misspelling \"amazng\" appears in multiple " +
          "punctuation contexts), but got: $spans.",
      )

      val withPunct: List<String> = spans.filter { it != DictionaryWord.normalize(it) }
      assertTrue(
        withPunct.isNotEmpty(),
        "Expected at least one returned span to include adjacent punctuation " +
          "(e.g. \"amazng!\" or \"\\\"amazng\\\"\"), but every span was already " +
          "bare. Observed spans: $spans. Either Premium behavior changed or " +
          "the current subscription tier no longer emits punctuation-inclusive " +
          "spans for this input — re-evaluate whether the normalization fix " +
          "is still needed.",
      )

      val normalizedSet: Set<String> = spans.map { DictionaryWord.normalize(it) }.toSet()
      assertTrue(
        normalizedSet == setOf("amazng"),
        "After normalization every span should reduce to the same bare word " +
          "\"amazng\", but the normalized set is $normalizedSet (from spans $spans).",
      )
    }

  @Test
  fun testPremiumPunctuationSpansSuppressedAfterBareWordAdd() =
    withPremiumFailureHint {
      val spans: List<String> =
        collectMatchSpans(buildSettings(dictionary = FileSettings.of("amazng")))

      assertTrue(
        spans.isEmpty(),
        "After adding the bare word \"amazng\" to the en-US dictionary, every " +
          "Premium variant (with or without adjacent punctuation) should be " +
          "suppressed. Still seeing spans: $spans.",
      )
    }

  companion object {
    private const val PREMIUM_ENDPOINT: String = "https://api.languagetoolplus.com"

    // Probed against api.languagetoolplus.com on 2026-05-15: this text
    // yields exactly three QB_NEW_EN_ORTHOGRAPHY_ERROR_IDS_1 matches:
    //   'amazng'    (bare, length 6)
    //   'amazng!'   (with trailing !, length 7)
    //   '"amazng"'  (surrounded by quotes, length 8)
    // All three normalize to "amazng". Other phrasings caused Premium's
    // QB_NEW_EN_MERGED_MATCH / QB_NEW_EN_OTHER to collapse the misspelling
    // with an adjacent legitimate word (e.g. "said amazng!"), which would
    // break the "every span normalizes to amazng" assertion below — avoid
    // changing this text without re-probing.
    private const val SAMPLE_TEXT: String =
      "I think amazng. Or amazng! Or even \"amazng\" works."

    private val FAILURE_HINT: String =
      """
      |Premium integration test failed.
      |
      |If the LanguageTool Premium subscription is still active:
      |  - Rotate LANGUAGETOOL_USERNAME / LANGUAGETOOL_API_KEY in the repo secrets.
      |
      |If the subscription has been discontinued:
      |  - Delete both secrets from the repository. The Premium tests will then
      |    auto-skip (RequirePremiumCredentials), keeping CI green without any
      |    code change.
      """.trimMargin()

    private fun buildSettings(dictionary: FileSettings<String> = FileSettings.of()): Settings {
      var settings =
        Settings(
          _languageShortCode = "en-US",
          _languageToolHttpServerUri = PREMIUM_ENDPOINT,
          _languageToolOrgUsername = System.getenv("LANGUAGETOOL_USERNAME"),
          _languageToolOrgApiKey = System.getenv("LANGUAGETOOL_API_KEY"),
        )
      if (dictionary.isNotEmpty()) {
        settings =
          settings.copy(
            _allDictionaries = mapOf("en-US" to dictionary),
          )
      }
      return settings
    }

    private fun collectMatchSpans(settings: Settings): List<String> {
      val settingsManager = SettingsManager(settings)
      val documentChecker = DocumentChecker(settingsManager)
      val document: LtexTextDocumentItem =
        DocumentCheckerTest.createDocument("markdown", SAMPLE_TEXT)
      val result: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
        documentChecker.check(document)
      val (matches, fragments) = result
      return matches.map { match ->
        val fragment: AnnotatedTextFragment =
          fragments.firstOrNull { it.codeFragment.contains(match) } ?: fragments[0]
        val offset: Int = fragment.codeFragment.fromPos
        fragment.getSubstringOfPlainText(match.fromPos - offset, match.toPos - offset)
      }
    }

    private fun <T> withPremiumFailureHint(block: () -> T): T =
      try {
        block()
      } catch (e: AssertionError) {
        throw AssertionError("$FAILURE_HINT\n\nOriginal failure: ${e.message}", e)
      } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
      ) {
        throw AssertionError(
          "$FAILURE_HINT\n\nOriginal failure: ${e.javaClass.simpleName}: ${e.message}",
          e,
        )
      }
  }
}

/**
 * JUnit 5 execution condition that disables the Premium integration tests when
 * LANGUAGETOOL_USERNAME or LANGUAGETOOL_API_KEY is missing, and — unlike the
 * vanilla [org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable] — prints
 * a clear one-time warning line to stdout so the skip is visible in the normal
 * `mvn test` console output rather than only in the XML surefire reports.
 */
class RequirePremiumCredentials : ExecutionCondition {
  override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
    val missing: List<String> =
      REQUIRED_ENV_VARS.filter { System.getenv(it).isNullOrEmpty() }
    if (missing.isEmpty()) {
      return ConditionEvaluationResult.enabled("Premium credentials present")
    }
    if (warningPrinted.compareAndSet(false, true)) {
      println(SKIP_MESSAGE.format(missing.joinToString(", ")))
    }
    return ConditionEvaluationResult.disabled(
      "Missing env var(s): ${missing.joinToString(", ")}",
    )
  }

  companion object {
    private val REQUIRED_ENV_VARS: List<String> =
      listOf("LANGUAGETOOL_USERNAME", "LANGUAGETOOL_API_KEY")
    private val warningPrinted: AtomicBoolean = AtomicBoolean(false)
    private val SKIP_MESSAGE: String =
      """
      |
      |⚠  Premium integration tests SKIPPED — missing env var(s): %s.
      |   Set LANGUAGETOOL_USERNAME and LANGUAGETOOL_API_KEY to exercise the
      |   Premium path against https://api.languagetoolplus.com.
      |
      """.trimMargin()
  }
}
