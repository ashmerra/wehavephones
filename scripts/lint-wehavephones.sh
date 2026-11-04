#!/usr/bin/env bash
# Run the project's linters: ShellCheck, Ruff and Android Lint.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINT_BIN="${WEHAVEPHONES_LINT_TOOLS:-$ROOT_DIR/.tools/lint}/bin"

usage() {
  cat <<'USAGE'
Run the WeHavePhones linters.

Usage:
  scripts/lint-wehavephones.sh [--shell] [--python] [--android]

With no option every linter runs. Options pick a subset.

  --shell    ShellCheck on tracked *.sh files (warnings and errors).
  --python   Ruff on tracked Python files, configured by ruff.toml.
  --android  Android Lint on the wehavephones debug variant. Findings already
             listed in app/lint-baseline.xml are not reported again.

Vendored sources under app/src/main/cpp are skipped. Install the pinned
ShellCheck and Ruff with scripts/install-lint-tools.sh. Copies on PATH are
used when .tools/lint/bin has none.
USAGE
}

RUN_SHELL=0
RUN_PYTHON=0
RUN_ANDROID=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --shell) RUN_SHELL=1 ;;
    --python) RUN_PYTHON=1 ;;
    --android) RUN_ANDROID=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "[lint-wehavephones] Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
if (( RUN_SHELL + RUN_PYTHON + RUN_ANDROID == 0 )); then
  RUN_SHELL=1 RUN_PYTHON=1 RUN_ANDROID=1
fi

cd "$ROOT_DIR"

find_tool() {
  if [[ -x "$LINT_BIN/$1" ]]; then
    echo "$LINT_BIN/$1"
  elif command -v "$1" >/dev/null 2>&1; then
    command -v "$1"
  else
    echo "[lint-wehavephones] $1 not found. Run scripts/install-lint-tools.sh." >&2
    return 1
  fi
}

failed=()

if (( RUN_SHELL )); then
  echo "[lint-wehavephones] ShellCheck"
  shellcheck_bin="$(find_tool shellcheck)"
  mapfile -t shell_files < <(git ls-files '*.sh' ':!:app/src/main/cpp/**')
  if "$shellcheck_bin" --external-sources --severity=warning "${shell_files[@]}"; then
    echo "[lint-wehavephones] ShellCheck passed (${#shell_files[@]} files)."
  else
    failed+=(ShellCheck)
  fi
fi

if (( RUN_PYTHON )); then
  echo "[lint-wehavephones] Ruff"
  ruff_bin="$(find_tool ruff)"
  # ruff.toml excludes the vendored folders.
  if "$ruff_bin" check --no-cache .; then
    echo "[lint-wehavephones] Ruff passed."
  else
    failed+=(Ruff)
  fi
fi

if (( RUN_ANDROID )); then
  echo "[lint-wehavephones] Android Lint"
  if [[ -f "$ROOT_DIR/.tools/env.sh" ]]; then
    # shellcheck disable=SC1091
    source "$ROOT_DIR/.tools/env.sh"
  fi
  # AGP 7.4's bundled aapt2 cannot read the compileSdk 36 android.jar.
  SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$SDK_DIR" && -f local.properties ]]; then
    SDK_DIR="$(sed -n 's/^sdk\.dir=//p' local.properties)"
  fi
  AAPT2="${AAPT2:-$SDK_DIR/build-tools/36.0.0/aapt2}"
  if [[ ! -x "$AAPT2" ]]; then
    echo "[lint-wehavephones] aapt2 36.0.0 not found at $AAPT2. Run scripts/setup-dev.sh or set AAPT2." >&2
    failed+=("Android Lint")
  elif ./gradlew -q :app:lintWehavephonesDebug "-Pandroid.aapt2FromMavenOverride=$AAPT2"; then
    echo "[lint-wehavephones] Android Lint passed. Report: app/build/reports/lint-results-wehavephonesDebug.html"
  else
    echo "[lint-wehavephones] Report: app/build/reports/lint-results-wehavephonesDebug.html" >&2
    failed+=("Android Lint")
  fi
fi

if (( ${#failed[@]} )); then
  echo "[lint-wehavephones] Failed: ${failed[*]}" >&2
  exit 1
fi
echo "[lint-wehavephones] All linters passed."
