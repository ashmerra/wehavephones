#!/usr/bin/env bash
set -euo pipefail

# Build the default Adreno 6xx/7xx Turnip: WinNative's WN-Turnip 1.17 "Balanced"
# recipe on Mesa 1b51639c20 (the same build as turnip26.3.0-devel-1b51639c20),
# plus patches/mesa/turnip-pvtmem-min.patch. Everything is pinned, and the
# compiler runs in Docker with Android NDK r26d, as WinNative's release did.
#
# Outputs, under out/turnip-pvtmem-build/:
# - vulkan.ad07xx.so and meta.json
# - adrenotools-<driver id>.tzst
#
# Usage:
#   scripts/build-turnip-pvtmem-docker.sh
#   scripts/build-turnip-pvtmem-docker.sh --update-assets   # also copy the .tzst into the APK assets

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

DRIVER_ID="turnip26.3.0-devel-1b51639c20-pvtmem"
MESA_REPO="https://gitlab.freedesktop.org/mesa/mesa.git"
MESA_COMMIT="1b51639c2074b5e6a03c98ea1e5dba73aec40865"
# WinNative-Emu/Drivers tag v1.17, the recipe of WN-Turnip-1.17-b_Axxx.
WN_REPO="https://github.com/WinNative-Emu/Drivers.git"
WN_COMMIT="8407c8012d7b3096621becec73d836f4fbe7b3ce"
WN_SCRIPTS="fix_gralloc_flushall fix_a8xx_dev_info apply_a8xx_gpus apply_a7xx_gen1_quirks apply_a7xx_gen2_ubwc_hint add_aimapper_gralloc add_ubwc_swapchain_usage apply_balance_variant"
NDK_URL="https://dl.google.com/android/repository/android-ndk-r26d-linux.zip"
NDK_SHA1="fcdad75a765a46a9cf6560353f480db251d14765"
DOCKER_IMAGE="whp-mesa-build:24.04"
WORK="${ROOT_DIR}/out/turnip-pvtmem-build"
UPDATE_ASSETS="0"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --update-assets) UPDATE_ASSETS="1"; shift ;;
    --work-dir) WORK="${2:?missing value for --work-dir}"; shift 2 ;;
    -h|--help) sed -n '3,15p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

log() { printf '[build-turnip-pvtmem] %s\n' "$*"; }
mkdir -p "${WORK}"

