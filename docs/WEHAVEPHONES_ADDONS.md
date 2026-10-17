# WeHavePhones — addon manager

This document describes how the addon manager is implemented and where. The app
architecture is in `docs/WEHAVEPHONES_APP.md`. What still needs on-device
validation is listed in section 7.

All code is in the `wehavephones` flavor source set:
`app/src/wehavephones/java/com/wehavephones/mobile/addons/`.

## 1. Providers

| Provider | Key | Endpoints used | Notes / caveats |
|---|---|---|---|
| CurseForge | user API key (required) | `api.curseforge.com/v1/mods/search`, `/v1/mods/{id}`, `/v1/mods/{id}/files`, `/files/{fileId}/download-url`, `/files/{fileId}/changelog`, `/v1/games/1` (test key) | `x-api-key` is sent only to `api.curseforge.com` and `*.forgecdn.net`. SHA-1 from `hashes[]` is verified after download. `downloadUrl` null (author-restricted files) falls back to the `download-url` endpoint. The key was never tested live (no key available). The 403 auth gate was confirmed live. |
| GitHub releases | optional PAT (raises the 60/hr anonymous limit) | `/repos/{owner}/{repo}/releases/latest`, `/releases`, `/repos/{owner}/{repo}` (metadata), `/search/repositories` (search), `/rate_limit` (test) | External id is `owner/repo`. Asset selection follows the BigWigs packager rules, including `release.json` flavor→asset mapping. `browser_download_url` is preferred over the API asset `url`. The PAT is sent only to `api.github.com`. Live shapes for DBM were checked on 2026-09-25. |
| Tukui | none | `/v1/addons` (5 min cache), `/v1/addon/{slug}`, `/v1/changelog/{slug}` | Only ElvUI/Tukui exist. The download token in `addon.url` is refreshed with a detail call before download. |
| Wago | Bearer token (optional, paid tier) | `addons.wago.io/api/external/addons/_search`, `/addons/popular`, `/addons/{id}`, `/addons/_recents` (batch), `/api/data/game` (no auth, not used at runtime yet) | Best-effort: the external read API is semi-private. Download link gets `?token=`, which is stripped automatically if a redirect leaves `*.wago.io`. Unsupported flavors fail with a typed `UNSUPPORTED` error. |
| Local ZIP | n/a | `ACTION_OPEN_DOCUMENT` | Safe extraction + discovery only. Never searched. TOC `X-…-ID` values are stored so a later update check can use the binding. |

Only GitHub releases and Local ZIP are offered in the app for now
(`ProviderType.offered`). CurseForge, Tukui and Wago stay in the code but are
not registered, searchable, configurable or used for update checks until they
are tested live. Addons whose TOC names one of them show as local.

The registry (`ProviderRegistry`) rebuilds from the Android Keystore-backed key
store (`AddonSecrets` → `SecretStore` generic secret methods). Keys
are never stored in the JSON index and never logged.

## 2. Class map

