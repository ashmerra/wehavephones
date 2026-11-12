#!/usr/bin/env python3
"""End-to-end game tests for WeHavePhones Dev on a real phone.

    scripts/e2e-wehavephones.py list
    scripts/e2e-wehavephones.py run [SCENARIO|GROUP ...] [--serial S]
    scripts/e2e-wehavephones.py restore [--serial S]
    scripts/e2e-wehavephones.py selftest

The game scenarios start one game with a known set of app settings, logs in, enters
the world with the last played character and checks what the character does.
A small test addon (tests/e2e/WeHavePhonesE2E) shows the game's own view of the
character as colored squares, which the runner reads from screenshots: in
world, jumped (the JUMP binding ran and the character left the ground), moved
and turned.

Per scenario:
  1. Stop any session, save the app settings the scenario changes and write the
     scenario's settings. "Default settings" means the app's own defaults: no
     touch controls, overlay, runtime profile, game resolution or driver pick.
  2. Copy the addon into the game with a .toc for the client's interface version.
  3. Log in the way a player does: the app's Play, through Battle.net and the
     sign-in Battle.net saved on the phone. The runner never types credentials.
  4. Wait for character select (the red Enter World button), press Enter or tap
     the button, and wait for the marker.
  5. Jump (keyboard Space, the on-screen gamepad's A or the touch Jump key),
     plus the scenario's extra checks, then save screenshots and crash logs.
  6. Stop the session.
Afterwards the runner removes the addon and restores the saved settings, also
after a failure. If it is killed, `restore` puts the settings back from
out/e2e/settings-backup.json.

The addon app scenarios (wowup, curseforge) open the app's Add-ons > Addon apps
and start the manager from its card, updating it when an update is offered.
They pass when the manager's own log shows it loaded and the session then stays
open for a hold time. They fail when Android closes the app (reported with its
reason, such as LOW_MEMORY) or the log stays short of the ready lines.

Results and evidence go to out/e2e/<time>/ (gitignored). Exit status: 0 all
passed or skipped, 1 a scenario failed, 2 usage or setup error.
The phone must be awake and unlocked. No credentials are read or printed here.
"""
import argparse
import datetime
import importlib.util
import io
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
ADDON_DIR = ROOT / 'tests/e2e/WeHavePhonesE2E'
ADDON = 'WeHavePhonesE2E'
OUT = ROOT / 'out/e2e'
BACKUP = OUT / 'settings-backup.json'

# Keep one adb binary for this runner and the device helper.
if not os.environ.get('ADB_BIN'):
    _sdk = pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or pathlib.Path.home() / 'Android/Sdk')
    if (_sdk / 'platform-tools/adb').is_file():
        os.environ['ADB_BIN'] = str(_sdk / 'platform-tools/adb')

_spec = importlib.util.spec_from_file_location('wehavephones_device', ROOT / 'scripts/wehavephones-device.py')
dev = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dev)

PKG = dev.PKG
ACTIVITY = '%s/com.wehavephones.mobile.WeHavePhonesActivity' % PKG
SESSION = '%s/com.wehavephones.XServerDisplayActivity' % PKG
MAIN_PREFS = 'shared_prefs/%s_preferences.xml' % PKG
DEVICE_PREFS = 'shared_prefs/wehavephones_devices.xml'
TOUCH_LAYOUT = 'files/wehavephones/touch-layout.json'
LAUNCH_RECORD = 'files/wehavephones/launch-last.json'
WOW_DIR = 'files/imagefs/home/xuser-%s/.wine/drive_c/Program Files (x86)/World of Warcraft'
APPDATA = 'files/imagefs/home/xuser-%s/.wine/drive_c/users/xuser/AppData/Roaming'

# App settings removed for "default settings", so the app falls back to its own defaults.
# Exact keys, plus key prefixes ending in "_". The game choice, region, setup state and
# touch guide flags stay: they are not settings a player tunes.
DEFAULT_MAIN = ['wehavephones_mobile_touch', 'wehavephones_mobile_touch_scale',
                'wehavephones_disable_virtual_gamepad', 'wehavephones_performance_overlay',
                'wehavephones_runtime_profile', 'wehavephones_game_screen_size',
                'wehavephones_game_screen_size_', 'wehavephones_keep_running_in_background',
                'wehavephones_start_input_bridge', 'wehavephones_save_memory']
DEFAULT_DEVICES = ['driver_choice']
# Every key a scenario may write; restore covers exactly these.
TOUCHED_MAIN = DEFAULT_MAIN + ['wehavephones_product_dir']

PRODUCT_CODES = {'_retail_': 'wow', '_forever_': 'wowf', '_classic_': 'wow_classic',
                 '_classic_titan_': 'wow_classic_titan', '_anniversary_': 'wow_anniversary',
                 '_classic_era_': 'wow_classic_era'}


class Scenario:
    def __init__(self, name, game, title, jump='key', touch=False, extra=(), boot=420):
        self.name, self.game, self.title = name, game, title
        self.jump, self.touch, self.extra, self.boot = jump, touch, tuple(extra), boot

    def describe(self):
        controls = 'touch controls' if self.touch else 'default settings'
        how = {'key': 'Space', 'gamepad': 'on-screen gamepad A', 'touch': 'touch Jump key'}[self.jump]
        extra = ' + ' + ', '.join(self.extra) if self.extra else ''
        return '%s, %s, jump with %s%s' % (self.title, controls, how, extra)


# One entry per game line: scenario name, game folder, title, seconds to reach the world.
GAMES = [
    ('classic-era', '_classic_era_', 'Classic Era', 420),
    ('anniversary', '_anniversary_', 'Anniversary (TBC)', 420),
    ('mop', '_classic_', 'MoP Classic', 420),
    ('titan', '_classic_titan_', 'Wrath / Titan', 420),
    ('retail', '_retail_', 'Retail', 600),
    ('forever', '_forever_', 'Forever', 600),
]
SCENARIOS = [Scenario(name, game, title, boot=boot) for name, game, title, boot in GAMES]
SCENARIOS += [Scenario(name + '-touch', game, title, jump='touch', touch=True, extra=('move', 'turn'), boot=boot)
              for name, game, title, boot in GAMES]
