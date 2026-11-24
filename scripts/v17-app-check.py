"""Check fixed glass capture bounds across real home scrolling and search expansion."""
from device_driver import Device, ROOT
import json, time
d=Device(); results=[]
def check(name,value):
    results.append({'check':name,'passed':bool(value)})
    (ROOT/'artifacts/v17-app-results.json').write_text(json.dumps(results,indent=2))
    print(name,bool(value),flush=True); assert value,name
def blur_bounds():
    found=[n for n in d.tree().iter('node') if n.get('resource-id')=='home_top_bar_blur']
    return found[0].get('bounds') if found else None
try:
    d.adb('shell','am','force-stop','app.biliflow'); d.start(); d.wait('搜索视频'); time.sleep(.5)
    original=blur_bounds(); check('glass is a separate fixed backdrop',original is not None)
    d.tap('热门'); time.sleep(2)
    for _ in range(2): d.swipe(350,1300,350,700,450)
    d.swipe(350,650,350,1150,500); d.wait('搜索视频'); time.sleep(.4)
    check('scroll hide and reveal preserve blur capture bounds',blur_bounds()==original)
    d.tap('搜索视频'); d.wait('搜索视频、UP 主或 BV 号'); time.sleep(.4)
    check('search expansion does not resize the capture layer',blur_bounds()==original)
    d.tap('搜索视频'); time.sleep(.4)
    check('search collapse retains the same capture layer',blur_bounds()==original)
    for label in ('我的','动态','视频'):
        d.wait(label); d.tap(label); time.sleep(.5)
        check(label+' tab retains the fixed backdrop',blur_bounds()==original)
    check('no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:
    d.adb('shell','am','force-stop','app.biliflow')
