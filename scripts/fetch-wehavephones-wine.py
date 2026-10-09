#!/usr/bin/env python3
"""Fetch the exact GE layer in the runtime manifest for offline APK setup."""
import hashlib
import json
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
manifest = json.loads((ROOT / 'app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json').read_text())
layer = manifest['profiles'][manifest['singleStageProfile']]['layer']
target = ROOT / 'app/src/wehavephones/assets/wehavephones/wine' / layer['archive']

def matches(path):
    if not path.is_file():
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == layer['sha256']

if matches(target):
    print('[fetch-wehavephones-wine] Verified bundled ' + layer['name'])
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_suffix(target.suffix + '.part')
    print('[fetch-wehavephones-wine] Downloading pinned ' + layer['name'], flush=True)
    try:
        request = urllib.request.Request(layer['url'], headers={'User-Agent': 'WeHavePhonesMobile-build'})
        with urllib.request.urlopen(request, timeout=60) as source, part.open('wb') as output:
            while chunk := source.read(1024 * 1024):
                output.write(chunk)
        if not matches(part):
            raise RuntimeError('GE-Proton archive does not match the runtime manifest SHA-256')
        part.replace(target)
        print('[fetch-wehavephones-wine] Verified bundled ' + layer['name'])
    finally:
        part.unlink(missing_ok=True)
