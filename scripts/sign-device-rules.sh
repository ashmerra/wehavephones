#!/usr/bin/env bash
# Validates app/src/wehavephones/assets/wehavephones/device-rules.json and writes
# device-rules.json.sig (base64 DER ECDSA P-256 / SHA-256 over the exact file bytes).
#
# Bump "rulesVersion" whenever the content changes; the app only takes a remote copy with a
# higher version. Commit and push the .json and .sig together (the app downloads both from
# raw.githubusercontent.com on main).
#
#   scripts/sign-device-rules.sh
#   WEHAVEPHONES_RULES_KEY=/path/key.pem scripts/sign-device-rules.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULES_REL="app/src/wehavephones/assets/wehavephones/device-rules.json"
RULES="$ROOT_DIR/$RULES_REL"
SIGNATURE="$RULES.sig"
KEY="${WEHAVEPHONES_RULES_KEY:-$HOME/.config/wehavephones/device-rules-signing-key.pem}"
CHECKER="$ROOT_DIR/tools/wehavephones-devices-selftest/device_rules_selftest.py"
PYTHON="${PYTHON:-python3}"

die() {
  echo "[sign-device-rules] $*" >&2
  exit 1
}

[[ -f "$RULES" ]] || die "missing $RULES_REL"
[[ -f "$KEY" ]] || die "signing key not found: $KEY (create it with scripts/generate-device-rules-key.sh)"

"$PYTHON" -I "$CHECKER" --validate || die "device-rules.json is invalid"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# A changed file must carry a higher rulesVersion than the committed one.
if git -C "$ROOT_DIR" show "HEAD:$RULES_REL" > "$work/head.json" 2>/dev/null && ! cmp -s "$work/head.json" "$RULES"; then
  head_version="$("$PYTHON" -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["rulesVersion"])' "$work/head.json")"
  new_version="$("$PYTHON" -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["rulesVersion"])' "$RULES")"
  if (( new_version <= head_version )); then
    die "rulesVersion is $new_version but HEAD has $head_version; bump it before signing"
  fi
fi

# The signing key must belong to the public key embedded in the app.
embedded="$("$PYTHON" -I "$CHECKER" --print-embedded-key)"
derived="$(openssl ec -in "$KEY" -pubout -outform DER 2>/dev/null | base64 -w0)"
[[ "$embedded" == "$derived" ]] || die "$KEY does not match the public key in DeviceRulesKey.java"

openssl dgst -sha256 -sign "$KEY" -out "$work/sig.der" "$RULES"
base64 -w0 "$work/sig.der" > "$SIGNATURE"
echo >> "$SIGNATURE"

"$PYTHON" -I "$CHECKER" >/dev/null || die "signature check failed after signing"
echo "[sign-device-rules] signed $RULES_REL (rulesVersion $("$PYTHON" -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["rulesVersion"])' "$RULES"))"
