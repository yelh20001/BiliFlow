"""Verify reselecting the dynamic tab scrolls to top, then refreshes."""
from device_driver import Device
import time, re
d=Device()
def nodes(): return list(d.tree().iter('node'))
def tags(prefix): return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def wait(fn, seconds=45):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(.4)
    raise AssertionError('Timed out waiting for dynamic screen')
try:
    d.start(); d.wait('动态'); d.tap('动态'); d.wait('关注动态'); wait(lambda: tags('dynamic_card_'))
    first=tags('dynamic_card_')[0]
    first_id=first.get('resource-id')
    first_y=int(re.findall(r'-?\d+',first.get('bounds'))[1])
    for _ in range(3): d.swipe(350,1300,350,650,350)
    if not d.find('动态'): d.swipe(350,700,350,1050,350)
    d.wait('动态'); time.sleep(2)
    before=wait(lambda: tags('dynamic_list_request_'))[0].get('resource-id')
    d.tap('动态')
    wait(lambda:any(n.get('resource-id')==first_id and abs(int(re.findall(r'-?\d+',n.get('bounds'))[1])-first_y)<3 for n in tags('dynamic_card_')))
    after=wait(lambda: tags('dynamic_list_request_'))[0].get('resource-id')
    assert after==before, 'scrolling to top unexpectedly refreshed'
    print('dynamic tab scrolls to top: PASS',flush=True)
    d.tap('动态'); wait(lambda:tags('dynamic_list_request_')[0].get('resource-id') != after)
    print('dynamic tab refreshes when already at top: PASS',flush=True)
    assert 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E')
    print('no fatal exception: PASS',flush=True)
finally:
    if d.find('返回动态'): d.tap('返回动态')
