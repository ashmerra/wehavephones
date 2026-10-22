#!/usr/bin/env python3
"""Resilient adb helper for WeHavePhones device runs.

A phone can drop off wireless adb (or the VPN it is reached through) when WoW's
startup peak makes lmkd reclaim background apps. This helper keeps device work
alive across such drops:

  connect      retry `adb connect` with exponential backoff over all candidate
               serials (ANDROID_DEVICE/ADB_SERIAL, then WEHAVEPHONES_DEVICE_CANDIDATES);
               prints the working serial. With none configured it uses the only
               device `adb devices` lists, and stops with guidance if there are
               none or several.
  protect      whitelist each WEHAVEPHONES_PROTECTED_PACKAGES app from doze, pin its
               standby bucket, then ask all other third-party apps to trim memory.
               The protected apps and WeHavePhones are never asked to trim.
  run NAME -- CMD
               run CMD detached on the device (setsid+nohup) with output in
               /sdcard/wehavephones-launch/NAME.log and exit code in NAME.done, so a
               dropped adb does not kill it.
  wait NAME    reconnect as needed until NAME.done exists; print the exit code.
  stop-runtime force-stop the app and kill detached Wine processes.
  status       battery / MemAvailable / Wine processes.

Env: ADB_BIN, ANDROID_DEVICE / ADB_SERIAL, WEHAVEPHONES_DEVICE_CANDIDATES
(comma-separated), WEHAVEPHONES_PACKAGE (default com.wehavephones.dev, the debug
build; stop-runtime and status use run-as, which needs a debuggable app),
WEHAVEPHONES_PROTECTED_PACKAGES (comma-separated package names, such as the VPN
or remote-control app that keeps the phone reachable; the app package is always
protected and is the whole default), WEHAVEPHONES_RECONNECT_SECONDS (default 600).

Machine-local settings can live in local.env at the repository root (gitignored;
WEHAVEPHONES_LOCAL_ENV names another file): plain KEY=value lines and # comments.
It is parsed, never executed, and variables already in the environment win. The
other Python helpers load it through this module; shell scripts source
scripts/local-env.sh, which follows the same rules.
Never prints credentials; nothing here touches them.
"""
import argparse
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCAL_ADB = ROOT / '.tools/android-sdk/platform-tools/adb'


def load_local_env(path=None):
    """Set variables from local.env that the environment does not define; returns their names."""
    path = pathlib.Path(path or os.environ.get('WEHAVEPHONES_LOCAL_ENV') or ROOT / 'local.env')
    try:
        lines = path.read_text(encoding='utf-8', errors='replace').splitlines()
    except OSError:
        return []
    loaded = []
    for number, line in enumerate(lines, 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[len('export '):]
        key, equals, value = line.partition('=')
        key, value = key.strip(), value.strip()
        if not equals or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key):
            print('[local.env] %s:%d ignored, expected KEY=value' % (path, number), file=sys.stderr)
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in '"\'':
            value = value[1:-1]
        if key not in os.environ:
            os.environ[key] = value
            loaded.append(key)
    return loaded


load_local_env()

PKG = os.environ.get('WEHAVEPHONES_PACKAGE') or 'com.wehavephones.dev'
DEVICE_DIR = '/sdcard/wehavephones-launch'
PACKAGE_NAME = re.compile(r'[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*')


def parse_packages(value):
    """Package names from a comma-separated setting, in order and without duplicates.

    Anything that is not a plain package name is reported and skipped, because
    the names end up in `adb shell` command lines.
    """
    names = []
    for item in (value or '').split(','):
        item = item.strip()
        if not item or item in names:
            continue
        if PACKAGE_NAME.fullmatch(item):
            names.append(item)
        else:
            print('[wehavephones-device] WEHAVEPHONES_PROTECTED_PACKAGES: ignoring %r, not a package name' % item,
                  file=sys.stderr)
    return names


