"""Fast, read-only accessibility snapshots through the separate device-probe APK."""
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
PROBE = '/sdcard/Android/data/app.biliflow.probe/files/'

class Device:
    def __init__(self, serial='emulator-5554'):
        self.serial = serial
    def adb(self, *args, binary=False, timeout=60):
        result = subprocess.run([str(ADB), '-s', self.serial, *args], capture_output=True, check=True, timeout=timeout)
        return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')
    def probe(self, op='dump', **params):
        args = ['shell', 'am', 'instrument', '-w', '-e', 'op', op]
        for key,value in params.items(): args += ['-e', key, str(value)]
        result = self.adb(*args, 'app.biliflow.probe/.DeviceProbe')
        if 'error=' in result: raise RuntimeError(result)
        return result
    def tree(self):
        for _ in range(8):
            try:
                self.probe()
                return ET.fromstring(self.adb('shell', 'cat', PROBE + 'window.xml'))
            except (ET.ParseError, RuntimeError): time.sleep(.3)
        raise RuntimeError('UI hierarchy unavailable')
    def find(self, label, contains=False):
        return [n for n in self.tree().iter('node') if any((label in n.get(k,'')) if contains else (label in n.get(k,'').splitlines()) for k in ['text','content-desc'])]
    def tap_node(self, node):
        bounds = list(map(int, re.findall(r'-?\d+',node.get('bounds'))))
        self.adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))
    def tap(self, label, contains=False):
        nodes = self.find(label, contains)
        if not nodes: raise AssertionError('Missing UI control: '+label)
        self.tap_node(nodes[-1]); time.sleep(.3)
    def wait(self, label, timeout=25, contains=False):
        deadline = time.monotonic()+timeout
        while time.monotonic()<deadline:
            try: nodes = self.find(label, contains)
            except RuntimeError:
                time.sleep(.4); continue
            if nodes: return nodes
            time.sleep(.4)
        raise AssertionError('UI control did not appear: '+label)
    def shot(self, name):
        folder = ROOT / '.tools/v3-device-shots'; folder.mkdir(parents=True, exist_ok=True)
        (folder/(name+'.png')).write_bytes(self.adb('exec-out','screencap','-p',binary=True))
    def start(self): self.adb('shell','am','start','-n','app.biliflow/.MainActivity')
    def back(self): self.adb('shell','input','keyevent','4'); time.sleep(.4)
    def swipe(self, x1,y1,x2,y2,ms=400): self.adb('shell','input','swipe',str(x1),str(y1),str(x2),str(y2),str(ms)); time.sleep(.4)
