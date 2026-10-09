#!/usr/bin/env python3
"""Fetch, verify and stage the large prebuilt assets pinned in scripts/large-assets.json.

  fetch [--replace] [--release-only] [GROUP ...]
                     make each asset present and sha256-verified (default group: apk)
  check [GROUP ...]  verify local copies without network access
  stage [--out DIR]  link the release groups, SHA256SUMS and release notes into DIR

GROUP is a manifest group (apk, runtime) or "all". An asset comes from
the first place whose bytes match its sha256: the local file, our own release
(release.repo in the manifest, or WEHAVEPHONES_ASSETS_BASE_URL), then the
recorded upstream source. See docs/BUILD_ASSETS.md.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import sys
import tarfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'scripts/large-assets.json'
CHUNK = 1024 * 1024
# URLError and HTTPError are OSErrors; a missing archive member raises KeyError.
SOURCE_ERRORS = (OSError, KeyError, tarfile.TarError, zipfile.BadZipFile)


class FetchError(Exception):
    pass


def log(message):
    print('[large-assets] ' + message, flush=True)


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def matches(path, entry):
    return path.is_file() and path.stat().st_size == entry['size'] and sha256(path) == entry['sha256']


def download(url, dest):
    request = urllib.request.Request(url, headers={'User-Agent': 'WeHavePhones-build'})
    with urllib.request.urlopen(request, timeout=60) as source, dest.open('wb') as output:
        total = int(source.headers.get('Content-Length') or 0)
        done = reported = 0
        while chunk := source.read(CHUNK):
            output.write(chunk)
            done += len(chunk)
            if total >= 100 * CHUNK and done * 4 // total > reported:
                reported = done * 4 // total
                log(f'  {reported * 25}% of {total // CHUNK} MiB')


def asset_name(asset):
    return pathlib.PurePosixPath(asset['path']).name


def release_base(manifest):
    base = os.environ.get('WEHAVEPHONES_ASSETS_BASE_URL', '').rstrip('/')
    release = manifest['release']
    if not base and release['repo']:
        base = f'https://github.com/{release["repo"]}/releases/download/{release["tag"]}'
    return base


def select(manifest, groups):
    known = sorted({asset['group'] for asset in manifest['assets']})
    wanted = known if groups == ['all'] else groups or ['apk']
    unknown = sorted(set(wanted) - set(known))
    if unknown:
        raise FetchError(f'Unknown group {", ".join(unknown)}; choose from {", ".join(known)} or all')
    return [asset for asset in manifest['assets'] if asset['group'] in wanted]


class Fetcher:
    def __init__(self, manifest, root, release_only=False):
        self.manifest = manifest
        self.root = root
        self.cache = root / 'out/_cache/large-assets'
        self.release_only = release_only

    def cached_archive(self, source_id):
        source = self.manifest['sources'][source_id]
        path = self.cache / source_id / source['url'].rsplit('/', 1)[-1]
        if matches(path, source):
            return path
        path.parent.mkdir(parents=True, exist_ok=True)
        part = path.with_name(path.name + '.part')
        log(f'Downloading {source_id} ({source["size"] // CHUNK} MiB) ...')
        try:
            download(source['url'], part)
            if not matches(part, source):
                raise FetchError(f'{source_id} does not match its pinned sha256')
            part.replace(path)
        finally:
            part.unlink(missing_ok=True)
        return path

    def from_upstream(self, asset, part):
        upstream = asset['upstream']
        source = self.manifest['sources'][upstream['source']]
        if source['format'] == 'file':
            download(source['url'], part)
            return
        archive = self.cached_archive(upstream['source'])
        if source['format'] == 'tar':
            with tarfile.open(archive) as bundle:
                stream = bundle.extractfile(upstream['member'])
                if stream is None:
                    raise FetchError(f'{upstream["member"]} is not a regular file in {archive.name}')
                with stream, part.open('wb') as output:
                    shutil.copyfileobj(stream, output, CHUNK)
        elif source['format'] == 'zip':
            with zipfile.ZipFile(archive) as bundle, bundle.open(upstream['member']) as stream, \
                    part.open('wb') as output:
                shutil.copyfileobj(stream, output, CHUNK)
        else:
            raise FetchError(f'Unknown source format {source["format"]}')

    def candidates(self, asset):
        base = release_base(self.manifest)
        if base and asset['group'] in self.manifest['release']['groups']:
            url = f'{base}/{asset_name(asset)}'
            yield 'release ' + url, lambda part: download(url, part)
        if asset.get('upstream') and not self.release_only:
            yield 'upstream ' + asset['upstream']['source'], lambda part: self.from_upstream(asset, part)

    def ensure(self, asset, replace=False):
        """Returns True when the asset had to be fetched."""
        target = self.root / asset['path']
        if target.exists():
            if matches(target, asset):
                return False
            if not replace:
                raise FetchError(f'{asset["path"]} does not match its pinned sha256. '
                                 'Move it aside, or rerun with --replace.')
            aside = target.with_name(f'{target.name}.mismatched-{sha256(target)[:12]}')
            target.replace(aside)
            log(f'Moved the mismatching {asset["path"]} to {aside.name}')
        target.parent.mkdir(parents=True, exist_ok=True)
        part = target.with_name(target.name + '.part')
        failures = []
        for label, produce in self.candidates(asset):
            log(f'Fetching {asset["path"]} from {label}')
            try:
                produce(part)
                if matches(part, asset):
                    part.replace(target)
                    return True
                failures.append(f'{label}: sha256 mismatch')
            except (FetchError, *SOURCE_ERRORS) as error:
                failures.append(f'{label}: {error}')
            finally:
                part.unlink(missing_ok=True)
        raise FetchError(f'Could not fetch {asset["path"]}:\n  '
                         + '\n  '.join(failures or ['no release or upstream source is available']))


def fetch(args, manifest):
    assets = select(manifest, args.groups)
    fetcher = Fetcher(manifest, args.root, args.release_only)
    fetched = sum(fetcher.ensure(asset, args.replace) for asset in assets)
    log(f'{len(assets)} assets verified ({fetched} fetched).')


def check(args, manifest):
    assets = select(manifest, args.groups)
    problems = []
    for asset in assets:
        target = args.root / asset['path']
        if not target.is_file():
            problems.append('missing  ' + asset['path'])
        elif not matches(target, asset):
            problems.append('mismatch ' + asset['path'])
    for problem in problems:
        log(problem)
    if problems:
        raise FetchError(f'{len(problems)} of {len(assets)} assets need scripts/fetch-large-assets.sh')
    log(f'{len(assets)} assets verified.')


def stage(args, manifest):
    release = manifest['release']
    out = args.out or args.root / 'out/release-assets' / release['tag']
    assets = [asset for asset in manifest['assets'] if asset['group'] in release['groups']]
    for asset in assets:
        if not matches(args.root / asset['path'], asset):
            raise FetchError(f'{asset["path"]} is missing or does not match; run scripts/fetch-large-assets.sh first')
    out.mkdir(parents=True, exist_ok=True)
    sums, rows = [], []
    for asset in assets:
        source, dest = args.root / asset['path'], out / asset_name(asset)
        if not (dest.exists() and os.path.samefile(source, dest)):
            dest.unlink(missing_ok=True)
            try:
                os.link(source, dest)
            except OSError:
                shutil.copyfile(source, dest)
        sums.append(f'{asset["sha256"]}  {dest.name}')
        upstream = asset.get('upstream')
        origin = manifest['sources'][upstream['source']]['origin'] if upstream else 'WeHavePhones'
        if upstream and upstream.get('member'):
            origin += f', `{upstream["member"]}`'
        rows.append(f'| `{dest.name}` | {asset["size"]} | `{asset["sha256"]}` | {origin} |')
    (out / 'SHA256SUMS').write_text('\n'.join(sums) + '\n')
    notes = out.with_name(out.name + '.md')
    notes.write_text(
        'Prebuilt assets for building WeHavePhones from source. `scripts/fetch-large-assets.sh` '
        'downloads them and verifies the sha256 pinned in `scripts/large-assets.json`. '
        'See `docs/BUILD_ASSETS.md`.\n\n'
        '| File | Bytes | SHA-256 | Taken from |\n|---|---|---|---|\n' + '\n'.join(rows) + '\n')
    log(f'Staged {len(assets)} assets and SHA256SUMS in {out}')
    log('Publish only after the repository is public (docs/BUILD_ASSETS.md):')
    print(f'  gh release create {release["tag"]} --repo {release["repo"] or "<owner>/<repo>"} '
          f'--title "Build assets {release["tag"].removeprefix("assets-")}" --notes-file {notes} {out}/*')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--manifest', type=pathlib.Path, default=MANIFEST, help=argparse.SUPPRESS)
    parser.add_argument('--root', type=pathlib.Path, default=ROOT,
                        help='checkout whose assets are fetched or checked (default: this repository)')
    commands = parser.add_subparsers(dest='command', required=True)
    fetch_parser = commands.add_parser('fetch', help='make assets present and verified')
    fetch_parser.add_argument('groups', nargs='*')
    fetch_parser.add_argument('--replace', action='store_true',
                              help='move mismatching local files aside instead of failing')
    fetch_parser.add_argument('--release-only', action='store_true',
                              help='skip upstream sources, to prove our release alone is complete')
    check_parser = commands.add_parser('check', help='verify local assets without network access')
    check_parser.add_argument('groups', nargs='*')
    stage_parser = commands.add_parser('stage', help='prepare the release upload')
    stage_parser.add_argument('--out', type=pathlib.Path)
    args = parser.parse_args()
    args.root = args.root.resolve()
    manifest = json.loads(args.manifest.read_text())
    try:
        {'fetch': fetch, 'check': check, 'stage': stage}[args.command](args, manifest)
    except FetchError as error:
        log(str(error))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