# Apps `protect` keeps alive in addition to the app itself.
PROTECTED_EXTRA = parse_packages(os.environ.get('WEHAVEPHONES_PROTECTED_PACKAGES'))
# Apps `protect` never asks to trim memory: those plus the app.
PROTECTED = set(PROTECTED_EXTRA) | {PKG}
# Serial of the only attached device, once single_device() picked it: later
# reconnects then stay on that phone instead of choosing again.
_discovered = []


def adb_bin():
    return os.environ.get('ADB_BIN') or (str(LOCAL_ADB) if LOCAL_ADB.is_file() else 'adb')


def explicit_serials():
    """ANDROID_DEVICE/ADB_SERIAL: when set, the only devices this helper may use.

    With neither set, the device single_device() picked is the only target.
    """
    configured = [value.strip() for value in (os.environ.get('ANDROID_DEVICE'), os.environ.get('ADB_SERIAL')) if value and value.strip()]
    return configured or list(_discovered)


def candidates():
    seen = []
    for value in explicit_serials() + os.environ.get('WEHAVEPHONES_DEVICE_CANDIDATES', '').split(','):
        value = value.strip()
        if value and value not in seen:
            seen.append(value)
    return seen


def attached_devices():
    """(serial, state) for every entry `adb devices` lists."""
    code, out, _ = adb(['devices'], timeout=10)
    if code != 0:
        return []
    devices = []
    listed = False
    for line in out.splitlines():
        fields = line.split()
        if line.startswith('List of devices attached'):
            listed = True
        elif listed and len(fields) >= 2:
            devices.append((fields[0], fields[1]))
    return devices


def single_device(flag=None):
    """Serial of the only device adb lists in state `device`; exits with guidance if there is none or several."""
    devices = attached_devices()
    ready = [serial for serial, state in devices if state == 'device']
    if len(ready) == 1:
        return ready[0]
    choose = 'set ANDROID_DEVICE in local.env%s' % (' or pass %s' % flag if flag else '')
    if ready:
        sys.exit('[wehavephones-device] No device is configured and several are connected (%s); %s.' %
                 (', '.join(ready), choose))
    listed = ', '.join('%s: %s' % entry for entry in devices)
    sys.exit('[wehavephones-device] No device is configured and none is ready%s; connect the phone or %s.' %
             (' (adb lists %s)' % listed if listed else '', choose))


def resolve_serial(explicit=None, flag=None):
    """The one device a script targets: an explicit flag, ADB_SERIAL, ANDROID_DEVICE, else the only attached device.

    flag names the script's own option (for example --device) in the guidance
    printed when nothing is configured and zero or several devices are attached.
    """
    for value in (explicit, os.environ.get('ADB_SERIAL'), os.environ.get('ANDROID_DEVICE')):
        if value and value.strip():
            return value.strip()
    if not _discovered:
        _discovered.append(single_device(flag))
    return _discovered[0]


