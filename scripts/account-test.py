"""Run only after the owner has confirmed app authorization in the dedicated emulator."""
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
checks = []

def adb(*args):
    return subprocess.check_output([str(ADB), '-s', 'emulator-5554', *args], timeout=60).decode('utf-8', errors='replace')

def tree():
    for _ in range(4):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/biliflow-account.xml')
            return ET.fromstring(adb('shell', 'cat', '/sdcard/biliflow-account.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(1)
    raise AssertionError('Account screen hierarchy did not become ready')

def find(label):
    return [n for n in tree().iter('node') if any(label in n.get(key, '').splitlines() for key in ['text', 'content-desc'])]

def tap(label):
    found = find(label)
    assert found, 'Missing control: ' + label
    bounds = list(map(int, re.findall(r'\d+', found[-1].get('bounds'))))
    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
    time.sleep(1)

def check(name, value):
    checks.append({'name': name, 'passed': bool(value)})
    assert value, name

def wait_for_account_feed():
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        nodes = list(tree().iter('node'))
        connected = any('的安卓 App 推荐' in n.get('text', '') for n in nodes)
        videos = any('次观看' in n.get('text', '') for n in nodes)
        if connected and videos:
            return True
        time.sleep(2)
    return False

try:
    time.sleep(3)
    tap('我的')
    check('Owner-confirmed application authorization is active', bool(find('App 授权已连接 · 首页使用账号推荐')))
    for name in ['app_authorization', 'account_session']:
        raw = adb('shell', 'run-as', 'app.biliflow', 'cat', f'shared_prefs/{name}.xml')
        root = ET.fromstring(raw)
        check(name + ' is stored encrypted', all(n.get('name') == 'sealed' for n in root) and bool(root.findall('string')) and 'access_token' not in raw and 'SESSDATA' not in raw)
    tap('视频')
    check('Authenticated Android app recommendations render', wait_for_account_feed())
    adb('shell', 'am', 'force-stop', 'app.biliflow')
    adb('shell', 'am', 'start', '-n', 'app.biliflow/.MainActivity')
    time.sleep(4)
    check('Account and recommendations survive process restart', wait_for_account_feed())
    tap('我的')
    check('App account identity is revalidated after restart', bool(find('App 授权已连接 · 首页使用账号推荐')))
finally:
    destination = ROOT / 'artifacts/device-tests/account-results.json'
    destination.write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(checks, ensure_ascii=True, indent=2))
