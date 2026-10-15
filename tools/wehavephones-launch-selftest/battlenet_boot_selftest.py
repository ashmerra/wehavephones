#!/usr/bin/env python3
"""Safety/configuration checks for the boot-only benchmark; no device needed."""
import importlib.util
import pathlib
from unittest.mock import patch
import datetime
import json

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
assert boot.app_pid(['12 1 com.wehavephones.dev com.wehavephones.dev',
                     '13 12 wine Battle.net.exe']) == '12'
assert boot.app_pid(['13 1 wine Battle.net.exe']) is None
exit_history = '''ApplicationExitInfo #0:
 timestamp=2026-10-14 23:49:03.609 pid=15977 realUid=10056
 process=com.wehavephones.dev reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN)
ApplicationExitInfo #1:
 timestamp=2026-10-14 23:41:26.307 pid=11617 realUid=10056
 process=com.wehavephones.dev reason=10 (USER REQUESTED) subreason=21 (FORCE STOP)
'''
assert boot.app_exit_reason(exit_history, '15977') == {
    'pid': 15977, 'reason_code': 3, 'reason': 'LOW_MEMORY'}
assert boot.app_exit_reason(exit_history, '11617')['reason'] == 'USER REQUESTED'
assert boot.app_exit_reason(exit_history, '99999') is None
with patch.object(boot, 'private', return_value=(
        'PID PPID STAT COMM ARGS\n'
        '10 1 Zs [winedevice.exe] [winedevice.exe]\n'
        '11 1 Sl [CrBrowserMain] Battle.net.exe --game=wow\n')):
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
assert boot.validate_geometry((2400, 1080), (410, 695, 300, 50))
assert not boot.validate_geometry((2400, 1080), (2300, 695, 300, 50))
assert not boot.validate_geometry((2400, 1080), (-1, 0, 300, 50))
assert not boot.validate_geometry((2400, 1080), (0, 0, 0, 50))
assert not boot.validate_geometry((0, 1080), (0, 0, 300, 50))
assert boot.ready_outcome('Play') == 'play-rendered'
assert boot.ready_outcome('Install') == 'install-rendered'
assert boot.region_box((330, 200, 480, 650)) == (330, 200, 810, 850)
frame = boot.Image.new('RGB', (2400, 1080), 'white')
safe = boot.redact_ready(frame, (330, 200, 480, 650))
assert safe.size == frame.size
assert safe.getpixel((330, 200)) == (255, 255, 255)
assert safe.getpixel((809, 849)) == (255, 255, 255)
assert safe.getpixel((810, 850)) == (0, 0, 0)
assert safe.getpixel((1600, 60)) == (0, 0, 0)
assert frame.getpixel((1600, 60)) == (255, 255, 255)
assert all(len(values) <= 1 for values in boot.CANDIDATES.values())
assert boot.CANDIDATES['no-smc'] == {'FEX_SMCCHECKS': 'none'}
assert boot.CANDIDATES['no-tso'] == {'FEX_TSOENABLED': '0'}
assert boot.CANDIDATES['no-vector-tso'] == {'FEX_VECTORTSOENABLED': '0'}
assert boot.CANDIDATES['no-memcpy-tso'] == {'FEX_MEMCPYSETTSOENABLED': '0'}
assert boot.CANDIDATES['reduced-x87'] == {'FEX_X87REDUCEDPRECISION': '1'}
assert boot.CANDIDATES['dynamic-l1'] == {'FEX_DYNAMICL1CACHE': '1'}
assert boot.CANDIDATES['no-l2'] == {'FEX_DISABLEL2CACHE': '1'}
configurations = ['baseline', 'small-blocks', 'reduced-x87']
assert boot.trial_configurations(configurations, 1, True) == configurations
assert boot.trial_configurations(configurations, 2, True) == configurations[::-1]
assert boot.trial_configurations(configurations, 2, False) == configurations
assert configurations == ['baseline', 'small-blocks', 'reduced-x87']
assert boot.battlenet_versions([
    '1 0 wine Battle.net.exe --user-agent-product=Battle.net/2.53.5.17932 (retail)',
    '2 0 wine Battle.net.exe --user-agent-product=Battle.net/2.53.5.17932 (retail)',
    '3 0 wine Battle.net.exe --game=wow',
]) == {'2.53.5.17932'}
assert boot.battlenet_versions(['1 0 wine Battle.net.exe']) == set()
epoch = datetime.datetime(2026, 10, 14, 6, 18, 14, tzinfo=datetime.timezone.utc).timestamp()
assert boot.fresh_log('battle.net-20261014T061819.123456.log', epoch)
assert not boot.fresh_log('battle.net-20261014T061500.123456.log', epoch)
assert not boot.fresh_log('unrelated.log', epoch)
with patch.object(boot, 'private', side_effect=[
        'battle.net-20261014T061819.123456.log\n',
        'I 2026-10-14 06:18:19.000000 [Main] Opening IPC shared memory. mode=server\n'
        'E 2026-10-14 06:18:20.000000 [UnifiedAuth] UAuth: tassadar Login URL is empty!\n'
        'E 2026-10-14 06:18:21.000000 [UnifiedAuth] UAuth: browser error: errorCode=1015\n'
        'I 2026-10-14 06:18:22.000000 [Main] Logged into Battle.net successfully.\n']):
    state = boot.launcher_state('test-phone', 1, epoch)
    assert state['events'] == [
        {'event': 'auth-url-missing', 'after_log_start_s': 1.0},
        {'event': 'auth-browser-error', 'after_log_start_s': 2.0, 'code': 1015},
        {'event': 'signed-in', 'after_log_start_s': 3.0},
    ]
    assert state['client_error_codes'] == []
