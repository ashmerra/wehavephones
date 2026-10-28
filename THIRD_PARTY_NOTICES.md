# Third-Party Notices

WeHavePhones ships its own code together with prebuilt software
from many other projects. This file inventories identified APK components, their licenses, and sources.
Open provenance questions are tracked in the project's issue tracker. Full license texts are in
[`docs/licenses/`](docs/licenses/).

## Fork lineage

| Project | Author | License | Source |
|---|---|---|---|
| Winlator | BrunoSX (brunodev85) | MIT for versions before 2026-04-14 (later versions are LGPL-2.1) | https://github.com/brunodev85/winlator |
| Winlator LLM | FrontMage | MIT | https://github.com/FrontMage/winlator-llm |
| WeHavePhones changes (this repo) | ashmerra | MIT | this repository |

This repository forked from Winlator LLM (baseline commit `b1c7b23`,
2026-02-17), which derived from MIT-licensed Winlator. See [`LICENSE`](LICENSE).
Upstream Winlator switched to LGPL-2.1 on 2026-04-14. Code taken from Winlator
after that date would be LGPL-2.1, not MIT.

## GPL and LGPL components

These licenses require that anyone who receives the binaries can also get the
matching source code. Where the source is in this repository, it is the source
the APK was built from.

| Component | Shipped as | License | Source |
|---|---|---|---|
| Wine / Proton 9.0 arm64ec (`runtime` development flavor only, not in the WeHavePhones APK) | `assets/proton-9.0-arm64ec.txz` | LGPL-2.1+ | **Exact source not yet identified** (inherited from upstream Winlator) |
| Wine / Proton 10 arm64ec (`runtime` development flavor only, not in the WeHavePhones APK) | `assets/preinstall/proton-10-arm64ec.wcp.xz` | LGPL-2.1+ | **Exact source not yet identified** (inherited from Winlator Cmod) |
| GE-Proton 10.0-34 arm64ec (bundled in WeHavePhones) | `assets/wehavephones/wine/GE-proton-10.0-34-arm64ec.wcp`, installed to `imagefs/opt/proton-10.0-34-arm64ec` | LGPL-2.1+ | https://github.com/The412Banner/proton-wine, release `build-bionic-layers-20260911-xp`, commit `91af073dbead93fe51a2e2abc8b6add6372c67e8` |
| Patched Wine `ntdll` (WeHavePhones runtime profiles) | `assets/wehavephones/ntdll/ntdll-*.dll`, `ntdll-*.so` | LGPL-2.1+ | https://github.com/The412Banner/proton-wine commit `c74acb09d51b447f5d5ac56c87608d3ec565a767` (branch `aio-eanet/proton_10.34-GE`), after `build-scripts/apply-ge-patches.sh`, plus [`patches/wine/arm64-copyable-syscalls-grace-wine10.patch`](patches/wine/arm64-copyable-syscalls-grace-wine10.patch). Build steps in [`docs/WEHAVEPHONES_RUNTIME.md`](docs/WEHAVEPHONES_RUNTIME.md) |
| Patched `wineserver` (WeHavePhones runtime profiles) | `assets/wehavephones/server/wineserver-*`, installed to `imagefs/opt/proton-10.0-34-arm64ec/bin/wineserver` | LGPL-2.1+ | https://github.com/The412Banner/proton-wine commit `c74acb09d51b447f5d5ac56c87608d3ec565a767`, with the Android patch list of `build-scripts/build-step-arm64ec.sh`, `build-scripts/apply-ge-patches.sh` and [`patches/wine/server-android-file-owner-wine10.patch`](patches/wine/server-android-file-owner-wine10.patch). Build steps in [`docs/WEHAVEPHONES_RUNTIME.md`](docs/WEHAVEPHONES_RUNTIME.md) |
| VKD3D-Proton 2.12, 2.14.1 | `assets/dxwrapper/vkd3d-*.tzst` | LGPL-2.1 | https://github.com/HansKristian-Work/vkd3d-proton (tags `v2.12`, `v2.14.1`). Local patch in [`patches/vkd3d/`](patches/vkd3d/) |
| PRoot | `libproot.so`, `libproot-loader*.so` | GPL-2.0 | [`app/src/main/cpp/proot/`](app/src/main/cpp/proot/) (upstream https://github.com/proot-me/proot) |
| talloc (statically linked into PRoot) | `libproot.so` | LGPL-3.0+ | [`app/src/main/cpp/proot/talloc/`](app/src/main/cpp/proot/talloc/) (upstream https://talloc.samba.org) |
| patchelf | `libpatchelf.so` | GPL-3.0 | [`app/src/main/cpp/patchelf/`](app/src/main/cpp/patchelf/) (upstream https://github.com/NixOS/patchelf) |
| PulseAudio 13.0 | `jniLibs/*/libpulse*.so` | LGPL-2.1+ | https://gitlab.freedesktop.org/pulseaudio/pulseaudio (tag `v13.0`) |
| libsndfile | `jniLibs/*/libsndfile.so` | LGPL-2.1+ | https://github.com/libsndfile/libsndfile |
| libltdl (GNU Libtool) | `jniLibs/*/libltdl.so` | LGPL-2.1+ | https://www.gnu.org/software/libtool/ |
| Linux userland (glibc, coreutils, GStreamer, etc.) | `assets/imagefs.txz` | Mixed: GPL-2.0, GPL-3.0, LGPL-2.1+, MIT and others, per package | Taken unchanged from FrontMage/winlator-llm's APK release `llm-apk-20260216-144633` (see [`docs/BUILD_ASSETS.md`](docs/BUILD_ASSETS.md)). This repository does not build it. Source: Arch Linux ARM packages (https://archlinuxarm.org/packages) plus the `rootfs-custom-winlator` base, which is no longer online. The exact package list is not yet recorded. |

License texts: [GPL-2.0](docs/licenses/GPL-2.0.txt),
[GPL-3.0](docs/licenses/GPL-3.0.txt), [LGPL-2.1](docs/licenses/LGPL-2.1.txt),
[LGPL-3.0](docs/licenses/LGPL-3.0.txt), [Wine notice](docs/licenses/Wine-LICENSE.txt).

## Permissive components

| Component | Shipped as | License | Source |
|---|---|---|---|
| Box64 / WowBox64 0.3.7 | `assets/wowbox64/` | MIT, [text](docs/licenses/Box64-MIT.txt) | https://github.com/ptitSeb/box64 |
| FEX (`FEX-2601-116-g1268a39`, asset ID `2508`) | `assets/fexcore/` | MIT, [text](docs/licenses/FEX-MIT.txt) | [FEX-Emu/FEX, revision 1268a391](https://github.com/FEX-Emu/FEX/tree/1268a3910cae02ceb1f3854b647a2397a24f7df7) |
| DXVK 2.4.1, 2.3.1 (gplasync, arm64ec) | `assets/dxwrapper/dxvk-*.tzst` | zlib, [text](docs/licenses/DXVK-Zlib.txt) | https://github.com/doitsujin/dxvk, https://gitlab.com/Ph42oN/dxvk-gplasync |
| DXVK 2.3.1 aarch64 DLLs (built by WeHavePhones, extracted over the default DXVK's `system32/`) | `assets/dxwrapper/aarch64/dxvk-2.3.1.tzst` | zlib, [text](docs/licenses/DXVK-Zlib.txt) | https://github.com/doitsujin/dxvk (tag `v2.3.1`, unmodified), built with [`scripts/build-arm-graphics-dlls-docker.sh`](scripts/build-arm-graphics-dlls-docker.sh) `--skip-vkd3d --package-dxvk` |
| D8VK 1.0 | `assets/dxwrapper/d8vk-1.0.tzst` | zlib, [text](docs/licenses/D8VK-Zlib.txt) | https://github.com/AlpyneDreams/d8vk |
| Mesa (Turnip, Zink, VirGL drivers) | `assets/graphics_driver/` | MIT, mostly (per-file SPDX tags), [text](docs/licenses/Mesa-MIT.txt) | https://gitlab.freedesktop.org/mesa/mesa |
| Default Adrenotools Turnip: Mesa 26.3.0-devel, revision `1b51639c20` | `assets/graphics_driver/adrenotools-turnip26.3.0-devel-1b51639c20.tzst` | Mesa licenses, [text](docs/licenses/Mesa-MIT.txt) | Exact tested binary and SHA-256 recorded in [`turnip.json`](app/src/wehavephones/assets/wehavephones/graphics/turnip.json). Taken from an earlier private test container. Original binary publisher and build flags are not recorded. |
| Adrenotools Turnip for Adreno 8xx: Mesa 26.3.0-devel, revision `a995eaeda4`, with the whitebelyash `turnip/gen8` changes ("Turnip Gen8 V37" by StevenMXZ) | `assets/graphics_driver/adrenotools-turnip-gen8-stevenmxz-v37.tzst` | Mesa licenses, [text](docs/licenses/Mesa-MIT.txt) | Unmodified `libvulkan_freedreno.so` from https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v37 (`Turnip_Gen8_V37.zip`). Source: https://gitlab.freedesktop.org/mesa/mesa and https://github.com/whitebelyash/mesa-unified (branch `turnip/gen8`). Exact SHA-256 values recorded in [`turnip-gen8-stevenmxz-v37.json`](app/src/wehavephones/assets/wehavephones/graphics/turnip-gen8-stevenmxz-v37.json). Build flags are not recorded. |
| Adrenotools Turnip for Adreno 8xx: "Turnip-V32-RP6sched" by arusiasotto, Mesa 26.3.0-devel from the whitebelyash `turnip/gen8` branch at `9c7e022`, plus the RP6 ir3 barycentric scheduler patch and `vk_icd*` exports | Not bundled. Downloaded on Adreno 8xx phones by the device rules (`turnip-v32-rp6sched` in `device-rules.json`) | Mesa licenses, [text](docs/licenses/Mesa-MIT.txt) | Unmodified `turnip-V32-RP6sched.zip` from https://github.com/arusiasotto/wow-forever-a840 (commit `f9312100`), which documents both patches and the build steps. Source: https://github.com/whitebelyash/mesa-unified (branch `turnip/gen8`). Archive and library SHA-256 values recorded in `device-rules.json`. |
| Adrenotools Turnip builds offered in the Diagnostics driver picker: whitebelyash "Mainline Turnip V32", "Stable Turnip V2" and "A8XX Turnip V29". StevenMXZ "Turnip Gen8 V33" and "Turnip Gen8 V30". Mr_Purple_666 "Turnip T30" and "Turnip T26". GameHub "turnip_v26.0.0_R8_b12" (Mesa 26.0.0-devel) | Not bundled. Downloaded only when a player picks one (`choices` in `device-rules.json`) | Mesa licenses, [text](docs/licenses/Mesa-MIT.txt) | Unmodified release archives from https://github.com/whitebelyash/freedreno_turnip-CI (tu_v32, stu_v2, tu_v29), https://github.com/StevenMXZ/freedreno_turnip-CI (v33, gen_8_v30), https://github.com/MrPurple666/purple-turnip (T30, T26) and GameNative's mirror of the GameHub build. Source: https://gitlab.freedesktop.org/mesa/mesa and https://github.com/whitebelyash/mesa-unified. GameHub does not publish the source commit of its build. Archive and library SHA-256 values recorded in `device-rules.json`. |
| Optional Turnip additions, 2026-10-16: Gamesir Rutabaga v3 and The412Banner A8xx 26.3.0-20261015 r4-r7 and 27.0.0-20261016 r3-r4 | Not bundled. Downloaded only after an explicit picker selection | Mesa licenses, [text](docs/licenses/Mesa-MIT.txt) | Unmodified Android AdrenoTools ZIPs from https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/gamesir and https://github.com/The412Banner/Banners-Turnip/releases. Mesa source: https://gitlab.freedesktop.org/mesa/mesa. Banner combines whitebelyash Gen8 and MaxsTechReview WinNative patches. GameHub does not publish the exact Rutabaga patch set. Exact release URLs, metadata and both hashes are in [the provenance manifest](app/src/wehavephones/assets/wehavephones/graphics/turnip-optional-20261016.json). [Compatibility notes](docs/TURNIP_OPTIONAL_2026_10_16.md). |
| Optional Turnip additions, 2026-10-27: StevenMXZ Turnip Gen8 V38 and V38 Patched, The412Banner A8xx 27.0.0 builds 20261021-r6, 20261023-r4, 20261024-r7 and 20261027-r3 and the A8xx 850-845 pre1 test build | Not bundled. Downloaded only after an explicit picker selection | Mesa licenses, [text](docs/licenses/Mesa-MIT.txt) | Unmodified Android AdrenoTools ZIPs from https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v27.0.0-R1 and https://github.com/The412Banner/Banners-Turnip/releases. Mesa source: https://gitlab.freedesktop.org/mesa/mesa. Both combine the whitebelyash Gen8 stack with WinNative patches. The pre1 build adds Mesa MR 45384. StevenMXZ does not list the exact V38 Patched patch set. Exact release URLs, metadata and both hashes are in [the provenance manifest](app/src/wehavephones/assets/wehavephones/graphics/turnip-optional-20261027.json). [Compatibility notes](docs/TURNIP_OPTIONAL_2026_10_27.md). |
| virglrenderer | `libwinlator.so` / native code | MIT, [text](docs/licenses/virglrenderer-MIT.txt) | [`app/src/main/cpp/virglrenderer/`](app/src/main/cpp/virglrenderer/) |
| libadrenotools | native code | BSD-2-Clause, [text](docs/licenses/libadrenotools-BSD-2-Clause.txt) | [`app/src/main/cpp/adrenotools/`](app/src/main/cpp/adrenotools/) |
| OpenXR SDK | `libopenxr_loader.so` | Apache-2.0, [text](docs/licenses/Apache-2.0.txt) | [`app/src/main/cpp/OpenXR-SDK/`](app/src/main/cpp/OpenXR-SDK/) |
| Vulkan wrapper, vkBasalt, BCn layer | `assets/graphics_driver/wrapper.tzst`, `extra_libs.tzst` | **To confirm** | **To confirm** |
| Tiny5 font | WeHavePhones LCD headings | SIL OFL 1.1, [text](docs/licenses/Tiny5-OFL.txt) | https://github.com/Gissio/font_tiny5 |
| ConsolePort addon (the release pinned in `consoleport.json`, fetched at build time, and the app can also download newer releases) | `assets/wehavephones/addons/ConsolePort.zip`, installed into each game's AddOns folder | Artistic-2.0, [text](docs/licenses/ConsolePort-Artistic-2.0.txt) | https://github.com/seblindfors/ConsolePort |

## Android libraries and design assets

Direct Java dependencies are pinned in `app/build.gradle`. This table expands
credit beyond the inherited runtime list. A complete transitive dependency
and bundled guest-utility inventory is still open and tracked in the
project's issue tracker.

| Component | Version / use | License | Source |
|---|---|---|---|
| AndroidX AppCompat / Preference | 1.2.0 / 1.1.1 | Apache-2.0, [text](docs/licenses/Apache-2.0.txt) | https://android.googlesource.com/platform/frameworks/support/ |
| Material Components for Android | 1.4.0 | Apache-2.0, [upstream text](https://github.com/material-components/material-components-android/blob/1.4.0/LICENSE) | https://github.com/material-components/material-components-android |
| zstd-jni | 1.5.2-3 | BSD-2-Clause for JNI, BSD-3-Clause for bundled Zstandard. See the [upstream notices](docs/licenses/zstd-jni-LICENSE.txt) | https://github.com/luben/zstd-jni/tree/v1.5.2-3 |
| XZ for Java | 1.7 | Public domain for this version (newer versions differ) | https://tukaani.org/xz/java.html |
| Apache Commons Compress | 1.20 | Apache-2.0, [text](docs/licenses/Apache-2.0.txt) | https://commons.apache.org/proper/commons-compress/ |
| Phosphor icons | WeHavePhones navigation / UI, Light icons | MIT, [text](docs/licenses/Phosphor-MIT.txt) | https://github.com/phosphor-icons/core |

## Proprietary files (not open source)

These files are not open source, and nothing in this file grants any rights to
them. WeHavePhones redistributes them the same way upstream Winlator does. They
remain the property of their owners.

| Files | Shipped as | Owner |
|---|---|---|
| DirectX, DirectMusic, DirectPlay, DirectShow, DirectSound, Windows Media and VC++ 2010 runtime DLLs (`d3dx9_*`, `d3dcompiler_*`, `dmusic`, `msvcr100`, ...), `cryptbase`, `rsaenh` | `assets/wincomponents/*.tzst` | Microsoft |
| Adreno Vulkan driver v819 ("Extracted from the Quest 2") | `assets/graphics_driver/adrenotools-v819.tzst` | Qualcomm |
| Adreno Vulkan drivers v891.7, v863.1 (StevenMXZ releases), v849 and v842.6 (K11MCH1 releases) | Not bundled. Downloaded from those GitHub releases only when a player picks one in Diagnostics (`device-rules.json`) | Qualcomm |

## Getting the source

Source for the components built from this repository is in the repository at
the commit the APK was built from. For the other GPL/LGPL components, use the
upstream links above. The rows marked **not yet identified** or **to confirm**
are open items, tracked in the project's issue tracker.

## Trademarks

World of Warcraft, Warcraft, Battle.net and Blizzard Entertainment are
trademarks or registered trademarks of Blizzard Entertainment, Inc. in the U.S.
and/or other countries. Other product names are trademarks of their respective
owners. WeHavePhones is not affiliated with, endorsed, sponsored, or approved
by Blizzard Entertainment, Inc. or Microsoft, and it ships no Blizzard game
files or assets.
