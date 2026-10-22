# WeHavePhones runtime (Battle.net + retail WoW)

The app runs the official Battle.net client and the installed ARM64 retail World
of Warcraft client in one Wine container on one runtime, with no switch between
them. It bundles a single runtime profile, `p10-ge34`. This document describes
that profile, the patched Wine `ntdll` it depends on and how that `ntdll` is
built. How the app applies a profile is described in
[WEHAVEPHONES_APP.md](WEHAVEPHONES_APP.md).

## The runtime

The container runs `proton-10.0-34-arm64ec` (GE-Proton 10.0-34) for both
applications. The layer's `ntdll` pair is replaced with a build whose ARM64
syscall stubs are self-contained (the dispatcher pointer is read from
TEB+0x308) and that waits a 100 ms grace in `RtlExitUserThread`. With it
Battle.net renders its signed-in home page, and Play launches `Wow-ARM64.exe`,
which reaches the rendered login screen under the same runtime.

Stock Proton 10 renders Battle.net's Chromium (CEF) UI, but the retail client
stalls after `Dx11/D3d12 Device Create Successful`. The Proton 11 builds that
were tried (GE 11.0-5, 11.0-7.1 and 11.0-7.2, and Proton-CachyOS 11.0) did not
render Battle.net's CEF browser at all, whatever Chromium flags were used: the
renderer's Mojo connection to the browser timed out. The app therefore ships
Proton 10.

## Profile `p10-ge34`

The profile is
`app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json`:

| Setting | Value |
| --- | --- |
| `wineVersion` | `proton-10.0-34-arm64ec` |
| Battle.net flags (`bnet.bat`) | `--disable-gpu --enable-features=NetworkServiceInProcess2` |
| Environment | `FEX_MULTIBLOCK=0`, `MESA_SHADER_CACHE_DISABLE=false`, `WINE_FDSAN_LEVEL=off` |
| Graphics driver | `turnip26.3.0-devel-1b51639c20` |

Artifacts it installs:

| Artifact | Target | sha256 |
| --- | --- | --- |
| layer `GE-proton-10.0-34-arm64ec.wcp` | `files/imagefs/opt/proton-10.0-34-arm64ec` | `50344ec9…` |
| `ntdll-grace-copysafe-10.0-34.dll` | `opt/proton-10.0-34-arm64ec/lib/wine/aarch64-windows/ntdll.dll` and prefix `drive_c/windows/system32/ntdll.dll` | `09adc21f…` |
| `ntdll-grace-copysafe-10.0-34.so` | `opt/proton-10.0-34-arm64ec/lib/wine/aarch64-unix/ntdll.so` | `9f3e55a0…` |
| `arm64-vkd3d.dll`, `arm64-libc++.dll`, `arm64-libunwind.dll` | `C:\arm64-*.dll` in the prefix | `f1c11399…`, `ed9edb58…`, `535c6c86…` |

The APK bundles the SHA-256-pinned GE-Proton 10.0-34 layer. First-run setup
installs it offline, then `WeHavePhonesRuntimeApplier` copies the patched
`ntdll` pair over the stock one in the layer and in the prefix (sha256-checked,
with a snapshot and read-back) and places the ARM64 DLLs. The three ARM64 DLLs
are copied into `system32` again when `bnet.bat` and `wow.bat` start.
`scripts/install-wine-layer-adb.py` installs a verified `.wcp` layer onto a
phone from a computer, for manual tests.

### Graphics default

The graphics setup is chosen per device by the [device rules](DEVICE_PROFILES.md). The rules
bundled with the app reproduce the earlier driver defaults for the phones that were tested. WoW
runs in D3D11 through DXVK instead of D3D12 on Adreno 7xx since rules v8 and on every other GPU
since rules v9.

Adreno 6xx and 7xx (and an unknown GPU) use **Mesa Turnip 26.3.0-devel (git-1b51639c20)**,
under the immutable driver ID `turnip26.3.0-devel-1b51639c20`. This is the exact
binary tested with Classic Era on a Galaxy S23+, rather than a stable Mesa 26.3
release or a moving nightly. The
[driver manifest](../app/src/wehavephones/assets/wehavephones/graphics/turnip.json)
pins both the archive and library SHA-256. The build helper and runtime checks
verify the archive before packaging. Adreno 800-839 phones try Turnip V32-RP6sched and
**Gen 8 Turnip**
([manifest](../app/src/wehavephones/assets/wehavephones/graphics/turnip-gen8-stevenmxz-v37.json))
first. If a driver fails its test load, the next one in the profile (finally the system driver) is
used. Adreno 840 phones use the system driver, because every Turnip build drew glitches in WoW on a
OnePlus 15. Non-Adreno devices
retain the wrapper's system Vulkan path with the BCn compat layer.