with patch.object(boot, 'private', side_effect=[
        'battle.net-20261014T061819.123456.log\n',
        'I 2026-10-14 06:18:19.000000 [Main] Opening IPC shared memory. mode=server\n'
        'E 2026-10-14 06:18:20.000000 [Auth] Login failed for private@example.invalid token=private-fixture\n'
        'E 2026-10-14 06:18:21.000000 [Auth] BLZBNTBGS80000023 private@example.invalid\n'
        'E 2026-10-14 06:18:22.000000 [Auth] BLZBNTBGS80000023\n']):
    state = boot.launcher_state('test-phone', 1, epoch)
    assert state['events'] == [{'event': 'auth-login-failed', 'after_log_start_s': 1.0}]
    assert state['client_error_codes'] == ['BLZBNTBGS80000023']
    assert 'private@example.invalid' not in json.dumps(state)
    assert 'private-fixture' not in json.dumps(state)
with patch.object(boot, 'shell', side_effect=['', 'KeyguardStateMonitor\n mIsShowing=true']):
    try:
        boot.ensure_unlocked('test-phone')
        raise AssertionError('Secure keyguard must stop the benchmark')
    except RuntimeError as error:
        assert 'keyguard' in str(error)
with patch.object(boot, 'shell', side_effect=['', 'KeyguardServiceDelegate\n showing=true']):
    try:
        boot.ensure_unlocked('test-phone')
        raise AssertionError('Android 16 keyguard must stop the benchmark')
    except RuntimeError as error:
        assert 'keyguard' in str(error)
assert boot.touch_protection_visible('  Window #1 IgniteTouchProtectionPresenter\n isVisible=true\n')
assert not boot.touch_protection_visible('  Window #1 IgniteTouchProtectionPresenter\n isVisible=false\n'
                                         '  Window #2 XServerDisplayActivity\n isVisible=true\n')
boot.OWN_SESSION = True
with patch.object(boot.dev, 'stop_runtime') as stop, patch.object(boot, 'processes',
        side_effect=[['1 0 wineserver wineserver'], []]), patch.object(boot.time, 'sleep') as sleep:
    boot.stop_owned_session('test-phone')
    stop.assert_called_once_with('test-phone')
    sleep.assert_called_once_with(.25)
    assert not boot.OWN_SESSION
boot.OWN_SESSION = True
boot.PRESERVE_FAILED_SESSION = True
with patch.object(boot, 'stop_owned_session') as stop:
    boot.cleanup_owned_session('test-phone')
    stop.assert_not_called()
boot.PRESERVE_FAILED_SESSION = False
with patch.object(boot, 'stop_owned_session') as stop, patch.object(boot, 'processes',
        return_value=['1 0 Wow-ARM64.exe Wow-ARM64.exe']):
    boot.cleanup_owned_session('test-phone')
    stop.assert_not_called()
with patch.object(boot, 'stop_owned_session') as stop, patch.object(boot, 'processes', return_value=[]):
    boot.cleanup_owned_session('test-phone')
    stop.assert_called_once_with('test-phone')
boot.OWN_SESSION = False
print('BattleNetBootSelfTest PASS: reversible overrides, live-session guards, detector, single-variable trials')
