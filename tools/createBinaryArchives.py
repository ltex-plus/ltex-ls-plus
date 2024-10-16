#!/usr/bin/python3

# Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

import json
import pathlib
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile
import os
import stat

javaVersion = "21.0.4+7"



def createBinaryArchive(platform: str, arch: str) -> None:
  print(f"Processing platform/arch '{platform}/{arch}'...")
  ltexLsVersion = getLtexLsVersion()
  targetDirPath = pathlib.Path(__file__).parent.parent.joinpath("target")
  ltexLsArchivePath = pathlib.Path(__file__).parent.parent.joinpath(
      targetDirPath, f"ltex-ls-plus-{ltexLsVersion}.tar.gz")

  with tempfile.TemporaryDirectory() as tmpDirPathStr:
    tmpDirPath = pathlib.Path(tmpDirPathStr)

    print("Extracting LTeX LS archive...")
    with tarfile.open(ltexLsArchivePath, "r:gz") as tarFile: tarFile.extractall(path=tmpDirPath)

    ltexLsDirPath = tmpDirPath.joinpath(f"ltex-ls-plus-{ltexLsVersion}")
    relativeJavaDirPath = downloadJava(tmpDirPath, ltexLsDirPath, platform, arch)

    print("Setting default for JAVA_HOME in startup script...")

    if platform == "windows":
      ltexLsDirPath.joinpath("bin", "ltex-ls-plus").unlink()
      ltexLsDirPath.joinpath("bin", "ltex-cli-plus").unlink()
      ltexLsBinScriptPath = ltexLsDirPath.joinpath("bin", "ltex-ls-plus.bat")
      ltexCliBinScriptPath = ltexLsDirPath.joinpath("bin", "ltex-cli-plus.bat")
      binScriptJavaHomeSearchPattern = re.compile("^set REPO=.*$", flags=re.MULTILINE)
    else:
      ltexLsDirPath.joinpath("bin", "ltex-ls-plus.bat").unlink()
      ltexLsDirPath.joinpath("bin", "ltex-cli-plus.bat").unlink()
      ltexLsBinScriptPath = ltexLsDirPath.joinpath("bin", "ltex-ls-plus")
      ltexCliBinScriptPath = ltexLsDirPath.joinpath("bin", "ltex-cli-plus")
      binScriptJavaHomeSearchPattern = re.compile("^BASEDIR=.*$", flags=re.MULTILINE)

    binScriptJavaHomeInsertString = (
        f"\r\nif not defined JAVA_HOME set JAVA_HOME=\"%BASEDIR%\\{relativeJavaDirPath}\""
        if platform == "windows" else
        f"\n[ -z \"$JAVA_HOME\" ] && JAVA_HOME=\"$BASEDIR\"/{relativeJavaDirPath}")

    for binScriptPath in [ltexLsBinScriptPath, ltexCliBinScriptPath]:
      with open(binScriptPath, "r") as file: binScript = file.read()
      regexMatch = binScriptJavaHomeSearchPattern.search(binScript)
      assert regexMatch is not None
      binScript = (binScript[:regexMatch.end()] + binScriptJavaHomeInsertString
          + binScript[regexMatch.end():])
      with open(binScriptPath, "w") as file: file.write(binScript)

    print("Setting script name in .lsp-cli.json...")
    lspCliJsonPath = ltexLsDirPath.joinpath("bin", ".lsp-cli.json")
    with open(lspCliJsonPath, "r") as file: lspCliJson = json.load(file)
    lspCliJson["defaultValues"]["--server-command-line"] = (
        "ltex-ls-plus.bat" if platform == "windows" else "./ltex-ls-plus")

    with open(lspCliJsonPath, "w") as file:
      json.dump(lspCliJson, file, indent=2, ensure_ascii=False)

    ltexLsBinaryArchiveFormat = ("zip" if platform == "windows" else "gztar")
    ltexLsBinaryArchiveExtension = (".zip" if platform == "windows" else ".tar.gz")
    ltexLsBinaryArchivePath = targetDirPath.joinpath(
        f"ltex-ls-plus-{ltexLsVersion}-{platform}-{arch}")

    print(f"Creating binary archive '{ltexLsBinaryArchivePath}{ltexLsBinaryArchiveExtension}'...")
    shutil.make_archive(str(ltexLsBinaryArchivePath), ltexLsBinaryArchiveFormat,
        root_dir=tmpDirPath)
    print("")



