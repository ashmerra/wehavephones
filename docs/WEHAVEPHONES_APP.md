# WeHavePhones — app architecture and runtime contract

The app ships the verified single-runtime Proton 10.0-34 artifacts. This
document describes the `wehavephones` product flavor, the dedicated
"WeHavePhones" UI, how the runtime profile is applied natively in Java, and the
extension points. The runtime and the patched ntdll are described in
`docs/WEHAVEPHONES_RUNTIME.md`. The addon manager in
`docs/WEHAVEPHONES_ADDONS.md`.

## 1. Independent app identity

The release build uses `applicationId com.wehavephones` (app name "WeHavePhones")
and installs alongside upstream Winlator builds. Android gives every
application ID its own private data. The debug build type adds the suffix `.dev`
to the application ID and `-dev` to the version name, so a debug build is a
separate app, `com.wehavephones.dev` ("WeHavePhones Dev"), that installs next to
a release build. Neither build sees the other's game files, containers or Wine
prefixes. Use fresh setup or explicitly export and import a container. The
deployment helper never uninstalls or clears data, and updates signed with the
same key use `adb install -r`, which preserves the app's data.

The launcher is `com.wehavephones.mobile.WeHavePhonesActivity`. Shared engine
Java packages and JNI bindings use `com.wehavephones`. The inherited tools
flavor is `runtime`, with application ID `com.wehavephones.runtime`
(`com.wehavephones.runtime.dev` for debug builds) and its own
launcher label. Upstream credits and runtime file-format contracts remain.
See [the production brand](design/brand/README.md) for the approved
blue flip-phone logo direction.

## 2. Build flavors and manifests

`app/build.gradle` sets the version (`versionCode`, `versionName`), builds only
`arm64-v8a`, and adds one flavor dimension plus the two build types:

```groovy
buildTypes {
    debug { applicationIdSuffix '.dev'; versionNameSuffix '-dev' }
    release { minifyEnabled false }  // signing is injected by build-wehavephones.sh --release
}
flavorDimensions 'vendor'
productFlavors {
    runtime { dimension 'vendor'; applicationIdSuffix '.runtime' }
    wehavephones {
        dimension 'vendor'
        applicationId 'com.wehavephones'
    }
}
```

Manifest split (the engine components stay in `src/main`):

| File | Contents |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | all engine activities/components. `MainActivity` declared without an intent filter |
| `app/src/runtime/AndroidManifest.xml` | `MainActivity` LAUNCHER intent filter |
| `app/src/wehavephones/AndroidManifest.xml` | `WeHavePhonesActivity` LAUNCHER intent filter + `AddonsActivity` |
| `app/src/wehavephonesDebug/AndroidManifest.xml` | the ADB-only `<instrumentation>` entries (`${applicationId}` target). Not in release builds |

Source sets:

- `app/src/wehavephones/java/com/wehavephones/mobile/` — WeHavePhones UI and runtime code.
- `app/src/wehavephones/java/com/wehavephones/mobile/addons/` — the addon manager
  (`docs/WEHAVEPHONES_ADDONS.md`).
- `app/src/wehavephones/assets/wehavephones/` — the bundled runtime profile JSON,
  the patched ntdll pair, the ARM64 DLLs, the launch table, the graphics driver
  manifest and the controller addons.
- `app/src/wehavephones/res/` — the flip-phone theme (navy, silver and green),
  layouts, strings and the Tiny5 font. The flavor overrides `app_name` with
  `WeHavePhones`. `app/src/wehavephonesDebug/res/` overrides it with
  `WeHavePhones Dev` for debug builds.

## 3. Screens

`WeHavePhonesActivity` is a single host activity with fragment screens, locked to
landscape (`sensorLandscape`, like the game session and `AddonsActivity`) and
full screen. A 172 dp navy rail on the left carries the flip-phone mark with the
WeHavePhones logotype and the sections **Play**, **Add-ons**, **Setup** and
**Settings**, with **Containers** and **Diagnostics** set apart at the bottom as
tools. Every entry is a key on the rail and the current section is lit as a
raised blue key. Every rail entry replaces the screen on top of Play so Back
always returns to Play. Screens use two columns so they fit the short axis
(~390 dp on the S23). A line under the screen carries the disclaimer
"Unofficial. Not affiliated with Blizzard Entertainment, Inc."

The look is the approved blue flip phone (see
[the production brand](design/brand/README.md)): early mobile hardware, with a
navy shell (`wehavephones_background`) and a silver bezel
(`wehavephones_lcd_frame`) around a green LCD that holds every screen.
`WeHavePhonesAtmosphereView` draws that LCD, a still green gradient with faint
scan lines, and nothing animates. Primary actions (Play and each screen's main
action) are raised blue keys (`wehavephones_button_primary`) with white labels and
secondary actions are silver keys (`wehavephones_button_outline`). Panels and
inset fields are green like the display (`wehavephones_card_background`,
`wehavephones_field`) and `wehavephones_divider` is a thin rule. Headings are
set in Tiny5 (`res/font/wehavephones_lcd.ttf`, SIL OFL 1.1, license in
`docs/licenses/`). Body text and controls use Android's sans-serif. Icons are
Phosphor Light (MIT) vector drawables. Status is expressed with words and icons
as well as color. The launcher icon is the blue flip-phone mark on a blue
background, with a monochrome layer for themed icons. `scripts/render-app-icon.sh`
renders it from `docs/design/brand/flip-phone.svg`, together with the native
vector drawable (`wehavephones_flip_phone`), the container icon and the Wine
desktop wallpaper.

