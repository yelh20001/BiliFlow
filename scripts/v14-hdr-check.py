"""Read-only HDR control rendering and live toggle regression; does not write account data."""
from device_driver import Device, ROOT
import json, re, time

d = Device()
results = []

def check(name, value):
    results.append({'check': name, 'passed': bool(value)})
    (ROOT / 'artifacts/v14-hdr-results.json').write_text(json.dumps(results, indent=2))
    print(name, bool(value), flush=True)
    assert value, name

def nodes(): return list(d.tree().iter('node'))
def rect(node): return list(map(int, re.findall(r'-?\d+', node.get('bounds'))))

def switch_to(enabled):
    d.wait('外观设置'); d.tap('外观设置'); label = d.wait('界面 HDR 显示')[0]
    switches = [n for n in nodes() if n.get('checkable') == 'true']
    toggle = min(switches, key=lambda n: abs(rect(n)[1] - rect(label)[1]))
    if (toggle.get('checked') == 'true') != enabled: d.tap_node(toggle)
    time.sleep(.4)
    d.back(); d.wait('外观设置')

try:
    d.adb('shell','am','force-stop','app.biliflow'); d.start(); d.wait('外观设置')
    switch_to(False)
    nav = [n for n in nodes() if n.get('text') == '视频']
    check('HDR off restores navigation text rendering', bool(nav))
    check('SDR toolbar icon descriptions remain accessible', bool(d.find('搜索视频')))
    d.shot('v14-hdr-off')
    switch_to(True)
    nav = [n for n in nodes() if n.get('content-desc') == '视频']
    check('HDR on uses gainmap navigation label', bool(nav))
    check('HDR toolbar icons remain accessible', bool(d.find('搜索视频')))
    d.shot('v14-hdr-on')
    d.tap('我的'); d.wait('我的收藏')
    check('HDR navigation remains interactive', bool(d.find('我的收藏')))
    d.tap('视频'); d.wait('外观设置')
    d.adb('shell','am','force-stop','app.biliflow'); d.start(); d.wait('外观设置')
    check('HDR flag survives process restart', any(n.get('content-desc') == '视频' for n in nodes()))
    switch_to(False)
    check('all controls switch back without restarting', any(n.get('text') == '视频' for n in nodes()))
    switch_to(True)
    check('no fatal exception', 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:
    d.adb('shell','am','force-stop','app.biliflow')
