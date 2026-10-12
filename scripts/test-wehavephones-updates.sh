#!/usr/bin/env bash
# Host checks for GitHub app releases and APK integrity/compatibility policy.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  source "$ROOT_DIR/.tools/env.sh"
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wehavephones-updates-XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
SRC_DIR="$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile"
"$JAVAC" -encoding UTF-8 -d "$WORK_DIR" \
  "$SRC_DIR/addons/repo/Json.java" "$SRC_DIR/addons/scan/VersionComparator.java" \
  "$SRC_DIR/updates/AppRelease.java" "$ROOT_DIR/tools/wehavephones-updates-selftest/AppUpdatesSelfTest.java"
"$JAVA" -cp "$WORK_DIR" AppUpdatesSelfTest
echo "[test-wehavephones-updates] PASS"
