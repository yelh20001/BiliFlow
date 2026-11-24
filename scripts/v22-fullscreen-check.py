"""Capture actual portrait fullscreen growth and return using the app player."""
from device_driver import Device, ROOT, PROBE
import time,re,json,sys
sys.path.insert(0,str(ROOT/'.tools/python-libs'))
from PIL import Image
d=Device();results=[]
def tags(prefix):return [n for n in d.tree().iter('node') if n.get('resource-id','').startswith(prefix)]
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def check(name,value):
    results.append(dict(check=name,passed=bool(value)));print(name,bool(value),flush=True)
    (ROOT/'artifacts/v22-fullscreen-results.json').write_text(json.dumps(results,indent=2));assert value,name
try:
    d.adb('shell','am','force-stop','app.biliflow');d.adb('logcat','-c');d.start();d.wait('热门');d.tap('热门');time.sleep(2)
    for _ in range(30):
        cards=[n for n in d.tree().iter('node') if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
        if cards:break
        time.sleep(.3)
    d.tap_node(cards[0]);d.wait('正在观看');time.sleep(2)
    d.adb('shell','am','broadcast','-n','app.biliflow/.MiniFixtureReceiver','--es','op','load','--ez','portrait','true');time.sleep(2)
    d.adb('shell','am','broadcast','-n','app.biliflow/.MiniFixtureReceiver','--es','op','pause')
    initial=rect(tags('video_player')[0]);x=(initial[0]+initial[2])//2
    d.probe('dragliveframes',x=x,**{'from':initial[3]-55,'to':initial[1]+30})
    folder=ROOT/'.tools/v22-fullscreen';folder.mkdir(exist_ok=True)
    heights=[]
    for i in range(40):
        data=d.adb('exec-out','cat',PROBE+f'drag-live-{i}.png',binary=True);(folder/f'{i}.png').write_bytes(data)
        im=Image.open(folder/f'{i}.png').convert('RGB').resize((180,400))
        # The fixed video's cyan center stripe identifies the visible video height.
        rows=[y for y in range(400) if sum(1 for xx in range(180) if (lambda p:p[0]<75 and p[1]>130 and p[2]>170)(im.getpixel((xx,y))))>8]
        heights.append(max(rows)-min(rows)+1 if rows else 0)
    final=rect(tags('video_player')[0])
    check('portrait swipe up reaches fullscreen',final[3]-final[1]>initial[3]-initial[1]+500)
    low=min(heights[:5]);high=max(heights)
    intermediate=[h for h in heights if low+15<h<high-15]
    check('portrait fullscreen has multiple intermediate video sizes',len(set(intermediate))>=4)
    (ROOT/'artifacts/v22-frame-heights.json').write_text(json.dumps(heights))
    d.swipe(x,final[1]+90,x,final[1]+330,650);d.wait('正在观看');time.sleep(1)
    restored=rect(tags('video_player')[0])
    check('swipe down restores detail player height',abs((restored[3]-restored[1])-(initial[3]-initial[1]))<=4)
    check('no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:d.adb('shell','am','force-stop','app.biliflow')
