#!/usr/bin/env bash
# Fill real stdout/stderr pipes when session logging is absent or fails.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  source "$ROOT_DIR/.tools/env.sh"
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wehavephones-process-selftest.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
TEST_DIR="$ROOT_DIR/tools/wehavephones-runtime-selftest"
"$JAVAC" -encoding UTF-8 -d "$WORK_DIR" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/core/ProcessHelper.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/core/Callback.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/core/SessionLogs.java" \
  "$TEST_DIR/process-stubs/android/os/Process.java" \
  "$TEST_DIR/process-stubs/com/wehavephones/xenvironment/ImageFs.java" \
  "$TEST_DIR/ProcessOutputSelfTest.java"
"$JAVA" --add-opens java.base/java.lang=ALL-UNNAMED -cp "$WORK_DIR" com.wehavephones.core.ProcessOutputSelfTest
