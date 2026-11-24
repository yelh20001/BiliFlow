"""Verify the reported video with Dolby preferred; no account actions are submitted."""
from device_driver import Device, ROOT
import json, time

d = Device()
results = []
def check(name, value):
    results.append({'check': name, 'passed': bool(value)})
    (ROOT / 'artifacts/v15-device-results.json').write_text(json.dumps(results, indent=2))
    print(name, bool(value), flush=True)
    assert value, name

def set_quality(label):
    d.wait('外观设置'); d.tap('外观设置')
    found = []
    for _ in range(5):
        found = [n for n in d.tree().iter('node') if n.get('content-desc','').startswith('首选清晰度：') or n.get('text','').startswith('首选清晰度：')]
        if found: break
        d.swipe(350,1200,350,650,300)
    assert found
    d.tap_node(found[0])
    d.shot('v15-quality-menu')
    d.wait(label); d.tap(label); d.back()

try:
    d.adb('shell','am','force-stop','app.biliflow'); d.start()
    set_quality('杜比视界')
    d.tap('我的')
    for _ in range(6):
        if d.find('观看历史'): break
        d.swipe(350,1250,350,650,300)
    d.tap('观看历史')
    found = []
    for _ in range(30):
        found = [n for n in d.tree().iter('node') if n.get('clickable') == 'true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
        if found: break
        time.sleep(.5)
    check('reported BV is available in the seeded local history', bool(found))
    d.tap_node(found[0]); d.wait('正在观看')
    state = ''
    for _ in range(30):
        state = d.adb('shell','dumpsys','activity','top')
        if 'playWhenReady=true' in state and 'videoSize=0x0' not in state: break
        time.sleep(.5)
    d.shot('v15-reported-video')
    print(state.split('BiliFlowPlayback')[-1][:600], flush=True)
    print([n.get('text') for n in d.tree().iter('node') if any(word in n.get('text','') for word in ('播放失败', '解码', '重试', '网络', '获取'))], flush=True)
    check('Dolby preferred video reaches decoded playback on test output', 'playWhenReady=true' in state and 'videoSize=0x0' not in state)
    check('premature display-brand rejection is removed', not d.find('当前设备无法输出所选 HDR 格式', contains=True))
    d.shot('v15-reported-video')
    check('no fatal exception', 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
    d.tap('返回浏览页面'); d.wait('外观设置'); set_quality('自动')
finally:
    d.adb('shell','am','force-stop','app.biliflow')
