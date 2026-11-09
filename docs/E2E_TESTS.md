# End-to-end game tests

`scripts/e2e-wehavephones.py` boots each installed game on a real phone, logs
in the way a player does, enters the world and checks that the character
responds. It also starts the addon managers from **Addon apps** and checks
that they load. It drives the Dev build (`com.wehavephones.dev`) over adb.

```bash
scripts/e2e-wehavephones.py list                      # scenarios and groups
scripts/e2e-wehavephones.py run --serial <adb serial>  # versions + touch
scripts/e2e-wehavephones.py run forever-touch classic-era
scripts/e2e-wehavephones.py run apps                  # WowUp and CurseForge
scripts/e2e-wehavephones.py restore                   # after an interrupted run
scripts/e2e-wehavephones.py selftest                  # host check, no phone
```

Run `selftest` after changing the runner. It checks the marker reader, the
Enter World detector and the settings round trip on synthetic data.

## Before a run

- Install the current Dev build with `scripts/deploy-wehavephones.sh`.
- Sign in to Battle.net once in the app. The tests use that saved sign-in
  through the app's own **Play** and never type credentials.
- Pick a character in each game once. The tests enter the world with the
  character selected at character select. If a game opens on a realm without
  your characters, it shows character creation and the test fails. Choose
  your realm and character in that game once and run it again.
- Keep the phone unlocked, awake and preferably charging. A Battle.net
  security challenge or a pending game update needs the player.

## Scenarios

| Scenario | Game | Settings | Checks |
| --- | --- | --- | --- |
| `classic-era` | Classic Era | App defaults | Enter world, jump with Space |
| `anniversary` | Anniversary (TBC) | App defaults | Enter world, jump with Space |
| `mop` | MoP Classic | App defaults | Enter world, jump with Space |
| `titan` | Wrath / Titan | App defaults | Enter world, jump with Space |
| `retail` | Retail | App defaults | Enter world, jump with Space |
| `forever` | Forever | App defaults | Enter world, jump with Space |
| `classic-era-touch`, `anniversary-touch`, `mop-touch`, `titan-touch`, `retail-touch`, `forever-touch` | Each game above | Mobile touch controls on | Tap Enter World, switch to Play, jump with the Jump key, move with the stick, turn with a camera drag |
| `classic-era-gamepad` | Classic Era | App defaults | Enter world, jump with the on-screen gamepad's A |

| `wowup` | WowUp | App defaults | Logs `Tray created true`, `Checking for addon updates` and `[scan] wowup scan of` |
| `curseforge` | CurseForge | App defaults | Logs `Connected to Mods Agent` and `Scan complete for game 1`, then stays open 30 s |

Groups: `versions` (one per game line), `touch` (the same games with touch
controls), `extra`, `apps` (`wowup` and `curseforge`), `default` (`versions`,
`touch` and `apps`) and `all`. A game that is not installed is skipped.

"App defaults" means the settings a new install starts with. The runner
removes the player's touch controls, performance overlay, runtime profile,
game resolution and graphics driver choices for the run. It keeps the game
choice, region, setup state and touch guide flags.

## How a scenario runs

1. The runner stops any session, saves the settings it will change to
   `out/e2e/settings-backup.json` and writes the scenario's settings.
2. It copies the test addon `tests/e2e/WeHavePhonesE2E` into the game, with a
   `.toc` for the interface version in the client's `.build.info`.
3. It taps **Play** and follows `launch-last.json` until the game runs. When
   Battle.net hangs or does not start the game, it stops the session and
   tries Play once more.
4. It waits for character select, then presses Enter (or taps Enter World with
   touch controls on). Character select shows either the red Enter World button
   at the bottom center or, on Forever with the on-screen gamepad, the flat
   button hint bar there.
5. It runs the checks and saves screenshots and the new crash log entries.
6. It stops the session and removes the addon. At the end it restores the
   saved settings, also after a failure.

## How an addon app scenario runs

1. The runner stops any session and notes the app's newest exit record and
   how far the manager's log has grown.
2. It opens **Add-ons**, then **Addon apps**, and taps the manager's button by
   its view id. The button may download or update the manager first. That is
   part of the test, and the time limit grows by 15 minutes for it.
3. It reads what the manager logged since the tap: WowUp's
   `AppData/Roaming/WowUp/logs/main.log`, or the new folders under
   CurseForge's `AppData/Roaming/CurseForge/logs`. The ready lines must appear
   in order. WowUp's mean it got past its tray icon, opened its addon list and
   read the game's addon folders. CurseForge's mean its .NET mods agent
   answered and the WoW library scan behind "Forging your library" finished.
4. CurseForge's session must then stay open for 30 s. WowUp has no hold. It
   looks up the addons on the addon sites after the folder scan and does not
   log the end of it. On the Galaxy S23 (8 GB) Android closes the app for low
   memory during those lookups, at about 1.3 GB across WowUp's 10 processes,
   so the test stops at the folder scan, the furthest WowUp reliably gets on
   that phone.
5. A new exit record fails the scenario with Android's reason, such as
   `LOW_MEMORY`, so a closed app is not mistaken for a slow one. The runner
   saves `app.png` (or `failure.png`), `app.log`, `logcat.txt` and new crash
   entries, then stops the session.

## Reading the game

The test addon draws a strip of squares near the top of the screen:

```
magenta | in world | jumped | moved | turned | cyan
```

In world, moved and turned are black until their event happens and then stay
green. Moved uses `GetUnitSpeed` and turned uses `GetPlayerFacing`. Turned is
yellow when the client does not report a usable facing. Retail on its starter
island reports one that never changes. When turned stays black or yellow, the
runner compares the upper right game area before and after the camera drag and
accepts a clear change of view. The result line says when it did.

Jumped is a pulse. It is green for five seconds after `IsFalling()` turns
true, yellow for five seconds after the JUMP binding ran
(`JumpOrAscendStart`) without the character leaving the ground, and black
otherwise. The runner waits for black, presses jump and looks for green, so a
fall while the world streams in never counts as a jump.

For its first eight seconds in the world the addon also closes the chat box,
so a late Enter press cannot swallow the jump key. The magenta and cyan ends
let the runner find the strip at any resolution.

Debuggable builds write the touch controls' mode and key bounds to
`files/wehavephones/touch-layout.json`, so touch checks press the real keys
without fixed coordinates. Release builds never write it.

## Results

Each run writes `out/e2e/<time>/` with a folder per scenario: `charselect.png`,
`world.png`, `checks.png` (or `failure.png` and `timeout.png`),
`launch-last.json`, `logcat.txt` and `crash.txt` when the crash buffer gained
entries. `summary.json` lists the results. The exit status is 0 when every
scenario passed or was skipped and 1 when one failed.

The on-screen gamepad's A position is set for the 2340x1080 Galaxy S23 and
scaled to other screens. Override it with `WEHAVEPHONES_E2E_GAMEPAD_A=x,y`.
`WEHAVEPHONES_E2E_WOW_DIR` names another World of Warcraft folder inside the
app's data.
