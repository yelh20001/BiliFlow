"""Read-only mini gesture cancellation and fast swipe checks."""
from device_driver import Device
import re, time
d = Device()
def nodes(): return list(d.tree().iter('node'))
def tag(s): return [n for n in nodes() if n.get('resource-id') == s]
def rect(n): return list(map(int, re.findall(r'-?\d+', n.get('bounds'))))
try:
    d.start(); d.wait('视频'); d.tap('视频'); d.tap('热门'); time.sleep(3)
    cards = [n for n in nodes() if n.get('clickable') == 'true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(cards[0]); d.wait('正在观看'); time.sleep(2)
    b = rect(tag('video_player')[0]); x = (b[0] + b[2]) // 2; y = b[1] + 80
    d.swipe(x, y, x, y + 55, 350); time.sleep(.6)
    assert d.find('正在观看') and not tag('in_app_mini_player')
    print('short downward drag returns to detail: PASS', flush=True)
    d.swipe(x, y, x, y + 200, 100); d.wait('关闭小窗'); time.sleep(.5)
    assert tag('in_app_mini_player')
    print('fast downward drag opens mini: PASS', flush=True)
    d.tap('关闭小窗')
    assert 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E')
    print('no fatal exception: PASS', flush=True)
finally:
    if d.find('关闭小窗'): d.tap('关闭小窗')
    if d.find('返回浏览页面'): d.tap('返回浏览页面')
