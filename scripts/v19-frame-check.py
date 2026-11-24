"""Check fixed-video visibility across live gesture screenshots from v19-mini-check."""
from pathlib import Path
import sys,json,statistics
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.tools/python-libs'))
from PIL import Image
reports=[]
for kind in ('handoff','drag'):
    files=sorted((ROOT/'.tools/v19-mini'/kind).glob('*.png'),key=lambda p:int(p.stem))
    counts=[];centers=[]
    for path in files:
        im=Image.open(path).convert('RGB').resize((180,400));rows=[]
        for y in range(400):
            rows.append(sum(1 for x in range(180) if min(sum((a-b)**2 for a,b in zip(im.getpixel((x,y)),color)) for color in ((230,105,57),(33,184,223),(88,184,50)))<25**2))
        counts.append(sum(rows));target=[y for y,n in enumerate(rows) if n>max(rows)*.65]
        centers.append(sum(target)/len(target) if target else 0)
    item=dict(capture=kind,frames=len(files),minColorPixels=min(counts),maxColorPixels=max(counts),medianColorPixels=statistics.median(counts),centerMovement=max(centers)-min(centers),counts=counts)
    item['noBlankFrame']=min(counts)>statistics.median(counts)*(.45 if kind=='handoff' else .85)
    item['gestureVisibleInCapture']=item['centerMovement']>25
    reports.append(item)
    print(kind,item['noBlankFrame'],item['gestureVisibleInCapture'],flush=True)
(ROOT/'artifacts/v19-frame-results.json').write_text(json.dumps(reports,indent=2))
assert all(r['noBlankFrame'] and r['gestureVisibleInCapture'] and r['frames']==40 for r in reports)