```
addons/
├── AddonManager.java          activity-scoped orchestrator (products, scan, install, update, delete)
├── AddonPrefs.java            selected product (shared with Home/Play) / provider + last-check timestamp
├── AddonRepository.java       process-wide bridge (InstalledAddon snapshots)
├── InstalledAddon.java        view model
├── AddonsActivity.java        host: product spinner + status/progress + fragment screens
├── (../WowFlavor.java)       app-wide game catalog: flavor dirs, CF type ids, Wago keys, TOC suffix
│                              priority, interface map, Forever detection (docs/WEHAVEPHONES_APP.md §3a)
├── model/
│   ├── TocData.java           parsed TOC + provider identity
│   ├── AddonRecord.java       persisted logical addon (one per project+flavor)
│   ├── RemoteAddon/RemoteFile/Channel/InstallStatus/ProviderType
├── scan/
│   ├── TocParser.java         TOC parsing + multi-TOC selection
│   ├── VersionComparator.java tolerant version compare (1.10 > 1.9, v-prefix, letter suffix)
│   └── AddonScanner.java      folder walk + identity grouping + index merge
├── install/
│   ├── SafeZipExtractor.java  commons-compress ZipFile + security checks (central directory, so symlinks are detected)
│   ├── ZipImporter.java       addon-root discovery (wrapper dirs, Interface/AddOns, version fallback)
│   ├── AddonInstaller.java    backup → swap → rollback → commit; disable/enable; delete backup; crash recovery
│   └── FileOps.java           pure-Java file helpers
├── provider/                  AddonProvider interface, registry, CF/GitHub/Tukui/Wago, secrets, URL builders
├── net/                       Http (HttpURLConnection), Downloader, RateLimiter
├── repo/                      AddonIndex + hand-rolled Json codec (host-testable) + AddonIndexStore (atomic file IO)
├── update/UpdateChecker.java  grouped batch update check (per provider+channel)
├── util/GameGuard.java        /proc-based WoW-running guard (ProcessScanner)
└── ui/                        installed list / details / browse / addon apps / settings fragments + AddonUi labels
```

The screen follows the main screen's pattern. A navy rail on the left holds
the game picker, four tabs (**Installed**, **Browse**, **Addon apps**,
**Settings**) and the shared status line. The Installed tab badge counts
addons with an update. The LCD panel shows the tab:

- **Installed:** one row per addon with an enable switch, version and source,
  and one status or an Update button. The source names the provider this app
  installed the addon from. When the app has no record, it names the catalog
  the TOC declares (`X-Curse-Project-ID`, `X-Wago-ID`, `X-Tukui-Project-ID`,
  `X-WoWI-ID`), so an addon another manager installed shows as CurseForge,
  Wago, Tukui or WoWInterface. A catalog the app does not offer shows as
  **Not tracked** with no Update button. Only an addon with no catalog id and
  no history reads as Local ZIP. Updates sort first. ConsolePort and its
  setup addon sit in their own group without Delete. Update all appears when
  there are updates. Check for updates, Rescan folder and Import ZIP are in the
  ⋯ menu. Tapping a row opens **Details** (facts, changelog, Update, Website,
  Delete).
- **Browse:** search plus Import ZIP. The provider picker shows only once more
  than one remote provider is offered. A result row opens the project page.
- **Addon apps:** see section 10.
- **Settings:** update preferences (saved on change), the AddOns folder with
  Copy full path, one row per offered provider key, and ConsolePort.

## 3. Flows

### Install / update (atomic)
1. `GameGuard` blocks the operation while a WoW process is running.
2. Download to `files/wehavephones/addons-tmp/<uuid>.zip` with a 512 MiB cap.
3. Verify provider SHA-1 when published (CurseForge). Always compute SHA-256.
4. Extract to `<AddOns>/.wehavephones-staging-<uuid>/` after the security checks.
5. Discover addon roots (folder with a matching TOC), then `AddonInstaller`:
   backs up existing folders (including `.disabled` variants) to
   `.wehavephones-backup-<uuid>/`, deletes them, moves staged folders into place,
   re-applies the disabled rename when updating a disabled addon, and rolls
   back from the backup on any failure. `commit()` deletes the backup.
6. Stale folders recorded from the previous version are removed by the same
   `targets` set (renamed-addon cleanup), then the index is re-scanned/saved.

### Update check
`UpdateChecker` groups records by provider+channel, batch-queries each provider,
and sets `UP_TO_DATE` / `UPDATE` / `UNKNOWN`. `UPDATE` is set when the remote
version is newer **or** the remote interface advertised in `gameVersions`
(`patch` for Tukui) is below the client's interface. One provider failure keeps
previous statuses and stores a per-record `lastError`. Auto-check on list open
is throttled to 15 minutes and can be disabled in settings.

