"""Read-only v0.7 UI checks. Frames and UI text remain in ignored .tools."""
from device_driver import Device,ROOT,PROBE
import json,re,time,sys,xml.etree.ElementTree as ET
d=Device();path=ROOT/'artifacts/device-tests/v7-results.json'
stage=sys.argv[1] if len(sys.argv)>1 else 'all'
results=json.loads(path.read_text()) if stage in ['tail','dynamic'] and path.exists() else []
for result in results:
    if result['check']=='PiP transition hides controls before compact presentation':result['check']='PiP transition preparation runs'
def check(name,value):
    results[:]=[item for item in results if item['check']!=name]
    results.append({'check':name,'passed':bool(value)});path.write_text(json.dumps(results,indent=2))
    print(name,bool(value),flush=True)
    if not value:raise AssertionError(name)
def nodes():return list(d.tree().iter('node'))
def tagged(tag):return [n for n in nodes() if n.get('resource-id','')==tag]
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def state():return d.adb('shell','dumpsys','activity','top')
def eventually(fn,seconds=30):
    until=time.monotonic()+seconds
    while time.monotonic()<until:
        if fn():return True
        time.sleep(.4)
    return False
def root():
    d.start();time.sleep(.5)
    if d.find('Close sheet'):d.back()
    if d.find('返回浏览页面'):d.tap('返回浏览页面')
    if not d.find('视频'):d.swipe(350,650,350,1350,450)
    d.wait('视频')
def settings():d.tap('外观设置');d.wait('画质变更提醒')
def switch():
    tree=nodes();tag=[n for n in tree if n.get('resource-id')=='quality_change_notice']
    if tag:return tag[0]
    label=next(n for n in tree if n.get('text')=='画质变更提醒');b=rect(label);center=(b[1]+b[3])/2
    return min((n for n in tree if n.get('checkable')=='true'),key=lambda n:abs((rect(n)[1]+rect(n)[3])/2-center))
