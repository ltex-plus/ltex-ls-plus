<!--
   - Copyright (C) 2019-2025
   - Julian Valentin, Daniel Spitzer, LTeX+ Development Community
   -
   - This Source Code Form is subject to the terms of the Mozilla Public
   - License, v. 2.0. If a copy of the MPL was not distributed with this
   - file, You can obtain one at https://mozilla.org/MPL/2.0/.
   -->

# Maintainer Guidelines

## Guidelines About Issues

- **Stale issues** are closed seven days after they became stale. An issue becomes stale if:
  - The issue is missing information.
  - The issue is declared stale by a maintainer.
- **Discussions** may happen on any issue regardless of its state. Discussions don't prolong the lifetime of stale issues, and they must be about the original issue, otherwise a new issue must be opened.
- Issues may only be **reopened** if the reason for closing it doesn't exist anymore.
  - *Examples:* Missing information is provided, bug occurs again due to a regression, etc.
- Issue comments that do not contribute to the discussion (e.g., “I'm affected, too” on confirmed bug reports) may be hidden. Instead, reactions (e.g., thumbs-up) should be used to upvote issues.
- Issues that don't follow the **template** may be closed immediately.
- Issues may be **locked** if they violate the [code of conduct](https://ltex-plus.github.io/ltex-plus/code-of-conduct.html).

## Guidelines About Versioning

- **Semantic versioning** is used for vscode-ltex-plus and ltex-ls-plus. The two versions are independent of each other.
  - For bug fixes, the patch version is increased.
  - For new features, the minor version is increased.
  - For breaking changes, the major version is increased.
    - *Explanation:* Breaking changes are changes that may require action from users (e.g., most changes of existing LT<sub>E</sub>X+ settings).
    - A dependency update that changes the Java API of programs using the jars in the `lib` directory of ltex-ls-plus is also a breaking change, even if the messages sent to editors stay the same (e.g., LSP4J 1.0.0, see [#199](https://github.com/ltex-plus/ltex-ls-plus/issues/199)). The bundled `ltex-cli-plus` is such a program.

## Guidelines About Fundamental Changes

- **Fundamental changes** are announced as an issue, in the documentation, and/or as message boxes in VS Code three months before their implementation.
  - *Explanation:* Fundamental changes are possibly breaking changes that change the foundation of LT<sub>E</sub>X+ (e.g., upgrade from Java 8 to Java 11).
- **Documentation of fundamental changes** and associated deprecated settings may be deleted three months after their implementation.

## Making a Release

- A release involves up to four repositories. Release them in this **order**, because each one downloads files from the release before it:
  1. lsp-cli-plus, if it changed. ltex-ls-plus bundles it as `ltex-cli-plus`.
  2. ltex-ls-plus.
  3. vscode-ltex-plus, which downloads ltex-ls-plus.
  4. ltex-plus, the website.
- **Tagging** works the same way in lsp-cli-plus, ltex-ls-plus and vscode-ltex-plus:
  1. In `changelog.xml`, change `date="upcoming"` of the release to the release date, and check that its `version` matches the version in `pom.xml` (`package.json` in vscode-ltex-plus).
  2. Remove the `-alpha` suffix from the version in `pom.xml` (`package.json` in vscode-ltex-plus).
  3. Commit as “Bump to X.Y.Z”, push `develop`, tag the commit as `X.Y.Z`, and push the tag.
  4. CI builds the release and creates it as a **draft**, with release notes generated from `changelog.xml`. Check the draft, shorten the release notes if needed, and publish it.
  - CI also creates a build provenance attestation for every file it uploads to the release, which shows that the file was built by the workflow from the tagged commit. To check a downloaded file, run `gh attestation verify <FILE> --repo ltex-plus/<REPOSITORY>`.
- **lsp-cli-plus:** publish the release before tagging ltex-ls-plus. `tools/addLtexCli.py` in ltex-ls-plus downloads the CLI from the public release URL, which a draft does not have. Then, in ltex-ls-plus, set `lspCliVersion` in `tools/addLtexCli.py` to the new version and update the “Update to lsp-cli-plus” entry in `changelog.xml`.
- **ltex-ls-plus:** before publishing the draft, download one of its archives and run `bin/ltex-cli-plus` on a file with a spelling mistake.
  - *Explanation:* In the first 19.0.0 draft, the bundled CLI printed `Either [ left = … ]` instead of the message.
- **vscode-ltex-plus:** start only after the ltex-ls-plus release is published.
  1. Run `tools/updateLtexLsVersionAndHashDigests.py --tag X.Y.Z` (it needs the Python package `semver`). It sets the ltex-ls-plus version in `src/DependencyManager.ts` and the hash of every archive, which it downloads from the published release. Check that the diff changes the version.
     - *Explanation:* 15.7.0 was published with the old server because the script had silently changed nothing.
  2. Add “Update to ltex-ls-plus X.Y.Z” to `changelog.xml`.
  3. Tag as described above. Before publishing the draft, check that the `.vsix` downloads the new server.
  4. **Publishing the draft publishes the extension on the VS Code Marketplace and on Open VSX.** Pushing the tag publishes nothing. Open VSX lists the new version after a few minutes, the Marketplace after about ten.
- **Code names** (the `description` attribute of `<release>`) are not used since vscode-ltex-plus 15.7.0, and CI does not need them.
- **After the release:**
  - In each released repository, set the next development version in `pom.xml` or `package.json` (e.g., `19.0.1-alpha`), and add an empty `<release version="..." date="upcoming">` entry to `changelog.xml`.
  - Update the website: in the ltex-plus repository, run `tools/updatePagesFromSource.py --vscode-ltex-repo <path> --ltex-ls-repo <path>`. The settings and changelog pages are generated from vscode-ltex-plus (`package.json`, `package.nls.json`, `package.nls.de.json`) and from the changelogs of both repositories, so do not edit them by hand. A setting that the server reads but vscode-ltex-plus does not declare is missing from the website.
  - Check the pages written by hand, such as `advanced-usage.md` and `ltex-ls-plus/server-usage.md`, against the changelog.
