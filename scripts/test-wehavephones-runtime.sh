#!/usr/bin/env bash
# Host self-tests for the bundled WeHavePhones runtime profiles and artifacts.
#
#   1. Python check (tools/wehavephones-runtime-selftest/runtime_profile_selftest.py):
#      parses app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json
#      and asserts the single-runtime contract (mode/profile names), that every
#      referenced asset exists and that every sha256 matches, cross-checked
#      against out/wehavephones-runtime/artifacts.json when present.
#   2. Java check (tools/wehavephones-runtime-selftest/RuntimeProfileParseSelfTest):
#      compiles the real WeHavePhonesRuntimeProfiles with file-serving stubs and
#      verifies schema parsing (ntdll/files/layer), backward compatibility for
#      profiles without files, and single/handoff stage selection.
#   3. Java check (tools/wehavephones-runtime-selftest/PresentPacerSelfTest):
#      the X server's frame pacing while the session is hidden (keep-alive):
#      200 ms spacing, presentation order, inline again once drained.
#   4. Java check (tools/wehavephones-runtime-selftest/WineLayerInstallerSelfTest):
#      the in-app Wine layer download: sha256 check, staged install, cached
#      archive reuse, and no half-installed layer on any failure.
#   5. Java check (tools/wehavephones-runtime-selftest/WineFontPathsSelfTest):
#      repair inherited Wine font basenames before CEF starts; preserve custom
#      fonts, unrelated registry data, and subsequent launches.
#      WineFontRegistrySelfTest: register the prefix's real CJK fonts so
#      Battle.net's web pages draw Chinese and Korean text.
#
# No adb, no device. Exits non-zero when any check fails.
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

PYTHON="${PYTHON:-python3}"
SELF_TEST_DIR="$ROOT_DIR/tools/wehavephones-runtime-selftest"
PROFILE_JSON="$ROOT_DIR/app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json"
WORK_DIR="${TMPDIR:-/tmp}/wehavephones-runtime-selftest-$$"
FAILURES=0

fail() {
  echo "[test-wehavephones-runtime] FAIL: $*" >&2
  FAILURES=$((FAILURES + 1))
}

echo "[test-wehavephones-runtime] == 1. profile JSON + asset sha256 =="
if ! "$PYTHON" "$ROOT_DIR/scripts/verify-wehavephones-turnip.py"; then
  fail "pinned Mesa Turnip bundle"
fi
if ! "$PYTHON" "$SELF_TEST_DIR/runtime_profile_selftest.py"; then
  fail "runtime_profile_selftest.py"
fi

echo
echo "[test-wehavephones-runtime] == 2. schema + stage selection host test =="
if [[ -z "$JAVAC" || -z "$JAVA" ]]; then
  fail "No JDK found. Run scripts/setup-dev.sh or set JAVA_HOME."
else
  JSON_JAR="${JSON_JAR:-}"
  if [[ -z "$JSON_JAR" ]]; then
    JSON_JAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json/json" \
      -name 'json-*.jar' 2>/dev/null | sort -V | tail -n 1)"
  fi
  if [[ -z "$JSON_JAR" || ! -f "$JSON_JAR" ]]; then
    fail "org.json jar not found; run ./gradlew :app:assembleWehavephonesDebug or set JSON_JAR."
  else
    echo "[test-wehavephones-runtime] javac: $JAVAC"
    echo "[test-wehavephones-runtime] org.json: $JSON_JAR"
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"
    SOURCES=(
      "$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile/WeHavePhonesRuntimeProfiles.java"
      "$ROOT_DIR/app/src/main/java/com/wehavephones/core/DefaultVersion.java"
      "$SELF_TEST_DIR/RuntimeProfileParseSelfTest.java"
      "$SELF_TEST_DIR/stubs/android/content/Context.java"
      "$SELF_TEST_DIR/stubs/com/wehavephones/core/FileUtils.java"
      "$SELF_TEST_DIR/stubs/com/wehavephones/mobile/WeHavePhonesConfig.java"
    )
    if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" -cp "$JSON_JAR" "${SOURCES[@]}"; then
      set +e
      "$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.wehavephones.mobile.RuntimeProfileParseSelfTest "$PROFILE_JSON"
      STATUS=$?
      set -e
      if [[ $STATUS -ne 0 ]]; then
        fail "RuntimeProfileParseSelfTest exited $STATUS"
      fi
    else
      fail "javac failed for RuntimeProfileParseSelfTest"
    fi
    rm -rf "$WORK_DIR"
  fi
