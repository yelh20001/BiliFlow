"""HDR settings, animated comments, nested mini return and tablet layout regression."""
from device_driver import Device, ROOT
import io, json, re, sys, time
sys.path.insert(0, str(ROOT / '.tools/python-libs'))
from PIL import Image

d = Device()
results = []

def check(name, value):
    results.append({'check': name, 'passed': bool(value)})
    print(name, bool(value), flush=True)
    report = 'v13-comment-results.json' if '--fixture-only' in sys.argv else 'v13-device-results.json'
    (ROOT / 'artifacts' / report).write_text(json.dumps(results, indent=2))
    assert value, name

def nodes(): return list(d.tree().iter('node'))
def tag(name): return [n for n in nodes() if n.get('resource-id') == name]
def bounds(n): return list(map(int, re.findall(r'-?\d+', n.get('bounds'))))
def cards(): return [n for n in nodes() if n.get('clickable') == 'true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
def wait_tag(name):
    for _ in range(35):
        found = tag(name)
        if found: return found
        time.sleep(.5)
    raise AssertionError(name)

def hdr_toggle():
    for _ in range(5):
        found = tag('interface_hdr')
        if found: return found[0]
        labels = d.find('界面 HDR 显示')
        if labels:
            y = bounds(labels[0])[1]
            switches = [n for n in nodes() if n.get('checkable') == 'true']
            if switches: return min(switches, key=lambda n: abs(bounds(n)[1] - y))
        d.swipe(350,1200,350,650,300)
    raise AssertionError('HDR switch is not visible')

def open_video():
    d.tap('热门'); time.sleep(3)
    for _ in range(10):
        found = cards()
        if found: break
        time.sleep(1)
    d.tap_node(found[0]); d.wait('正在观看'); time.sleep(2)

def comment_fixture():
    d.adb('shell','am','force-stop','app.biliflow')
    d.adb('shell','am','start','-n','app.biliflow/.CommentPictureFixtureActivity')
    first = wait_tag('comment_picture_loaded_0')[0]
    d.tap_node(first); d.wait('关闭评论图片')
    colors = set()
    for _ in range(6):
        im = Image.open(io.BytesIO(d.adb('exec-out','screencap','-p',binary=True))).convert('RGB')
        rgb = im.getpixel((im.width//2, im.height//2))
        colors.add('red' if rgb[0] > rgb[2] else 'blue')
        time.sleep(.2)
    check('GIF displays multiple animation frames', len(colors) == 2)
    d.swipe(600,700,100,700,300); time.sleep(.5)
    check('one finger swipe moves to second picture', bool(d.find('2 / 2')))
    d.swipe(100,700,600,700,300); time.sleep(.5)
    check('reverse swipe returns to first picture', bool(d.find('1 / 2')))
    d.tap('关闭评论图片')

try:
    if '--release' not in sys.argv: comment_fixture()
    if '--fixture-only' in sys.argv: sys.exit(0)
    d.adb('shell','am','force-stop','app.biliflow'); d.start(); d.wait('视频')
    check('top bar subtitle removed', not d.find('你的兴趣，在这里相遇'))
    d.tap('外观设置'); d.wait('界面 HDR 显示')
    toggle = hdr_toggle()
    if toggle.get('checked') != 'true': d.tap_node(toggle)
    time.sleep(.5)
    d.adb('shell','am','force-stop','app.biliflow'); d.start(); d.wait('视频'); d.tap('外观设置')
    check('HDR interface switch persists after restart', hdr_toggle().get('checked') == 'true')
    d.back(); open_video()
    for depth in (2, 3):
        related = []
        for _ in range(12):
            related = cards()
            if related: break
            d.swipe(350,1370,350,840,300)
        d.tap_node(related[0]); wait_tag('video_stack_depth_' + str(depth)); time.sleep(1)
    p = bounds(tag('video_player')[0]); x = (p[0]+p[2])//2
    d.shot('v13-before-nested-drag')
    print('nested player bounds', p, flush=True)
    d.probe('dragframes', x=x, **{'from':p[1]+60, 'to':p[3]-25})
    d.shot('v13-after-nested-drag')
    print(d.adb('shell','dumpsys','activity','top').split('BiliFlowPlayback')[-1][:650], flush=True)
    d.wait('关闭小窗')
    check('nested downward swipe exposes video feed and mini', bool(tag('in_app_mini_player')) and bool(d.find('热门')))
    d.tap('视频')
    check('feed navigation remains interactive below nested mini', bool(tag('in_app_mini_player')))
    d.tap('展开小窗'); wait_tag('video_stack_depth_3')
    d.tap('返回'); wait_tag('video_stack_depth_2')
    check('restored nested video returns to preceding video', bool(d.find('正在观看')))
    d.tap('返回浏览页面'); d.wait('视频')
    d.adb('shell','wm','size','1280x800'); d.adb('shell','wm','density','160'); time.sleep(3)
    d.adb('shell','am','force-stop','app.biliflow'); d.start(); d.wait('视频')
    d.tap('热门'); time.sleep(3)
    visible = cards()
    check('tablet feed displays at least three card columns', len({bounds(n)[0] for n in visible}) >= 3)
    d.shot('v13-tablet-feed')
    d.tap_node(visible[0]); d.wait('正在观看'); time.sleep(3)
    p = bounds(tag('video_player')[0]); tabs = d.find('简介与推荐')
    check('tablet detail places tabs to the right of player', bool(tabs) and bounds(tabs[0])[0] >= p[2])
    d.shot('v13-tablet-detail')
    d.tap('评论', contains=True); time.sleep(1)
    p2 = bounds(tag('video_player')[0])
    check('tablet comments preserve player bounds', p == p2)
    check('no fatal exception', 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:
    d.adb('shell','wm','size','reset'); d.adb('shell','wm','density','reset')
    d.adb('shell','am','force-stop','app.biliflow')
