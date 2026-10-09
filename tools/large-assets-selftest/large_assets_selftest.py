#!/usr/bin/env python3
"""Host self-test for scripts/large-assets.py and the pinned scripts/large-assets.json.

Runs the real CLI against a temporary manifest whose sources are file:// URLs:
plain downloads, tar and zip members, local verification without network,
mismatch handling, release-first ordering with upstream fallback, and staging.
Then checks the structure of the real manifest. No network, no device.
"""
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/large-assets.py'
failures = []


def expect(condition, label):
    print(('ok   ' if condition else 'FAIL ') + label)
    if not condition:
        failures.append(label)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def run(manifest, root, *args, base_url=''):
    env = dict(os.environ, WEHAVEPHONES_ASSETS_BASE_URL=base_url)
    return subprocess.run([sys.executable, str(SCRIPT), '--manifest', str(manifest), '--root', str(root), *args],
                          capture_output=True, text=True, env=env)


def entry(data):
    return {'size': len(data), 'sha256': digest(data)}


def synthetic(tmp):
    upstream = tmp / 'upstream'
    upstream.mkdir()
    payload = {'plain.bin': b'plain asset\n', 'tarred.bin': b'tar member\n' * 50,
               'zipped.bin': b'zip member\n' * 70, 'other.bin': b'second-group asset\n'}
    (upstream / 'plain.bin').write_bytes(payload['plain.bin'])
    (upstream / 'other.bin').write_bytes(payload['other.bin'])
    with tarfile.open(upstream / 'bundle.tar.xz', 'w:xz') as bundle:
        for name, data in (('app/a/tarred.bin', payload['tarred.bin']), ('app/a/unused.bin', b'unused')):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            bundle.addfile(info, io.BytesIO(data))
    with zipfile.ZipFile(upstream / 'app.apk', 'w') as apk:
        apk.writestr('assets/zipped.bin', payload['zipped.bin'])
    sources = {
        'plain': {'origin': 'test plain', 'format': 'file', 'url': (upstream / 'plain.bin').as_uri()},
        'other': {'origin': 'test other', 'format': 'file', 'url': (upstream / 'other.bin').as_uri()},
        'bundle': {'origin': 'test bundle', 'format': 'tar', 'url': (upstream / 'bundle.tar.xz').as_uri(),
                   **entry((upstream / 'bundle.tar.xz').read_bytes())},
        'apk': {'origin': 'test apk', 'format': 'zip', 'url': (upstream / 'app.apk').as_uri(),
                **entry((upstream / 'app.apk').read_bytes())},
    }
    assets = [
        {'path': 'app/a/plain.bin', 'group': 'apk', **entry(payload['plain.bin']), 'upstream': {'source': 'plain'}},
        {'path': 'app/a/tarred.bin', 'group': 'apk', **entry(payload['tarred.bin']),
         'upstream': {'source': 'bundle', 'member': 'app/a/tarred.bin'}},
        {'path': 'app/b/zipped.bin', 'group': 'apk', **entry(payload['zipped.bin']),
         'upstream': {'source': 'apk', 'member': 'assets/zipped.bin'}},
        {'path': 'extra/other.bin', 'group': 'runtime', **entry(payload['other.bin']), 'upstream': {'source': 'other'}},
    ]
    manifest = {'schemaVersion': 1, 'release': {'repo': '', 'tag': 'assets-test', 'groups': ['apk']},
                'sources': sources, 'assets': assets}
    path = tmp / 'manifest.json'
    path.write_text(json.dumps(manifest))
    return path, upstream, payload, manifest


