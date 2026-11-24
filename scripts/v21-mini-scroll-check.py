"""Fixed mini passes drags to the page, while taps and movable-mini gestures stay local."""
from device_driver import Device,ROOT
import json,re,time
d=Device();results=[]
def check(name,value):
    results.append(dict(check=name,passed=bool(value)));print(name,bool(value),flush=True)
    (ROOT/'artifacts/v21-mini-results.json').write_text(json.dumps(results,indent=2));assert value,name
def nodes():return list(d.tree().iter('node'))
def tags(prefix):return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def cards():return [n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
def position():return [(tuple(c.get('text') for c in n.iter('node') if c.get('text')),rect(n)[1]) for n in cards()[:2]]
def switch(on):
    if not d.find('外观设置'):d.swipe(55,650,55,1100,400)
    d.wait('外观设置');d.tap('外观设置');d.wait('小窗拖动');n=tags('mini_dragging')[0]
    if (n.get('checked')=='true')!=on:d.tap_node(n)
    d.back()
def mini():return rect(tags('in_app_mini_player')[0])
def minimize():
    p=rect(tags('video_player')[0]);x=(p[0]+p[2])//2
    d.swipe(x,p[1]+55,x,p[3]-20,600);d.wait('关闭小窗');time.sleep(.5)
try:
    d.adb('shell','am','force-stop','app.biliflow');d.adb('logcat','-c');d.start();switch(False);d.tap('热门');time.sleep(2)
    for _ in range(30):
        if cards():break
        time.sleep(.3)
    d.tap_node(cards()[0]);d.wait('正在观看');time.sleep(2);minimize()
    p=mini();before=position();x=(p[0]+p[2])//2;y=(p[1]+p[3])//2
    d.swipe(x,y,x,y-420,600);time.sleep(.5)
    check('swipe starting on fixed mini scrolls underlying feed',position()!=before)
    check('fixed mini stays at the same position and remains minimized',mini()==p)
    before=position();was_playing=bool(d.find('暂停小窗'));d.tap('暂停小窗' if was_playing else '播放小窗')
    d.wait('播放小窗' if was_playing else '暂停小窗');time.sleep(.3)
    check('mini playback button does not click through to page',mini()==p and position()==before)
    before=position();d.tap('展开小窗');d.wait('正在观看');minimize()
    check('expand button still works and does not open underlying video',mini()==p and position()==before)
    # A gesture starting over a button should scroll, rather than trigger the button.
    close=rect(d.wait('关闭小窗')[0]);x=(close[0]+close[2])//2;y=(close[1]+close[3])//2;before=position()
    d.swipe(x,y,x,y-320,600);time.sleep(.5)
    check('swipe over close button scrolls without closing mini',bool(tags('in_app_mini_player')) and position()!=before)
    switch(True);p=mini();before=position();x=(p[0]+p[2])//2;y=(p[1]+p[3])//2
    d.swipe(x,y,x,y-180,600);time.sleep(.5)
    check('dragging enabled moves mini rather than scrolling feed',mini()[1]<p[1]-80 and position()==before)
    d.tap('关闭小窗');switch(False)
    check('no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:d.adb('shell','am','force-stop','app.biliflow')
