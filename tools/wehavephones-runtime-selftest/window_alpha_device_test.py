#!/usr/bin/env python3
"""Check real X-server window compositing on an explicitly selected Android device.

Requires a running WeHavePhones X session at its default, unzoomed aspect ratio
and Pillow on the host. Draws a temporary 100 x 100 test window, changes only
its own properties, and destroys it afterward. Never stops the running session.
Only cropped test pixels are saved when --output-dir is supplied.
"""
import argparse
from contextlib import suppress
import io
import os
from pathlib import Path
import re
import subprocess
import struct
import select
import time

from PIL import Image


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--package', default='com.wehavephones.dev')
parser.add_argument('--adb', default=os.environ.get('ADB_BIN', 'adb'))
parser.add_argument('--output-dir', type=Path)
args = parser.parse_args()
if not re.fullmatch('[A-Za-z0-9_.]+', args.package):
    parser.error('invalid package name')
adb = [args.adb, '-s', args.serial]
if args.output_dir:
    args.output_dir.mkdir(parents=True, exist_ok=True)
socket_path = f'/data/user/0/{args.package}/files/imagefs/usr/tmp/.X11-unix/X0'
p = subprocess.Popen(adb + ['shell', '-T', 'run-as', args.package, '/system/bin/toybox', 'nc', '-U', socket_path], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def read(n):
    b = b''
    while len(b) < n:
        if not select.select([p.stdout], [], [], 5)[0]:
            raise RuntimeError('X11 read timeout')
        x = p.stdout.read1(n - len(b))
        if not x:
            raise RuntimeError('X11 connection closed')
        b += x
    return b


def send(b):
    p.stdin.write(b)
    p.stdin.flush()


def request(op, data=b'', detail=0):
    send(struct.pack('<BBH', op, detail, (len(data) + 4) // 4) + data)
    while True:
        h = read(32)
        if h[0] == 0:
            raise RuntimeError('X11 error %d for opcode %d' % (h[1], op))
        if h[0] == 1:
            return h + read(struct.unpack_from('<I', h, 4)[0] * 4)


def atom(name):
    b = name.encode()
    r = request(16, struct.pack('<HH', len(b), 0) + b + b'\x00' * (-len(b) % 4))
    return struct.unpack_from('<I', r, 8)[0]

try:
    send(b'l\x00' + struct.pack('<HHHHH', 11, 0, 0, 0, 0))
    h = read(8)
    if h[0] != 1:
        raise RuntimeError('X11 handshake rejected')
    s = read(struct.unpack_from('<H', h, 6)[0] * 4)
    v = struct.unpack_from('<H', s, 16)[0]
    o = 32 + (v + 3) // 4 * 4 + 8 * s[21]
    root = struct.unpack_from('<I', s, o)[0]
    base = struct.unpack_from('<I', s, 4)[0]
    if struct.unpack_from('<I', s, 8)[0] & 3 != 3:
        raise RuntimeError('X11 resource mask cannot allocate fixture IDs')
    visual = struct.unpack_from('<I', s, o + 32)[0]
    depth = s[o + 38]
    width, height = struct.unpack_from('<HH', s, o + 20)

    def fire(op, data=b'', detail=0):
        send(struct.pack('<BBH', op, detail, (len(data) + 4) // 4) + data)

    def sync():
        request(43)
        time.sleep(0.5)

    def create(w):
        fire(1, struct.pack('<IIhhHHHHIII', w, root, 100, 100, 100, 100, 0, 1, visual, 1 << 9, 1), depth)

    def image(w, pixels):
        fire(72, struct.pack('<IIHHhhBB2x', w, base + 3, 100, 100, 0, 0, 0, 32) + pixels, 2)

    def style(value):
        fire(18, struct.pack('<IIIB3xII', base + 2, style_atom, 6, 32, 1, value))

    def capture(label, expected):
        sync()
        raw = subprocess.check_output(adb + ['exec-out', 'screencap', '-p'], timeout=20)
        img = Image.open(io.BytesIO(raw)).convert('RGB')
        sw, sh = img.size
        scale = min(sw / width, sh / height)
        ox = (sw - width * scale) / 2
        oy = (sh - height * scale) / 2
        samples = []
        for i in range(4):
            x = round(ox + (100 + 12.5 + i * 25) * scale)
            y = round(oy + 150 * scale)
            samples.append(img.getpixel((x, y)))
        print(label, samples, flush=True)
        if args.output_dir:
            img.crop((round(ox + 101 * scale), round(oy + 101 * scale), round(ox + 199 * scale), round(oy + 199 * scale))).save(args.output_dir / ('fixture-' + label + '.png'))
        for got, want in zip(samples, expected):
            if any((abs(a - b) > 3 for a, b in zip(got, want))):
                raise AssertionError((label, got, want))
    # The background deliberately has zero alpha in an ordinary window.
    create(base + 1)
    create(base + 2)
    fire(55, struct.pack('<III', base + 3, base + 1, 0))
    image(base + 1, bytes([80, 40, 20, 0]) * 10000)
    # Transparent, half-green, opaque blue and quarter-red premultiplied pixels.
    row = b''.join((bytes(c) * 25 for c in [(0, 0, 0, 0), (0, 128, 0, 128), (255, 0, 0, 255), (0, 0, 64, 64)]))
    image(base + 2, row * 100)
    style_atom = atom('_WINE_HWND_EXSTYLE')
    style(0x00080000)
    fire(8, struct.pack('<I', base + 1))
    fire(8, struct.pack('<I', base + 2))
    layered = [(20, 40, 80), (10, 148, 40), (0, 0, 255), (79, 30, 60)]
    opaque = [(0, 0, 0), (0, 128, 0), (0, 0, 255), (64, 0, 0)]
    capture('layered', layered)
    style(0)
    capture('ordinary', opaque)
    style(0x00080000)
    capture('layered-restored', layered)
    fire(19, struct.pack('<II', base + 2, style_atom))
    capture('property-deleted', opaque)
    style(0x00080000)
    capture('property-restored', layered)
    fire(4, struct.pack('<I', base + 2))
    fire(4, struct.pack('<I', base + 1))
    request(43)
    print('Window alpha pixel regression PASS', flush=True)
finally:
    # Disconnecting frees only this fixture client's resources on failure.
    with suppress(BrokenPipeError, OSError):
        p.stdin.close()
    p.terminate()
    try:
        p.wait(timeout=3)
    except subprocess.TimeoutExpired:
        p.kill()
        p.wait(timeout=3)
