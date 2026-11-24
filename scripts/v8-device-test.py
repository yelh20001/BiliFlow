"""Read-only v0.8 checks. Private frames and metadata remain in ignored .tools."""
from device_driver import Device,ROOT
import json,re,time,sys
d=Device();path=ROOT/'artifacts/device-tests/v8-results.json';results=json.loads(path.read_text()) if path.exists() else []
def check(name,value):
    results[:]=[i for i in results if i['check']!=name];results.append({'check':name,'passed':bool(value)})
    path.write_text(json.dumps(results,indent=2));print(name,bool(value),flush=True)
    if not value:raise AssertionError(name)
def nodes():return list(d.tree().iter('node'))
def tagged(tag):return [n for n in nodes() if n.get('resource-id')==tag]
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def state():return d.adb('shell','dumpsys','activity','top')
def eventually(fn,seconds=35):
    until=time.monotonic()+seconds
    while time.monotonic()<until:
        if fn():return True
        time.sleep(.4)
    return False
def audio():
    pid=d.adb('shell','pidof','app.biliflow').strip()
    return any(f'/{pid} state:started' in s for s in d.adb('shell','dumpsys','audio').splitlines())
def identity():
    m=re.search(r'androidx.media3.ui.PlayerView\{([^ ]+)',state())
    return m[1] if m else None
def position():return int(re.search(r'BiliFlowMini minimized=\w+ position=(\d+)',state())[1])
def root():
    d.start();time.sleep(.5)
    if d.find('Close sheet'):d.back()
    if d.find('关闭小窗'):d.tap('关闭小窗')
    if d.find('返回浏览页面'):d.tap('返回浏览页面')
    if not d.find('视频'):d.swipe(350,650,350,1350,450)
    d.wait('视频')
def cards():return [n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
def tap_card(card):
    b=rect(card);d.adb('shell','input','tap',str(b[0]+35),str(b[1]+80))
def open_video():
    root();d.tap('视频');d.tap('热门');time.sleep(2);tap_card(cards()[0]);d.wait('正在观看')
    if not eventually(audio):raise AssertionError('Video did not play')
    # Keep saved near-end progress from ending a short test video during lifecycle checks.
    d.adb('shell','input','keyevent','127');time.sleep(.3)
    if not d.find('全屏'):
        b=rect(tagged('video_player')[0]);d.adb('shell','input','tap',str(b[0]+25),str((b[1]+b[3])//2));time.sleep(.4)
    bar=rect(tagged('playback_seekbar')[0]);d.adb('shell','input','tap',str(bar[0]+int((bar[2]-bar[0])*.08)),str((bar[1]+bar[3])//2))
    d.adb('shell','input','keyevent','126');assert eventually(audio)
def down():
    b=rect(tagged('video_player')[0]);d.probe('drag',x=(b[0]+b[2])//2,**{'from':b[1]+70,'to':b[3]-35});d.wait('关闭小窗');time.sleep(.7)
def fits():
    m=rect(tagged('in_app_mini_player')[0]);nav=rect(tagged('home_bottom_bar')[0])
    return m[3]<nav[1] and m[0]>=0 and m[2]<=nav[2] and m[1]>0

stage=sys.argv[1] if len(sys.argv)>1 else 'mini'
try:
    if stage=='avatars':
        root();d.tap('视频');d.tap('热门')
        check('popular feed loads real decoded author images',eventually(lambda:bool(tagged('video_author_avatar_loaded'))));d.shot('v8-avatars-popular')
        d.tap('推荐');d.wait('安卓 App 推荐',contains=True)
        check('Android account recommendation resolves real decoded author images',eventually(lambda:bool(tagged('video_author_avatar_loaded'))));d.shot('v8-avatars-android')
    if stage=='mini':
        open_video();view=identity();before=position();down()
        check('downward video gesture returns to video feed with in-app mini',bool(tagged('in_app_mini_player')) and bool(d.find('视频')) and 'minimized=true' in state())
        check('mini is above floating navigation with a gap',fits());d.shot('v8-mini')
        mini=rect(tagged('in_app_mini_player')[0]);player=rect(tagged('video_player')[0])
        check('mini video fills compact viewport without old toolbar reservation',abs((mini[3]-mini[1])-(player[3]-player[1]))<=2 and abs((mini[2]-mini[0])-(player[2]-player[0]))<=2)
        check('mini keeps same native PlayerView and playing progress',identity()==view and audio() and position()>before)
        check('mini releases high refresh request while hiding danmaku','BiliFlowRefresh requested=0.0' in state())
        d.tap('我的');check('navigation below mini remains usable',bool(d.find('账号头像')) and bool(tagged('in_app_mini_player')))
        d.tap('视频');d.tap('暂停小窗');check('mini can pause without expanding',eventually(lambda:not audio()) and bool(tagged('in_app_mini_player')))
        d.tap('播放小窗');check('mini can resume without expanding',eventually(audio) and bool(tagged('in_app_mini_player')))
        before=position();d.tap('展开小窗');d.wait('正在观看');time.sleep(.8)
        check('restore reuses PlayerView and preserves progress',identity()==view and position()>=before and not tagged('in_app_mini_player') and audio())
        b=rect(tagged('video_player')[0]);d.probe('drag',x=350,**{'from':b[3]-30,'to':b[1]+25});time.sleep(1)
        b=rect(tagged('video_player')[0]);d.probe('drag',x=(b[0]+b[2])//2,**{'from':b[1]+50,'to':b[3]-60});d.wait('正在观看');time.sleep(.7)
        check('fullscreen downward gesture exits fullscreen without minimizing',not tagged('in_app_mini_player') and 'minimized=false' in state() and audio())
        d.adb('shell','input','keyevent','127');time.sleep(.4);down()
        check('minimizing paused video preserves pause',not audio() and 'playWhenReady=false' in state())
        d.tap('关闭小窗');check('closing mini releases media and clears routes',not audio() and identity() is None and not tagged('in_app_mini_player'))
    if stage=='replace':
        open_video();old=identity();down();available=cards();assert len(available)>1
        tap_card(available[1]);d.wait('正在观看');check('opening another feed video replaces mini with detail',eventually(audio) and not tagged('in_app_mini_player') and identity()!=old and bool(d.find('返回浏览页面')))
        d.tap('返回浏览页面');check('replacement video releases on return home',not audio())
    if stage=='pip':
        open_video();view=identity();down();d.adb('shell','input','keyevent','3');time.sleep(2)
        check('app mini can enter native system PiP without restarting',audio() and 'pip=true' in state() and identity()==view)
        d.shot('v8-mini-native-pip');d.start();d.wait('关闭小窗');time.sleep(.7)
        check('native PiP returns to app mini above navigation',fits() and audio() and identity()==view);d.shot('v8-mini-pip-return')
        d.tap('关闭小窗');check('closing mini after native PiP stops audio',not audio())
    if stage=='qhd':
        root()
        try:
            d.adb('shell','wm','size','1440x3120');d.adb('shell','wm','density','560');time.sleep(2)
            open_video();b=rect(tagged('video_player')[0]);d.probe('drag',x=(b[0]+b[2])//2,**{'from':b[1]+100,'to':b[3]-60});d.wait('关闭小窗');time.sleep(1)
            check('QHD mini uses measured bottom bar clearance',fits() and audio());d.shot('v8-mini-qhd');d.tap('关闭小窗')
        finally:d.adb('shell','wm','size','reset');d.adb('shell','wm','density','reset')
    check('no fatal runtime exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
except Exception:d.shot('v8-failure');raise
finally:root()
