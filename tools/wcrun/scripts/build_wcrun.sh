#!/usr/bin/env bash
# Builds wcrun.exe (32-bit, GUI subsystem) and copies it to the wehavephones assets.
# Uses MINGW_CC when set, otherwise Zig or mingw in Docker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${PROJ_DIR}/../.." && pwd)"
OUT_DIR="${PROJ_DIR}/out"
OUT_EXE="${OUT_DIR}/wcrun.exe"
ASSET="${REPO_DIR}/app/src/wehavephones/assets/wehavephones/wcrun.exe"

mkdir -p "${OUT_DIR}"

ZIG="${ZIG:-$(command -v zig || true)}"
if [[ -n "${MINGW_CC:-}" ]]; then
  "${MINGW_CC}" -O2 -s -mwindows -municode \
    -o "${OUT_EXE}" "${PROJ_DIR}/src/wcrun.c"
elif [[ -n "${ZIG}" ]]; then
  "${ZIG}" cc -target x86-windows-gnu -O2 -s -municode -Wl,--subsystem,windows \
    -o "${OUT_EXE}" "${PROJ_DIR}/src/wcrun.c"
else
  IMAGE="wehavephones-mingw:20.04-v3"
  if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
    cat <<'DOCKERFILE' | docker build -t "${IMAGE}" -
FROM ubuntu:20.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
  && apt-get install -y --no-install-recommends --fix-missing -o Acquire::Retries=5 \
     gcc-mingw-w64-i686 \
     make \
  && rm -rf /var/lib/apt/lists/*
DOCKERFILE
  fi
  docker run --rm \
    -v "${PROJ_DIR}:/src" \
    "${IMAGE}" \
    bash -lc "set -euo pipefail; \
      i686-w64-mingw32-gcc -O2 -s -mwindows -municode -o /src/out/wcrun.exe /src/src/wcrun.c"
fi

cp "${OUT_EXE}" "${ASSET}"
echo "Built: ${OUT_EXE} -> ${ASSET}"
