#!/usr/bin/env bash
# Host self-tests for the one-button launch (no adb, no device).
#
#   1. Java (tools/wehavephones-launch-selftest/LaunchSelfTest): compiles the real
#      BattleNetLauncherLog, BattleNetPlayButton, WeHavePhonesLaunchPlan and
#      WeHavePhonesBnetConfig and checks
#      product-code mapping + overrides, readiness/update parsing against
#      sanitized device logs (fixtures/), main-log selection vs the --exec
#      forwarder, launch/start bat generation, the 64-byte WinHandler EXEC
#      packet limit, fallback ladder ordering, the greyed-out Play button
#      classifier and the Battle.net.config edit.
#   1a. Java (BattleNetGamesSelfTest): product.db protobuf decoding,
#       .build.info/.flavor.info parsing and the merged Battle.net game list.
#   1b. Java (ProcessScannerSelfTest): real /proc scan with fake WoW and
#      Battle.net processes (bash exec -a) - game detection, launcher reap.
#   1c. Java (WowClientFilesSelfTest): Config.wtf CVars, first-boot seeding
#      and the Logs/ marker the launch overlay waits for.
#   1d/1e. Java (GlueInputBridgeSelfTest, WowExitDetectorSelfTest): the
#      pre-game controller bridge and the return to the menu when WoW closes.
#   1i. Java (InputBridgeCompanionSelfTest): guest path of the user's own
#      Input Bridge ib.exe through the container drive mappings.
#   2. Python (tools/wehavephones-launch-selftest/reconnect_selftest.py): the
#      reconnect/detached-run helper scripts/wehavephones-device.py against a fake
#      adb shim (backoff, give-up deadline, USB preference, drop + resume, the
#      configurable list of apps `protect` keeps alive).
#
# Exits non-zero when any check fails.
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
SELF_TEST_DIR="$ROOT_DIR/tools/wehavephones-launch-selftest"
SRC_DIR="$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile"
ASSETS_DIR="$ROOT_DIR/app/src/wehavephones/assets/wehavephones"
WORK_DIR="${TMPDIR:-/tmp}/wehavephones-launch-selftest-$$"
FAILURES=0

fail() {
  echo "[test-wehavephones-launch] FAIL: $*" >&2
  FAILURES=$((FAILURES + 1))
}

echo "[test-wehavephones-launch] == 1. launch logic host test =="
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
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"
    SOURCES=(
      "$SRC_DIR/BattleNetLauncherLog.java"
      "$SRC_DIR/BattleNetPlayButton.java"
      "$SRC_DIR/WeHavePhonesLaunchPlan.java"
      "$SRC_DIR/WeHavePhonesBnetConfig.java"
      "$SRC_DIR/AddonApp.java"
      "$SELF_TEST_DIR/LaunchSelfTest.java"
    )
    if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" -cp "$JSON_JAR" "${SOURCES[@]}"; then
      set +e
      "$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.wehavephones.mobile.LaunchSelfTest \
        "$ASSETS_DIR/wehavephones-launch-products.json" \
        "$ASSETS_DIR/wehavephones-runtime-profiles.json" \
        "$SELF_TEST_DIR/fixtures"
      STATUS=$?
      set -e
      [[ $STATUS -eq 0 ]] || fail "LaunchSelfTest exited $STATUS"
    else
      fail "javac failed for LaunchSelfTest"
    fi
    rm -rf "$WORK_DIR"
  fi
fi