### Enable / disable
Rename each folder to `<folder>.disabled` (and back). Callers may pass either
form of the name, since a scanned disabled record holds the physical
`<folder>.disabled` name. The game-closed guard applies. Disabled state is
stored in the index and re-applied after updates.

### Delete
Folders are moved into `.wehavephones-deleted-<uuid>/` (not deleted) and the record
is removed. The backup is kept until the next successful install/delete
operation, which cleans all older delete-backups.

### Local ZIP import
`ACTION_OPEN_DOCUMENT` → copy with size cap → safe extract → discover → the UI
shows a multi-choice confirmation with name/version/inferred provider identity →
selected folders are installed, the index is rebound from TOC `X-…-ID` values
when present (otherwise `LOCAL_ONLY`).

### External rewrite
An addon manager run in the container (CurseForge, WowUp) writes into
`Interface/AddOns` directly. On the next scan the app keeps the provider it
recorded when it installed the addon, so its update tracking survives. If that
folder was written after the recorded install time (its TOC then names a
catalog), the app treats the addon as handed over: it adopts the TOC catalog,
drops the previous provider's update metadata, and shows **Not tracked**. A
folder with no TOC catalog keeps the recorded source, since there is nothing
better to adopt.

## 4. Persistence

- `<filesDir>/wehavephones/addons.json` (atomic `.tmp` + fsync + rename). A corrupt
  file is preserved as `addons.json.bad-<ts>` and rebuilt from a scan.
- Stores provider ids, external ids, release ids, installed/latest versions,
  folders, interface list, source URL, SHA-256, status, timestamps. Changelogs
  are capped at 20 KB. No secrets.
- Provider keys/tokens live in `wehavephones_secrets` SharedPreferences encrypted
  with an Android Keystore key (`SecretStore.putSecret/getSecret`).

## 5. Networking rules

`Http` / `Downloader` use `HttpURLConnection` only: 15 s connect / 30 s read
timeouts, manual redirect chain (max 5, HTTPS only), gzip for API text, size
caps (4 MiB JSON, 1 MiB text, 512 MiB download), one request/second per host,
and retry with `Retry-After`/exponential backoff for 429/5xx. Secret headers are
dropped on every redirect to a host outside the provider allow-list, and a
`token=` query parameter is stripped on cross-host redirects.

## 6. Host self-test

```bash
scripts/test-wehavephones-addons.sh
```

Compiles the pure logic subset (models, TOC parser, version comparator,
scanner, safe extractor, importer, installer, URL builders, index codec) plus
`tools/wehavephones-addons-selftest/AddonLogicSelfTest.java` with the
`commons-compress` jar from the Gradle cache (override with
`COMMONS_COMPRESS_JAR=/path/to/commons-compress-1.20.jar`), then runs its
assertions: multi-TOC selection per flavor, comma interface lists, dependency
aliases, placeholder versions, version comparisons, provider URL queries,
GitHub asset selection, index JSON round-trip, valid ZIP extraction/discovery,
zip-slip/absolute/backslash/drive/symlink/duplicate-case/ratio-bomb rejections,
wrapper + `Interface/AddOns` discovery, atomic install/commit, disabled-update
re-application, crash recovery, delete backup, and scanner grouping/statuses.
Exit code is non-zero on any failure. No Gradle test dependency was added.

## 7. Needs on-device validation

- `AddOns` paths inside the real Wine container (symlinked `home/xuser`,
  `Program Files (x86)` case) and that the game sees the same tree.
- Live provider calls with a real CurseForge key (search/download/`x-api-key`
  on `*.forgecdn.net`) and a Wago token. Endpoint shapes may drift.
- ZIP import through `ACTION_OPEN_DOCUMENT` on the device (content resolver
  read, file size, dialog flow).
- Enable/disable renames while the game is genuinely closed. Verify the guard
  against a running `Wow*.exe`.
- Install/update of a real addon (e.g. DBM from GitHub, ElvUI from Tukui) and
  rollback behaviour under a mid-install failure (e.g. disk full).