SCENARIOS.append(Scenario('classic-era-gamepad', '_classic_era_', 'Classic Era', jump='gamepad'))


class AppScenario:
    """An addon manager started from the app's Addon apps screen.

    log: 'file' for one log the app appends to, 'dirs' for a new folder of logs per start.
    ready: log lines that show it loaded, in the order the app writes them.
    hold: seconds the session must then stay open, for work the app does not log the end of.
    """

    def __init__(self, name, title, log, path, ready, boot=300, hold=30):
        self.name, self.title, self.log, self.path = name, title, log, path
        self.ready, self.boot, self.hold = tuple(ready), boot, hold

    def describe(self):
        hold = ', then it stays open %d s' % self.hold if self.hold else ''
        return '%s from Addon apps: its log shows %s%s' % (
            self.title, ' then '.join('"%s"' % line for line in self.ready), hold)


APP_SCENARIOS = [
    # Past the tray icon that used to hang the session, its addon list, then the scan of the
    # game's addon folders. No hold: the lookups on the addon sites follow, and on the Galaxy S23
    # (8 GB) Android closes the app for low memory there, at about 1.3 GB across 10 processes.
    AppScenario('wowup', 'WowUp', 'file', 'WowUp/logs/main.log',
                ['Tray created true', 'Checking for addon updates', '[scan] wowup scan of'], hold=0),
    # Its .NET mods agent answered, then the WoW library scan ("Forging your library") finished.
    AppScenario('curseforge', 'CurseForge', 'dirs', 'CurseForge/logs',
                ['Connected to Mods Agent', 'Scan complete for game 1 ']),
]
SCENARIOS += APP_SCENARIOS
BY_NAME = {scenario.name: scenario for scenario in SCENARIOS}
GROUPS = {
    'versions': [name for name, _, _, _ in GAMES],
    'touch': [name + '-touch' for name, _, _, _ in GAMES],
    'extra': ['classic-era-gamepad'],
    'apps': [scenario.name for scenario in APP_SCENARIOS],
}
GROUPS['all'] = GROUPS['versions'] + GROUPS['touch'] + GROUPS['extra'] + GROUPS['apps']
GROUPS['default'] = GROUPS['versions'] + GROUPS['touch'] + GROUPS['apps']


def say(message):
    print('[e2e] ' + message, flush=True)


class Failure(Exception):
    pass


class Phone:
    def __init__(self, serial):
        self.serial = serial
        self.size = None

    def sh(self, command, timeout=60):
        code, out, err = dev.shell(self.serial, command, timeout)
        if code == 124 or (code != 0 and 'device' in err and ('not found' in err or 'offline' in err)):
            self.reconnect()
            code, out, err = dev.shell(self.serial, command, timeout)
        return out

    def reconnect(self):
        if dev.is_online(self.serial):
            return
        say('adb dropped; reconnecting')
        serial = dev.reconnect(max_seconds=300)
        if not serial:
            raise Failure('the phone did not come back on adb')
        self.serial = serial

    def private(self, command, timeout=60):
        return self.sh('run-as %s sh -c %s' % (PKG, shlex.quote(command)), timeout)

    def read_private(self, path):
        code, out, _ = dev.adb(['exec-out', 'run-as', PKG, 'cat', path], self.serial, timeout=60)
        return out if code == 0 else None

    def write_private(self, path, data):
        tmp = '/data/local/tmp/wehavephones-e2e-%d' % os.getpid()
        code, _, err = dev.adb(['shell', 'cat > %s && chmod 644 %s' % (tmp, tmp)], self.serial,
                               input_bytes=data if isinstance(data, bytes) else data.encode())
        if code != 0:
            raise Failure('could not write %s: %s' % (path, err.strip()))
        self.private('mkdir -p %s && cat %s > %s' % (shlex.quote(os.path.dirname(path)), tmp, shlex.quote(path)))
        self.sh('rm -f %s' % tmp)

    def screenshot(self, path=None):
        from PIL import Image
        for _ in range(3):
            try:
                data = subprocess.run([dev.adb_bin(), '-s', self.serial, 'exec-out', 'screencap', '-p'],
                                      capture_output=True, timeout=60).stdout
            except subprocess.TimeoutExpired:
                data = b''
            if data.startswith(b'\x89PNG'):
                if path:
                    pathlib.Path(path).write_bytes(data)
                image = Image.open(io.BytesIO(data)).convert('RGB')
                self.size = image.size
                return image
            self.reconnect()
        raise Failure('screencap failed')

    def tap(self, x, y):
        self.sh('input tap %d %d' % (x, y))

    def hold(self, x, y, ms=200):
        self.sh('input swipe %d %d %d %d %d' % (x, y, x, y, ms))

    def drag(self, x1, y1, x2, y2, ms):
        self.sh('input swipe %d %d %d %d %d' % (x1, y1, x2, y2, ms))

    def key(self, name):
        self.sh('input keyevent ' + name)

    def locked(self):
        # Only the current user's line counts; Secure Folder reports its own lock.
        return any('(current)' in line and 'deviceLocked=1' in line for line in self.sh('dumpsys trust').splitlines())

    def wake(self):
        if 'mWakefulness=Awake' not in self.sh('dumpsys power'):
            self.key('KEYCODE_WAKEUP')
            time.sleep(1)

    def session_open(self):
        return any('Hist' in line and SESSION in line for line in self.sh('dumpsys activity activities', 90).splitlines())

    def dismiss_idle_lock(self):
        """Samsung Game Booster dims and touch-locks an idle game; injected touches then do nothing."""
        windows = self.sh('dumpsys window windows | grep -A30 "IgniteTouchProtectionPresenter}:" | grep isVisible=true')
        if 'isVisible=true' in windows:
            say('dismissing Game Booster touch lock')
            self.tap(1100, 300)
            time.sleep(1)
            self.sh('input draganddrop 1170 570 300 900 1000')
            time.sleep(1)

    def ui_nodes(self):
        for _ in range(4):
            text = dev.adb(['exec-out', 'uiautomator', 'dump', '/dev/tty'], self.serial, timeout=40)[1]
            start, end = text.find('<?xml'), text.rfind('</hierarchy>')
            if start >= 0 and end > start:
                return list(ET.fromstring(text[start:end + len('</hierarchy>')]).iter('node'))
            time.sleep(1)
        return []


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', node.get('bounds', '')))
    return (x1 + x2) // 2, (y1 + y2) // 2


