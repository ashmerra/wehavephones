#!/usr/bin/env bash
# Exercise real gamepad session control, preferences, UDP replies and evdev releases on the host.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  source "$ROOT_DIR/.tools/env.sh"
fi
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wehavephones-gamepad-selftest.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
TEST_DIR="$ROOT_DIR/tools/wehavephones-gamepad-selftest"
MAIN="$ROOT_DIR/app/src/main/java/com/wehavephones"
WEHAVEPHONES="$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile"
ANDROID_JAR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}/platforms/android-30/android.jar"
JSON_JAR="${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" -name 'json-*.jar' | sort -V | tail -n 1)}"
mapfile -t STUBS < <(find "$TEST_DIR/stubs" -name '*.java' | sort)
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -cp "$JSON_JAR:$ANDROID_JAR" -d "$WORK_DIR" \
  "${STUBS[@]}" "$TEST_DIR/GamepadSelfTest.java" \
  "$WEHAVEPHONES/VirtualGamepadController.java" "$WEHAVEPHONES/WeHavePhonesConfig.java" \
  "$WEHAVEPHONES/MemoryTransforms.java" "$WEHAVEPHONES/WowFirstBoot.java" "$WEHAVEPHONES/WowClientFiles.java" \
  "$MAIN/inputcontrols/GamepadState.java" "$MAIN/inputcontrols/FakeGamepadWriter.java" \
  "$MAIN/winhandler/WinHandler.java" "$MAIN/winhandler/RequestCodes.java" \
  "$MAIN/winhandler/OnImeFocusListener.java" "$MAIN/winhandler/MouseEventFlags.java" \
  "$MAIN/winhandler/ProcessInfo.java" "$MAIN/winhandler/OnGetProcessInfoListener.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$WORK_DIR:$JSON_JAR:$ANDROID_JAR" com.wehavephones.winhandler.GamepadSelfTest
