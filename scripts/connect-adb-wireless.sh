#!/usr/bin/env bash
# Connect the project-local adb to Android Wireless Debugging.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.tools/env.sh"
fi
# Machine-local settings from local.env (ANDROID_DEVICE, ANDROID_TAILSCALE_IP, ...).
# shellcheck disable=SC1091
source "$ROOT_DIR/scripts/local-env.sh"

android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb_bin="${ADB_BIN:-$android_sdk/platform-tools/adb}"
if [[ ! -x "$adb_bin" ]]; then
  adb_bin="${ADB_BIN:-adb}"
fi
default_endpoint="${ANDROID_DEVICE:-}"
if [[ -z "$default_endpoint" && -n "${ANDROID_TAILSCALE_IP:-}" ]]; then
  default_endpoint="$ANDROID_TAILSCALE_IP:5555"
fi

usage() {
  cat <<EOF
Usage: scripts/connect-adb-wireless.sh [host:port]

Without an argument it connects to \$ANDROID_DEVICE, or to the fixed port at
\$ANDROID_TAILSCALE_IP:5555 when only the address is set. Set them in the
environment or in local.env. Currently: ${default_endpoint:-neither is set, so pass host:port}.

Pass an explicit host:port for the current Wireless debugging endpoint shown in
Android Settings > Developer options > Wireless debugging; that port changes
whenever Wireless debugging is restarted.
EOF
}

endpoint="${1:-${WINLATOR_ADB_ENDPOINT:-$default_endpoint}}"
[[ -n "$endpoint" ]] || { usage >&2; exit 2; }
command -v "$adb_bin" >/dev/null 2>&1 || {
  echo "adb is unavailable; run scripts/setup-dev.sh first." >&2
  exit 1
}

"$adb_bin" start-server
if ! connect_output="$("$adb_bin" connect "$endpoint" 2>&1)"; then
  printf '%s\n' "$connect_output" >&2
  cat >&2 <<EOF
[connect-adb-wireless] Could not connect to $endpoint.
Confirm that Wireless debugging is enabled and reconnect with the current
host:port shown on the phone, or re-pair with:
  scripts/pair-android <host:pairing-port> <host:connect-port>
EOF
  exit 1
fi
printf '%s\n' "$connect_output"
if ! state="$("$adb_bin" -s "$endpoint" get-state 2>&1)" || [[ "$state" != "device" ]]; then
  cat >&2 <<EOF
[connect-adb-wireless] Could not connect to $endpoint.
Confirm that Wireless debugging is enabled and reconnect with the current
host:port shown on the phone, or re-pair with:
  scripts/pair-android <host:pairing-port> <host:connect-port>
EOF
  exit 1
fi
printf '%s\n' "$state"
"$adb_bin" devices -l
