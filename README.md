<!--
   - Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
   -
   - This Source Code Form is subject to the terms of the Mozilla Public
   - License, v. 2.0. If a copy of the MPL was not distributed with this
   - file, You can obtain one at https://mozilla.org/MPL/2.0/.
   -->

# LT<sub>E</sub>X LS+ — LT<sub>E</sub>X Language Server

LT<sub>E</sub>X LS+ (LT<sub>E</sub>X Language Server) implements a language server according to the [Language Server Protocol (LSP)](https://microsoft.github.io/language-server-protocol/) and provides grammar and spelling errors in markup documents (L<sup>A</sup>T<sub>E</sub>X, Markdown, etc.). The documents are checked with [LanguageTool](https://languagetool.org/).

Typically, you start the language server (either locally or remotely), you send the language server your L<sup>A</sup>T<sub>E</sub>X or Markdown document, and it will respond with a list of the grammar and spelling errors in it. To use LT<sub>E</sub>X LS+ in this way, you have to use a language client (usually an editor or an extension of the editor) that communicates with LT<sub>E</sub>X LS+ according to the LSP.

However, it is also possible to supply LT<sub>E</sub>X LS+ paths to files and directories to be checked as command-line arguments. In this mode, LT<sub>E</sub>X LS+ will print the results to standard output, and no language client is necessary.

The reference language client of LT<sub>E</sub>X LS+ is the [LT<sub>E</sub>X extension for Visual Studio Code (vscode-ltex)](https://ltex-plus.github.io/ltex-plus/), whose development LT<sub>E</sub>X LS+ follows closely and vice versa.

Find more information (how to install, how to use, etc.) at the [website of LT<sub>E</sub>X](https://ltex-plus.github.io/ltex-plus/).

## Features

- **Supported markup languages:** BibT<sub>E</sub>X, ConT<sub>E</sub>Xt, Git commit messages, L<sup>A</sup>T<sub>E</sub>X, Markdown, Org, Quarto, reStructuredText, R Markdown, R Sweave, XHTML
- Comment checking in **many popular programming languages** (optional, opt-in)
- Comes with **everything included,** no need to install Java or LanguageTool
- **Offline checking:** Does not upload anything to the internet
- Supports **over 20 languages:** English, French, German, Dutch, Chinese, Russian, etc.
- **Replacement suggestions** via quick fixes
- **Completion support** for English and German
- **User dictionaries**
- **Multilingual support** with babel commands or magic comments
- Possibility to use **external LanguageTool servers**
- **[Extensive documentation](https://ltex-plus.github.io/ltex-plus/)**
