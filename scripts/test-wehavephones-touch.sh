#!/usr/bin/env bash
# Verify simultaneous touch input and reversible addon mode changes without a phone.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "$root/.tools/env.sh" ]]; then source "$root/.tools/env.sh"; fi
work="$(mktemp -d "${TMPDIR:-/tmp}/wehavephones-touch-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT
src="$root/app/src/wehavephones/java/com/wehavephones/mobile"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d "$work" \
  "$src/MobileTouchInput.java" "$src/addons/required/MobileTouchSetup.java" \
  "$root/tools/wehavephones-touch-selftest/TouchSelfTest.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$work" TouchSelfTest
python3 "$root/tools/wehavephones-touch-selftest/addon_selftest.py"
