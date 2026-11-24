"""Compare actual native mini bounds for landscape and portrait video output."""
from device_driver import Device, ROOT
import json,re,time
d=Device();results=[]
def tags(prefix):return [n for n in d.tree().iter('node') if n.get('resource-id','').startswith(prefix)]
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def check(name,value):
    results.append(dict(check=name,passed=bool(value)));print(name,bool(value),flush=True)
    (ROOT/'artifacts/v20-mini-results.json').write_text(json.dumps(results,indent=2));assert value,name
try:
    d.adb('shell','am','force-stop','app.biliflow');d.adb('logcat','-c');d.start();d.wait('热门');d.tap('热门');time.sleep(2)
    cards=[]
    for _ in range(30):
        cards=[n for n in d.tree().iter('node') if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
        if cards:break
        time.sleep(.3)
    d.tap_node(cards[0]);d.wait('正在观看');time.sleep(1)
    sizes=[]
    for portrait in (False,True):
        d.adb('shell','am','broadcast','-n','app.biliflow/.MiniFixtureReceiver','--es','op','load','--ez','portrait',str(portrait).lower());time.sleep(1)
        p=rect(tags('video_player')[0]);x=(p[0]+p[2])//2
        d.swipe(x,p[1]+55,x,p[3]-20,600);d.wait('关闭小窗');time.sleep(.4)
        p=rect(tags('in_app_mini_player')[0]);sizes.append((p[2]-p[0],p[3]-p[1]))
        check(('portrait' if portrait else 'landscape')+' mini preserves video aspect',abs(sizes[-1][0]/sizes[-1][1]-(9/16 if portrait else 16/9))<.02)
        d.shot('v20-'+('portrait' if portrait else 'landscape'))
        d.tap('展开小窗');d.wait('正在观看');time.sleep(.5)
    area0=sizes[0][0]*sizes[0][1];area1=sizes[1][0]*sizes[1][1]
    check('portrait and landscape minis use the same area',abs(area0-area1)/area0<.02)
    check('portrait mini is narrower and taller',sizes[1][0]<sizes[0][0] and sizes[1][1]>sizes[0][1])
    check('no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:d.adb('shell','am','force-stop','app.biliflow')
