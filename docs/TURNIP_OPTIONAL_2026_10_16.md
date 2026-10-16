# Optional Turnip catalog, October 16, 2026

Rules v7 appends seven download-on-selection choices. It preserves the complete v6 automatic
driver lists and their order, rule matching, graphics APIs, environment variables, compatibility
layers, existing driver entries and saved picker ids. OnePlus 15 still defaults to the system
Qualcomm driver with D3D11. No startup prefetch or automatic fallback tries these additions.

## Choices and applicability

- `turnip-gamesir-rutabaga-v3`: [StevenMXZ Gamesir release](https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/gamesir),
  `Turnip-Rutabaga-v3.zip`, published October 14. Optional in `adreno-6xx`, `adreno-7xx`,
  `adreno-8xx` and `adreno-84x`.
- `turnip-banner-26.3.0-20261015-r4-a8xx`: [Banner r4](https://github.com/The412Banner/Banners-Turnip/releases/tag/v26.3.0-20261015-r4).
- `turnip-banner-26.3.0-20261015-r5-a8xx`: [Banner r5](https://github.com/The412Banner/Banners-Turnip/releases/tag/v26.3.0-20261015-r5).
- `turnip-banner-26.3.0-20261015-r6-a8xx`: [Banner r6](https://github.com/The412Banner/Banners-Turnip/releases/tag/v26.3.0-20261015-r6).
- `turnip-banner-26.3.0-20261015-r7-a8xx`: [Banner r7](https://github.com/The412Banner/Banners-Turnip/releases/tag/v26.3.0-20261015-r7).
- `turnip-banner-27.0.0-20261016-r3-a8xx`: [Banner r3](https://github.com/The412Banner/Banners-Turnip/releases/tag/v27.0.0-20261016-r3).
- `turnip-banner-27.0.0-20261016-r4-a8xx`: [Banner r4](https://github.com/The412Banner/Banners-Turnip/releases/tag/v27.0.0-20261016-r4).

All six Banner entries use exactly `Turnip-<tag>-A8xx.zip`, the Android KGSL AdrenoTools variant.
They are optional only in `adreno-8xx` and `adreno-84x`. No Linux/glibc, Wayland, standard,
One UI or A710 test packages are added. None of the seven additions is offered to Mali,
Xclipse, PowerVR, unknown GPUs or the unclassified `adreno-other` profile.

The existing profiles cover ranges, not individual driver chip tables: `adreno-8xx` matches
800-839 and `adreno-84x` matches 840-859 or the existing chip wildcard. Offering an experimental
choice in one of these profiles does not establish support for every model or chip revision in
that range. These rules are deliberately unchanged. An unavailable or failed explicit pick
uses the existing automatic fallback order.

## Artifact evidence and limits

The [provenance manifest](../app/src/wehavephones/assets/wehavephones/graphics/turnip-optional-20261016.json)
records the exact release and download URLs, publication times, archive and library SHA-256,
upstream `meta.json`, Mesa revision, ELF build id and chip-table byte offsets for each release.
All seven actual ZIPs were downloaded and checked against GitHub's release-asset SHA-256.
Each contains only `meta.json` and `libvulkan_freedreno.so`. All libraries are little-endian
AArch64 ELF64 Android HAL drivers with an exported `HMI`, Android `libc.so` dependencies and
metadata requiring Android API 28 or newer. These are package compatibility checks, not
successful Vulkan probe or game tests. The catalog descriptions retain the experimental and
unverified status. Picker rows show the driver name and download status.

Rutabaga's metadata calls it `Rutabaga-26.10.10`, package version `1`, built October 10 from
Mesa `b00479181d`. Keep the requested v3 release identity rather than rewriting upstream metadata.
StevenMXZ credits extraction from GameHub and claims A6xx/A7xx/A8xx support. Inspection finds
A6xx/A7xx entries and named A810, A829, A830 and A840 GPU-table records. The A840 record uses
`0xffff44050A31`. No `0x44050A21` or `0xffff44050A21` record was found, so POCO-style A840v2
compatibility is unverified. No A825 `0x44030000` or A812 `0x44010200` record was found either.
The shared A8xx profile offers the package for its A810/A829/A830 entries, without promising
A825/A812 support. The precise GameHub patch set is unpublished. Release claims about
Uncharted, Cyberpunk and Spider-Man are not WoW evidence.

Banner's notes and package metadata list experimental A810/A812/A825/A829/A830/A840 support.
Each inspected library contains corresponding chip-table byte evidence, including both
`0xffff44050A31` and `0xffff44050A21` A840 entries. Byte-table recognition does not prove rendering
correctness. The October 15 revisions identify Mesa `ae2dbaf0da`, `cf98950a3f`, `bf76a9d673`
and `827d538ed9`. Both October 16 revisions identify Mesa `3923c34547` and document the same
whitebelyash Gen8 stack plus WinNative patches 0001-0005. All six archives and libraries have
different SHA-256 values, so each requested release retains its own catalog id. These may be
rebuilds or repackages. No speed, stability or rendering improvement is established between them.

The carried-over WinNative cube-map, bindless-descriptor and command-stream patches could affect
rendering or hangs. The [command-stream patch](https://raw.githubusercontent.com/The412Banner/Banners-Turnip/v27.0.0-20261016-r4/patches/a8xx-winnative/0005-tu-kgsl-Fetch-A8XX-command-streams-through-a-virtual.patch)
describes A8xx memory-release hangs in FINAL FANTASY VII REBIRTH. It does not establish a fix for
WoW stripes or swizzling. None of these seven additions is verified for WoW Retail or OnePlus 15.
Banner's A830 sysmem advice does not change any environment default, including A840 settings.

## Rechecking

`scripts/test-wehavephones-devices.sh` compares with the committed v6 fixture, checks every
profile's complete defaults, existing driver entries and picker order, exercises automatic
fallback and saved selections, and checks exact optional applicability. The existing launch
and runtime host checks cover application of graphics API and environment settings.

To repeat all seven archive, metadata, library, ELF and chip-byte checks, download the exact
manifest URLs into a task directory under `$TMPDIR`, then run:

```bash
python3 tools/wehavephones-devices-selftest/optional_turnip_selftest.py --archives "$TMPDIR/turnip-optional-20261016"
```

Keep downloaded binaries outside git. Sign changed rules with `scripts/sign-device-rules.sh`
and commit `device-rules.json` together with its signature. A catalog rollback must use another
higher rules version and signature so phones can accept it. The Turnip cron is unchanged.
