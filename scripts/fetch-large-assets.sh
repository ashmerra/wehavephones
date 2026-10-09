#!/usr/bin/env bash
# Fetch and verify the large prebuilt assets pinned in scripts/large-assets.json.
#
#   scripts/fetch-large-assets.sh [--replace] [--release-only] [GROUP ...]
#
# GROUP: apk (default, both APK flavors), runtime (runtime flavor extras) or all.
# See docs/BUILD_ASSETS.md.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$ROOT_DIR/scripts/large-assets.py" fetch "$@"
