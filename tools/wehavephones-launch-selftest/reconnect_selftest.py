#!/usr/bin/env python3
"""Self-test for scripts/wehavephones-device.py against tools/.../fake_adb.py (no device).

All addresses are RFC 5737 documentation addresses. The test never reads the
machine's real local.env: every scenario points WEHAVEPHONES_LOCAL_ENV at an
empty file unless it supplies its own.
"""
import contextlib
import importlib.util
import io
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
FAKE = HERE / 'fake_adb.py'
FAILURES = []
CHECKS = [0]
ORIGINAL_ENV = dict(os.environ)
PHONE = '192.0.2.10:5555'
OTHER = '198.51.100.9:5555'
EXTRA = '203.0.113.7:5555'
USB = 'TESTUSB0001'
# The two built-in addresses (Tailscale and LAN) were removed; the scenarios that
# need the phone as a candidate now configure it, plus a second address that is never online.
CONFIGURED = {'WEHAVEPHONES_DEVICE_CANDIDATES': '%s,%s' % (PHONE, EXTRA)}


def check(name, expected, actual):
    CHECKS[0] += 1
    if expected == actual:
        print('ok   %s' % name)
    else:
        FAILURES.append(name)
        print('FAIL %s: expected %r got %r' % (name, expected, actual))


class FakeClock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