- **Play** (`HomeFragment`): the selected game at the top left (round badge in
  the game's accent color, title such as *World of Warcraft: Forever*,
  expansion/channel and build). Tapping the title opens the game version list
  of what the Battle.net client on this device reports (§3b). Bottom left, the
  readiness checks (system files, container, Battle.net, runtime, which open
  Diagnostics when tapped). Bottom right, one status line (the live stage, then what Play
  will do with the current settings) above the **Play** button. The chosen game
  is stored in `wehavephones_product_dir` (the folder, or `bnet:<id>` for a game
  that is not installed) and drives Play, `wow.bat` and the Add-ons screen. For
  a game Battle.net lists but has not installed, Play becomes **Install**.
- **Launch overlay** (`LaunchOverlayView`, state in `LaunchProgress`, owner
  `LaunchOverlayController`): when Play starts the one-button or direct launch,
  the game session shows a loading screen instead of Winlator's "Starting up"
  dialog (hooked in through `SessionLaunchExtras`. The runtime flavor keeps the
  dialog.). It shows the game, a rotating tip, the stage line with elapsed time,
  and a silver-framed bar over Runtime, Battle.net, Sign-in, Updates, Game. It
  covers the game only while there is nothing to do and steps aside for good
  when: WoW has drawn (the `Device Create Successful` line in `Logs/gx.log` plus
  8 s, capped at 150 s), Battle.net needs the player
  (`OneButtonLauncher.Stage.NEEDS_USER`: Battle.net shows its login form), any
  stage fails or times out while a window exists, or the
  player taps **Show screen**. With a reason it leaves a 6 s note in the corner
  that touches pass through. So WoW's own login errors and the realm login
  queue are always visible. A failure before any window keeps it up as an error
  card with **Show screen** and **Back to menu**. **Cancel** stops the launch and
  ends the session. Toasts for stage changes only appear once it has stepped
  aside. The reveal rules are host-tested (`LaunchProgressSelfTest`).
- **First-run setup** (`FirstRunSetupFragment`): the default boot screen until
  system/container preparation and Battle.net installation are complete. The
  rail is hidden. Region selection and two primary install buttons replace the
  old four-step screen. **Install container** ensures imagefs and Wine layers,
  creates/reuses the container, applies the runtime and creates shortcuts.
  **Install Battle.net** then opens the official installer: Blizzard's China
  app (`Battle.net-Setup-CN.exe` from `downloader.battlenet.com.cn`) when the
  region is China, the global one otherwise. The global app cannot sign in to
  China accounts. China also seeds `zhCN` as the game language. Battle.net's
  web pages draw text through DirectWrite, which only sees registered fonts, so
  every session start registers the prefix's CJK fonts (`WineFontRegistry`,
  run by `WineFontPaths`). Without it Chinese and Korean page text is empty
  boxes. China sign-in continues in the default browser (the NetEase account
  page), which Wine's own browser cannot render, so every session start
  installs `C:\windows\openurl.exe` as the http/https "open" command. It sends
  the link to WinHandler (UDP packet `0x75`, loopback senders only), which
  shows it in a WebView panel over the session (`GuestBrowserDialog`). The
  phone's browser would send the session to the background, where Android
  may stop it before sign-in finishes. The page's final redirect to
  `127.0.0.1:<port>/login-callback` reaches Battle.net because Wine shares the
  phone's network (the manifest's network security config allows cleartext to
  127.0.0.1 and localhost only), and the panel closes shortly after it. Links
  to other apps go to Android; **Open in browser** is the fallback, as is the
  browser on devices without a WebView. Source: `tools/openurl`. Completion is
  detected while this screen is visible and on return from the installer.
  Sign-in and WoW downloads are not completion requirements. Interrupted setup
  resumes from the prepared-container marker. Existing installations migrate
  automatically. An app-wide completion preference keeps this screen out of
  navigation and future boots, even after a container/profile change.
  Setup's **Add New** reopens it as a section
  (`FirstRunSetupFragment.forNewContainer()`) for one more container, named
  after its region (`WeHavePhones WoW (CN)`), with its own Battle.net. Other
  containers are left as they are and the new one becomes active.
- **Setup** (`SetupFragment`): active container selection with **Add New**
  and **Manage**, plus **Import WoW files** from `Download/World of Warcraft`.
  `WowImportService` runs the copy as a foreground data-sync service with a
  progress notification and cancellation, retaining it across navigation and
  screen-off. `WowFileImporter` validates the complete folder and free space,
  refuses both existing supported install paths, and stages the copy beside
  the destination before publishing it by rename. Failed/cancelled copies
  remove only staging. A retry discards any staging left by process death.
  Downloads is retained. Play, inherited session launches, container switching
  and the Setup container-management button are blocked during import. See
  [Reuse existing WoW game files](REUSE_WOW_FILES.md).
  Changing the active container retains the running-session protection.
  The runtime profile selector is hidden (`LLRuntimeProfile`,
  `visibility="gone"`) while the bundled file ships one profile. Its code is
  kept for handoff mode.
- **Settings** (`WeHavePhonesSettingsFragment`): **Game resolution**
  (1024x576 by default, applied to the container screen size and
  WoW's `GxWindowedResolution` at each Play, with `GxMaximize 1` so the
  game fills the screen), **Keep the game running when you switch apps**
  (on by default, §6 item 11) and **Game region**. Not configurable (removed
  as settings 2026-10-04): Play always launches through Battle.net (the old
  *One-button launch* off mode only opened Battle.net, which **Open Battle.net**
  does), the session always returns to the menu when WoW closes
  (`WowExitWatcher`), Battle.net always closes after launching WoW, and the
  first-boot photosensitivity warning and intro cinematic are always skipped
  (`WowFirstBoot`). Explicit region changes
  replace WoW's saved portal on the next launch. Switching to or from China
  once Battle.net is installed is refused with a note pointing to Setup's
  **Add New**, since the installed Battle.net app cannot sign in to the
  other side. Other existing
  language/intro preferences remain preserved. With no detected game, Home
  offers **Open Battle.net**.