def downloadJava(tmpDirPath: pathlib.Path, ltexLsDirPath: pathlib.Path,
      platform: str, arch: str) -> str:
  javaArchiveExtension = (".zip" if platform == "windows" else ".tar.gz")
  javaArchiveName = (f"OpenJDK21U-jdk_{arch}_{platform}_hotspot_"
      f"{javaVersion.replace('+', '_')}{javaArchiveExtension}")

  javaUrl = ("https://github.com/adoptium/temurin21-binaries/releases/download/"
      f"jdk-{urllib.parse.quote_plus(javaVersion)}/{javaArchiveName}")
  relativeJavaDirPathString = f"jdk-{javaVersion}"

  # See https://github.com/adoptium/adoptium-support/issues/616
  if platform == "windows" and arch == "aarch64":
    print("Temurin JDK for Windows on ARM is currently available as beta version only.")    
    relativeJavaDirPathString = "jdk-21.0.5+9"
    javaUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B9-ea-beta/OpenJDK21U-jdk_aarch64_windows_hotspot_21.0.5_9-ea.zip"

  javaArchivePath = ltexLsDirPath.joinpath(javaArchiveName)
  print(f"Downloading JDK from '{javaUrl}' to '{javaArchivePath}'...")
  urllib.request.urlretrieve(javaUrl, javaArchivePath)
  print("Extracting JDK archive...")

  if javaArchiveExtension == ".zip":
    with zipfile.ZipFile(javaArchivePath, "r") as zipFile: zipFile.extractall(path=tmpDirPath)
  else:
    with tarfile.open(javaArchivePath, "r:gz") as tarFile: tarFile.extractall(path=tmpDirPath)

  print("Removing JDK archive...")
  javaArchivePath.unlink()

  jdkDirPath = tmpDirPath.joinpath(relativeJavaDirPathString)
  jmodsDirPath = (jdkDirPath.joinpath("jmods") if platform != "mac" else
      jdkDirPath.joinpath("Contents", "Home", "jmods"))
  javaTargetDirPath = ltexLsDirPath.joinpath(relativeJavaDirPathString)

  # List generated by downloading last AdoptOpenJDK JRE and running "bin/java --list-modules".
  # "java.se" doesn't suffice as this causes LTeX LS to crash when started by VS Code
  # ("Unable to invoke no-args constructor for class org.eclipse.lsp4j.SemanticTokensCapabilities.
  # Registering an InstanceCreator with Gson for this type may fix this problem.").
  javaModules = [
        "java.base",
        "java.compiler",
        "java.datatransfer",
        "java.desktop",
        "java.instrument",
        "java.logging",
        "java.management",
        "java.management.rmi",
        "java.naming",
        "java.net.http",
        "java.prefs",
        "java.rmi",
        "java.scripting",
        "java.se",
        "java.security.jgss",
        "java.security.sasl",
        "java.smartcardio",
        "java.sql",
        "java.sql.rowset",
        "java.transaction.xa",
        "java.xml",
        "java.xml.crypto",
        "jdk.accessibility",
        "jdk.charsets",
        "jdk.crypto.cryptoki",
        "jdk.crypto.ec",
        "jdk.dynalink",
        "jdk.httpserver",
        "jdk.incubator.vector",
        "jdk.internal.vm.ci",
        "jdk.internal.vm.compiler",
        "jdk.internal.vm.compiler.management",
        "jdk.jdwp.agent",
        "jdk.jfr",
        "jdk.jsobject",
        "jdk.localedata",
        "jdk.management",
        "jdk.management.agent",
        "jdk.naming.dns",
        "jdk.naming.rmi",
        "jdk.net",
        "jdk.nio.mapmode",
        "jdk.sctp",
        "jdk.security.auth",
        "jdk.security.jgss",
        "jdk.unsupported",
        "jdk.xml.dom",
        "jdk.zipfs",
      ]

  print("Creating Java distribution...")
  subprocess.run(["jlink", "--module-path", str(jmodsDirPath),
      "--add-modules", ",".join(javaModules), "--strip-debug", "--no-man-pages",
      "--no-header-files", "--compress=2", "--output", str(javaTargetDirPath)])
  assert javaTargetDirPath.is_dir()

  print("Removing JDK directory...")
  shutil.rmtree(jdkDirPath, onerror=remove_readonly)

  return relativeJavaDirPathString



def getLtexLsVersion() -> str:
  with open("pom.xml", "r") as file:
    regexMatch = re.search(r"<version>(.*?)</version>", file.read())
    assert regexMatch is not None
    return regexMatch.group(1)


def remove_readonly(func, path, _):
    os.chmod(path, stat.S_IWRITE)
    func(path)


def main() -> None:
  createBinaryArchive("linux", "x64")
  createBinaryArchive("mac", "x64")
  createBinaryArchive("windows", "x64")
  createBinaryArchive("linux", "aarch64")
  createBinaryArchive("mac", "aarch64")
  createBinaryArchive("windows", "aarch64")


if __name__ == "__main__":
  main()
