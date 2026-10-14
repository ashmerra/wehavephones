# Build assets

The APK packages large prebuilt archives that git does not track.
[`scripts/large-assets.json`](../scripts/large-assets.json) pins each one by size
and SHA-256, and `scripts/fetch-large-assets.sh` (run by the build scripts)
makes sure every file is present and matches. A clean clone therefore builds
with the same bytes as our test builds, or fails with a clear error.

Not covered here: the GE-Proton layer (`scripts/fetch-wehavephones-wine.py`,
pinned in the runtime profiles) and ConsolePort (`scripts/fetch-consoleport.sh`,
pinned in `consoleport.json`).

## Where a file comes from

The fetcher takes each asset from the first place whose bytes match:

1. **The local file.** A file that does not match stops the build.
   `--replace` moves it aside (`<name>.mismatched-<sha>`) and fetches the pinned
   copy.
2. **Our release** (`release.repo` and `release.tag` in the manifest), for the
   groups listed in `release.groups`. `WEHAVEPHONES_ASSETS_BASE_URL` points the
   fetcher at any mirror that uses the same file names.
3. **The recorded upstream:** a direct download, or one member of an upstream
   archive. Archives are cached in `out/_cache/large-assets/`, which is safe to
   delete (the FrontMage APK alone is 695 MiB).

| File | Group | Upstream |
|---|---|---|
| `imagefs.txz` | apk | Only inside FrontMage/winlator-llm's APK release `llm-apk-20260216-144633` |
| `dxwrapper/dxvk-2.3.1-arm64ec-gplasync.tzst` (default DXVK) | apk | The same APK |
| `container_pattern_common.tzst` | apk | StevenMXZ/Winlator-Ludashi, commit `212447d` |
| `imagefs_patches.tzst` | apk | FrontMage `imagefs-v1` (that bundle's own `imagefs.txz` is a different build we do not use) |
| `dxwrapper/dxvk-2.4.1.tzst`, `dxwrapper/vkd3d-2.12.tzst` | apk | FrontMage `dx-wrapper-v1` |
| `graphics_driver/turnip-24.1.0.tzst`, `virgl-23.1.9.tzst`, `zink-22.2.5.tzst` | apk | FrontMage `drivers-v1` |
| `wincomponents/direct3d.tzst` (Microsoft) | apk | FrontMage `wincomponents-v1`. Identical to the copy upstream Winlator commits |
| `preinstall/proton-10-arm64ec.wcp.xz` | runtime | StevenMXZ/Winlator-Contents release `1.0`. `runtime` flavor only |

The default DXVK's aarch64 DLLs (its `system32/`) were built with per-frame
`SWAPTRACE` debug logging. The app extracts
`dxwrapper/aarch64/dxvk-2.3.1.tzst` over them: a clean DXVK v2.3.1 build,
tracked in git and made with
`scripts/build-arm-graphics-dlls-docker.sh --skip-vkd3d --package-dxvk`.

## Commands

```bash
scripts/fetch-large-assets.sh                # apk group (build-wehavephones.sh)
scripts/fetch-large-assets.sh apk runtime    # build-apk.sh
python3 scripts/large-assets.py check all    # verify without network access
python3 scripts/large-assets.py stage        # prepare our release upload
scripts/test-wehavephones-assets.sh          # host self-test
```

`--root DIR` (before the command) fetches into another checkout, for example
an empty directory to test what a clean clone gets.

## Publishing our release

Do this only after the public repository exists **and is public**. Assets on
a private repository need a login, and the fetcher downloads without one.

1. Stage the upload. It hard-links the `apk` assets into
   `out/release-assets/assets-v1/` and writes `SHA256SUMS` and the release
   notes (`out/release-assets/assets-v1.md`):

   ```bash
   python3 scripts/large-assets.py stage
   ```

2. Publish, using the command `stage` printed with the real repository:

   ```bash
   gh release create assets-v1 --repo ashmerra/wehavephones --title "Build assets v1" \
     --notes-file out/release-assets/assets-v1.md out/release-assets/assets-v1/*
   ```

3. Prove the release alone is complete, in an empty directory:

   ```bash
   rm -rf /tmp/wh-assets-check && mkdir /tmp/wh-assets-check
   WEHAVEPHONES_ASSETS_BASE_URL=https://github.com/ashmerra/wehavephones/releases/download/assets-v1 \
     python3 scripts/large-assets.py --root /tmp/wh-assets-check fetch --release-only
   ```

4. Check that `release` in `scripts/large-assets.json` has
   `"repo": "ashmerra/wehavephones"` and the new tag, run
   `scripts/test-wehavephones-assets.sh`, and commit any change.
5. Repeat step 3 without `WEHAVEPHONES_ASSETS_BASE_URL`.

## Changing an asset

Never replace or delete a file in a published tag: older commits pin its hash
and would stop building. Instead:

1. Put the new file in place and update its `size` and `sha256` in the
   manifest. Update `upstream`, or remove it if the file only exists in our
   release (for example, an archive we build ourselves).
2. Bump `release.tag` (`assets-v2`), then stage, publish and verify as above.
3. Update the origin in [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).

## Release builds

`scripts/build-wehavephones.sh --release` builds the signed `com.wehavephones`
APK. It needs `WEHAVEPHONES_KEYSTORE`, `WEHAVEPHONES_KEYSTORE_PASS`,
`WEHAVEPHONES_KEY_ALIAS` and `WEHAVEPHONES_KEY_PASS` in the environment or in
`~/.config/wehavephones/signing.env` (`KEY=value` lines, or another file named by
`WEHAVEPHONES_SIGNING_ENV`), and writes `out/release/WeHavePhones-<version>.apk` plus
`SHA256SUMS`. Keep the keystore and that file outside the repository.
