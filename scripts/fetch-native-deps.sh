#!/usr/bin/env bash
# Populate the native gitlink dependency when the checkout was not recursive.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OPENXR_DIR="$ROOT_DIR/app/src/main/cpp/OpenXR-SDK"
OPENXR_REPO="${WINLATOR_OPENXR_REPO:-https://github.com/KhronosGroup/OpenXR-SDK.git}"
OPENXR_REV="288d3a7ebc1ad959f62d51da75baa3d27438c499"

if [[ -f "$OPENXR_DIR/CMakeLists.txt" ]]; then
  exit 0
fi

if [[ -e "$OPENXR_DIR" && -n "$(find "$OPENXR_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  echo "[fetch-native-deps] $OPENXR_DIR exists but is incomplete; refusing to replace it." >&2
  exit 1
fi

command -v git >/dev/null 2>&1 || {
  echo "[fetch-native-deps] git is required to obtain OpenXR-SDK." >&2
  exit 1
}

rm -rf "$OPENXR_DIR"
echo "[fetch-native-deps] Fetching OpenXR-SDK at $OPENXR_REV ..."
git clone --filter=blob:none --no-checkout "$OPENXR_REPO" "$OPENXR_DIR"
git -C "$OPENXR_DIR" checkout --detach "$OPENXR_REV"
git -C "$ROOT_DIR" submodule init -- "app/src/main/cpp/OpenXR-SDK"
test -f "$OPENXR_DIR/CMakeLists.txt"