- Auto-check on open, update-all with many addons, and cancellation when the
  activity is destroyed mid-download.
- Root override: `AddonIndex.wowRootOverride` is persisted but has no UI field
  yet. The manager supports it. Interface numbers use `.build.info` first and
  the static table as fallback.

## 8. Known limitations

- No CurseForge key was available to test live. The API calls are built from the
  documented schema and the live 403 auth gate.
- Wago remains best-effort (paid key, semi-private API). The batch `_recents`
  shape is implemented defensively but untested.
- Fingerprint matching (`X-…-ID` absent) is not implemented. Unidentified
  addons are `LOCAL_ONLY`/`UNKNOWN` and are never silently rebound.
- A catalog the app does not offer yet (CurseForge, Tukui, Wago, WoWInterface)
  labels an addon and its project id in the list and details, but the status
  stays `Not tracked` with no Update button. This is how addons installed by
  another manager (CurseForge, WowUp) keep their origin. Addons whose TOC
  carries no `X-…-ID` still read as Local ZIP.
- Handover detection compares the folder's modification time with the recorded
  install time. An external manager that rewrites only file contents without
  touching the folder's entries, or an addon whose TOC declares no catalog, is
  not detected and keeps its recorded source.
- No download resume, no Wi-Fi-only setting, no background service (installs
  run in a visible activity-scoped executor), no SavedVariables cleanup
  (SavedVariables are outside `Interface/AddOns` and untouched).
- The TOC `Interface:` list is displayed and compared for status, but the
  flavor's product version from `.build.info` is the primary interface source.

## 9. ConsolePort (required controller addon)

ConsolePort (`seblindfors/ConsolePort`, Artistic-2.0, notice in
`docs/licenses/ConsolePort-Artistic-2.0.txt`) is kept installed in every WoW
product except the Forever family, which has native controller support
(`ConsolePort.requiredFor`. A Forever client is detected by its 1.60+ version
whatever its directory is called). One release zip serves every flavor.

- **Bundled release:** `app/src/wehavephones/assets/wehavephones/addons/consoleport.json`
  pins version, URL and SHA-256 and is committed. `ConsolePort.zip` beside it
  is gitignored and downloaded/verified by `scripts/fetch-consoleport.sh`, which
  `scripts/build-wehavephones.sh` runs. Bump the pin with
  `scripts/fetch-consoleport.sh --latest`.
- **Update in the app:** `addons/required/ConsolePortUpdater` queries GitHub
  releases (anonymous, or with the saved GitHub PAT) at app start, at most
  every 6 hours. Newer releases go to `files/wehavephones/required-addons/`
  (state in `consoleport.json` there). The newest of the bundled and
  downloaded archives wins, and older archives are pruned. Add-ons → Settings →
  "Check for update now" forces a check.
- **Install:** `ensureInstalled` runs at app start and before every Play.
  It installs into each non-Forever product whose ConsolePort is missing or
  older, using the atomic installer (modules dropped upstream are removed). It
  never downgrades, keeps a user-disabled suite disabled, skips while the game
  runs, and never blocks the launch. If an APK was built without the archive,
  it falls back to downloading once.
- **Addon manager:** the scanner binds the suite to GitHub instead of the TOC's
  CurseForge project (no API key needed), so the normal Update button works.
  Delete is hidden or blocked for required installs. Disable still works.
  Writes to the AddOns directory from the manager and the updater are
  serialized by `AddonInstaller.LOCK`.