# Android NDK r26d, checked against Google's published SHA-1.
NDK_DIR="${WORK}/ndk/android-ndk-r26d"
if [[ ! -x "${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
  log "Downloading NDK r26d"
  curl -fsSL -o "${WORK}/ndk-r26d.zip" "${NDK_URL}"
  echo "${NDK_SHA1}  ${WORK}/ndk-r26d.zip" | sha1sum -c -
  rm -rf "${WORK}/ndk"
  mkdir -p "${WORK}/ndk"
  unzip -q "${WORK}/ndk-r26d.zip" -d "${WORK}/ndk"
fi

if ! docker image inspect "${DOCKER_IMAGE}" >/dev/null 2>&1; then
  log "Building Docker image ${DOCKER_IMAGE}"
  docker build -t "${DOCKER_IMAGE}" - <<'EOF'
FROM ubuntu:24.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 python3-pip bison flex pkg-config glslang-tools gcc g++ git ca-certificates && rm -rf /var/lib/apt/lists/*
RUN pip3 install --break-system-packages --no-cache-dir meson==1.6.1 ninja==1.13.2 mako==1.4.3 pyyaml==6.0.3 packaging==26.3
EOF
fi

fetch_commit() {  # repo commit dir
  rm -rf "$3"
  git init -q "$3"
  git -C "$3" remote add origin "$1"
  git -C "$3" fetch -q --depth 1 origin "$2"
  git -C "$3" -c advice.detachedHead=false checkout -q FETCH_HEAD
}

log "Fetching Mesa ${MESA_COMMIT:0:10} and the WinNative recipe ${WN_COMMIT:0:7}"
fetch_commit "${MESA_REPO}" "${MESA_COMMIT}" "${WORK}/mesa"
fetch_commit "${WN_REPO}" "${WN_COMMIT}" "${WORK}/wn"

log "Applying the WinNative 1.17 balanced scripts"
for script in ${WN_SCRIPTS}; do
  (cd "${WORK}/mesa" && python3 -I "../wn/patches/${script}.py")
done
# The source fixes from WinNative's build_turnip.sh.
(
  cd "${WORK}/mesa"
  sed -i 's/typedef const native_handle_t\* buffer_handle_t;/typedef void\* buffer_handle_t;/g' include/android_stub/cutils/native_handle.h
  sed -i 's/, hnd->handle/, (void \*)hnd->handle/g' src/util/u_gralloc/u_gralloc_fallback.c
  sed -i 's/native_buffer->handle->/((const native_handle_t \*)native_buffer->handle)->/g' src/vulkan/runtime/vk_android.c
  sed -i 's/anb->handle->/((const native_handle_t \*)anb->handle)->/g' src/vulkan/runtime/vk_android.c
  sed -i "/-Werror=gnu-empty-initializer/d" meson.build
  git apply "${ROOT_DIR}/patches/mesa/turnip-pvtmem-min.patch"
)

cat > "${WORK}/android-aarch64-wn.ini" <<'EOF'
[binaries]
ar = '/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar'
c = ['/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang']
cpp = ['/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang++', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '--start-no-unused-arguments', '-static-libstdc++', '--end-no-unused-arguments']
c_ld = '/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/ld.lld'
cpp_ld = '/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/ld.lld'
strip = '/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
pkg-config = ['/usr/bin/false']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
EOF

# Same flags and meson options as WinNative's build_turnip.sh.
cat > "${WORK}/build-in-docker.sh" <<'EOF'
#!/bin/sh
set -e
cd /src
# Mesa's code generators iterate Python sets. A fixed hash seed makes their output, and the library, reproducible.
export PYTHONHASHSEED=0
export LDFLAGS="-fuse-ld=lld"
export CFLAGS="-D__ANDROID__ -Wno-error -Wno-error=gnu-empty-initializer -Wno-gnu-empty-initializer -Wno-deprecated-declarations -Wno-incompatible-pointer-types-discards-qualifiers -Wno-incompatible-pointer-types"
export CXXFLAGS="$CFLAGS"
rm -rf /out/build-wn
meson setup /out/build-wn --cross-file /work/android-aarch64-wn.ini \
  -Dbuildtype=release -Dstrip=true -Dplatforms=android -Dvideo-codecs= -Dplatform-sdk-version=36 \
  -Dandroid-stub=true -Dgallium-drivers= -Dvulkan-drivers=freedreno -Dvulkan-beta=true \
  -Dfreedreno-kmds=kgsl -Degl=disabled -Dandroid-libbacktrace=disabled
ninja -C /out/build-wn src/freedreno/vulkan/libvulkan_freedreno.so
EOF
chmod +x "${WORK}/build-in-docker.sh"

log "Compiling Turnip"
mkdir -p "${WORK}/out"
docker run --rm -u "$(id -u):$(id -g)" \
  -v "${WORK}/mesa:/src" -v "${WORK}:/work" -v "${WORK}/out:/out" -v "${NDK_DIR}:/ndk:ro" \
  "${DOCKER_IMAGE}" /work/build-in-docker.sh > "${WORK}/build.log" 2>&1 \
  || { tail -n 30 "${WORK}/build.log"; exit 1; }

PKG="${WORK}/package"
rm -rf "${PKG}"
mkdir -p "${PKG}"
"${NDK_DIR}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  -o "${PKG}/vulkan.ad07xx.so" "${WORK}/out/build-wn/src/freedreno/vulkan/libvulkan_freedreno.so"
chmod 755 "${PKG}/vulkan.ad07xx.so"
LIB_SHA="$(sha256sum "${PKG}/vulkan.ad07xx.so" | cut -d' ' -f1)"
cat > "${PKG}/meta.json" <<EOF
{
  "libraryName": "vulkan.ad07xx.so",
  "name": "Mesa Turnip 26.3.0-devel (WN-Turnip 1.17 b + pvtmem)",
  "version": "26.3.0-devel",
  "mesaRevision": "1b51639c20",
  "librarySha256": "${LIB_SHA}"
}
EOF
chmod 644 "${PKG}/meta.json"

ARCHIVE="${WORK}/adrenotools-${DRIVER_ID}.tzst"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime=@0 --format=gnu \
  -C "${PKG}" -cf - meta.json vulkan.ad07xx.so | zstd -q -19 -f -o "${ARCHIVE}"
ARCHIVE_SHA="$(sha256sum "${ARCHIVE}" | cut -d' ' -f1)"

log "Library ${LIB_SHA}"
log "Archive ${ARCHIVE_SHA} ($(stat -c %s "${ARCHIVE}") bytes)"
if [[ "${UPDATE_ASSETS}" == "1" ]]; then
  cp "${ARCHIVE}" "app/src/main/assets/graphics_driver/adrenotools-${DRIVER_ID}.tzst"
  log "Copied to app/src/main/assets/graphics_driver/adrenotools-${DRIVER_ID}.tzst"
fi
