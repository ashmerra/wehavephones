#!/usr/bin/env python3
"""Group GitHub issues by GPU family so device-specific problems stand out.

  scripts/device-reports.py [--repo OWNER/NAME] [--limit N] [--json] [--all]
  scripts/device-reports.py --input issues.json     # offline, same JSON as gh prints
  scripts/device-reports.py --selftest

Read-only. Issues come from `gh issue list --json number,title,body,author,labels,
createdAt,url` (needs an authenticated gh) or from --input. Device data is taken,
strongest first, from:
  1. the app's "== WeHavePhones device report ==" block (also inside an issue form's
     "Device report" field),
  2. issue form fields (Phone model, Chipset and GPU, Android version, Game and version),
  3. phone names and model codes from scripts/device-catalog.json found in the title/body
     (issues filed by the external bot have no structured data).
See docs/DEVICE_REPORTS.md.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CATALOG = pathlib.Path(__file__).resolve().with_name('device-catalog.json')
DEFAULT_REPO = 'ashmerra/wehavephones'
GH_FIELDS = 'number,title,body,author,labels,createdAt,url'

REPORT_START = '== WeHavePhones device report =='
REPORT_END = '== end =='
FORM_LABELS = {
    'wehavephones version': 'version',
    'phone model': 'phone',
    'chipset and gpu': 'chipset',
    'android version': 'android',
    'game and version': 'game',
    'device report': 'device_report',
    'what happened?': 'what',
    'steps to reproduce': 'steps',
    'logs or screenshots': 'logs',
    'where did this report come from?': 'source',
    'link to the original report': 'source_link',
}
FAMILY_ORDER = ['adreno-6xx', 'adreno-7xx', 'adreno-83x', 'adreno-84x', 'adreno-8xx',
                'mali', 'xclipse', 'powervr', 'unknown']
PROBLEM_TITLE = re.compile(
    r'corrupt|crash|glitch|artifact|black screen|freez|flicker|stripe|garbl|broken|not work|'
    r"doesn'?t work|fail|error|bug|noise|missing|\bslow\b|\blag", re.I)
# A trailing word that makes it a different model ("Galaxy S23" is not "Galaxy S23 FE").
MODEL_SUFFIX = r'(?!\s*(?:ultra|plus|pro|fe|edge|max|mini|air|fold|flip|xl|\+))'
URL = re.compile(r'https?://[^\s)>\]]+')


# ---------------------------------------------------------------- catalog

def load_catalog(path=CATALOG):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def family_of(vendor, adreno_model=None):
    if vendor == 'adreno':
        if not adreno_model:
            return 'unknown'
        if adreno_model < 700:
            return 'adreno-6xx'
        if adreno_model < 800:
            return 'adreno-7xx'
        if 830 <= adreno_model < 840:
            return 'adreno-83x'
        if 840 <= adreno_model < 850:
            return 'adreno-84x'
        return 'adreno-8xx'
    return vendor if vendor in ('mali', 'xclipse', 'powervr') else 'unknown'


def _boundary(core):
    return re.compile(r'(?<![a-z0-9])' + core + r'(?![a-z0-9])', re.I)


def _alias_regex(alias):
    words = [re.escape(w) for w in alias.split()]
    core = r'(?<![a-z0-9])' + r'[\s\-_]*'.join(words) + r'(?![a-z0-9])'
    # "Galaxy S23+" already names its suffix; plain names must not be a prefix of another model.
    return re.compile(core if alias.endswith('+') else core + r'(?!\+)' + MODEL_SUFFIX, re.I)


def _code_regex(code, prefix):
    core = re.escape(code).replace(r'\-', r'[\s\-_]?')
    if prefix or code.upper().startswith('SM-'):
        # Samsung regional suffixes: SM-S916U, SM-S916U1, SM-S916B/DS
        core += r'[a-z0-9]{0,2}'
    return _boundary(core)


class Catalog:
    def __init__(self, data):
        self.socs = data.get('socs', {})
        self.devices = data.get('devices', [])
        self.matchers = []   # (regex, specificity, device, variant or None, kind)
        for dev in self.devices:
            for alias in dev.get('aliases', []):
                self.matchers.append((_alias_regex(alias), len(alias), dev, None, 'name'))
            for variant in dev.get('variants', []):
                for code in variant.get('model_codes', []):
                    self.matchers.append((_code_regex(code, False), len(code) + 100, dev, variant, 'code'))
            for code in dev.get('model_code_prefixes', []):
                self.matchers.append((_code_regex(code, True), len(code) + 100, dev, None, 'code'))
        self.soc_matchers = []
        for key, soc in self.socs.items():
            for name in soc.get('names', []) + [key]:
                self.soc_matchers.append((_alias_regex(name) if ' ' in name else _boundary(re.escape(name)),
                                          len(name), key))

    def gpu_of_soc(self, key):
        soc = self.socs.get(key, {})
        return soc.get('gpu_vendor', 'other'), soc.get('adreno_model'), soc.get('gpu', '')

    def find_device(self, text):
        """Best catalog hit in free text: the longest matching alias or model code."""
        best = None
        for rx, spec, dev, variant, kind in self.matchers:
            m = rx.search(text)
            if m and (best is None or spec > best[0]):
                best = (spec, dev, variant, kind, m.group(0))
        if not best:
            return None
        _, dev, variant, kind, matched = best
        variants = [variant] if variant else dev.get('variants', [])
        gpus = {}
        for v in variants:
            vendor, model, gpu = self.gpu_of_soc(v['soc'])
            gpus[(vendor, model)] = (v['soc'], gpu)
        info = {'device': dev['name'], 'matched': matched, 'via': 'catalog-' + kind,
                'catalog_confidence': dev.get('confidence', 'low')}
        if len(gpus) == 1:
            (vendor, model), (soc, gpu) = next(iter(gpus.items()))
            info.update(gpu_vendor=vendor, adreno_model=model, gpu=gpu, soc=soc)
        else:
            info['candidates'] = sorted({gpu for _, gpu in gpus.values()})
        return info

    def find_soc(self, text):
        best = None
        for rx, spec, key in self.soc_matchers:
            if rx.search(text) and (best is None or spec > best[0]):
                best = (spec, key)
        return best[1] if best else None


def gpu_from_text(text, catalog):
    """GPU vendor/model from a renderer, chipset or SoC string, or None."""
    m = re.search(r'adreno\D{0,8}(\d{3})', text, re.I)
    if m:
        return {'gpu_vendor': 'adreno', 'adreno_model': int(m.group(1)), 'gpu': 'Adreno ' + m.group(1)}
    for needle, vendor in (('mali', 'mali'), ('xclipse', 'xclipse'), ('powervr', 'powervr'),
                           ('imagination', 'powervr')):
        if needle in text.lower():
            return {'gpu_vendor': vendor, 'adreno_model': None, 'gpu': text.strip()}
    soc = catalog.find_soc(text)
    if soc:
        vendor, model, gpu = catalog.gpu_of_soc(soc)
        return {'gpu_vendor': vendor, 'adreno_model': model, 'gpu': gpu, 'soc': soc}
    return None


# ---------------------------------------------------------------- parsing

def parse_report_block(text):
    """key/value dict from the app's device report block, or None."""
    start = text.find(REPORT_START)
    if start < 0:
        return None
    body = text[start + len(REPORT_START):]
    end = body.find(REPORT_END)
    if end >= 0:
        body = body[:end]
    fields = {}
    for line in body.splitlines():
        line = line.strip()
        if not line or line.startswith('```'):
            continue
        key, sep, value = line.partition(':')
        if sep and re.fullmatch(r'[a-z][a-z0-9_]*', key.strip()):
            fields[key.strip()] = value.strip()
    return fields


