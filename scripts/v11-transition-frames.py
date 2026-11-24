"""Capture frames of the in-app mini-player handoff on a logged-in emulator."""
from device_driver import Device, ROOT, PROBE
import re, time
d = Device()
def nodes(): return list(d.tree().iter('node'))
try:
    d.start(); d.wait('视频'); d.tap('视频'); d.tap('热门'); time.sleep(3)
    cards = [n for n in nodes() if n.get('clickable') == 'true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(cards[0]); d.wait('正在观看'); time.sleep(4)
    player = [n for n in nodes() if n.get('resource-id') == 'video_player'][0]
    a,b,c,e = map(int, re.findall(r'-?\d+', player.get('bounds')))
    d.probe('dragframes', x=(a+c)//2, **{'from':b+70, 'to':e-25})
    folder = ROOT / '.tools/v11-mini-frames'; folder.mkdir(parents=True, exist_ok=True)
    for i in range(24): (folder / f'{i}.png').write_bytes(d.adb('exec-out','cat',PROBE+f'drag-fast-{i}.png',binary=True))
    d.wait('关闭小窗'); print('mini transition captured', flush=True)
finally:
    if d.find('关闭小窗'): d.tap('关闭小窗')
    if d.find('返回浏览页面'): d.tap('返回浏览页面')
