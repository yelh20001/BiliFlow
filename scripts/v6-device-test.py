"""Read-only v0.6 checks; account data/screenshots remain under ignored .tools."""
from device_driver import Device, ROOT
import json, re, sys, time, xml.etree.ElementTree as ET

d=Device(); path=ROOT/'artifacts/device-tests/v6-results.json'
results=json.loads(path.read_text()) if path.exists() else []
def check(name, value):
    global results
    results=[v for v in results if v['check']!=name]+[{'check':name,'passed':bool(value)}]
    path.write_text(json.dumps(results,indent=2),encoding='utf-8'); print(name,bool(value),flush=True)
    if not value: raise AssertionError(name)
def nodes(): return list(d.tree().iter('node'))
def tagged(prefix): return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def rect(n): return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def bounds(): return rect(tagged('video_player')[0])
def state(): return d.adb('shell','dumpsys','activity','top')
def live_state():
    match=re.search(r'BiliFlowLiveDanmaku connected=(\w+) received=(\d+)',state())
    return match and match[1]=='true',int(match[2]) if match else 0
def eventually(predicate,seconds=35):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if predicate():return True
        time.sleep(.7)
    return False
def root():
    d.start();time.sleep(1)
    if d.find('返回浏览页面'):d.tap('返回浏览页面')
    for _ in range(5):
        if d.find('视频'):return
        d.back()
    d.wait('视频')
def audio():
    pid=d.adb('shell','pidof','app.biliflow').strip()
    return any(f'/{pid} state:started' in line for line in d.adb('shell','dumpsys','audio').splitlines())
