/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.apache.commons.text.StringEscapeUtils
import org.bsplines.ltexls.languagetool.LanguageToolInterface
import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.parsing.plaintext.PlaintextAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.program.ProgramCommentRegexs
import org.bsplines.ltexls.settings.HiddenFalsePositive
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import org.bsplines.ltexls.tools.I18n
import org.bsplines.ltexls.tools.Logging
import org.bsplines.ltexls.tools.Tools
import org.eclipse.lsp4j.Range
import org.languagetool.DetectedLanguage
import org.languagetool.language.identifier.SimpleLanguageIdentifier
import org.languagetool.markup.AnnotatedText
import org.languagetool.markup.AnnotatedTextBuilder
import org.languagetool.markup.TextPart
import java.io.OutputStream
import java.io.PrintStream
import java.time.Duration
import java.time.Instant
import java.util.logging.Level

class DocumentChecker(
  val settingsManager: SettingsManager,
) {
  private var simpleLanguageIdentifier: SimpleLanguageIdentifier

  init {
    // workaround bugs like https://github.com/languagetool-org/languagetool/issues/3181,
    // in which LT prints to stdout instead of stderr (this messes up the LSP communication
    // and results in a deadlock) => temporarily discard output to stdout
    val stdout: PrintStream = System.out
    System.setOut(
      PrintStream(
        object : OutputStream() {
          override fun write(b: Int) {
          }
        },
        false,
        "utf-8",
      ),
    )
    simpleLanguageIdentifier = SimpleLanguageIdentifier()
    System.setOut(stdout)
  }

  var lastCheckedDocument: LtexTextDocumentItem? = null
    private set

  private fun fragmentizeDocument(
    document: LtexTextDocumentItem,
    range: Range? = null,
  ): List<CodeFragment> {
    val codeFragmentizer: CodeFragmentizer = CodeFragmentizer.create(document.languageId)
    var code: String = document.text

    if (range != null) {
      code =
        code.substring(
          document.convertPosition(range.start),
          document.convertPosition(range.end),
        )
    }

    return codeFragmentizer.fragmentize(code, this.settingsManager.settings)
  }

  private fun buildAnnotatedTextFragments(
    codeFragments: List<CodeFragment>,
    document: LtexTextDocumentItem,
    rangeStartPos: Int? = null,
  ): List<AnnotatedTextFragment> {
    val annotatedTextFragments = ArrayList<AnnotatedTextFragment>()
    val hasRange: Boolean = (rangeStartPos != null)

    for (codeFragment: CodeFragment in codeFragments) {
      if (
        shouldSkipCheck(codeFragment.codeLanguageId, codeFragment.settings, rangeStartPos)
      ) {
        // Fragment will be skipped at the check phase (e.g., region after
        // "# ltex: enabled=false"). Skip parsing too — running the
        // CodeAnnotatedTextBuilder over a large disabled region is pure
        // overhead, and on big files (>100KB) it dominates request latency.
        annotatedTextFragments.add(
          AnnotatedTextFragment(AnnotatedTextBuilder().build(), codeFragment, document),
        )
        continue
      }

      val builder: CodeAnnotatedTextBuilder =
        if (
          hasRange && ProgramCommentRegexs.isSupportedCodeLanguageId(codeFragment.codeLanguageId)
        ) {
          PlaintextAnnotatedTextBuilder(codeFragment.codeLanguageId)
        } else {
          CodeAnnotatedTextBuilder.create(codeFragment.codeLanguageId)
        }

      builder.setSettings(codeFragment.settings)
      builder.addCode(codeFragment.code)
      val curAnnotatedText: AnnotatedText = builder.build()
      annotatedTextFragments.add(AnnotatedTextFragment(curAnnotatedText, codeFragment, document))
    }

    return annotatedTextFragments
  }

  private fun checkAnnotatedTextFragments(
    annotatedTextFragments: List<AnnotatedTextFragment>,
    rangeStartPos: Int? = null,
  ): List<LanguageToolRuleMatch> {
    val matches = ArrayList<LanguageToolRuleMatch>()

    for (annotatedTextFragment: AnnotatedTextFragment in annotatedTextFragments) {
      matches.addAll(checkAnnotatedTextFragment(annotatedTextFragment, rangeStartPos))
    }

    return matches
  }

  @Suppress("TooGenericExceptionCaught")
  private fun checkAnnotatedTextFragment(
    annotatedTextFragment: AnnotatedTextFragment,
    rangeStartPos: Int? = null,
  ): List<LanguageToolRuleMatch> {
    val codeFragment: CodeFragment = annotatedTextFragment.codeFragment
    var settings: Settings = codeFragment.settings

    // On the HTTP path we let "auto" propagate to the server, which has a better
    // (ngram/fasttext) detector and honours preferredVariants. LanguageToolHttpInterface
    // back-fills codeFragment.languageShortCode from the response. Only the Java backend
    // needs local resolution, because LT does not support language=auto in-process.
    if (settings.languageShortCode == "auto" && settings.languageToolHttpServerUri.isEmpty()) {
      val cleanText: String =
        this.simpleLanguageIdentifier.cleanAndShortenText(
          annotatedTextFragment.annotatedText.plainText,
        )
      val detectedLanguage: DetectedLanguage? =
        this.simpleLanguageIdentifier.detectLanguage(cleanText, emptyList(), emptyList())
      val detectedCode: String =
        detectedLanguage?.detectedLanguage?.shortCodeWithCountryAndVariant ?: "en-US"
      // SimpleLanguageIdentifier only returns bare codes (e.g. "en"); promote to the
      // first matching ltex.preferredVariants entry so LT's variant-specific spell-check
      // dictionary is activated rather than silently skipped.
      val languageShortCode: String =
        Settings.promoteToPreferredVariant(detectedCode, settings.preferredVariants)
      annotatedTextFragment.codeFragment.languageShortCode = languageShortCode
      settings = settings.copy(_languageShortCode = languageShortCode)
    }

    this.settingsManager.settings = settings

    val languageToolInterface: LanguageToolInterface =
      this.settingsManager.languageToolInterface ?: run {
        Logging.LOGGER.warning(
          I18n.format("skippingTextCheckAsLanguageToolHasNotBeenInitialized"),
        )
        return emptyList()
      }

    val codeLanguageId: String = codeFragment.codeLanguageId

    if (shouldSkipCheck(codeLanguageId, settings, rangeStartPos)) {
      Logging.LOGGER.fine(I18n.format("skippingTextCheckAsLtexHasBeenDisabled", codeLanguageId))
      return emptyList()
    } else if (settings.dictionary.contains("BsPlInEs")) {
      languageToolInterface.enableEasterEgg()
    }

    logTextToBeChecked(annotatedTextFragment.annotatedText, settings)

    val beforeCheckingInstant: Instant = Instant.now()
    val matches: ArrayList<LanguageToolRuleMatch> =
      try {
        ArrayList(languageToolInterface.check(annotatedTextFragment))
      } catch (e: RuntimeException) {
        Tools.rethrowCancellationException(e)
        Logging.LOGGER.severe(I18n.format("languageToolFailed", e))
        return emptyList()
      }

    if (Logging.LOGGER.isLoggable(Level.FINER)) {
      Logging.LOGGER.finer(
        I18n.format(
          "checkingDone",
          Duration.between(beforeCheckingInstant, Instant.now()).toMillis(),
        ),
      )
    }

    Logging.LOGGER.fine(
      if (matches.size == 1) {
        I18n.format("obtainedRuleMatch")
      } else {
        I18n.format("obtainedRuleMatches", matches.size)
      },
    )
    removeIgnoredMatches(matches)

    val result = ArrayList<LanguageToolRuleMatch>()

    for (match: LanguageToolRuleMatch in matches) {
      result.add(
        match.copy(
          fromPos =
            match.fromPos + annotatedTextFragment.codeFragment.fromPos + (rangeStartPos ?: 0),
          toPos =
            match.toPos + annotatedTextFragment.codeFragment.fromPos + (rangeStartPos ?: 0),
        ),
      )
    }

    return result
  }

  private fun logTextToBeChecked(
    annotatedText: AnnotatedText,
    settings: Settings,
  ) {
    if (Logging.LOGGER.isLoggable(Level.FINER)) {
      Logging.LOGGER.finer(
        I18n.format(
          "checkingText",
          settings.languageShortCode,
          StringEscapeUtils.escapeJava(annotatedText.plainText),
          "",
        ),
      )

      if (Logging.LOGGER.isLoggable(Level.FINEST)) {
        val builder = StringBuilder()

        for (textPart: TextPart in annotatedText.parts) {
          builder.append(if (builder.isEmpty()) "annotatedTextParts = [" else ", ")
          builder.append(textPart.type.toString())
          builder.append("(\"")
          builder.append(StringEscapeUtils.escapeJava(textPart.part))
          builder.append("\")")
        }

        builder.append("]")
        Logging.LOGGER.finest(builder.toString())
      }
    } else if (Logging.LOGGER.isLoggable(Level.FINE)) {
      var logText: String = annotatedText.plainText
      var postfix = ""

      if (logText.length > MAX_LOG_TEXT_LENGTH) {
        logText = logText.substring(0, MAX_LOG_TEXT_LENGTH)
        postfix = I18n.format("truncatedPostfix", MAX_LOG_TEXT_LENGTH)
      }

      Logging.LOGGER.fine(
        I18n.format(
          "checkingText",
          settings.languageShortCode,
          StringEscapeUtils.escapeJava(logText),
          postfix,
        ),
      )
    }
  }

  private fun searchMatchInHiddenFalsePositives(
    ruleId: String,
    sentence: String,
    hiddenFalsePositives: Set<HiddenFalsePositive>,
  ): Boolean {
    for (pair: HiddenFalsePositive in hiddenFalsePositives) {
      if (pair.ruleId == ruleId) {
        if (pair.sentenceRegex.find(sentence) != null) return true
      }
    }

    return false
  }

  private fun removeIgnoredMatches(matches: MutableList<LanguageToolRuleMatch>) {
    val settings: Settings = this.settingsManager.settings
    val hiddenFalsePositives: MutableSet<HiddenFalsePositive> =
      settings.hiddenFalsePositives
        .toMutableSet()
    hiddenFalsePositives.add(
      HiddenFalsePositive("MORFOLOGIK_RULE_NL_NL", "(?:Dummy|Dummy's)\\+[0-9]+-\\w+"),
    )

    if (matches.isEmpty()) return

    val ignoreMatches = ArrayList<LanguageToolRuleMatch>()

    for (match: LanguageToolRuleMatch in matches) {
      val ruleId: String? = match.ruleId
      val sentence: String? = match.sentence?.trim()
      if ((ruleId == null) || (sentence == null)) continue

      if (searchMatchInHiddenFalsePositives(ruleId, sentence, hiddenFalsePositives)) {
        Logging.LOGGER.fine(I18n.format("hidingFalsePositive", ruleId, sentence))
        ignoreMatches.add(match)
      }
    }

    if (ignoreMatches.isNotEmpty()) {
      Logging.LOGGER.fine(
        if (ignoreMatches.size == 1) {
          I18n.format("hidFalsePositive")
        } else {
          I18n.format("hidFalsePositives", ignoreMatches.size)
        },
      )
      for (match: LanguageToolRuleMatch in ignoreMatches) matches.remove(match)
    }
  }

  fun check(
    document: LtexTextDocumentItem,
    range: Range? = null,
  ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
    this.lastCheckedDocument = document
    val originalSettings: Settings = this.settingsManager.settings
    val rangeStartPos: Int? = if (range != null) document.convertPosition(range.start) else null

    try {
      val codeFragments: List<CodeFragment> = fragmentizeDocument(document, range)
      val annotatedTextFragments: List<AnnotatedTextFragment> =
        buildAnnotatedTextFragments(codeFragments, document, rangeStartPos)
      val matches: List<LanguageToolRuleMatch> =
        checkAnnotatedTextFragments(annotatedTextFragments, rangeStartPos)
      return Pair(matches, annotatedTextFragments)
    } finally {
      this.settingsManager.settings = originalSettings
    }
  }

  companion object {
    private const val MAX_LOG_TEXT_LENGTH = 100

    private fun shouldSkipCheck(
      codeLanguageId: String,
      settings: Settings,
      rangeStartPos: Int?,
    ): Boolean =
      (
        (rangeStartPos == null) &&
          !settings.enabled.contains(codeLanguageId) &&
          (codeLanguageId != "nop") &&
          (codeLanguageId != "plaintext")
      )
  }
}
