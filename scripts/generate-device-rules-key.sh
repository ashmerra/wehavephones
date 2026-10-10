#!/usr/bin/env bash
# Creates the ECDSA P-256 key that signs device-rules.json and prints only the
# public key (base64 X.509 SubjectPublicKeyInfo) to embed in DeviceRulesKey.java.
# The private key stays outside the repository and is never printed.
#
#   scripts/generate-device-rules-key.sh
#   WEHAVEPHONES_RULES_KEY=/path/key.pem scripts/generate-device-rules-key.sh
set -euo pipefail

KEY="${WEHAVEPHONES_RULES_KEY:-$HOME/.config/wehavephones/device-rules-signing-key.pem}"

if [[ -e "$KEY" ]]; then
  echo "[generate-device-rules-key] refusing to overwrite existing key: $KEY" >&2
  exit 1
fi

umask 077
mkdir -p "$(dirname "$KEY")"
openssl ecparam -name prime256v1 -genkey -noout -out "$KEY" 2>/dev/null
chmod 600 "$KEY"
echo "[generate-device-rules-key] private key written to $KEY (keep it, back it up, never commit it)" >&2
echo "[generate-device-rules-key] public key (base64 X.509 SubjectPublicKeyInfo):" >&2
openssl ec -in "$KEY" -pubout -outform DER 2>/dev/null | base64 -w0
echo
