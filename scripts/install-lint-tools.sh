#!/usr/bin/env bash
# Download the pinned ShellCheck and Ruff binaries into .tools/lint/bin.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINT_DIR="${WEHAVEPHONES_LINT_TOOLS:-$ROOT_DIR/.tools/lint}"
BIN_DIR="$LINT_DIR/bin"

SHELLCHECK_VERSION=0.11.0
RUFF_VERSION=0.17.1

case "$(uname -m)" in
  x86_64|amd64)
    SHELLCHECK_ARCH=x86_64
    SHELLCHECK_SHA256=8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198
    RUFF_TRIPLE=x86_64-unknown-linux-gnu
    RUFF_SHA256=a293ac937715ae6fb6b64da86761598379e6149f40fbc00fde9ba8a2a562ac65
    ;;
  aarch64|arm64)
    SHELLCHECK_ARCH=aarch64
    SHELLCHECK_SHA256=12b331c1d2db6b9eb13cfca64306b1b157a86eb69db83023e261eaa7e7c14588
    RUFF_TRIPLE=aarch64-unknown-linux-gnu
    RUFF_SHA256=d3ead1f0ed779e777a034ccd9286be6bd9bd3cdaba732ff9797f8288a283e9e2
    ;;
  *)
    echo "[install-lint-tools] Unsupported architecture $(uname -m). Install shellcheck and ruff yourself." >&2
    exit 1
    ;;
esac
if [[ "$(uname -s)" != Linux ]]; then
  echo "[install-lint-tools] Only Linux is supported. Install shellcheck $SHELLCHECK_VERSION and ruff $RUFF_VERSION yourself." >&2
  exit 1
fi

for cmd in curl tar sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "[install-lint-tools] Missing $cmd." >&2; exit 1; }
done

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "$BIN_DIR"

fetch() {
  local url="$1" sha="$2" out="$3"
  curl --fail --location --retry 3 --silent --show-error --output "$out" "$url"
  if ! echo "$sha  $out" | sha256sum --check --status; then
    echo "[install-lint-tools] Checksum mismatch for $url" >&2
    exit 1
  fi
}

installed_version() {
  [[ -x "$BIN_DIR/$1" ]] && "$BIN_DIR/$1" --version 2>/dev/null | grep -Eo '[0-9]+\.[0-9]+\.[0-9]+' | head -n 1
}

if [[ "$(installed_version shellcheck || true)" == "$SHELLCHECK_VERSION" ]]; then
  echo "[install-lint-tools] shellcheck $SHELLCHECK_VERSION already installed."
else
  name="shellcheck-v$SHELLCHECK_VERSION.linux.$SHELLCHECK_ARCH.tar.xz"
  fetch "https://github.com/koalaman/shellcheck/releases/download/v$SHELLCHECK_VERSION/$name" "$SHELLCHECK_SHA256" "$WORK_DIR/$name"
  tar -xJf "$WORK_DIR/$name" -C "$WORK_DIR"
  install -m 0755 "$WORK_DIR/shellcheck-v$SHELLCHECK_VERSION/shellcheck" "$BIN_DIR/shellcheck"
  echo "[install-lint-tools] Installed shellcheck $SHELLCHECK_VERSION."
fi

if [[ "$(installed_version ruff || true)" == "$RUFF_VERSION" ]]; then
  echo "[install-lint-tools] ruff $RUFF_VERSION already installed."
else
  name="ruff-$RUFF_TRIPLE.tar.gz"
  fetch "https://github.com/astral-sh/ruff/releases/download/$RUFF_VERSION/$name" "$RUFF_SHA256" "$WORK_DIR/$name"
  tar -xzf "$WORK_DIR/$name" -C "$WORK_DIR"
  install -m 0755 "$WORK_DIR/ruff-$RUFF_TRIPLE/ruff" "$BIN_DIR/ruff"
  echo "[install-lint-tools] Installed ruff $RUFF_VERSION."
fi
