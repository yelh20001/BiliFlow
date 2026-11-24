from device_driver import Device, ROOT
import json,re,time
d=Device();results=[]
def nodes():return list(d.tree().iter('node'))
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def find(tag):
    for _ in range(12):
        hits=[n for n in nodes() if n.get('resource-id')==tag]
        if hits:return hits[0]
        d.swipe(350,1250,350,700,400)
    raise AssertionError(tag)
def label(prefix):return next(n.get('text') for n in nodes() if n.get('text','').startswith(prefix+' ·'))
def check(name,value):
    results.append(dict(check=name,passed=bool(value)));print(name,bool(value),flush=True)
    (ROOT/'artifacts/v23-settings-results.json').write_text(json.dumps(results,indent=2));assert value,name
try:
    d.adb('shell','am','force-stop','app.biliflow');d.start();d.wait('外观设置');d.tap('外观设置')
    values={}
    for tag,title in [('dm_opacity','透明度'),('dm_outline','描边粗细'),('dm_font','字体大小'),('dm_speed','弹幕速度'),('dm_area','显示区域')]:
        n=find(tag);a,b,c,e=rect(n);d.adb('shell','input','tap',str(int(a+(c-a)*.7)),str((b+e)//2));time.sleep(.3)
        values[tag]=label(title)
    for tag in ['dm_scrolling','dm_top']:
        n=find(tag)
        if n.get('checked')=='true':d.tap_node(n)
    n=find('dm_bottom')
    if n.get('checked')=='true':d.tap_node(n)
    check('all five style controls and independent bottom switch are available',len(values)==5)
    d.adb('shell','am','force-stop','app.biliflow');d.start();d.wait('外观设置');d.tap('外观设置')
    for tag,title in [('dm_opacity','透明度'),('dm_outline','描边粗细'),('dm_font','字体大小'),('dm_speed','弹幕速度'),('dm_area','显示区域')]:
        find(tag);check(tag+' persists after restart',label(title)==values[tag])
    for tag in ['dm_scrolling','dm_top','dm_bottom']:
        check(tag+' switch persists independently',find(tag).get('checked')=='false')
    for _ in range(4):
        if d.find('恢复默认弹幕样式'):break
        d.swipe(350,1250,350,900,350)
    d.wait('恢复默认弹幕样式');d.tap('恢复默认弹幕样式')
    check('restore defaults enables bottom danmaku',find('dm_bottom').get('checked')=='true')
finally:d.adb('shell','am','force-stop','app.biliflow')
