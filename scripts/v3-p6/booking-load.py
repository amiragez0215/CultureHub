"""Real HTTP burst test. Requires dedicated fixtures; never targets a production database."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from collections import Counter
import importlib.util, json, os, secrets, statistics, time
from pathlib import Path
root=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location("p1",root/"scripts/v3-p1/run-local.py")
p1=importlib.util.module_from_spec(spec);spec.loader.exec_module(p1)
conf,_=p1.setup();http=p1.SMOKE;base="http://127.0.0.1:18110"
results=[]
env={**os.environ,**conf,"SPRING_PROFILES_ACTIVE":"v3-p2","DB_URL":p1.jdbc("core_db"),
 "V3_DISCOVERY_ENABLED":"false","RABBIT_HOST":"127.0.0.1","RABBIT_PORT":"5673",
 "RABBIT_USER":"guest","RABBIT_PASSWORD":"guest","RABBIT_VHOST":"culturehub_v3p1_audit",
 "REDIS_HOST":"127.0.0.1","REDIS_PORT":"16379","JOBS_ENABLED":"true","XXL_JOB_ADMIN":"",
 "AI_CHAT_PROVIDER":"none","AI_EMBEDDING_PROVIDER":"none","AI_VECTORSTORE":"none","DEMO_PASSWORD":""}
with ExitStack() as processes:
 core=p1.spawn(processes,"p6-core","culturehub-api",18110,env)
 p1.ready("p6-core",core,18110,"/actuator/health/readiness")
 tokens=[]
 for i in range(200):
  auth={"username":"v3load_"+secrets.token_hex(8),"password":secrets.token_hex(16)}
  http.expect(http.call(base,"/api/v1/auth/register","POST",auth),200,"fixture register")
  login,_=http.expect(http.call(base,"/api/v1/auth/login","POST",auth),200,"fixture login")
  tokens.append(login["token"])
 for count in (50,100,200):
  slot=int(p1.root_sql("""USE core_db;
   insert into venue(name) values('V3 isolated load fixture');
   insert into activity(venue_id,title,status) values(last_insert_id(),'V3 load','PUBLISHED');
   insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,
    checkin_open_at,checkin_close_at,capacity,status)
   values(last_insert_id(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),
    date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),date_add(now(),interval 2 hour),
    date_add(now(),interval 3 hour),25,'PUBLISHED'); select last_insert_id();"""))
  def submit(token):
   request=secrets.token_hex(16);start=time.perf_counter()
   try:
    status,body,_=http.call(base,f"/api/v1/sessions/{slot}/reservations","POST",{"request_id":request},token)
    category="accepted" if status in (200,202) else "rate_limited" if status==429 else "system_error" if status>=500 else body.get("code","business_rejection")
   except Exception:
    status=0;category="transport_error"
   return token,request,(time.perf_counter()-start)*1000,category
  start=time.perf_counter()
  with ThreadPoolExecutor(max_workers=count) as pool: samples=list(pool.map(submit,tokens[:count]))
  duration=time.perf_counter()-start;accepted=[sample for sample in samples if sample[3]=="accepted"]
  for token,request,_,_ in accepted:
   deadline=time.monotonic()+45
   while True:
    state,_=http.expect(http.call(base,"/api/v1/booking-requests/"+request,token=token),200,"terminal lookup")
    if state["requestStatus"] not in ("RECEIVED","QUEUED","PROCESSING"):break
    assert time.monotonic()<deadline,state
    time.sleep(.1)
  ledger=p1.root_sql(f"select occupied_count,capacity,(select count(*) from core_db.participation where session_id={slot} and status in ('RESERVED','OFFERED','CHECKED_IN')) from core_db.activity_session where id={slot}")
  occupied,capacity,valid=map(int,ledger.split())
  assert occupied==valid and 0<=occupied<=capacity,(occupied,capacity,valid)
  duplicate=int(p1.root_sql(f"select count(*) from (select user_id from core_db.participation where session_id={slot} and active_guard=1 group by user_id having count(*)>1) d"))
  assert duplicate==0
  terminal={}
  for line in p1.root_sql(f"select state,count(*) from core_db.booking_request where session_id={slot} group by state").splitlines():
   state,total=line.split();terminal[state]=int(total)
  latencies=sorted(sample[2] for sample in samples)
  result={"concurrency":count,"requests":count,"capacity":capacity,"occupied":occupied,"validQualifications":valid,
   "durationSeconds":duration,"ingressRps":count/duration,"meanMs":statistics.mean(latencies),
   "p95Ms":latencies[max(0,int(count*.95)-1)],"p99Ms":latencies[max(0,int(count*.99)-1)],
   "responses":dict(Counter(sample[3] for sample in samples)),"terminalStates":terminal,"duplicateQualifications":duplicate}
  results.append(result);print(json.dumps(result),flush=True)
 report={"scope":"real HTTP bursts; fixture preparation and terminal polling excluded from ingress latency",
  "runsPerConcurrency":1,"components":"Java17/MySQL8/Redis7/RabbitMQ4, local single machine",
  "databaseConcurrencyMeasured":False,"results":results,
  "limitations":["No sustained throughput claim", "No SQL or connection-pool instrumentation", "No full V3 gateway or AI path"]}
 target=root/"docs/evidence/v3-http-bursts.json";target.write_text(json.dumps(report,indent=2)+"\n",encoding="utf8")