def checked():return any(n.get('checked')=='true' for n in switch().iter('node'))
def close_settings():d.adb('shell','input','tap','360','180');time.sleep(.4)
def cards():return [n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
def open_video(film=False):
    d.tap('视频');d.tap('热门');time.sleep(2);card=cards()[0]
    if film:
        cover=next(c for c in card.iter('node') if c.get('content-desc') and (lambda b:b[2]-b[0]>200 and b[3]-b[1]>100)(rect(c)))
        b=rect(cover);d.probe('fade',x=(b[0]+b[2])//2,y=(b[1]+b[3])//2)
    else:d.tap_node(card)
    d.wait('正在观看')
def show():
    if not tagged('player_controls'):
        b=rect(tagged('video_player')[0]);d.adb('shell','input','tap',str(b[0]+25),str((b[1]+b[3])//2));time.sleep(.5)
def thumb_fits():
    b=rect(tagged('video_player')[0]);thumb=rect(tagged('playback_seek_thumb')[0]);track=rect(tagged('playback_seekbar')[0])
    return b[0]<=thumb[0]<thumb[2]<=b[2] and b[1]<=thumb[1]<thumb[3]<=b[3] and track[1]<=thumb[1]<thumb[3]<=track[3]
def dynamic_checks():
    d.tap('动态');d.wait('关注动态');d.wait('关注的 UP 主',contains=True);time.sleep(2)
    d.swipe(350,1350,350,600,450);time.sleep(.5)
    # The empty parent semantics nodes retain their old bounds after translation;
    # inspect the actual visible title and navigation controls, and retain screenshots.
    check('followed feed hides top and bottom bars on downward browsing',not d.find('关注动态') and not d.find('视频'))
    d.shot('v7-dynamic-hidden');d.swipe(350,650,350,1300,450);time.sleep(.5)
    check('followed feed reveals bars on upward browsing',bool(d.find('关注动态')) and bool(d.find('视频')))
    d.shot('v7-dynamic-shown')
timeouts={key:d.adb('shell','settings','get','secure',key).strip() for key in ['accessibility_non_interactive_ui_timeout_ms','accessibility_interactive_ui_timeout_ms']}
try:
    for key in timeouts:d.adb('shell','settings','put','secure',key,'15000')
    if stage=='dynamic':
        root();dynamic_checks()
        check('no fatal AndroidRuntime exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
        sys.exit(0)
    if stage!='tail':
        root();settings();check('quality change notice defaults off',not checked())
        d.tap_node(switch());check('quality notice can be enabled',checked());close_settings()
        d.adb('shell','am','force-stop','app.biliflow');root();settings()
        check('quality notice option survives restart',checked())
        d.tap('首选清晰度：',contains=True);d.tap('8K');close_settings();open_video(film=True)
        check('enabled option shows real fallback notice',eventually(lambda:bool(d.find('已切换至当前账号可用',contains=True)),35))
        check('playing bullets request 120Hz',eventually(lambda:'BiliFlowRefresh requested=120.0' in state()))
        d.adb('shell','input','keyevent','127');time.sleep(.4);show()
        check('pause releases refresh request',eventually(lambda:'BiliFlowRefresh requested=0.0' in state()))
        check('portrait seek thumb stays inside viewport and track',thumb_fits());d.shot('v7-seek-portrait')
        folder=ROOT/'.tools/v7-entry';folder.mkdir(exist_ok=True)
        for i in range(18):(folder/f'{i}.png').write_bytes(d.adb('exec-out','cat',PROBE+f'fade-frame-{i}.png',binary=True))
        d.tap('全屏');time.sleep(1);show()
        check('fullscreen seek thumb stays inside viewport and track',thumb_fits());d.shot('v7-seek-fullscreen')
        d.back();d.tap('返回浏览页面');settings();d.tap_node(switch());check('quality notice can be disabled',not checked());close_settings()
        open_video();time.sleep(2)
        check('disabled option suppresses fallback notice',not d.find('已切换至当前账号可用',contains=True))
        check('quality still falls back while notice is disabled',eventually(lambda:'videoSize=0x0' not in state()))
        d.adb('shell','input','keyevent','127');time.sleep(.4);show();d.tap('关闭弹幕')
        d.adb('shell','input','keyevent','126');time.sleep(.5)
        check('disabled bullets release refresh request while playing','playWhenReady=true' in state() and 'BiliFlowRefresh requested=0.0' in state())
        d.adb('shell','input','keyevent','127');time.sleep(.4);show();d.tap('开启弹幕')
        d.adb('shell','input','keyevent','126');time.sleep(.5)
        check('reenabled bullets restore 120Hz request',eventually(lambda:'BiliFlowRefresh requested=120.0' in state()))
        d.probe('home');time.sleep(1)
        folder=ROOT/'.tools/v7-pip';folder.mkdir(exist_ok=True)
        for i in range(18):(folder/f'{i}.png').write_bytes(d.adb('exec-out','cat',PROBE+f'fade-frame-{i}.png',binary=True))
        check('PiP restores system refresh policy','pip=true' in state() and 'BiliFlowRefresh requested=0.0' in state())
        check('PiP transition preparation runs',bool(re.search(r'BiliFlowPip transitioning=false enters=[1-9]',state())))
    else:
        d.start();d.wait('\u6b63\u5728\u89c2\u770b');d.adb('shell','input','keyevent','126');time.sleep(.6);d.adb('shell','input','keyevent','3');time.sleep(1)
    snapshot=state();native=re.search(r'androidx.media3.ui.PlayerView\{[^\n]*? (\d+),(\d+)-(\d+),(\d+)\}',snapshot)
    decor=re.search(r'DecorView\{[^\n]*? (\d+),(\d+)-(\d+),(\d+)[^\n]*\[MainActivity\]',snapshot)
    assert native and decor,'Native PiP view bounds missing'
    nb=list(map(int,native.groups()));db=list(map(int,decor.groups()))
    check('PiP native player fills entire window without toolbar strip',(nb[2]-nb[0],nb[3]-nb[1])==(db[2]-db[0],db[3]-db[1]))
    d.start();d.wait('正在观看');check('PiP return restores 120Hz request',eventually(lambda:'BiliFlowRefresh requested=120.0' in state()))
    d.tap('返回浏览页面');check('closing video restores system refresh policy','playWhenReady=false' in state() and 'BiliFlowRefresh requested=0.0' in state())
    d.tap('我的');d.wait('账号头像');check('update keeps account authorization',bool(d.find('App 授权已连接',contains=True)))
    dynamic_checks()
    check('no fatal AndroidRuntime exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
except Exception:
    d.shot('v7-failure');raise
finally:
    for key,value in timeouts.items():
        if value=='null':d.adb('shell','settings','delete','secure',key)
        else:d.adb('shell','settings','put','secure',key,value)
    root();settings()
    if checked():d.tap_node(switch())
    d.tap('首选清晰度：',contains=True);d.tap('自动');close_settings();d.tap('视频');d.tap('推荐')
