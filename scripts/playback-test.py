"""Playback and local library checks on the dedicated BiliFlow emulator."""
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import sys
from io import BytesIO

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / '.tools/python-libs'))
from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
OUT = ROOT / 'artifacts/device-tests'
OUT.mkdir(parents=True, exist_ok=True)
results = []

def adb(*args, binary=False):
    result = subprocess.run([str(ADB), '-s', 'emulator-5554', *args], capture_output=True, timeout=60, check=True)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/biliflow-playback.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/biliflow-playback.xml'))

def nodes(label):
    return [n for n in tree().iter('node') if any(label in n.get(k, '').splitlines() for k in ('text', 'content-desc'))]

def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(1)

def tap(label):
    found = nodes(label)
    assert found, 'Missing control: ' + label
    tap_node(found[-1])

def check(name, passed):
    results.append({'name': name, 'passed': bool(passed)})
    assert passed, name

def screenshot(name):
    (OUT / (name+'.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    (OUT / (name+'.xml')).write_text(ET.tostring(tree(), encoding='unicode'), encoding='utf-8')

def preferences():
    xml = ET.fromstring(adb('shell', 'run-as', 'app.biliflow', 'cat', 'shared_prefs/biliflow.xml'))
    return {n.get('name'): n.text or '' for n in xml}

def video_frame(bounds):
    image = Image.open(BytesIO(adb('exec-out', 'screencap', '-p', binary=True))).convert('RGB')
    return image.crop(bounds)

def frame_delta(a, b):
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3

def audio_state(pid, state):
    dump = adb('shell', 'dumpsys', 'audio')
    return any('AudioPlaybackConfiguration' in line and f'/{pid} state:{state}' in line for line in dump.splitlines())

def position():
    # Read visible controls first; touching the centre could toggle pause and invalidate this test.
    for attempt in range(2):
        if attempt:
            adb('shell', 'input', 'tap', '80', '350')
        for n in tree().iter('node'):
            if n.get('resource-id', '').endswith('/exo_position'):
                value = n.get('text', '')
                parts = value.split(':')
                if all(p.isdigit() for p in parts):
                    return sum(int(p)*60**i for i,p in enumerate(reversed(parts)))
    return None

try:
    adb('shell', 'am', 'force-stop', 'app.biliflow')
    adb('shell', 'am', 'start', '-n', 'app.biliflow/.MainActivity')
    time.sleep(5)
    cards = [n for n in tree().iter('node') if n.get('clickable') == 'true' and any('次观看' in c.get('text', '') for c in n.iter('node'))]
    check('Real video cards loaded', bool(cards))
    tap_node(cards[0])
    time.sleep(6)
    check('Native video detail opened', bool(nodes('正在观看')))
    player = next(n for n in tree().iter('node') if n.get('resource-id', '').endswith('/exo_content_frame'))
    bounds = tuple(map(int, re.findall(r'\d+', player.get('bounds'))))
    pid = adb('shell', 'pidof', 'app.biliflow').strip()
    first = video_frame(bounds)
    time.sleep(3)
    second = video_frame(bounds)
    check('Decoded video frames advance', frame_delta(first, second) > .5)
    check('Player audio is started', audio_state(pid, 'started'))
    screenshot('07-native-playback')
    saved_before = preferences()
    history = json.loads(saved_before.get('history', '[]'))
    check('Viewing recorded to local history', bool(history))
    video_id = history[0]['bvid']
    favorites_before = json.loads(saved_before.get('favorites', '[]'))
    already_saved = any(v['bvid'] == video_id for v in favorites_before)
    if not already_saved:
        tap('收藏')
    check('Favorite is saved', any(v['bvid'] == video_id for v in json.loads(preferences().get('favorites', '[]'))))
    adb('shell', 'input', 'keyevent', '3')
    time.sleep(2)
    check('Backgrounding pauses audio', audio_state(pid, 'paused'))
    adb('shell', 'am', 'start', '-n', 'app.biliflow/.MainActivity')
    time.sleep(2)
    time.sleep(3)
    paused1 = video_frame(bounds)
    time.sleep(3)
    paused2 = video_frame(bounds)
    check('Backgrounding keeps video paused on return', frame_delta(paused1, paused2) < .1 and audio_state(pid, 'paused'))
    screenshot('08-paused-after-background')
    adb('shell', 'input', 'keyevent', '4')
    time.sleep(2)
    log = adb('logcat', '-d', '--pid=' + pid, '-s', 'ExoPlayerImpl:I')
    (OUT / 'player-lifecycle.log').write_text(log, encoding='utf-8')
    check('Leaving detail releases player', 'Release ' in log)
    adb('shell', 'am', 'force-stop', 'app.biliflow')
    adb('shell', 'am', 'start', '-n', 'app.biliflow/.MainActivity')
    time.sleep(3)
    prefs = preferences()
    check('Favorite survives process restart', any(v['bvid'] == video_id for v in json.loads(prefs.get('favorites', '[]'))))
    check('History survives process restart', any(v['bvid'] == video_id for v in json.loads(prefs.get('history', '[]'))))
    tap('我的'); tap('我的收藏')
    check('Favorite appears in collection UI', any('次观看' in n.get('text', '') for n in tree().iter('node')))
    screenshot('09-local-favorite')
finally:
    (OUT / 'playback-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(results, ensure_ascii=True, indent=2))
