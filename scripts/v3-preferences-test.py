"""Local preference, restart and ripple checks; preserves account authorization."""
from device_driver import Device, ROOT, PROBE
import json, re, time, sys, xml.etree.ElementTree as ET
sys.path.insert(0,str(ROOT/'.tools/python-libs'))
from PIL import Image, ImageChops, ImageStat
from io import BytesIO

d=Device(); results=[]
def check(name,value):
    results.append({'check':name,'passed':bool(value)})
    (ROOT/'artifacts/device-tests/v3-preferences-results.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    print(name,bool(value),flush=True)
    if not value: raise AssertionError(name)
def pref():
    r=ET.fromstring(d.adb('shell','run-as','app.biliflow','cat','shared_prefs/biliflow.xml'))
    return {n.get('name'): n.get('value',n.text) for n in r}
def mark():
    r=ET.fromstring(d.adb('shell','run-as','app.biliflow','cat','shared_prefs/playback_progress.xml'))
    return max(json.loads(next(n.text for n in r if n.get('name')=='marks')),key=lambda x:x['updated'])
def settings(): d.tap('外观设置'); d.wait('首选清晰度',contains=True)
def choose(label):
    d.tap('首选清晰度',contains=True)
    for _ in range(6):
        if d.find(label): break
        if label == '自动': d.swipe(200,600,200,1000,250)
        else: d.swipe(200,1000,200,600,250)
    d.tap(label)
def open_card():
    cards=[n for n in d.tree().iter('node') if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(cards[0]); d.wait('正在观看'); time.sleep(6)

d.start(); time.sleep(1)
if d.find('首选清晰度',contains=True): d.adb('shell','input','tap','360','200'); time.sleep(.5)
if d.find('正在观看'): d.back()
d.wait('视频'); d.tap('视频'); time.sleep(1)
settings(); choose('480P'); check('quality preference saved', pref().get('preferred_quality')=='32')
d.adb('shell','input','tap','360','200'); time.sleep(.5)
open_card()
b=next(n for n in d.tree().iter('node') if n.get('resource-id')=='video_player')
r=list(map(int,re.findall(r'-?\d+',b.get('bounds'))))
d.adb('shell','input','tap','25',str((r[1]+r[3])//2))
check('new video uses preferred 480P',bool(d.find('480P')))
time.sleep(6); d.back(); saved=mark()
d.adb('shell','am','force-stop','app.biliflow'); d.start(); d.wait('视频')
check('preference persists process restart',pref().get('preferred_quality')=='32')
d.tap('我的'); d.tap('观看历史'); open_card()
current=mark()
check('progress survives process restart',current['video']==saved['video'] and current['position']>=saved['position']-1500)
d.back(); d.back(); d.tap('视频')
settings(); choose('自动'); check('restored automatic preference',pref().get('preferred_quality')=='0')
d.adb('shell','input','tap','360','200'); time.sleep(.5)
d.tap('视频'); time.sleep(2)
item=d.find('视频')[-1]; b=list(map(int,re.findall(r'-?\d+',item.get('bounds'))))
before=Image.open(BytesIO(d.adb('exec-out','screencap','-p',binary=True))).convert('RGB')
d.probe('press',x=(b[0]+b[2])//2,y=(b[1]+b[3])//2,hold=180)
held=Image.open(BytesIO(d.adb('exec-out','cat',PROBE+'press-hold.png',binary=True))).convert('RGB')
region=(b[0]-10,b[1]-15,b[2]+10,b[3]+10)
difference=ImageStat.Stat(ImageChops.difference(before.crop(region),held.crop(region))).mean
check('selected tab press has no ripple',max(difference)<.5)
print('Passed',len(results),'checks',flush=True)
