#!/usr/bin/env bash
# Real Unix-socket backpressure tests for queued X server replies and events.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  source "$ROOT_DIR/.tools/env.sh"
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JDK_DIR="$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wehavephones-xoutput-selftest.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
TEST_DIR="$ROOT_DIR/tools/wehavephones-runtime-selftest"
if [[ "$(uname -s)" != Linux ]]; then
  echo "X socket JNI tests require a Linux host" >&2
  exit 1
fi
"${CC:-cc}" -Wall -Wextra -Werror -fPIC -shared -Dsend=test_send \
  -I"$JDK_DIR/include" -I"$JDK_DIR/include/linux" \
  "$ROOT_DIR/app/src/main/cpp/wehavephones/xsocket_output.c" \
  "$TEST_DIR/xsocket_output_test.c" -o "$WORK_DIR/libwehavephones.so"
"$JAVAC" -encoding UTF-8 -d "$WORK_DIR" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XSocketOutput.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XOutputStream.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XStreamLock.java" \
  "$TEST_DIR/graphics-stubs/com/wehavephones/xserver/XServer.java" \
  "$TEST_DIR/graphics-stubs/com/wehavephones/xconnector/ClientSocket.java" \
  "$TEST_DIR/XSocketOutputSelfTest.java"
"$JAVA" -Djava.library.path="$WORK_DIR" -cp "$WORK_DIR" com.wehavephones.xconnector.XSocketOutputSelfTest
