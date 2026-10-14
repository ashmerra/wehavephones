# Device reports

Graphics problems depend on the phone, so every problem report should say which phone and GPU it
came from. This page describes how reports reach GitHub, how to read them, and what the bot that
files reports from other sites should include.

## How reports flow

1. **In the app.** Diagnostics has a Copy button and a Report a problem button. Both use the same
   text block, `== WeHavePhones device report ==` (model, GPU, rule, profile, driver, the driver
   picked in Diagnostics and probe results. No account names, emails or serial numbers). Report a problem opens a prefilled
   issue form in the browser (see below). Copy lets the user paste the block into any issue.
2. **Issue form.** `.github/ISSUE_TEMPLATE/bug_report.yml` asks for version, phone, chipset,
   Android version, game, a free-text description and an optional `Device report` field.
3. **External reports.** A separate bot files issues for problems people post elsewhere (Reddit and
   similar), for example `Graphics corruption on OnePlus 15`. These have no structured device data
   unless the bot adds it (see the guidance below).
4. **Triage.** `scripts/device-reports.py` reads all issues (read-only) and groups them by GPU
   family:

   ```bash
   scripts/device-reports.py              # table from `gh issue list` (needs an authenticated gh)
   scripts/device-reports.py --json       # same data as JSON
   scripts/device-reports.py --input issues.json --limit 500 --all
   scripts/device-reports.py --selftest   # offline fixtures for all three input kinds
   ```

   Device data is taken from the device report block first, then the issue-form fields, then phone
   names and model codes found in the title or body, looked up in `scripts/device-catalog.json`
   (phone to SoC and GPU, with low-confidence entries marked and reported as such). Issues with
   no device information are listed under "unmatched issues needing device info". Issues that do
   not look like problem reports are skipped unless `--all` is given.

## Prefilled issue URL (for the Report a problem button)

GitHub issue forms accept `https://github.com/ashmerra/wehavephones/issues/new?template=bug_report.yml`
plus one query parameter per field id. Values must be URL-encoded (spaces as `%20`, newlines as
`%0A`).

| Parameter | Field | Value from the app |
| --- | --- | --- |
| `template` | form | always `bug_report.yml` |
| `title` | issue title | optional, for example `Graphics problem on OnePlus CPH2747` |
| `version` | WeHavePhones version | `0.0.2` |
| `phone` | Phone model | manufacturer and model, for example `OnePlus CPH2747` |
| `chipset` | Chipset and GPU | for example `Adreno (TM) 840` |
| `android` | Android version | for example `16` |
| `device-report` | Device report | the full report block, newlines encoded |
| `source` | Where did this report come from? | exactly `In the app` (a dropdown value must equal an option) |

Leave `game`, `what-happened`, `steps` and `logs` for the user (`source-link` is for reports from
elsewhere). Example (device report shortened to the identity lines):

```
https://github.com/ashmerra/wehavephones/issues/new?template=bug_report.yml&version=0.0.2&phone=OnePlus%20CPH2747&chipset=Adreno%20%28TM%29%20840&android=16&device-report=%3D%3D%20WeHavePhones%20device%20report%20%3D%3D%0Agl_renderer%3A%20Adreno%20%28TM%29%20840%0Agpu_vendor%3A%20adreno%0Aadreno_model%3A%20840%0Asoc%3A%20QTI%20SM8850%0Adevice%3A%20OnePlus%20CPH2747%0A%3D%3D%20end%20%3D%3D&source=In%20the%20app
```

URL length: URL encoding roughly doubles the size of the report, and GitHub rejects very long
URLs (around 8 KB). Keep the whole URL under about 7000 characters. Put only the device report in
the URL, never logs. If it is too long, drop the least useful lines first (`fingerprint`,
`vk_device`, `driver_probes`) and keep the header, `rule`, `profile`, `driver`, `gl_renderer`,
`gpu_vendor`, `adreno_model`, `soc` and `device` lines. A 400-character report encodes to about
800 characters in total, so the full block normally fits easily. The field still lets the user
paste the Diagnostics Copy output when the button cannot be used.

## Guidance for the issue-filing bot

For every issue created from an outside report, include:

- **Phone model as the reporter wrote it** (for example `OnePlus 15`, `Pixel 8a`, `Galaxy S24
  Ultra`, or a model code such as `SM-S928U`). If the reporter gave the chipset or GPU, include it
  as written. Do not guess a model that the reporter did not state.
- **Game version** (Classic Era, Wrath, TBC, Retail, build number if given) and WeHavePhones
  version if the reporter mentions it.
- **Symptom** in the title, ideally `<symptom> on <phone>` such as `Graphics corruption on OnePlus
  15`, plus one or two sentences of what the reporter described.
- **Source link** to the original post or comment, and the screenshot or video link if separate.

If the bot creates issues through the API instead of the form, use a body that mirrors the form
headings so `device-reports.py` reads both kinds the same way:

```markdown
### WeHavePhones version

0.0.2

### Phone model

OnePlus 15

### Chipset and GPU

Snapdragon 8 Elite Gen 5 / Adreno 840

### Android version

16

### Game and version

Retail

### What happened?

Multicolored noise and horizontal streaks across the character-selection scene.

### Where did this report come from?

Reported elsewhere (Reddit, Discord, etc.)

### Link to the original report

https://www.reddit.com/r/classicwow/comments/.../
```

Omit headings you have no data for rather than writing placeholders. Plain prose issues with the
phone name in the title or body still work (the script looks the name up in the catalog), but
structured headings are more reliable.

Suggested labels (not created, only suggestions for later): `source:external`, `source:in-app`,
`device:adreno-7xx`, `device:adreno-83x`, `device:adreno-84x`, `device:mali`, `device:xclipse`,
`device:powervr`, `needs-device-info`. The script treats any `device:*` or `source:*` label as a
sign that an issue is a problem report.

## From reports to a device-rules change

Once several reports point to one GPU family, check the rule and profile it matches and test
changes as described in [DEVICE_PROFILES.md](DEVICE_PROFILES.md). Add or correct a phone in
`scripts/device-catalog.json` when the script cannot place a reported phone.
