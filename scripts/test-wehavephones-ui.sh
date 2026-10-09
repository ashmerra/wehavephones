#!/usr/bin/env bash
# Validate an already deployed debug build on an explicitly selected, unlocked phone.
# No emulator, container setup, game launch, preference fixture or extra APK is used.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -f "$root/.tools/env.sh" ]]; then source "$root/.tools/env.sh"; fi
# Machine-local settings from local.env (ANDROID_DEVICE, WEHAVEPHONES_PACKAGE, ...).
# shellcheck disable=SC1091
source "$root/scripts/local-env.sh"
package="${WEHAVEPHONES_PACKAGE:-com.wehavephones.dev}"
phone_serial="${ANDROID_DEVICE:-}"
if [[ "${1:-}" == --serial ]]; then phone_serial="${2:-}"; shift 2; fi
if [[ $# -gt 0 || -z "$phone_serial" ]]; then
  echo "Usage: scripts/test-wehavephones-ui.sh --serial <verified-device> (or set ANDROID_DEVICE)" >&2; exit 2
fi
phone_adb="${ADB_BIN:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
python3 - "$phone_adb" "$phone_serial" "$root/out/phone-ui" "$package" <<'PY'
from pathlib import Path
import io, re, subprocess, sys, tarfile
adb, serial, directory, package = sys.argv[1:]
out = Path(directory); out.mkdir(parents=True, exist_ok=True)
def call(*args, timeout=30):
    return subprocess.check_output([adb, '-s', serial, *args], timeout=timeout)
identity = call('shell','getprop ro.product.model; getprop ro.serialno').decode().strip()
print('Validating native UI on:', identity.replace('\n', ' / '))
processes=call('shell','ps -A -o NAME').decode()
if re.search(r'(?im)^(?:.*[/ ])?(?:wine(?:64)?(?:-preloader)?|wineserver|wow[^\s]*|battle\.net[^\s]*)(?:\.exe)?\s*$', processes):
    raise SystemExit('A live Wine/game session is running; leave it intact and obtain owner approval before testing')
result=call('shell','am','instrument','-w','-r',package+'/com.wehavephones.mobile.PhoneUiValidation',timeout=180).decode()
(out/'instrumentation.log').write_text(result)
print(result)
if 'PASS:' not in result or 'FAIL:' in result: raise SystemExit('Native UI validation failed')
archive=call('exec-out','run-as',package,'tar','-C','cache/ui-validation','-cf','-','.',timeout=45)
with tarfile.open(fileobj=io.BytesIO(archive)) as files:
    for member in files.getmembers():
        if not member.isfile() or not re.fullmatch(r'(?:\./)?[a-z-]+\.png', member.name): continue
        with files.extractfile(member) as image: (out/Path(member.name).name).write_bytes(image.read())
(out/'crash.log').write_bytes(call('logcat','-d','-b','crash'))
call('shell','am','start','-W','-n',package+'/com.wehavephones.mobile.WeHavePhonesActivity')
(out/'installed-first-run.png').write_bytes(call('exec-out','screencap','-p',timeout=45))
print('Screenshots and crash buffer:', out)
PY
