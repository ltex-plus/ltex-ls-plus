#!/usr/bin/python3

# Copyright (C) 2019-2025
# Julian Valentin, Daniel Spitzer, LTeX+ Development Community
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

import os
import pathlib
import shutil
import tarfile
import tempfile
import urllib.request

lspCliVersion = "3.0.0"



def main() -> None:
  with tempfile.TemporaryDirectory() as tmpDirPathStr:
    tmpDirPath = pathlib.Path(tmpDirPathStr)

    lspCliArchiveName = f"lsp-cli-plus-{lspCliVersion}.tar.gz"
    lspCliUrl = ("https://github.com/ltex-plus/lsp-cli-plus/releases/download/"
        f"{lspCliVersion}/{lspCliArchiveName}")
    lspCliArchivePath = tmpDirPath.joinpath(lspCliArchiveName)
    print(f"Downloading lsp-cli-plus {lspCliVersion} from '{lspCliUrl}' to '{lspCliArchivePath}'...")
    urllib.request.urlretrieve(lspCliUrl, lspCliArchivePath)

    print("Extracting lsp-cli-plus archive...")
    with tarfile.open(lspCliArchivePath, "r:gz") as tarFile: tarFile.extractall(path=tmpDirPath)

    lspCliDirPath = tmpDirPath.joinpath(f"lsp-cli-plus-{lspCliVersion}")
    targetDirPath = pathlib.Path(__file__).parent.parent.joinpath("target", "appassembler")

    print("Copying *.jar files...")

    for jarFilePath in lspCliDirPath.joinpath("lib").iterdir():
      shutil.copy(jarFilePath, targetDirPath.joinpath("lib"))

    print("Copying startup scripts...")

    for extension in ["", ".bat"]:
      targetFilePath = targetDirPath.joinpath("bin", f"ltex-cli-plus{extension}")
      shutil.copyfile(lspCliDirPath.joinpath("bin", f"lsp-cli-plus{extension}"), targetFilePath)

      if extension == "":
        mode = os.stat(targetFilePath).st_mode
        mode |= (mode & 0o444) >> 2
        os.chmod(targetFilePath, mode)

    print("Creating .lsp-cli-plus.json...")
    lspCliJson = """
{
  "programName": "ltex-cli-plus",
  "helpMessage": {
    "description": "LTeX+ CLI - Command-line interface for LTeX+ LS",
    "visibleArguments": [
      "--client-configuration",
      "--verbose"
    ]
  },
  "defaultValues": {
    "--hide-commands": true,
    "--server-command-line": "./ltex-ls-plus"
  }
}
""".lstrip()
    with open(targetDirPath.joinpath("bin", ".lsp-cli-plus.json"), "w") as f: f.write(lspCliJson)



if __name__ == "__main__":
  main()
