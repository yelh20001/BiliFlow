"""Additional real touch gesture checks against the test emulator."""
from device_driver import Device,ROOT
import concurrent.futures,json,re,time,xml.etree.ElementTree as ET
d=Device(); results=[]
def check(name,value):
    results.append({'check':name,'passed':bool(value)})
    (ROOT/'artifacts/device-tests/v3-gestures-results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    print(name,bool(value),flush=True)
    if not value: raise AssertionError(name)
def bounds():
    n=next(n for n in d.tree().iter('node') if n.get('resource-id')=='video_player')
    return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def position():
    root=ET.fromstring(d.adb('shell','run-as','app.biliflow','cat','shared_prefs/playback_progress.xml'))
    return max(json.loads(next(n.text for n in root if n.get('name')=='marks')),key=lambda x:x['updated'])['position']
d.start();d.wait('视频')
cards=[n for n in d.tree().iter('node') if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
d.tap_node(cards[0]);d.wait('正在观看');time.sleep(6)
b=bounds(); y=(b[1]+b[3])//2
d.swipe(350,b[3]-45,350,b[1]+30,450);time.sleep(1)
check('upward player gesture enters fullscreen',bounds()[2]>1000)
d.back();time.sleep(1); b=bounds();y=(b[1]+b[3])//2
time.sleep(5);before=position();d.swipe(170,y,500,y,600);time.sleep(.7)
check('horizontal drag seeks forward',position()-before>25000)
with concurrent.futures.ThreadPoolExecutor() as pool:
    held=pool.submit(d.probe,'press',x=350,y=y,hold=2500)
    time.sleep(1.2)
    state=d.adb('shell','dumpsys','media_session')
    check('held player gesture sets double speed',bool(re.search(r'speed=2(?:\.0)?\b',state)))
    held.result()
time.sleep(.3)
check('release restores normal speed',bool(re.search(r'speed=1(?:\.0)?\b',d.adb('shell','dumpsys','media_session'))))
d.back()
print('Passed',len(results),'checks',flush=True)
