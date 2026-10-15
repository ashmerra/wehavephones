#!/usr/bin/env python3
"""Measure Battle.net boot only; never click Play or start a game.

Requires an installed debug build whose Open Battle.net selects the game page.
One setting changes at a time, via a backed-up launcher shortcut. The container,
Blizzard files, sign-in and caches are untouched. Results go under ignored out/.
Requires Pillow. Check the final page crop visually before trusting its detector.
"""
import argparse
import datetime
import hashlib
import importlib.util
import io
import json
import pathlib
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageStat

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('device', ROOT / 'scripts/wehavephones-device.py')
dev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev)
PKG = dev.PKG
CANDIDATES = {
    'baseline': {},
    'split-locks': {'FEX_STRICTINPROCESSSPLITLOCKS': '1'},
    'full-smc': {'FEX_SMCCHECKS': 'full'},
    'no-smc': {'FEX_SMCCHECKS': 'none'},
    'no-tso': {'FEX_TSOENABLED': '0'},
    'no-vector-tso': {'FEX_VECTORTSOENABLED': '0'},
    'no-memcpy-tso': {'FEX_MEMCPYSETTSOENABLED': '0'},
    'reduced-x87': {'FEX_X87REDUCEDPRECISION': '1'},
    'v8-jitless': {'WHP_BNET_V8_MODE': 'jitless'},
    'v8-no-opt': {'WHP_BNET_V8_MODE': 'no-opt'},
    'small-blocks': {'FEX_MAXINST': '500'},
    'multiblock': {'FEX_MULTIBLOCK': '1'},
    'dynamic-l1': {'FEX_DYNAMICL1CACHE': '1'},
    'no-l2': {'FEX_DISABLEL2CACHE': '1'},
}
OWN_SESSION = False
PRESERVE_FAILED_SESSION = False


def trial_configurations(configurations, attempt, alternate_order):
    return list(reversed(configurations)) if alternate_order and attempt % 2 == 0 else list(configurations)


def battlenet_versions(process_lines):
    return set(re.findall(r'--user-agent-product=Battle\.net/([0-9.]+)', '\n'.join(process_lines)))


def shell(serial, command, timeout=30):
    code, out, err = dev.shell(serial, command, timeout)
    if code:
        raise RuntimeError('ADB command failed (%d): %s' % (code, err.strip()))
    return out


def private(serial, command, data=None):
    code, out, err = dev.adb(['shell', 'run-as %s sh -c %s' % (PKG, shlex.quote(command))],
                             serial, input_bytes=data)
    if code:
        raise RuntimeError('App-private command failed: ' + err.strip())
    return out


def processes(serial):
    out = private(serial, 'ps -o PID,PPID,STAT,COMM,ARGS -u "$(id -u)"')
    live = []
    for line in out.splitlines()[1:]:
        fields = line.split(None, 4)
        # Android can retain reaped Wine service zombies under init. They have
        # no running session to preserve and cannot be killed again.
        if len(fields) >= 4 and not fields[2].startswith('Z'):
            live.append(' '.join(fields[:2] + [fields[3].strip('[]')] + fields[4:]))
    return live


def game_running(lines):
    return any(re.search(r'(?i)\bWow(?:B|T|Classic)?(?:-ARM64)?\.exe\b', line) for line in lines)


def runtime_running(lines):
    return any(dev.is_wine_process(line.split()[2]) or line.split()[2].startswith('Cr')
               for line in lines if len(line.split()) >= 3)


def app_pid(lines):
    for line in lines:
        fields = line.split(None, 3)
        if len(fields) == 4 and fields[3] == PKG:
            return fields[0]
    return None


def app_exit_reason(text, pid):
    for block in re.split(r'ApplicationExitInfo #\d+:', text)[1:]:
        identity = re.search(r'\bpid=(\d+)\b', block)
        reason = re.search(r'\breason=(\d+) \(([^)]+)\)', block)
        if identity and identity[1] == str(pid) and reason:
            return {'pid': int(pid), 'reason_code': int(reason[1]), 'reason': reason[2]}
    return None


def ensure_unlocked(serial):
    shell(serial, 'input keyevent KEYCODE_WAKEUP')
    policy = shell(serial, 'dumpsys window policy')
    if re.search(r'\b(?:mIsShowing|showing|mShowingLockscreen)=true\b', policy):
        raise RuntimeError('Phone keyguard is showing; unlock the phone before testing')


