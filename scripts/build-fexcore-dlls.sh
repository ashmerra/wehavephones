#!/usr/bin/env bash
set -euo pipefail

# Cross-compile FEX's matched Wine bridge DLLs with a host llvm-mingw toolchain.
# Sources and submodules must already be checked out at the desired revision.
# Generated binaries stay under out/. This helper never changes app assets.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FEX_SOURCE=""
FEX_TOOLCHAIN=""
FEX_OUTPUT="$ROOT_DIR/out/fexcore-cross"
FEX_VERSION="experimental"
FEX_JOBS="4"
FEX_NINJA="${NINJA_BIN:-}"

usage() {
    cat <<'EOF'
Build the WoW64 and ARM64EC FEX bridge DLLs without Docker.

Usage: scripts/build-fexcore-dlls.sh --source <FEX checkout> --toolchain <llvm-mingw>
       [--output <directory>] [--version <archive label>] [--jobs <count>] [--ninja <binary>]

Requires CMake, Ninja, zstd, Python 3, a host llvm-mingw toolchain with both
aarch64-w64-mingw32 and arm64ec-w64-mingw32 compilers, and initialized FEX
submodules. The source revision and output hashes are retained in build.json.
The archive label does not change runtime defaults or install anything.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --source) FEX_SOURCE="${2:?missing source}"; shift 2 ;;
        --toolchain) FEX_TOOLCHAIN="${2:?missing toolchain}"; shift 2 ;;
        --output) FEX_OUTPUT="${2:?missing output}"; shift 2 ;;
        --version) FEX_VERSION="${2:?missing version}"; shift 2 ;;
        --jobs) FEX_JOBS="${2:?missing job count}"; shift 2 ;;
        --ninja) FEX_NINJA="${2:?missing Ninja binary}"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ -z "$FEX_SOURCE" || -z "$FEX_TOOLCHAIN" ]]; then
    usage >&2
    exit 2
fi
if [[ ! "$FEX_VERSION" =~ ^[A-Za-z0-9._-]+$ || ! "$FEX_JOBS" =~ ^[1-9][0-9]*$ ]]; then
    echo "Invalid archive label or job count" >&2
    exit 2
fi
FEX_SOURCE="$(realpath "$FEX_SOURCE")"
FEX_TOOLCHAIN="$(realpath "$FEX_TOOLCHAIN")"
if [[ -z "$FEX_NINJA" ]]; then
    FEX_NINJA="$(command -v ninja || true)"
fi
if [[ -z "$FEX_NINJA" || ! -x "$FEX_NINJA" ]]; then
    echo "Ninja is required. Pass --ninja with the SDK's Ninja binary if needed." >&2
    exit 2
fi
FEX_NINJA="$(realpath "$FEX_NINJA")"
mkdir -p "$FEX_OUTPUT"
FEX_OUTPUT="$(realpath "$FEX_OUTPUT")"
if [[ ! -f "$FEX_SOURCE/Data/CMake/toolchain_mingw.cmake" ]]; then
    echo "FEX checkout lacks its MinGW toolchain file" >&2
    exit 2
fi
for triple in aarch64-w64-mingw32 arm64ec-w64-mingw32; do
    if [[ ! -x "$FEX_TOOLCHAIN/bin/$triple-clang++" ]]; then
        echo "Missing compiler: $FEX_TOOLCHAIN/bin/$triple-clang++" >&2
        exit 2
    fi
done
export PATH="$FEX_TOOLCHAIN/bin:$PATH"

build_one() {
    local name="$1" triple="$2" target="$3"
    local build_dir="$FEX_OUTPUT/build-$name"
    cmake -S "$FEX_SOURCE" -B "$build_dir" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$FEX_NINJA" \
        -DCMAKE_TOOLCHAIN_FILE="$FEX_SOURCE/Data/CMake/toolchain_mingw.cmake" \
        -DMINGW_TRIPLE="$triple" -DCMAKE_BUILD_TYPE=Release \
        -DTUNE_CPU=none -DENABLE_LTO=OFF -DENABLE_GDB_SYMBOLS=OFF \
        -DENABLE_OFFLINE_TELEMETRY=OFF -DBUILD_TESTING=OFF -DBUILD_FEXCONFIG=OFF
    cmake --build "$build_dir" --parallel "$FEX_JOBS" --target "$target"
    local dll="$build_dir/Bin/lib$target.dll"
    if [[ ! -f "$dll" ]]; then
        echo "Build did not produce $dll" >&2
        exit 1
    fi
    cp "$dll" "$FEX_OUTPUT/lib$target.dll"
}

build_one wow64 aarch64-w64-mingw32 wow64fex
build_one arm64ec arm64ec-w64-mingw32 arm64ecfex
tar -C "$FEX_OUTPUT" -cf - libwow64fex.dll libarm64ecfex.dll \
    | zstd -T2 -10 -f -o "$FEX_OUTPUT/fexcore-$FEX_VERSION.tzst"
python3 - "$FEX_SOURCE" "$FEX_TOOLCHAIN" "$FEX_OUTPUT" "$FEX_VERSION" <<'PY'
import hashlib
import json
from pathlib import Path
import subprocess
import sys

source, toolchain, output, label = sys.argv[1:]
artifacts = [Path(output) / name for name in
             ('libwow64fex.dll', 'libarm64ecfex.dll', 'fexcore-' + label + '.tzst')]
metadata = {
    'source_commit': subprocess.check_output(['git', '-C', source, 'rev-parse', 'HEAD'], text=True).strip(),
    'source_dirty': bool(subprocess.check_output(['git', '-C', source, 'status', '--porcelain'], text=True)),
    'submodules': subprocess.check_output(['git', '-C', source, 'submodule', 'status'], text=True).splitlines(),
    'compiler': subprocess.check_output([str(Path(toolchain) / 'bin/clang'), '--version'], text=True).splitlines()[0],
    'build_type': 'Release', 'lto': False, 'archive_label': label,
    'sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts},
}
(Path(output) / 'build.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps(metadata, indent=2))
PY
