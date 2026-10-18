#!/usr/bin/env bash
# Builds openurl.exe into app/src/main/assets/winhandler/ (needs gcc-mingw-w64-i686).
set -euo pipefail
PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${PROJ_DIR}/../../app/src/main/assets/winhandler/openurl.exe"
i686-w64-mingw32-gcc -O2 -s -mwindows -Wall -Wextra -o "${OUT}" "${PROJ_DIR}/src/openurl.c" -lws2_32 -lshell32
echo "Built: ${OUT}"
