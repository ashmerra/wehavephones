#!/usr/bin/env bash
# Reproduce an in-flight native audio write during release and re-prepare.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  source "$ROOT_DIR/.tools/env.sh"
fi
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JDK_DIR="$("$JAVA" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wehavephones-audio-selftest.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
TEST_DIR="$ROOT_DIR/tools/wehavephones-audio-selftest"
case "$(uname -s)" in
  Linux) JNI_PLATFORM=linux; LIB_NAME=libwehavephones.so; LINK_FLAG=-shared ;;
  Darwin) JNI_PLATFORM=darwin; LIB_NAME=libwehavephones.dylib; LINK_FLAG=-dynamiclib ;;
  *) echo "Unsupported JNI host platform" >&2; exit 1 ;;
esac
"${CC:-cc}" -Wall -Wextra -Werror -fPIC "$LINK_FLAG" \
  -I"$JDK_DIR/include" -I"$JDK_DIR/include/$JNI_PLATFORM" \
  "$TEST_DIR/alsa_jni_stub.c" -o "$WORK_DIR/$LIB_NAME"
"$JAVAC" -encoding UTF-8 -d "$WORK_DIR" \
  "$ROOT_DIR/app/src/main/java/com/wehavephones/alsaserver/ALSAClient.java" \
  "$TEST_DIR/stubs/com/wehavephones/sysvshm/SysVSharedMemory.java" \
  "$TEST_DIR/ALSAClientSelfTest.java"
"$JAVA" -Djava.library.path="$WORK_DIR" -cp "$WORK_DIR" com.wehavephones.alsaserver.ALSAClientSelfTest
