#!/usr/bin/env bash
# Report whether every local iteration prerequisite is usable.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
status=0
check() {
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf '[ok] %s\n' "$label"
  else
    printf '[missing] %s\n' "$label" >&2
    status=1
  fi
}

if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.tools/env.sh"
fi

check 'JDK 17' test -x "${JAVA_HOME:-}/bin/java"
check 'Android SDK platform android-36' test -d "${ANDROID_SDK_ROOT:-}/platforms/android-36"
check 'Android build-tools 36.0.0 aapt2' test -x "${ANDROID_SDK_ROOT:-}/build-tools/36.0.0/aapt2"
check 'Android NDK 22.1.7171670' test -x "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
check 'adb platform-tools' command -v adb
check 'Docker daemon' docker info

if ! command -v docker >/dev/null 2>&1; then
  cat >&2 <<'EOF'
[hint] Docker is not installed. In WSL, enable this distro under Docker Desktop
       Settings > Resources > WSL Integration, then reopen the terminal.
       Alternatively install and start Docker Engine with administrator access.
EOF
elif getent group docker >/dev/null 2>&1 && getent group docker | cut -d: -f4 | tr ',' '\n' | grep -qx "${USER:-}" && ! id -nG | tr ' ' '\n' | grep -qx docker; then
  cat >&2 <<'EOF'
[hint] This user is in the docker group, but this shell predates that change.
       Run `newgrp docker` or open a new terminal, then run this check again.
EOF
fi

exit "$status"
