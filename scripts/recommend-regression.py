"""Read-only regression for successive related videos and the home animation."""
from device_driver import Device, ROOT, PROBE
import re, time

d = Device()
def nodes(): return list(d.tree().iter('node'))
def tag(name): return [n for n in nodes() if n.get('resource-id') == name]
def bounds(node): return list(map(int, re.findall(r'-?\d+', node.get('bounds'))))
def cards(): return [n for n in nodes() if n.get('clickable') == 'true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
def related():
    for _ in range(12):
        matches = cards()
        if matches: return matches[0]
        d.swipe(350, 1370, 350, 840, 300)
    raise AssertionError('No related video')
def wait_tag(name):
    end = time.monotonic() + 35
    while time.monotonic() < end:
        if tag(name): return
        time.sleep(.4)
    raise AssertionError('Missing route ' + name)
try:
    d.start(); d.wait('视频'); d.tap('视频'); d.tap('热门'); time.sleep(3)
    d.tap_node(cards()[0]); d.wait('正在观看'); wait_tag('video_stack_depth_1')
    for depth in (2, 3):
        d.tap_node(related()); wait_tag('video_stack_depth_' + str(depth)); time.sleep(1)
        print('related video depth', depth, 'PASS', flush=True)
    button = d.wait('返回浏览页面')[0]; a,b,c,e = bounds(button)
    d.probe('tapframes', x=(a+c)//2, y=(b+e)//2)
    frames = ROOT / '.tools/recommend-return-frames'; frames.mkdir(parents=True, exist_ok=True)
    for i in range(6): (frames / f'{i}.png').write_bytes(d.adb('exec-out','cat',PROBE+f'entry-frame-{i}.png',binary=True))
    d.wait('视频'); assert not tag('video_stack_depth_3')
    state = d.adb('shell','dumpsys','activity','top')
    assert 'playWhenReady=false' in state
    assert 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E')
    print('animated return clears stack and stops playback PASS', flush=True)
finally:
    if d.find('返回浏览页面'): d.tap('返回浏览页面')