def test_cli():
    with tempfile.TemporaryDirectory() as tmp_name:
        tmp = pathlib.Path(tmp_name)
        manifest_path, upstream, payload, manifest = synthetic(tmp)
        root = tmp / 'root'
        root.mkdir()
        apk_files = {'app/a/plain.bin': payload['plain.bin'], 'app/a/tarred.bin': payload['tarred.bin'],
                     'app/b/zipped.bin': payload['zipped.bin']}

        def apk_present():
            return all((root / path).is_file() and (root / path).read_bytes() == data
                       for path, data in apk_files.items())

        result = run(manifest_path, root, 'fetch')
        expect(result.returncode == 0 and apk_present(), 'fetch apk group from file, tar and zip upstreams')
        expect(not (root / 'extra/other.bin').exists(), 'default fetch leaves other groups alone')
        expect(not list(root.rglob('*.part')), 'no partial files remain')
        expect(run(manifest_path, root, 'check').returncode == 0, 'check passes after fetch')
        expect(run(manifest_path, root, 'check', 'runtime').returncode == 1, 'check reports a missing group')
        expect(run(manifest_path, root, 'fetch', 'bogus').returncode == 1, 'unknown group is rejected')

        hidden = tmp / 'upstream-hidden'
        upstream.rename(hidden)
        shutil.rmtree(root / 'out/_cache')
        result = run(manifest_path, root, 'fetch')
        expect(result.returncode == 0 and '(0 fetched)' in result.stdout, 'verified local files need no source')

        (root / 'app/a/plain.bin').write_bytes(b'stale local copy\n')
        result = run(manifest_path, root, 'fetch')
        expect(result.returncode == 1 and 'does not match' in result.stdout, 'mismatching local file stops fetch')
        expect((root / 'app/a/plain.bin').read_bytes() == b'stale local copy\n', 'mismatching file is kept by default')
        hidden.rename(upstream)
        result = run(manifest_path, root, 'fetch', '--replace')
        aside = list((root / 'app/a').glob('plain.bin.mismatched-*'))
        expect(result.returncode == 0 and apk_present(), '--replace fetches the pinned copy')
        expect(len(aside) == 1 and aside[0].read_bytes() == b'stale local copy\n', '--replace keeps the old file aside')
        aside[0].unlink()

        release = tmp / 'release'
        release.mkdir()
        for path, data in apk_files.items():
            (release / pathlib.PurePosixPath(path).name).write_bytes(data)
        upstream.rename(hidden)
        for path in apk_files:
            (root / path).unlink()
        result = run(manifest_path, root, 'fetch', base_url=release.as_uri())
        expect(result.returncode == 0 and apk_present() and 'from release' in result.stdout,
               'release copy is used before upstream')

        (root / 'app/b/zipped.bin').unlink()
        (release / 'zipped.bin').unlink()
        result = run(manifest_path, root, 'fetch', '--release-only', base_url=release.as_uri())
        expect(result.returncode == 1 and 'Could not fetch app/b/zipped.bin' in result.stdout,
               '--release-only reports a file missing from the release')

        hidden.rename(upstream)
        (release / 'zipped.bin').write_bytes(b'wrong bytes in the release\n')
        result = run(manifest_path, root, 'fetch', base_url=release.as_uri())
        expect(result.returncode == 0 and apk_present() and 'upstream apk' in result.stdout,
               'bad release copy falls back to upstream')

        broken = json.loads(manifest_path.read_text())
        broken['sources']['bundle']['sha256'] = '0' * 64
        broken_path = tmp / 'broken.json'
        broken_path.write_text(json.dumps(broken))
        (root / 'app/a/tarred.bin').unlink()
        shutil.rmtree(root / 'out/_cache')
        result = run(broken_path, root, 'fetch')
        expect(result.returncode == 1 and 'bundle does not match its pinned sha256' in result.stdout,
               'changed upstream archive is rejected')
        expect(not (root / 'app/a/tarred.bin').exists(), 'rejected archive leaves no asset behind')

        expect(run(manifest_path, root, 'fetch').returncode == 0, 'refetch after rejected archive')
        out = tmp / 'staged'
        result = run(manifest_path, root, 'stage', '--out', str(out))
        sums = (out / 'SHA256SUMS').read_text().split('\n') if (out / 'SHA256SUMS').exists() else []
        expected = [f'{asset["sha256"]}  {pathlib.PurePosixPath(asset["path"]).name}'
                    for asset in manifest['assets'] if asset['group'] == 'apk']
        expect(result.returncode == 0 and sums[:-1] == expected, 'stage writes SHA256SUMS for the release groups')
        expect(sorted(p.name for p in out.iterdir()) == ['SHA256SUMS', 'plain.bin', 'tarred.bin', 'zipped.bin'],
               'stage links only release-group assets')
        notes = out.with_name('staged.md')
        expect(notes.is_file() and 'test bundle, `app/a/tarred.bin`' in notes.read_text(),
               'stage writes release notes with each origin')
        expect('gh release create assets-test --repo <owner>/<repo>' in result.stdout,
               'stage prints the upload command')
        (root / 'app/a/plain.bin').write_bytes(b'stale local copy\n')
        expect(run(manifest_path, root, 'stage', '--out', str(out)).returncode == 1,
               'stage refuses unverified local files')


def test_real_manifest():
    manifest = json.loads((ROOT / 'scripts/large-assets.json').read_text())
    sources, assets, release = manifest['sources'], manifest['assets'], manifest['release']
    sha = re.compile(r'[0-9a-f]{64}')
    groups = {asset['group'] for asset in assets}
    expect('apk' in groups and set(release['groups']) <= groups, 'release groups exist')
    expect(re.fullmatch(r'assets-v\d+', release['tag']) is not None, 'release tag is versioned')
    expect(release['repo'] == '' or re.fullmatch(r'[\w.-]+/[\w.-]+', release['repo']) is not None,
           'release repo is empty or owner/name')
    for name, source in sources.items():
        archive = source['format'] in ('tar', 'zip')
        ok = (source['format'] in ('file', 'tar', 'zip') and source['url'].startswith('https://')
              and source.get('origin') and (not archive or (sha.fullmatch(source.get('sha256', ''))
                                                            and source.get('size', 0) > 0)))
        expect(bool(ok), f'source {name} is pinned')
    release_names = []
    for asset in assets:
        upstream = asset.get('upstream')
        if upstream:
            source = sources.get(upstream['source'])
            upstream_ok = source is not None and bool(upstream.get('member')) == (source['format'] != 'file')
        else:
            upstream_ok = asset['group'] in release['groups']
        expect(bool(sha.fullmatch(asset['sha256'])) and asset['size'] > 0 and upstream_ok
               and not asset['path'].startswith('/'), f'asset {asset["path"]} is pinned')
        if asset['group'] in release['groups']:
            release_names.append(pathlib.PurePosixPath(asset['path']).name)
    expect(len(release_names) == len(set(release_names)), 'release file names are unique')
    ignored = subprocess.run(['git', 'check-ignore', '--no-index', *[a['path'] for a in assets]],
                             cwd=ROOT, capture_output=True, text=True).stdout.split()
    expect(sorted(ignored) == sorted(a['path'] for a in assets), 'every pinned asset is gitignored')


test_cli()
test_real_manifest()
if failures:
    print(f'[large-assets-selftest] {len(failures)} failed')
    sys.exit(1)
print('[large-assets-selftest] all checks passed')
