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
import org.bsplines.ltexls.parsing.AnnotatedTextSlicer
import org.bsplines.ltexls.parsing.CachedFragment
import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.parsing.FragmentCache
import org.bsplines.ltexls.parsing.FragmentCacheKey
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

@Suppress("TooManyFunctions")
class DocumentChecker(
  val settingsManager: SettingsManager,
  // Reuses per-fragment results across edits via the fragment cache. The
  // default keeps the many test call sites (which construct DocumentChecker with
  // only a SettingsManager) working with a private cache each. The server passes
  // its shared, swept instance.
  val fragmentCache: FragmentCache = FragmentCache(),
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

  private fun buildAnnotatedTextFragment(
    codeFragment: CodeFragment,
    document: LtexTextDocumentItem,
    rangeStartPos: Int?,
  ): AnnotatedTextFragment {
    if (shouldSkipCheck(codeFragment.codeLanguageId, codeFragment.settings, rangeStartPos)) {
      // Fragment will be skipped at the check phase (e.g., region after
      // "# ltex: enabled=false"). Skip parsing too — running the
      // CodeAnnotatedTextBuilder over a large disabled region is pure
      // overhead, and on big files (>100KB) it dominates request latency.
      return AnnotatedTextFragment(AnnotatedTextBuilder().build(), codeFragment, document)
    }

    val hasRange: Boolean = (rangeStartPos != null)
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
    return AnnotatedTextFragment(builder.build(), codeFragment, document)
  }

  private fun checkAnnotatedTextFragments(
    annotatedTextFragments: List<AnnotatedTextFragment>,
    rangeStartPos: Int? = null,
  ): List<LanguageToolRuleMatch> {
    val matches = ArrayList<LanguageToolRuleMatch>()

    for (annotatedTextFragment: AnnotatedTextFragment in annotatedTextFragments) {
      val relativeMatches: List<LanguageToolRuleMatch> =
        checkAnnotatedTextFragmentRelative(annotatedTextFragment, rangeStartPos)
      matches.addAll(
        shiftMatchesToAbsolute(relativeMatches, annotatedTextFragment.codeFragment, rangeStartPos),
      )
    }

    return matches
  }

  // Re-projects fragment-relative match offsets to absolute document offsets.
  // Applied identically to freshly computed and cached matches, so a fragment
  // whose result was cached but whose position shifted (text inserted above it)
  // still lands at the correct absolute offset.
  private fun shiftMatchesToAbsolute(
    relativeMatches: List<LanguageToolRuleMatch>,
    codeFragment: CodeFragment,
    rangeStartPos: Int?,
  ): List<LanguageToolRuleMatch> {
    val offset: Int = codeFragment.fromPos + (rangeStartPos ?: 0)
    return relativeMatches.map {
      it.copy(fromPos = it.fromPos + offset, toPos = it.toPos + offset)
    }
  }

  // Returns matches with FRAGMENT-RELATIVE offsets (ignored matches already
  // removed). Callers apply shiftMatchesToAbsolute. For ltex.language="auto"
  // this resolves and writes codeFragment.languageShortCode as a side effect.
  @Suppress("TooGenericExceptionCaught")
  private fun checkAnnotatedTextFragmentRelative(
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
    } else if (settings.allDictionaries.values.any { it.contains("BsPlInEs") }) {
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
    removeIgnoredMatches(matches, annotatedTextFragment)

    return matches
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

  private fun removeIgnoredMatches(
    matches: MutableList<LanguageToolRuleMatch>,
    annotatedTextFragment: AnnotatedTextFragment,
  ) {
    val settings: Settings = this.settingsManager.settings
    // Look up hiddenFalsePositives by the *fragment's* detected language rather than
    // settings.languageShortCode. On the auto+HTTP path the latter stays at the literal
    // "auto", so a settings.hiddenFalsePositives lookup would always miss the user's
    // per-language entries. The fragment carries the variant the server back-filled.
    val fragmentLanguage: String = annotatedTextFragment.codeFragment.languageShortCode
    val hiddenFalsePositives: MutableSet<HiddenFalsePositive> =
      (settings.allHiddenFalsePositives[fragmentLanguage] ?: emptySet()).toMutableSet()
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

      // Only the full-document path (no range) uses the fragment cache. The
      // range path (code-action partial checks) is already small and stays on
      // the simple non-cached path.
      if (rangeStartPos == null) return checkCodeFragmentsWithCache(codeFragments, document)

      val annotatedTextFragments: List<AnnotatedTextFragment> =
        codeFragments.map { buildAnnotatedTextFragment(it, document, rangeStartPos) }
      val matches: List<LanguageToolRuleMatch> =
        checkAnnotatedTextFragments(annotatedTextFragments, rangeStartPos)
      return Pair(matches, annotatedTextFragments)
    } finally {
      this.settingsManager.settings = originalSettings
    }
  }

  // Full-document check with per-paragraph result reuse. Each language region is
  // built once (the build understands the markup), then sliced into paragraphs by
  // AnnotatedTextSlicer. The cache is keyed per paragraph on its source substring
  // + settings (not its position), so a paragraph that merely shifted is still a
  // hit. A hit returns the cached AnnotatedText and matches, skipping the
  // LanguageTool call; a miss checks and stores. Offsets are projected to absolute
  // positions on every iteration (hit or miss).
  private fun checkCodeFragmentsWithCache(
    codeFragments: List<CodeFragment>,
    document: LtexTextDocumentItem,
  ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
    val annotatedTextFragments = ArrayList<AnnotatedTextFragment>()
    val matches = ArrayList<LanguageToolRuleMatch>()

    for (codeFragment: CodeFragment in codeFragments) {
      for (paragraph: AnnotatedTextFragment in sliceRegionIntoParagraphs(codeFragment, document)) {
        val paragraphCodeFragment: CodeFragment = paragraph.codeFragment
        val key: FragmentCacheKey = FragmentCache.makeKey(document.uri, paragraphCodeFragment)
        val cached: CachedFragment? = this.fragmentCache.get(key)
        val annotatedTextFragment: AnnotatedTextFragment
        val relativeMatches: List<LanguageToolRuleMatch>

        if (cached != null) {
          // Restore the language the paragraph was checked under (matters for
          // "auto"); the cached matches already carry the correct per-match
          // languageShortCode.
          paragraphCodeFragment.languageShortCode = cached.detectedLanguageShortCode
          annotatedTextFragment =
            AnnotatedTextFragment(cached.annotatedText, paragraphCodeFragment, document)
          relativeMatches = cached.relativeMatches
        } else {
          annotatedTextFragment = paragraph
          relativeMatches = checkAnnotatedTextFragmentRelative(annotatedTextFragment, null)
          this.fragmentCache.put(
            key,
            CachedFragment(
              annotatedTextFragment.annotatedText,
              relativeMatches,
              paragraphCodeFragment.languageShortCode,
            ),
          )
        }

        annotatedTextFragments.add(annotatedTextFragment)
        matches.addAll(shiftMatchesToAbsolute(relativeMatches, paragraphCodeFragment, null))
      }
    }

    return Pair(matches, annotatedTextFragments)
  }

  // Builds a region's AnnotatedText once, then slices it into one
  // AnnotatedTextFragment per prose paragraph (see AnnotatedTextSlicer). Each
  // paragraph gets a synthetic CodeFragment whose code is its source substring
  // and whose fromPos is the region's fromPos plus the paragraph's source offset,
  // so cache keys are position-independent and matches reproject correctly.
  // A skipped region (e.g. after "ltex: enabled=false") is not sliced: its build
  // is empty by design, so it passes through as a single fragment.
  private fun sliceRegionIntoParagraphs(
    region: CodeFragment,
    document: LtexTextDocumentItem,
  ): List<AnnotatedTextFragment> {
    val regionFragment: AnnotatedTextFragment = buildAnnotatedTextFragment(region, document, null)
    if (shouldSkipCheck(region.codeLanguageId, region.settings, null)) {
      return listOf(regionFragment)
    }

    return AnnotatedTextSlicer.slice(regionFragment.annotatedText).map { slice ->
      // Source length of the slice = sum of TEXT and MARKUP part lengths;
      // FAKE_CONTENT exists only in the plain text and contributes zero source
      // characters. Recovers the paragraph's source substring from its region.
      val sourceLength: Int =
        slice.annotatedText.parts.sumOf {
          if (it.type == TextPart.Type.FAKE_CONTENT) 0 else it.part.length
        }
      val paragraphCodeFragment =
        CodeFragment(
          region.codeLanguageId,
          region.code.substring(slice.sourceFromPos, slice.sourceFromPos + sourceLength),
          region.fromPos + slice.sourceFromPos,
          region.settings,
          region.languageShortCode,
        )
      AnnotatedTextFragment(slice.annotatedText, paragraphCodeFragment, document)
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
