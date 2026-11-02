# -*- coding: utf-8 -*-
"""Opt-in real-model reproduction of the youth/family capacity conversation; never books."""
import argparse,json,os,time,urllib.request,urllib.error
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--run-real-model',action='store_true');p.add_argument('--base',default='http://127.0.0.1:15173/api/v1')
p.add_argument('--username',default='admin');p.add_argument('--output',default='docs/evidence/ai-capacity-http.json');args=p.parse_args()
if not args.run_real_model:raise SystemExit('Explicit --run-real-model required; model calls may incur charges.')
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}));token=None
def call(path,body=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request(args.base+path,headers=headers,method='POST' if body is not None else 'GET',data=None if body is None else json.dumps(body).encode())
    response=opener.open(request,timeout=120)
    if not path.endswith('/stream'):return json.load(response)
    started=time.monotonic();event='message';lines=[];result=None;events=[]
    with response:
        for raw in response:
            line=raw.decode('utf-8').rstrip('\r\n')
            if line.startswith('event:'):event=line[6:].strip()
            elif line.startswith('data:'):lines.append(line[5:].lstrip())
            elif not line and lines:
                value=json.loads('\n'.join(lines));lines=[];events.append({'event':event,'afterSeconds':round(time.monotonic()-started,3)})
                if event=='error':raise AssertionError(value)
                if event=='done':result=value
    assert result is not None,'SSE ended without done'
    return result,events
for attempt in range(30):
    try:token=call('/auth/login',{'username':args.username,'password':os.environ.get('DEMO_PASSWORD','123')})['token'];break
    except (OSError,ValueError):time.sleep(0.5)
else:raise SystemExit('API not ready')
before=call('/me/participations/count')['total'];conversation=call('/ai/conversations',{})['id']
first,first_events=call('/ai/conversations/'+conversation+'/messages/stream',{'message':'有没有适合青少年的活动？'})
second,second_events=call('/ai/conversations/'+conversation+'/messages/stream',{'message':'都可以啊，剩余人数达到3人以上的，我要带我老婆和孩子一起去'})
assert second['preferences']['minimumRemaining']==3,second['preferences']
assert second['phase'] in ('RECOMMENDATION','NO_RESULT'),second['phase']
assert all(c['remaining']>=3 for c in second['candidates'])
assert '当前账号一次只能预约 1 个名额' in second['explanation']
assert call('/me/participations/count')['total']==before,'Chat must not create a participation'
record={'passed':True,'realModelCalled':True,'conversation':['有没有适合青少年的活动？','都可以啊，剩余人数达到3人以上的，我要带我老婆和孩子一起去'],'firstPhase':first['phase'],'secondPhase':second['phase'],'preferences':second['preferences'],'candidateRemaining':[c['remaining'] for c in second['candidates']],'explanation':second['explanation'],'noReservationCreated':True,'streams':[first_events,second_events]}
Path(args.output).write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'passed':True,'firstPhase':first['phase'],'secondPhase':second['phase'],'minimumRemaining':3,'candidateRemaining':record['candidateRemaining'],'noReservationCreated':True}))