def load(env):
    os.environ.clear()
    os.environ.update({key: value for key, value in ORIGINAL_ENV.items()
                       if not key.startswith('FAKE_ADB_') and key not in (
                           'ANDROID_DEVICE', 'ADB_SERIAL', 'WEHAVEPHONES_DEVICE_CANDIDATES',
                           'WEHAVEPHONES_PACKAGE', 'WEHAVEPHONES_PROTECTED_PACKAGES',
                           'WEHAVEPHONES_LOCAL_ENV')})
    os.environ['WEHAVEPHONES_LOCAL_ENV'] = os.devnull
    os.environ.update(env)
    spec = importlib.util.spec_from_file_location('wehavephones_device', ROOT / 'scripts/wehavephones-device.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    clock = FakeClock()
    module.time = clock
    return module, clock


def scenario(name, **env):
    state = pathlib.Path(tempfile.mkdtemp(prefix='fake-adb-'))
    base = {'ADB_BIN': str(FAKE), 'FAKE_ADB_STATE': str(state), 'FAKE_ADB_SERIAL': PHONE}
    base.update({k: str(v) for k, v in env.items()})
    module, clock = load(base)
    return module, clock, state


def calls(state):
    log = state / 'calls.log'
    return log.read_text() if log.is_file() else ''


def exit_message(function, *args, **kwargs):
    try:
        function(*args, **kwargs)
    except SystemExit as error:
        return str(error.code)
    return None


def main():
    os.chmod(FAKE, 0o755)
    logs = []

    # 1. candidate order: explicit serials first, then the configured candidates, deduplicated.
    #    Nothing is built in, and nothing configured means no candidates.
    module, _, state = scenario('order', ANDROID_DEVICE=OTHER, WEHAVEPHONES_DEVICE_CANDIDATES='%s, %s' % (EXTRA, OTHER))
    check('candidate order', [OTHER, EXTRA], module.candidates())
    shutil.rmtree(state)
    module, _, state = scenario('nothing')
    check('no built-in candidates', [], module.candidates())
    shutil.rmtree(state)

    # 2. comes back after 3 connects: backoff doubles, returns the working serial.
    module, clock, state = scenario('backoff', FAKE_ADB_ONLINE_AFTER=3, **CONFIGURED)
    serial = module.reconnect(max_seconds=600, sleep=clock.sleep, log=logs.append)
    check('reconnect returns serial', PHONE, serial)
    check('backoff sleeps', [2.0], clock.sleeps)
    check('reconnect logged', True, any('reconnected' in line for line in logs))
    shutil.rmtree(state)

    # 3. never comes back: gives up at the deadline with capped backoff.
    module, clock, state = scenario('offline', FAKE_ADB_ONLINE_AFTER=10**6, **CONFIGURED)
    serial = module.reconnect(max_seconds=120, sleep=clock.sleep, log=logs.append)
    check('offline returns None', None, serial)
    check('offline backoff capped', [2.0, 4.0, 8.0, 16.0, 30.0, 30.0, 30.0], clock.sleeps)
    check('offline within deadline', True, clock.now <= 120)
    shutil.rmtree(state)

    # 4. USB adb is preferred when present, unless a device is configured explicitly.
    module, clock, state = scenario('usb', FAKE_ADB_USB=USB, FAKE_ADB_ONLINE_AFTER=0, **CONFIGURED)
    check('usb preferred', USB, module.reconnect(max_seconds=10, sleep=clock.sleep, log=logs.append))
    shutil.rmtree(state)
    module, clock, state = scenario('explicit', ANDROID_DEVICE=OTHER, FAKE_ADB_SERIAL=OTHER, FAKE_ADB_USB=USB, FAKE_ADB_ONLINE_AFTER=0)
    check('explicit device beats usb', OTHER, module.reconnect(max_seconds=10, sleep=clock.sleep, log=logs.append))
    check('explicit device needs no adb devices listing', False, 'devices' in calls(state).split())
    shutil.rmtree(state)

    # 5. detached run survives a drop; wait_done reconnects and reads the result file.
    module, clock, state = scenario('detached', FAKE_ADB_ONLINE_AFTER=0, FAKE_ADB_DROP_AFTER=4, **CONFIGURED)
    serial = module.reconnect(max_seconds=10, sleep=clock.sleep, log=logs.append)
    module.run_detached(serial, 'verify1', 'echo hi')
    commands = (state / 'commands.log').read_text()
    check('detached uses setsid nohup', True, 'setsid nohup sh /sdcard/wehavephones-launch/verify1.sh' in commands)
    script = (state / 'file-_sdcard_wehavephones-launch_verify1.sh').read_text()
    check('detached script writes .done', True, 'echo $? > /sdcard/wehavephones-launch/verify1.done' in script)
    check('detached script logs output', True, '> /sdcard/wehavephones-launch/verify1.log 2>&1' in script)
    # The device finishes the job while adb is down.
    (state / 'file-_sdcard_wehavephones-launch_verify1.done').write_text('0\n')
    serial, code = module.wait_done('verify1', timeout=300, serial=serial, sleep=clock.sleep)
    check('wait_done result after drop', 0, code)
    check('wait_done serial', PHONE, serial)
    check('drop forced a reconnect', True, int((state / 'connects').read_text()) >= 2)
    shutil.rmtree(state)

    # 6. battery parsing (stop threshold uses level + powered).
    module, clock, state = scenario('battery', FAKE_ADB_ONLINE_AFTER=0, **CONFIGURED)
    serial = module.reconnect(max_seconds=10, sleep=clock.sleep, log=logs.append)
    check('battery parse', (42, False), module.battery(serial))
    shutil.rmtree(state)

    # 7. nothing configured: the only attached device is used, and stays the target.
    module, clock, state = scenario('single', FAKE_ADB_USB=USB)
    check('single attached device is used', USB, module.resolve_serial())
    check('single device is remembered', [USB], module.explicit_serials())
    check('reconnect keeps the single device', USB, module.reconnect(max_seconds=10, sleep=clock.sleep, log=logs.append))
    module.adb(['connect', PHONE])  # a second phone appears later; the choice does not change
    check('later devices do not change the choice', USB, module.reconnect(max_seconds=10, sleep=clock.sleep, log=logs.append))
    shutil.rmtree(state)
    module, clock, state = scenario('single-reconnect', FAKE_ADB_USB=USB)
    check('reconnect alone picks the single device', USB, module.reconnect(max_seconds=10, sleep=clock.sleep, log=logs.append))
    shutil.rmtree(state)

    # 8. nothing configured and zero or several devices: stop at once with guidance.
    module, clock, state = scenario('none')
    message = exit_message(module.resolve_serial, flag='--serial')
    check('none attached stops', True, message is not None and 'none is ready' in message)
    check('guidance names local.env and the flag', True,
          message is not None and 'ANDROID_DEVICE in local.env or pass --serial' in message)
    message = exit_message(module.resolve_serial)
    check('guidance without a flag', True,
          message is not None and message.endswith('connect the phone or set ANDROID_DEVICE in local.env.'))
    message = exit_message(module.reconnect, max_seconds=600, sleep=clock.sleep, log=logs.append)
    check('reconnect with nothing configured does not wait', ([], True), (clock.sleeps, message is not None))
    shutil.rmtree(state)
    module, clock, state = scenario('several', FAKE_ADB_USB=USB, FAKE_ADB_ONLINE_AFTER=0)
    module.adb(['connect', PHONE])
    message = exit_message(module.resolve_serial, flag='--device')
    check('several attached stops', True, message is not None and USB in message and PHONE in message)
    check('several attached mention the flag', True, message is not None and '--device' in message)
    check('several attached stop reconnect', True, exit_message(module.reconnect, max_seconds=10, sleep=clock.sleep) is not None)
    shutil.rmtree(state)
    module, clock, state = scenario('unauthorized', FAKE_ADB_UNAUTHORIZED='TESTUSB0002')
    message = exit_message(module.resolve_serial)
    check('unauthorized device is not used', True, message is not None and 'TESTUSB0002: unauthorized' in message)
    shutil.rmtree(state)

    # 9. resolution order: flag, ADB_SERIAL, ANDROID_DEVICE.
    module, _, state = scenario('order-both', ADB_SERIAL=OTHER, ANDROID_DEVICE=EXTRA)
    check('flag beats ADB_SERIAL', PHONE, module.resolve_serial(PHONE))
    check('ADB_SERIAL beats ANDROID_DEVICE', OTHER, module.resolve_serial())
    shutil.rmtree(state)
    module, _, state = scenario('order-android', ANDROID_DEVICE=EXTRA)
    check('ANDROID_DEVICE is used', EXTRA, module.resolve_serial())
    shutil.rmtree(state)

    # 10. local.env: parsed, never executed; variables already in the environment win.
    local_env = pathlib.Path(tempfile.mkdtemp(prefix='local-env-')) / 'local.env'
    local_env.write_text('# machine-local settings\n\n'
                         'ANDROID_DEVICE=%s\r\n'
                         'export WEHAVEPHONES_PACKAGE="org.example.app"\n'
                         '  SPACED_KEY = spaced value  \n'
                         "QUOTED='single quoted'\n"
                         'EMPTY=\n'
                         'COMMAND=$(touch %s)\n'
                         'this line is not a setting\n'
                         'WEHAVEPHONES_DEVICE_CANDIDATES=%s\n' % (OTHER, local_env.parent / 'executed', EXTRA))
    stderr = io.StringIO()
    with contextlib.redirect_stderr(stderr):
        module, _, state = scenario('local-env', WEHAVEPHONES_LOCAL_ENV=local_env, WEHAVEPHONES_DEVICE_CANDIDATES=PHONE)
    check('local.env sets ANDROID_DEVICE', OTHER, os.environ.get('ANDROID_DEVICE'))
    check('local.env sets the package', 'org.example.app', module.PKG)
    check('local.env trims keys and values', 'spaced value', os.environ.get('SPACED_KEY'))
    check('local.env strips quotes', 'single quoted', os.environ.get('QUOTED'))
    check('local.env allows empty values', '', os.environ.get('EMPTY'))
    check('local.env is not executed', ('$(touch %s)' % (local_env.parent / 'executed'), False),
          (os.environ.get('COMMAND'), (local_env.parent / 'executed').exists()))
    check('environment beats local.env', [OTHER, PHONE], module.candidates())
    check('malformed line is reported', True, 'local.env' in stderr.getvalue() and ':9 ignored' in stderr.getvalue())
    check('resolve_serial reads local.env', OTHER, module.resolve_serial())

    # The shell loader (scripts/local-env.sh) must give the same result for the same file.
    names = ['ANDROID_DEVICE', 'WEHAVEPHONES_PACKAGE', 'SPACED_KEY', 'QUOTED', 'EMPTY', 'COMMAND',
             'WEHAVEPHONES_DEVICE_CANDIDATES', 'MISSING']
    python_values = ['%s=%s' % (name, os.environ.get(name, '<unset>')) for name in names]
    shell = subprocess.run(
        ['env', '-i', 'PATH=' + ORIGINAL_ENV.get('PATH', '/usr/bin:/bin'), 'WEHAVEPHONES_LOCAL_ENV=' + str(local_env),
         'WEHAVEPHONES_DEVICE_CANDIDATES=' + PHONE, 'bash', '-c',
         'set -euo pipefail; source "$1"; shift; for name in "$@"; do printf "%s=%s\\n" "$name" "${!name-<unset>}"; done',
         'loader', str(ROOT / 'scripts/local-env.sh'), *names],
        capture_output=True, text=True)
    check('shell loader gives the same values', python_values, shell.stdout.splitlines())
    check('shell loader reports the malformed line', True, ':9 ignored' in shell.stderr)
    check('shell loader does not execute the file', False, (local_env.parent / 'executed').exists())
    shutil.rmtree(state)
    shutil.rmtree(local_env.parent)
    module, _, state = scenario('local-env-missing', WEHAVEPHONES_LOCAL_ENV=local_env)
    check('missing local.env is fine', [], module.load_local_env())
    shutil.rmtree(state)

    # 11. package: one setting, default com.wehavephones.dev (the debug build).
    module, _, state = scenario('package-default')
    check('default package', 'com.wehavephones.dev', module.PKG)
    check('only the app is protected by default', ({'com.wehavephones.dev'}, []),
          (module.PROTECTED, module.PROTECTED_EXTRA))
    shutil.rmtree(state)
    module, _, state = scenario('package-env', WEHAVEPHONES_PACKAGE='org.example.app')
    check('package from environment', 'org.example.app', module.PKG)
    check('package is protected from trimming', True, 'org.example.app' in module.PROTECTED)
    shutil.rmtree(state)

    # 12. runtime stop uses the configured package.
    module, clock, state = scenario('stop', WEHAVEPHONES_PACKAGE='org.example.app')
    run_calls = []

    def stopped_shell(serial, command, timeout=30):
        run_calls.append(command)
        return 0, 'PID PPID S NAME\n101 1 S wine-preloader\n102 1 S Battle.net.exe\n103 1 S wineserver\n104 1 S app_process64\n105 99 S wine-preloader\n', ''
    module.shell = stopped_shell
    module.stop_runtime('phone')
    check('runtime stop follows the package', 'am force-stop --user 0 org.example.app', run_calls[0])
    shutil.rmtree(state)

    module, clock, state = scenario('stop-default')
    calls_made = []

    def stopped_shell(serial, command, timeout=30):
        calls_made.append(command)
        if '/proc/106/task' in command:
            return 0, 'C:\\Program Files (x86)\\Battle.net\\Battle.net.exe\n', ''
        if '/proc/107/task' in command:
            return 0, '/system/bin/sh\n', ''
        if '/task/' in command:
            return 0, '', ''
        return 0, ('PID PPID S NAME\n101 1 S wine-preloader\n102 1 S Battle.net.exe\n103 1 S wineserver\n'
                   '104 1 S app_process64\n105 99 S wine-preloader\n106 1 Z [Main]\n107 1 Z [sh]\n'
                   '108 1 Z [Main]\n'), ''
    module.shell = stopped_shell
    killed = module.stop_runtime('phone')
    check('runtime stop excludes Secure Folder', 'am force-stop --user 0 com.wehavephones.dev', calls_made[0])
    check('runtime scan uses base app UID', True, 'ps -o PID,PPID,S,NAME -u' in calls_made[1] and 'id -u' in calls_made[1])
    check('runtime stop includes GE preloader orphans', True, 'kill -9 101 102 103 ' in calls_made[-1])
    check('runtime stop excludes non-orphans and Android processes', False, '104' in calls_made[-1] or '105' in calls_made[-1])
    check('runtime stop includes a renamed Battle.net zombie', True, ' 106 ' in calls_made[-1] + ' ')
    check('runtime stop excludes other zombies', False, '107' in calls_made[-1] or '108' in calls_made[-1])
    check('runtime stop returns the killed PIDs', ['101', '102', '103', '106'], killed)
    check('GE preloader recognized', True, module.is_wine_process('wine-preloader'))
    check('Wine game recognized', True, module.is_wine_process('WowClassic.exe'))
    check('Android app excluded', False, module.is_wine_process('app_process64'))
    check('Windows path names a Wine program', True, module.is_wine_program('C:\\Program Files (x86)\\Battle.net\\Battle.net.exe'))
    check('Android shell is not a Wine program', False, module.is_wine_program('/system/bin/sh'))
    shutil.rmtree(state)

    # 13. protected apps: WEHAVEPHONES_PROTECTED_PACKAGES from the environment or local.env.
    stderr = io.StringIO()
    with contextlib.redirect_stderr(stderr):
        module, _, state = scenario('protected-env', WEHAVEPHONES_PACKAGE='org.example.app',
                                    WEHAVEPHONES_PROTECTED_PACKAGES=' org.example.vpn, org.example.remote,org.example.vpn,,bad name;touch x ')
    check('protected apps keep their order without duplicates', ['org.example.vpn', 'org.example.remote'],
          module.PROTECTED_EXTRA)
    check('the app is always protected too', {'org.example.app', 'org.example.vpn', 'org.example.remote'},
          module.PROTECTED)
    check('an invalid name is reported and skipped', True, "ignoring 'bad name;touch x'" in stderr.getvalue())
    shutil.rmtree(state)

    protected_env = pathlib.Path(tempfile.mkdtemp(prefix='local-env-')) / 'local.env'
    protected_env.write_text('WEHAVEPHONES_PROTECTED_PACKAGES=org.example.vpn\n')
    module, _, state = scenario('protected-local-env', WEHAVEPHONES_LOCAL_ENV=protected_env)
    check('protected apps come from local.env', ['org.example.vpn'], module.PROTECTED_EXTRA)
    shutil.rmtree(state)
    module, _, state = scenario('protected-env-wins', WEHAVEPHONES_LOCAL_ENV=protected_env,
                                WEHAVEPHONES_PROTECTED_PACKAGES='org.example.remote')
    check('environment beats local.env for protected apps', ['org.example.remote'], module.PROTECTED_EXTRA)
    shutil.rmtree(state)
    shutil.rmtree(protected_env.parent)

    # 14. protect: protected apps get the doze whitelist and an active standby bucket; every
    #     other third-party app that is running is asked to trim, never the protected ones.
    def run_protect(**env):
        module, _, state = scenario('protect', **env)
        seen = []

        def protect_shell(serial, command, timeout=30):
            seen.append(command)
            if command == 'pm list packages -3':
                names = ('org.example.vpn', 'org.example.remote', 'org.example.app', 'org.example.game', 'org.example.idle')
                return 0, ''.join('package:%s\n' % name for name in names), ''
            if command.startswith('pidof '):
                return (0, '4242\n', '') if command.endswith('org.example.game') else (1, '', '')
            return 0, '', ''
        module.shell = protect_shell
        printed = io.StringIO()
        with contextlib.redirect_stdout(printed):
            module.protect('phone')
        shutil.rmtree(state)
        return seen, printed.getvalue()

    seen, printed = run_protect(WEHAVEPHONES_PACKAGE='org.example.app',
                                WEHAVEPHONES_PROTECTED_PACKAGES='org.example.vpn,org.example.remote')
    check('protect whitelists each protected app from doze',
          ['cmd deviceidle whitelist +org.example.vpn', 'cmd deviceidle whitelist +org.example.remote'],
          [command for command in seen if command.startswith('cmd deviceidle')])
    check('protect pins each protected app standby bucket',
          ['am set-standby-bucket org.example.vpn active', 'am set-standby-bucket org.example.remote active'],
          [command for command in seen if command.startswith('am set-standby-bucket')])
    check('protect trims only the unprotected running app',
          ['am send-trim-memory org.example.game RUNNING_CRITICAL'],
          [command for command in seen if command.startswith('am send-trim-memory')])
    check('protect does not even probe protected apps and the app', [],
          [command for command in seen if command.startswith('pidof ') and any(
              name in command for name in ('org.example.vpn', 'org.example.remote', 'org.example.app'))])
    check('protect reports the trim', True, 'trim requested: org.example.game' in printed)

    seen, printed = run_protect(WEHAVEPHONES_PACKAGE='org.example.app')
    check('protect with nothing configured changes no doze or standby setting', [],
          [command for command in seen if 'deviceidle' in command or 'standby' in command])
    check('protect with nothing configured still trims others but never the app',
          ['am send-trim-memory org.example.game RUNNING_CRITICAL'],
          [command for command in seen if command.startswith('am send-trim-memory')])
    check('protect with nothing configured does not probe the app', False,
          'pidof org.example.app' in seen)

    print('[reconnect-selftest] %d checks, %d failure(s)' % (CHECKS[0], len(FAILURES)))
    return 1 if FAILURES else 0


if __name__ == '__main__':
    sys.exit(main())
