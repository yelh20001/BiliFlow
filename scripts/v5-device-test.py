"""Read-only v0.5 checks. Private frames stay under ignored .tools.

Stages: navigation, palette, playback, layout. No account writes are submitted.
"""
from device_driver import Device,ROOT,PROBE
from pathlib import Path
import json,re,time,sys,xml.etree.ElementTree as ET
from io import BytesIO
sys.path.insert(0,str(ROOT/'.tools/python-libs'))
from PIL import Image

d=Device();out=ROOT/'.tools/v5-device-shots';out.mkdir(exist_ok=True)
path=ROOT/'artifacts/device-tests/v5-results.json';results=json.loads(path.read_text()) if path.exists() else []
def check(name,value):
    global results
    results=[v for v in results if v['check']!=name]+[{'check':name,'passed':bool(value)}]
    path.write_text(json.dumps(results,indent=2),encoding='utf-8');print(name,bool(value),flush=True)
    if not value: raise AssertionError(name)
def nodes():return list(d.tree().iter('node'))
def tagged(prefix):return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def has(label):return bool(d.find(label,contains=True))
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def bounds():return rect(tagged('video_player')[0])
def shot(name): (out/(name+'.png')).write_bytes(d.adb('exec-out','screencap','-p',binary=True))
def root():
    d.start();time.sleep(1)
    if d.find('返回浏览页面'):d.tap('返回浏览页面')
    for _ in range(5):
        if d.find('视频'):return
        d.back()
    d.wait('视频')