# ---------- settings ----------

def parse_prefs(text):
    if not text or '<map' not in text:
        return ET.Element('map')
    return ET.fromstring(text[text.find('<map'):])


def prefs_text(root):
    for child in root:
        child.tail = '\n'
    root.text = '\n'
    return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" + ET.tostring(root, encoding='unicode') + '\n'


def matches(name, keys):
    return any(name == key or (key.endswith('_') and name.startswith(key)) for key in keys)


def entries(root, keys):
    return {child.get('name'): ET.tostring(child, encoding='unicode').strip()
            for child in root if matches(child.get('name', ''), keys)}


def apply_entries(root, keys, saved):
    """Replace the keys' entries in root with the saved ones (absent keys are removed)."""
    for child in list(root):
        if matches(child.get('name', ''), keys):
            root.remove(child)
    for xml in saved.values():
        root.append(ET.fromstring(xml))


def set_value(root, tag, name, value):
    for child in list(root):
        if child.get('name') == name:
            root.remove(child)
    element = ET.SubElement(root, tag, name=name)
    if tag == 'string':
        element.text = value
    else:
        element.set('value', value)


def save_settings(phone):
    main = parse_prefs(phone.read_private(MAIN_PREFS))
    devices = parse_prefs(phone.read_private(DEVICE_PREFS))
    backup = {'serial': phone.serial, 'saved': datetime.datetime.now().isoformat(timespec='seconds'),
              'main': entries(main, TOUCHED_MAIN), 'devices': entries(devices, DEFAULT_DEVICES)}
    OUT.mkdir(parents=True, exist_ok=True)
    if BACKUP.is_file():
        raise Failure('%s exists from an unfinished run; run `%s restore` first' % (BACKUP.relative_to(ROOT), sys.argv[0]))
    BACKUP.write_text(json.dumps(backup, indent=2))
    return backup


def restore_settings(phone, backup):
    dev.stop_runtime(phone.serial)
    main = parse_prefs(phone.read_private(MAIN_PREFS))
    apply_entries(main, TOUCHED_MAIN, backup['main'])
    phone.write_private(MAIN_PREFS, prefs_text(main))
    devices = parse_prefs(phone.read_private(DEVICE_PREFS))
    apply_entries(devices, DEFAULT_DEVICES, backup['devices'])
    phone.write_private(DEVICE_PREFS, prefs_text(devices))
    BACKUP.unlink(missing_ok=True)
    say('app settings restored')


def write_scenario_settings(phone, scenario):
    main = parse_prefs(phone.read_private(MAIN_PREFS))
    apply_entries(main, DEFAULT_MAIN, {})
    set_value(main, 'string', 'wehavephones_product_dir', scenario.game)
    if scenario.touch:
        set_value(main, 'boolean', 'wehavephones_mobile_touch', 'true')
    phone.write_private(MAIN_PREFS, prefs_text(main))
    devices = parse_prefs(phone.read_private(DEVICE_PREFS))
    apply_entries(devices, DEFAULT_DEVICES, {})
    phone.write_private(DEVICE_PREFS, prefs_text(devices))


# ---------- game files ----------

def wow_dir(phone):
    main = parse_prefs(phone.read_private(MAIN_PREFS))
    container = next((c.get('value') for c in main if c.get('name') == 'wehavephones_container_id'), '1')
    return os.environ.get('WEHAVEPHONES_E2E_WOW_DIR') or WOW_DIR % container


def client_version(phone, scenario):
    """Installed version from .build.info, or None when the game is not installed."""
    base = wow_dir(phone)
    exe = phone.private('ls %s 2>/dev/null' % shlex.quote(base + '/' + scenario.game))
    if not re.search(r'^Wow\S*\.exe$', exe, re.M):
        return None
    info = phone.read_private(base + '/.build.info') or ''
    lines = [line.split('|') for line in info.splitlines() if line.strip()]
    if not lines:
        return None
    header = [column.split('!')[0] for column in lines[0]]
    for row in lines[1:]:
        record = dict(zip(header, row))
        if record.get('Product') == PRODUCT_CODES.get(scenario.game):
            return record.get('Version')
    return None


def interface_version(version):
    major, minor, patch = (int(part) for part in version.split('.')[:3])
    return major * 10000 + minor * 100 + patch


def install_addon(phone, scenario, version):
    folder = '%s/%s/Interface/AddOns/%s' % (wow_dir(phone), scenario.game, ADDON)
    toc = ('## Interface: %d\n## Title: WeHavePhones E2E\n'
           '## Notes: Test marker for scripts/e2e-wehavephones.py, which removes it again.\n'
           '## SavedVariables: WeHavePhonesE2EDB\n\n%s.lua\n') % (interface_version(version), ADDON)
    phone.write_private(folder + '/%s.toc' % ADDON, toc)
    phone.write_private(folder + '/%s.lua' % ADDON, (ADDON_DIR / ('%s.lua' % ADDON)).read_bytes())


def remove_addon(phone, game):
    base = '%s/%s' % (wow_dir(phone), game)
    phone.private('rm -rf %s; rm -f %s/WTF/Account/*/SavedVariables/%s.lua %s/WTF/Account/*/SavedVariables/%s.lua.bak'
                  % (shlex.quote(base + '/Interface/AddOns/' + ADDON), shlex.quote(base), ADDON, shlex.quote(base), ADDON))


# ---------- screen reading ----------