- **Diagnostics** (`DiagnosticsFragment`): live memory panel (MemAvailable,
  SwapFree, PSI avg10, same-uid per-process RSS, current mode + applied
  overrides, save/copy JSONL report under `files/wehavephones/memory-reports/`),
  runtime profile + applied state, `wineVersion` from the container JSON,
  container path, `wine.log`/`guest.log` locations, ntdll hashes per Wine
  layer, and copy-to-clipboard.
- **Containers** (rail entry → `com.wehavephones.MainActivity`): the classic
  containers/shortcuts/input-controls/settings screens. In this flavor they,
  the in-game drawer and every inherited dialog use the WeHavePhones look through
  resource overrides in `app/src/wehavephones/res` (`values/runtime_theme.xml`
  shadows `AppTheme`, the inherited colors and the button/line/fieldset styles.
  `drawable-anydpi/` shadows `button_*`, `combo_box`, `edit_text`,
  `bordered_panel`, `content_dialog_background`, `table_head_background` and
  friends. `layout/main_menu_header.xml` shows the flip-phone mark and logotype in
  both drawers). The few colors that were hard-coded in `main` layouts are named
  tokens (`toolbar_background`, `drawer_background`, `dialog_background`,
  `secondary_text_color`, `fab_icon_color`) with their original values, so the
  `runtime` flavor looks exactly as before. `MainActivity` and
  `ExternalControllerBindingsActivity` are forced to `sensorLandscape` in this
  flavor's manifest.
- **Add-ons** (`AddonsActivity`): addon manager for the selected game, with
  its own rail (game picker shared with Home, then Installed, Browse, Addon
  apps and Settings tabs). See `docs/WEHAVEPHONES_ADDONS.md`.

## 3a. Game catalog (every WoW game on Battle.net)

`WowFlavor` (pure Java, host-tested) is the single catalog of World of
Warcraft games Battle.net installs under `World of Warcraft\_<product>_`. It
drives install detection, titles, executable choice, process detection and the
addon-provider mappings.

