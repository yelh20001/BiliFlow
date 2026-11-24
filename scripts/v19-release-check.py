"""Verify release gestures and settings without debug receivers or account writes."""
from device_driver import Device, ROOT
import json,re,time
d=Device();results=[]
def check(name,value):
    results.append(dict(check=name,passed=bool(value)));print(name,bool(value),flush=True)
    (ROOT/'artifacts/v19-release-results.json').write_text(json.dumps(results,indent=2));assert value,name
def tags(prefix):return [n for n in d.tree().iter('node') if n.get('resource-id','').startswith(prefix)]
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def until(fn):
    for _ in range(40):
        value=fn()
        if value:return value
        time.sleep(.3)
    raise AssertionError('UI did not settle')
def switch(on):
    d.wait('外观设置');d.tap('外观设置');d.wait('小窗拖动');n=tags('mini_dragging')[0]
    if (n.get('checked')=='true')!=on:d.tap_node(n)
    d.back()
def minimize():
    p=rect(tags('video_player')[0]);d.swipe((p[0]+p[2])//2,p[1]+55,(p[0]+p[2])//2,p[3]-20,650);d.wait('关闭小窗');time.sleep(.5)
try:
    d.adb('shell','am','force-stop','app.biliflow');d.adb('logcat','-c');d.start();switch(False)
    d.tap('热门');time.sleep(2)
    cards=until(lambda:[n for n in d.tree().iter('node') if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))])
    d.tap_node(cards[0]);d.wait('正在观看');time.sleep(2)
    d.tap_node(d.wait('评论',contains=True)[0]);until(lambda:[n for n in tags('comments_feed_page_') if not n.get('resource-id').startswith('comments_feed_page_0_')])
    d.swipe(350,1300,350,850,450);position=tags('video_comment_position_')[0].get('resource-id')
    minimize();p=rect(tags('in_app_mini_player')[0]);x=(p[0]+p[2])//2;y=(p[1]+p[3])//2
    d.swipe(x,y,x,y-180,500);check('release switch off prevents movement',rect(tags('in_app_mini_player')[0])==p)
    switch(True);d.swipe(x,y,x,y-180,500);q=rect(tags('in_app_mini_player')[0])
    check('release switch on allows movement',q[1]<p[1]-80)
    d.tap('展开小窗');d.wait('正在观看');until(lambda:tags('video_details_page_1'))
    check('release mini preserves comment tab and exact scroll',tags('video_comment_position_')[0].get('resource-id')==position)
    minimize();q2=rect(tags('in_app_mini_player')[0]);check('next minimize returns to dragged position',max(abs(a-b) for a,b in zip(q,q2))<=2)
    d.tap('关闭小窗');switch(False)
    check('release has no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:d.adb('shell','am','force-stop','app.biliflow')