- **First-run layout:** `WeHavePhonesControllerSetup`
  (`assets/wehavephones/addons/WeHavePhonesControllerSetup/`, synced by
  `ControllerSetupAddon` on every ConsolePort pass) is installed next to the
  suite. Its TOC `Interface` line is copied from the installed ConsolePort,
  and a user-disabled copy is left alone. At the first login without an
  active ConsolePort device, it does what the setup guide's "recommended"
  choices do. It picks the device preset by vendor ID (Sony → PlayStation
  4/5, Nintendo → Switch Pro, Valve → Steam Deck, otherwise Xbox) and sets
  LB = Shift, LT = Ctrl, stick clicks as left/right mouse buttons, and
  balanced movement (115°/115°). After the binding set loads, it applies the
  device's preset bindings (`db.Bindings:LoadPreset`) unless the account
  already has 8 or more gamepad bindings. It also closes the guide. No
  community template beats these presets: shared layouts are
  per-player/per-class strings, and the built-in presets are what the
  addon's own guide applies. Installs with an active device are never
  touched. The folder name must not start with `ConsolePort_`, because
  suite updates delete such folders.
- **Host self-test:** `testConsolePort` in the addon self-test also installs
  the real bundled archive when it is present. `testControllerSetupAddon`
  covers the companion sync.

## 10. Addon managers' own apps (no API key)

Add-ons → **Addon apps** runs the CurseForge and WowUp addon managers'
own Windows apps in the game's container. They find the WoW installs and
install, update and remove addons themselves, so the app needs no API key.
CurseForge covers its whole catalog (guest use works). WowUp's Wago edition
adds Wago (through its ad-supported partnership), WoWInterface, Tukui and
GitHub. Code: `AddonApp` (feeds, batch file, settings and patches, covered by
the launch self-test) and `AddonAppLauncher` (Android side).

Both are x64 Electron apps packaged by electron-builder, so they share one
install and start path:

- **Download:** each app's electron-updater `latest.yml` feed gives the
  version, size and SHA-512 of the full installer. CurseForge's comes from
  `electron-updates.overwolf.com` (its official web installer is a .NET
  bootstrapper that needs Wine Mono and WebView2). WowUp's is the
  `WowUp/WowUp` GitHub release, pinned to the release tag. Downloads must be
  HTTPS from the app's own host, and nothing is bundled.
- **Install:** `wehavephones-<app>.bat` runs the installer with `/S` (per
  user, `%LOCALAPPDATA%\Programs\CurseForge` or `\wowup`). The installer asks
  PowerShell whether the app is running, and Wine's PowerShell stub always
  answers yes, so the batch file moves both `powershell.exe` stubs aside for
  the install and restores them (also at the start of the next run if a
  session was interrupted). The installed version goes to
  `C:\windows\wehavephones-<app>\installed-version.txt`. A newer feed version
  is installed on the next open, but only over a release this app installed.
- **Addon apps tab:** one row per app with its state and one button (Open,
  Download and open, or Update and open). The tab reads the feed to show the
  download size, and reuses a release seen in the last ten minutes. Open is
  the only update path, so there is no separate update button.
- **Start:** the container's `dxgi.dll`/`d3d11.dll` are ARM64-only builds for
  the native ARM64 game, which x64 apps cannot load (status `c000007b`), and
  Chromium aborts without `CreateDXGIFactory1`. Wine's own ARM64X builds are
  staged and copied next to the app before each start. Chromium runs with
  `--no-sandbox --disable-gpu`. CurseForge's .NET mods agent
  (`Curse.Agent.Host.exe`) crashes with `ExecutionEngineException` under
  emulation unless `DOTNET_EnableWriteXorExecute=0` is set.
- **WowUp patches:** before each start the batch file runs
  `container-fixes.js` with WowUp's own Electron as Node (its RunAsNode fuse
  is on, asar integrity checking is off). It makes three same-length edits to
  `app.asar`: no tray icon (updating it hangs this Wine build's taskbar
  inside explorer's `WM_COPYDATA` handler, so the window never appeared), the
  ARM64 game executable names (the x64 app only looks for `WowClassic.exe`
  and similar), and drive C for Battle.net's `product.db` (its disk listing
  finds no drives under Wine). The edits are idempotent and reapplied after
  updates. WowUp is GPL-3.0, and the changes stay on the device.
