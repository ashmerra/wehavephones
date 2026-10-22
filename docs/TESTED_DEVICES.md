# Tested devices and graphics drivers

Research snapshot: **2026-10-13**. “Version” below means the WoW edition.
Exact game builds and app versions are included when the source records them.

A successful row requires a first-hand report of a working game or a project
test record. Reaching login or character select is recorded separately from
playing in the world. A device-rule match, successful driver probe, suggested
fix, or closed issue alone does not establish gameplay compatibility. Missing
drivers are not filled in from today's defaults.

## Community-confirmed WeHavePhones combinations

| Device | WoW edition | Working driver | Result and limits | Evidence |
| --- | --- | --- | --- | --- |
| OnePlus 15 (CPH2747, Adreno 840, Android 16) | Retail | **System / phone's own driver** | In-world gameplay. Reporter's preferred driver. 40 FPS cap, native container resolution, 50% render scale. Frame drops remain. Exact successful app/game build not stated. | XMCaster: [in-world report](https://github.com/ashmerra/wehavephones/issues/13#issuecomment-6122955964), [explicit system-driver clarification](https://github.com/ashmerra/wehavephones/issues/13#issuecomment-6134066972), Oct 12 |
| OnePlus 15 (CPH2747, Adreno 840, Android 16) | Retail | **Qualcomm 842.6** (`qualcomm-842.6`) | Playable in-world. Some frame drops even at lowest settings. Exact successful app/game build not stated. | XMCaster: [driver test and screenshot](https://github.com/ashmerra/wehavephones/issues/13#issuecomment-6120055501), Oct 12 |
| Lenovo TB390FU (Adreno 825, Android 16) | Classic Era | **Qualcomm 863.1** (`qualcomm-863.1`) | Reporter confirms Classic works. Successful app version and exact game build not stated. Original failing report used app 0.0.2. SD-card installation remains a separate problem. | ondrejfriedel: [success confirmation](https://github.com/ashmerra/wehavephones/issues/17#issuecomment-6140277261), [device identity and edition](https://github.com/ashmerra/wehavephones/issues/17), Oct 13 |
| Xiaomi Pad 8 Pro (model code, GPU and Android version not stated) | Retail | **System / phone's own driver** | Running at 1920×1080, 59–60 FPS. Reporter says the system driver is the only one that works. App version, game build and gameplay stage not stated. | NyXXXoR: [r/winlator comment](https://www.reddit.com/r/winlator/comments/1x23l4p/comment/pgq6tgf/), Oct 14 |

The OnePlus 15 profile uses the system driver and D3D11 by default starting in
WeHavePhones 0.0.4. That release is the maintainer's follow-up, not a version
explicitly identified in the reporter's earlier successful test. The Lenovo
confirmation followed a request to test the 0.0.4 picker, but the reply does not
state the installed version. See [device profiles](DEVICE_PROFILES.md).

## Project-tested baseline

These are maintainer tests, rather than independent community confirmations.

| Device | WoW edition / recorded build | Driver evidence | Result | Source |
| --- | --- | --- | --- | --- |
| Samsung Galaxy S23+ (SM-S916U1, Adreno 740, Android 16) | Classic Era — `1.15.9.70003` | Project's pinned Turnip 26.3.0-devel (`1b51639c20`). See qualification below | Entered world | [Issue #1 test record](https://github.com/ashmerra/wehavephones/issues/1#issuecomment-6103721746) |
| Samsung Galaxy S23+ (same device) | Burning Crusade Anniversary — `2.5.6.69795` | Driver not recorded in this run | Entered world | [Issue #1 test record](https://github.com/ashmerra/wehavephones/issues/1#issuecomment-6103721746) |
| Samsung Galaxy S23+ (same device) | Mists of Pandaria Classic — `5.5.4.70268` | Driver not recorded in this run | Entered world | [Issue #1 test record](https://github.com/ashmerra/wehavephones/issues/1#issuecomment-6103721746) |
| Samsung Galaxy S23+ (same device) | Retail / Midnight — `12.1.0.69933` | Driver not recorded in this run | Owner confirmed world login. Disconnects occurred during testing | [Issue #1 test record](https://github.com/ashmerra/wehavephones/issues/1#issuecomment-6103721746) |
| Samsung Galaxy S23+ (same device) | Forever stress test — build not recorded | Driver not recorded in this run | Owner confirmed fully tested in-world gameplay during the Oct 21, 2026 stress test | Maintainer's first-hand confirmation, Oct 21, 2026 |
| Google Pixel 8a (Tensor G3 / Mali-G715, Android 17) | Classic Era — build not recorded | System Vulkan driver + BCn compatibility layer, per project runtime record | Playable in-world | [README](../README.md#tested-on), [runtime](WEHAVEPHONES_RUNTIME.md), [profile](DEVICE_PROFILES.md) |
| Samsung Galaxy S26 Ultra (SM-S948U, Android 16, One UI 8.5) | Classic Era — build not recorded | Device's automatic system graphics driver. Exact version not recorded | Signed in and reached in-world gameplay. The account owner performed the in-world step | [Issue #18](https://github.com/ashmerra/wehavephones/issues/18), internal S26 test report, Oct 16 |

The four S23+ edition tests were performed on Oct 10 with WeHavePhones
`0.0.2-dev`, source commit `bc1eaee`. The issue records world access, not a
sustained stability or performance benchmark. It does not log the driver.
[The runtime documentation](WEHAVEPHONES_RUNTIME.md) independently records
Classic Era testing with the pinned Turnip, and
[the driver manifest](../app/src/wehavephones/assets/wehavephones/graphics/turnip.json)
records its validation on Oct 3. The S23+ profile prefers that driver, but this
does not prove which driver was selected in each Oct 10 run.

On **2026-10-21**, the maintainer confirmed fully tested **Forever gameplay on
the S23+** during the stress test. This supersedes the earlier login-screen-only
coverage for Forever, when beta access was unavailable. The new confirmation
is for the stress-test client, not a separate validation of the earlier beta
client. Exact game build, app version, selected driver, session duration and
performance measurements were not recorded with this confirmation.

The S26 Ultra test used a Dev build from the Security Check fix (`4acd2fd`),
which is included in 0.0.6. The agent observed the complete Security Check page,
signed-in state and the start of the Classic Era installation. The account owner
then completed the remaining steps manually and confirmed in-world Classic Era
gameplay. That step was not observed by the agent and no driver version or
performance measurement was recorded, so the row above claims only the reported
result.

## Reddit reports with missing details or limited progress

| Device | WoW edition | Driver | Reported result | Source |
| --- | --- | --- | --- | --- |
| ASUS ROG 6 (as reported) | Not stated | Not stated | Works. Character movement and auction-house use mentioned | [_grotesqome_, r/wow](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/) |
| Samsung Galaxy S23 (community device, not assumed to be S23+) | Not stated | Not stated | Running below 30 FPS | [TrainDroiver, Reddit](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/pf1mmwg/), [GitHub mirror](https://github.com/ashmerra/wehavephones/issues/2#issuecomment-6099994241) |
| OnePlus 15 (second reporter) | Not stated | Reporter explicitly unsure | Working after update. Edition, driver and gameplay stage unspecified | [Fair_Bear_7861, r/classicwow, Oct 13 follow-up](https://www.reddit.com/r/classicwow/comments/1x22wwu/wehavephones_wow_running_on_android/) |
| Lenovo Xiaoxin Pad Pro GT (reported Snapdragon 8 Gen 3, 12 GB RAM) | Forever beta | Not stated | **Character select only**, approximately 60 FPS. Download unfinished | [Aedarrow, r/wow](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/) |

These replies appear in WeHavePhones announcement threads, but do not all
explicitly identify the installed app. They establish only the details shown.
They do not add a fully specified device/edition/driver combination.

## Other apps and broader community context

| Device | WoW edition | Driver | App / evidence | Result |
| --- | --- | --- | --- | --- |
| Nubia RedMagic 11 Pro (Adreno 840) | Forever beta | **Turnip-V32-RP6sched**, Mesa `26.3.0-devel-9c7e022+rp6sched`, D3D11 | GameNative 1.2.1. [author's test record](https://github.com/arusiasotto/wow-forever-a840#readme), Sept 26, with updated container notes Oct 12 | Reached Ironforge. Custom Proton/DXVK stack. 1344×608 or 1280×720 worked, 1920×1080 hit an assertion. **Not a WeHavePhones test.** |
| AYN Thor / Thor Max (different reporters) | Forever beta. Classic also reported | Not stated | [fpGrumms, r/classicwow](https://www.reddit.com/r/classicwow/comments/1x22wwu/wehavephones_wow_running_on_android/) links [wow-forever-android](https://github.com/jaredgei/wow-forever-android). [ZeroRD, r/wow](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/) explicitly uses another app | Working reported. Max report mentions 60 FPS. Different editions/reports must not be merged into a WeHavePhones result. |
| Samsung S25 (as reported) | Wrath `3.3.5a` HD client | Not stated | GameNative. [denartes, r/wow](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/) | Reported 60 FPS with a local server |
| Xiaomi 17 Pro Max | Forever, from thread context | Not stated | App not identified. [ImplementBig7706, r/wow](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/) | Reported 90 FPS and USB-C controller support |
| Red Magic Astra 1 (reporter's name) | Forever, from thread context | Not stated | App not identified. [snailpo225, r/wow](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/) | Working reported with images |

The RedMagic 11 Pro record explains why V32-RP6sched is offered in our picker.
Availability of that driver does not establish the same result in WeHavePhones.

## Reports that still need confirmation

- **Galaxy S26 Ultra / TBC:** the Reddit follow-up says graphics corruption
  remained after updating. [Original report](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/pf2cvhp/)
  and [issue #14](https://github.com/ashmerra/wehavephones/issues/14).
  The issue's closure is not a user confirmation of a fix.
- **“F8 Ultra”:** slow load and graphics errors reported by Half-deaf-mixed-guy
  in [r/wow](https://www.reddit.com/r/wow/comments/1x23dwk/wehavephones_wow_running_on_android/).
  Full model, edition and driver are missing.
- **AYN Thor:** a failure report and successes with other apps coexist. No
  fully specified successful WeHavePhones combination found in the reviewed threads.
- **RP6 and OnePlus tablet:** positive remarks lack an exact edition, driver
  or identified WeHavePhones setup. Do not infer Odin compatibility from them.

## Coverage and updates

Reviewed all 19 public GitHub issues (open and closed) and their comments, the
linked r/wow and r/classicwow announcement discussions, the r/EmulationOnAndroid
announcement, a later r/winlator report (Oct 14), and the project's existing device/runtime records. Reddit search
results exposed newer replies than the cached page view. This is a snapshot of
accessible evidence, not an exhaustive compatibility guarantee.

The S26 Ultra row was added on 2026-10-17 from the project's own rental-device
test record and the account owner's confirmation, which fall outside the
snapshot above.

The S23+ Forever row was added on 2026-10-21 from the maintainer's first-hand
stress-test gameplay confirmation, also outside the research snapshot above.

For a new row, record the device/model code, WoW edition and build, app version,
driver picker ID (and system-driver version if available), graphics API,
furthest stage reached, report date and a direct source link. Prefer a
Diagnostics device report plus an in-world screenshot. Keep failures and
character-select-only results qualified, and never infer compatibility for
another phone merely because its GPU matches.