| Family | Live | PTR | Beta | Title shown |
| --- | --- | --- | --- | --- |
| Retail | `_retail_` | `_ptr_`, `_xptr_` | `_beta_` | World of Warcraft (+ expansion from the build, e.g. *Midnight*) |
| Forever | `_forever_` (product `wowf`, the Forever Stress Test option) | `_forever_ptr_` (expected) | `_classic_beta_` (`wow_classic_beta`, the launcher's Forever beta) | World of Warcraft: Forever |
| Classic progression | `_classic_` | `_classic_ptr_` | shares `_classic_beta_` (a 5.x build there) | World of Warcraft Classic (+ *Mists of Pandaria*) |
| Titan-Reforged | `_classic_titan_` | | | Titan-Reforged Classic |
| Anniversary | `_anniversary_` | | | Classic Anniversary |
| Classic Era | `_classic_era_` | `_classic_era_ptr_` | `_classic_era_beta_` | Classic Era |

Detection (`WowFlavor.detect(dir, version)`, version from `.build.info`):

1. A **Forever** build (client 1.60 or later) is always Forever, keeping the
   directory's channel, whatever the directory is called.
2. `_classic_beta_` is the Forever beta unless its build is Classic-line (3–9),
   which is the Classic progression beta sharing the same directory.
3. Otherwise a directory in the table wins (so a progressed `_anniversary_`
   stays Anniversary).
4. Unknown directories are classified by version (1.x Era, 2.x Anniversary,
   3.80 Titan, 3–9 Classic, 10+ Retail) with the channel taken from `ptr`/`beta`
   in the name. Anything else is listed under its prettified directory name.

The Forever client is product `wowf` in `_forever_` (Battle.net offers it as
the October 19 2026 Forever Stress Test under In Development, and the folder is
reused for the live game on 2026-11-04). The Forever beta is product
`wow_classic_beta` in `_classic_beta_` (the launcher logs it as
`productCode=WoWF_wow_classic_beta`), a directory it shares with the Classic
progression beta: offered with no build yet it reads **Forever Beta**, while a
Classic-line (5.x) build there reads **Classic Beta**. The Forever PTR
directory is still a guess. Rules 1–2 and the pattern-based exe scan still pick
the game up wherever it lands, and the launch table can be hot-fixed through
`files/wehavephones/launch-overrides.json` without a rebuild. The README lists
which games have been tested on a phone.

Executables: `WowFlavor.pickExecutable` takes the known names ARM64-first
(`Wow-ARM64.exe`, `WowT-ARM64.exe`, `WowB-ARM64.exe`, `WowClassic-arm64.exe`,
…, then the x64 names), then any other `Wow*.exe` (ARM64 first), never
`WowVoiceProxy.exe`. `ProcessScanner` uses the same matcher.

## 3b. Where the Game version list comes from

`WowInstallationScanner.battleNetGames(container)` → `BattleNetGames.merge`
(pure Java, host-tested) joins what the Battle.net client itself writes, keyed
by folder, product code or uid. Diagnostics shows each game's `from=` sources:

| Source | File (guest) | Gives |
| --- | --- | --- |
| folder | `World of Warcraft\_*_\` + `.flavor.info` | installed games, product code, game exe |
| build.info | `World of Warcraft\.build.info` (Agent-written, one row per product) | product code, version, branch, build key |
| product.db | `C:\ProgramData\Battle.net\Agent\product.db` (protobuf, `BattleNetProductDb`) | uid, product code, installed/playable, version. Product-config codes the Agent has fetched |
| launcher log | newest main `battle.net-*.log`, `InstallState (<uid>)` | uid, `installed`, `sharedContainerSubpath` (folder), `localVersion` |

Only WoW game product codes are kept (`wow`, `wowt`, `wowxptr`, `wow_*`, not
`wowdev`, the Agent or other games). Games with a folder and exe are
*installed* (Play). The rest are games Battle.net has registered on this device
but not installed (Install). With no Battle.net data the list is the folders.

**Limit:** the list is what the client has written locally. Games the account
can see only in Battle.net's own Game Version menu, which the client never
registered on this device, do not appear until Battle.net records them
(for example after they are selected there once). The account's full
entitlement list lives on Blizzard's servers and is not read.

**Install** runs `Battle.net.exe --game=<uid or product code> --install`:
forwarded to a running client through `C:\wcinstall.bat` + `WinHandler.exec`,
or, with no session, the runtime is applied and Battle.net starts with the
flags via `C:\wcstart.bat` / the `WeHavePhones Play` shortcut. The user
confirms the install in the Battle.net window. **Not exercised on device.**

The scanner also now reads versions from the Agent's root `.build.info` (by
the folder's `.flavor.info` code). It used to read `.build.info` inside the
product folder only, which current installs do not have.

## 4. Flows

### First-run setup

1. `ImageFs.find()` validates the rootfs. If invalid, `ImageFsInstaller` is
   invoked (asset install, no download).
2. `WeHavePhonesContainers.resolve()` prefers the remembered container id, then a
   container whose name contains "wow"/"warcraft"/"wehavephones", then the first
   container. Only if none exists does setup create one ("WeHavePhones WoW") using
   the selected profile's Wine version.
3. The runtime profile is applied (section 5).
4. `bnet.bat`/`wow.bat` are written and the `.desktop` shortcuts
   (`Battle.net`, `AAWoW_Retail`) are created if missing.
5. Battle.net install is optional and can be run at any time.

### Update the app

Official release builds check the public GitHub releases list at most once a
day when the completed setup's menu gains focus. Setup exposes a manual
check and an opt-out for automatic checks. `mobile/updates/AppRelease` chooses
the highest newer three-part version with a matching uploaded release APK and
a SHA-256 digest or `SHA256SUMS`. Drafts, prereleases and asset-only tags are
excluded. No GitHub account or token is needed.

`AppUpdates` owns the worker and cached metadata independently of the screen.
It streams the APK into private cache with cancellation and progress, checks
space, size and SHA-256, then verifies the package, versionCode, versionName,
Android requirement and signing certificates against the installed app. Failed
or interrupted downloads restart. A complete download is reverified and reused.
`AppUpdateInstallActivity` repeats verification, stages a `PackageInstaller`
session and handles Android's confirmation/cancellation/failure callbacks. It
requires the install-source permission and user approval and refuses while a
Wine session, launch or WoW file import is active. It never uninstalls or clears
data. Debug builds expose an explanation instead of updating the Dev package
from official releases. Run `scripts/test-wehavephones-updates.sh` for the host
release-selection and APK verification policy checks.

### Install Battle.net

1. Reuse an existing installer: `Download/Battle.net-Setup.exe`, the `D:` drive
   mapping of the container, `C:\Battle.net-Setup.exe`, or the app-private
   copy under `files/wehavephones/installers/`.
2. Otherwise verify the official bootstrapper URL with an HTTP `HEAD` request
   (`https://us.battle.net/download/getInstallerForGame?os=win&version=LIVE&gameProgram=BATTLENET_APP`),
   then download it to public `Download/` when writable, else to app-private
   storage (and copy into the container so the guest can run it).
3. Write `C:\wehavephones-bnet-setup.bat` and the "WeHavePhones Installer" shortcut,
   then launch that shortcut through `XServerDisplayActivity`.
   No Blizzard installer is bundled with the APK.

### Battle.net alone

There is no separate Battle.net tab (removed 2026-10-04). Play's **Open
Battle.net** button (`WeHavePhonesPlayController.openBattleNet`) does this.

1. **Open Battle.net** applies the Battle.net stage profile and launches the
   `Battle.net` shortcut. Battle.net is set to close itself after it launches
   the game (`Client.GameLaunchWindowBehavior` = `2`, *Exit Battle.net
   completely*). The launcher reaper only closes it when it is still running
   60 s after WoW appears.
2. Battle.net signs in with the login it saved itself. When it shows its login
   form instead, the player signs in in the Battle.net window (ticking "Keep me
   logged in"). Play's one-button flow brings the session to the front and
   says so. WeHavePhones keeps no account credentials: builds before
   2026-10-04 could save one (Keystore-encrypted) and type it, and
   `SecretStore.purgeAccountCredentials` deletes it on start.

### Play

Play always goes through Battle.net: only a game Battle.net launched gets
its sign-in. (Builds before 2026-10-04 could start WoW directly and type a
saved or entered password into it. That path is gone.)

`WeHavePhonesPlayController` reads the bundled profile JSON:

- Before every stage the memory transform is composed on top of the profile
  (`WeHavePhonesMemoryOverrides.apply`): it removes the debug `DXVK_HUD`, pins
  `WRAPPER_MAX_IMAGE_COUNT=2` and `MESA_SHADER_CACHE_MAX_SIZE=256MB`, and applies
  the game resolution from Settings to the container `screenSize` and the WoW
  CVars of every detected product. The profile JSON itself is never modified.
- `mode = single` (the default) with **one-button Play** (always on, via
  `OneButtonLauncher`):
  1. Apply the configured `singleStageProfile` (`p10-ge34`) exactly as below,
     write `C:\wcstart.bat` (the profile `bnet.bat` plus optional
     `--autostarted`) and start the session with the `WeHavePhones Play`
     shortcut. A live session is reused instead (warm path). If it has no
     Battle.net, `wcstart.bat` is run inside it through WinHandler.
  2. Tail the newest *main-client* `battle.net-*.log` (`IPC ShMem
     mode=server`; `--exec` forwarders log `mode=client` and are skipped) with
     `BattleNetLauncherLog`. Status: *Starting → Signing in → Checking for
     updates → Updating… (progress when logged) → Launching → Running*,
     mirrored as toasts over the session.
  3. Update gate: launch only when `Logged into Battle.net successfully.` and
     the merged `InstallState (<uid>)` fields say `currentOperation=UpToDate`
     **and** `playable=1`, stable for `settleSeconds` (5 s). `Update`/`Install`
     or a missing `playable=1` shows *Updating…* (timeout 60 min). No sign-in
     within 420 s fails with a hint to sign in.
     The launch table uses Battle.net's UID, which can differ from the TACT
     product code in `.build.info`: Anniversary is `wow_classic_anniversary`
     in `InstallState` and Agent `product.db`, while its file product code is
     `wow_anniversary`. Page selection and the update gate both use the UID.
  4. Launch (`WeHavePhonesLaunchPlan.ladder`) for the game selected on Home:
     Battle.net starts with `--game=<uid>` so it opens on the game's page (a
     client that was already running gets one in-session `Battle.net.exe
     --game=<uid>` through `WinHandler.exec("C:\windows\wcrun.exe
     C:\wclaunch.bat")`. Re-sending it reloads the page). Once it is ready,
     Escape is sent before each Play attempt to the focused Battle.net window
     to dismiss its **What's New** dialog (including when its content fails to
     render). This runs only after sign-in and the update gate. Cancellation or
     an existing WoW process stops the input. The dialog is drawn inside the
     main window, so it cannot be distinguished by a separate X window title.
     Play is clicked in the main Battle.net window, `playButton` (155, 108) X
     pixels from its bottom-left corner (`wehavephones-launch-products.json`),
     every 10 s until the launcher logs `Launched … pid: N` or a WoW process
     appears: after sign-in the page can take tens of seconds to render on the
     phone and drops clicks until then. Before each click the button's own
     pixels are checked (`BattleNetPlayButton`): Battle.net paints Play in the
     game's accent colour and dims it to about half when the game cannot
     launch, and the settings gear beside it keeps the accent colour, so the
     two fills are compared (a dialog dimming the page dims both). Two
     readings about a second apart must agree, and a button that cannot be
     read (the page is still painting) is never greyed out. A greyed-out Play
     fails the run at once with *Play is not available*, instead of clicking
     and restarting Battle.net for nothing: this is the state of a
     pre-installed pre-release such as the Forever Stress Test, where
     Battle.net itself shows *This game cannot be played yet* (S23,
     2026-10-17). About one start in four Battle.net
     hangs before its page renders (spinner under 1 FPS, its log silent), so
     after six clicks it is closed and started again in the same session, up
     to twice (`playButton.restarts`). It signs in again from its own saved
     login. Only a game Battle.net launched this way gets Battle.net's
     sign-in: on Battle.net 2.53 `--exec="launch <code>"` and `--game=<uid>`
     only select the game, `--launch` is an Xbox launch (Battle.net waits for
     an external sign-in token and starts minimized), and a directly started
     `<exe> -launcherlogin -uid <uid>` stops at "Connecting" and the game's own
     password prompt even while Battle.net keeps running (S23, 2026-10-03).
     Those rungs remain only behind `forcePath` for testing.
  5. Launcher exit: before Battle.net starts, `WeHavePhonesBnetConfig
     .applyExitOnGameLaunch` sets `Client.GameLaunchWindowBehavior` to `2`
     (*Exit Battle.net completely*) and keeps the user's original value in
     `files/wehavephones/`. Battle.net closes itself a few seconds after it
     launches the game, and the game still signs in and reaches character
     select. Battle.net applies this only to games it launched (it logs
     `Pre-existing game session detected without a pending launch`
     otherwise). One still running `launcherExitSeconds` (90 s) after the game
     appears is closed by `WeHavePhonesLauncherReaper.reapNow`.
     Agent.exe is left to the game. Processes are matched by executable
     (argv[0] up to `.exe`), never by arguments: the main `Battle.net.exe`
     started with `--exec="launch WoW"` used to be spared, kept ~650 MB and
     respawned the helpers the reap had closed.
  6. Every run is recorded in `files/wehavephones/launch-last.json` (path used,
     attempts, timings, pids, reap and memory numbers, Play button probe
     verdict and fill colours) and
     `files/wehavephones/launch.log` (logcat tag `WeHavePhonesLaunch`). Diagnostics
     shows the last record.
  Testing knobs (no UI): `files/wehavephones/launch-overrides.json`, e.g.
  `{"playButton":{"fromLeft":130,"fromBottom":90}}`,
  `{"options":{"forcePath":"direct"}}` (also `exec`, `game`),
  `{"products":[{"dir":"_classic_","execCodes":["WoWC"]}]}`,
  `{"timeouts":{"launcherExitSeconds":120}}`.