def parse_form(body):
    """Issue-form sections keyed by FORM_LABELS names; None when the body is not a form."""
    parts = re.split(r'(?m)^###[ \t]+(.+?)[ \t]*$', body or '')
    if len(parts) < 3:
        return None
    sections = {}
    for label, content in zip(parts[1::2], parts[2::2]):
        key = FORM_LABELS.get(label.strip().lower())
        if not key:
            continue
        value = content.strip()
        if value == '_No response_':
            value = ''
        sections[key] = value
    return sections or None


def analyze(issue, catalog):
    title = issue.get('title') or ''
    body = issue.get('body') or ''
    labels = [l.get('name', '') if isinstance(l, dict) else str(l) for l in issue.get('labels') or []]
    author = issue.get('author')
    if isinstance(author, dict):
        author = author.get('login')
    rec = {
        'number': issue.get('number'), 'title': title, 'url': issue.get('url', ''),
        'author': author or '', 'labels': labels, 'created': issue.get('createdAt', ''),
        'phone': '', 'family': 'unknown', 'gpu': '', 'soc': '', 'android': '', 'game': '',
        'rule': '', 'profile': '', 'driver': '', 'driver_probes': '', 'app': '',
        'chip_id': '', 'via': [], 'source_link': '', 'note': '',
    }
    form = parse_form(body)
    report = parse_report_block(form.get('device_report', '') if form else '') or parse_report_block(body)
    gpu = None

    if report:
        rec['via'].append('report')
        rec['phone'] = report.get('device', '')
        rec['android'] = report.get('android', '')
        for key in ('rule', 'profile', 'driver', 'driver_probes', 'app', 'chip_id'):
            rec[key] = report.get(key, '')
        rec['soc'] = report.get('soc', '')
        vendor = report.get('gpu_vendor', '').lower()
        if vendor:
            try:
                model = int(report.get('adreno_model') or 0) or None
            except ValueError:
                model = None
            gpu = {'gpu_vendor': vendor, 'adreno_model': model, 'gpu': report.get('gl_renderer', '')}
        if not gpu and report.get('gl_renderer'):
            gpu = gpu_from_text(report['gl_renderer'], catalog)
        if not gpu and report.get('soc'):
            gpu = gpu_from_text(report['soc'], catalog)

    if form:
        rec['via'].append('form')
        rec['phone'] = form.get('phone') or rec['phone']
        rec['android'] = rec['android'] or form.get('android', '')
        rec['game'] = form.get('game', '')
        if not gpu and form.get('chipset'):
            gpu = gpu_from_text(form['chipset'], catalog)
        rec['source_link'] = form.get('source_link', '')

    if not rec['source_link']:
        # The first link of a bot issue is the original report; this repo's own links do not count.
        urls = [u for u in URL.findall(body) if 'github.com/ashmerra/' not in u]
        rec['source_link'] = urls[0] if urls else ''
    problem = bool(report or form or labels_report(labels) or PROBLEM_TITLE.search(title))

    # Phone name -> catalog; free text only when the structured fields did not name a phone.
    hit = None
    if rec['phone']:
        hit = catalog.find_device(rec['phone'])
    if not hit and problem and not report and not (form and form.get('phone')):
        hit = catalog.find_device(title) or catalog.find_device(body)
        if hit:
            rec['phone'] = hit['device']
    if hit:
        rec['via'].append(hit['via'])
        if not gpu and 'gpu_vendor' in hit:
            gpu = {k: hit[k] for k in ('gpu_vendor', 'adreno_model', 'gpu', 'soc')}
        elif not gpu and hit.get('candidates'):
            rec['note'] = 'ambiguous %s: %s' % (hit['device'], ' or '.join(hit['candidates']))
        if hit['catalog_confidence'] == 'low' and 'gpu_vendor' in hit:
            rec['note'] = 'low-confidence catalog entry'

    if gpu:
        rec['family'] = family_of(gpu.get('gpu_vendor'), gpu.get('adreno_model'))
        rec['gpu'] = gpu.get('gpu', '')
        rec['soc'] = rec['soc'] or gpu.get('soc', '')
    if not rec['via'] and not rec['note']:
        rec['note'] = 'no device named'
    elif rec['family'] == 'unknown' and not rec['note']:
        rec['note'] = 'phone not in catalog' if rec['phone'] else 'no GPU info'

    rec['external'] = bool((form and form.get('source', '').startswith('Reported elsewhere'))
                           or 'source:external' in labels
                           or (not report and not form and rec['source_link']))
    rec['is_report'] = problem
    return rec


