"""Read-only checks for account library screens; never records account data."""
from device_driver import Device
import time
d = Device()
def tags(): return [n.get('resource-id','') for n in d.tree().iter('node')]
try:
    d.start(); d.wait('我的', timeout=60); d.tap('我的'); time.sleep(1)
    for title in ('账号收藏夹', '已点赞的视频', '已投币的视频'):
        for _ in range(4):
            if d.find(title): break
            d.swipe(350, 1300, 350, 750)
        d.tap(title); d.wait(title)
        end=time.monotonic()+40
        while time.monotonic()<end:
            state=tags()
            if any(t.startswith('account_library_loaded_') or t == 'account_library_error' for t in state): break
            time.sleep(.5)
        loaded=next((t for t in state if t.startswith('account_library_loaded_')), None)
        print(title, loaded or ('ERROR' if 'account_library_error' in state else 'TIMEOUT'), flush=True)
        if title == '账号收藏夹' and loaded and d.find('打开'):
            d.tap('打开'); end=time.monotonic()+40
            while time.monotonic()<end:
                state=tags()
                if any(t.startswith('account_library_loaded_') or t == 'account_library_error' for t in state): break
                time.sleep(.5)
            print('收藏夹内容', next((t for t in state if t.startswith('account_library_loaded_')), 'ERROR or TIMEOUT'), flush=True)
            d.tap('返回收藏夹', contains=True)
        if not loaded:
            for n in d.tree().iter('node'):
                value=n.get('text','')
                if any(x in value for x in ('HTTP','请求','授权','登录','限制')): print('status:', value, flush=True)
        d.back(); time.sleep(1)
finally:
    if d.find('返回'): d.back()