- **Open Battle.net** (`WeHavePhonesPlayController.openBattleNet`) starts
  Battle.net alone on the same runtime, with `GameLaunchWindowBehavior` set
  the same way, so pressing Play in Battle.net also closes it. A reaper closes
  Battle.net/Agent/CEF if it is still running 60 s after the WoW process
  appears. (The former
  *One-button launch* off mode did the same and was removed 2026-10-04.)
- `mode = handoff` (fallback, two profiles):
  1. `RuntimeStageController` applies the Battle.net stage profile and starts
     the `Battle.net` shortcut.
  2. It polls `/proc` for same-uid processes whose cmdline matches
     `Wow-ARM64.exe`, `Wow.exe`, `WowClassic.exe`, `WowB.exe`, or `WowT.exe`
     (20-minute timeout).
  3. When the game process spawns it calls
     `XServerDisplayActivity.requestSessionShutdown()` (which suppresses the
     usual app restart), waits for the session to stop, kills leftover
     same-uid Wine processes, applies the WoW stage profile, and launches the
     `AAWoW_Retail` shortcut.
  4. Every stage transition is reported to the Home screen status line and
     persisted in preferences so it survives activity pauses.

## 5. Runtime profile contract

`WeHavePhonesRuntimeProfiles` loads
`app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json`. Replacing
that file requires no code changes as long as the schema
below is kept (only `WeHavePhonesRuntimeProfiles` needs touching otherwise).

