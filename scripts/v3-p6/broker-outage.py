"""Stop/restart ONLY the dedicated v3p1_audit broker. Requires explicit environment guard."""
from contextlib import ExitStack
from pathlib import Path
import importlib.util,json,os,secrets,socket,subprocess,time
if os.environ.get('V3_FAULT_TEST_CONFIRM')!='isolated-v3p1-audit':raise SystemExit('Set V3_FAULT_TEST_CONFIRM=isolated-v3p1-audit; never run against production')
spec=importlib.util.spec_from_file_location('p1',Path(__file__).resolve().parents[1]/'v3-p1/run-local.py')
p1=importlib.util.module_from_spec(spec);spec.loader.exec_module(p1)
conf,_=p1.setup();http=p1.SMOKE
runtime=Path(os.environ.get('V3_RABBIT_RUNTIME',p1.ROOT/'.tools/rabbit-runtime'))
fixture=p1.ROOT/'.tools/v3-p1/local-fixtures.ps1'
if os.name!='nt' or not fixture.exists():raise SystemExit('This fixture runner requires the dedicated Windows broker helper')
env={**os.environ,**conf,'ERLANG_HOME':str(runtime/'erlang'),'RABBITMQ_NODENAME':'culturehub_v3p1_audit@localhost','RABBITMQ_CTL_ERL_ARGS':'-setcookie culturehub_v3p1_local_audit +S 2:2',
 'SPRING_PROFILES_ACTIVE':'v3-p5','DB_URL':p1.jdbc('core_db'),'V3_DISCOVERY_ENABLED':'false',
 'RABBIT_HOST':'127.0.0.1','RABBIT_PORT':'5673','RABBIT_USER':'guest','RABBIT_PASSWORD':'guest','RABBIT_VHOST':'culturehub_v3p1_audit',
 'REDIS_HOST':'127.0.0.1','REDIS_PORT':'16379','JOBS_ENABLED':'true','XXL_JOB_ADMIN':'','DEMO_PASSWORD':'',
 'AI_CHAT_PROVIDER':'none','AI_EMBEDDING_PROVIDER':'none','AI_VECTORSTORE':'none'}
base='http://127.0.0.1:18110';stopped=False
def restart():
 log=open(p1.WORK/'p6-broker-restart.log','wb')
 process=subprocess.Popen(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(fixture),'-Action','BrokerRun'],stdout=log,stderr=log,creationflags=p1.NO_WINDOW)
 log.close()
 (p1.WORK/'rabbit.pid').write_text(str(process.pid))
 deadline=time.monotonic()+45
 while True:
  try:
   with socket.create_connection(('127.0.0.1',5673),timeout=1):return
  except OSError:
   assert time.monotonic()<deadline,'Dedicated broker did not restart';time.sleep(.5)
try:
 command=runtime/'rabbitmq/rabbitmq_server-4.1.4/sbin/rabbitmqctl.bat'
 subprocess.run([str(command),'shutdown'],env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True,timeout=30,creationflags=p1.NO_WINDOW)
 stopped=True
 with ExitStack() as processes:
  core=p1.spawn(processes,'p6-broker-down-core','culturehub-api',18110,env)
  p1.ready('p6-broker-down-core',core,18110,'/actuator/health/readiness')
  auth={'username':'outage_'+secrets.token_hex(8),'password':secrets.token_hex(16)}
  user,_=http.expect(http.call(base,'/api/v1/auth/register','POST',auth),200,'register')
  login,_=http.expect(http.call(base,'/api/v1/auth/login','POST',auth),200,'login');token=login['token']
  slot=int(p1.root_sql("""USE core_db;insert into venue(name) values('Broker outage fixture');insert into activity(venue_id,title,status) values(last_insert_id(),'Broker outage','PUBLISHED');insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status) values(last_insert_id(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),1,'PUBLISHED');select last_insert_id();"""))
  request=secrets.token_hex(16)
  http.expect(http.call(base,f'/api/v1/sessions/{slot}/reservations','POST',{'request_id':request},token),202,'queue while broker down')
  assert p1.root_sql(f"select state from core_db.booking_request where request_id='{request}'")=='QUEUED'
  assert p1.root_sql(f"select count(*) from core_db.participation where session_id={slot}")=='0'
  print('PASS: API starts with broker stopped; HTTP 202 retains durable request, no fabricated participation',flush=True)
  restart();stopped=False;deadline=time.monotonic()+45
  while True:
   result,_=http.expect(http.call(base,'/api/v1/booking-requests/'+request,token=token),200,'recover request')
   if result['requestStatus']=='CONFIRMED':break
   assert time.monotonic()<deadline,result;time.sleep(.3)
  assert p1.root_sql(f"select count(*) from core_db.participation where session_id={slot}")=='1'
  assert p1.root_sql(f"select occupied_count from core_db.activity_session where id={slot}")=='1'
  (p1.ROOT/'docs/evidence/v3-broker-outage.json').write_text(json.dumps({'realBrokerStopped':True,'coreStartsWithoutBroker':True,'acceptedWhileUnavailable':True,'restoredToOneQualification':True,'scope':'dedicated MySQL/Redis/RabbitMQ, no simulated broker acknowledgements'},indent=2)+'\n',encoding='utf8')
  print('PASS: broker restart recovers the original Stream request and creates exactly one qualification',flush=True)
finally:
 if stopped:restart()
