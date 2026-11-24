"""Read-only account dynamic UI checks; no author IDs or content are recorded."""
from device_driver import Device
import time
d = Device()
def nodes(): return list(d.tree().iter('node'))
def tags(prefix): return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def wait_for(fn, timeout=45):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(.5)
    raise AssertionError('Timed out waiting for dynamic data')
try:
    d.start(); d.wait('动态'); d.tap('动态'); d.wait('关注动态')
    wait_for(lambda: tags('dynamic_card_'))
    authors=wait_for(lambda: tags('followed_author_'))
    print('app dynamic feed and author list visible: PASS', flush=True)
    d.tap_node(authors[0])
    wait_for(lambda: d.find('的动态', contains=True))
    wait_for(lambda: tags('dynamic_card_') or d.find('暂时没有新动态') or d.find('重试'))
    assert not d.find('重试')
    print('single author app dynamic opens: PASS', flush=True)
    if d.find('全部关注'): d.tap('全部关注')
    else:
        d.swipe(350, 600, 350, 1350, 400)
        d.tap('全部')
    wait_for(lambda: d.find('关注动态'))
    print('return to all dynamics: PASS', flush=True)
    assert 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E')
    print('no fatal exception: PASS', flush=True)
finally:
    if d.find('返回动态'): d.tap('返回动态')