def touch_protection_visible(windows):
    return any('IgniteTouchProtectionPresenter' in block and 'isVisible=true' in block
               for block in re.split(r'(?m)^  Window #', windows))


def temperature(serial):
    out = shell(serial, 'dumpsys battery')
    match = re.search(r'^\s*temperature:\s*(\d+)', out, re.M)
    if not match:
        raise RuntimeError('Cannot read battery temperature')
    return int(match[1]) / 10


def stop_owned_session(serial):
    global OWN_SESSION
    dev.stop_runtime(serial)
    # Android's force-stop and the Wine orphan reaper return before all dying
    # children disappear from ps. Do not mistake that exit tail for a new run.
    for _ in range(20):
        if not runtime_running(processes(serial)):
            OWN_SESSION = False
            return
        time.sleep(.25)
    raise RuntimeError('Previous test session has not exited; comparison stopped')


def cleanup_owned_session(serial):
    if OWN_SESSION and not PRESERVE_FAILED_SESSION and not game_running(processes(serial)):
        stop_owned_session(serial)


def set_override(original, values):
    text = original.decode()
    if not values:
        return original
    # Preserve every unrelated shortcut key and its original environment.
    lines = text.splitlines()
    env_index = next((i for i, line in enumerate(lines) if line.startswith('envVars=')), None)
    env = lines[env_index][len('envVars='):] if env_index is not None else ''
    for key, value in values.items():
        env = re.sub(r'(?:^|\s)' + re.escape(key) + r'=[^\s]*', '', env).strip()
        env += (' ' if env else '') + key + '=' + value
    if env_index is None:
        if '[Extra Data]' not in lines:
            lines.extend(['', '[Extra Data]'])
        lines.append('envVars=' + env)
    else:
        lines[env_index] = 'envVars=' + env
    return ('\n'.join(lines) + '\n').encode()


