# Optional Turnip catalog, October 27, 2026

Rules v11 appends seven download-on-selection choices for Adreno 8xx phones. Like the
[October 16 catalog](TURNIP_OPTIONAL_2026_10_16.md), it keeps every automatic driver list and its
order, rule matching, graphics API, environment variable, compatibility layer, existing driver
entry and saved picker id. OnePlus 15 still defaults to the system Qualcomm driver with D3D11.
No startup prefetch or automatic fallback tries these additions. A first draft, rules v10, listed
46 entries and was never published.

## Choices and applicability

- `turnip-gen8-stevenmxz-v38` and `turnip-gen8-stevenmxz-v38-patched`:
  [StevenMXZ release v27.0.0-R1](https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v27.0.0-R1),
  `Turnip_Gen8_V38.zip` and `Turnip_Gen8_V38_Patched.zip`, published October 19. Optional in
  `adreno-8xx` and `adreno-84x`.
- Four [The412Banner](https://github.com/The412Banner/Banners-Turnip/releases) Android A8xx builds,
  `turnip-banner-27.0.0-<build>-a8xx`: `20261021-r6`, `20261023-r4`, `20261024-r7` and `20261027-r3`.
  Optional in `adreno-8xx` and `adreno-84x`.
- `turnip-banner-a8xx-850-845-pre1`:
  [Banner prerelease](https://github.com/The412Banner/Banners-Turnip/releases/tag/a8xx-850-845-pre1)
  with basic Adreno 850/845 support from Mesa MR 45384. Optional only in `adreno-84x`, the profile
  that an Adreno 845 or 850 renderer string matches (840-859).

Every Banner entry uses exactly `Turnip-<tag>-A8xx.zip`, the Android KGSL AdrenoTools variant.
No Linux/glibc, Wayland, standard, One UI or A710 test package is added, and neither is
StevenMXZ's `Turnip_v27.0.0-R1` A6xx/A7xx pair.

## Choosing four of 44 Banner builds

Banner published 44 A8xx Android builds from October 16 r5 to October 27 r3. All carry the same
patch stack and differ only in the Mesa `main` commit they rebuild. Counting the Mesa commits
between consecutive builds that touch `src/freedreno`, `src/compiler`, `src/vulkan` or `src/util`
shows that 16 builds have no Turnip-relevant change from the build before. `v27.0.0-20261025` even
has a byte-identical library to `20261024-r7`. Most others differ by a handful of small commits,
some only in Mesa's crash-dump tools. The real Turnip changes cluster in October 23 r3 (18
freedreno commits, among them dot-product and per-game GMEM changes), October 24 r2 (13) and
October 27 r2 (7).

The catalog keeps one build per stretch: `20261021-r6`, the last build before the October 23
changes, then `20261023-r4`, `20261024-r7` and the latest `20261027-r3`. A player who sees
glitches on one of them has a build with different Turnip code to try. Any other build can be
added later through a new signed rules version, without an app release.

Picker rows keep the existing order and append the new entries: V38, V38 Patched, the four
Banner builds by publication time, then the A850/A845 prerelease. Adreno 840 phones now see 30
drivers and other Adreno 8xx phones 27. Adreno 6xx, 7xx, Mali and every other profile are
unchanged.

## Artifact evidence and limits

The [provenance manifest](../app/src/wehavephones/assets/wehavephones/graphics/turnip-optional-20261027.json)
records the release and download URLs, publication times, archive and library SHA-256, upstream
`meta.json`, Mesa revision, ELF build id and chip-table byte offsets for each entry. All 47
candidate ZIPs, the 44 Banner builds, the prerelease and both V38 packages, were downloaded and
matched GitHub's release-asset SHA-256. Each contains only `meta.json` and `libvulkan_freedreno.so`. All libraries are little-endian AArch64
ELF64 Android HAL drivers with an exported `HMI`, Android `libc.so` dependencies and metadata
requiring Android API 28 or newer. The Mesa revision in every Banner library matches the commit
in its release notes. These are package compatibility checks, not successful Vulkan probe or
game tests.

Banner marks every normal build as automated, checked by CI and not tested on a device. The
release notes describe the same A8xx stack throughout: whitebelyash `turnip/gen8`, the 64 KB
shared-memory patch and WinNative patches 0001-0005 (mesh shader emulation, wave32 and the
cube-map, bindless-descriptor and command-stream hang fixes). No speed, stability or rendering improvement is established
between them, and none is verified for WoW Retail or OnePlus 15.

Every inspected Banner library has A810, A812, A825, A829, A830 and both A840 chip-table entries
(`0xffff44050A31` and `0xffff44050A21`). Builds from October 16 r5 onward also carry Mesa's
`0xffff44070031`, `0xffff44060030` and `0xffff44070041` entries, which upstream names Adreno
X2-85 and X2-90. Those are laptop GPUs, not phones. From October 23 r3 the table also adds a
`0xffff44030A20` A829 entry beside the KGSL one, so the three newer listed builds have it.

StevenMXZ V38 identifies Mesa `f28352d117`, the same revision as Banner `v27.0.0-20261019`, a
build that is not listed itself. Its
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
