#!/usr/bin/env bash
# Download the ConsolePort release pinned in the WeHavePhones asset manifest into
# app/src/wehavephones/assets/wehavephones/addons/ConsolePort.zip (bundled in the APK and
# installed into every non-Forever WoW install; see docs/WEHAVEPHONES_ADDONS.md §9).
#
# Usage:
#   scripts/fetch-consoleport.sh           # fetch/verify the pinned version
#   scripts/fetch-consoleport.sh --latest  # bump the manifest to the latest GitHub release, then fetch
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_DIR="$ROOT_DIR/app/src/wehavephones/assets/wehavephones/addons"
MANIFEST="$ASSET_DIR/consoleport.json"
ZIP="$ASSET_DIR/ConsolePort.zip"
REPO="seblindfors/ConsolePort"

log() { echo "[fetch-consoleport] $*"; }

manifest_field() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$MANIFEST" "$1"
}

if [[ "${1:-}" == "--latest" ]]; then
  log "Querying the latest $REPO release ..."
  release_json="$(curl -fsSL -H 'Accept: application/vnd.github+json' \
    ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
    "https://api.github.com/repos/$REPO/releases/latest")"
  read -r version url < <(python3 -c '
import json, sys
release = json.loads(sys.stdin.read())
tag = release["tag_name"].lstrip("vV")
assets = [a for a in release["assets"] if a["name"].lower().endswith(".zip")
          and "nolib" not in a["name"].lower() and "source" not in a["name"].lower()]
if not assets:
    sys.exit("no zip asset in release " + tag)
print(tag, assets[0]["browser_download_url"])
' <<<"$release_json")
  if [[ "$version" == "$(manifest_field version)" ]]; then
    log "Manifest already pins the latest release ($version)."
  else
    tmp="$(mktemp)"
    curl -fL --retry 3 -o "$tmp" "$url"
    sha="$(sha256sum "$tmp" | awk '{print $1}')"
    mv "$tmp" "$ZIP"
    python3 - "$MANIFEST" "$version" "$url" "$sha" <<'PY'
import json, sys
path, version, url, sha = sys.argv[1:]
data = json.load(open(path))
data.update(version=version, url=url, sha256=sha)
with open(path, "w") as handle:
    json.dump(data, handle, indent=2)
    handle.write("\n")
PY
    log "Manifest bumped to $version ($sha)."
  fi
fi

version="$(manifest_field version)"
url="$(manifest_field url)"
sha="$(manifest_field sha256)"

if [[ -f "$ZIP" ]] && [[ "$(sha256sum "$ZIP" | awk '{print $1}')" == "$sha" ]]; then
  log "ConsolePort $version present and verified."
  exit 0
fi

log "Downloading ConsolePort $version ..."
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
curl -fL --retry 3 -o "$tmp" "$url"
actual="$(sha256sum "$tmp" | awk '{print $1}')"
if [[ "$actual" != "$sha" ]]; then
  echo "[fetch-consoleport] SHA-256 mismatch for $url: expected $sha, got $actual" >&2
  exit 1
fi
mv "$tmp" "$ZIP"
trap - EXIT
log "ConsolePort $version saved to ${ZIP#$ROOT_DIR/}."