def tap_open(serial):
    shell(serial, 'am start -W -n %s/com.wehavephones.mobile.WeHavePhonesActivity' % PKG)
    for _ in range(5):
        shell(serial, 'uiautomator dump /sdcard/whp-boot-benchmark-ui.xml >/dev/null')
        xml = shell(serial, 'cat /sdcard/whp-boot-benchmark-ui.xml')
        for node in ET.fromstring(xml).iter('node'):
            if node.get('resource-id', '').endswith(':id/BTOpenBattleNet') and node.get('enabled') == 'true':
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                clock = shell(serial, 'cat /proc/uptime; date +%s').splitlines()
                before = float(clock[0].split()[0])
                epoch = int(clock[1])
                shell(serial, 'input tap %d %d' % ((x1 + x2) // 2, (y1 + y2) // 2))
                return before, epoch
        shell(serial, 'input keyevent KEYCODE_BACK')
        time.sleep(1)
    raise RuntimeError('Open Battle.net button unavailable; no Play button was tapped')


def capture(serial):
    # Device uptime immediately before/after screencap brackets the frame time.
    result = subprocess.run([dev.adb_bin(), '-s', serial, 'exec-out',
                             'sh', '-c', 'cat /proc/uptime; screencap -p; cat /proc/uptime'],
                            capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError('Screenshot transport failed')
    head, payload = result.stdout.split(b'\n', 1)
    end = payload.rfind(b'IEND') + 8
    png, tail = payload[:end], payload[end:]
    return float(head.split()[0]), float(tail.split()[0]), Image.open(io.BytesIO(png)).convert('RGB')


def mean(image, region):
    x, y, w, h = region
    return tuple(round(v, 2) for v in ImageStat.Stat(image.crop((x, y, x + w, y + h))).mean)


def is_play(rgb):
    r, g, b = rgb
    return b > 150 and b - r > 60


def play_label_pixels(image, region):
    # The blue placeholder is painted before the label. Require white glyphs
    # in its center; a dark loading window and the button's border do not pass.
    x, y, w, h = region
    label = image.crop((x + w * .3, y, x + w * .7, y + h))
    return sum(min(pixel) > 215 for pixel in label.getdata())


def region_box(region):
    x, y, w, h = region
    return (x, y, x + w, y + h)


def validate_geometry(size, region):
    x, y, w, h = region
    return (min(size) > 0 and x >= 0 and y >= 0 and w > 0 and h > 0
            and x + w <= size[0] and y + h <= size[1])


def ready_outcome(label):
    return label.lower() + '-rendered'


def redact_ready(image, panel_region):
    # Keep only the calibrated game panel, regardless of account header layout.
    redacted = Image.new('RGB', image.size, 'black')
    box = region_box(panel_region)
    redacted.paste(image.crop(box), box[:2])
    return redacted


def fresh_log(file, tap_epoch):
    match = re.search(r'(?:battle.net|libcef)-(\d{8}T\d{6})\.(\d+)\.log$', file)
    if not match:
        return False
    started = datetime.datetime.strptime(match[1], '%Y%m%dT%H%M%S').replace(tzinfo=datetime.timezone.utc).timestamp()
    return started + float('0.' + match[2]) >= tap_epoch - 2


def sanitized_evidence(serial, target, container, tap_epoch):
    # Retain diagnostics, not arbitrary launcher logs or account/token records.
    app_pids = [line.split()[0] for line in processes(serial)
                if len(line.split(None, 3)) == 4 and line.split(None, 3)[3] == PKG]
    out = shell(serial, 'logcat -d -v brief --pid=' + app_pids[0]) if app_pids else ''
    logs = shell(serial, 'ls -t /sdcard/Download/WeHavePhones/wine-*.log 2>/dev/null || true').splitlines()
    if logs:
        out += shell(serial, 'tail -n 6000 ' + shlex.quote(logs[0]))
    directory = 'files/imagefs/home/xuser-%d/.wine/drive_c/users/xuser/AppData/Local/Battle.net/Logs' % container
    cef = private(serial, 'ls -t ' + shlex.quote(directory) + '/libcef-*.log 2>/dev/null || true').splitlines()
    cef = [file for file in cef if fresh_log(file, tap_epoch)]
    if cef:
        out += private(serial, 'cat ' + shlex.quote(cef[0]))
    keep = []
    for line in out.splitlines():
        if ('Guest env (arm64ec):' in line or 'Launching guest command:' in line
                or re.search(r'(?:err:|warn:|ERROR:|FATAL:|Unhandled exception|FEX-|FEX:|SIGSEGV|SIGABRT|fdsan:)', line)):
            if re.search(r'(?i)(password|token|authorization|account|email|ticket|credential|https?://)', line):
                continue
            keep.append(re.sub(r'[^\s<>]+@[^\s<>]+', '[redacted]', line))
    (target / 'diagnostics.log').write_text('\n'.join(keep) + '\n')
    crash = shell(serial, 'logcat -d -b crash -v brief')
    # Preserve crash summaries/stacks, excluding potentially sensitive messages.
    crash = '\n'.join(line for line in crash.splitlines()
                      if re.search(r'(?:FATAL EXCEPTION|Process:|signal \d|backtrace:|^.*#[0-9]+|^.*\bat [\w.]+\()', line))
    (target / 'android-crashes.log').write_text(crash + '\n')


def launcher_state(serial, container, tap_epoch=None):
    directory = 'files/imagefs/home/xuser-%d/.wine/drive_c/users/xuser/AppData/Local/Battle.net/Logs' % container
    files = private(serial, 'ls -t ' + shlex.quote(directory) + '/battle.net-*.log 2>/dev/null || true').splitlines()
    for file in files[:4]:
        if tap_epoch is not None and not fresh_log(file, tap_epoch):
            continue
        text = private(serial, 'cat ' + shlex.quote(file))
        if not re.search(r'Opening IPC shared memory\..*mode=server', text):
            continue
        stamps = re.findall(r'^[A-Z] (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)', text, re.M)
        start = datetime.datetime.fromisoformat(stamps[0]) if stamps else None
        events = []
        for line in text.splitlines():
            stamp = re.match(r'^[A-Z] (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d+)', line)
            if not stamp or start is None:
                continue
            elapsed = (datetime.datetime.fromisoformat(stamp[1]) - start).total_seconds()
            if 'Logged into Battle.net successfully.' in line:
                events.append({'event': 'signed-in', 'after_log_start_s': elapsed})
            elif 'login failed' in line.lower():
                events.append({'event': 'auth-login-failed', 'after_log_start_s': elapsed})
            elif 'UAuth: tassadar Login URL is empty!' in line:
                events.append({'event': 'auth-url-missing', 'after_log_start_s': elapsed})
            elif 'UAuth: browser error:' in line:
                match = re.search(r'errorCode=(\d+)', line)
                events.append({'event': 'auth-browser-error', 'after_log_start_s': elapsed,
                               'code': int(match[1]) if match else None})
            elif 'InstallState (' in line:
                match = re.search(r'InstallState \(([^)]+)\)', line)
                if not match:
                    continue
                uid = match[1]
                fields = dict(re.findall(r'\b(currentOperation|playable|localVersion|latestVersion)=([\w.]+)', line))
                events.append({'event': 'product-state', 'uid': uid, 'after_log_start_s': elapsed, **fields})
            elif re.search(r'(?i)(mojo|renderer).*(?:timed out|crash|failed)', line):
                events.append({'event': 'renderer-error', 'after_log_start_s': elapsed})
            elif 'Scope:Root for Browser' in line:
                events.append({'event': 'browser-observer-destroyed' if 'Destroying' in line else 'browser-observer-created',
                               'after_log_start_s': elapsed})
        codes = sorted(set(re.findall(r'\bBLZ[A-Z0-9]{5,}\b', text)))
        return {'file': pathlib.PurePosixPath(file).name, 'events': events, 'client_error_codes': codes}
    return {'events': []}


def run_attempt(args, serial, name, shortcut, original, label):
    global OWN_SESSION, PRESERVE_FAILED_SESSION
    target = args.output / name
    target.mkdir()
    if runtime_running(processes(serial)):
        raise RuntimeError('Unexpected live session; refusing to replace it')
    ensure_unlocked(serial)
    dev.stop_runtime(serial)
    cooldown_start = time.monotonic()
    time.sleep(args.cooldown)
    cooling_deadline = time.monotonic() + 900
    while temperature(serial) > args.max_temperature:
        if time.monotonic() >= cooling_deadline:
            raise RuntimeError('Phone did not cool to configured temperature; comparison stopped')
        ensure_unlocked(serial)
        print(name + ': cooling', flush=True)
        time.sleep(30)
    if runtime_running(processes(serial)):
        raise RuntimeError('Another session appeared during cooldown; refusing to replace it')
    ensure_unlocked(serial)
    override = set_override(original, CANDIDATES[label])
    private(serial, 'cat > ' + shlex.quote(shortcut), override)
    shell(serial, 'input keyevent KEYCODE_WAKEUP; cmd statusbar collapse')
    temp_start = temperature(serial)
    OWN_SESSION = True
    tap, tap_epoch = tap_open(serial)
    initial_app_pid = app_pid(processes(serial))
    print(name + ': started', flush=True)
    t0 = None
    previous = False
    rows = []
    pids = set()
    versions = set()
    browser_flag_roles = set()
    browser_mode = CANDIDATES[label].get('WHP_BNET_V8_MODE')
    result = {'name': name, 'configuration': label, 'override': CANDIDATES[label],
              'ready_label': args.ready_label,
              'app_pid': initial_app_pid,
              'tap_uptime': tap, 'temperature_start_c': temp_start,
              'tap_epoch': tap_epoch,
              'cooldown_s': round(time.monotonic() - cooldown_start, 1),
              'outcome': 'no-%s-within-window' % args.ready_label.lower(), 'window_s': args.window}
    next_report = time.monotonic() + 30
    start = time.monotonic()
    last_image = None
    while time.monotonic() - start < args.window:
        procs = processes(serial)
        if game_running(procs):
            raise RuntimeError('A game appeared; preserving the live session and stopping the benchmark')
        if initial_app_pid is None or app_pid(procs) != initial_app_pid:
            result['outcome'] = 'host-app-exited'
            history = shell(serial, 'dumpsys activity exit-info ' + PKG)
            result['app_exit_reason'] = app_exit_reason(history, initial_app_pid)
            break
        now = float(shell(serial, 'cat /proc/uptime').split()[0])
        clients = [line for line in procs if 'Battle.net.exe' in line]
        versions.update(battlenet_versions(clients))
        if browser_mode:
            for line in clients:
                if '--js-flags=--' + browser_mode in line:
                    role = re.search(r'--type=([a-z-]+)', line)
                    browser_flag_roles.add(role[1] if role else 'main')
        if clients and t0 is None:
            t0 = now
            result['process_uptime'] = t0
        for line in clients:
            # CEF can briefly hide its --type argument during initialization.
            # Count only confirmed browser main processes, never transient
            # unclassified children. Battle.net's server log cross-checks this.
            fields = line.split(None, 3)
            if len(fields) >= 3 and fields[2] == 'CrBrowserMain':
                pids.add(fields[0])
        before, after, img = capture(serial)
        if img.size != tuple(args.capture_size):
            raise RuntimeError('Capture size changed. Recalibrate regions for this device')
        rgb = mean(img, args.play_region)
        label_pixels = play_label_pixels(img, args.play_region)
        painted = is_play(rgb) and label_pixels >= 100 and t0 is not None
        memory = shell(serial, 'cat /proc/meminfo')
        rows.append({'before_uptime': before, 'after_uptime': after, 'play_rgb': rgb, 'label_white_pixels': label_pixels,
                     'client_count': len(clients),
                     'mem_available_kb': int(re.search(r'MemAvailable:\s*(\d+)', memory)[1]),
                     'swap_free_kb': int(re.search(r'SwapFree:\s*(\d+)', memory)[1])})
        # Only retain the left game panel (no account header, chats or credentials).
        img.crop(region_box(args.panel_region)).save(target / ('panel-%04d.png' % len(rows)))
        last_image = img
        if painted and previous:
            elapsed = round(after - t0, 2)
            result.update(outcome=ready_outcome(args.ready_label), ready_uptime=after,
                          boot_to_ready_s=elapsed, tap_to_ready_s=round(after - tap, 2))
            if args.ready_label == 'Play':
                result.update(play_uptime=after, boot_to_play_s=elapsed,
                              tap_to_play_s=result['tap_to_ready_s'])
            redact_ready(img, args.panel_region).save(target / 'ready-redacted.png')
            break
        previous = painted
        if time.monotonic() >= next_report:
            if touch_protection_visible(shell(serial, 'dumpsys window windows')):
                result['outcome'] = 'screen-obstructed'
                break
            print('%s: %.0fs, %d client processes' % (name, time.monotonic() - start, len(clients)), flush=True)
            next_report += 30
        time.sleep(args.interval)
    result.update(temperature_end_c=temperature(serial), main_client_pids=sorted(pids),
                  main_replacements=max(0, len(pids) - 1) if pids else None, samples=len(rows),
                  battlenet_versions=sorted(versions), browser_flag_roles=sorted(browser_flag_roles))
    if last_image is not None:
        last_image.crop(region_box(args.panel_region)).save(target / 'final-panel.png')
    (target / 'samples.json').write_text(json.dumps(rows, indent=2) + '\n')
    # Verify actual session environment rather than trusting persisted JSON alone.
    env_lines = shell(serial, 'logcat -d -v brief -s GuestLauncher:I')
    env_lines = [line for line in env_lines.splitlines() if 'Guest env (arm64ec):' in line]
    latest_env = env_lines[-1] if env_lines else ''
    result['effective_fex_env'] = dict(re.findall(r'\b(FEX_[A-Z0-9_]+)=([^\s]+)', latest_env))
    effective_overrides = {}
    for key, value in CANDIDATES[label].items():
        match = re.search(r'\b' + re.escape(key) + r'=([^\s]+)', latest_env)
        effective_overrides[key] = match[1] if match else None
        if effective_overrides[key] != value:
            raise RuntimeError('Candidate was not present in effective session environment: ' + key)
    result['effective_overrides'] = effective_overrides
    if label in ('v8-jitless', 'v8-no-opt'):
        expected = '--js-flags=--' + CANDIDATES[label]['WHP_BNET_V8_MODE']
        batch = private(serial, 'cat files/imagefs/home/xuser-%d/.wine/drive_c/bnet.bat' % args.container)
        if expected not in batch:
            raise RuntimeError('Installed app did not apply the requested browser diagnostic')
        result['verified_browser_flag'] = expected
    sanitized_evidence(serial, target, args.container, tap_epoch)
    result['launcher_state'] = launcher_state(serial, args.container, tap_epoch)
    (target / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in ('name', 'outcome', 'temperature_start_c', 'temperature_end_c')}
                     | {'boot_to_ready_s': result.get('boot_to_ready_s'),
                        'boot_to_play_s': result.get('boot_to_play_s')}), flush=True)
    if args.keep_failed_session and result['outcome'] != ready_outcome(args.ready_label):
        PRESERVE_FAILED_SESSION = True
    else:
        stop_owned_session(serial)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial')
    parser.add_argument('--container', type=int, default=1)
    parser.add_argument('--runs', type=int, default=5)
    parser.add_argument('--configurations', nargs='+', choices=CANDIDATES, default=['baseline'])
    parser.add_argument('--alternate-order', action='store_true',
                        help='Reverse configuration order every second round to balance sequence effects')
    parser.add_argument('--keep-failed-session', action='store_true',
                        help='Stop the comparison and leave a timed-out test session open for diagnosis')
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--window', type=float, default=195)
    parser.add_argument('--interval', type=float, default=1)
    parser.add_argument('--cooldown', type=float, default=30)
    parser.add_argument('--max-temperature', type=float, default=39.5)
    parser.add_argument('--capture-size', type=int, nargs=2, default=(2340, 1080),
                        metavar=('WIDTH', 'HEIGHT'), help='Expected screenshot size. Calibrate regions before changing')
    parser.add_argument('--panel-region', type=int, nargs=4, default=(300, 200, 480, 650),
                        metavar=('X', 'Y', 'WIDTH', 'HEIGHT'), help='Safe game-panel crop without account details')
    parser.add_argument('--ready-label', choices=['Play', 'Install'], default='Play',
                        help='Visually verified ready button. Install is for containers without a game')
    parser.add_argument('--button-region', '--play-region', dest='play_region', type=int, nargs=4,
                        default=(380, 695, 300, 50), metavar=('X', 'Y', 'WIDTH', 'HEIGHT'))
    args = parser.parse_args()
    if args.runs < 1 or args.container < 1 or args.window <= 0 or args.interval < 0 or args.cooldown < 0:
        parser.error('Run count/container/window must be positive; interval/cooldown nonnegative')
    if not all(validate_geometry(args.capture_size, region) for region in [args.panel_region, args.play_region]):
        parser.error('Panel and button regions must fit within the positive capture size')
    serial = dev.resolve_serial(args.serial, '--serial')
    dev.require(serial)
    identity = shell(serial, 'getprop ro.product.model; getprop ro.serialno; getprop ro.build.version.release')
    ensure_unlocked(serial)
    if runtime_running(processes(serial)):
        raise RuntimeError('Live Wine/Battle.net/game session exists; refusing to replace it')
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    shortcut = 'files/imagefs/home/xuser-%d/.wine/drive_c/users/xuser/Desktop/Battle.net.desktop' % args.container
    original = private(serial, 'cat ' + shlex.quote(shortcut)).encode()
    (args.output / 'shortcut-original.desktop').write_bytes(original)
    container = json.loads(private(serial, 'cat files/imagefs/home/xuser-%d/.container' % args.container))
    (args.output / 'metadata.json').write_text(json.dumps({'identity': identity.splitlines(),
        'runtime': container.get('wineVersion'), 'fex_asset_identifier': container.get('fexcoreVersion'),
        'graphics': container.get('graphicsDriverConfig'), 'screen': container.get('screenSize'),
        'app_commit': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip(),
        'shortcut_sha256': hashlib.sha256(original).hexdigest(),
        'configurations': args.configurations, 'runs': args.runs,
        'alternate_order': args.alternate_order,
        'capture_size': args.capture_size, 'panel_region': args.panel_region,
        'button_region': args.play_region, 'ready_label': args.ready_label,
        'definition': 'First observed Battle.net.exe to second consecutive blue %s frame with white label glyphs. Upper screenshot timestamp' % args.ready_label,
        'game_launch_validation': 'Excluded by user: Battle.net boot only'}, indent=2) + '\n')
    results = []
    expected_versions = None
    try:
        # Interleave conditions to reduce thermal/time/network drift.
        for attempt in range(1, args.runs + 1):
            for label in trial_configurations(args.configurations, attempt, args.alternate_order):
                result = run_attempt(args, serial, '%s-%02d' % (label, attempt), shortcut, original, label)
                results.append(result)
                (args.output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
                if PRESERVE_FAILED_SESSION:
                    raise RuntimeError('Failed test session preserved for diagnostics. Original shortcut will be restored.')
                versions = result['battlenet_versions']
                if versions:
                    if expected_versions is not None and versions != expected_versions:
                        raise RuntimeError('Battle.net version changed between trials. Re-establish the baseline.')
                    expected_versions = versions
    finally:
        # Never close a game started by the owner while the benchmark was running.
        cleanup_owned_session(serial)
        private(serial, 'cat > ' + shlex.quote(shortcut), original)
        restored = private(serial, 'cat ' + shlex.quote(shortcut)).encode()
        if restored != original:
            raise RuntimeError('Launcher shortcut rollback verification failed')
        print('Restored original launcher shortcut', flush=True)


if __name__ == '__main__':
    main()
