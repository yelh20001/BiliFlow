"""Read-only pagination, quality and PiP regressions after the v0.4 changes."""
from device_driver import Device,ROOT
import json,re,time,xml.etree.ElementTree as ET

d=Device();path=ROOT/'artifacts/device-tests/v4-regression-results.json';results=[]
def check(name,value):
    results.append({'check':name,'passed':bool(value)})
    path.write_text(json.dumps(results,indent=2),encoding='utf-8')
    print(name,bool(value),flush=True)
    if not value:raise AssertionError(name)
def nodes():return list(d.tree().iter('node'))
def tag(prefix):return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def comments():
    n=tag('comments_feed_page_')[0]
    return tuple(map(int,re.findall(r'\d+',n.get('resource-id'))))
def bounds():return list(map(int,re.findall(r'-?\d+',tag('video_player')[0].get('bounds'))))
def audio():
    pid=d.adb('shell','pidof','app.biliflow').strip()
    return any(f'/{pid} state:started' in s for s in d.adb('shell','dumpsys','audio').splitlines())
def show():
    if not d.find('全屏'):
        b=bounds();d.adb('shell','input','tap','25',str((b[1]+b[3])//2))
def mark():
    root=ET.fromstring(d.adb('shell','run-as','app.biliflow','cat','shared_prefs/playback_progress.xml'))
    return max(json.loads(next(n.text for n in root if n.get('name')=='marks')),key=lambda x:x['updated'])['position']

d.start();time.sleep(1)
for _ in range(4):
    if d.find('视频'):break
    d.back()
d.tap('视频');d.tap('热门');time.sleep(4)
cards=[n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
d.tap_node(cards[0]);d.wait('正在观看');time.sleep(6)
before=comments()[2]
d.swipe(350,790,350,1390,650);time.sleep(2)
check('pull down refreshes comments in detail',comments()[2]>before)
check('comment refresh preserves video playback',audio())
for _ in range(30):
    if comments()[0]>1:break
    d.swipe(350,1390,350,780,200)
page,count,_=comments();check('direct comments automatically load next page',page>1 and count>20)
d.back();d.tap_node(cards[0]);d.wait('正在观看');time.sleep(5)
show();d.tap('暂停');time.sleep(.5);at=mark()
show()
quality=[n for n in nodes() if n.get('clickable')=='true' and any(c.get('text')=='自动' or re.fullmatch(r'\d+P.*',c.get('text','')) for c in n.iter('node'))]
d.tap_node(quality[-1]);d.wait('清晰度')
for _ in range(5):
    if d.find('480P',contains=True):break
    d.swipe(350,1370,350,880,300)
d.tap('480P',contains=True);time.sleep(3)
check('quality switch retains paused state',not audio())
check('quality switch retains position',abs(mark()-at)<1500)
show();d.tap('播放');time.sleep(2)
d.adb('shell','input','keyevent','3');time.sleep(2)
check('VOD native PiP survives layout changes',bool(re.search(r'windowingMode=pinned|mode=pinned|mWindowingMode=2',d.adb('shell','dumpsys','activity','activities'),re.I)))
check('VOD PiP continues audio',audio());d.start();d.wait('正在观看');d.back()
check('VOD exit releases player after PiP',not audio())
check('no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
print('Passed',len(results),'checks',flush=True)
