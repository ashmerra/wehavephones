#!/usr/bin/env python3
"""Drive and verify the WeHavePhones one-button Play flow on the real phone.

    scripts/wehavephones-launch-verify.py NAME [--overrides JSON] [--warm]
                                           [--duration 900] [--post 120]

1. Reconnect (scripts/wehavephones-device.py), refuse at <=15% battery unplugged,
   keep the WEHAVEPHONES_PROTECTED_PACKAGES apps alive (the phone's link, if
   configured), stop any leftover runtime (unless --warm).
2. Write/clear files/wehavephones/launch-overrides.json (testing knobs such as
   {"options":{"forcePath":"direct"}}).
3. Start a detached on-device monitor (/sdcard/wehavephones-launch/NAME/):
   MemAvailable/SwapFree + game/launcher process samples every ~2 s,
   periodic screenshots, the app's launch record/log, launcher-log markers,
   WeHavePhonesLaunch logcat and lmkd/am_kill events. It keeps running if adb drops.
4. Open WeHavePhones and tap Play (uiautomator, by resource id).
5. Poll the monitor status, reconnecting with backoff; when done, pull the
   directory to out/wehavephones-launch/NAME/ and print a summary.

No credentials are read or printed.
"""
import argparse
import importlib.util
import json
import pathlib
import re
import shlex
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('wehavephones_device', ROOT / 'scripts/wehavephones-device.py')
dev = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev)

PKG = dev.PKG
OUT = ROOT / 'out/wehavephones-launch'

MONITOR = r'''
D=%(dir)s
PKG=%(pkg)s
mkdir -p $D
END=$(( $(date +%%s) + %(duration)d ))
POST=%(post)d
i=0
done_at=0
LOGS='files/imagefs/home/xuser-*/.wine/drive_c/users/xuser/AppData/Local/Battle.net/Logs'
while [ $(date +%%s) -lt $END ]; do
  now=$(date +%%s)
  ma=$(grep MemAvailable /proc/meminfo | awk '{print $2}')
  sf=$(grep SwapFree /proc/meminfo | awk '{print $2}')
  procs=$(run-as $PKG sh -c 'ps -A -o PID,RSS,ARGS' 2>/dev/null | grep -iE 'wow|battle|agent|wineserver' | grep -v grep | sed -E 's/^ *//; s/ +/ /g' | cut -c1-80 | tr '\n' '|')
  echo "$now memAvailKb=$ma swapFreeKb=$sf $procs" >> $D/samples.txt
  if [ $((i %% 8)) -eq 0 ]; then screencap -p $D/shot-$(printf %%03d $i)-$now.png; fi
  status=$(run-as $PKG cat files/wehavephones/launch-last.json 2>/dev/null | grep -E '"(lastStage|result|pathUsed)"' | tr -d ' \n')
  echo "$now $status" > $D/status.txt
  case "$status" in
    *'"result":"running"'*|*'"result":"failed"'*|*'"result":"already-running"'*)
      if [ $done_at -eq 0 ]; then done_at=$now; fi ;;
  esac
  if [ $done_at -gt 0 ] && [ $((now - done_at)) -ge $POST ]; then break; fi
  i=$((i + 1))
  sleep 2
done
screencap -p $D/final.png
run-as $PKG cat files/wehavephones/launch-last.json > $D/launch-last.json 2>/dev/null
run-as $PKG cat files/wehavephones/launch.log > $D/launch.log 2>/dev/null
run-as $PKG sh -c "cd $LOGS && for f in \$(ls -t battle.net-*.log | head -4); do echo == \$f; grep -E 'Command line|IPC shared memory|Logged into|InstallState \\((wow)\\)|Launched|LaunchBinary|Game is running|Received IPC|Attempting to show main window' \$f | sed -E 's/(successfully\\.).*/\\1/' | cut -c1-300; done" > $D/bnet-markers.txt 2>&1
run-as $PKG sh -c 'ps -A -o PID,RSS,ARGS' > $D/ps-final.txt 2>&1
logcat -d -s WeHavePhonesLaunch > $D/logcat-launch.txt 2>&1
logcat -d -b events | grep -E 'am_kill|lmk|am_proc_died' | tail -80 > $D/kills.txt 2>&1
grep -E 'MemAvailable|SwapFree' /proc/meminfo > $D/meminfo-final.txt
'''


def sh(serial, command, timeout=30):
    return dev.shell(serial, command, timeout)


def write_device(serial, path, data):
    code, _, err = dev.adb(['shell', 'cat > %s' % path], serial, input_bytes=data.encode())
    if code != 0:
        raise SystemExit('could not write %s: %s' % (path, err.strip()))


def write_private(serial, relative, data):
    tmp = '/data/local/tmp/wehavephones-%s' % pathlib.Path(relative).name
    write_device(serial, tmp, data)
    sh(serial, 'chmod 644 %s' % tmp)
    sh(serial, 'run-as %s sh -c %s' % (PKG, shlex.quote('mkdir -p files/wehavephones && cat %s > %s' % (tmp, relative))))
    sh(serial, 'rm -f %s' % tmp)


def wake(serial):
    sh(serial, 'input keyevent KEYCODE_WAKEUP')
    time.sleep(1)
    keyguard = sh(serial, 'dumpsys window | grep -E "mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen" | head -3')[1]
    if 'mShowingLockscreen=true' in keyguard or 'isKeyguardShowing=true' in keyguard:
        sh(serial, 'input swipe 540 1800 540 600 200')
        time.sleep(1)
    sh(serial, 'cmd statusbar collapse')