The rules replace the graphics `version` of the container at session start. The `p10-ge34` profile
still sets that `version` so Play keeps existing managed containers consistent through the usual
snapshot and read-back verification, but it no longer decides the driver. Other graphics settings
and the Wine descriptor workaround is retained. The old 26.0 archive remains
available for an explicit rollback trial.

## Behavior notes

- **Battle.net flags.** CEF 108's separate network utility process crashed
  during a fresh-container check. Running it in the client process
  (`--enable-features=NetworkServiceInProcess2`) allowed sign-in and the game
  install confirmation to render. The feature name comes from
  [Chromium 108's feature definition](https://chromium.googlesource.com/chromium/src/+/refs/tags/108.0.5359.125/content/public/common/content_features.cc).
- **FEX.** The profile sets `FEX_MULTIBLOCK=0` itself: a fresh container's FEX
  preset would otherwise enable multiblock.
  The archive's asset ID is `2508`, but both bundled bridges identify as
  `FEX-2601-116-g1268a39` ([source revision](https://github.com/FEX-Emu/FEX/tree/1268a3910cae02ceb1f3854b647a2397a24f7df7)).
  Use that revision when checking supported controls. In particular, code
  modification checks use `FEX_SMCCHECKS` (`mtrack` by default, or `full`),
  rather than `FEX_SMC_CHECKS`. `FEX_PARANOIDTSO` no longer exists in this build.
  The standalone Battle.net and installer shortcuts use the Stability preset:
  scalar, vector and memcpy TSO enabled, half-barrier TSO enabled, reduced x87
  precision disabled. The one-button Play shortcut uses the container's preset.
  Shortcut environment overrides win over container values and preset defaults,
  and apply to the Wine session and its children. They are not isolated to
  Battle.net if a game is subsequently started in the same session.
  An overnight boot screen on the S23+
  found no speed gain from strict split locks or multiblock. `FEX_SMCCHECKS=full`
  stalled before rendering in all five clean trials. Retain `mtrack` on this
  setup. That screen did not reproduce the baseline's intermittent hang and
  did not include WoW validation.
- **Hidden batch runner.** The 32-bit hidden batch runner (`tools/wcrun`) must
  start native `cmd.exe` through `C:\windows\Sysnative` under WOW64. Otherwise
  the batch's `System32` copies land in `SysWOW64`, leaving ARM64 WoW without
  its VKD3D DLLs. Copying those DLLs into the prefix once is insufficient:
  graphics-wrapper preparation can restore Wine's original D3D12 DLL before the
  next batch runs, so the native batch reapplies the bundled DLLs after that
  preparation.
- **Guest output.** Guest stdout and stderr are drained even when no log file
  can be opened. Secure Folder cannot write the primary user's Downloads path,
  so session logs fall back to the app instance's private
  `files/wehavephones/logs` directory. Without pipe readers, graphics
  diagnostics can fill stderr and block WoW's rendering thread.
  `scripts/test-wehavephones-process.sh` checks both full pipes without a
  logger, observer failure, and selection of the private log directory.
- **Shader cache.** The profile sets `MESA_SHADER_CACHE_DISABLE=false`, so the
  [on-disk shader cache](https://docs.mesa3d.org/envvars.html#mesa-shader-cache-disable)
  stays on and later launches reuse compiled shaders. Earlier profiles disabled it
  to work around a Mesa `compile failed!` abort that Classic Era hit right after
  creating its D3D12 device on an older Turnip. With the cache re-enabled on
  Turnip 26.3, the game launched without that abort on the S23+ on 2026-10-11.
  The value is set explicitly rather than omitted because the applier only
  replaces keys the profile lists, so existing prepared containers would
  otherwise keep the old `true`. Cache files are never deleted.
- **fdsan.** The profile requests `WINE_FDSAN_LEVEL=off`. On Android API 29+ the
  guest launcher preloads `libwehavephones_fdsan.so` before Wine starts when a
  container requests `off` or diagnostic `warn`. This extends the Android 17
  workaround to Android 16, where Classic Era also aborted with
  `fdsan: attempted to close file descriptor ... owned by FILE*`, and loading the
  shim before Wine also covers failures before sign-in. The Android 17 default
  remains available without an explicit profile setting. The setting changes
  only the guest process's sanitizer level, not the Android app or device
  setting. It avoids the fatal check. It does not repair Wine's underlying
  descriptor ownership bug.
- **Battle.net cold starts.** A cold start sometimes stalls in an empty dark
  window and never paints its page. The one-button launcher restarts Battle.net
  in the same session when that happens (see the Play flow in
  [WEHAVEPHONES_APP.md](WEHAVEPHONES_APP.md)).
- **Sign-in.** WeHavePhones reads, types and stores no Battle.net credentials.
  Battle.net signs in with the login it saved itself.
  The Diagnostics screen includes a bounded authentication summary from the
  client's latest main log. It reports page categories, browser states, HTTP
  status and fixed error codes, without URLs, challenge identifiers, account
  details or arbitrary log text. Dev builds also update
  `Download/WeHavePhones/battle-net-auth.txt` during a session so a blank window
  can be investigated without remote ADB. A loaded `Challenge` page does not
  establish that its contents painted or that sign-in succeeded.
- **Wine identifier pattern.** The app only accepts Wine directory names that
  match `WineInfo.pattern` (for example `proton-10.0-34-arm64ec`). Any other name
  makes `XServerDisplayActivity` rewrite `container.wineVersion` to the first
  arm64ec runtime at session start.
- **Shared prefix `ntdll.dll`.** The prefix's `system32/ntdll.dll` is shared by
  every Wine version in the container. The applier writes the patched DLL there
  on every apply, next to the copy in the layer.

### Observed timings

Measured on a Galaxy S23+ with an earlier host harness:

- Battle.net cold start to rendered signed-in home: 85 to 155 s. The first
  ~60 s can be black or show an empty window even when the start will succeed.
- WoW login screen after Play: ~15 to 30 s. Under memory pressure it can take
  ~75 s or more.
- Battle.net takes `MemAvailable` from ~2.6 GB to ~1.0 to 1.5 GB, with
  `SwapFree` at ~7 to 10 GB.

## The patched ntdll

`patches/wine/arm64-copyable-syscalls-grace-wine10.patch` changes only Wine:

- It makes Wine's ARM64 syscall stubs self-contained, as they are on Windows.
  Each stub loads the syscall dispatcher from TEB+0x308 instead of from an
  `ntdll` data literal, so code that copies the stubs keeps working
  (`include/wine/asm.h`. The dispatcher is initialised in
  `dlls/ntdll/unix/signal_arm64.c`.).
- It adds a 100 ms grace in `RtlExitUserThread` (`dlls/ntdll/thread.c`).

The grace alone was not enough: WoW still stalled with `gx.log` frozen after
device creation while Battle.net rendered. With the self-contained stubs added,
WoW starts and Battle.net still renders.

### How the ntdll was built

- Source: [The412Banner/proton-wine](https://github.com/The412Banner/proton-wine),
  branch `aio-eanet/proton_10.34-GE`, commit
  `c74acb09d51b447f5d5ac56c87608d3ec565a767`, after
  `build-scripts/apply-ge-patches.sh`, plus the patch above.
- Build: `bash build-scripts/build-step-arm64ec.sh --enable-16kb-pages --ntdll`
  (autogen, wine-tools, configure, then only `aarch64-windows/ntdll.dll` and
  `ntdll.so`).
- Configure was run minimally (`--without-x`, `--without-freetype`,
  `--without-fontconfig`, `--without-pulse`, `--without-gstreamer`,
  `--without-vulkan`, `--without-sdl`, `--without-gnutls`, `--without-alsa`,
  `--without-opengl`) because the aarch64 sysroot used for the build also
  contained x86-64 libraries. The GUI drivers come from the GE layer. The host
  `wine-tools` build needed `--without-freetype`, and a stray x86-64
  `libunwind.a` had to be moved out of the way.
- The resulting `ntdll.dll` and `ntdll.so` are the two files pinned by SHA-256 in
  the runtime profile.
