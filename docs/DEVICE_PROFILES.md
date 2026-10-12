# Device profiles

Phones differ in GPU and Vulkan driver, so the graphics setup is chosen per device from a table of
rules instead of one fixed driver. The table is bundled in the APK and can be updated without a new
release. Only the `wehavephones` flavor uses it; the `runtime` flavor keeps the built-in setup.

## What the rules do

At the start of a session the app matches the phone against the rules and gets a profile. The
profile lists Vulkan drivers in order of preference, extra environment variables, whether the BCn
compat layer is installed, and optionally the WoW graphics API flag.

- Galaxy S23 (Adreno 7xx): the pinned Turnip, as before.
- Pixel 8a and other Mali phones: the phone's own Vulkan driver with the BCn compat layer, as before.
- Adreno 840 and newer (Snapdragon 8 Elite Gen 5): WoW in D3D11 with Turnip V32-RP6sched, downloaded on
  first use, then Gen 8 Turnip V37, then the system driver. This is the setup of the only reported
  in-world WoW run on an Adreno 840.
- Other Adreno 8xx (800-839, such as the 825 in the Snapdragon 8s Gen 4 and the 830 in the Snapdragon
  8 Elite): the same, with the pinned Turnip before the system driver.
- Anything else falls through to a catch-all rule that matches the old behavior.

Each profile has a `status` (`tested`, `community` or `untested`) so Diagnostics and issue reports
show how much a setup has been run on real hardware.

## Files

| File | Purpose |
| --- | --- |
| `app/src/wehavephones/assets/wehavephones/device-rules.json` | The rules |
| `app/src/wehavephones/assets/wehavephones/device-rules.json.sig` | Base64 ECDSA P-256 / SHA-256 signature over the exact file bytes |
| `app/src/wehavephones/assets/wehavephones/graphics/*.json` | Provenance manifest for each bundled driver |
| `app/src/main/assets/graphics_driver/adrenotools-<id>.tzst` | Bundled driver archives |
| `app/src/wehavephones/java/com/wehavephones/mobile/devices/` | Parser, matcher, store, selector |

## Schema

```json
{
  "schema": 1, "rulesVersion": 1, "minAppVersionCode": 1, "updatedAt": "2026-10-10",
  "drivers":  { "<id>": { "type": "system" | "adrenotools", "asset": "...", "url": null,
                          "sha256": "...", "libraryName": "...", "librarySha256": "...", "source": "..." } },
  "profiles": { "<name>": { "status": "tested", "drivers": ["<id>", "system"], "env": {},
                            "vulkanCompatLayer": false, "wowApiArg": null, "notes": "..." } },
  "rules":    [ { "id": "...", "match": { }, "profile": "<name>", "source": "..." } ]
}
```

- Rules are checked in file order; the first rule whose keys all match wins. The last rule must be
  a catch-all with `"match": {}`.
- `match` keys are all optional and AND-ed; list values are OR-ed: `gpuVendor` (`adreno`, `mali`,
  `xclipse`, `powervr`, `other`), `adrenoSeries` (`[8]`), `adrenoModel` (`"840"` or `"840-849"`),
  `chipId` (`"0x44050AFF"`, where a byte `FF` is a wildcard), `socModel`, `manufacturer`, `minSdk`,
  `maxSdk`. A chip id the phone does not report never matches.
- An `adrenotools` driver needs an `asset` in the APK, or a `url` plus `sha256` (archive) and
  `librarySha256`. Archives are `.tzst` or adrenotools `.zip`. Downloaded drivers are installed only
  after both hashes match.
- `env` is applied only for keys the container does not already set. The default
  `TU_DEBUG` logic still runs afterwards (`noconform`, always plus `sysmem`).
- `wowApiArg: null` keeps `-d3d12` in `wow.bat` and the direct launch; a profile may set `-d3d11`.
- Unknown keys are ignored; a newer `schema` than the app supports is rejected.

## Selection

1. Load the rules: the verified remote copy if it is newer than the bundled one, else the bundled one.
2. Detect the phone: GL renderer (vendor and Adreno model), KGSL chip id and GPU model, `Build.*`.
3. Match the first rule and take its profile.
4. For each driver in the profile, in order: skip it if it is not installed and cannot be installed
   or downloaded, then test-load it with a Vulkan instance and device creation. The first driver
   that loads wins. If none does, the system driver is used.
5. The plan (driver, environment, compat layer) is applied to the session and saved for Diagnostics.

Probe results are cached per driver, driver hash, `Build.FINGERPRINT` and app version code, so the
test runs once per driver and again after a system or app update. Before each probe the app records
that it is running; if the process dies inside the driver, the next start sees the record, marks
that driver as failed ("crashed during probe") and moves on to the next one. Decisions are logged
under the `WeHavePhonesDevices` logcat tag.

## Updates

On startup the app fetches `device-rules.json` and its `.sig` from
`https://raw.githubusercontent.com/ashmerra/wehavephones/main/` at most every six hours, and
Diagnostics has a "Check for device updates" button. A remote file is used only when its signature
verifies against the public key built into the APK (`DeviceRulesKey.java`), its schema is
supported, `minAppVersionCode` is not above the installed app, and `rulesVersion` is higher than
the bundled and the cached copy. Otherwise the app keeps what it has. Drivers with a `url` are
downloaded in the background for this phone's profile only.

## Adding a device or a driver

1. A new driver: add the archive as `app/src/main/assets/graphics_driver/adrenotools-<id>.tzst` and a
   manifest under `graphics/`, or give the driver entry a `url` with `sha256` and `librarySha256`.
   Record the source in `source` and in `THIRD_PARTY_NOTICES.md`. Drivers with only a `url` reach
   phones through a rules update; drivers that ship as assets need a new APK.
2. Add or edit the profile and a rule that matches the phone (prefer GPU keys over `socModel`).
3. Bump `rulesVersion` (and `updatedAt`). `minAppVersionCode` should name the first app build that
   can use everything in the file.
4. `scripts/sign-device-rules.sh` validates the file and rewrites `device-rules.json.sig`.
   It refuses to sign changed rules without a higher `rulesVersion`.
5. `scripts/test-wehavephones-devices.sh` checks the rules, bundled driver hashes, the signature,
   selection, fallback, and that the S23 and Pixel 8a still get the same driver and environment.
6. Commit `device-rules.json` and `device-rules.json.sig` together and push `main`; phones pick
   them up on their next check. Test the profile on the phone first when possible and keep the
   status `community` or `untested` until it has been.

## Reports

Diagnostics has a Device section and a "Report a problem" button that opens a prefilled GitHub
issue with the device report block (phone model, GPU, rule, profile, driver and probe results; no
accounts or serial numbers). [DEVICE_REPORTS.md](DEVICE_REPORTS.md) describes the block, how
`scripts/device-reports.py` groups issues by GPU family, and what the issue-filing bot should
include. Several reports for one GPU family are the signal to add or change a profile.

## Key custody

The private signing key lives outside the repository at
`~/.config/wehavephones/device-rules-signing-key.pem` (or `WEHAVEPHONES_RULES_KEY`). Never commit,
print or share it, and keep an offline backup: without it no rules update can be signed, and the
public key in `DeviceRulesKey.java` (so a new APK) would have to be replaced. Create a key with
`scripts/generate-device-rules-key.sh`, which prints only the public half.