fi

echo
echo "[test-wehavephones-runtime] == 3. hidden-session frame pacing =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xserver/extensions/PresentPacer.java" \
      "$SELF_TEST_DIR/PresentPacerSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.xserver.extensions.PresentPacerSelfTest || fail "PresentPacerSelfTest"
  else
    fail "javac failed for PresentPacerSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-runtime] == 4. in-app Wine layer install =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" \
      "$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile/WineLayerInstaller.java" \
      "$SELF_TEST_DIR/WineLayerInstallerSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.WineLayerInstallerSelfTest || fail "WineLayerInstallerSelfTest"
  else
    fail "javac failed for WineLayerInstallerSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-runtime] == 5. Wine font registry paths =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/core/WineFontPaths.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/core/WineFontRegistry.java" \
      "$SELF_TEST_DIR/WineFontPathsSelfTest.java" "$SELF_TEST_DIR/WineFontRegistrySelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.core.WineFontPathsSelfTest || fail "WineFontPathsSelfTest"
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.core.WineFontRegistrySelfTest || fail "WineFontRegistrySelfTest"
  else
    fail "javac failed for WineFontPathsSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-runtime] == 6. X11 image clipping and copies =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xserver/GraphicsClip.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xserver/WineWindowStyle.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xserver/ClientOpcodes.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xserver/events/Event.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xserver/events/RawEvent.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xserver/events/NoExpose.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xserver/events/GraphicsExpose.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XOutputStream.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XSocketOutput.java" \
      "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XStreamLock.java" \
      "$SELF_TEST_DIR/graphics-stubs/com/wehavephones/xserver/XServer.java" \
      "$SELF_TEST_DIR/graphics-stubs/com/wehavephones/xconnector/ClientSocket.java" \
      "$SELF_TEST_DIR/GraphicsClipSelfTest.java" \
      "$SELF_TEST_DIR/WineWindowStyleSelfTest.java" \
      "$SELF_TEST_DIR/RawEventSelfTest.java" \
      "$SELF_TEST_DIR/CopyExposureSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.xserver.GraphicsClipSelfTest || fail "GraphicsClipSelfTest"
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.xserver.WineWindowStyleSelfTest || fail "WineWindowStyleSelfTest"
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.xserver.CopyExposureSelfTest || fail "CopyExposureSelfTest"
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.xserver.RawEventSelfTest || fail "RawEventSelfTest"
  else
    fail "javac failed for GraphicsClipSelfTest"
  fi
  if cc -Wall -Wextra -Werror "$SELF_TEST_DIR/DrawableCopySelfTest.c" -o "$WORK_DIR/drawable-copy-test"; then
    "$WORK_DIR/drawable-copy-test" || fail "DrawableCopySelfTest"
  else
    fail "cc failed for DrawableCopySelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-runtime] == 7. audio writes during shutdown =="
"$ROOT_DIR/scripts/test-wehavephones-audio.sh" || fail "ALSAClientSelfTest"

echo
echo "[test-wehavephones-runtime] == 8. X output under socket backpressure =="
"$ROOT_DIR/scripts/test-wehavephones-xoutput.sh" || fail "XSocketOutputSelfTest"

echo
echo "[test-wehavephones-runtime] == 9. guest output without session logging =="
"$ROOT_DIR/scripts/test-wehavephones-process.sh" || fail "ProcessOutputSelfTest"

echo
echo "[test-wehavephones-runtime] == 10. automatic virtual gamepad and controller handoff =="
"$ROOT_DIR/scripts/test-wehavephones-gamepad.sh" || fail "GamepadSelfTest"

echo
echo "[test-wehavephones-runtime] == 11. concurrent native connector isolation =="
"$ROOT_DIR/scripts/test-wehavephones-xconnector.sh" || fail "XConnectorIsolationSelfTest"

if [[ $FAILURES -ne 0 ]]; then
  echo "[test-wehavephones-runtime] FAIL ($FAILURES failing checks)"
  exit 1
fi
echo "[test-wehavephones-runtime] PASS"