def MAGENTA(p):
    return p[0] > 190 and p[1] < 80 and p[2] > 190


def CYAN(p):
    return p[0] < 80 and p[1] > 190 and p[2] > 190


def read_marker(image):
    """Flags from the addon's strip: {'inWorld': 'on'|'off'|'pressed', ...}, or None when it is not on screen."""
    width, height = image.size
    pixels = image.load()
    step = 2
    found = [(x, y) for y in range(0, height // 2, step) for x in range(0, width, step) if MAGENTA(pixels[x, y])]
    if len(found) < 20:
        return None
    # The largest dense square of magenta: group by rows near the median.
    ys = sorted(y for _, y in found)
    mid_y = ys[len(ys) // 2]
    row = [x for x, y in found if abs(y - mid_y) <= step]
    xs = sorted(row)
    mid_x = xs[len(xs) // 2]
    left = right = mid_x
    while left - 1 >= 0 and MAGENTA(pixels[left - 1, mid_y]):
        left -= 1
    while right + 1 < width and MAGENTA(pixels[right + 1, mid_y]):
        right += 1
    size = right - left + 1
    if size < 8:
        return None
    # Squares are 26 units with 4-unit gaps.
    pitch = size * 30 / 26
    cx = (left + right) / 2
    flags = {}
    for index, name in enumerate(('inWorld', 'jumped', 'moved', 'turned'), 1):
        x = int(cx + index * pitch)
        if x >= width:
            return None
        r, g, b = pixels[x, mid_y]
        flags[name] = 'on' if g > 150 and r < 100 and b < 100 else 'pressed' if r > 150 and g > 150 and b < 100 \
            else 'off' if r < 60 and g < 60 and b < 60 else '?'
    end = int(cx + 5 * pitch)
    if end >= width or not CYAN(pixels[end, mid_y]) or '?' in flags.values():
        return None
    return flags


def launch_overlay(image):
    """True while the app's light green launch overlay covers the game."""
    width, height = image.size
    pixels = image.load()
    samples = [pixels[x, y] for y in range(0, height, 24) for x in range(0, width, 24)]
    green = sum(1 for r, g, b in samples if 165 <= r <= 215 and 190 <= g <= 235 and 145 <= b <= 195 and g > r > b)
    return green >= len(samples) * .25


def enter_world_button(image, viewport=None):
    """Center of the red Enter World button at the bottom of character select, or None.

    Classic clients draw it dark red (about 80,13,18), Retail-style ones a little brighter. The on-screen
    gamepad or touch keys can cover parts of it, so this looks for a wide horizontal band of
    red rows near the bottom center rather than a filled rectangle.
    """
    if launch_overlay(image):
        return None
    width, height = image.size
    vx1, vy1, vx2, vy2 = viewport or (0, 0, width, height)
    vw, vh = vx2 - vx1, vy2 - vy1
    x1, x2 = int(vx1 + vw * .34), int(vx1 + vw * .66)
    y1, y2 = int(vy1 + vh * .80), int(vy1 + vh * .995)
    pixels = image.load()
    band = []
    for y in range(y1, y2, 2):
        row = []
        for x in range(x1, x2, 2):
            r, g, b = pixels[x, y]
            # Button reds carry almost no green. Desert ground and fire do.
            if r >= 55 and g <= r * .2 and b <= r * .35:
                row.append(x)
        if len(row) * 2 >= vw * .05 and row[-1] - row[0] >= vw * .08:
            band.append((y, row))
    if len(band) >= 4:
        xs = sorted(x for _, row in band for x in row)
        ys = [y for y, _ in band]
        return xs[len(xs) // 2], ys[len(ys) // 2]
    return gamepad_footer(image, viewport)


def gamepad_footer(image, viewport=None):
    """Center of the button hint bar ("A Enter World ...") of a gamepad character select, or None.

    Forever draws it with the on-screen gamepad: a flat dark bar at the bottom center. Game
    scenes, action bars and loading screens are never one exact color that wide. The bar is
    a warm near-black gray, unlike the blue-black of the app's own progress bar.
    """
    width, height = image.size
    vx1, vy1, vx2, vy2 = viewport or (0, 0, width, height)
    vw, vh = vx2 - vx1, vy2 - vy1
    x1, x2 = int(vx1 + vw * .36), int(vx1 + vw * .64)
    pixels = image.load()
    rows = []
    for y in range(int(vy1 + vh * .86), int(vy1 + vh * .995), 2):
        samples = [pixels[x, y] for x in range(x1, x2, 3)]
        mode = max(set(samples), key=samples.count)
        if (samples.count(mode) >= len(samples) * .7 and 20 < sum(mode) < 150 and max(mode) < 60
                and max(mode) - min(mode) <= 12 and mode[2] <= mode[0] + 4):
            rows.append(y)
    if len(rows) < 8:
        return None
    return int(vx1 + vw / 2), rows[len(rows) // 2]


# ---------- steps ----------

def check_ready(phone):
    if phone.locked():
        raise Failure('the phone is locked; ask the user to unlock it')
    phone.wake()
    level, powered = dev.battery(phone.serial)
    if level <= 15 and not powered:
        raise Failure('battery at %d%% and unplugged' % level)
    if level < 40 and not powered:
        say('battery %d%% and unplugged; plug the phone in for long runs' % level)


def login(phone, evidence):
    phone.private('rm -f %s' % LAUNCH_RECORD)
    phone.sh('am start -W -n %s' % ACTIVITY, 90)
    deadline = time.time() + 60
    while True:
        nodes = phone.ui_nodes()
        texts = [node.get('text', '') for node in nodes]
        if any('App Compatibility' in text for text in texts):
            ok = next((node for node in nodes if node.get('text') == 'OK'), None)
            if ok is not None:
                phone.tap(*center(ok))
                time.sleep(1)
                continue
        play = next((node for node in nodes if node.get('resource-id', '').endswith(':id/BTPlay')), None)
        if play is not None and play.get('enabled') != 'false':
            phone.tap(*center(play))
            say('pressed Play')
            break
        if time.time() > deadline:
            raise Failure('Play is not on screen')
        time.sleep(2)
    started = time.time()
    last = None
    while time.time() - started < 720:
        time.sleep(5)
        phone.dismiss_idle_lock()
        text = phone.read_private(LAUNCH_RECORD) or ''
        try:
            record = json.loads(text) if text.strip() else {}
        except ValueError:
            record = {}
        stage = record.get('lastStage')
        if stage != last:
            say('Play: %s' % stage)
            last = stage
        result = record.get('result')
        if result in ('running', 'already-running'):
            (evidence / 'launch-last.json').write_text(text)
            say('Play: game running after %d s' % (time.time() - started))
            return
        if result == 'failed':
            (evidence / 'launch-last.json').write_text(text)
            raise Failure('Play failed at %s: %s' % (stage, record.get('error', 'no error recorded')))
    raise Failure('Play did not start the game in 12 minutes (Battle.net challenge or spinner? check the screen)')


def touch_layout(phone):
    text = phone.read_private(TOUCH_LAYOUT) or ''
    try:
        return json.loads(text) if text.strip() else None
    except ValueError:
        return None


def enter_world(phone, scenario, evidence, seconds):
    started = time.time()
    pressed_at = 0
    charselect_saved = False
    # A press during "Connecting" cancels the login, so a detection must hold for two shots.
    seen = 0
    while time.time() - started < seconds:
        phone.dismiss_idle_lock()
        image = phone.screenshot()
        flags = read_marker(image)
        if flags and flags['inWorld'] == 'on':
            say('in world after %d s' % (time.time() - started))
            return flags
        layout = touch_layout(phone) if scenario.touch else None
        button = enter_world_button(image, layout.get('viewport') if layout else None)
        seen = seen + 1 if button else 0
        if seen >= 2 and time.time() - pressed_at > 12:
            if not charselect_saved:
                image.save(evidence / 'charselect.png')
                charselect_saved = True
                say('character select after %d s' % (time.time() - started))
            if scenario.touch:
                phone.tap(*button)
                say('tapped Enter World at %d,%d' % button)
            else:
                phone.key('KEYCODE_ENTER')
                say('pressed Enter')
            pressed_at = time.time()
        time.sleep(3)
    phone.screenshot(evidence / 'timeout.png')
    if not charselect_saved:
        raise Failure('character select did not show in %d s (see timeout.png)' % seconds)
    raise Failure('the world did not load in %d s after character select (see timeout.png)' % seconds)


def wait_flag(phone, name, seconds=4):
    deadline = time.time() + seconds
    flags = None
    while time.time() < deadline:
        flags = read_marker(phone.screenshot()) or flags
        if flags and flags[name] == 'on':
            return flags
        time.sleep(.5)
    return flags


def gamepad_a(phone):
    width, height = phone.size or (2340, 1080)
    # The default on-screen gamepad's A button on the S23 (2340x1080), scaled to other screens.
    spot = os.environ.get('WEHAVEPHONES_E2E_GAMEPAD_A', '2024,916')
    x, y = (int(v) for v in spot.split(','))
    return int(x * width / 2340), int(y * height / 1080)


def play_mode(phone):
    """Open the touch toolbar and switch to Play; returns the layout with the Jump key."""
    for _ in range(6):
        layout = touch_layout(phone)
        if not layout or not layout.get('visible'):
            raise Failure('the touch controls are not on screen (no %s)' % TOUCH_LAYOUT)
        keys = layout.get('keys', {})
        if layout.get('guide'):
            phone.tap(int(layout['viewport'][0] + 40), int(layout['viewport'][1] + 300))
        elif layout['mode'] == 'play' and 'jump' in keys:
            return layout
        elif 'handle' in keys and layout['mode'] == 'collapsed':
            phone.tap(*rect_center(keys['handle']))
        elif 'play' in keys:
            phone.tap(*rect_center(keys['play']))
        time.sleep(1.5)
    raise Failure('could not switch the touch controls to Play: %s' % touch_layout(phone))


def rect_center(rect):
    return int((rect[0] + rect[2]) / 2), int((rect[1] + rect[3]) / 2)


def jump(phone, scenario):
    """The jumped square pulses green after any fall, so wait for black, press, then look for green."""
    layout = play_mode(phone) if scenario.jump == 'touch' else None
    flags = None
    for _ in range(3):
        deadline = time.time() + 20
        while time.time() < deadline:
            flags = read_marker(phone.screenshot())
            if flags and flags['jumped'] == 'off':
                break
            time.sleep(1)
        else:
            raise Failure('the jumped square never settled to black before the jump (%s)' % flags)
        if scenario.jump == 'key':
            phone.key('KEYCODE_SPACE')
        elif scenario.jump == 'gamepad':
            phone.hold(*gamepad_a(phone), ms=250)
        else:
            phone.hold(*rect_center(layout['keys']['jump']), ms=200)
        flags = wait_flag(phone, 'jumped')
        if flags and flags['jumped'] == 'on':
            say('jumped')
            return
    state = flags['jumped'] if flags else 'marker not visible'
    raise Failure('the character did not jump (jumped flag: %s%s)' % (
        state, ', the jump binding ran but the character stayed on the ground' if state == 'pressed' else ''))


def move(phone, scenario):
    layout = play_mode(phone)
    x, y, radius = layout['stick']
    phone.drag(int(x), int(y), int(x), int(y - radius * .8), 1500)
    flags = wait_flag(phone, 'moved')
    if not flags or flags['moved'] != 'on':
        raise Failure('the stick did not move the character')
    say('moved with the stick')


def world_change(before, after, viewport):
    """Mean brightness change of the upper right game area, 0-255. Idle scenes stay below 6."""
    from PIL import ImageChops, ImageStat
    vx1, vy1, vx2, vy2 = viewport
    w, h = vx2 - vx1, vy2 - vy1
    box = (int(vx1 + w * .55), int(vy1 + h * .12), int(vx1 + w * .95), int(vy1 + h * .65))
    a, b = (image.convert('L').crop(box).resize((200, 100)) for image in (before, after))
    return ImageStat.Stat(ImageChops.difference(a, b)).mean[0]


# Camera drags in fractions of the game area: (x1, y1, x2, y2, repeats). Chat, the quest tracker
# and the action bars sit in different places per client and touch layout, and a drag that
# crosses them can stop turning or open a chat link, so the runner tries several.
TURN_DRAGS = [(.84, .56, .36, .56, 1), (.97, .40, .80, .40, 3), (.97, .74, .55, .74, 1)]


def turn(phone, scenario):
    layout = play_mode(phone)
    vx1, vy1, vx2, vy2 = layout['viewport']
    w, h = vx2 - vx1, vy2 - vy1
    before = phone.screenshot()
    change = 0
    for x1, y1, x2, y2, repeats in TURN_DRAGS:
        for _ in range(repeats):
            phone.drag(int(vx1 + w * x1), int(vy1 + h * y1), int(vx1 + w * x2), int(vy1 + h * y2), 900)
            time.sleep(.5)
        flags = wait_flag(phone, 'turned', 3)
        if flags and flags['turned'] == 'on':
            say('turned with a camera drag')
            return
        # Retail can hide the facing (yellow) or report one that never changes (black). Fall
        # back on the view: standing still changes it by 6 or less, a turn by 11 to 28.
        change = world_change(before, phone.screenshot(), layout['viewport'])
        if change >= 8:
            say('turned the view with a camera drag (facing not readable, view changed by %.0f)' % change)
            return
    raise Failure('camera drags did not turn the character (view changed by %.0f)' % change)


EXTRA = {'move': move, 'turn': turn}


def crash_lines(phone):
    return phone.sh('logcat -d -b crash', 60).splitlines()


def run_scenario(phone, scenario, evidence):
    evidence.mkdir(parents=True, exist_ok=True)
    started = time.time()
    check_ready(phone)
    version = client_version(phone, scenario)
    if not version:
        return 'skip', '%s is not installed' % scenario.game, 0
    say('== %s: %s %s' % (scenario.name, scenario.title, version))
    dev.stop_runtime(phone.serial)
    time.sleep(2)
    write_scenario_settings(phone, scenario)
    install_addon(phone, scenario, version)
    phone.private('rm -f %s' % TOUCH_LAYOUT)
    crashes_before = crash_lines(phone)
    try:
        try:
            login(phone, evidence)
        except Failure as error:
            # Battle.net sometimes hangs on sign-in or never starts the game. One retry from a
            # clean session clears that. A second failure is the scenario's result.
            say('%s; retrying Play once' % error)
            dev.stop_runtime(phone.serial)
            time.sleep(3)
            login(phone, evidence)
        enter_world(phone, scenario, evidence, scenario.boot)
        time.sleep(4)
        phone.screenshot(evidence / 'world.png')
        jump(phone, scenario)
        for name in scenario.extra:
            EXTRA[name](phone, scenario)
        phone.screenshot(evidence / 'checks.png')
        result, detail = 'pass', version
    except Failure as error:
        phone.screenshot(evidence / 'failure.png')
        result, detail = 'fail', str(error)
    finally:
        crashes = crash_lines(phone)
        new = crashes[len(crashes_before):] if crashes[:len(crashes_before)] == crashes_before else crashes
        if new:
            (evidence / 'crash.txt').write_text('\n'.join(new) + '\n')
        (evidence / 'logcat.txt').write_text(phone.sh('logcat -d -t 3000', 90))
        dev.stop_runtime(phone.serial)
        remove_addon(phone, scenario.game)
    if result == 'pass' and new and any(PKG in line for line in new):
        result, detail = 'fail', 'passed the checks but the app crashed (crash.txt)'
    return result, detail, time.time() - started


# ---------- addon apps ----------

def appdata(phone):
    main = parse_prefs(phone.read_private(MAIN_PREFS))
    container = next((c.get('value') for c in main if c.get('name') == 'wehavephones_container_id'), '1')
    return APPDATA % container


def missing_ready(text, ready):
    """The ready lines not yet in text, in order: each must follow the one before it."""
    at = 0
    for index, line in enumerate(ready):
        found = text.find(line, at)
        if found < 0:
            return list(ready[index:])
        at = found + len(line)
    return []


class AppLog:
    """What the manager logged since mark(), for a single log or a folder of logs per start."""

    def __init__(self, phone, scenario):
        self.phone, self.scenario = phone, scenario
        self.base = '%s/%s' % (appdata(phone), scenario.path)
        self.size, self.dirs = 0, set()

    def mark(self):
        if self.scenario.log == 'file':
            out = self.phone.private('wc -c < %s 2>/dev/null' % shlex.quote(self.base)).strip()
            self.size = int(out) if out.isdigit() else 0
        else:
            self.dirs = set(self.phone.private('ls %s 2>/dev/null' % shlex.quote(self.base)).split())

    def text(self):
        if self.scenario.log == 'file':
            path = shlex.quote(self.base)
            # Byte offsets, since the log is UTF-8. electron-log starts a new file at 1 MB: then all of it is new.
            return self.phone.private('size=$(wc -c < %s); if [ "$size" -ge %d ]; then tail -c +%d %s; else cat %s; fi'
                                      % (path, self.size, self.size + 1, path, path), 90)
        new = sorted(set(self.phone.private('ls %s 2>/dev/null' % shlex.quote(self.base)).split()) - self.dirs)
        return ''.join(self.phone.private('cat %s/full-*.log 2>/dev/null' % shlex.quote(self.base + '/' + name), 90)
                       for name in new)


def last_exit(phone):
    """(timestamp, reason) of the app's newest exit record, or None."""
    lines = phone.sh('dumpsys activity exit-info %s' % PKG, 60).splitlines()
    for index, line in enumerate(lines):
        stamp = re.search(r'timestamp=(\S+ \S+)', line)
        if stamp:
            reason = next((re.search(r'reason=\d+ \(([A-Z_]+)\)', later).group(1) for later in lines[index:index + 3]
                           if re.search(r'reason=\d+ \(([A-Z_]+)\)', later)), 'unknown')
            return stamp.group(1), reason
    return None


def tap_view(phone, view_id, seconds=60):
    deadline = time.time() + seconds
    while time.time() < deadline:
        nodes = phone.ui_nodes()
        ok = next((node for node in nodes if node.get('text') == 'OK'), None)
        if ok is not None and any('App Compatibility' in node.get('text', '') for node in nodes):
            phone.tap(*center(ok))
            time.sleep(1)
            continue
        node = next((node for node in nodes if node.get('resource-id', '').endswith(':id/' + view_id)), None)
        if node is not None:
            phone.tap(*center(node))
            return
        time.sleep(2)
    raise Failure('%s is not on screen' % view_id)


def start_app(phone, scenario):
    """Start the manager from its card; returns the card's button label."""
    phone.sh('am start -W -n %s' % ACTIVITY, 90)
    tap_view(phone, 'RailAddons')
    tap_view(phone, 'RailAddonsApps')
    deadline = time.time() + 60
    while time.time() < deadline:
        nodes = phone.ui_nodes()
        name = next((node for node in nodes if node.get('resource-id', '').endswith(':id/TVAppName')
                     and node.get('text') == scenario.title), None)
        buttons = [node for node in nodes if node.get('resource-id', '').endswith(':id/BTAppOpen')]
        if name is not None and buttons:
            row_y = center(name)[1]
            button = min(buttons, key=lambda node: abs(center(node)[1] - row_y))
            if button.get('enabled') != 'false':
                phone.tap(*center(button))
                return button.get('text', '')
        time.sleep(2)
    raise Failure('the %s card is not on the Addon apps screen' % scenario.title)


def run_app(phone, scenario, evidence):
    evidence.mkdir(parents=True, exist_ok=True)
    started = time.time()
    check_ready(phone)
    say('== %s: %s' % (scenario.name, scenario.title))
    dev.stop_runtime(phone.serial)
    time.sleep(2)
    log = AppLog(phone, scenario)
    log.mark()
    exit_before = last_exit(phone)
    crashes_before = crash_lines(phone)
    text = ''
    try:
        label = start_app(phone, scenario)
        say('pressed %s' % label)
        # A download or update comes first; give it time on a slow connection.
        seconds = scenario.boot + (0 if label == 'Open' else 900)
        loaded = None
        while True:
            time.sleep(5)
            phone.dismiss_idle_lock()
            exited = last_exit(phone)
            if loaded is None:
                text = log.text()
                missing = missing_ready(text, scenario.ready)
                if not missing:
                    loaded = time.time()
                    say('%s loaded after %d s%s' % (scenario.title, loaded - started,
                                                     '; checking it stays open %d s' % scenario.hold if scenario.hold else ''))
                    if not scenario.hold:
                        break
                    continue
                if exited and exited != exit_before:
                    raise Failure('Android closed the app (%s at %s) before %s logged "%s"'
                                  % (exited[1], exited[0], scenario.title, missing[0]))
                if time.time() - started > seconds:
                    raise Failure('%s did not log "%s" in %d s (see app.log)' % (scenario.title, missing[0], seconds))
                continue
            if exited and exited != exit_before:
                raise Failure('Android closed the app (%s at %s) %d s after %s loaded'
                              % (exited[1], exited[0], time.time() - loaded, scenario.title))
            if not phone.session_open():
                raise Failure('the session closed %d s after %s loaded' % (time.time() - loaded, scenario.title))
            if time.time() - loaded >= scenario.hold:
                break
        phone.screenshot(evidence / 'app.png')
        result, detail = 'pass', 'loaded in %d s%s (%s)' % (loaded - started, ' and stayed open' if scenario.hold else '', label)
    except Failure as error:
        phone.screenshot(evidence / 'failure.png')
        result, detail = 'fail', str(error)
    finally:
        (evidence / 'app.log').write_text(log.text() or text)
        crashes = crash_lines(phone)
        new = crashes[len(crashes_before):] if crashes[:len(crashes_before)] == crashes_before else crashes
        if new:
            (evidence / 'crash.txt').write_text('\n'.join(new) + '\n')
        (evidence / 'logcat.txt').write_text(phone.sh('logcat -d -t 3000', 90))
        dev.stop_runtime(phone.serial)
    if result == 'pass' and new and any(PKG in line for line in new):
        result, detail = 'fail', 'loaded but the app crashed (crash.txt)'
    return result, detail, time.time() - started


def selftest():
    """Host checks of the screen readers and the settings round trip, on synthetic data."""
    from PIL import Image, ImageDraw

    def strip(scale, flags):
        image = Image.new('RGB', (2340, 1080), (40, 70, 110))
        draw = ImageDraw.Draw(image)
        size, gap = 26 * scale, 4 * scale
        x, y = 1000, 200
        colors = [(255, 0, 255)] + [{'on': (0, 255, 0), 'off': (0, 0, 0), 'pressed': (255, 255, 0)}[f] for f in flags]
        for color in colors + [(0, 255, 255)]:
            draw.rectangle([x, y, x + size - 1, y + size - 1], fill=color)
            x += size + gap
        return image

    for scale in (1, 1.4, 2):
        flags = ['on', 'pressed', 'off', 'on']
        read = read_marker(strip(scale, flags))
        assert read == dict(zip(('inWorld', 'jumped', 'moved', 'turned'), flags)), (scale, read)
    assert read_marker(Image.new('RGB', (2340, 1080), (90, 10, 15))) is None

    charselect = Image.new('RGB', (2340, 1080), (30, 60, 90))
    ImageDraw.Draw(charselect).rectangle([1040, 975, 1300, 1010], fill=(80, 13, 18))
    button = enter_world_button(charselect)
    assert button and abs(button[0] - 1170) < 30 and 975 <= button[1] <= 1010, button
    footer = Image.new('RGB', (2340, 1080), (30, 60, 90))
    ImageDraw.Draw(footer).rectangle([800, 1000, 1560, 1052], fill=(24, 20, 21))
    assert enter_world_button(footer), 'gamepad footer'
    overlay = Image.new('RGB', (2340, 1080), (190, 210, 168))
    ImageDraw.Draw(overlay).rectangle([800, 1000, 1560, 1052], fill=(24, 20, 21))
    assert enter_world_button(overlay) is None, 'launch overlay'
    progress = Image.new('RGB', (2340, 1080), (30, 60, 90))
    ImageDraw.Draw(progress).rectangle([800, 1000, 1560, 1052], fill=(18, 55, 71))
    assert enter_world_button(progress) is None, 'blue-black bar'
    world = Image.new('RGB', (2340, 1080), (30, 60, 90))
    ImageDraw.Draw(world).rectangle([1150, 975, 1190, 1010], fill=(200, 20, 20))
    assert enter_world_button(world) is None

    original = parse_prefs("""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="wehavephones_mobile_touch" value="true" />
    <string name="wehavephones_game_screen_size_2340x1080">1280x720</string>
    <string name="wehavephones_product_dir">_forever_</string>
    <int name="wehavephones_container_id" value="1" />
</map>""")
    saved = entries(original, TOUCHED_MAIN)
    changed = parse_prefs(prefs_text(original))
    apply_entries(changed, DEFAULT_MAIN, {})
    set_value(changed, 'string', 'wehavephones_product_dir', '_classic_era_')
    set_value(changed, 'boolean', 'wehavephones_performance_overlay', 'false')
    names = {child.get('name') for child in changed}
    assert names == {'wehavephones_product_dir', 'wehavephones_container_id', 'wehavephones_performance_overlay'}, names
    apply_entries(changed, TOUCHED_MAIN, saved)
    assert entries(changed, TOUCHED_MAIN) == saved
    assert interface_version('12.1.5.70783') == 120105 and interface_version('1.60.1.70862') == 16001

    wowup = BY_NAME['wowup'].ready
    assert missing_ready('[i] Creating tray\n', wowup) == list(wowup), 'hung at the tray'
    assert missing_ready('Tray created true\nChecking for addon updates...\n', wowup) == [wowup[2]], 'scanning'
    assert missing_ready('Tray created true\nChecking for addon updates...\n'
                         '[debug] [scan] wowup scan of 15 folders read 742 files in 22603ms\n', wowup) == []
    assert missing_ready('Checking for addon updates...\nTray created true\n', wowup) == list(wowup[1:]), 'order'
    curseforge = BY_NAME['curseforge'].ready
    agent_timeout = '[error] [PluginProcessHost] Timed out starting the Mods Agent process.\n'
    assert missing_ready(agent_timeout * 3, curseforge) == list(curseforge), 'agent never answered'
    assert missing_ready('Connected to Mods Agent on port 59911.\n'
                         'Scan complete for game 1 - Classic - 395d9c8f\n', curseforge) == []
    assert missing_ready('Connected to Mods Agent on port 59911.\nScan complete for game 432 - x\n',
                         curseforge) == [curseforge[1]], 'other game'
    assert expand(['apps']) == APP_SCENARIOS and all(app in expand(['default']) for app in APP_SCENARIOS)
    print('[e2e] selftest passed')
    return 0


def expand(names):
    picked = []
    for name in names or ['default']:
        for item in GROUPS.get(name, [name]):
            if item not in BY_NAME:
                raise SystemExit('unknown scenario or group: %s (see `list`)' % item)
            if item not in picked:
                picked.append(item)
    return [BY_NAME[name] for name in picked]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('list')
    run = sub.add_parser('run')
    run.add_argument('names', nargs='*', help='scenarios or groups (default: versions + touch)')
    run.add_argument('--serial')
    run.add_argument('--stop-on-failure', action='store_true')
    restore = sub.add_parser('restore')
    restore.add_argument('--serial')
    sub.add_parser('selftest')
    args = parser.parse_args()

    if args.command == 'selftest':
        return selftest()

    if args.command == 'list':
        for scenario in SCENARIOS:
            print('%-20s %s' % (scenario.name, scenario.describe()))
        for name, members in GROUPS.items():
            print('group %-14s %s' % (name, ' '.join(members)))
        return 0

    phone = Phone(dev.require(dev.resolve_serial(args.serial, '--serial')))
    if args.command == 'restore':
        if not BACKUP.is_file():
            say('nothing to restore')
            return 0
        restore_settings(phone, json.loads(BACKUP.read_text()))
        for game in PRODUCT_CODES:
            remove_addon(phone, game)
        return 0

    scenarios = expand(args.names)
    if phone.locked():
        say('the phone is locked; ask the user to unlock it')
        return 2
    run_dir = OUT / datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    say('phone %s, evidence in %s' % (phone.serial, run_dir.relative_to(ROOT)))
    dev.protect(phone.serial)
    try:
        backup = save_settings(phone)
    except Failure as error:
        say(str(error))
        return 2
    results = []
    try:
        for scenario in scenarios:
            run = run_app if isinstance(scenario, AppScenario) else run_scenario
            try:
                result, detail, seconds = run(phone, scenario, run_dir / scenario.name)
            except Failure as error:
                result, detail, seconds = 'fail', str(error), 0
            say('%s %s: %s' % (result.upper(), scenario.name, detail))
            results.append({'scenario': scenario.name, 'result': result, 'detail': detail, 'seconds': round(seconds)})
            if result == 'fail' and args.stop_on_failure:
                break
    finally:
        restore_settings(phone, backup)
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / 'summary.json').write_text(json.dumps({'serial': phone.serial, 'results': results}, indent=2))
    print()
    for item in results:
        print('%-5s %-20s %4ss  %s' % (item['result'].upper(), item['scenario'], item['seconds'], item['detail']))
    return 1 if any(item['result'] == 'fail' for item in results) else 0


if __name__ == '__main__':
    sys.exit(main())
