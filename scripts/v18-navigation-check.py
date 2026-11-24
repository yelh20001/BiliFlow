"""Current-page mini-player navigation and Watch Later UI, without account mutations."""
from device_driver import Device, ROOT
import json, re, time

d = Device()
results = []
def check(name, value):
    results.append(dict(check=name, passed=bool(value)))
    (ROOT/'artifacts/v18-navigation-results.json').write_text(json.dumps(results, indent=2))
    print(name, bool(value), flush=True)
    assert value, name
def nodes(): return list(d.tree().iter('node'))
def tags(prefix): return [n for n in nodes() if n.get('resource-id','').startswith(prefix)]
def rect(n): return list(map(int,re.findall(r'-?\d+',n.get('bounds'))))
def until(fn):
    for _ in range(60):
        value=fn()
        if value:return value
        time.sleep(.4)
    raise AssertionError('UI did not settle')
def video_cards():
    return [n for n in nodes() if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))]
def minimize():
    p=rect(tags('video_player')[0]); x=(p[0]+p[2])//2
    d.swipe(x,p[1]+55,x,p[3]-20,650)
    d.wait('关闭小窗'); until(lambda:tags('in_app_mini_player'))
def tab(label):
    if not d.find(label):d.swipe(350,650,350,1100,400)
    d.tap(label);time.sleep(.5)

try:
    d.adb('shell','am','force-stop','app.biliflow'); d.adb('logcat','-c'); d.start(); d.wait('动态'); tab('动态')
    until(lambda:tags('dynamic_card_'))
    cards=[]
    for _ in range(16):
        cards=video_cards()
        if cards: break
        d.swipe(350,1300,350,700,450)
    assert cards, 'No video post found'
    p=rect(cards[0]);x=(p[0]+p[2])//2;y=(p[1]+p[3])//2
    d.swipe(x,y,x,y,900);d.wait('稍后再看')
    check('dynamic video long press offers watch later',True);d.back()
    d.tap_node(video_cards()[0]);d.wait('正在观看');d.wait('稍后再看')
    check('video detail has watch later button',True)
    minimize();check('dynamic video minimizes onto dynamic page',bool(tags('home_page_2')))
    tab('我的');check('background tab can change under mini',bool(tags('home_page_3')))
    d.tap('展开小窗');d.wait('正在观看');minimize()
    check('restore then minimize retains newly selected profile page',bool(tags('home_page_3')))
    tab('动态');d.tap('关闭小窗');time.sleep(.6)
    check('closing mini keeps current dynamic page',bool(tags('home_page_2')) and not tags('in_app_mini_player'))
    tab('我的');d.wait('稍后再看');d.tap('稍后再看');d.wait('播放全部')
    check('profile exposes account watch later and bulk actions',bool(d.find('删除已播放')) and bool(d.find('清空列表')))
    until(video_cards)
    d.tap('播放全部');d.wait('下一个')
    check('watch later starts an ordered playback queue',bool(tags('playback_queue')) and bool(d.find('稍后再看 · 1/',contains=True)))
    d.tap('下一个');d.wait('稍后再看 · 2/',contains=True)
    check('queue next replaces current video without exiting playlist',bool(tags('playback_queue')))
    minimize();check('queue minimizes onto its watch later list',bool(tags('watch_later_list')))
    d.tap('关闭小窗');check('closing queue mini preserves watch later list',bool(tags('watch_later_list')))
    # Open history as a retained overlay, then minimize and close without losing it.
    d.back()
    for _ in range(5):
        if d.find('观看历史'):break
        d.swipe(350,1200,350,700,400)
    d.tap('观看历史');until(video_cards);d.tap_node(video_cards()[0]);d.wait('正在观看');minimize()
    check('minimize retains history overlay',bool(d.find('观看历史')))
    d.tap('关闭小窗');check('closing mini retains history overlay',bool(d.find('观看历史')))
    d.tap_node(video_cards()[0]);d.wait('正在观看');d.wait('作者头像');d.tap('作者头像');d.wait('UP 主主页')
    def author_cards():
        profiles=tags('author_profile')
        return [n for n in profiles[0].iter('node') if n.get('clickable')=='true' and any('次观看' in c.get('text','') for c in n.iter('node'))] if profiles else []
    until(author_cards);d.tap_node(author_cards()[0]);d.wait('正在观看');minimize()
    check('UP video minimizes onto retained native author page',bool(d.find('UP 主主页')))
    d.tap('关闭小窗');check('closing UP video keeps native author page',bool(d.find('UP 主主页')))
    check('no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
    d.shot('v18-history-after-mini-close')
finally:
    d.adb('shell','am','force-stop','app.biliflow')