def labels_report(labels):
    return any(l == 'bug' or l.startswith(('device:', 'source:')) for l in labels)


# ---------------------------------------------------------------- output

def collect(issues, catalog, include_all=False):
    recs = [analyze(i, catalog) for i in issues]
    recs.sort(key=lambda r: r['number'] or 0)
    skipped = [r['number'] for r in recs if not r['is_report'] and not include_all]
    recs = [r for r in recs if r['is_report'] or include_all]
    groups = {}
    for r in recs:
        if r['family'] != 'unknown' or r['via']:
            groups.setdefault(r['family'], []).append(r)
    unmatched = [r for r in recs if not r['via']]
    return {'groups': {f: groups[f] for f in FAMILY_ORDER if f in groups},
            'unmatched': unmatched, 'skipped': skipped}


def rule_text(r):
    parts = [r['rule'] and 'rule ' + r['rule'], r['profile'] and 'profile ' + r['profile'],
             r['driver'] and 'driver ' + r['driver']]
    return '; '.join(p for p in parts if p) or '-'


def render(result):
    out = []
    for family, recs in result['groups'].items():
        out.append('== %s (%d) ==' % (family, len(recs)))
        out.append('%-6s %-26s %-44s %-14s %s' % ('issue', 'phone', 'symptom', 'via', 'rule/profile/driver'))
        for r in recs:
            title = r['title'] if len(r['title']) <= 44 else r['title'][:41] + '...'
            phone = r['phone'] if len(r['phone']) <= 26 else r['phone'][:23] + '...'
            via = '+'.join(v.replace('catalog-', 'cat-') for v in r['via'])
            line = '#%-5s %-26s %-44s %-14s %s' % (r['number'], phone or '-', title, via, rule_text(r))
            if r['note']:
                line += '  [%s]' % r['note']
            out.append(line)
        out.append('')
    out.append('== unmatched issues needing device info (%d) ==' % len(result['unmatched']))
    for r in result['unmatched']:
        link = ('  source: ' + r['source_link']) if r['source_link'] else ''
        out.append('#%-5s %s [%s]%s' % (r['number'], r['title'], r['note'] or 'no device information', link))
    if not result['unmatched']:
        out.append('(none)')
    if result['skipped']:
        out.append('')
        out.append('Skipped %d issue(s) that do not look like problem reports: %s (use --all to include)'
                   % (len(result['skipped']), ', '.join('#%s' % n for n in result['skipped'])))
    return '\n'.join(out)