```json
{
  "schemaVersion": 2,
  "mode": "single",
  "defaultProfile": "p10-ge34",
  "singleStageProfile": "p10-ge34",
  "bnetShortcut": { "name": "Battle.net", "target": "C:\\bnet.bat" },
  "wowShortcut":  { "name": "AAWoW_Retail", "target": "C:\\wow.bat" },
  "bnetBat": "@echo off\r\ncd /d \"C:\\Program Files (x86)\\Battle.net\"\r\nBattle.net.exe{flags}\r\n",
  "wowBat":  "@echo off\r\ncd /d \"C:\\Program Files (x86)\\World of Warcraft\\{productDir}\"\r\n...{exe} -d3d12\r\n",
  "profiles": {
    "p10-ge34": {
      "description": "Verified single runtime: GE-Proton 10.0-34 arm64ec + copysafe ntdll.",
      "wineVersion": "proton-10.0-34-arm64ec",
      "env": {},
      "bnetFlags": "--disable-gpu",
      "layer": {
        "name": "proton-10.0-34-arm64ec",
        "archive": "GE-proton-10.0-34-arm64ec.wcp",
        "sizeLabel": "110 MB",
        "url": "https://github.com/The412Banner/proton-wine/releases/download/build-bionic-layers-20260911-xp/GE-proton-10.0-34-arm64ec.wcp",
        "sha256": "50344ec9…"
      },
      "ntdll": [
        {
          "asset": "ntdll/ntdll-grace-copysafe-10.0-34.dll",
          "target": "opt/{wineVersion}/lib/wine/aarch64-windows/ntdll.dll",
          "sha256": "09adc21f…"
        },
        {
          "asset": "ntdll/ntdll-grace-copysafe-10.0-34.dll",
          "target": "container:.wine/drive_c/windows/system32/ntdll.dll",
          "sha256": "09adc21f…"
        },
        {
          "asset": "ntdll/ntdll-grace-copysafe-10.0-34.so",
          "target": "opt/{wineVersion}/lib/wine/aarch64-unix/ntdll.so",
          "sha256": "9f3e55a0…"
        }
      ],
      "files": [
        {
          "asset": "dll/arm64-vkd3d.dll",
          "target": "container:.wine/drive_c/arm64-vkd3d.dll",
          "sha256": "f1c11399…"
        }
      ]
    }
  }
}
```

Semantics:

- `mode` is `single` or `handoff`. In `single`, `singleStageProfile` (here
  `p10-ge34`) is applied for Battle.net, WoW and first-run setup. The Setup
  profile selection is ignored. In `handoff`, a Setup selection overrides
  `bnetStageProfile` and `wowStageProfile` is always used for the WoW stage.
  Both default to `defaultProfile` when omitted. The bundled file ships only
  `p10-ge34`. The `p10-battle`, `p10-ge34-wow` and `p10-pipe-debug` app
  profiles were removed on 2026-10-08 because single mode never used them.
