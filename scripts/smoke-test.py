"""Real-device UI smoke checks. Requires the dedicated emulator and installed debug APK."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
args = parser.parse_args()
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
OUT = ROOT / 'artifacts/device-tests'
OUT.mkdir(parents=True, exist_ok=True)
results = []

def adb(*parts, binary=False):
    result = subprocess.run([str(ADB), '-s', args.serial, *parts], capture_output=True, timeout=60)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

def tree():
    for _ in range(4):
        adb('shell', 'uiautomator', 'dump', '/sdcard/biliflow-window.xml')
        try:
            return ET.fromstring(adb('shell', 'cat', '/sdcard/biliflow-window.xml'))
        except ET.ParseError:
            time.sleep(1)
    raise AssertionError('Could not read UI hierarchy')

def find(label):
    return [n for n in tree().iter('node') if any(label in n.get(key, '').splitlines() for key in ('text', 'content-desc'))]

def tap(label):
    nodes = find(label)
    assert nodes, f'Missing control: {label}'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', nodes[-1].get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1.2)

def check(name, condition):
    results.append({'name': name, 'passed': bool(condition)})
    assert condition, name

def screenshot(name):
    (OUT / f'{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    (OUT / f'{name}.xml').write_text(ET.tostring(tree(), encoding='unicode'), encoding='utf-8')

try:
    adb('shell', 'am', 'force-stop', 'app.biliflow')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    adb('shell', 'am', 'start', '-n', 'app.biliflow/.MainActivity')
    time.sleep(6)
    check('Four required navigation labels', all(find(x) for x in ['直播', '视频', '动态', '我的']))
    check('Video is initial destination', bool(find('为你推荐')))
    screenshot('01-video-system-light')
    tap('直播'); check('Live page', bool(find('正在发生的精彩'))); screenshot('02-live')
    tap('动态'); check('Dynamic official entry', bool(find('打开官方动态'))); screenshot('03-dynamic')
    tap('我的'); check('Profile page', bool(find('你好，探索者'))); screenshot('04-profile')
    tap('我的收藏'); check('Local collection page', bool(find('这里，等待你的第一份喜欢')))
    adb('shell', 'input', 'keyevent', '4'); time.sleep(1)
    tap('外观设置'); tap('纯白 · 清爽明亮')
    adb('shell', 'input', 'keyevent', '4'); time.sleep(1)
    adb('shell', 'cmd', 'uimode', 'night', 'yes'); time.sleep(2)
    screenshot('05-white-with-system-dark')
    prefs = adb('shell', 'run-as', 'app.biliflow', 'cat', 'shared_prefs/biliflow.xml')
    check('White theme persisted', '>white<' in prefs)
    adb('shell', 'am', 'force-stop', 'app.biliflow')
    adb('shell', 'am', 'start', '-n', 'app.biliflow/.MainActivity'); time.sleep(3)
    tap('外观设置'); check('White theme restored after restart', '>white<' in adb('shell', 'run-as', 'app.biliflow', 'cat', 'shared_prefs/biliflow.xml'))
    tap('跟随系统 · 原生取色'); adb('shell', 'input', 'keyevent', '4'); time.sleep(2)
    screenshot('06-system-dark')
    check('System theme persisted', '>system<' in adb('shell', 'run-as', 'app.biliflow', 'cat', 'shared_prefs/biliflow.xml'))
    tap('搜索视频'); check('Search field opens', bool(find('搜索视频、UP 主或 BV 号')))
    adb('shell', 'input', 'keyevent', '4'); time.sleep(1)
    check('Process still running', bool(adb('shell', 'pidof', 'app.biliflow').strip()))
finally:
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    (OUT / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(results, ensure_ascii=True, indent=2))
