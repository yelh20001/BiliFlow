"""Native mini aspect, retained comment position, opt-in dragging and frame continuity."""
from device_driver import Device, ROOT, PROBE
import json, re, time, sys, io
sys.path.insert(0,str(ROOT/'.tools/python-libs'))
from PIL import Image
d=Device(); results=[]
def check(name,value):
    results.append(dict(check=name,passed=bool(value)))
    (ROOT/'artifacts/v19-mini-results.json').write_text(json.dumps(results,indent=2))
    print(name,bool(value),flush=True);assert value,name
def nodes():return list(d.tree().iter('node'))
def tag(prefix):return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def until(fn):
    for _ in range(60):
        value=fn()
        if value:return value
        time.sleep(.3)
    raise AssertionError('UI did not settle')
def switch(on):
    d.tap('外观设置');d.wait('小窗拖动')
    n=tag('mini_dragging')[0]
    if (n.get('checked')=='true')!=on:d.tap_node(n)
    d.back()
def broadcast(portrait):
    d.adb('shell','am','broadcast','-n','app.biliflow/.MiniFixtureReceiver','--es','op','load','--ez','portrait',str(portrait).lower());time.sleep(2)
    d.adb('shell','am','broadcast','-n','app.biliflow/.MiniFixtureReceiver','--es','op','pause');time.sleep(.3)
def minimize(capture=False):
    p=rect(tag('video_player')[0]);x=(p[0]+p[2])//2
    if capture:
        d.probe('dragliveframes',x=x,**{'from':p[1]+55,'to':p[3]-20})
        save_frames('handoff')
    else:d.swipe(x,p[1]+55,x,p[3]-20,650)
    d.wait('关闭小窗');time.sleep(.5)
def save_frames(name):
    folder=ROOT/'.tools/v19-mini'/name;folder.mkdir(parents=True,exist_ok=True)
    for i in range(40):
        (folder/f'{i}.png').write_bytes(d.adb('exec-out','cat',PROBE+f'drag-live-{i}.png',binary=True))
def mini():return rect(tag('in_app_mini_player')[0])
try:
    d.adb('shell','am','force-stop','app.biliflow');d.adb('logcat','-c');d.start();d.wait('外观设置');switch(True)
    d.adb('shell','am','force-stop','app.biliflow');d.start();d.wait('外观设置');d.tap('外观设置');d.wait('小窗拖动')
    check('dragging switch persists',tag('mini_dragging')[0].get('checked')=='true');d.back()
    d.tap('热门');time.sleep(2)
    cards=until(lambda:[n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))])
    d.tap_node(cards[0]);d.wait('正在观看');time.sleep(2)
    tabs=d.wait('评论',contains=True);d.tap_node(tabs[0]);until(lambda:[n for n in tag('comments_feed_page_') if not n.get('resource-id').startswith('comments_feed_page_0_')])
    for _ in range(3):d.swipe(350,1300,350,850,450)
    position=tag('video_comment_position_')[0].get('resource-id')
    check('comment list scrolled',not position.startswith('video_comment_position_0_'))
    broadcast(True);minimize(True)
    p=mini();check('portrait mini matches 9 by 16 output',abs((p[2]-p[0])/(p[3]-p[1])-9/16)<.015)
    d.shot('v19-portrait-mini')
    x=(p[0]+p[2])//2;y=(p[1]+p[3])//2
    d.probe('dragliveframes',x=x,**{'from':y,'to':max(180,y-260)});save_frames('drag')
    moved=mini();check('mini can be dragged without resizing',moved[1]<p[1]-100 and abs((moved[2]-moved[0])-(p[2]-p[0]))<=2)
    d.tap('展开小窗');d.wait('正在观看');until(lambda:tag('video_details_page_1'))
    check('mini restore keeps exact comment scroll position',tag('video_comment_position_')[0].get('resource-id')==position)
    broadcast(False);minimize();p=mini();check('landscape mini matches 16 by 9 output',abs((p[2]-p[0])/(p[3]-p[1])-16/9)<.02)
    d.wait('外观设置');switch(False)
    p=mini();d.swipe((p[0]+p[2])//2,(p[1]+p[3])//2,(p[0]+p[2])//2,max(180,(p[1]+p[3])//2-160),600)
    check('disabled switch prevents mini movement',mini()==p)
    d.tap('关闭小窗');d.wait('外观设置')
    d.adb('shell','am','force-stop','app.biliflow');d.start();d.wait('外观设置');d.tap('外观设置');d.wait('小窗拖动')
    check('disabled dragging switch persists',tag('mini_dragging')[0].get('checked')=='false');d.back()
    check('no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:d.adb('shell','am','force-stop','app.biliflow')
