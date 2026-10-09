#!/usr/bin/env python3
"""Fake adb for the reconnect self-test. State lives in $FAKE_ADB_STATE.

FAKE_ADB_SERIAL        the only serial that can be online
FAKE_ADB_ONLINE_AFTER  number of `adb connect` calls before it comes online
FAKE_ADB_DROP_AFTER    go offline after this many successful shell calls (0 = never)
FAKE_ADB_USB           serial reported by `adb devices` as a USB device (always online)
FAKE_ADB_UNAUTHORIZED  serial `adb devices` lists in state "unauthorized"

The default serial is a documentation address (RFC 5737), not a real phone.
"""
import os
import pathlib
import sys

state = pathlib.Path(os.environ['FAKE_ADB_STATE'])
state.mkdir(parents=True, exist_ok=True)
serial_ok = os.environ.get('FAKE_ADB_SERIAL', '192.0.2.10:5555')
usb = os.environ.get('FAKE_ADB_USB', '')
unauthorized = os.environ.get('FAKE_ADB_UNAUTHORIZED', '')


def counter(name, increment=False):
    path = state / name
    value = int(path.read_text()) if path.is_file() else 0
    if increment:
        value += 1
        path.write_text(str(value))
    return value


def online(serial):
    if usb and serial == usb:
        return True
    return serial == serial_ok and (state / 'online').is_file()


args = sys.argv[1:]
with open(state / 'calls.log', 'a') as log:
    log.write(' '.join(args) + '\n')

serial = None
if args[:1] == ['-s']:
    serial, args = args[1], args[2:]

if args[:1] == ['devices']:
    print('List of devices attached')
    if usb:
        print('%s\tdevice' % usb)
    if (state / 'online').is_file():
        print('%s\tdevice' % serial_ok)
    if unauthorized:
        print('%s\tunauthorized' % unauthorized)
    sys.exit(0)

if args[:1] == ['connect']:
    count = counter('connects', True)
    if args[1] == serial_ok and count >= int(os.environ.get('FAKE_ADB_ONLINE_AFTER', '0')):
        (state / 'online').touch()
        (state / 'shells').write_text('0')
        print('connected to %s' % args[1])
    else:
        print('failed to connect to %s' % args[1])
    sys.exit(0)

if args[:1] == ['disconnect']:
    print('disconnected %s' % args[1])
    sys.exit(0)

if args[:1] == ['shell']:
    if not online(serial):
        sys.stderr.write("error: device '%s' not found\n" % serial)
        sys.exit(1)
    drop_after = int(os.environ.get('FAKE_ADB_DROP_AFTER', '0'))
    if serial == serial_ok and drop_after:
        if counter('shells', True) > drop_after:
            (state / 'online').unlink()
            sys.stderr.write('error: closed\n')
            sys.exit(1)
    command = ' '.join(args[1:])
    with open(state / 'commands.log', 'a') as log:
        log.write(command + '\n')
    if 'echo wehavephones-ok' in command:
        print('wehavephones-ok')
    elif command.startswith('cat > '):
        target = command[len('cat > '):].strip()
        (state / ('file-' + target.replace('/', '_'))).write_bytes(sys.stdin.buffer.read())
    elif command.startswith('cat '):
        target = command[len('cat '):].split()[0].strip("'")
        path = state / ('file-' + target.replace('/', '_'))
        if path.is_file():
            sys.stdout.write(path.read_text())
        else:
            sys.exit(1)
    elif command == 'dumpsys battery':
        print('Current Battery Service state:\n  AC powered: false\n  USB powered: false\n  level: 42')
    sys.exit(0)

sys.stderr.write('fake adb: unsupported %r\n' % args)
sys.exit(2)