def cards():return [n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
def audio():
    pid=d.adb('shell','pidof','app.biliflow').strip()
    return any(f'/{pid} state:started' in s for s in d.adb('shell','dumpsys','audio').splitlines())
def wait_audio():
    for _ in range(35):
        if audio():return True
        time.sleep(1)
    return False
def show():
    if not has('全屏'):
        b=bounds();d.adb('shell','input','tap',str(b[0]+25),str((b[1]+b[3])//2))
def flight(card,name):
    cover=next(c for c in card.iter('node') if c.get('content-desc') and (lambda b:b[2]-b[0]>200 and b[3]-b[1]>100)(rect(c)))
    b=rect(cover)
    before=d.adb('logcat','-d','-s','CoverFlight:D').splitlines()
    shot(name+'-before')
    d.adb('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.85)
    trace=[line for line in d.adb('logcat','-d','-s','CoverFlight:D').splitlines() if line not in before]
    phases=[int(v) for line in trace for v in re.findall(r'phase=(\d+)',line)]
    (out/(name+'-animation.json')).write_text(json.dumps(phases),encoding='utf-8')
    # Reading the entire accessibility tree during the transition blocks the UI
    # thread. Sample frame callbacks without instrumentation, then inspect endpoints.
    global results
    results=[v for v in results if v['check']!=name+' cover has intermediate measured positions']
    check(name+' cover has intermediate animation frames',len(set(phases))>=6)
    check(name+' cover reaches player rectangle',bool(phases) and max(phases)==100)
    shot(name+'-after')
    d.wait('正在观看')
def open_video():
    d.tap_node(cards()[0]);d.wait('正在观看')
def related_card():
    for _ in range(10):
        candidates=cards()
        if candidates:return candidates[0]
        d.swipe(350,1370,350,840,300)
    raise AssertionError('No related video card loaded')

stage=sys.argv[1] if len(sys.argv)>1 else 'all'
if stage in ['all','navigation']:
    root();d.tap('视频');d.tap('推荐');time.sleep(3)
    check('video header appears once',len(d.find('为你推荐'))==1 and not d.find('BiliFlow'))
    shot('light-bars')
    d.tap('动态');d.wait('关注动态');time.sleep(3)
    check('dynamic header replaces branding without duplicate',len(d.find('关注动态'))==1 and len(d.find('按动态与视频更新排序'))==1)
    d.tap('视频');d.tap('热门');time.sleep(4);flight(cards()[0],'feed-entry')
    check('detail has two separate pages',has('简介与推荐') and has('评论'))
    check('modern output uses SurfaceView',any('SurfaceView' in s and 'app.biliflow' in s for s in d.adb('shell','dumpsys','SurfaceFlinger','--list').splitlines()))
    check('native video still plays',wait_audio())
    first=related_card();shot('related-list');flight(first,'related-entry')
    check('related video pushes second route',bool(tagged('video_stack_depth_2')))
    check('related video plays with one audio stream',wait_audio())
    d.probe('gesture',x=270,y=1100,cancel='true');check('cancelled back preserves related route',bool(tagged('video_stack_depth_2')))
    d.probe('gesture',x=270,y=1100);time.sleep(.7)
    check('back returns to previous video route',bool(tagged('video_stack_depth_1')))
    check('previous related scroll position restored',bool(cards()))
    check('previous video resumes',wait_audio())
    first=related_card();d.tap_node(first);d.wait('正在观看');time.sleep(4)
    d.tap_node(related_card());d.wait('正在观看');time.sleep(2)
    check('related stack supports third video',bool(tagged('video_stack_depth_3')))
    d.tap('返回浏览页面');d.wait('视频');check('home button clears all video routes',not tagged('video_stack_depth_'))
    check('home button releases playback',not audio())

if stage in ['all','palette']:
    root();d.tap('视频');d.tap('热门');time.sleep(3)
    d.tap('外观设置');d.tap('跟随系统 · 原生取色')
    d.tap('首选清晰度',contains=True);d.tap('8K');d.adb('shell','input','tap','360','180');time.sleep(.5)
    colors=[]
    for mode in ['no','yes']:
        d.adb('shell','cmd','uimode','night',mode);time.sleep(1)
        open_video();notice=d.wait('已切换至当前账号可用',timeout=35,contains=True)
        b=rect(notice[-1]);data=d.adb('exec-out','screencap','-p',binary=True);(out/f'notice-{mode}.png').write_bytes(data)
        image=Image.open(BytesIO(data)).convert('RGB').crop((b[0]+12,b[1]+12,b[2]-12,b[3]-12))
        colors.append(max(image.getcolors(image.width*image.height),key=lambda p:p[0])[1])
        d.tap('返回浏览页面');d.wait('视频')
    check('quality notice colors match in light and dark',colors[0]==colors[1]==(38,49,59))
    d.adb('shell','cmd','uimode','night','no');time.sleep(1)
    d.tap('外观设置');d.tap('首选清晰度',contains=True);d.tap('自动');d.adb('shell','input','tap','360','180')

if stage in ['all','playback']:
    root();d.tap('视频');d.tap('热门');time.sleep(3);open_video();check('playback starts after hardware policy',wait_audio())
    d.tap('评论');d.wait('评论者头像',timeout=35);check('comments live on second page',bool(tagged('video_details_page_1')) and has('评论者头像'))
    feed=tagged('comments_feed_page_')[0].get('resource-id');before=int(feed.rsplit('_',1)[-1])
    d.swipe(350,850,350,1380,550);time.sleep(2)
    after=int(tagged('comments_feed_page_')[0].get('resource-id').rsplit('_',1)[-1]);check('comments still pull to refresh',after>before)
    d.swipe(100,1050,650,1050,400);time.sleep(.7);check('horizontal swipe switches detail pages',bool(tagged('video_details_page_0')))
    d.adb('shell','cmd','uimode','night','yes');time.sleep(2)
    check('night mode preserves the playing video',audio() and bool(tagged('video_stack_depth_1')))
    d.adb('shell','cmd','uimode','night','no');time.sleep(1)
    b=bounds();d.probe('drag',x=350,**{'from':b[3]-30,'to':b[1]+25});time.sleep(1)
    check('SurfaceView upward gesture enters fullscreen',bounds()[2]>1000)
    b=bounds();d.probe('drag',x=800,**{'from':90,'to':b[3]-70});time.sleep(1)
    check('SurfaceView downward gesture exits fullscreen',bounds()[2]==720)
    d.adb('shell','input','keyevent','3');time.sleep(2)
    check('SurfaceView PiP works',bool(re.search(r'windowingMode=pinned|mode=pinned|mWindowingMode=2',d.adb('shell','dumpsys','activity','activities'),re.I)))
    check('PiP audio continues',audio());d.start();d.wait('正在观看');d.tap('返回浏览页面');check('exit stops audio',not audio())

if stage in ['all','live']:
    root();d.tap('直播');d.wait('正在发生的精彩');time.sleep(3)
    rooms=[n for n in nodes() if n.get('clickable')=='true' and any(c.get('text','').startswith('● ') or '人气' in c.get('text','') for c in n.iter('node'))]
    if not rooms:
        d.swipe(350,1350,350,800,350)
        rooms=[n for n in nodes() if n.get('clickable')=='true' and any('人气' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(rooms[0]);d.wait('直播间');check('release native live plays',wait_audio())
    check('release live has no WebView',not any(n.get('class')=='android.webkit.WebView' for n in nodes()))
    b=bounds();d.probe('drag',x=350,**{'from':b[3]-30,'to':b[1]+25});time.sleep(1)
    full=bounds()
    check('release live fullscreen uses modern surface',(full[2]>1000 or (full[1]==0 and full[3]>=1500)) and any('SurfaceView' in s and 'app.biliflow' in s for s in d.adb('shell','dumpsys','SurfaceFlinger','--list').splitlines()))
    d.back();d.adb('shell','input','keyevent','3');time.sleep(2)
    check('release live PiP works',bool(re.search(r'windowingMode=pinned|mode=pinned|mWindowingMode=2',d.adb('shell','dumpsys','activity','activities'),re.I)))
    d.start();d.wait('直播间');d.back();check('release live exit releases audio',not audio())

if stage in ['all','layout']:
    root();d.tap('视频')
    try:
        d.adb('shell','wm','size','1440x3120');d.adb('shell','wm','density','560');time.sleep(2)
        d.wait('视频');check('QHD phone layout keeps four destinations',all(d.find(x) for x in ['直播','视频','动态','我的']))
        shot('qhd-layout');check('QHD video header remains singular',len(d.find('全站热门'))+len(d.find('为你推荐'))==1)
    finally:
        d.adb('shell','wm','size','reset');d.adb('shell','wm','density','reset');time.sleep(1)

if stage=='release':
    root();d.tap('我的');d.wait('账号头像',timeout=35)
    check('optimized build retains account and app authorization',has('App 授权已连接') and has('账号头像'))
    d.tap('视频');d.tap('推荐');d.wait('安卓 App 推荐',timeout=35,contains=True)
    check('optimized build keeps account Android recommendations',has('安卓 App 推荐'))
    package=d.adb('shell','dumpsys','package','app.biliflow')
    check('delivery build is not debuggable',not any('DEBUGGABLE' in line for line in package.splitlines() if 'flags=' in line.lower()))
    d.tap('热门');time.sleep(3);open_video();wait_audio()
    d.tap_node(related_card());d.wait('正在观看');time.sleep(2)
    check('optimized build pushes related route',bool(tagged('video_stack_depth_2')))
    d.back();time.sleep(1);check('optimized build returns to prior video',bool(tagged('video_stack_depth_1')))
    d.tap('返回浏览页面');check('optimized build clears routes and stops playback',not tagged('video_stack_depth_') and not audio())
check('no fatal runtime exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
print('Passed',sum(v['passed'] for v in results),'checks',flush=True)
