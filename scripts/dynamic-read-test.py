"""Read-only feed checks except for the requested read-state sync of one visited author.

The chosen author's ID and content remain in memory; output contains only pass/fail.
"""
from device_driver import Device
import time
d=Device()
def nodes(): return list(d.tree().iter('node'))
def tags(prefix): return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def updated(node): return any(n.get('resource-id')=='author_update_dot' for n in node.iter('node'))
def wait(fn, seconds=45):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        v=fn()
        if v:return v
        time.sleep(.4)
    raise AssertionError('Timed out waiting for dynamic screen')
def target(id): return next((n for n in tags('followed_author_') if n.get('resource-id')==id), None)
try:
    d.start(); d.wait('动态'); d.tap('动态'); d.wait('关注动态'); wait(lambda:tags('dynamic_card_'))
    authors=wait(lambda:tags('followed_author_'))
    unread=next((a for a in authors if updated(a)),None)
    if unread is None: raise AssertionError('No visible unread author available')
    author_id=unread.get('resource-id')
    d.tap_node(unread); wait(lambda:d.find('的动态',contains=True))
    def read_complete():
        if d.find('已读状态同步失败',contains=True): raise AssertionError('Server read-state sync failed')
        a=target(author_id)
        return a is not None and not updated(a) and bool(tags('dynamic_card_'))
    wait(read_complete)
    print('viewing author clears update dot after server acknowledgement: PASS',flush=True)
    d.tap('全部关注'); wait(lambda:d.find('关注动态')); time.sleep(3)
    a=target(author_id)
    if a is None:
        for _ in range(12):
            d.swipe(620,360,160,360,350); a=target(author_id)
            if a is not None: break
    assert a is not None, 'author no longer visible in refreshed list'
    assert not updated(a), 'server refresh returned author as unread'
    print('read state persists after reloading app feed: PASS',flush=True)
    # Move the timeline off its top, then reveal the immersive bottom bar.
    for _ in range(3): d.swipe(350,1300,350,650,350)
    if not d.find('动态'): d.swipe(350,700,350,1050,350)
    d.wait('动态'); before=tags('dynamic_list_request_')[0].get('resource-id')
    d.tap('动态'); wait(lambda:tags('followed_authors_count_'))
    after_scroll=tags('dynamic_list_request_')[0].get('resource-id')
    assert after_scroll == before, 'scroll-to-top should not refresh'
    print('dynamic tab scrolls to top: PASS',flush=True)
    d.tap('动态'); wait(lambda:tags('dynamic_list_request_')[0].get('resource-id') != after_scroll)
    print('dynamic tab refreshes when already at top: PASS',flush=True)
    assert 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E')
    print('no fatal exception: PASS',flush=True)
finally:
    if d.find('返回动态'): d.tap('返回动态')