echo
echo "[test-wehavephones-launch] == 1a. Battle.net game list (product.db, .build.info, merge) =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  SOURCES=(
    "$SRC_DIR/BattleNetProductDb.java"
    "$SRC_DIR/BattleNetBuildInfo.java"
    "$SRC_DIR/BattleNetGames.java"
    "$SRC_DIR/BattleNetLauncherLog.java"
    "$SRC_DIR/WeHavePhonesLaunchPlan.java"
    "$SRC_DIR/WowFlavor.java"
    "$SRC_DIR/WowProduct.java"
    "$SELF_TEST_DIR/BattleNetGamesSelfTest.java"
  )
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" -cp "${JSON_JAR:-}" "${SOURCES[@]}"; then
    set +e
    "$JAVA" -cp "$WORK_DIR:${JSON_JAR:-}" com.wehavephones.mobile.BattleNetGamesSelfTest
    STATUS=$?
    set -e
    [[ $STATUS -eq 0 ]] || fail "BattleNetGamesSelfTest exited $STATUS"
  else
    fail "javac failed for BattleNetGamesSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 1b. ProcessScanner / launcher reaper against real /proc =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  SOURCES=(
    "$SRC_DIR/ProcessScanner.java"
    "$SRC_DIR/WowFlavor.java"
    "$SRC_DIR/WeHavePhonesLauncherReaper.java"
    "$SELF_TEST_DIR/ProcessScannerSelfTest.java"
    "$SELF_TEST_DIR/stubs/android/os/Process.java"
    "$SELF_TEST_DIR/stubs/android/os/Handler.java"
    "$SELF_TEST_DIR/stubs/android/os/Looper.java"
    "$SELF_TEST_DIR/stubs/android/content/Context.java"
  )
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "${SOURCES[@]}"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.ProcessScannerSelfTest || fail "ProcessScannerSelfTest"
  else
    fail "javac failed for ProcessScannerSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 1c. WoW client files =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "$SRC_DIR/WowClientFiles.java" "$SRC_DIR/MemoryTransforms.java" \
      "$SRC_DIR/WowFirstBoot.java" "$SELF_TEST_DIR/WowClientFilesSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.WowClientFilesSelfTest || fail "WowClientFilesSelfTest"
  else
    fail "javac failed for WowClientFilesSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 1d. pre-game controller bridge =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "$SRC_DIR/GlueInputBridge.java" "$SELF_TEST_DIR/GlueInputBridgeSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.GlueInputBridgeSelfTest || fail "GlueInputBridgeSelfTest"
  else
    fail "javac failed for GlueInputBridgeSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 1e. return to the menu when the game closes =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "$SRC_DIR/WowExitDetector.java" "$SELF_TEST_DIR/WowExitDetectorSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.WowExitDetectorSelfTest || fail "WowExitDetectorSelfTest"
  else
    fail "javac failed for WowExitDetectorSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 1f. launch overlay state and reveal rules =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "$SRC_DIR/LaunchProgress.java" "$SELF_TEST_DIR/LaunchProgressSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.LaunchProgressSelfTest || fail "LaunchProgressSelfTest"
  else
    fail "javac failed for LaunchProgressSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 1g. one-time setup routing and interrupted installs =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "$SRC_DIR/InitialSetupState.java" "$SELF_TEST_DIR/InitialSetupSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.InitialSetupSelfTest || fail "InitialSetupSelfTest"
  else
    fail "javac failed for InitialSetupSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 1h. WoW file import (integrity, space, cancellation, preservation) =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "$SRC_DIR/WowFlavor.java" "$SRC_DIR/WowFileImporter.java" \
      "$SELF_TEST_DIR/WowFileImporterSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.WowFileImporterSelfTest || fail "WowFileImporterSelfTest"
  else
    fail "javac failed for WowFileImporterSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 1i. Input Bridge helper path (user-supplied ib.exe) =="
if [[ -n "$JAVAC" && -n "$JAVA" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  if "$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "$SRC_DIR/InputBridgeCompanion.java" \
      "$SELF_TEST_DIR/InputBridgeCompanionSelfTest.java"; then
    "$JAVA" -cp "$WORK_DIR" com.wehavephones.mobile.InputBridgeCompanionSelfTest || fail "InputBridgeCompanionSelfTest"
  else
    fail "javac failed for InputBridgeCompanionSelfTest"
  fi
  rm -rf "$WORK_DIR"
fi

echo
echo "[test-wehavephones-launch] == 2. reconnect helper (fake adb) =="
if ! "$PYTHON" "$SELF_TEST_DIR/reconnect_selftest.py"; then
  fail "reconnect_selftest.py"
fi

echo
echo "[test-wehavephones-launch] == 3. fixtures contain no account identifiers =="
if grep -nE 'bnet=|game=[0-9]|SavedAccountNames|@[A-Za-z0-9.-]+\.[a-z]{2,}' "$SELF_TEST_DIR"/fixtures/*.log; then
  fail "fixture logs contain account identifiers"
fi

if [[ $FAILURES -ne 0 ]]; then
  echo "[test-wehavephones-launch] FAIL ($FAILURES failing checks)"
  exit 1
fi
echo "[test-wehavephones-launch] PASS"
