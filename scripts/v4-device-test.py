"""v0.4 integration checks on a logged-in test emulator.

The like check changes one test video's like and restores its original state in
finally, then verifies it by reopening the video. No coins/favorites/follows are
submitted. Screenshots stay private under ignored .tools.
"""
from device_driver import Device,ROOT,PROBE
import json,re,time,sys,xml.etree.ElementTree as ET

d=Device(); path=ROOT/'artifacts/device-tests/v4-results.json'
results=json.loads(path.read_text()) if path.exists() else []
def check(name,value):
    global results
    results=[v for v in results if v['check']!=name]+[{'check':name,'passed':bool(value)}]
    path.write_text(json.dumps(results,indent=2),encoding='utf-8')
    print(name,bool(value),flush=True)
    if not value: raise AssertionError(name)
def tree(): return list(d.tree().iter('node'))
def has(label): return bool(d.find(label,contains=True))
def tagged(prefix): return [n for n in tree() if n.get('resource-id','').startswith(prefix)]
def box(n): return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def bounds(): return box(tagged('video_player')[0])
def root():
    d.start();time.sleep(2)
    for _ in range(4):
        if d.find('视频'): return
        d.back()
    d.wait('视频')
def cards(): return [n for n in tree() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
def open_video():
    d.tap_node(cards()[0]);d.wait('正在观看');time.sleep(5)
def audio():
    pid=d.adb('shell','pidof','app.biliflow').strip()
    return any('AudioPlaybackConfiguration' in s and f'/{pid} state:started' in s for s in d.adb('shell','dumpsys','audio').splitlines())
def wait_audio():
    for _ in range(30):
        if audio(): return True
        time.sleep(1)
    return False
def show():
    if not has('全屏'):
        b=bounds();d.adb('shell','input','tap',str(b[0]+25),str((b[1]+b[3])//2))
def wait_like():
    for _ in range(35):
        button=tagged('interaction_like_')
        if button and button[0].get('enabled')=='true': return button[0]
        time.sleep(.35)
    raise AssertionError('Like button did not finish loading')
def selected(n): return n.get('resource-id','').endswith('_selected')
def motion(kind):
    b=bounds();d.probe('drag',x=(b[0]+b[2])//2,**{'from':b[3]-35,'to':b[1]+30})
    held=ET.fromstring(d.adb('shell','cat',PROBE+'drag-held.xml'))
    moved=[n for n in held.iter('node') if n.get('resource-id')=='player_motion_dragging']
    check(kind+' moves picture while finger is down',bool(moved) and box(moved[0])[1]<b[1]-20)
    time.sleep(1)
    check(kind+' upward gesture enters fullscreen',bounds()[2]>1000)
    b=bounds();d.probe('drag',x=(b[0]+b[2])//2,**{'from':b[1]+80,'to':b[3]-70});time.sleep(1)
    check(kind+' downward gesture exits fullscreen',bounds()[2]==720)

section=sys.argv[1] if len(sys.argv)>1 else 'all'
if section in ['all','social']:
    root();d.tap('动态');d.wait('关注动态');time.sleep(5)
    count=tagged('followed_authors_count_')[0].get('resource-id')
    check('native followed author list loads',int(count.rsplit('_',1)[-1])>0)
    author=tagged('followed_author_')[0];d.tap_node(author);time.sleep(5)
    check('author tap opens their native dynamic feed',has('的动态') and (has('UP 主头像') or has('暂时没有新动态')))
    check('author dynamic feed has no WebView',not any(n.get('class')=='android.webkit.WebView' for n in tree()))
    d.shot('v4-author-feed');d.tap('全部关注');d.tap('视频')

if section in ['all','video']:
    root();d.tap('视频');d.tap('热门');time.sleep(4);open_video()
    check('VOD still decodes with audio',audio())
    original=selected(wait_like())
    try:
        d.tap_node(wait_like())
        check('like icon immediately reflects the new state',selected(tagged('interaction_like_')[0])!=original)
        changed=selected(wait_like());check('like request completes without reverting',changed!=original)
        d.back();open_video()
        check('server readback agrees with changed like',selected(wait_like())!=original)
    finally:
        current=selected(wait_like())
        if current!=original: d.tap_node(wait_like());wait_like()
        d.back();open_video()
        check('original like state restored on server',selected(wait_like())==original)
    check('coin and favorite expose selected state',bool(tagged('interaction_coin_')) and bool(tagged('interaction_favorite_')))
    d.shot('v4-interactions')
    motion('VOD');show();d.tap('暂停');time.sleep(.5)
    check('pause still works after fullscreen animation',not audio())
    show();d.tap('播放');time.sleep(1)
    # Return progress and release pictures are kept private for frame inspection.
    d.probe('gesture',x=270,y=950,cancel='true');check('back cancellation retains playing detail',has('正在观看'))
    d.probe('gesture',x=270,y=950);time.sleep(.5)
    check('back commit reaches video feed',bool(tagged('video_feed_page_')))
    check('detail exit releases audio',not audio())
    open_video()
    for _ in range(10):
        if has('刷新评论'): break
        d.swipe(350,1380,350,850,300)
    d.wait('刷新评论');check('comments appear directly in detail',has('评论者头像'))
    before=tagged('comments_page_')[0].get('resource-id');d.tap('刷新评论');time.sleep(2)
    after=tagged('comments_page_')[0].get('resource-id')
    check('comment refresh button fetches new first page',int(after.rsplit('_',1)[-1])>int(before.rsplit('_',1)[-1]))
    for _ in range(17): d.swipe(350,1390,350,770,200)
    check('comments load next page without a more button',has('评论者头像') and not has('查看全部评论'))
    d.shot('v4-comments');d.back()

if section in ['all','live']:
    root();d.tap('直播');d.wait('正在发生的精彩');time.sleep(3)
    rooms=[n for n in tree() if n.get('clickable')=='true' and any('人气' in c.get('text','') or c.get('text','').startswith('● ') for c in n.iter('node'))]
    if not rooms:
        d.swipe(350,1330,350,730,350)
        rooms=[n for n in tree() if n.get('clickable')=='true' and any('人气' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(rooms[0]);d.wait('直播间');time.sleep(10)
    check('live room is native with no WebView',bool(tagged('native_live_detail')) and not any(n.get('class')=='android.webkit.WebView' for n in tree()))
    check('live anchor information loads',has('主播头像'))
    check('native live HLS decodes with audio',wait_audio())
    check('live chat is native',has('直播聊天'))
    d.shot('v4-live');motion('live')
    show();d.tap('暂停');time.sleep(.6);check('live pause works',not audio())
    show();d.tap('播放');time.sleep(2);check('live resume works',wait_audio())
    d.adb('shell','input','keyevent','3');time.sleep(2)
    check('live supports native PiP',bool(re.search(r'windowingMode=pinned|mode=pinned|mWindowingMode=2',d.adb('shell','dumpsys','activity','activities'),re.I)))
    d.start();d.wait('直播间');d.back();check('leaving native live releases playback',not audio())

check('no fatal runtime exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
print('Passed',sum(v['passed'] for v in results),'checks',flush=True)
