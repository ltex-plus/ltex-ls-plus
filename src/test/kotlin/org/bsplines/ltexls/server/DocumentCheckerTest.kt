/* Copyright (C) 2019-2025
 * Julian Valentin, Daniel Spitzer, LTeX+ Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.bsplines.ltexls.languagetool.LanguageToolRuleMatch
import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.settings.HiddenFalsePositive
import org.bsplines.ltexls.settings.Settings
import org.bsplines.ltexls.settings.SettingsManager
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentCheckerTest {
  @Test
  @Suppress("LongMethod")
  fun testLatex() {
    var document: LtexTextDocumentItem =
      createDocument(
        "latex",
        "This is an \\textbf{test.}\n% LTeX: language=de-DE\nDies ist eine \\textbf{Test.}\n",
      )
    var checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      checkDocument(document)
    assertMatches(checkingResult.first, 8, 10, 58, 75)

    document =
      createDocument(
        "latex",
        """
        This is a qwertyzuiopa\footnote{This is another qwertyzuiopb.}.
        % ltex: language=de-DE
        Dies ist ein Qwertyzuiopc\todo[name]{Dies ist ein weiteres Qwertyzuiopd.}.

        """.trimIndent(),
      )
    checkingResult = checkDocument(document)

    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    val annotatedTextFragments: List<AnnotatedTextFragment> = checkingResult.second
    assertEquals(4, matches.size)
    assertEquals(5, annotatedTextFragments.size)

    assertEquals("MORFOLOGIK_RULE_EN_US", matches[0].ruleId)
    assertEquals("This is another qwertyzuiopb.", matches[0].sentence)
    assertEquals(48, matches[0].fromPos)
    assertEquals(60, matches[0].toPos)

    assertEquals("MORFOLOGIK_RULE_EN_US", matches[1].ruleId)
    assertEquals("This is a qwertyzuiopa. ", matches[1].sentence)
    assertEquals(10, matches[1].fromPos)
    assertEquals(22, matches[1].toPos)

    assertEquals("GERMAN_SPELLER_RULE", matches[2].ruleId)
    assertEquals("Dies ist ein weiteres Qwertyzuiopd.", matches[2].sentence)
    assertEquals(146, matches[2].fromPos)
    assertEquals(158, matches[2].toPos)

    assertEquals("GERMAN_SPELLER_RULE", matches[3].ruleId)
    assertEquals(" Dies ist ein Qwertyzuiopc. ", matches[3].sentence)
    assertEquals(100, matches[3].fromPos)
    assertEquals(112, matches[3].toPos)

    assertEquals("This is another qwertyzuiopb.", annotatedTextFragments[0].codeFragment.code)
    assertEquals("This is another qwertyzuiopb.", annotatedTextFragments[0].annotatedText.plainText)

    assertEquals(
      "This is a qwertyzuiopa\\footnote{This is another qwertyzuiopb.}.\n",
      annotatedTextFragments[1].codeFragment.code,
    )
    assertEquals("This is a qwertyzuiopa. ", annotatedTextFragments[1].annotatedText.plainText)

    assertEquals("% ltex: language=de-DE", annotatedTextFragments[2].codeFragment.code)
    assertEquals("", annotatedTextFragments[2].annotatedText.plainText)

    assertEquals("Dies ist ein weiteres Qwertyzuiopd.", annotatedTextFragments[3].codeFragment.code)
    assertEquals(
      "Dies ist ein weiteres Qwertyzuiopd.",
      annotatedTextFragments[3].annotatedText.plainText,
    )

    assertEquals(
      """

      Dies ist ein Qwertyzuiopc\todo[name]{Dies ist ein weiteres Qwertyzuiopd.}.

      """.trimIndent(),
      annotatedTextFragments[4].codeFragment.code,
    )
    assertEquals(
      " Dies ist ein Qwertyzuiopc. ",
      annotatedTextFragments[4].annotatedText.plainText,
    )
    assertOriginalAndPlainTextWords("latex", "The \\v{S}ekki\n", "\\v{S}ekki", "\u0160ekki")
    assertOriginalAndPlainTextWords("latex", "The Sekk\\v{S}\n", "Sekk\\v{S}", "Sekk\u0160")
    assertOriginalAndPlainTextWords("latex", "This is \\textbf{an} test.\n", "an", "an")
  }

  @Test
  @Suppress("LongMethod")
  fun testMagicComments() {
    val document =
      createDocument(
        "latex",
        """
        \documentclass{article}

        \newcommand{\ltexComment}[1]{}

        \begin{document}

        % LTeX: latex.commands.\ltexComment{}=ignore
        \ltexComment{This text is now ignored. This is an test.}
        \ltexComment{
            Use KEY=VALUE to set scalar properties.
            Use KEY=# to restore global settings (undo magic commands for this KEY).
            Use KEY-=VALUE to remove VALUE from a set.
            Use KEY+=VALUE to add VALUE to a set.
            Use KEY#=VALUE to have VALUE in a set if and only if it is in the global set (undo magic commands for this KEY and VALUE).

            Use 'true' or 'false' without quotes for booleans.
        }

        \ltexComment{}

        \ltexComment{Enable and disable all checks.}
        This is an \textbf{test} with a mistake.
        % LTeX: enabled=false
        This is an \textbf{test} with no mistake.
        % LTeX: enabled=true
        This is an \textbf{test} with a mistake.
        % LTeX: enabled=#
        This is an \textbf{test} with a mistake.

        \ltexComment{Change the language.}
        % LTeX: language=de-DE
        This test contains the mistake. Dies ist ein Test ohne Fehler.
        % LTeX: language=#
        This is a test with no mistake. Dies ist ein Test with mistakes.


        \ltexComment{Enable and disable rules.}
        This is an \textbf{test} with a mistake.
        % LTeX: rules-=EN_A_VS_AN
        This is an \textbf{test} with no mistake.
        % LTeX: rules+=EN_A_VS_AN
        This is an \textbf{test} with a mistake.
        % LTeX: rules#=EN_A_VS_AN
        This is an \textbf{test} with a mistake.


        \ltexComment{Add and remove words from the dictionary.}
        \ltexComment{Known word: BROKEN}
        Test is a word.
        % LTeX: dictionary-=Test
        Test is not a word.
        % LTeX: dictionary+=Test
        Test is a word.
        % LTeX: dictionary#=Test
        Test is a word.

        \ltexComment{Unknown word}
        Dcba is not a word.
        % LTeX: dictionary-=Dcba
        Dcba is not a word.
        % LTeX: dictionary+=Dcba
        Dcba is a word.
        % LTeX: dictionary#=Dcba
        Dcba is not a word.

        \ltexComment{Set actions for latex commands.}
        This is an \textbf{test} with a mistake.
        % LTeX: latex.commands.\textbf{}=default 'default': parameters are checked.
        This is an \textbf{test} with a mistake.
        % LTeX: latex.commands.\textbf{}=ignore 'ignore': pretend it is not there.
        This is an \textbf{test} ignored test without a mistake.
        % LTeX: latex.commands.\textbf{}=dummy
        \ltexComment{Apparently dummy can be plural.}
        This \textbf{test} has no mistake.
        These \textbf{test} have no mistake.
        % LTeX: latex.commands.\textbf{}=pluralDummy
        This \textbf{test} has a mistake.
        These \textbf{test} have no mistake.
        % LTeX: latex.commands.\textbf{}=vowelDummy
        This is an \textbf{test} with no mistake.
        % LTeX: latex.commands.\textbf{}=#
        This is an \textbf{test} with a mistake.

        \ltexComment{Set actions for latex environments.}
        \begin{center}
            This is an test with a mistake.
        \end{center}
        % LTeX: latex.environments.center=default 'default': check contents
        \begin{center}
            This is an test with a mistake.
        \end{center}
        % LTeX: latex.environments.center=ignore 'default': check nothing
        \begin{center}
            This is an test with no mistake.
        \end{center}
        % LTeX: latex.environments.center=#
        \begin{center}
            This is an test with a mistake.
        \end{center}

        \ltexComment{Enable picky rules}
        The rules have been enabled by the comment. The sentence is fine.
        % LTeX: enablePickyRules=false
        The rules have been enabled by the comment. The sentence is fine.
        % LTeX: enablePickyRules=true
        The rules have been enabled by the comment. The sentence should be in active voice.
        % LTeX: enablePickyRules=#
        The rules have been enabled by the comment. The sentence is fine.

        \ltexComment{Set logLevel for debugging a specific section}

        % LTeX: logLevel=finer
        Suppose this sentence produces weird logs.
        % LTeX: logLevel=#

        \ltexComment{You can change multiple values at once.}
        % Ltex: language=de-DE rules-=DE_AGREEMENT
        Dies ist eine Test.
        % Ltex: language=#

        \ltexComment{Comment suffixes that do not conform to KEY=VALUE are ignored.}
        % Ltex: rules-=EN_A_VS_AN       I know what I am doing!
        This is an test with no mistake.
        % Ltex: rules#=EN_A_VS_AN       Never mind.
        This is an test with a mistake.

        \end{document}
        """.trimIndent(),
      )
    val checkingResult = checkDocument(document)

    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertEquals(matches.size, 22)

    assertMatchIs(matches[0], "EN_A_VS_AN", "This is an test with a mistake.", 656, 658)
    assertMatchIs(matches[1], "EN_A_VS_AN", "This is an test with a mistake.", 782, 784)
    assertMatchIs(matches[2], "EN_A_VS_AN", "This is an test with a mistake.", 841, 843)
    assertMatchIs(matches[3], "GERMAN_SPELLER_RULE", "This test contains the mistake.", 952, 955)
    assertMatchIs(matches[4], "GERMAN_SPELLER_RULE", "This test contains the mistake.", 956, 963)
    assertMatchIs(
      matches[5],
      "MORFOLOGIK_RULE_EN_US",
      "Dies ist ein Test with mistakes.",
      1052,
      1055,
    )
    assertMatchIs(
      matches[6],
      "MORFOLOGIK_RULE_EN_US",
      "Dies ist ein Test with mistakes.",
      1056,
      1059,
    )
    assertMatchIs(matches[7], "EN_A_VS_AN", "This is an test with a mistake.", 1130, 1132)
    assertMatchIs(matches[8], "EN_A_VS_AN", "This is an test with a mistake.", 1265, 1267)
    assertMatchIs(matches[9], "EN_A_VS_AN", "This is an test with a mistake.", 1332, 1334)
    assertMatchIs(matches[10], "MORFOLOGIK_RULE_EN_US", "Dcba is not a word.", 1627, 1631)
    assertMatchIs(matches[11], "MORFOLOGIK_RULE_EN_US", "Dcba is not a word.", 1672, 1676)
    assertMatchIs(matches[12], "MORFOLOGIK_RULE_EN_US", "Dcba is not a word.", 1758, 1762)
    assertMatchIs(matches[13], "EN_A_VS_AN", "This is an test with a mistake.", 1833, 1835)
    assertMatchIs(matches[14], "EN_A_VS_AN", "This is an test with a mistake.", 1950, 1952)
    assertMatchIs(matches[15], "AGREEMENT_SENT_START", "This Dummies has a mistake.", 2322, 2335)
    assertMatchIs(matches[16], "EN_A_VS_AN", "This is an test with a mistake.", 2517, 2519)
    assertMatchIs(matches[17], "EN_A_VS_AN", "This is an test with a mistake.", 2628, 2630)
    assertMatchIs(matches[18], "EN_A_VS_AN", "This is an test with a mistake.", 2760, 2762)
    assertMatchIs(matches[19], "EN_A_VS_AN", "This is an test with a mistake.", 2991, 2993)
    assertMatchIs(
      matches[20],
      "PASSIVE_VOICE_SIMPLE",
      "The rules have been enabled by the comment.",
      3255,
      3297,
    )
    assertMatchIs(matches[21], "EN_A_VS_AN", "This is an test with a mistake.", 3935, 3937)
  }

  /**
   * This is a regression test for https://github.com/ltex-plus/ltex-ls-plus/pull/102#issuecomment-3415086204.
   */
  @Test
  fun testMultipleDictionaryEntriesInMagicComment() {
    val document =
      createDocument(
        "latex",
        """
        \documentclass{article}
        \newcommand{\ltexComment}[1]{}
        \begin{document}
        Olching

        Ruedsy
        % LTeX: dictionary+=Olching dictionary+=Ruedsy
        Olching

        Ruedsy
        \end{document}
        """.trimIndent(),
      )
    val checkingResult = checkDocument(document)

    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertEquals(2, matches.size)

    assertMatchIs(matches[0], "MORFOLOGIK_RULE_EN_US", "Olching", 72, 79)
    assertMatchIs(matches[1], "MORFOLOGIK_RULE_EN_US", "Ruedsy", 81, 87)
  }

  @Test
  fun testMarkdown() {
    val document: LtexTextDocumentItem =
      createDocument(
        "markdown",
        """
        This is an **test.**

        <!-- LTeX: language=de-DE -->

        Dies ist eine **Test**.

        """.trimIndent(),
      )
    assertMatches(checkDocument(document).first, 8, 10, 62, 73)
  }

  @Test
  @Suppress("LongMethod")
  fun testMagicCommentsMarkdown() {
    val document =
      createDocument(
        "markdown",
        """
        # Magic comments

        <!-- LTeX: loglevel=finest rules-=CONSECUTIVE_SPACES -->

        This is an **test** with a mistake.
        <!-- LTeX: markdown.nodes.StrongEmphasis=ignore -->
        This is an **test** ignored test without a mistake.
        <!-- LTeX: markdown.nodes.StrongEmphasis=default -->
        This is an **test** with a mistake.
        <!-- LTeX: markdown.nodes.StrongEmphasis=dummy -->
        This is an **test** with a mistake.
        <!-- LTeX: markdown.nodes.StrongEmphasis=# -->
        This is an **test** with a mistake.
        """.trimIndent(),
      )
    val checkingResult = checkDocument(document)

    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertEquals(matches.size, 4)

    assertMatchIs(matches[0], "EN_A_VS_AN", "This is an test with a mistake.", 84, 86)
    assertMatchIs(matches[1], "EN_A_VS_AN", "This is an test with a mistake.", 277, 279)
    assertMatchIs(matches[2], "EN_A_VS_AN", "This is an Dummy0 with a mistake.", 364, 366)
    assertMatchIs(matches[3], "EN_A_VS_AN", "This is an test with a mistake.", 447, 449)
  }

  @Test
  @Suppress("LongMethod")
  fun testMagicCommentsBibtex() {
    val document =
      createDocument(
        "bibtex",
        """
        % Set actions for bibtex fields.
        @misc{sample,
          author    = {This is an test.},
        }
        % LTeX: bibtex.fields.author=false
        @misc{sample,
          author    = {This is an test.},
        }
        % LTeX: bibtex.fields.author=true
        @misc{sample,
          author    = {This is an test.},
        }
        % LTeX: bibtex.fields.author=#
        @misc{sample,
          author    = {This is an test.},
        }
        """.trimIndent(),
      )
    val checkingResult = checkDocument(document)

    val matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertEquals(matches.size, 1)

    assertMatchIs(matches[0], "EN_A_VS_AN", "This is an test.", 239, 241)
  }

  @Test
  fun testLanguageDetection() {
    val document: LtexTextDocumentItem =
      createDocument(
        "markdown",
        """
        This is an **test.**

        <!-- LTeX: language=auto -->

        Dies ist eine **Test**.

        """.trimIndent(),
      )
    assertMatches(checkDocument(document).first, 8, 10, 61, 72)
  }

  @Test
  fun testRange() {
    var document: LtexTextDocumentItem =
      createDocument(
        "markdown",
        "# Test\n\nThis is an **test.**\n\nThis is an **test.**\n",
      )
    val settingsManager = SettingsManager(Settings(_logLevel = Level.FINEST))
    val documentChecker = DocumentChecker(settingsManager)
    var checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      documentChecker.check(document, Range(Position(4, 0), Position(4, 20)))
    var matches: List<LanguageToolRuleMatch> = checkingResult.first
    assertEquals(1, matches.size)
    assertEquals("EN_A_VS_AN", matches[0].ruleId)
    assertEquals("This is an test.", matches[0].sentence?.trim())
    assertEquals(38, matches[0].fromPos)
    assertEquals(40, matches[0].toPos)

    document =
      createDocument(
        "cpp",
        """
        #include <iostream>

        int main() {
          std::cout << "This is an test." << std::endl;
          return 0;
        }

        """.trimIndent(),
      )
    checkingResult = documentChecker.check(document, Range(Position(3, 16), Position(3, 32)))
    matches = checkingResult.first
    assertEquals(1, matches.size)
    assertEquals("EN_A_VS_AN", matches[0].ruleId)
    assertEquals("This is an test.", matches[0].sentence?.trim())
    assertEquals(58, matches[0].fromPos)
    assertEquals(60, matches[0].toPos)
  }

  @Test
  fun testJavaCrlfCommentProducesDiagnostic() {
    val code =
      "public class Foo {\r\n" +
        "  /** This is an test.\r\n" +
        "    * Second line of comment.\r\n" +
        "    */\r\n" +
        "  void bar() {}\r\n" +
        "}\r\n"
    val document = createDocument("java", code)
    val matches = checkDocument(document, Settings(_enabled = setOf("java"))).first
    assertEquals(1, matches.size)
    assertEquals("EN_A_VS_AN", matches[0].ruleId)
    assertEquals("an", code.substring(matches[0].fromPos, matches[0].toPos))
  }

  @Test
  fun testLispCrlfCommentProducesDiagnostic() {
    val code =
      ";; This is an test.\r\n" +
        ";; Second line of comment.\r\n"
    val document = createDocument("lisp", code)
    val matches = checkDocument(document, Settings(_enabled = setOf("lisp"))).first
    assertEquals(1, matches.size)
    assertEquals("EN_A_VS_AN", matches[0].ruleId)
    assertEquals("an", code.substring(matches[0].fromPos, matches[0].toPos))
  }

  @Test
  fun testCodeActionGenerator() {
    val document: LtexTextDocumentItem =
      createDocument(
        "markdown",
        "This is an unknownword.\n",
      )
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      checkDocument(document)
    val params =
      CodeActionParams(
        TextDocumentIdentifier(document.uri),
        Range(Position(0, 0), Position(100, 0)),
        CodeActionContext(emptyList()),
      )
    val settingsManager = SettingsManager()
    val codeActionProvider = CodeActionProvider(settingsManager)
    val result: List<Either<Command, CodeAction>> =
      codeActionProvider.generate(params, document, checkingResult)
    assertEquals(4, result.size)
  }

  @Test
  fun testEnabled() {
    val document: LtexTextDocumentItem =
      createDocument(
        "latex",
        """
        This is a firstunknownword.
        % ltex: enabled=false
        This is a secondunknownword.
        % ltex: enabled=true
        This is a thirdunknownword.

        """.trimIndent(),
      )
    val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      checkDocument(document)
    assertEquals(2, checkingResult.first.size)
  }

  @Test
  fun testDictionary() {
    val jsonDictionaryArray = JsonArray()
    jsonDictionaryArray.add("unbekannteswort")

    val jsonDictionaryObject = JsonObject()
    jsonDictionaryObject.add("de-DE", jsonDictionaryArray)

    val jsonSettings = JsonObject()
    val jsonWorkspaceSpecificSettings = JsonObject()
    jsonWorkspaceSpecificSettings.add("dictionary", jsonDictionaryObject)

    var document =
      createDocument(
        "latex",
        "This is an unknownword.\n% ltex: language=de-DE\nDies ist ein unbekannteswort.\n",
      )
    var settings: Settings = Settings.fromJson(jsonSettings, jsonWorkspaceSpecificSettings)
    var checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      checkDocument(document, settings)
    assertEquals(1, checkingResult.first.size)

    document = createDocument("latex", "S pekn\u00e9 inteligentn\u00fdmi dubmi.\n")
    settings = Settings(_languageShortCode = "sk-SK")
    checkingResult = checkDocument(document, settings)
    assertEquals(1, checkingResult.first.size)
    settings = settings.copy(_allDictionaries = mapOf(Pair("sk-SK", setOf("pekn\u00e9"))))
    checkingResult = checkDocument(document, settings)
    assertEquals(0, checkingResult.first.size)

    document = createDocument("latex", "On trouve des mmots inconnus.\n")
    settings = Settings(_languageShortCode = "fr")
    checkingResult = checkDocument(document, settings)
    assertEquals(1, checkingResult.first.size)
    settings = settings.copy(_allDictionaries = mapOf(Pair("fr", setOf("mmots"))))
    checkingResult = checkDocument(document, settings)
    assertEquals(0, checkingResult.first.size)

    // Multi-word dictionary entry: the phrase is masked out before LanguageTool
    // sees it, so a contiguous phrase is accepted as a unit, while a lone first
    // word stays flagged. (A phrase split by markup in the source — e.g.
    // `LT<sub>E</sub>X LS` — is not covered by this plain-text masking; matching
    // verbatim source-form entries is handled separately.)
    document = createDocument("markdown", "This is GreenTeam Penciltest here.\n")
    settings = Settings()
    checkingResult = checkDocument(document, settings)
    assertTrue(checkingResult.first.isNotEmpty())
    settings = settings.copy(_allDictionaries = mapOf(Pair("en-US", setOf("GreenTeam Penciltest"))))
    checkingResult = checkDocument(document, settings)
    assertEquals(0, checkingResult.first.size)

    document = createDocument("markdown", "This is GreenTeam here.\n")
    checkingResult = checkDocument(document, settings)
    assertEquals(1, checkingResult.first.size)
  }

  @Test
  fun testHiddenFalsePositives() {
    var document: LtexTextDocumentItem = createDocument("markdown", "This is an unknownword.\n")
    var settings =
      Settings(
        _allHiddenFalsePositives =
          mapOf(
            Pair(
              "en-US",
              setOf(HiddenFalsePositive("MORFOLOGIK_RULE_EN_US", "This is an unknownword\\.")),
            ),
          ),
      )
    var checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
      checkDocument(document, settings)
    assertTrue(checkingResult.first.isEmpty())

    document = createDocument("latex", "\\(\\Delta Q\\)-waarde. \\(\\Delta Q\\)-waarde.")
    settings = Settings(_languageShortCode = "nl")
    checkingResult = checkDocument(document, settings)
    assertEquals(0, checkingResult.first.size)
    document = createDocument("latex", "Dummy++1-waarde")
    settings = Settings(_languageShortCode = "nl")
    checkingResult = checkDocument(document, settings)
    assertEquals(1, checkingResult.first.size)
  }

  companion object {
    private fun checkDocument(
      document: LtexTextDocumentItem,
      settings: Settings = Settings(),
    ): Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> {
      val settingsManager = SettingsManager(settings.copy(_logLevel = Level.FINEST))
      val documentChecker = DocumentChecker(settingsManager)
      return documentChecker.check(document)
    }

    fun createDocument(
      codeLanguageId: String,
      code: String,
    ): LtexTextDocumentItem {
      val languageServer = LtexLanguageServer()
      return LtexTextDocumentItem(languageServer, "untitled:test.txt", codeLanguageId, 1, code)
    }

    private fun assertOriginalAndPlainTextWords(
      codeLanguageId: String,
      code: String,
      expectedOriginalTextWord: String,
      expectedPlainTextWord: String,
    ) {
      val document: LtexTextDocumentItem = createDocument(codeLanguageId, code)
      val checkingResult: Pair<List<LanguageToolRuleMatch>, List<AnnotatedTextFragment>> =
        checkDocument(document)
      val matches: List<LanguageToolRuleMatch> = checkingResult.first
      val annotatedTextFragments: List<AnnotatedTextFragment> = checkingResult.second
      assertEquals(1, matches.size)
      assertEquals(1, annotatedTextFragments.size)
      assertEquals(
        expectedOriginalTextWord,
        code.substring(matches[0].fromPos, matches[0].toPos),
      )
      assertEquals(
        expectedPlainTextWord,
        annotatedTextFragments[0].getSubstringOfPlainText(matches[0].fromPos, matches[0].toPos),
      )
    }

    @Suppress("SwallowedException")
    fun assertMatches(
      matches: List<LanguageToolRuleMatch>,
      fromPos1: Int,
      toPos1: Int,
      fromPos2: Int,
      toPos2: Int,
    ) {
      assertEquals(2, matches.size)
      assertEquals("EN_A_VS_AN", matches[0].ruleId)
      assertEquals("This is an test.", matches[0].sentence?.trim())
      assertEquals(fromPos1, matches[0].fromPos)
      assertEquals(toPos1, matches[0].toPos)

      try {
        assertEquals(
          "Use <suggestion>a</suggestion> instead of 'an' if the following " +
            "word doesn't start with a vowel sound, e.g. " +
            "'a sentence', 'a university'.",
          matches[0].message,
        )
      } catch (e: AssertionError) {
        assertEquals(
          "Use \u201ca\u201d instead of \u2018an\u2019 if the following " +
            "word doesn\u2019t start with a vowel sound, e.g.\u00a0" +
            "\u2018a sentence\u2019, \u2018a university\u2019.",
          matches[0].message,
        )
      }

      assertEquals(1, matches[0].suggestedReplacements.size)
      assertEquals("a", matches[0].suggestedReplacements[0])
      assertEquals("DE_AGREEMENT", matches[1].ruleId)
      assertEquals("Dies ist eine Test.", matches[1].sentence?.trim())
      assertEquals(fromPos2, matches[1].fromPos)
      assertEquals(toPos2, matches[1].toPos)

      try {
        assertEquals(
          "M\u00f6glicherweise passen das Nomen und die W\u00f6rter, " +
            "die das Nomen beschreiben, grammatisch nicht zusammen.",
          matches[1].message,
        )
      } catch (e: AssertionError) {
        assertEquals(
          "M\u00f6glicherweise passen das Nomen und die W\u00f6rter, " +
            "die das Nomen beschreiben, grammatisch nicht zusammen.",
          matches[1].message,
        )
      }

      assertEquals(3, matches[1].suggestedReplacements.size)
      assertEquals("ein Test", matches[1].suggestedReplacements[0])
      assertEquals("einen Test", matches[1].suggestedReplacements[1])
      assertEquals("einem Test", matches[1].suggestedReplacements[2])
    }

    fun assertMatchIs(
      match: LanguageToolRuleMatch,
      rule: String,
      sentence: String,
      fromPos: Int,
      toPos: Int,
    ) {
      assertEquals(rule, match.ruleId)
      assertEquals(sentence.trim(), match.sentence?.trim())
      assertEquals(fromPos, match.fromPos)
      assertEquals(toPos, match.toPos)
    }
  }
}
