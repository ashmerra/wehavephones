#!/usr/bin/env bash
# Host self-test for the pinned large build assets (scripts/large-assets.py and
# scripts/large-assets.json): fetch order, archive members, sha256 checks,
# release staging and manifest structure. No network, no device.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$ROOT_DIR/tools/large-assets-selftest/large_assets_selftest.py"
