"""Read-only account integration checks. Does not submit account mutations.

Requires logged-in emulator and the separate device-probe debug APK.
Screenshots remain under ignored .tools to protect account content.
"""
from device_driver import Device, ROOT
import json, re, time, sys, xml.etree.ElementTree as ET

d = Device()
checks = []
def check(name, value):
    checks.append({'check': name, 'passed': bool(value)})
    print(name, bool(value), flush=True)
    save()
    if not value: raise AssertionError(name)
def save():
    folder = ROOT / 'artifacts/device-tests'
    folder.mkdir(parents=True, exist_ok=True)
    (folder/'v3-results.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
def nodes(): return list(d.tree().iter('node'))
def has(label): return bool(d.find(label, contains=True))
def mark():
    r = ET.fromstring(d.adb('shell','run-as','app.biliflow','cat','shared_prefs/playback_progress.xml'))
    return max(json.loads(next(n.text for n in r if n.get('name') == 'marks')), key=lambda x:x['updated'])
def bounds():
    n = next(n for n in nodes() if n.get('resource-id') == 'video_player')
    return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def show():
    if not has('全屏'):
        b = bounds(); d.adb('shell','input','tap',str(b[0]+25),str((b[1]+b[3])//2))
def audio():
    pid = d.adb('shell','pidof','app.biliflow').strip()
    return any('AudioPlaybackConfiguration' in s and f'/{pid} state:started' in s for s in d.adb('shell','dumpsys','audio').splitlines())
def feed():
    for n in nodes():
        m = re.search(r'video_feed_page_(\d+)_count_(\d+)', n.get('resource-id',''))
        if m: return tuple(map(int,m.groups()))
def open_card():
    cards = [n for n in nodes() if n.get('clickable') == 'true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(cards[0]); d.wait('正在观看'); time.sleep(6)

if '--player' not in sys.argv:
    d.start(); time.sleep(2)
    if has('正在观看'): d.back()
    d.wait('视频')
    d.tap('视频'); time.sleep(1)
    initial = feed()
    for _ in range(5): d.swipe(360,1200,360,420,350)
    check('infinite recommendation pagination', feed()[0] > initial[0])
    check('chrome hides on downward scroll', not has('BiliFlow') and not d.find('视频'))
    d.swipe(360,600,360,900,300); d.wait('视频'); d.tap('视频'); time.sleep(1)
    check('repeated video tab returns to top', has('为你推荐'))
    d.tap('视频'); time.sleep(3)
    check('video tab at top refreshes first page', feed()[0] == 1)
    d.tap('动态'); d.wait('关注动态'); time.sleep(7)
    check('native dynamic feed', has('UP 主头像') and not any(n.get('class') == 'android.webkit.WebView' for n in nodes()))
    d.shot('dynamic-native')
    d.tap('直播'); d.wait('关注的直播'); time.sleep(5)
    check('followed live section above recommendations', has('关注的直播'))
    check('live recommendations load', has('正在发生的精彩'))
    d.shot('live-followed')
    d.tap('视频'); d.tap('热门'); time.sleep(4); open_card()
else:
    checks = json.loads((ROOT/'artifacts/device-tests/v3-results.json').read_text(encoding='utf-8'))
    checks = [c for c in checks if c['passed'] and c['check'] not in ['native playback has audio']]
check('native playback has audio', audio())
check('author avatar and fans', has('作者头像') and has('粉丝'))
check('published date displayed', any(re.search(r'\d{4}-\d{2}-\d{2}',n.get('text','')) for n in nodes()))
check('danmaku loaded', any(re.search(r'danmaku_overlay_count_[1-9]',n.get('resource-id','')) for n in nodes()))
d.tap('投币'); d.wait('给喜欢的内容投币'); check('coin amount confirmation opens', has('同时点赞')); d.tap('取消')
d.tap('账号收藏'); d.wait('收藏到账号收藏夹'); time.sleep(3)
check('account favorite folders loaded', has('个视频') and has('保存到所选收藏夹'))
d.shot('favorite-folders'); d.back()
show(); d.tap('暂停'); time.sleep(.6); check('pause control stops audio', not audio())
before = mark()['position']; show()
quality = [n for n in nodes() if n.get('clickable') == 'true' and any('自动' == c.get('text') or re.fullmatch(r'\d+P.*',c.get('text','')) for c in n.iter('node'))]
d.tap_node(quality[-1]); d.wait('清晰度')
for _ in range(4):
    if has('360P'): break
    d.swipe(360,1350,360,900,300)
d.tap('360P',contains=True); time.sleep(2)
check('quality switch retains pause', not audio())
check('quality switch retains position', abs(mark()['position']-before) < 1500)
show(); d.tap('播放'); time.sleep(2); check('resume control starts audio',audio())
b=bounds(); y=(b[1]+b[3])//2
for side, factor, sign in [('right',.85,1),('left',.15,-1)]:
    before=mark()['position']; x=int(b[0]+(b[2]-b[0])*factor)
    d.adb('shell','input','tap',str(x),str(y)); time.sleep(.09); d.adb('shell','input','tap',str(x),str(y)); time.sleep(.6)
    check('double tap '+side+' seeks ten seconds', 7000 < sign*(mark()['position']-before) < 16000)
show(); d.tap('全屏'); time.sleep(2)
if has('Got it'): d.tap('Got it')
b=bounds(); check('fullscreen player fills landscape', b[2]>b[3] and b[2]>1000)
check('fullscreen retains danmaku', any('danmaku_overlay_count_' in n.get('resource-id','') for n in nodes()))
d.shot('fullscreen-v3')
d.swipe(800,180,800,510,450); time.sleep(2)
check('downward player gesture exits fullscreen', bounds()[3]>bounds()[2] or bounds()[2]==720)
show(); d.tap('关闭弹幕'); check('danmaku toggle hides overlay', not any('danmaku_overlay_count_' in n.get('resource-id','') for n in nodes()))
show(); d.tap('开启弹幕')
d.adb('shell','input','keyevent','3'); time.sleep(2)
check('Home enters native PiP', bool(re.search(r'windowingMode=pinned|mode=pinned|mWindowingMode=2',d.adb('shell','dumpsys','activity','activities'),re.I)))
check('PiP keeps audio playing',audio()); d.shot('pip-v3')
d.start(); d.wait('正在观看'); check('return from PiP restores detail',has('正在观看'))
time.sleep(5); saved=mark()['position']; d.back(); open_card()
check('reopen restores playback position',mark()['position'] >= saved-1500)
for _ in range(4):
    if has('查看全部评论'): break
    d.swipe(360,1400,360,820,350)
d.tap('查看全部评论',contains=True); time.sleep(4)
check('native full comments loaded',has('评论者头像'))
d.shot('comments-native'); d.back()
d.probe('gesture',x=280,y=800,cancel='true'); time.sleep(.3)
check('predictive gesture cancellation retains detail',has('正在观看'))
d.probe('gesture',x=280,y=800); time.sleep(.5)
check('predictive gesture commit returns to feed',feed() is not None)
check('no fatal exception', 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
print('Passed',len(checks),'checks',flush=True)
