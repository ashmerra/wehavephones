# WeHavePhones

We do have phones, and also Wine.

Winlator fork for running World of Warcraft (ARM64 Windows version) and Battle.net on Android phones.

Controller support.\
Built-in addon manager.

[![WeHavePhones demo video](https://img.youtube.com/vi/4wet8as_QfA/maxresdefault.jpg)](https://www.youtube.com/watch?v=4wet8as_QfA)

## Tested on

| Phone | Chip / GPU | Android | Classic Era | Burning Crusade Anniversary | Mists of Pandaria Classic | Retail | Forever (beta) |
|---|---|---|---|---|---|---|---|
| Samsung Galaxy S23+ | Snapdragon 8 Gen 2 / Adreno 740 | 16 | Playable in world | Playable in world | Playable in world | Playable in world | Launches to login screen* |
| Google Pixel 8a | Tensor G3 / Mali-G715 | 17 | Playable in world | Not tested | Not tested | Not tested | Not tested |

\* Not tested past the login screen because I don't have beta access.

The four live clients in the Americas & Oceania catalog were validated on the S23+ with 0.0.2-dev on 2026-10-10. Classic Era, Burning Crusade Anniversary and Mists of Pandaria Classic reached the world through automated phone control; Retail's new-character world login was confirmed manually. Hardcore and Season of Discovery share Classic Era's launch path and were not exercised separately. Beta and PTR clients were excluded from this run.

Other games and versions have not been tested. Other phones are matched to a graphics setup by device rules; if yours misbehaves, use Report a problem in Diagnostics.

## Install

1. Download the latest APK from [Releases](https://github.com/ashmerra/wehavephones/releases).
2. Allow your browser or file manager to install unknown apps, then open the APK.
3. Open WeHavePhones and follow Setup: prepare the container, install Battle.net, sign in, and download your WoW version (or import existing files as described below).
4. When the game finishes installing, close Battle.net: swipe in from the left edge of the screen to open the side menu, then tap Exit. Launching the game from the Battle.net window is not recommended.
5. Back in the WeHavePhones app, press Play.

Already have WoW installed on a Windows PC? Once the container and Battle.net are ready:

1. Copy your entire **World of Warcraft** folder into the phone's **Downloads**.
2. In WeHavePhones, tap **Setup → Import WoW files** and wait.
3. Open Battle.net, choose the matching game version, and use **Locate the game** to select the imported folder. Finish updates, exit through the side menu, then press **Play** in WeHavePhones.

Allow space for two copies during import. Battle.net may still download missing
files or updates. See [detailed instructions](docs/REUSE_WOW_FILES.md).

You need a 64-bit ARM phone with Android 8.0 or newer and enough free space for the game.

## Build from source

```bash
# x86_64 Linux or WSL2 with git, curl and python3; about 10 GB free
git clone https://github.com/ashmerra/wehavephones.git
cd wehavephones
scripts/setup-dev.sh           # JDK 17 + Android SDK/NDK in .tools/ (~5 GB)
scripts/build-wehavephones.sh  # fetches pinned assets, builds the APK
```

The APK is written to `app/build/outputs/apk/wehavephones/debug/app-wehavephones-debug.apk`. A debug build installs as a separate app, WeHavePhones Dev (`com.wehavephones.dev`), next to an official release. `scripts/deploy-wehavephones.sh` builds it, installs it and launches it over adb; set `ANDROID_DEVICE` in a gitignored `local.env` or pass `--serial`. Large prebuilt assets are pinned by SHA-256; see [docs/BUILD_ASSETS.md](docs/BUILD_ASSETS.md).

## Documentation

- [Reuse existing WoW game files](docs/REUSE_WOW_FILES.md)
- [App architecture](docs/WEHAVEPHONES_APP.md)
- [Runtime and the patched Wine ntdll](docs/WEHAVEPHONES_RUNTIME.md)
- [Addon manager](docs/WEHAVEPHONES_ADDONS.md)
- [Device profiles](docs/DEVICE_PROFILES.md)
- [Device reports](docs/DEVICE_REPORTS.md)
- [Build assets](docs/BUILD_ASSETS.md)

## Credits

WeHavePhones builds on [Winlator LLM](https://github.com/FrontMage/winlator-llm)
by FrontMage, which builds on [Winlator](https://github.com/brunodev85/winlator)
by BrunoSX. Their Android application and Windows runtime integration make this
project possible.

Thanks to the [FEX developers](https://github.com/FEX-Emu/FEX) for FEX, the
[Mesa](https://www.mesa3d.org) Turnip developers, and
[The412Banner](https://github.com/The412Banner/proton-wine) for the GE-Proton
arm64ec builds WeHavePhones runs on. Controller support is powered by
[ConsolePort](https://github.com/seblindfors/ConsolePort) by Sebastian
Lindfors, which WeHavePhones downloads and installs as a WoW addon.

## License

The code in this fork is MIT-licensed; the original copyright and license are
retained in [LICENSE](LICENSE). Bundled components keep their own licenses,
listed with their sources in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Trademarks and disclaimer

WeHavePhones is an independent, unofficial project. It is not affiliated with,
endorsed, sponsored, or approved by Blizzard Entertainment, Inc., Microsoft,
or the Winlator projects. WeHavePhones includes no Blizzard game files or
assets; users need their own Blizzard account and game license and install the
game themselves through Battle.net.

Blizzard does not officially support running its games through Wine or on
Android and offers no technical support for these setups. As with any account,
Blizzard can suspend or close accounts at its discretion under its terms of
service. Use WeHavePhones at your own risk.
WeHavePhones never asks for, stores or types your Battle.net credentials; you
sign in inside Battle.net itself.

World of Warcraft, Warcraft, Battle.net and Blizzard Entertainment are
trademarks or registered trademarks of Blizzard Entertainment, Inc. in the U.S.
and/or other countries. All other trademarks are the property of their
respective owners.