def show():
    if not d.find('全屏') and not d.find('退出全屏'):
        b=bounds();d.adb('shell','input','tap',str(b[0]+25),str((b[1]+b[3])//2));time.sleep(.25)
def open_video(wait=True):
    d.tap('视频');d.tap('热门');time.sleep(3)
    cards=[n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(cards[0]);d.wait('正在观看')
    if wait:check('video decodes with audio',eventually(audio))

stage=sys.argv[1] if len(sys.argv)>1 else 'player'
if stage=='live':
    root();d.tap('直播');d.wait('正在发生的精彩');time.sleep(3)
    rooms=[n for n in nodes() if n.get('clickable')=='true' and any(c.get('text','').startswith('●') or '人气' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(rooms[0]);d.wait('直播间')
    check('live native playback starts',eventually(audio))
    # Use a modest stream on the network-proxied emulator for the long lifecycle run.
    show();d.tap('原画');d.wait('直播清晰度')
    choices=[label for label in ['流畅','高清','超清'] if d.find(label)]
    if choices:d.tap(choices[0])
    else:d.back()
    check('live lower-bandwidth stream plays',eventually(audio))
    check('live socket authenticates',eventually(lambda:live_state()[0]))
    check('live socket receives real bullet messages',eventually(lambda:live_state()[1]>0,80))
    check('live portrait has a populated bullet canvas',eventually(lambda:any(int(n.get('resource-id').rsplit('_',1)[-1])>0 for n in tagged('live_danmaku_overlay_count_'))))
    d.shot('v6-live-portrait');before=live_state()[1]
    # Observe at least one heartbeat interval against the real server.
    deadline=time.monotonic()+32
    while time.monotonic()<deadline:time.sleep(1)
    check('live socket survives the heartbeat interval',live_state()[0] and live_state()[1]>=before)
    check('live stream continues through heartbeat observation',audio())
    b=bounds();d.probe('drag',x=350,**{'from':b[3]-30,'to':b[1]+25});time.sleep(1)
    check('live fullscreen keeps its bullet canvas',bool(tagged('live_danmaku_overlay_count_')) and live_state()[0])
    d.shot('v6-live-fullscreen');d.back();time.sleep(1)
    show();d.tap('关闭弹幕')
    check('live bullet switch closes the socket',eventually(lambda:not live_state()[0]) and not tagged('live_danmaku_overlay_count_'))
    show();d.tap('开启弹幕')
    check('live bullet switch reconnects',eventually(lambda:live_state()[0]))
    show();d.tap('暂停')
    check('live pause releases bullet socket',eventually(lambda:not live_state()[0]) and not audio())
    show();d.tap('播放');check('live resume reconnects',eventually(lambda:live_state()[0]))
    d.adb('shell','input','keyevent','3');time.sleep(2)
    check('live PiP keeps audio and suspends bullets',audio() and 'pip=true' in state() and not live_state()[0])
    d.start();d.wait('直播间');check('live PiP return reconnects',eventually(lambda:live_state()[0]))
    d.back();time.sleep(1)
    check('leaving live releases audio and bullets',not audio() and not live_state()[0])

if stage=='player':
    root();open_video();time.sleep(4)
    check('video controls auto-hide',not tagged('player_controls'))
    show();check('tap displays player controls',bool(tagged('player_controls')))
    b=bounds();d.adb('shell','input','tap',str(b[0]+25),str((b[1]+b[3])//2));time.sleep(.4)
    check('second tap hides player controls',not tagged('player_controls'))
    baseline=bounds()
    try:
        d.adb('shell','settings','put','global','policy_control','immersive.status=app.biliflow');time.sleep(1)
        hidden=bounds()
        d.adb('shell','settings','delete','global','policy_control');time.sleep(1)
        restored=bounds()
        check('portrait player stable across status policy roundtrip',baseline==hidden==restored)
    finally:d.adb('shell','settings','delete','global','policy_control')
    for i in range(2):
        b=bounds();d.probe('drag',x=350,**{'from':b[3]-30,'to':b[1]+25});time.sleep(1)
        full=bounds();check(f'fullscreen cycle {i+1} expands player',full[2]>1000)
        d.back();time.sleep(1)
        check(f'fullscreen cycle {i+1} restores stable insets',bounds()==baseline and audio())
    d.probe('gesture',x=270,y=1100,cancel='true');time.sleep(.7)
    check('cancelled predictive back retains playing video',bool(tagged('video_stack_depth_1')) and audio())
    d.probe('gesture',x=270,y=1100);time.sleep(.7)
    check('completed predictive back releases video',bool(d.find('视频')) and not audio())

if stage=='notice':
    timeouts={k:d.adb('shell','settings','get','secure',k).strip() for k in ['accessibility_non_interactive_ui_timeout_ms','accessibility_interactive_ui_timeout_ms']}
    try:
        for key in timeouts:d.adb('shell','settings','put','secure',key,'15000')
        root();d.tap('视频');d.tap('热门');time.sleep(2)
        d.tap('外观设置');d.tap('跟随系统 · 原生取色');d.tap('首选清晰度',contains=True);d.tap('8K');d.adb('shell','input','tap','360','180')
        for mode in ['no','yes']:
            d.adb('shell','cmd','uimode','night',mode);time.sleep(1)
            open_video(False)
            from device_driver import PROBE
            deadline=time.monotonic()+35;notice=[]
            while time.monotonic()<deadline:
                try:
                    d.probe(screenshot='true')
                    tree=ET.fromstring(d.adb('shell','cat',PROBE+'window.xml'))
                except (RuntimeError,ET.ParseError):
                    time.sleep(.2);continue
                notice=[n for n in tree.iter('node') if '已切换至当前账号可用' in n.get('text','')]
                if notice:break
                time.sleep(.15)
            assert notice,'Quality fallback notice not found'
            b=rect(notice[-1])
            image=d.adb('exec-out','cat',PROBE+'screen.png',binary=True)
            (ROOT/f'.tools/v3-device-shots/v6-notice-{mode}.png').write_bytes(image)
            check('raised quality notice '+mode,b[3]<1500 and b[0]>0 and b[2]<720)
            d.tap('返回浏览页面');d.wait('视频')
    finally:
        for key,value in timeouts.items():
            if value=='null':d.adb('shell','settings','delete','secure',key)
            else:d.adb('shell','settings','put','secure',key,value)
        d.adb('shell','cmd','uimode','night','no');root();d.tap('外观设置');d.tap('首选清晰度',contains=True);d.tap('自动');d.adb('shell','input','tap','360','180')

if stage=='fade':
    from io import BytesIO
    sys.path.insert(0,str(ROOT/'.tools/python-libs'))
    from PIL import Image, ImageChops, ImageStat
    from device_driver import PROBE
    root();open_video();d.adb('shell','input','keyevent','127');time.sleep(.5);show()
    restore_bullets=bool(d.find('关闭弹幕'))
    if restore_bullets:d.tap('关闭弹幕')
    check('fade sampling freezes video and disables moving bullets',not audio() and not tagged('danmaku_overlay_count_'))
    b=bounds(); crop=(b[0]+20,b[1]+10,b[2]-20,b[3]-10)
    def image(name):
        raw=d.adb('exec-out','cat',PROBE+name,binary=True)
        folder=ROOT/'.tools/v6-fade';folder.mkdir(exist_ok=True);(folder/name).write_bytes(raw)
        return Image.open(BytesIO(raw)).convert('RGB').crop(crop)
    for direction in ['out','in']:
        d.probe('fade',x=b[0]+25,y=(b[1]+b[3])//2)
        first=image('fade-before.png'); frames=[image(f'fade-frame-{i}.png') for i in range(18)]
        difference=lambda a,z:sum(ImageStat.Stat(ImageChops.difference(a,z)).mean)
        total=difference(first,frames[-1]); phases=[difference(first,f)/max(total,.001) for f in frames]
        (ROOT/f'.tools/v6-fade/{direction}.json').write_text(json.dumps(phases))
        check('player fade '+direction+' has intermediate visual frames',total>5 and len({round(p,2) for p in phases if .03<p<.97})>=2)
    show()
    if restore_bullets:d.tap('开启弹幕')
    d.tap('返回浏览页面');check('fade test releases player',not audio())

if stage=='release':
    root();d.tap('我的');d.wait('账号头像',timeout=35)
    check('release retains account and Android app authorization',bool(d.find('账号头像')) and bool(d.find('App 授权已连接',contains=True)))
    d.tap('视频');d.tap('推荐');d.wait('安卓 App 推荐',timeout=35,contains=True)
    check('release retains official Android account recommendations',bool(d.find('安卓 App 推荐',contains=True)))
    open_video()
    candidates=[]
    for _ in range(10):
        candidates=[n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
        if candidates:break
        d.swipe(350,1370,350,840,300)
    assert candidates,'No related video loaded'
    d.tap_node(candidates[0]);d.wait('正在观看');time.sleep(2)
    check('release opens related video on stack',bool(tagged('video_stack_depth_2')) and eventually(audio))
    d.back();time.sleep(1)
    check('release returns to previous video',bool(tagged('video_stack_depth_1')) and eventually(audio))
    d.tap('返回浏览页面');check('release home clears stack and stops audio',not tagged('video_stack_depth_') and not audio())