- **Settings:** before each start the launcher sets the app to exit when
  closed instead of hiding in the tray (CurseForge `storage.json` →
  `general-settings`, WowUp `preferences.json` → `collapse_to_tray`), turns
  off CurseForge's start with Windows and removes its `Run` entry, and turns
  off WowUp's hardware acceleration. Hidden in the background an app would
  keep using memory and changing AddOns after the player goes back to play.
- **Game detection:** `WowUp.exe` matches the `Wow*.exe` game pattern, so
  `WowFlavor.isGameExecutable` excludes it. Without that, a running WowUp
  counted as the game (virtual gamepad, add-on manager and launch guards).
- **Session:** it refuses while WoW runs. A running session gets the batch
  file through WinHandler. Otherwise a new session starts with the Battle.net
  runtime, the stability FEX preset and a 1280x800 desktop. Free memory on
  the S23 dropped from about 2.5 GB to 0.6 to 1 GB while an app ran.
- **Validated** on the S23 (2026-10-15). CurseForge: fresh download and
  install, guest use, Classic Era detected with its addons, search and
  install of an addon, exit on close. WowUp: fresh download and install,
  start past the tray, all installed games found through `product.db`, Wago
  token received. Not yet validated: an addon install through WowUp, the
  in-session WinHandler path, an update to a newer feed version, and other
  phones.
- **Why WowUp is unreliable and heavy on the phone** (S23, 2026-10-16, WowUp
  2.24.0). WowUp is an x64 Electron app, so every Chromium process runs
  through FEX emulation inside the session. Measured cost while running:
  6-9 processes (main, GPU, utility, network, plus the consent window and the
  Wago ad view), about 1.6 GB summed RSS, MemAvailable falling from
  1.7-2.0 GB to 0.7-0.9 GB, and the FEX overlay reporting 9-74 FPS with
  frame times from 13.5 ms up to 252 ms. That is why it feels sluggish and
  why it competes with the game for memory.
  The session also ends on its own, after 25 s to 10 min in testing. The
  cause is the memory pressure itself, not a WowUp crash: Android's
  low-memory killer crosses its watermark and kills the app process while it
  is the foreground app (`ActivityManager_kpm: calculateLmkdStatus currentProcess
  Killed com.wehavephones.dev_0`, `WINDOW DIED ...XServerDisplayActivity`,
  `lmkd: Reclaim ... reason: min2x watermark is breached`), which takes the
  whole Wine session with it. The longer runs match the boot benchmarking
  rule: the phone needs free memory for the session to survive.
- **Why searching in WowUp misbehaves.** The search needs a Wago API token
  that only the ad view hands over. The consent window
  (`addons.wago.io/app-cmp.html`) must open and close first, then the ad page
  (`addons.wago.io/wowup_ad_nitro` plus its `preload/wago.js`) serves the
  token, which arrives 3-9 s after launch and is logged as
  `[wago] onWagoTokenReceived`. A search before that, or while the ad page
  fails to load, returns nothing: WowUp waits up to 10 s for the token and
  then continues with an empty bearer token. Ad subresources inside that
  page already fail under the Wine network stack
  (`us-u.openx.net`, `tpc.googlesyndication.com` and `imasdk.googleapis.com`
  all fail with `ERR_SOCKET_NOT_CONNECTED`), so the page is slow to hand the
  token over and can retry. Once a request fails, WowUp opens a 30 s
  circuit breaker and a 10 s HTTP timeout applies to every call, so one slow
  call makes the next searches fail as well. Updates for an empty addon list
  stay on their `Loading...` spinner for the life of the session.
  What helps in practice: wait for the token line, keep searches to one
  word, and give the phone free memory. For catalog browsing and installs,
  CurseForge (search validated) or the app's own GitHub-releases provider
  is both lighter and more reliable on the phone.
- **Limits:** the empty Wine desktop stays after an app closes (use the
  session menu's Exit, as after Battle.net). The apps' own updaters cannot
  install updates in the container for the same PowerShell reason, so updates
  come from the launcher.
