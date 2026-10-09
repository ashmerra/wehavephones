#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.tools/env.sh"
fi
# Machine-local settings from local.env (ANDROID_DEVICE, WEHAVEPHONES_PACKAGE, ...).
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/local-env.sh"

android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
# An explicit ADB_BIN is used as given. Otherwise prefer the SDK's adb and fall
# back to the one on PATH when the SDK has none.
if [[ -z "${ADB_BIN:-}" ]]; then
  if [[ -x "$android_sdk/platform-tools/adb" ]]; then
    ADB_BIN="$android_sdk/platform-tools/adb"
  else
    ADB_BIN="adb"
  fi
fi
ADB_SERIAL="${ADB_SERIAL:-${ANDROID_DEVICE:-}}"

RELEASE=0
RELEASE_PKG="com.wehavephones"
DEBUG_PKG="${WEHAVEPHONES_PACKAGE:-com.wehavephones.dev}"

usage() {
  cat <<USAGE
Build, install, and launch WeHavePhones on a phone over adb.

By default this is the debug build, which installs as com.wehavephones.dev
("WeHavePhones Dev"). With --release it is the signed release build, which
installs as com.wehavephones ("WeHavePhones"). They are separate apps with
separate data and can be installed side by side.

The script updates an installed copy in place (install -r), which keeps its
data. It never uninstalls an app and never clears data. A signing mismatch
reported by adb means the installed copy was signed with another key, so it
cannot be updated in place. The app is recorded as its own installer, so it
can mark itself as a game, which Samsung's Game Booster requires.

Usage:
  scripts/deploy-wehavephones.sh [--release] [--serial <id>]

Options:
  --release        Build with scripts/build-wehavephones.sh --release (signed
                   with the key from ~/.config/wehavephones/signing.env) and
                   install out/release/WeHavePhones-<version>.apk.
  --serial <id>    adb device serial to target (adb -s <id>). Defaults to
                   \$ADB_SERIAL, then \$ANDROID_DEVICE (environment or local.env;
                   now: ${ADB_SERIAL:-not set}), then the only device adb lists.

WEHAVEPHONES_PACKAGE overrides the package of the debug build (now: $DEBUG_PKG).
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release)
      RELEASE=1
      shift
      ;;
    --serial)
      ADB_SERIAL="${2:-}"
      if [[ -z "$ADB_SERIAL" ]]; then
        echo "[deploy-wehavephones] --serial requires a value" >&2
        exit 2
      fi
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$RELEASE" -eq 1 ]]; then
  PKG="$RELEASE_PKG"
else
  PKG="$DEBUG_PKG"
fi
ACTIVITY="$PKG/com.wehavephones.mobile.WeHavePhonesActivity"

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  echo "[deploy-wehavephones] adb not found ($ADB_BIN); install platform-tools, put adb on PATH or set ADB_BIN" >&2
  exit 1
fi

if [[ -z "$ADB_SERIAL" ]]; then
  ADB_SERIAL="$(single_adb_device "$ADB_BIN" --serial)" || exit 1
fi

if [[ "$RELEASE" -eq 1 ]]; then
  "$ROOT_DIR/scripts/build-wehavephones.sh" --release
  # The build script stages the signed APK in out/release/ and lists it, by bare
  # file name, in SHA256SUMS. Install exactly that file, after checking it.
  release_dir="$ROOT_DIR/out/release"
  apk_name="$(awk 'NR == 1 { print $2 }' "$release_dir/SHA256SUMS")"
  if [[ ! "$apk_name" =~ ^WeHavePhones-[0-9A-Za-z][0-9A-Za-z._+-]*\.apk$ ]]; then
    echo "[deploy-wehavephones] No release APK is listed in $release_dir/SHA256SUMS" >&2
    exit 1
  fi
  APK_PATH="$release_dir/$apk_name"
  if ! (cd "$release_dir" && sha256sum --check --quiet SHA256SUMS); then
    echo "[deploy-wehavephones] $APK_PATH does not match $release_dir/SHA256SUMS" >&2
    exit 1
  fi
else
  "$ROOT_DIR/scripts/build-wehavephones.sh"
  APK_PATH="$ROOT_DIR/app/build/outputs/apk/wehavephones/debug/app-wehavephones-debug.apk"
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "[deploy-wehavephones] APK not found at $APK_PATH" >&2
  exit 1
fi

adb_prefix=("$ADB_BIN")
if [[ -n "$ADB_SERIAL" ]]; then
  adb_prefix+=("-s" "$ADB_SERIAL")
fi

echo "[deploy-wehavephones] Installing $APK_PATH as $PKG ..."
# Being its own installer of record lets the app call setApplicationCategoryHint (see
# GameCategoryHint); Samsung's game manager ignores the manifest's appCategory.
if ! install_output="$("${adb_prefix[@]}" install -r -i "$PKG" "$APK_PATH" 2>&1)"; then
  printf '%s\n' "$install_output" >&2
  if printf '%s' "$install_output" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    if [[ "$RELEASE" -eq 1 ]]; then
      signing_hint="The release build is signed with the key from ~/.config/wehavephones/signing.env."
    else
      signing_hint="The debug build is signed with your local debug key unless WEHAVEPHONES_KEYSTORE names another."
    fi
    cat >&2 <<MSG
[deploy-wehavephones] The installed $PKG was signed with a different key than this APK.
[deploy-wehavephones] $signing_hint
[deploy-wehavephones] install -r only updates an app signed with the same key, and uninstalling it
[deploy-wehavephones] would delete its data (imagefs, containers and game files), so this script never does.
MSG
  fi
  exit 1
fi
printf '%s\n' "$install_output"

"${adb_prefix[@]}" shell am start -W -n "$ACTIVITY"

echo "[deploy-wehavephones] Installed and launched"
