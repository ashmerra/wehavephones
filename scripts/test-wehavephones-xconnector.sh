#!/usr/bin/env bash
# Exercise the actual native connector with overlapping epoll dispatches.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$(uname -s)" != Linux ]]; then
  echo "X connector JNI tests require a Linux host" >&2
  exit 1
fi
if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.tools/env.sh"
fi
CONNECTOR_JDK="${JAVA_HOME:-}"
if [[ -z "$CONNECTOR_JDK" ]]; then
  CONNECTOR_JDK="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
if [[ ! -f "$CONNECTOR_JDK/include/jni.h" || ! -x "$CONNECTOR_JDK/bin/javac" ]]; then
  echo "A JDK with JNI headers is required. Set JAVA_HOME or run scripts/setup-dev.sh." >&2
  exit 1
fi
SELF_TEST_DIR="$ROOT_DIR/tools/wehavephones-runtime-selftest"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wehavephones-xconnector.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

"${CC:-cc}" -Wall -Wextra -Werror -Wno-unused-parameter -pthread \
  -I "$CONNECTOR_JDK/include" -I "$CONNECTOR_JDK/include/linux" \
  -I "$SELF_TEST_DIR/native-stubs" \
  "$SELF_TEST_DIR/XConnectorIsolationSelfTest.c" -o "$WORK_DIR/xconnector-test"
"$WORK_DIR/xconnector-test"

"${CC:-cc}" -Wall -Wextra -Werror -Wno-unused-parameter -fPIC -shared \
  -I "$CONNECTOR_JDK/include" -I "$CONNECTOR_JDK/include/linux" \
  -I "$SELF_TEST_DIR/native-stubs" \
  "$ROOT_DIR/app/src/main/cpp/wehavephones/xconnector_epoll.c" \
  -o "$WORK_DIR/libwehavephones.so"
"$CONNECTOR_JDK/bin/javac" -encoding UTF-8 -d "$WORK_DIR" \
  "$SELF_TEST_DIR/connector-stubs/androidx/annotation/Keep.java" \
  "$SELF_TEST_DIR/connector-stubs/com/wehavephones/xconnector/UnixSocketConfig.java" \
  "$SELF_TEST_DIR/connector-stubs/android/util/Log.java" \
  "$SELF_TEST_DIR/graphics-stubs/com/wehavephones/xserver/XServer.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/ConnectionHandler.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/RequestHandler.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/Client.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/ClientSocket.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XInputStream.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XOutputStream.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XSocketOutput.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XStreamLock.java" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/xconnector/XConnectorEpoll.java" \
  "$SELF_TEST_DIR/XConnectorLifecycleSelfTest.java"
timeout 20s "$CONNECTOR_JDK/bin/java" -Djava.library.path="$WORK_DIR" -cp "$WORK_DIR" \
  com.wehavephones.xconnector.XConnectorLifecycleSelfTest "$WORK_DIR"
