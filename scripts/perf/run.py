"""Isolated real HTTP load runner (stdlib). No rate-limit bypass, no production DB access.
Usage: python scripts/perf/run.py prepare|run --database culturehub_perf --port 13306
JWT test tokens are generated only for users inserted into this dedicated local test schema.
"""
import argparse,base64,collections,concurrent.futures,hashlib,hmac,json,math,os,platform,socket,statistics,subprocess,threading,time,urllib.request,urllib.error,uuid,shutil
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','run']);p.add_argument('--database',default='culturehub_perf');p.add_argument('--port',type=int,default=13306);p.add_argument('--mysql',default=shutil.which('mysql') or 'mysql');p.add_argument('--base',default='http://127.0.0.1:18090');p.add_argument('--redis-port',type=int,default=16379);p.add_argument('--runs',type=int,default=3);p.add_argument('--fault-only',action='store_true');p.add_argument('--output',default='docs/evidence/performance.json');a=p.parse_args()
assert a.database.endswith('_perf') and a.database.replace('_','').isalnum(),'Dedicated _perf schema required'
assert a.base in ('http://127.0.0.1:18090','http://localhost:18090'),'Dedicated local API port 18090 required'
root=Path(__file__).resolve().parents[2];state=root/'.tools/perf-fixture.json';secret='culturehub-isolated-performance-secret-2026'
cli=[a.mysql,'--no-defaults','--host=127.0.0.1','--port='+str(a.port),'--user=root','--batch','--skip-column-names','--default-character-set=utf8mb4']
def sql(query,db=True):
 r=subprocess.run(cli+(['--database='+a.database] if db else []),input=query.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if r.returncode:raise RuntimeError(r.stderr.decode('utf-8',errors='replace'))
 return r.stdout.decode('utf-8').strip()
def token(user):
 def enc(v):return base64.urlsafe_b64encode(json.dumps(v,separators=(',',':')).encode()).rstrip(b'=')
 body=enc({'alg':'HS256'})+b'.'+enc({'sub':str(user),'iss':'culturehub','iat':int(time.time()),'exp':int(time.time())+86400})
 return (body+b'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),body,hashlib.sha256).digest()).rstrip(b'=')).decode()
def insert(query):return int(sql(query+';select last_insert_id();').splitlines()[-1])
def session(capacity):return insert("insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status) values(%d,now()-interval 1 day,now()+interval 1 day,now()+interval 2 day,now()+interval 3 day,now()+interval 1 day,now()+interval 3 day,%d,'PUBLISHED')"%(f['activity'],capacity))
def redis(*args):
 with socket.create_connection(('127.0.0.1',a.redis_port),timeout=3) as s:
  encoded=[str(x).encode() for x in args];s.sendall(b'*'+str(len(args)).encode()+b'\r\n'+b''.join(b'$'+str(len(x)).encode()+b'\r\n'+x+b'\r\n' for x in encoded));stream=s.makefile('rb');line=stream.readline()
  if line[:1]==b'$':return stream.read(int(line[1:].strip())).decode()
  return line.decode().strip()
if a.action=='prepare':
 exists=sql("select count(*) from information_schema.tables where table_schema='%s' and table_name='app_user'"%a.database,False)
 if exists=='0':sql((root/'deploy/mysql/01-schema.sql').read_text(encoding='utf-8-sig').replace('culturehub',a.database),False)
 prefix='perf_'+uuid.uuid4().hex[:10];venue=insert("insert into venue(name) values('%s')"%prefix)
 activity=insert("insert into activity(venue_id,title,status) values(%d,'%s','PUBLISHED')"%(venue,prefix))
 users=[]
 for i in range(200):users.append(insert("insert into app_user(username,password_hash,nickname) values('%s_%d','unused-test-jwt-account','load user')"%(prefix,i)))
 sql("insert into user_role(user_id,role) values(%d,'PLATFORM_ADMIN')"%users[0])
 # Query dataset: multiple rows per category and venue, deterministic non-identical titles.
 for block in range(10):
  sql('insert into activity(venue_id,title,category,status) values '+','.join("(%d,'load catalog %d','%s','PUBLISHED')"%(venue,block*100+i,['ART','FAMILY','LECTURE'][i%3]) for i in range(100)))
 sql("insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status) select id,now()-interval 1 day,now()+interval 1 day,now()+interval 2 day,now()+interval 3 day,now()+interval 1 day,now()+interval 3 day,20,'PUBLISHED' from activity where venue_id=%d"%venue)
 history_session=int(sql('select min(id) from activity_session where activity_id=%d'%activity))
 for block in range(30):
  sql('insert into participation(session_id,user_id,status,entry_type,request_id) values '+','.join("(%d,%d,'CANCELLED','DIRECT','%s_history_%d')"%(history_session,users[0],prefix,block*1000+i) for i in range(1000)))
 f={'database':a.database,'venue':venue,'activity':activity,'users':users,'tokens':[token(u) for u in users],'prefix':prefix};state.parent.mkdir(exist_ok=True);state.write_text(json.dumps(f))
 print('Prepared dedicated schema and 200 users; tokens are only in ignored .tools/perf-fixture.json');raise SystemExit()
f=json.loads(state.read_text());assert f['database']==a.database
if not f.get('reportVenue'):
 f['reportVenue']=insert("insert into venue(name) values('%s_report')"%f['prefix'])
 report_activity=insert("insert into activity(venue_id,title,status) values(%d,'bounded report','PUBLISHED')"%f['reportVenue'])
 sql("insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status) values(%d,now()-interval 1 day,now()+interval 1 day,now()+interval 2 day,now()+interval 3 day,now()+interval 1 day,now()+interval 3 day,20,'PUBLISHED')"%report_activity)
 state.write_text(json.dumps(f))
op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(path,user=0,body=None,timeout=60):
 start=time.perf_counter()
 try:
  req=urllib.request.Request(a.base+path,headers={'Authorization':'Bearer '+f['tokens'][user],'Content-Type':'application/json'},data=None if body is None else json.dumps(body).encode())
  try:r=op.open(req,timeout=timeout)
  except urllib.error.HTTPError as e:r=e
  with r:data=json.load(r);status=r.code
  code=data.get('code','OK') if isinstance(data,dict) else 'OK'
  category='success' if status<400 else 'rate_limit' if status==429 else 'system' if status>=500 else 'business'
  return {'ms':(time.perf_counter()-start)*1000,'status':status,'code':code,'category':category}
 except Exception as e:return {'ms':(time.perf_counter()-start)*1000,'status':0,'code':type(e).__name__,'category':'transport'}
def snapshot():
 values=dict(line.split('\t') for line in sql("show global status where Variable_name in ('Innodb_row_lock_waits','Innodb_row_lock_time','Slow_queries','Threads_running')",False).splitlines())
 values['schemaSelectStatements']=sql("select coalesce(sum(COUNT_STAR),0) from performance_schema.events_statements_summary_by_digest where SCHEMA_NAME='%s' and DIGEST_TEXT like 'SELECT %%'"%a.database,False)
 try:values['redisInfo']=dict(line.split(':',1) for line in redis('INFO','stats').splitlines() if ':' in line and not line.startswith('#'))
 except Exception:values['redisInfo']={}
 return values
def summary(rows,seconds):
 times=sorted(r['ms'] for r in rows);return {'requests':len(rows),'seconds':seconds,'rps':len(rows)/seconds,'meanMs':statistics.mean(times),'p95Ms':times[math.ceil(.95*len(times))-1],'p99Ms':times[math.ceil(.99*len(times))-1],'outcomes':dict(collections.Counter(r['category'] for r in rows)),'codes':dict(collections.Counter(r['code'] for r in rows)),'successRate':sum(r['category']=='success' for r in rows)/len(rows)}
def burst(name,n,sid,repeat=False):
 before=snapshot();gate=threading.Barrier(n);key=uuid.uuid4().hex
 samples=[];stop=threading.Event()
 def monitor():
  while not stop.is_set():
   sample={}
   for metric in ('hikaricp.connections.active','hikaricp.connections.pending'):
    try:
     req=urllib.request.Request(a.base+'/actuator/metrics/'+metric,headers={'Authorization':'Bearer '+f['tokens'][0]})
     sample[metric]=json.load(op.open(req,timeout=3))['measurements']
    except Exception as error:sample[metric]=type(error).__name__
   samples.append(sample);stop.wait(.25)
 watcher=threading.Thread(target=monitor,daemon=True);watcher.start()
 def work(i):gate.wait(timeout=30);return request('/api/v1/sessions/%d/reservations'%sid,0 if repeat else i,{'request_id':key if repeat else uuid.uuid4().hex})
 start=time.perf_counter()
 with concurrent.futures.ThreadPoolExecutor(max_workers=n) as pool:rows=list(pool.map(work,range(n)))
 elapsed=time.perf_counter()-start;stop.set();watcher.join(timeout=8);after=snapshot()
 counts=sql("select x.capacity,x.occupied_count,(select count(*) from participation p where p.session_id=x.id and p.active_guard=1),(select count(*) from participation p where p.session_id=x.id and p.status in ('OFFERED','RESERVED','CHECKED_IN')) from activity_session x where x.id=%d"%sid).split('\t')
 cap,occupied,active,consuming=map(int,counts)
 duplicates=int(sql("select count(*) from (select user_id from participation where session_id=%d and active_guard=1 group by user_id having count(*)>1) d"%sid))
 outbox=int(sql("select count(*) from outbox where event_type='RESERVATION_CONFIRMED' and aggregate_id in (select id from participation where session_id=%d)"%sid))
 assert occupied==consuming<=cap and duplicates==0
 if name.startswith('B') or repeat:assert occupied==active==outbox==1
 value=dict(name=name,session=sid,concurrency=n,**summary(rows,elapsed),committedTPS=outbox/elapsed,capacity=cap,occupied=occupied,active=active,duplicateActive=duplicates,outboxConfirmed=outbox,before=before,after=after,raw=rows,poolSamples=samples)
 if repeat:
  # Committed replay after a deliberately tiny client read timeout remains one business operation.
  request('/api/v1/sessions/%d/reservations'%sid,0,{'request_id':key},timeout=.0001)
  value['replay']=request('/api/v1/sessions/%d/reservations'%sid,0,{'request_id':key})
  assert value['replay']['status']==200
 results.append(value);print(name,n,value['outcomes'],flush=True)
results=[]
for run in range(0 if a.fault_only else a.runs):
 for n in (50,100,200):burst('A-hot-run'+str(run+1),n,session(10));time.sleep(2)
 burst('B-last-run'+str(run+1),50,session(1));time.sleep(2)
 burst('C-idempotent-run'+str(run+1),20,session(1),True);time.sleep(2)
sid=session(1);key=uuid.uuid4().hex
# Fault injection only in the guarded _perf schema. Trigger failure occurs after the
# participation mutation, proving the original local transaction rolls everything back.
try:
 sql("create trigger perf_outbox_failure before insert on outbox for each row SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated performance rollback fixture'")
 failure=request('/api/v1/sessions/%d/reservations'%sid,0,{'request_id':key});assert failure['status']==500
 assert sql('select occupied_count from activity_session where id=%d'%sid)=='0'
 assert sql('select count(*) from participation where session_id=%d'%sid)=='0'
finally:sql('drop trigger if exists perf_outbox_failure')
retry=request('/api/v1/sessions/%d/reservations'%sid,0,{'request_id':key});assert retry['status']==200
assert sql('select occupied_count from activity_session where id=%d'%sid)=='1'
assert sql("select count(*) from outbox where event_type='RESERVATION_CONFIRMED' and aggregate_id in(select id from participation where session_id=%d)"%sid)=='1'
results.append({'name':'C-rollback-retry','injectedFailure':failure,'retry':retry,'rollbackVerified':True})
sid=session(1);key=uuid.uuid4().hex
try:
 sql('create trigger perf_outbox_delay before insert on outbox for each row DO SLEEP(0.3)')
 timed=request('/api/v1/sessions/%d/reservations'%sid,0,{'request_id':key},timeout=.05)
 assert timed['status']==0,'Expected deliberately delayed client timeout'
 time.sleep(.5)
finally:sql('drop trigger if exists perf_outbox_delay')
retried=request('/api/v1/sessions/%d/reservations'%sid,0,{'request_id':key});assert retried['status']==200
assert sql('select occupied_count from activity_session where id=%d'%sid)=='1'
assert sql('select count(*) from participation where session_id=%d'%sid)=='1'
assert sql("select count(*) from outbox where event_type='RESERVATION_CONFIRMED' and aggregate_id in(select id from participation where session_id=%d)"%sid)=='1'
results.append({'name':'C-timeout-before-response','clientTimeout':timed,'retry':retried,'singleQualificationAndEvent':True})
query_scenarios=[] if a.fault_only else [('list','/api/v1/activities?venueId='+str(f['venue'])),('sessions-warm','/api/v1/activities/%d/sessions'%f['activity']),('sessions-cold','/api/v1/activities/%d/sessions'%f['activity']),('statistics','/api/v1/admin/statistics?venueId=%d&from=2020-01-01&to=2030-01-01'%f['reportVenue']),('history-first','/api/v1/me/participations?page=1'),('history-deep','/api/v1/me/participations?page=1001')]
for mode,path in query_scenarios:
 request(path);before=snapshot();rows=[];start=time.perf_counter()
 for i in range(100):
  if mode=='sessions-cold':redis('DEL','display:activity:%d:sessions'%f['activity'])
  rows.append(request(path))
 results.append(dict(name='D-'+mode,concurrency=1,**summary(rows,time.perf_counter()-start),before=before,after=snapshot(),raw=rows))
 if mode!='sessions-cold':
  start=time.perf_counter()
  with concurrent.futures.ThreadPoolExecutor(max_workers=50) as pool:parallel=list(pool.map(lambda _:request(path),range(200)))
  results.append(dict(name='D-'+mode+'-parallel',concurrency=50,**summary(parallel,time.perf_counter()-start),raw=parallel))
metrics={}
for metric in ('hikaricp.connections.active','hikaricp.connections.max','hikaricp.connections.pending'):
 try:
  req=urllib.request.Request(a.base+'/actuator/metrics/'+metric,headers={'Authorization':'Bearer '+f['tokens'][0]});metrics[metric]=json.load(op.open(req))
 except Exception as e:metrics[metric]={'unavailable':type(e).__name__}
record={'tool':'Python stdlib barrier + urllib real HTTP (no keep-alive pool)','environment':{'os':platform.platform(),'python':platform.python_version(),'cpuCount':os.cpu_count(),'database':a.database,'mysql':sql('select version()'),'redis':redis('INFO','server').splitlines()[:4],'dataScale':sql('select (select count(*) from activity),(select count(*) from activity_session),(select count(*) from participation)')},'runs':a.runs,'results':results,'poolAfterLoad':metrics,'limits':['Burst concurrency is client concurrency, not admitted database concurrency. 429 is reported separately.','Shared isolated MySQL/Redis counters include monitoring and any other clients; not per-request tracing.','Pool metrics sampled after load, not peak utilization.','SQL digest instrumentation may be disabled; zero is not proof of zero queries.','No Kafka worker is required for committed Outbox correctness; delivery is not tested here.']}
out=root/a.output;out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(record,indent=2),encoding='utf-8');print('Real HTTP results:',out)