def to_json(result):
    def clean(r):
        return {k: v for k, v in r.items() if k not in ('is_report',)}
    return {'groups': {f: [clean(r) for r in recs] for f, recs in result['groups'].items()},
            'unmatched': [clean(r) for r in result['unmatched']], 'skipped': result['skipped']}


# ---------------------------------------------------------------- inputs

def fetch_issues(repo, limit):
    cmd = ['gh', 'issue', 'list', '--repo', repo, '--state', 'all', '--limit', str(limit),
           '--json', GH_FIELDS]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as exc:
        sys.exit('gh failed: %s' % exc)
    if proc.returncode != 0:
        sys.exit('gh failed: %s' % proc.stderr.strip())
    return json.loads(proc.stdout)


# ---------------------------------------------------------------- self-test

SELFTEST_REPORT = """== WeHavePhones device report ==
app: 0.0.2 (2) com.wehavephones
rules: remote v3
rule: adreno-84x
profile: adreno-84x (community)
driver: turnip-gen8-example (adrenotools)
driver_probes: turnip-gen8-example=ok; system=not-tried
gl_renderer: Adreno (TM) 840
gpu_vendor: adreno
adreno_model: 840
chip_id: 0x44050A21
kgsl_model: Adreno840v2
soc: QTI SM8850
device: OnePlus CPH2747
android: 16 (SDK 36)
== end =="""


