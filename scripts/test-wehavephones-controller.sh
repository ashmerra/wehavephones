#!/usr/bin/env bash
# Exercise simultaneous physical controller input (triggers, D-pad, sticks) on the host.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  source "$ROOT_DIR/.tools/env.sh"
fi
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wehavephones-controller-selftest.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
TEST_DIR="$ROOT_DIR/tools/wehavephones-controller-selftest"
INPUT="$ROOT_DIR/app/src/main/java/com/wehavephones/inputcontrols"
JSON_JAR="${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" -name 'json-*.jar' | sort -V | tail -n 1)}"
mapfile -t STUBS < <(find "$TEST_DIR/stubs" -name '*.java' | sort)
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -cp "$JSON_JAR" -d "$WORK_DIR" \
  "${STUBS[@]}" "$TEST_DIR/ControllerSelfTest.java" \
  "$INPUT/ExternalController.java" "$INPUT/GamepadState.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$WORK_DIR:$JSON_JAR" com.wehavephones.inputcontrols.ControllerSelfTest
