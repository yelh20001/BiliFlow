"""Deterministic glass QA: stable pixels, smooth visibility, real blur and live input."""
from device_driver import Device, ROOT, PROBE
import sys, io, time, re, json
sys.path.insert(0, str(ROOT/'.tools/python-libs'))
from PIL import Image, ImageChops, ImageStat

d=Device(); results=[]
def check(name,value):
    results.append({'check':name,'passed':bool(value)})
    (ROOT/'artifacts/v17-glass-results.json').write_text(json.dumps(results,indent=2))
    print(name,bool(value),flush=True)
    assert value,name
def shot(): return Image.open(io.BytesIO(d.adb('exec-out','screencap','-p',binary=True))).convert('RGB')
def capture_tap(label,folder):
    n=d.wait(label)[-1]; a,b,c,e=map(int,re.findall(r'-?\d+',n.get('bounds')))
    d.probe('tapfastframes',x=(a+c)//2,y=(b+e)//2)
    folder.mkdir(parents=True,exist_ok=True)
    images=[]
    for i in range(20):
        data=d.adb('exec-out','cat',PROBE+f'tap-fast-{i}.png',binary=True)
        (folder/f'{i}.png').write_bytes(data); images.append(Image.open(io.BytesIO(data)).convert('RGB'))
    return images
def level(im): return sum(ImageStat.Stat(im.crop((510,150,530,170))).mean)/3
def monotonic(images,first,last):
    values=[first]+[level(im) for im in images]+[last]
    sign=1 if last>first else -1
    return max(-sign*(b-a) for a,b in zip(values,values[1:])) < 6

try:
    for dark in (False,True):
        mode='dark' if dark else 'light'
        d.adb('shell','am','force-stop','app.biliflow')
        d.adb('shell','am','start','-n','app.biliflow/.GlassFixtureActivity','--ez','dark',str(dark).lower())
        d.wait('隐藏顶栏'); time.sleep(1)
        baseline=shot(); roi=(420,140,680,220)
        samples=[shot().crop(roi) for _ in range(4)]
        noise=max(sum(ImageStat.Stat(ImageChops.difference(samples[0],s)).mean) for s in samples[1:])
        check(mode+' stationary glass does not flicker',noise<1.5)
        line=[baseline.getpixel((x,180)) for x in range(40,680)]
        raw=[baseline.getpixel((x,500)) for x in range(40,680)]
        edge=lambda row:max(sum(abs(a-b) for a,b in zip(p,q)) for p,q in zip(row,row[1:]))
        check(mode+' glass still blurs the background instead of a solid replacement',edge(line)<edge(raw)*.1 and sum(ImageStat.Stat(baseline.crop(roi)).stddev)>.5)
        hide=capture_tap('隐藏顶栏',ROOT/f'.tools/v17-{mode}-hide')
        hidden=shot()
        check(mode+' hide frames have no reverse brightness flash',abs(level(hidden)-level(baseline))>30 and monotonic(hide,level(baseline),level(hidden)))
        show=capture_tap('显示顶栏',ROOT/f'.tools/v17-{mode}-show')
        shown=shot()
        check(mode+' show frames have no reverse brightness flash',monotonic(show,level(hidden),level(shown)))
        check(mode+' restored glass matches the original',sum(ImageStat.Stat(ImageChops.difference(baseline.crop(roi),shown.crop(roi))).mean)<1.5)
        d.tap('切换底图'); time.sleep(.5); changed=shot()
        check(mode+' blur keeps sampling live source content',sum(ImageStat.Stat(ImageChops.difference(shown.crop(roi),changed.crop(roi))).mean)>3)
    check('no fatal exception','FATAL EXCEPTION' not in d.adb('logcat','-d','-s','AndroidRuntime:E'))
finally:
    d.adb('shell','am','force-stop','app.biliflow')