def selftest():
    catalog = Catalog(load_catalog())

    def issue(number, title, body='', labels=()):
        return {'number': number, 'title': title, 'body': body,
                'labels': [{'name': l} for l in labels], 'author': {'login': 'tester'}}

    form = ('### WeHavePhones version\n\n0.0.2\n\n### Phone model\n\n%s\n\n### Chipset and GPU\n\n%s\n\n'
            '### Android version\n\n16\n\n### Game and version\n\nRetail\n\n### Device report\n\n%s\n\n'
            '### What happened?\n\nBlack screen\n\n### Where did this report come from?\n\n%s\n\n'
            '### Link to the original report\n\n_No response_\n')
    issues = [
        # (a) the app's device report inside the issue form
        issue(101, 'Garbled terrain', form % ('My phone', '_No response_',
              '```text\n' + SELFTEST_REPORT + '\n```', 'In the app'), ['bug']),
        # (b) form fields only
        issue(102, 'Crash on login', form % ('Samsung Galaxy S23+', 'Snapdragon 8 Gen 2 / Adreno 740',
              '_No response_', '_No response_')),
        issue(103, 'Black screen', form % ('Pixel 8a', 'Tensor G3', '_No response_', '_No response_')),
        # (c) bot-style free text issues
        issue(104, 'Graphics corruption on OnePlus 15',
              'A Reddit user reports graphics problems while running WoW on a OnePlus 15.\n\n'
              '[Report and screenshot](https://www.reddit.com/r/classicwow/comments/x/y/z/)'),
        issue(105, 'Flicker on Pixel 8 Pro', 'Seen on a Pixel 8 Pro running Retail. https://example.com/post'),
        issue(106, 'Artifacts on Galaxy S24', 'User has a Galaxy S24 and sees artifacts.'),
        issue(107, 'Artifacts on Galaxy S24 Ultra', 'Reported on SM-S928U.'),
        issue(108, 'Graphics corruption in WoW TBC', 'Black diagonal stripes.\n\n[Report](https://www.reddit.com/r/wow/x)'),
        issue(109, 'Document how to hide the controller overlay', 'Docs.'),
        issue(110, 'Slow on Samsung Galaxy S23 FE', 'FE is not in the catalog, S23 must not match.'),
    ]
    result = collect(issues, catalog)
    by_num = {r['number']: r for g in result['groups'].values() for r in g}
    by_num.update({r['number']: r for r in result['unmatched']})

    def check(cond, msg):
        if not cond:
            raise AssertionError(msg)

    check(by_num[101]['family'] == 'adreno-84x' and by_num[101]['rule'] == 'adreno-84x', 'report block')
    check(by_num[101]['driver'].startswith('turnip-gen8-example') and by_num[101]['via'] == ['report', 'form'],
          'report fields %r' % by_num[101]['via'])
    check(by_num[101]['phone'] == 'My phone' and by_num[101]['external'] is False, 'form phone kept')
    check(by_num[102]['family'] == 'adreno-7xx' and by_num[102]['via'][0] == 'form', 'form chipset')
    check(by_num[103]['family'] == 'mali', 'form chipset via catalog SoC')
    check(by_num[104]['family'] == 'adreno-84x' and by_num[104]['external'], 'bot OnePlus 15')
    check(by_num[104]['source_link'].startswith('https://www.reddit.com/'), 'bot source link')
    check(by_num[105]['family'] == 'mali' and by_num[105]['phone'] == 'Pixel 8 Pro', 'longest alias wins')
    check(by_num[106]['family'] == 'unknown' and 'ambiguous' in by_num[106]['note'], 'ambiguous S24')
    check(by_num[107]['family'] == 'adreno-7xx', 'S24 Ultra by model code')
    check(by_num[108] in result['unmatched'], 'no device info listed as unmatched')
    check(109 in result['skipped'], 'non-report skipped')
    check(by_num[110]['family'] == 'unknown' and by_num[110]['phone'] == '', 'S23 FE must not match S23')
    check(family_of('adreno', 830) == 'adreno-83x' and family_of('adreno', 650) == 'adreno-6xx', 'families')
    check(json.loads(json.dumps(to_json(result)))['skipped'] == [109], 'json output')
    print(render(result))
    print('\nselftest ok')


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    ap.add_argument('--repo', default=DEFAULT_REPO)
    ap.add_argument('--limit', type=int, default=200)
    ap.add_argument('--input', help='read issues from this JSON file instead of calling gh')
    ap.add_argument('--json', action='store_true', help='print JSON instead of a table')
    ap.add_argument('--all', action='store_true', help='include issues that do not look like problem reports')
    ap.add_argument('--catalog', default=str(CATALOG))
    ap.add_argument('--selftest', action='store_true')
    args = ap.parse_args()
    if args.selftest:
        selftest()
        return
    catalog = Catalog(load_catalog(args.catalog))
    if args.input:
        with open(args.input, encoding='utf-8') as f:
            issues = json.load(f)
    else:
        issues = fetch_issues(args.repo, args.limit)
    result = collect(issues, catalog, args.all)
    if args.json:
        print(json.dumps(to_json(result), indent=2))
    else:
        print(render(result))


if __name__ == '__main__':
    main()
