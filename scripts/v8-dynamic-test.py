"""Native dynamic navigation; no account interactions are submitted."""
from device_driver import Device,ROOT
import re,json,time,sys
d=Device();path=ROOT/'artifacts/device-tests/v8-dynamic-results.json';results=json.loads(path.read_text()) if path.exists() else []
def check(name,value):
    results[:]=[r for r in results if r['check']!=name];results.append({'check':name,'passed':bool(value)});path.write_text(json.dumps(results,indent=2));print(name,bool(value),flush=True)
    if not value:raise AssertionError(name)
def nodes():return list(d.tree().iter('node'))
def tagged(tag):return [n for n in nodes() if n.get('resource-id')==tag]
def rect(n):return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def root():
    d.start();time.sleep(.5)
    if d.find('关闭小窗'):d.tap('关闭小窗')
    if d.find('返回浏览页面'):d.tap('返回浏览页面')
    for _ in range(5):
        if not d.find('返回动态'):break
        d.tap('返回动态')
    if not d.find('动态'):d.swipe(350,650,350,1350,450)
    d.tap('动态');d.wait('关注动态');time.sleep(2)
def find(kind):
    for _ in range(36):
        tree=d.tree()
        for n in tree.iter('node'):
            prefix=f'dynamic_card_{kind}_'
            if n.get('resource-id','').startswith(prefix):
                button=next((b for b in n.iter('node') if b.get('resource-id')=={'post':'dynamic_view_post','video':'dynamic_view_video','forward':'dynamic_view_original'}[kind]),None)
                if button is not None and 200<rect(button)[1]<1450:return n,button
        d.swipe(350,1300,350,650,350);time.sleep(.3)
    raise AssertionError('No real '+kind+' dynamic found')
def wait_complete():
    until=time.monotonic()+55
    while time.monotonic()<until:
        if tagged('dynamic_document_complete'):return True
        time.sleep(.5)
    return False
stage=sys.argv[1] if len(sys.argv)>1 else 'post'
try:
    root();card,button=find(stage)
    if stage=='forward':
        original=next(n for n in card.iter('node') if n.get('resource-id','').startswith('dynamic_card_original_'))
        expected=original.get('resource-id').removeprefix('dynamic_card_original_')
    elif stage=='post':expected=card.get('resource-id').removeprefix('dynamic_card_post_')
    else:expected=None
    d.tap_node(button)
    if stage=='video':
        d.wait('正在观看');check('video dynamic view opens native video detail',bool(tagged('video_stack_depth_1')))
    else:
        d.wait('动态详情');check(stage+' view opens correct native dynamic ID',bool(tagged('native_dynamic_id_'+expected)))
        check(stage+' detail loads complete native content',wait_complete())
        check(stage+' native detail has no WebView',not any(n.get('class')=='android.webkit.WebView' for n in nodes()))
        d.shot('v8-dynamic-'+stage)
    check('no fatal dynamic runtime exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
except Exception:d.shot('v8-dynamic-failure');raise
finally:root()
