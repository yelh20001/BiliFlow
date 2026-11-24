"""Read-only tests of comment pictures and mini-player transitions on a logged-in emulator."""
from device_driver import Device, ROOT, PROBE
import json, re, sys, time

d = Device()
path = ROOT / 'artifacts/device-tests/v9-results.json'
results = json.loads(path.read_text()) if path.exists() else []
def check(name, value):
    results[:] = [i for i in results if i['check'] != name]
    results.append({'check': name, 'passed': bool(value)})
    path.write_text(json.dumps(results, indent=2))
    print(name, bool(value), flush=True)
    if not value: raise AssertionError(name)
def nodes(): return list(d.tree().iter('node'))
def tag(name): return [n for n in nodes() if n.get('resource-id') == name]
def bounds(n): return list(map(int, re.findall(r'-?\d+', n.get('bounds'))))
def root():
    d.start()
    if d.find('关闭评论图片'): d.tap('关闭评论图片')
    if d.find('关闭小窗'): d.tap('关闭小窗')
    if d.find('返回浏览页面'): d.tap('返回浏览页面')
    if not d.find('视频'): d.swipe(350, 600, 350, 1300)
    d.wait('视频')
def open_video():
    root(); d.tap('视频'); d.tap('热门'); time.sleep(2)
    cards = [n for n in nodes() if n.get('clickable') == 'true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
    d.tap_node(cards[0]); d.wait('正在观看'); time.sleep(2)
def mini():
    open_video(); b = bounds(tag('video_player')[0])
    d.probe('drag', x=(b[0]+b[2])//2, **{'from':b[1]+60,'to':b[3]-25})
    folder = ROOT / '.tools/v3-device-shots/v9-mini-frames'; folder.mkdir(parents=True, exist_ok=True)
    for i in range(12):
        (folder / f'{i}.png').write_bytes(d.adb('exec-out','cat',PROBE+f'drag-release-{i}.png',binary=True))
    d.wait('关闭小窗'); check('downward drag enters mini player', bool(tag('in_app_mini_player')))
    m = bounds(tag('in_app_mini_player')[0]); bar = bounds(tag('home_bottom_bar')[0])
    check('mini remains above bottom navigation', m[3] < bar[1]); d.tap('展开小窗'); d.wait('正在观看')
    check('mini expands back to the video detail', not tag('in_app_mini_player'))
def comments():
    root(); d.tap('视频'); d.tap('搜索视频'); d.adb('shell', 'input', 'text', 'BV1UB4v67Exr'); d.tap('开始搜索'); time.sleep(3)
    cards = [n for n in nodes() if n.get('clickable') == 'true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
    if not cards: raise AssertionError('No search result for public picture-comment fixture')
    d.tap_node(cards[0]); d.wait('正在观看'); d.tap('评论', contains=True); time.sleep(2)
    found = []
    for _ in range(18):
        found = [n for n in nodes() if n.get('resource-id','').startswith('comment_picture_')]
        if found: break
        d.swipe(350, 1100, 350, 550, 350)
    if not found: d.shot('v9-comment-search-failure')
    check('real comment picture thumbnail is visible', bool(found))
    d.tap_node(found[0]); d.wait('关闭评论图片')
    check('comment picture opens native viewer', bool(d.find('关闭评论图片')) and bool(d.find('评论原图', contains=True)))
    d.shot('v9-comment-picture'); d.tap('关闭评论图片')
    check('closing comment picture returns to comments', not d.find('关闭评论图片'))
def fixture():
    d.adb('shell','am','force-stop','app.biliflow')
    d.adb('shell','am','start','-n','app.biliflow/.CommentPictureFixtureActivity')
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline and not tag('comment_picture_loaded_0'): time.sleep(.5)
    check('fixture comment thumbnail decodes real image', bool(tag('comment_picture_loaded_0')))
    d.tap_node(tag('comment_picture_loaded_0')[0]); d.wait('关闭评论图片')
    check('fixture opens native picture viewer', bool(d.find('关闭评论图片')) and bool(d.find('评论原图 1')))
    d.tap('下一张评论图片'); d.wait('评论原图 2')
    check('fixture switches between comment pictures', bool(d.find('评论原图 2')))
    d.shot('v9-comment-picture-fixture'); d.tap('关闭评论图片')
    check('fixture closes picture viewer', not d.find('关闭评论图片'))
    d.adb('shell','am','force-stop','app.biliflow')
try:
    {'mini': mini, 'comments': comments, 'fixture': fixture}[sys.argv[1]]()
    check('no fatal exception', 'FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:
    root()
