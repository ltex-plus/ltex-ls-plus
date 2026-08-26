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
 * If these tests fail in CI, the maintainer has three options:
 *
 *   - Re-probe [SAMPLE_TEXT]. These tests assert live Premium behavior on one
 *     fixed passage, so LanguageTool retraining its QB_NEW_ and AI_ rule
 *     families can break them without any change on our side. The notes on
 *     that constant say what to check and why each property is load-bearing.
 *   - Rotate the credentials (renewed subscription, API key cycled, ...).
 *   - Delete LANGUAGETOOL_USERNAME and LANGUAGETOOL_API_KEY from the repository
 *     secrets. The tests will then auto-skip, keeping CI green without any
 *     code change.
 *
 * The assertion-failure message produced by [withPremiumFailureHint] repeats
 * this so a future maintainer doesn't need to open the source to recover.
 *
 * The behavior under test is Premium's QB_NEW_ rule family returning spans that
 * include adjacent punctuation: for one misspelling in three contexts it emits
 * `enviroment` (length 10), `enviroment!` (length 11) and `"enviroment"`
 * (length 12). DictionaryWord.normalize is applied on the add path, so the
 * stored entry is the bare word, and again on the check path, so one stored
 * entry suppresses every punctuation variant — see
 * LanguageToolInterface.isCoveredByDictionary.
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
          "\"$SAMPLE_TEXT\" (the misspelling \"$MISSPELLING\" appears in three " +
          "punctuation contexts), but got: $spans.",
      )

      val withPunct: List<String> = spans.filter { it != DictionaryWord.normalize(it) }
      assertTrue(
        withPunct.isNotEmpty(),
        "Expected at least one returned span to include adjacent punctuation " +
          "(here \"$MISSPELLING!\" and \"\\\"$MISSPELLING\\\"\"), but every " +
          "span was already bare. " +
          "Observed spans: $spans. Either Premium behavior changed or " +
          "the current subscription tier no longer emits punctuation-inclusive " +
          "spans for this input — re-evaluate whether the normalization fix " +
          "is still needed.",
      )

      val normalizedSet: Set<String> = spans.map { DictionaryWord.normalize(it) }.toSet()
      assertTrue(
        normalizedSet == setOf(MISSPELLING),
        "After normalization every span should reduce to the same bare word " +
          "\"$MISSPELLING\", but the normalized set is $normalizedSet (from spans $spans).",
      )
    }

  @Test
  fun testPremiumPunctuationSpansSuppressedAfterBareWordAdd() =
    withPremiumFailureHint {
      val spans: List<String> =
        collectMatchSpans(buildSettings(dictionary = setOf(MISSPELLING)))

      assertTrue(
        spans.isEmpty(),
        "After adding the bare word \"$MISSPELLING\" to the en-US dictionary, " +
          "every Premium variant (with or without adjacent punctuation) should " +
          "be suppressed. Still seeing spans: $spans — the check-path " +
          "normalization in LanguageToolInterface.isCoveredByDictionary no " +
          "longer reduces every variant to the stored bare word.",
      )
    }

  @Test
  fun testPremiumVerdictIsUnchangedByAcceptingAWord() =
    withPremiumFailureHint {
      // The invariant, stated as a comparison rather than as "no matches":
      // accepting a word must never change what Premium reports about the rest
      // of the sentence. Asserting emptiness alone would be vacuous here,
      // because this sentence is clean either way — it would pass even if
      // dictionary handling did nothing.
      //
      // This is the case a Premium user reported. Substituting an invented
      // placeholder for the entry made Premium object to the placeholder
      // instead: `Dummy0` mid-sentence drew QB_NEW_EN_DECAPITALIZE_ERROR_IDS_6,
      // a diagnostic on the very word the user had accepted. A single-word
      // entry is now left in the text, so there is nothing to object to.
      val before: List<String> = collectMatchSpans(buildSettings(), text = REPORTED_TEXT)
      val after: List<String> =
        collectMatchSpans(
          buildSettings(dictionary = setOf(REPORTED_ENTRY)),
          text = REPORTED_TEXT,
        )

      assertTrue(
        after == before,
        "Accepting \"$REPORTED_ENTRY\" changed what Premium reports for " +
          "\"$REPORTED_TEXT\": $before without the entry, $after with it. " +
          "Accepting a word may only ever remove a diagnostic on that word; " +
          "anything appearing or moving means the text we send no longer says " +
          "what the user wrote.",
      )
    }

  companion object {
    private const val PREMIUM_ENDPOINT: String = "https://api.languagetoolplus.com"

    // Reported by a Premium user (2026-08-23). Verified against the endpoint:
    // the sentence is clean on its own, so anything reported once
    // `eigenstates` is accepted would be an artefact of how we handle the entry.
    private const val REPORTED_ENTRY: String = "eigenstates"
    private const val REPORTED_TEXT: String =
      "The $REPORTED_ENTRY of Eq. 1 are Fock states."

    private const val MISSPELLING: String = "enviroment"

    // Probed against api.languagetoolplus.com on 2026-08-23 (four repeat runs,
    // identical every time): this text yields exactly three matches, one per
    // occurrence, each in a different punctuation context:
    //   'enviroment'    (bare, length 10)
    //   'enviroment!'   (with trailing !, length 11)
    //   '"enviroment"'  (surrounded by quotes, length 12)
    // The two punctuation-inclusive spans are the whole reason
    // DictionaryWord.normalize exists: all three normalize to "enviroment", so
    // one stored entry has to cover them, which is what the tests below check.
    //
    // A single-word entry is not substituted before checking, so the second test
    // exercises the check-path suppression on the real word in all three
    // contexts. Re-probe this text before changing it: the assertions depend on
    // Premium still emitting a punctuation-inclusive span for at least one
    // occurrence, which is its behavior and not ours.
    private const val SAMPLE_TEXT: String =
      "The report repeats $MISSPELLING later. Our team called it $MISSPELLING! " +
        "The summary lists \"$MISSPELLING\" as the top risk."

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

    private fun buildSettings(dictionary: Set<String> = emptySet()): Settings {
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

    private fun collectMatchSpans(
      settings: Settings,
      text: String = SAMPLE_TEXT,
    ): List<String> {
      val settingsManager = SettingsManager(settings)
      val documentChecker = DocumentChecker(settingsManager)
      val document: LtexTextDocumentItem =
        DocumentCheckerTest.createDocument("markdown", text)
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
