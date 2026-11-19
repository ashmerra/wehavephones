#!/usr/bin/env bash
# Builds agentpipe.exe (32-bit, GUI subsystem) into the wehavephones assets.
# Uses MINGW_CC when set, otherwise mingw in Docker (same image as wcrun).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="$(cd "${PROJ_DIR}/../.." && pwd)"
OUT_DIR="${PROJ_DIR}/out"
OUT_EXE="${OUT_DIR}/agentpipe.exe"
ASSET="${REPO_DIR}/app/src/wehavephones/assets/wehavephones/agentpipe.exe"
FLAGS="-O2 -s -mwindows -Wall -Wextra -Wl,--no-insert-timestamp"

mkdir -p "${OUT_DIR}"

if [[ -n "${MINGW_CC:-}" ]]; then
  # shellcheck disable=SC2086
  "${MINGW_CC}" ${FLAGS} -o "${OUT_EXE}" "${PROJ_DIR}/src/agentpipe.c" -lws2_32
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
  docker run --rm -v "${PROJ_DIR}:/src" "${IMAGE}" \
    bash -lc "set -euo pipefail; i686-w64-mingw32-gcc ${FLAGS} -o /src/out/agentpipe.exe /src/src/agentpipe.c -lws2_32"
fi

cp "${OUT_EXE}" "${ASSET}"
echo "Built: ${OUT_EXE} -> ${ASSET}"
