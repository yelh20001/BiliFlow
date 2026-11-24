"""Checks real predictive gestures and the official login entry without entering credentials."""
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
OUT = ROOT / 'artifacts/device-tests'
OUT.mkdir(parents=True, exist_ok=True)
checks = []

def adb(*args, binary=False):
    result = subprocess.run([str(ADB), '-s', 'emulator-5554', *args], capture_output=True, check=True, timeout=60)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/biliflow-auth.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/biliflow-auth.xml'))

def find(label):
    return [n for n in tree().iter('node') if any(label in n.get(key, '').splitlines() for key in ['text', 'content-desc'])]

def tap(label):
    matches = find(label)
    assert matches, 'Control not found: ' + label
    x1,y1,x2,y2 = map(int,re.findall(r'\d+',matches[-1].get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(1)

def check(name, passed):
    checks.append({'name': name, 'passed': bool(passed)})
    assert passed, name

def screenshot(name, private=False):
    destination = ROOT / '.tools' if private else OUT
    (destination / (name+'.png')).write_bytes(adb('exec-out','screencap','-p',binary=True))

try:
    adb('shell','am','force-stop','app.biliflow')
    adb('shell','am','start','-n','app.biliflow/.MainActivity')
    time.sleep(4)
    tap('我的')
    tap('我的收藏')
    check('Library opens before gesture', bool(find('我的收藏')))
    adb('shell','input','motionevent','DOWN','1','750')
    for x in ['35','90','160','210']:
        adb('shell','input','motionevent','MOVE',x,'750')
    screenshot('10-predictive-back-progress')
    for x in ['140','50','3']:
        adb('shell','input','motionevent','MOVE',x,'750')
    adb('shell','input','motionevent','UP','3','750')
    time.sleep(1)
    check('Cancelled gesture retains library destination', bool(find('我的收藏')) and not find('你好，探索者'))
    screenshot('11-predictive-back-cancelled')
    adb('shell','input','swipe','1','750','500','750','420')
    time.sleep(1)
    check('Completed gesture returns to profile', bool(find('你好，探索者')))
    tap('登录哔哩哔哩')
    time.sleep(5)
    check('Official app authorization QR is displayed', bool(find('B 站登录二维码')))
    check('Official QR is waiting for phone confirmation', bool(find('使用哔哩哔哩客户端扫描二维码')))
    screenshot('login-qr-before-web', private=True)
    tap('使用官方网页登录')
    time.sleep(8)
    check('Official web login opens', bool(find('完成登录')))
    screenshot('12-official-web-login')
    check('Official web form rendered', any(n.get('class') == 'android.webkit.WebView' for n in tree().iter('node')))
    tap('关闭网页')
    time.sleep(1)
    check('Closing web login returns to native login', bool(find('刷新二维码')))
    tap('刷新二维码')
    time.sleep(4)
    screenshot('login-qr', private=True)
finally:
    (OUT/'auth-navigation-results.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(checks,ensure_ascii=True,indent=2))
