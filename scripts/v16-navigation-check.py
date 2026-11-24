"""Verify settled UP paging, cached feeds, scroll state and animated bottom navigation."""
from device_driver import Device, ROOT, PROBE
import json, re, time, threading, sys, io
sys.path.insert(0,str(ROOT/'.tools/python-libs'))
from PIL import Image,ImageChops,ImageStat

d = Device()
results = []
def check(name, passed):
    results.append({'check':name,'passed':bool(passed)})
    (ROOT/'artifacts/v17-navigation-results.json').write_text(json.dumps(results,indent=2))
    print(name, bool(passed), flush=True)
    assert passed, name
def nodes(): return list(d.tree().iter('node'))
def tags(prefix): return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def rect(n): return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def until(fn):
    for _ in range(50):
        value=fn()
        if value:return value
        time.sleep(.4)
    raise AssertionError('UI did not settle')
def page_id():
    pages=tags('dynamic_author_page_')
    return pages[0].get('resource-id') if pages else ''
def cards(): return [n.get('resource-id') for n in tags('dynamic_card_')]
def show_bars():
    if not d.find('动态'): d.swipe(350,650,350,1050,350)
def choose_tab(label):
    show_bars(); d.wait(label); d.tap(label); time.sleep(.5)
try:
    d.adb('shell','am','force-stop','app.biliflow'); d.adb('logcat','-c'); d.start()
    choose_tab('动态'); until(lambda: len(tags('followed_author_')) >= 2)
    authors=tags('followed_author_'); first=authors[0].get('resource-id').removeprefix('followed_author_'); second=authors[1].get('resource-id').removeprefix('followed_author_')
    d.tap_node(authors[0]); until(lambda:page_id()=='dynamic_author_page_'+first); until(cards); time.sleep(1)
    a=cards(); epoch=tags('dynamic_list_request_')[0].get('resource-id')
    check('avatar opens corresponding UP pager page', page_id()=='dynamic_author_page_'+first)
    d.swipe(350,950,390,950,400); time.sleep(.5)
    check('cancelled short swipe keeps UP and does not request another feed', page_id()=='dynamic_author_page_'+first and tags('dynamic_list_request_')[0].get('resource-id')==epoch)
    frames=ROOT/'.tools/v16-author-frames'; frames.mkdir(exist_ok=True)
    selector=tags('dynamic_author_selector')
    selector_bounds=rect(selector[0]) if selector else None
    all_icon=rect(d.find('全部关注动态')[0])
    before_icon=Image.open(io.BytesIO(d.adb('exec-out','screencap','-p',binary=True))).convert('RGB').crop(all_icon)
    worker=threading.Thread(target=lambda:d.swipe(650,950,70,950,900)); worker.start()
    for i in range(5):
        (frames/f'{i}.png').write_bytes(d.adb('exec-out','screencap','-p',binary=True)); time.sleep(.12)
    worker.join()
    if selector_bounds:
        diffs=[]
        for i in range(5):
            sample=Image.open(frames/f'{i}.png').convert('RGB').crop(all_icon)
            diffs.append(sum(ImageStat.Stat(ImageChops.difference(before_icon,sample)).mean))
        check('UP selector remains fixed while dynamic content slides',max(diffs)<3 and rect(tags('dynamic_author_selector')[0])==selector_bounds)
    until(lambda:page_id()=='dynamic_author_page_'+second); until(cards)
    check('left swipe settles on next UP', page_id()=='dynamic_author_page_'+second)
    d.swipe(80,950,650,950,400); until(lambda:page_id()=='dynamic_author_page_'+first); time.sleep(.5)
    check('right swipe restores previous UP feed', cards()==a)
    if selector_bounds:
        row_y=(selector_bounds[1]+selector_bounds[3])//2
        d.swipe(620,row_y,100,row_y,400)
        check('UP list scrolls independently without switching the dynamic page',page_id()=='dynamic_author_page_'+first)
        d.swipe(100,row_y,650,row_y,400)
    d.swipe(350,1300,350,750,500); time.sleep(.4)
    show_bars()
    position=cards()
    choose_tab('视频'); until(lambda:tags('home_page_1'))
    button=d.wait('我的')[-1]; x1,y1,x2,y2=rect(button)
    d.probe('tapfastframes',x=(x1+x2)//2,y=(y1+y2)//2)
    target=ROOT/'.tools/v16-tab-frames'; target.mkdir(exist_ok=True)
    for i in range(12): (target/f'{i}.png').write_bytes(d.adb('exec-out','cat',PROBE+f'tap-fast-{i}.png',binary=True))
    check('bottom tab animation reaches profile', bool(tags('home_page_3')))
    choose_tab('动态'); until(lambda:page_id()=='dynamic_author_page_'+first)
    check('tab return keeps selected UP and list position', cards()==position)
    for label in ('直播','视频','我的','动态','视频'):
        choose_tab(label)
    check('successive bottom tab transitions complete',bool(tags('home_page_1')))
    check('no fatal exception', 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:
    d.adb('shell','am','force-stop','app.biliflow')