- `bnetBat` uses `{flags}` (the profile's `bnetFlags`). `wowBat` uses
  `{productDir}` and `{exe}`, resolved from the detected WoW products
  (`_retail_`/`Wow-ARM64.exe` by default). Product selection is stored in
  preferences and applies to all WoW product directories.
- `ntdll[]` and `files[]` entries have the same shape (`asset`, `target`,
  optional `sha256`) and the same deployment logic. `asset` is relative to
  `app/src/wehavephones/assets/wehavephones/`. `target` is relative to the imagefs
  root, and `container:` targets are relative to the active container's root.
  `{wineVersion}` expands to the profile's Wine directory name. Profiles
  without `files` behave exactly as before.
- `layer` is optional metadata for the required Wine layer. If
  `files/imagefs/opt/<wineVersion>` is missing, the apply fails before any
  write with a message naming the layer, the expected install path and, when
  `layer.url` is set, the download URL, size label and sha256.
- Application is idempotent, snapshots `container.json`/`bnet.bat`/`wow.bat`
  plus replaced ntdll/files under `files/wehavephones/snapshots/` (last 20 kept),
  writes through `Container`/`ContainerManager`, and verifies every write by
  read-back.
- Missing asset policy: if the bundled asset is absent but the target already
  exists, the step is skipped with a note. If both are missing the apply fails
  with a clear error. No crash path.
- The bundled artifacts (sizes are on-disk bytes) are:

| Asset | Size | sha256 (prefix) |
| --- | --- | --- |
| `ntdll/ntdll-grace-copysafe-10.0-34.dll` | 1 658 880 | `09adc21f…` |
| `ntdll/ntdll-grace-copysafe-10.0-34.so` | 629 624 | `9f3e55a0…` |
| `dll/arm64-vkd3d.dll` | 2 105 344 | `f1c11399…` |
| `dll/arm64-libc++.dll` | 1 853 440 | `ed9edb58…` |
| `dll/arm64-libunwind.dll` | 90 624 | `535c6c86…` |

  The 110 MB GE-Proton layer is bundled separately
  (`assets/wehavephones/wine/GE-proton-10.0-34-arm64ec.wcp`, fetched at build time
  by `scripts/fetch-wehavephones-wine.py` and checked against the SHA-256 in the
  profile's `layer`). First-run setup installs it offline. `wow.bat` still copies
  the three DLLs into `system32` at game start, and the applier additionally
  places them at `C:\arm64-*.dll` on every apply so a fresh install has them.

## 6. Engine hooks

Session control: only two main-source files carry hooks for the WeHavePhones UI:

1. `XServerDisplayActivity`
   - `getActiveInstance()` / `isSessionActive()` / `requestSessionShutdown()`
     let the WeHavePhones UI stop a session for the runtime handoff. The shutdown
     sets `externalShutdownRequested` so the usual guest-termination path does
     not restart the whole app mid-handoff.
2. `ImageFsInstaller`
   - `installIfNeeded(AppCompatActivity)` overload so `WeHavePhonesActivity` can
     install the rootfs without going through `MainActivity`.

The low-memory trim hook:

3. `XServerDisplayActivity.MemoryTrimListener` (null by default) +
   `onTrimMemory` forwarding + `trimMemoryCaches()`.
4. `GLRenderer.trimMemory()` (effect targets + unmapped-window textures).
5. `EffectComposer.releaseBuffers()`.
6. `RenderTarget.destroy()` override.

Controller play (2026-09-27):

7. `FakeGamepadWriter` + `WinHandler.setFakeGamepad()`: the Proton runtime
   uses stock Wine xinput (SDL2 through winebus), not Winlator's input DLLs,
   so it never sends `GET_GAMEPAD`. Before this, no controller reached the
   game (`C_GamePad.GetAllDeviceIDs()` was empty). `setupXEnvironment` now
   creates or empties `imagefs/dev/input/event0` before Wine starts (SDL
   scans the directory once). `libfakeinput.so` redirects
   `/dev/input/event0` to that file and reports it as "Generic HID Gamepad
   0", and WinHandler appends the physical controller's state to it as
   24-byte `input_event` records. The file only grows during a session,
   because a reader's offset cannot move back. The on-screen gamepad
   (a controls profile with gamepad bindings) goes through
   `WinHandler.onVirtualGamepadInput()` to the same file. Before that it
   only used the UDP path and did nothing in WoW.
8. `WinHandler.acquireController()`: takes the controller that sends input
   when none is current or the current one disconnected. Bluetooth pads come
   back from sleep with a new device id and descriptor. The id already
   given to Winlator-DLL guests is kept.
9. `SessionInputExtras` (flavor hook, empty in `runtime`): WeHavePhones's
   `GlueControllerBridge` maps A to Enter, B to Escape and the D-pad to the
   arrow keys on WoW's pre-game screens. They ignore gamepads, which was
   checked with the fake gamepad working in the world. It must never send
   those keys in the world, where Enter opens chat. The client's logs are
   buffered until logout (EditMode's "Loading" line reached the disk
   minutes later), so it classifies bursts of file writes, which reach the
   disk immediately. Measured on Classic Era 1.15.9:

   | Event | Global `WTF/SavedVariables/*.lua` | `WTF/Account/*/SavedVariables/ConsolePort.lua` | Logout marker (`WTF/Account/*/tts-cache-account.txt`, `Logs/EditMode.log`) |
   |---|---|---|---|
   | Enter World | written | — | — |
   | `/reload` | written | written | — |
   | Logout | written | written | written |

   Bridge off = global-only burst. Bridge on = ConsolePort write plus a
   marker. Anything else changes nothing. Blizzard's account-level
   `Blizzard_SavedSets.lua` is written on world entry too, so it is not
   followed. The bridge arms only when ConsolePort is installed,
   identifies the game by `/proc/<pid>/cwd`, and skips Forever.

No console window (2026-09-28):

10. `SessionLaunchExtras` (flavor hook, returns null in `runtime`): every
    session copies `assets/wehavephones/wcrun.exe` to `C:\windows`, and
    `getWineStartCommand` starts `.bat`/`.cmd` shortcuts as
    `winhandler.exe /dir <dir> "C:\windows\wcrun.exe" "<bat>"`. wcrun runs
    `cmd /s /c "<args>"` with `CREATE_NO_WINDOW`, so Wine's conhost is
    headless and no console window shows before Battle.net or WoW. The
    in-session `WinHandler.exec` commands use it too. Source and build
    script: `tools/wcrun/` (Zig or mingw in Docker).

Keep running in the background (2026-09-28):

11. `SessionKeepAliveService` + `SessionLaunchExtras.keepRunningInBackground`
    (false in `runtime`, which keeps the upstream pause). Upstream
    `onPause` SIGSTOPs the guest launcher process, and without a foreground
    service Samsung/Android freezes the whole app (Wine children included)
    or low-memory-kills it, so WoW lost its server connection when the user
    switched apps. With the setting on, the session runs a foreground
    service ("Running game" notification channel, created on the home
    screen so the Android 13+ permission prompt shows there), holds a
    partial wake lock and a `WIFI_MODE_FULL_HIGH_PERF` Wi-Fi lock, and
    skips the SIGSTOP. `shutdownRuntimeOnce` stops the service. Swiping the
    app out of Recents calls `onTaskRemoved`, which ends the session and
    exits the process as the old Recents kill did.
    While hidden, `PresentExtension.setHidden(true)` spaces Present
    Idle/Complete notifications 200 ms apart (`PresentPacer`, in order),
    which holds the swapchain to 5 FPS. Host test:
    `scripts/test-wehavephones-runtime.sh` §3.

Everything else reuses existing public APIs: `ImageFs`, `ContainerManager`,
`Container`, `Shortcut`, `WinHandler`, `ProcessHelper`, `HttpUtils`.

## 7. Build and deploy

```bash
source .tools/env.sh

# Both flavors
./gradlew :app:assembleRuntimeDebug
./gradlew :app:assembleWehavephonesDebug

# Scripts
scripts/build-apk.sh            # runtime flavor -> app/build/outputs/apk/runtime/debug/app-runtime-debug.apk
scripts/build-wehavephones.sh            # wehavephones flavor, debug (com.wehavephones.dev) -> app/build/outputs/apk/wehavephones/debug/app-wehavephones-debug.apk
scripts/build-wehavephones.sh --release  # signed release (com.wehavephones) -> out/release/WeHavePhones-<version>.apk + SHA256SUMS
scripts/deploy-wehavephones.sh           # build the debug APK + install -r + am start WeHavePhonesActivity
scripts/deploy-wehavephones.sh --release # build + sign + install -r + am start the release app

# Bundled runtime profile + artifact self-test (no device)
scripts/test-wehavephones-runtime.sh        # JSON parser/selection + asset existence + sha256

# Memory trims / game resolution
scripts/test-wehavephones-memory.sh         # host self-test (no device)
python3 scripts/profile-wehavephones-memory.py --minutes 15 --output out/run.jsonl
                                            # sample a session's memory from the host (phone attached)
```

`scripts/deploy-wehavephones.sh` never uninstalls the package: uninstalling
would delete the app's private game data. The debug and release apps have
separate data, so deploying one never touches the other. The script respects
`ANDROID_DEVICE`, `ADB_SERIAL` (also from `local.env`) and `ADB_BIN`, and prints
`Installed and launched` after `am start -W` succeeds.

`--release` runs `scripts/build-wehavephones.sh --release`, which signs with the
key from the environment or `~/.config/wehavephones/signing.env`, checks the
signature and the manifest (package `com.wehavephones`, not debuggable) and
stages `out/release/WeHavePhones-<version>.apk` with `SHA256SUMS`
([release builds](BUILD_ASSETS.md#release-builds)). The script installs that
file.

It installs with `-i <package>` (`com.wehavephones.dev`, or `com.wehavephones`
with `--release`) so the app is its own installer of record.
Samsung's game manager ignores the manifest's `appCategory` and only counts a
package as a game when PackageManager has a category override, which only the
installer may set. `GameCategoryHint` sets it on launch, and Game Booster (its
panel, FPS and performance profile) then applies to WeHavePhones. Check it with
`adb shell dumpsys package <package> | grep category=`: it should report
`override: 0`. Adding the app in Gaming Hub's library does not set this
override. `pm set-installer` can't set it either, because that command refuses
an installer whose certificate differs from the shell's.

## 8. Add-ons and memory behavior

### Add-ons

The addon manager lives in
`app/src/wehavephones/java/com/wehavephones/mobile/addons/` and is described in
`docs/WEHAVEPHONES_ADDONS.md`. `AddonsActivity` resolves the active container and
its `Interface/AddOns` directory via `WeHavePhonesPaths.wowRootFor(container)`.
`AddonRepository` is a process-wide registry that lets any other screen observe
the installed addon list (`InstalledAddon`) without holding an activity.
`AddonProvider` is the provider interface.

### Memory behavior

The opt-in LOW memory mode is gone. What remains is default behavior:

- `WeHavePhonesConfig` persists the game resolution (`wehavephones_game_screen_size`,
  default 1024x576) and the last memory snapshot name.
- `WeHavePhonesMemoryOverrides` applies the always-on env trims and the game
  resolution (container `screenSize` + `GxMaximize 1` and
  `GxWindowedResolution` in existing
  `Config.wtf` files) after every profile apply, with a snapshot and
  read-back. It also removes the old LOW `wehavephones-dxvk.conf` and its
  `DXVK_CONFIG_FILE` entry. It no longer writes the LOW CVar preset.
- `WeHavePhonesLauncherReaper` closes Battle.net/Agent/CEF on every launch path
  when Battle.net has not closed itself within 60 s of the game starting.
  `WeHavePhonesMemoryDiagnostics` feeds the diagnostics panel and the JSONL report.
- `scripts/profile-wehavephones-memory.py` samples a session's memory from the
  host, and `scripts/test-wehavephones-memory.sh` with
  `tools/wehavephones-memory-selftest/` provides the host tests.

## 9. Known limitations

- The bundled artifacts are byte-verified on the host by
  `scripts/test-wehavephones-runtime.sh`. On device the first apply installs the
  ntdll pair into the layer and prefix (with snapshot + read-back) and copies
  the three WoW DLLs to `C:\arm64-*.dll`.
- The full Play path reaches the rendered WoW character-select screen on
  `p10-ge34`, but only when (a) the ARM64 vkd3d DLLs are in the prefix
  `system32` before Battle.net starts and (b) Battle.net is closed shortly after
  `Wow-ARM64.exe` appears. The bundled profile `bnetBat` performs (a) with
  guarded copies, matching `wowBat`. For (b) Battle.net closes itself when it
  launches the game and the launcher reaper closes it otherwise (§4, Play).
- The official installer URL is verified with a runtime `HEAD` request. If the
  endpoint rejects `HEAD` or is unreachable, the flow reports a clear error and
  falls back to any existing local installer.
- Exiting the Battle.net session manually still uses the engine's normal exit
  path (app restart). Only handoff-initiated shutdowns suppress the restart.
- `install -r` only upgrades across matching signing keys and fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE` otherwise, which means the installed copy
  was signed with another key. `scripts/deploy-wehavephones.sh` explains this.
  `scripts/build-wehavephones.sh` signs debug builds with your local debug key
  unless `WEHAVEPHONES_KEYSTORE` (with `WEHAVEPHONES_KEYSTORE_PASS`,
  `WEHAVEPHONES_KEY_ALIAS` and `WEHAVEPHONES_KEY_PASS`) names another key, and
  `--release` builds with the release key. Never uninstall to resolve a
  mismatch: it deletes the app's game data.