def tap_play(serial):
    sh(serial, 'am start -n %s/com.wehavephones.mobile.WeHavePhonesActivity' % PKG)
    for _ in range(10):
        time.sleep(2)
        sh(serial, 'uiautomator dump /sdcard/wehavephones-launch/ui.xml', timeout=40)
        xml = sh(serial, 'cat /sdcard/wehavephones-launch/ui.xml')[1]
        match = re.search(r'resource-id="%s:id/BTPlay"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(PKG), xml)
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            x, y = (x1 + x2) // 2, (y1 + y2) // 2
            sh(serial, 'input tap %d %d' % (x, y))
            print('[verify] tapped Play at %d,%d' % (x, y), flush=True)
            return True
        sh(serial, 'input keyevent 4')
    return False


def pull(serial, name):
    target = OUT / name
    target.mkdir(parents=True, exist_ok=True)
    listing = sh(serial, 'ls /sdcard/wehavephones-launch/%s' % name)[1].split()
    for entry in listing:
        dev.adb(['pull', '/sdcard/wehavephones-launch/%s/%s' % (name, entry), str(target / entry)], serial, timeout=120)
    for entry in ('%s-monitor.log' % name, '%s-monitor.done' % name):
        dev.adb(['pull', '/sdcard/wehavephones-launch/%s' % entry, str(target / entry)], serial, timeout=60)
    # The detached monitor's final run-as copies can come back empty; read the app records directly too.
    for entry in ('launch-last.json', 'launch.log'):
        code, out, _ = dev.adb(['shell', 'run-as %s cat files/wehavephones/%s' % (PKG, entry)], serial, timeout=60)
        if code == 0 and out.strip():
            (target / entry).write_text(out)
    return target


def summarize(target):
    record_path = target / 'launch-last.json'
    record = {}
    if record_path.is_file() and record_path.stat().st_size:
        try:
            record = json.loads(record_path.read_text())
        except ValueError:
            pass
    keys = ['result', 'pathUsed', 'warm', 'minimized', 'coldExec', 'signedInAfterMs', 'readyAfterMs', 'spawnAfterMs',
            'launcherPid', 'wowPid', 'reapedProcesses', 'memAvailableBeforeReapMb', 'memAvailableAfterReapMb', 'error']
    print('[verify] record: ' + json.dumps({k: record.get(k) for k in keys if k in record}))
    samples = target / 'samples.txt'
    if samples.is_file():
        values = [int(m.group(1)) // 1024 for m in re.finditer(r'memAvailKb=(\d+)', samples.read_text())]
        if values:
            print('[verify] MemAvailable MB: start=%d min=%d end=%d (%d samples)' % (values[0], min(values), values[-1], len(values)))
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('name')
    parser.add_argument('--overrides', default='')
    parser.add_argument('--warm', action='store_true', help='keep the running session (warm exec test)')
    parser.add_argument('--duration', type=int, default=900)
    parser.add_argument('--post', type=int, default=120, help='seconds to keep sampling after the result')
    parser.add_argument('--no-tap', action='store_true', help='only collect; Play is pressed elsewhere')
    args = parser.parse_args()

    serial = dev.require()
    level, powered = dev.battery(serial)
    print('[verify] serial=%s battery=%d%% powered=%s' % (serial, level, powered), flush=True)
    if level <= 15 and not powered:
        print('[verify] battery <= 15%% and unplugged; stopping device work')
        return 3
    dev.protect(serial)
    if not args.warm:
        dev.stop_runtime(serial)
        time.sleep(2)
    sh(serial, 'mkdir -p /sdcard/wehavephones-launch/%s && rm -f /sdcard/wehavephones-launch/%s/*' % (args.name, args.name))
    if args.overrides:
        json.loads(args.overrides)
        write_private(serial, 'files/wehavephones/launch-overrides.json', args.overrides)
        print('[verify] overrides: %s' % args.overrides)
    else:
        sh(serial, 'run-as %s rm -f files/wehavephones/launch-overrides.json' % PKG)
    if not args.warm:
        sh(serial, 'run-as %s rm -f files/wehavephones/launch-last.json' % PKG)
    sh(serial, 'logcat -c')

    monitor = MONITOR % {'dir': '/sdcard/wehavephones-launch/' + args.name, 'pkg': PKG,
                         'duration': args.duration, 'post': args.post}
    write_device(serial, '/sdcard/wehavephones-launch/%s-monitor-body.sh' % args.name, monitor)
    dev.run_detached(serial, args.name + '-monitor', 'sh /sdcard/wehavephones-launch/%s-monitor-body.sh' % args.name)

    wake(serial)
    if not args.no_tap and not tap_play(serial):
        print('[verify] Play button not found')
        return 2
    started = time.time()

    last = ''
    while time.time() - started < args.duration + 120:
        if not dev.is_online(serial):
            print('[verify] adb dropped at +%ds; reconnecting' % (time.time() - started), flush=True)
            serial = dev.reconnect(max_seconds=max(60, args.duration + 120 - (time.time() - started)))
            if not serial:
                print('[verify] device did not come back; evidence stays in /sdcard/wehavephones-launch/%s' % args.name)
                return 4
        done = sh(serial, 'cat /sdcard/wehavephones-launch/%s-monitor.done 2>/dev/null' % args.name)[1].strip()
        status = sh(serial, 'cat /sdcard/wehavephones-launch/%s/status.txt 2>/dev/null' % args.name)[1].strip()
        if status and status.split(' ', 1)[-1] != last:
            last = status.split(' ', 1)[-1]
            print('[verify] +%ds %s' % (time.time() - started, last), flush=True)
        if done:
            break
        time.sleep(10)

    target = pull(serial, args.name)
    print('[verify] evidence: %s' % target.relative_to(ROOT))
    record = summarize(target)
    return 0 if record.get('result') in ('running', 'already-running') else 1


if __name__ == '__main__':
    sys.exit(main())