def adb(args, serial=None, timeout=30, input_bytes=None):
    command = [adb_bin()] + (['-s', serial] if serial else []) + list(args)
    try:
        result = subprocess.run(command, input=input_bytes, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
        return result.returncode, result.stdout.decode(errors='replace'), result.stderr.decode(errors='replace')
    except subprocess.TimeoutExpired:
        return 124, '', 'timeout'


def shell(serial, command, timeout=30):
    return adb(['shell', command], serial, timeout)


def is_online(serial):
    code, out, _ = shell(serial, 'echo wehavephones-ok', timeout=12)
    return code == 0 and 'wehavephones-ok' in out


def usb_serials():
    return [serial for serial, state in attached_devices() if state == 'device' and ':' not in serial]


def reconnect(max_seconds=None, base_delay=2.0, max_delay=30.0, log=print, sleep=time.sleep):
    """Return a working serial (USB preferred), retrying with backoff; None on timeout.

    An explicit ANDROID_DEVICE/ADB_SERIAL is the only target: another phone
    that happens to be connected (USB or a TLS serial) is never substituted.
    With no device configured at all, the only attached device is chosen once
    (single_device() exits if there are none or several) and stays the target.
    """
    if max_seconds is None:
        max_seconds = float(os.environ.get('WEHAVEPHONES_RECONNECT_SECONDS', '600'))
    if not candidates():
        _discovered.append(single_device())
    deadline = time.monotonic() + max_seconds
    delay = base_delay
    attempt = 0
    while True:
        attempt += 1
        explicit = explicit_serials()
        for serial in ([] if explicit else usb_serials()):
            if is_online(serial):
                return serial
        for serial in explicit or candidates():
            if is_online(serial):
                if attempt > 1:
                    log('[wehavephones-device] reconnected to %s after %d attempt(s)' % (serial, attempt))
                return serial
            if ':' in serial:
                adb(['disconnect', serial], timeout=8)
                adb(['connect', serial], timeout=12)
                if is_online(serial):
                    log('[wehavephones-device] reconnected to %s after %d attempt(s)' % (serial, attempt))
                    return serial
        if time.monotonic() + delay > deadline:
            log('[wehavephones-device] device unreachable after %d attempt(s)' % attempt)
            return None
        log('[wehavephones-device] offline; retry in %.0fs' % delay)
        sleep(delay)
        delay = min(max_delay, delay * 2)


def require(serial=None):
    serial = serial if serial and is_online(serial) else reconnect()
    if not serial:
        sys.exit('[wehavephones-device] no device reachable')
    return serial


def protect(serial):
    steps = []
    for package in PROTECTED_EXTRA:
        steps.append('cmd deviceidle whitelist +%s' % shlex.quote(package))
        steps.append('am set-standby-bucket %s active' % shlex.quote(package))
    for step in steps:
        code, out, err = shell(serial, step)
        print('[wehavephones-device] %s -> rc=%d %s' % (step, code, (out + err).strip()[:120]))
    code, out, _ = shell(serial, 'pm list packages -3')
    trimmed = []
    for line in out.splitlines():
        package = line.replace('package:', '').strip()
        if not package or package in PROTECTED:
            continue
        code, pid, _ = shell(serial, 'pidof ' + shlex.quote(package), timeout=10)
        if code == 0 and pid.strip():
            shell(serial, 'am send-trim-memory %s RUNNING_CRITICAL' % shlex.quote(package), timeout=10)
            trimmed.append(package)
    print('[wehavephones-device] trim requested: %s' % (', '.join(trimmed) or 'none running'))


def run_detached(serial, name, command):
    script = '%s/%s.sh' % (DEVICE_DIR, name)
    body = ('#!/system/bin/sh\n'
            'rm -f %(dir)s/%(name)s.done\n'
            '( %(cmd)s ) > %(dir)s/%(name)s.log 2>&1\n'
            'echo $? > %(dir)s/%(name)s.done\n') % {'dir': DEVICE_DIR, 'name': name, 'cmd': command}
    shell(serial, 'mkdir -p %s' % DEVICE_DIR)
    code, _, err = adb(['shell', 'cat > %s' % script], serial, input_bytes=body.encode())
    if code != 0:
        raise SystemExit('[wehavephones-device] could not write %s: %s' % (script, err.strip()))
    shell(serial, 'rm -f %s/%s.done; setsid nohup sh %s > /dev/null 2>&1 < /dev/null &' % (DEVICE_DIR, name, script))
    print('[wehavephones-device] started %s detached (%s.log/.done)' % (name, name))


def read_device_file(serial, path):
    code, out, _ = shell(serial, 'cat %s 2>/dev/null' % shlex.quote(path))
    return out if code == 0 else None


def wait_done(name, timeout, serial=None, poll=5.0, sleep=time.sleep):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        serial = serial if serial and is_online(serial) else reconnect(max_seconds=max(30, deadline - time.monotonic()))
        if serial:
            done = read_device_file(serial, '%s/%s.done' % (DEVICE_DIR, name))
            if done and done.strip():
                return serial, int(done.strip().split()[0])
        sleep(poll)
    return serial, None


def is_wine_process(name):
    name = name.strip('[]').lower()
    return name.endswith('.exe') or name in ('wine', 'wine-preloader', 'wineserver')


def is_wine_program(argv0):
    """True when an argv[0] (a Windows or Unix path) names a Wine process."""
    return is_wine_process(argv0.replace('\\', '/').rsplit('/', 1)[-1])


# argv[0] of a thread other than the zombie leader, empty when none is left.
THREAD_ARGV0 = 'for t in /proc/{pid}/task/*; do [ "${{t##*/}}" = {pid} ] && continue; tr "\\0" "\\n" < "$t/cmdline" | head -n 1; break; done'


def stop_runtime(serial):
    """Force-stop the app and kill detached Wine processes; returns the PIDs it killed."""
    # A package-wide stop also affects Secure Folder (user 150). Only the base
    # app's test session is authorized by this helper.
    shell(serial, 'am force-stop --user 0 ' + PKG)
    listing = shell(serial, 'run-as %s sh -c %s' % (PKG, shlex.quote('ps -o PID,PPID,S,NAME -u "$(id -u)"')))[1]
    orphans = []
    for line in listing.splitlines()[1:]:
        fields = line.split(None, 3)
        if len(fields) != 4 or not fields[0].isdigit() or fields[1] != '1':
            continue
        if is_wine_process(fields[3]):
            orphans.append(fields[0])
        elif fields[2] == 'Z':
            # Blizzard renames its main thread, so a leftover Battle.net leader
            # shows as "Main". Its remaining threads still report the exe.
            argv0 = shell(serial, 'run-as %s sh -c %s' % (PKG, shlex.quote(THREAD_ARGV0.format(pid=fields[0]))))[1]
            if is_wine_program(argv0.strip()):
                orphans.append(fields[0])
    if orphans:
        shell(serial, 'run-as %s sh -c %s' % (PKG, shlex.quote('kill -9 %s 2>/dev/null || true' % ' '.join(orphans))))
    print('[wehavephones-device] runtime stopped%s' % (' (killed %s)' % ', '.join(orphans) if orphans else ''))
    return orphans


def battery(serial):
    out = shell(serial, 'dumpsys battery')[1]
    level, ac, usb = -1, False, False
    for line in out.splitlines():
        line = line.strip()
        if line.startswith('level:'):
            level = int(line.split(':')[1])
        elif line.startswith('AC powered:'):
            ac = line.endswith('true')
        elif line.startswith('USB powered:'):
            usb = line.endswith('true')
    return level, ac or usb


def status(serial):
    level, powered = battery(serial)
    mem = shell(serial, 'grep MemAvailable /proc/meminfo')[1].strip()
    listing = shell(serial, 'run-as %s sh -c %s' % (PKG, shlex.quote('ps -o PID,RSS,NAME -u "$(id -u)"')))[1]
    procs = []
    for line in listing.splitlines()[1:]:
        fields = line.split(None, 2)
        if len(fields) == 3 and is_wine_process(fields[2]):
            procs.append(line.strip())
    print('serial=%s battery=%d%% powered=%s %s' % (serial, level, powered, mem))
    print('\n'.join(procs) or '(no Wine game/launcher processes)')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='cmd', required=True)
    sub.add_parser('connect')
    sub.add_parser('protect')
    sub.add_parser('stop-runtime')
    sub.add_parser('status')
    run = sub.add_parser('run')
    run.add_argument('name')
    run.add_argument('command', nargs=argparse.REMAINDER)
    wait = sub.add_parser('wait')
    wait.add_argument('name')
    wait.add_argument('--timeout', type=float, default=900)
    args = parser.parse_args()

    if args.cmd == 'connect':
        serial = reconnect()
        if not serial:
            return 1
        print(serial)
        return 0
    serial = require(os.environ.get('ANDROID_DEVICE') or os.environ.get('ADB_SERIAL'))
    if args.cmd == 'protect':
        protect(serial)
    elif args.cmd == 'stop-runtime':
        stop_runtime(serial)
    elif args.cmd == 'status':
        status(serial)
    elif args.cmd == 'run':
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        run_detached(serial, args.name, ' '.join(command))
    elif args.cmd == 'wait':
        serial, code = wait_done(args.name, args.timeout, serial)
        print('exit=%s' % code)
        return 0 if code == 0 else 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
