#!/usr/bin/env bash
# Render production flip-phone assets from the shared SVG without a browser.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 "$root/scripts/render-phone-brand.py" brand
