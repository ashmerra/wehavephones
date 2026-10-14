#!/usr/bin/env python3
"""Safety/configuration checks for the boot-only benchmark; no device needed."""
import importlib.util
import pathlib
from unittest.mock import patch
import datetime

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('boot', ROOT / 'scripts/benchmark-battlenet-boot.py')
boot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boot)

original = b'[Desktop Entry]\nExec=wine C:/bnet.bat\n\n[Extra Data]\nfexcorePreset=STABILITY\nenvVars=WINEESYNC=1 FEX_MAXINST=1000 OTHER=kept\n'
assert boot.set_override(original, {}) == original
changed = boot.set_override(original, {'FEX_MAXINST': '500'}).decode()
assert 'fexcorePreset=STABILITY' in changed
assert 'WINEESYNC=1' in changed and 'OTHER=kept' in changed
assert changed.count('FEX_MAXINST=') == 1 and 'FEX_MAXINST=500' in changed
assert '[Extra Data]\nenvVars=FEX_SMCCHECKS=full\n' in boot.set_override(
    b'[Desktop Entry]\nExec=wine C:/bnet.bat\n', {'FEX_SMCCHECKS': 'full'}).decode()
assert boot.game_running(['1 0 Wow-ARM64.exe Wow-ARM64.exe -uid wow'])
assert boot.game_running(['1 0 wine WowClassic.exe'])
assert not boot.game_running(['1 0 Battle.net.exe Battle.net.exe --game=wow'])
assert boot.runtime_running(['1 0 wineserver wineserver'])
assert boot.runtime_running(['1 0 CrBrowserMain Battle.net.exe'])
assert not boot.runtime_running(['1 0 com.wehavephones.dev com.wehavephones.dev'])
with patch.object(boot, 'private', return_value=(
        'PID PPID STAT COMM ARGS\n'
        '10 1 Zs [winedevice.exe] [winedevice.exe]\n'
        '11 1 Sl CrBrowserMain Battle.net.exe --game=wow\n')):
    live = boot.processes('test-phone')
    assert live == ['11 1 CrBrowserMain Battle.net.exe --game=wow']
    assert boot.runtime_running(live)
assert not boot.is_play((28, 30, 38))
assert boot.is_play((24, 125, 223))
assert not boot.is_play((5, 25, 45))  # Dimmed Play must not count as ready.
placeholder = boot.Image.new('RGB', (300, 50), (24, 125, 223))
assert boot.play_label_pixels(placeholder, (0, 0, 300, 50)) == 0
boot.ImageDraw.Draw(placeholder).rectangle((130, 20, 170, 30), fill='white')
assert boot.play_label_pixels(placeholder, (0, 0, 300, 50)) > 100
assert all(len(values) <= 1 for values in boot.CANDIDATES.values())
assert boot.CANDIDATES['no-smc'] == {'FEX_SMCCHECKS': 'none'}
assert boot.CANDIDATES['no-tso'] == {'FEX_TSOENABLED': '0'}
assert boot.CANDIDATES['no-vector-tso'] == {'FEX_VECTORTSOENABLED': '0'}
assert boot.CANDIDATES['no-memcpy-tso'] == {'FEX_MEMCPYSETTSOENABLED': '0'}
assert boot.CANDIDATES['reduced-x87'] == {'FEX_X87REDUCEDPRECISION': '1'}
configurations = ['baseline', 'small-blocks', 'reduced-x87']
assert boot.trial_configurations(configurations, 1, True) == configurations
assert boot.trial_configurations(configurations, 2, True) == configurations[::-1]
assert boot.trial_configurations(configurations, 2, False) == configurations
assert configurations == ['baseline', 'small-blocks', 'reduced-x87']
epoch = datetime.datetime(2026, 10, 14, 6, 18, 14, tzinfo=datetime.timezone.utc).timestamp()
assert boot.fresh_log('battle.net-20261014T061819.123456.log', epoch)
assert not boot.fresh_log('battle.net-20261014T061500.123456.log', epoch)
assert not boot.fresh_log('unrelated.log', epoch)
with patch.object(boot, 'shell', side_effect=['', 'KeyguardStateMonitor\n mIsShowing=true']):
    try:
        boot.ensure_unlocked('test-phone')
        raise AssertionError('Secure keyguard must stop the benchmark')
    except RuntimeError as error:
        assert 'keyguard' in str(error)
boot.OWN_SESSION = True
with patch.object(boot.dev, 'stop_runtime') as stop, patch.object(boot, 'processes',
        side_effect=[['1 0 wineserver wineserver'], []]), patch.object(boot.time, 'sleep') as sleep:
    boot.stop_owned_session('test-phone')
    stop.assert_called_once_with('test-phone')
    sleep.assert_called_once_with(.25)
    assert not boot.OWN_SESSION
print('BattleNetBootSelfTest PASS: reversible overrides, live-session guards, detector, single-variable trials')
