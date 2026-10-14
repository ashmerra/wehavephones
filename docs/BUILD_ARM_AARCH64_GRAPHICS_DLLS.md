# Build ARM (aarch64) Graphics DLLs

This workflow builds and injects **aarch64-windows** graphics DLLs for ARM64 game processes (e.g. `WowClassic-arm64.exe`):

- DXVK: `dxgi.dll`, `d3d11.dll`, `d3d10core.dll`, `d3d9.dll`
- VKD3D-Proton: `d3d12.dll` (and `d3d12core.dll` if available in selected version)

It does **not** modify `arm64ec` assets.

## 1) Build in Docker

```bash
scripts/build-arm-graphics-dlls-docker.sh
```

Default versions:

- DXVK: `v2.3.1`
- VKD3D-Proton: `v2.8`

The container matches the host architecture (x86_64 or arm64) and uses the
matching llvm-mingw host build. Both cross-compile to aarch64 Windows.

Output:

- `out/arm-graphics/aarch64/system32/*.dll`
- `out/arm-graphics/aarch64/system32/manifest.txt`

`--swaptrace` adds the upstream SWAPTRACE debug logging, which writes a line
for every present and draw. Use it only for swapchain debugging and never ship
its DLLs.

The app ships these DXVK DLLs as an overlay over the default DXVK's aarch64
DLLs (see [BUILD_ASSETS.md](BUILD_ASSETS.md)). Refresh it with:

```bash
scripts/build-arm-graphics-dlls-docker.sh --skip-vkd3d --package-dxvk
```

A changed overlay reaches existing containers only when the DXVK cache id in
`XServerDisplayActivity.getDXWrapperCacheId` changes too.

If your network is unstable, set proxy first:

```bash
export https_proxy="http://<host-lan-ip>:8080"
export http_proxy="http://<host-lan-ip>:8080"
scripts/build-arm-graphics-dlls-docker.sh
```

Do not use `localhost` here for Docker builds unless proxy runs inside the container.

## 2) Inject into a container

Start the target container once so its Wine prefix exists, then copy the DLLs
from `out/arm-graphics/aarch64/system32/` into the prefix's `system32`
directory with your own tooling (for example `adb push` to a staging directory
and a copy from there into the app's private storage). The target path inside
the app sandbox is:

- `files/imagefs/home/xuser/.wine/drive_c/windows/system32`

## 3) Quick verification

Check logs for DLL load path and D3D init:

```bash
adb logcat | grep -Ei "winlator|wine|dxgi|d3d11|d3d12|vulkan"
```

If needed, enable Wine debug temporarily:

```bash
WINEDEBUG=+loaddll,+dxgi,+d3d11,+d3d12,+vulkan
```

## Notes

- Container restart may overwrite injected files from packaged assets. Copy the DLLs again after a restart.
- This is a focused ARM64-game path workflow. The x86/x64/FEX paths are intentionally unchanged.
