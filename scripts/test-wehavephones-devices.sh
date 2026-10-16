#!/usr/bin/env bash
# Host self-tests for the per-device graphics rules (no adb, no device, no Gradle).
#
#   1. Python (tools/wehavephones-devices-selftest/device_rules_selftest.py): the bundled
#      device-rules.json is valid (schema, unique ids, every rule's profile and every
#      profile's driver exist, catch-all last), every bundled driver asset exists with the
#      archive and library sha256 the rules claim, and device-rules.json.sig verifies
#      against the public key embedded in DeviceRulesKey.java (openssl).
#   2. Java (DeviceRulesSelfTest): compiles the real GraphicsPlan, GpuProbe and the
#      com.wehavephones.mobile.devices classes with android stubs and checks parsing,
#      matching, remote-rules acceptance (throwaway key), driver install/download,
#      probe fallback order, crash guard, probe cache and equivalence with the
#      pre-rules behaviour for the S23 and Pixel 8a.
#      DeviceReportSelfTest checks the device report block and the prefilled issue link.
#   3. Java (GpuProbeJsonSelfTest), when present: the GpuProbe JSON round trip.
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
SELF_TEST_DIR="$ROOT_DIR/tools/wehavephones-devices-selftest"
RULES_JSON="$ROOT_DIR/app/src/wehavephones/assets/wehavephones/device-rules.json"
DEVICES_SRC="$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile/devices"
CORE_SRC="$ROOT_DIR/app/src/main/java/com/wehavephones/core"
WORK_DIR="${TMPDIR:-/tmp}/wehavephones-devices-selftest-$$"
FAILURES=0

fail() {
  echo "[test-wehavephones-devices] FAIL: $*" >&2
  FAILURES=$((FAILURES + 1))
}

find_jar() {
  find "$HOME/.gradle/caches/modules-2/files-2.1/$1" -name "$2" 2>/dev/null | sort -V | tail -n 1
}

echo "[test-wehavephones-devices] == 1. device-rules.json, driver assets, signature =="
"$PYTHON" -I "$SELF_TEST_DIR/device_rules_selftest.py" || fail "device_rules_selftest.py"
"$PYTHON" -I "$SELF_TEST_DIR/optional_turnip_selftest.py" || fail "optional_turnip_selftest.py"

echo
echo "[test-wehavephones-devices] == 2. rules, selection and driver store host test =="
if [[ -z "$JAVAC" || -z "$JAVA" ]]; then
  fail "No JDK found. Run scripts/setup-dev.sh or set JAVA_HOME."
else
  JSON_JAR="${JSON_JAR:-$(find_jar org.json/json 'json-*.jar')}"
  COMPRESS_JAR="${COMPRESS_JAR:-$(find_jar org.apache.commons/commons-compress 'commons-compress-*.jar')}"
  if [[ -z "$JSON_JAR" || ! -f "$JSON_JAR" ]]; then
    fail "org.json jar not found; run ./gradlew :app:assembleWehavephonesDebug or set JSON_JAR."
  elif [[ -z "$COMPRESS_JAR" || ! -f "$COMPRESS_JAR" ]]; then
    fail "commons-compress jar not found; run ./gradlew :app:assembleWehavephonesDebug or set COMPRESS_JAR."
  else
    echo "[test-wehavephones-devices] javac: $JAVAC"
    echo "[test-wehavephones-devices] org.json: $JSON_JAR"
    echo "[test-wehavephones-devices] commons-compress: $COMPRESS_JAR"
    rm -rf "$WORK_DIR"
    mkdir -p "$WORK_DIR"
    CLASSPATH_JARS="$JSON_JAR:$COMPRESS_JAR"
    SOURCES=(
      "$CORE_SRC/GraphicsPlan.java"
      "$CORE_SRC/GpuProbe.java"
      "$CORE_SRC/DefaultVersion.java"
      "$CORE_SRC/OnExtractFileListener.java"
      "$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile/addons/install/SafeZipExtractor.java"
      "$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile/addons/install/ZipSecurityException.java"
      "$DEVICES_SRC/DeviceIdentity.java"
      "$DEVICES_SRC/DeviceRules.java"
      "$DEVICES_SRC/DeviceRulesKey.java"
      "$DEVICES_SRC/DeviceRulesStore.java"
      "$DEVICES_SRC/DeviceDriverStore.java"
      "$DEVICES_SRC/DeviceProfileSelector.java"
      "$DEVICES_SRC/DeviceReport.java"
      "$SELF_TEST_DIR/DeviceRulesSelfTest.java"
      "$SELF_TEST_DIR/DeviceReportSelfTest.java"
    )
    while IFS= read -r stub; do SOURCES+=("$stub"); done < <(find "$SELF_TEST_DIR/stubs" -name '*.java' | sort)
    if "$JAVAC" -encoding UTF-8 -Xlint:-options -d "$WORK_DIR" -cp "$CLASSPATH_JARS" "${SOURCES[@]}"; then
      set +e
      "$JAVA" -cp "$WORK_DIR:$CLASSPATH_JARS" com.wehavephones.mobile.devices.DeviceRulesSelfTest "$RULES_JSON" \
        "$SELF_TEST_DIR/fixtures/device-rules-v6.json"
      STATUS=$?
      set -e
      if [[ $STATUS -ne 0 ]]; then
        fail "DeviceRulesSelfTest exited $STATUS"
      fi
      "$JAVA" -cp "$WORK_DIR:$CLASSPATH_JARS" com.wehavephones.mobile.devices.DeviceReportSelfTest || fail "DeviceReportSelfTest"
    else
      fail "javac failed for DeviceRulesSelfTest"
    fi
    rm -rf "$WORK_DIR"
  fi
fi

echo
echo "[test-wehavephones-devices] == 3. GpuProbe JSON round trip =="
if [[ -f "$SELF_TEST_DIR/GpuProbeJsonSelfTest.java" ]]; then
  JSON_JAR="${JSON_JAR:-$(find_jar org.json/json 'json-*.jar')}"
  rm -rf "$WORK_DIR"
  mkdir -p "$WORK_DIR"
  PROBE_SOURCES=("$CORE_SRC/GpuProbe.java" "$SELF_TEST_DIR/GpuProbeJsonSelfTest.java")
  while IFS= read -r stub; do PROBE_SOURCES+=("$stub"); done < <(find "$SELF_TEST_DIR/gpuprobe-stubs" -name '*.java' | sort)
  if "$JAVAC" -encoding UTF-8 -Xlint:-options -d "$WORK_DIR" -cp "$JSON_JAR" "${PROBE_SOURCES[@]}"; then
    "$JAVA" -cp "$WORK_DIR:$JSON_JAR" com.wehavephones.core.GpuProbeJsonSelfTest || fail "GpuProbeJsonSelfTest"
  else
    fail "javac failed for GpuProbeJsonSelfTest"
  fi
  rm -rf "$WORK_DIR"
else
  echo "[test-wehavephones-devices] no GpuProbeJsonSelfTest.java; skipped"
fi

if [[ $FAILURES -ne 0 ]]; then
  echo "[test-wehavephones-devices] FAIL ($FAILURES failing checks)"
  exit 1
fi
echo "[test-wehavephones-devices] PASS"
