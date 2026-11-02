# -*- coding: utf-8 -*-
"""Opt-in real-model/HTTP regression. Requires a dedicated demo DB; cleans temporary fixtures.
The API uses its local credentials. This script never reads or prints model API keys.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timedelta
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.request
import urllib.error
import uuid

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--run-real-model',action='store_true')
p.add_argument('--stream',action='store_true',help='Exercise POST SSE through the same HTTP proxy and confirmation flow')
p.add_argument('--base',default='http://127.0.0.1:15173/api/v1')
p.add_argument('--database',required=True)
p.add_argument('--mysql',default='mysql')
p.add_argument('--db-port',type=int,default=3306)
p.add_argument('--db-user',default='root')
p.add_argument('--output',default='docs/evidence/ai-upgrade-http.json')
args=p.parse_args()
if not args.run_real_model:raise SystemExit('Explicit --run-real-model required; requests may incur provider charges.')
if not re.fullmatch(r'[A-Za-z0-9_]+_demo',args.database):raise SystemExit('Use a dedicated _demo database')
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
client=[args.mysql,'--no-defaults','--host=127.0.0.1','--port='+str(args.db_port),'--user='+args.db_user,
    '--database='+args.database,'--default-character-set=utf8mb4','--batch','--skip-column-names']
record={'passed':False,'realModelCalled':True,'ragVerified':False,'checks':[],'results':{}}
uid=None;aid=None;name='ai_graph_'+uuid.uuid4().hex[:16];title='AI-GRAPH-'+uuid.uuid4().hex[:10]
started=time.monotonic()
def sql(statement):return subprocess.check_output(client+['-e',statement]).decode('utf-8').strip()
def call(path,token=None,method='GET',body=None,expected=(200,)):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    streaming=args.stream and path.endswith('/messages')
    if streaming:
        path+='/stream';headers['Accept']='text/event-stream'
    request=urllib.request.Request(args.base+path,headers=headers,method=method,data=None if body is None else json.dumps(body).encode())
    try:response=opener.open(request,timeout=120)
    except urllib.error.HTTPError as error:response=error
    if streaming and response.code==200:
        assert 'text/event-stream' in response.headers.get('Content-Type','')
        opened=time.monotonic();events=[];result=None;event='message';lines=[]
        with response:
            for raw in response:
                line=raw.decode('utf-8').rstrip('\r\n')
                if line.startswith('event:'):event=line[6:].strip()
                elif line.startswith('data:'):lines.append(line[5:].lstrip())
                elif not line and lines:
                    value=json.loads('\n'.join(lines));lines=[]
                    events.append({'event':event,'afterSeconds':round(time.monotonic()-opened,3)})
                    if event=='error':raise AssertionError(value)
                    if event=='done':result=value
        assert result is not None and events[-1]['event']=='done'
        assert any(e['event']=='delta' for e in events)
        assert events[0]['event']=='status'
        record.setdefault('streams',[]).append(events)
        return result
    data=json.load(response)
    assert response.code in expected,(path,response.code,data.get('code') or data.get('errorCode'))
    return data
def login(username):return call('/auth/login',method='POST',body={'username':username,'password':os.environ.get('DEMO_PASSWORD','123')})['token']
def passed(check):record['checks'].append(check);print(json.dumps({'passed':check}),flush=True)
def count():return int(sql('select count(*) from participation where user_id='+str(uid)))
try:
    for attempt in range(40):
        try:admin=login('admin');break
        except (OSError,ValueError):time.sleep(0.5)
    else:raise AssertionError('Local API did not become ready')
    other=login('user')
    uid=call('/auth/register',method='POST',body={'username':name,'password':os.environ.get('DEMO_PASSWORD','123')})['id']
    assert sql("select count(*) from app_user where id="+str(uid)+" and username='"+name+"'")=='1'
    user=login(name);venue=next(v for v in call('/admin/me/venues',admin) if v['status']=='OPEN')
    aid=call('/admin/activities',admin,'POST',{'venue_id':venue['id'],'title':title,'category':'HERITAGE','audience':'ALL','description':'AI confirmation test fixture; temporary, not for public registration.'})['id']
    day=(datetime.utcnow()+timedelta(hours=8,days=3)).date();start=datetime.combine(day,datetime.min.time()).replace(hour=14)
    fmt=lambda date:date.isoformat(timespec='minutes')
    session={'activity_id':aid,'capacity':2,'registration_start_at':fmt(start-timedelta(days=10)),
        'registration_end_at':fmt(start-timedelta(hours=1)),'start_at':fmt(start),'end_at':fmt(start+timedelta(hours=1)),
        'checkin_open_at':fmt(start-timedelta(minutes=20)),'checkin_close_at':fmt(start+timedelta(minutes=30))}
    sid=call('/admin/sessions',admin,'POST',session)['id'];call('/admin/activities/'+str(aid)+'/publish',admin,'POST',{})
    conv=call('/ai/conversations',user,'POST',{})['id']
    clarification=call('/ai/conversations/'+conv+'/messages',user,'POST',{'message':'想参加文化活动，还没想好哪天。'})
    assert clarification['type']=='CLARIFICATION',clarification
    assert count()==0;passed('real Graph clarification branch does not book')
    recommendation=call('/ai/conversations/'+conv+'/messages',user,'POST',{'message':str(day)+'下午，不限人群，只查活动名称包含“'+title+'”的场次。请将完整名称作为关键词，不要省略。'})
    record['results']['recommendation']=recommendation
    assert recommendation['type']=='RECOMMENDATION',recommendation
    candidate=next(c for c in recommendation['candidates'] if c['sessionId']==sid)
    assert candidate['activityId']==aid and candidate['remaining']==2 and count()==0
    passed('real tool calling returns a verified session and authoritative remaining capacity')
    path='/ai/conversations/'+conv+'/proposals';selection={'candidateId':candidate['candidateId'],'mode':'DIRECT'}
    call(path,other,'POST',selection,expected=(403,))
    proposal=call(path,user,'POST',selection);assert proposal['status']=='PENDING' and count()==0
    sql("update ai_booking_proposal set expires_at=date_sub(now(),interval 1 minute) where id='"+proposal['id']+"' and user_id="+str(uid))
    expired=call('/ai/booking-proposals/'+proposal['id']+'/confirm',user,'POST',{},expected=(409,))
    assert expired['status']=='EXPIRED' and count()==0;passed('expired confirmation is rejected without booking')
    rejected=call(path,user,'POST',selection);call('/ai/booking-proposals/'+rejected['id']+'/reject',user,'POST',{})
    assert count()==0;passed('selection and rejection do not create participation')
    proposal=call(path,user,'POST',selection);proposal_path='/ai/booking-proposals/'+proposal['id']
    call(proposal_path,other,expected=(403,));call(proposal_path+'/confirm',other,'POST',{},expected=(403,))
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda _:call(proposal_path+'/confirm',user,'POST',{},expected=(200,202)),range(3)))
    result=call(proposal_path,user);assert result['status']=='SUCCEEDED' and result['participation']['status']=='RESERVED'
    assert count()==1 and sql('select count(*) from outbox o join participation p on p.id=o.aggregate_id where p.user_id='+str(uid)+" and o.event_type='RESERVATION_CONFIRMED'")=='1'
    replay=call(proposal_path+'/confirm',user,'POST',{});assert replay['participation']['id']==result['participation']['id']
    assert not any(k in result for k in ('request_id','execution_token','checkin_token'))
    record['results']['confirmation']={'status':result['status'],'participationStatus':result['participation']['status'],'people':result['people']}
    passed('concurrent and repeated real confirmations create one reservation and one Outbox event')
    information=call('/ai/conversations/'+conv+'/messages',user,'POST',{'message':'刚才这项活动所在的场馆几点开放？只依据已有资料回答，不需要再预约。'})
    assert information['type']=='MESSAGE' and information['explanation'],information
    record['results']['venueInformation']=information['explanation'];passed('multi-turn venue information does not require a booking date')
    call('/admin/ai/activity-plans',admin,'POST',{'venue_id':venue['id'],'theme':'test'},expected=(400,))
    call('/admin/ai/reports',admin,'POST',{},expected=(400,))
    call('/admin/ai/activity-plans',user,'POST',{'venue_id':venue['id'],'theme':'test','minutes':60},expected=(403,))
    plan=call('/admin/ai/activity-plans',admin,'POST',{'venue_id':venue['id'],'theme':'城市记忆与公共文化','minutes':60,'audience':'ALL','requirements':'4个简洁环节，总计60分钟。'})
    assert sum(step['minutes'] for step in plan['steps'])==60
    record['results']['planTitle']=plan['title'];passed('AI2 real structured plan and DTO/permission regression')
    metrics=call('/admin/sessions/48/statistics',admin);report=call('/admin/ai/reports',admin,'POST',{'session_id':48})
    assert report['sampleCount']>0
    for key in ('reserved','checkedIn','checkinRate'):assert report['metrics'][key]==metrics[key]
    reviews=call('/admin/sessions/48/reviews',admin)
    assert set(report['analysis']['reviewIds']).issubset({r['id'] for r in reviews})
    record['results']['report']={'sessionId':48,'metrics':report['metrics'],'sampleCount':report['sampleCount']}
    passed('AI3 real review analysis preserves Java metrics and valid review references');record['passed']=True
except Exception as error:
    record['failure']=str(error);print(json.dumps({'failed':type(error).__name__,'detail':str(error)},ensure_ascii=True),flush=True)
finally:
    if aid:
        assert sql("select count(*) from activity where id="+str(aid)+" and title='"+title+"'")=='1'
        target='select x.id from activity_session x where x.activity_id='+str(aid)
        qualifications='select p.id from participation p where p.session_id in ('+target+')'
        cleanup=['START TRANSACTION;','DELETE FROM ai_booking_proposal WHERE activity_id='+str(aid)+';',
            'DELETE FROM notification WHERE participation_id IN ('+qualifications+');','DELETE FROM outbox WHERE aggregate_id IN ('+qualifications+');',
            'DELETE FROM participation_history WHERE participation_id IN ('+qualifications+');','DELETE FROM participation WHERE session_id IN ('+target+');',
            'DELETE FROM activity_session WHERE activity_id='+str(aid)+';',"DELETE FROM activity WHERE id="+str(aid)+" AND title='"+title+"';",'COMMIT;']
        subprocess.run(client+['-e',' '.join(cleanup)],check=True)
    if uid:
        subprocess.run(client+['-e','START TRANSACTION; DELETE FROM user_role WHERE user_id='+str(uid)+"; DELETE FROM app_user WHERE id="+str(uid)+" AND username='"+name+"'; COMMIT;"],check=True)
        assert sql("select count(*) from app_user where username='"+name+"'")=='0'
    record['fixturesCleaned']=True;record['seconds']=round(time.monotonic()-started,2)
    output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(record,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
    print(json.dumps({'passed':record['passed'],'checks':record['checks'],'fixturesCleaned':True,'seconds':record['seconds']}),flush=True)
if not record['passed']:raise SystemExit(1)
