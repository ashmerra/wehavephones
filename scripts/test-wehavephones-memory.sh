#!/usr/bin/env bash
# Host tests for the WeHavePhones memory trims and game resolution.
#
#   1. Compiles the pure memory logic (MemoryTransforms/MemoryReport)
#      plus tools/wehavephones-memory-selftest and runs MemoryLogicSelfTest.
#   2. Runs the profiler's own self-test (no adb).
#   3. Checks how the device scripts pick the phone through scripts/local-env.sh
#      (local.env, the environment, the only attached device, and the guidance
#      when there is none or several) against the fake adb shim in
#      tools/wehavephones-memory-selftest.
#
# Exits non-zero when any check fails. Prints the exact output of each step.
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
  echo "[test-wehavephones-memory] No JDK found. Run scripts/setup-dev.sh or set JAVA_HOME." >&2
  exit 1
fi

PYTHON="${PYTHON:-python3}"
WORK_DIR="${TMPDIR:-/tmp}/wehavephones-memory-selftest-$$"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"
FAILURES=0

fail() {
  echo "[test-wehavephones-memory] FAIL: $*" >&2
  FAILURES=$((FAILURES + 1))
}

echo "[test-wehavephones-memory] javac: $JAVAC"
echo "[test-wehavephones-memory] work dir: $WORK_DIR"

SOURCES=(
  "$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile/MemoryTransforms.java"
  "$ROOT_DIR/app/src/wehavephones/java/com/wehavephones/mobile/MemoryReport.java"
  "$ROOT_DIR/tools/wehavephones-memory-selftest/MemoryLogicSelfTest.java"
)

echo
echo "[test-wehavephones-memory] == 1. compile pure logic =="
"$JAVAC" -encoding UTF-8 -d "$WORK_DIR" "${SOURCES[@]}"

echo
echo "[test-wehavephones-memory] == 2. MemoryLogicSelfTest =="
set +e
"$JAVA" -cp "$WORK_DIR" MemoryLogicSelfTest | tee "$WORK_DIR/selftest.log"
STATUS=${PIPESTATUS[0]}
set -e
if [[ $STATUS -ne 0 ]]; then
  fail "MemoryLogicSelfTest exited $STATUS"
fi

echo
echo "[test-wehavephones-memory] == 3. profile-wehavephones-memory.py --self-test =="
set +e
"$PYTHON" "$ROOT_DIR/scripts/profile-wehavephones-memory.py" --self-test | tee "$WORK_DIR/profiler-selftest.log"
STATUS=${PIPESTATUS[0]}
set -e
if [[ $STATUS -ne 0 ]]; then
  fail "profiler self-test exited $STATUS"
fi

echo
echo "[test-wehavephones-memory] == 4. phone selection (scripts/local-env.sh) =="
assert_contains() {
  local file="$1" pattern="$2" label="$3"
  if ! grep -qF -- "$pattern" "$file"; then
    fail "$label (missing: $pattern)"
  else
    echo "  PASS $label"
  fi
}

FAKE_ADB="$ROOT_DIR/tools/wehavephones-memory-selftest/fake-adb"
chmod +x "$FAKE_ADB"
FAKE_LOG="$WORK_DIR/fake-adb.log"
: > "$FAKE_LOG"
# RFC 5737 documentation addresses; WEHAVEPHONES_LOCAL_ENV keeps the machine's
# real local.env out of the test.
LOCAL_ENV="$WORK_DIR/local.env"
printf '# test settings\nANDROID_DEVICE=192.0.2.10:5555\n' > "$LOCAL_ENV"

# Source scripts/local-env.sh the way the device scripts do, then run a snippet.
# Settings that are not passed explicitly are removed from the environment.
local_env() {
  env -u ADB_SERIAL -u ANDROID_DEVICE FAKE_ADB_LOG="$FAKE_LOG" "$@" \
    bash -c 'source "$1/scripts/local-env.sh"; shift; eval "$1"' local-env "$ROOT_DIR" "$SNIPPET" 2>&1
}

SNIPPET='echo "ANDROID_DEVICE=${ANDROID_DEVICE:-<unset>}"'
local_env WEHAVEPHONES_LOCAL_ENV="$LOCAL_ENV" > "$WORK_DIR/select-local.log" || fail "reading local.env exited non-zero"
assert_contains "$WORK_DIR/select-local.log" "ANDROID_DEVICE=192.0.2.10:5555" "ANDROID_DEVICE comes from local.env"

local_env WEHAVEPHONES_LOCAL_ENV="$LOCAL_ENV" ANDROID_DEVICE=198.51.100.9:5555 \
  > "$WORK_DIR/select-env.log" || fail "reading the environment exited non-zero"
assert_contains "$WORK_DIR/select-env.log" "ANDROID_DEVICE=198.51.100.9:5555" "environment beats local.env"

SNIPPET='single_adb_device "$FAKE_ADB" --device'
export FAKE_ADB
local_env WEHAVEPHONES_LOCAL_ENV=/dev/null FAKE_ADB_DEVICES="203.0.113.5:5555" \
  > "$WORK_DIR/select-single.log" || fail "selection of the only device exited non-zero"
assert_contains "$WORK_DIR/select-single.log" "203.0.113.5:5555" "the only attached device is used"
assert_contains "$FAKE_LOG" "devices" "the device list is read through adb"

set +e
local_env WEHAVEPHONES_LOCAL_ENV=/dev/null > "$WORK_DIR/select-none.log"
STATUS=$?
set -e
[[ $STATUS -eq 1 ]] || fail "nothing configured and nothing attached exited $STATUS, expected 1"
assert_contains "$WORK_DIR/select-none.log" "set ANDROID_DEVICE in local.env or pass --device" "no device: guidance names local.env and --device"

set +e
local_env WEHAVEPHONES_LOCAL_ENV=/dev/null FAKE_ADB_DEVICES="203.0.113.5:5555 203.0.113.6:5555" \
  > "$WORK_DIR/select-several.log"
STATUS=$?
set -e
[[ $STATUS -eq 1 ]] || fail "nothing configured with two attached exited $STATUS, expected 1"
assert_contains "$WORK_DIR/select-several.log" "203.0.113.5:5555, 203.0.113.6:5555" "several devices: both are listed"

echo
echo "[test-wehavephones-memory] fake adb invocations:"
cat "$FAKE_LOG"

rm -rf "$WORK_DIR"

if [[ $FAILURES -ne 0 ]]; then
  echo "[test-wehavephones-memory] FAIL ($FAILURES failing checks)"
  exit 1
fi
echo "[test-wehavephones-memory] PASS"
