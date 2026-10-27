# Optional Turnip catalog, October 27, 2026

Rules v10 appends 46 download-on-selection choices for Adreno 8xx phones. Like the
[October 16 catalog](TURNIP_OPTIONAL_2026_10_16.md), it keeps every automatic driver list and its
order, rule matching, graphics API, environment variable, compatibility layer, existing driver
entry and saved picker id. OnePlus 15 still defaults to the system Qualcomm driver with D3D11.
No startup prefetch or automatic fallback tries these additions.

## Choices and applicability

- `turnip-gen8-stevenmxz-v38` and `turnip-gen8-stevenmxz-v38-patched`:
  [StevenMXZ release v27.0.0-R1](https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v27.0.0-R1),
  `Turnip_Gen8_V38.zip` and `Turnip_Gen8_V38_Patched.zip`, published October 19. Optional in
  `adreno-8xx` and `adreno-84x`.
- 43 [The412Banner](https://github.com/The412Banner/Banners-Turnip/releases) Android A8xx builds,
  `turnip-banner-27.0.0-<build>-a8xx`, from `20261016-r5` to `20261027-r3`: October 16 r5-r7,
  October 17 base and r2, October 18 base and r2-r3, October 19 base and r2-r3, October 20 base and
  r2-r5, October 21 base and r2-r6, October 22 base, October 23 r3-r6, October 24 base and r2-r7,
  October 25 r2, r3 and r5, October 26 base and r2-r3, October 27 base and r2-r3. Optional in
  `adreno-8xx` and `adreno-84x`.
- `turnip-banner-a8xx-850-845-pre1`:
  [Banner prerelease](https://github.com/The412Banner/Banners-Turnip/releases/tag/a8xx-850-845-pre1)
  with basic Adreno 850/845 support from Mesa MR 45384. Optional only in `adreno-84x`, the profile
  that an Adreno 845 or 850 renderer string matches (840-859).

Every Banner entry uses exactly `Turnip-<tag>-A8xx.zip`, the Android KGSL AdrenoTools variant.
No Linux/glibc, Wayland, standard, One UI or A710 test package is added, and neither is
StevenMXZ's `Turnip_v27.0.0-R1` A6xx/A7xx pair. Banner releases with no A8xx Android asset
(October 22 r2-r6, October 23 base and r2, October 25 r4) have nothing to add.

`v27.0.0-20261025` is left out on purpose. Its `libvulkan_freedreno.so` is byte-identical to
`v27.0.0-20261024-r7` (SHA-256 `76326f01…3d401037` in both, only `meta.json` differs), so a separate entry
would load the same driver. The manifest records the exclusion.

Picker rows keep the existing order and append the new entries: V38, V38 Patched, the Banner
builds by publication time, then the A850/A845 prerelease. Adreno 840 phones now see 69 drivers
and other Adreno 8xx phones 66. Adreno 6xx, 7xx, Mali and every other profile are unchanged.

## Artifact evidence and limits

The [provenance manifest](../app/src/wehavephones/assets/wehavephones/graphics/turnip-optional-20261027.json)
records the release and download URLs, publication times, archive and library SHA-256, upstream
`meta.json`, Mesa revision, ELF build id and chip-table byte offsets for each entry. All 47 ZIPs,
including the excluded one, were downloaded and matched GitHub's release-asset SHA-256. Each
contains only `meta.json` and `libvulkan_freedreno.so`. All libraries are little-endian AArch64
ELF64 Android HAL drivers with an exported `HMI`, Android `libc.so` dependencies and metadata
requiring Android API 28 or newer. The Mesa revision in every Banner library matches the commit
in its release notes. These are package compatibility checks, not successful Vulkan probe or
game tests.

Banner marks every normal build as automated, checked by CI and not tested on a device. The
release notes describe the same A8xx stack throughout: whitebelyash `turnip/gen8`, the 64 KB
shared-memory patch and WinNative patches 0001-0005 (mesh shader emulation, wave32 and the
cube-map, bindless-descriptor and command-stream hang fixes). Most revisions differ only in the
Mesa `main` commit they rebuild. No speed, stability or rendering improvement is established
between them, and none is verified for WoW Retail or OnePlus 15.

Every library has A810, A812, A825, A829, A830 and both A840 chip-table entries
(`0xffff44050A31` and `0xffff44050A21`). Builds from October 16 r5 onward also carry Mesa's
`0xffff44070031`, `0xffff44060030` and `0xffff44070041` entries, which upstream names Adreno
X2-85 and X2-90. Those are laptop GPUs, not phones. From October 23 r3 the table also adds a
`0xffff44030A20` A829 entry beside the KGSL one.

StevenMXZ V38 identifies Mesa `f28352d117`, the same revision as Banner `v27.0.0-20261019`. Its
notes cite whitebelyash `turnip/gen8` plus Mesa upstream for A840/A830/A829/A825/A810. The
Patched variant adds unspecified WinNative and Vauzi-17/710 patches and hacks. Its chip table has
an unnamed `0x44030A00` entry but no A825 `0x44030000` or A812 `0x44010200` entry, so A825 and
A812 support is unverified for it. Neither V38 metadata file has a package version.

The A850/A845 prerelease is Mesa `02f1337523` plus the two MR 45384 patches and the usual A8xx
stack. Its table adds `0xffff44051431` and `0xffff44041430`. The maintainer reports no device
testing and advises other GPUs to keep using the normal releases. The real chip ids and renderer
strings of shipping Adreno 845/850 phones have not been seen here. The `adreno-84x-chip` rule
does not match those two ids, so such a phone reaches the profile only through its renderer
string.

## Rechecking

`scripts/test-wehavephones-devices.sh` compares with the committed v6 fixture, checks every
profile's defaults, existing driver entries and picker order, exercises automatic fallback and
saved selections, and checks exact optional applicability for both catalogs.

To repeat the archive, metadata, library, ELF and chip-byte checks, download the manifest URLs
into a task directory under `$TMPDIR`, then run:

```bash
python3 tools/wehavephones-devices-selftest/optional_turnip_selftest.py --archives "$TMPDIR/turnip-optional-20261027"
```

The check covers each manifest whose archives are all in that directory. Keep downloaded
binaries outside git. Sign changed rules with `scripts/sign-device-rules.sh` and commit
`device-rules.json` together with its signature. A catalog rollback must use another higher
rules version and signature so phones can accept it.
