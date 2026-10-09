#!/usr/bin/env bash
# Host JVM self-test for the WeHavePhones addon manager pure logic.
# Compiles a subset of app/src/wehavephones sources plus tools/wehavephones-addons-selftest
# and runs AddonLogicSelfTest. Exits non-zero when any check fails.
#
# Usage:
#   scripts/test-wehavephones-addons.sh
#   COMMONS_COMPRESS_JAR=/path/to/commons-compress-1.20.jar scripts/test-wehavephones-addons.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.tools/env.sh"
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC="$(command -v javac || true)"
  JAVA="$(command -v java || true)"
fi

if [[ -z "$JAVAC" || -z "$JAVA" ]]; then
  echo "[test-wehavephones-addons] No JDK found. Run scripts/setup-dev.sh or set JAVA_HOME." >&2
  exit 1
fi

COMMONS_JAR="${COMMONS_COMPRESS_JAR:-}"
if [[ -z "$COMMONS_JAR" ]]; then
  COMMONS_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.apache.commons/commons-compress" \
    -name 'commons-compress-1.20.jar' 2>/dev/null | head -n 1)"
fi
if [[ -z "$COMMONS_JAR" ]]; then
  COMMONS_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.apache.commons/commons-compress" \
    -name 'commons-compress-*.jar' 2>/dev/null | sort -V | tail -n 1)"
fi
if [[ -z "$COMMONS_JAR" || ! -f "$COMMONS_JAR" ]]; then
  echo "[test-wehavephones-addons] commons-compress jar not found; set COMMONS_COMPRESS_JAR." >&2
  exit 1
fi

ADDONS_DIR="$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile/addons"
SELF_TEST_DIR="$ROOT_DIR/tools/wehavephones-addons-selftest"
OUT_DIR="${TMPDIR:-/tmp}/wehavephones-addons-selftest-$$"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

SOURCES=(
  "$ADDONS_DIR/../WowFlavor.java"
  "$ADDONS_DIR/model/TocData.java"
  "$ADDONS_DIR/model/AddonFolderInfo.java"
  "$ADDONS_DIR/model/AddonRecord.java"
  "$ADDONS_DIR/model/ProviderType.java"
  "$ADDONS_DIR/model/InstallStatus.java"
  "$ADDONS_DIR/model/Channel.java"
  "$ADDONS_DIR/model/RemoteAddon.java"
  "$ADDONS_DIR/model/RemoteFile.java"
  "$ADDONS_DIR/scan/VersionComparator.java"
  "$ADDONS_DIR/scan/TocParser.java"
  "$ADDONS_DIR/scan/AddonScanner.java"
  "$ADDONS_DIR/install/ZipSecurityException.java"
  "$ADDONS_DIR/install/SafeZipExtractor.java"
  "$ADDONS_DIR/install/DiscoveredAddon.java"
  "$ADDONS_DIR/install/ZipImporter.java"
  "$ADDONS_DIR/install/FileOps.java"
  "$ADDONS_DIR/install/AddonInstaller.java"
  "$ADDONS_DIR/provider/ProviderUrls.java"
  "$ADDONS_DIR/provider/GitHubRepo.java"
  "$ADDONS_DIR/provider/GitHubAssetSelector.java"
  "$ADDONS_DIR/repo/Json.java"
  "$ADDONS_DIR/repo/AddonIndex.java"
  "$ADDONS_DIR/required/ConsolePort.java"
  "$ADDONS_DIR/required/ControllerSetupAddon.java"
  "$SELF_TEST_DIR/AddonLogicSelfTest.java"
)

echo "[test-wehavephones-addons] javac: $JAVAC"
echo "[test-wehavephones-addons] commons-compress: $COMMONS_JAR"
"$JAVAC" -encoding UTF-8 -d "$OUT_DIR" -cp "$COMMONS_JAR" "${SOURCES[@]}"

set +e
CONSOLEPORT_ASSETS="$ROOT_DIR/app/src/wehavephones/assets/wehavephones/addons"
CONSOLEPORT_VERSION="$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$CONSOLEPORT_ASSETS/consoleport.json")"
"$JAVA" -Dwehavephones.consoleport.zip="$CONSOLEPORT_ASSETS/ConsolePort.zip" \
  -Dwehavephones.consoleport.version="$CONSOLEPORT_VERSION" \
  -Dwehavephones.controllersetup.dir="$CONSOLEPORT_ASSETS/WeHavePhonesControllerSetup" \
  -cp "$OUT_DIR:$COMMONS_JAR" AddonLogicSelfTest
STATUS=$?
set -e
rm -rf "$OUT_DIR"

if [[ $STATUS -ne 0 ]]; then
  echo "[test-wehavephones-addons] FAIL (exit $STATUS)"
  exit $STATUS
fi
echo "[test-wehavephones-addons] PASS"
