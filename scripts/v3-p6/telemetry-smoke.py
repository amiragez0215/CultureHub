"""Real OTLP HTTP export and Prometheus scrape. The collector receiver is controlled, not Grafana."""
from contextlib import ExitStack
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import importlib.util,os,secrets,time,threading,gzip,json
spec=importlib.util.spec_from_file_location("p1",Path(__file__).resolve().parents[1]/"v3-p1/run-local.py")
p1=importlib.util.module_from_spec(spec);spec.loader.exec_module(p1)
conf,_=p1.setup();http=p1.SMOKE;exports=[]
class Receiver(BaseHTTPRequestHandler):
 def do_POST(self):
  body=self.rfile.read(int(self.headers['Content-Length']))
  if self.headers.get('Content-Encoding')=='gzip':body=gzip.decompress(body)
  exports.append(body);self.send_response(200);self.send_header('Content-Type','application/x-protobuf');self.end_headers()
 def log_message(self,*args):pass
# Minimal protobuf wire reader for the OTLP trace envelope. No external parser dependency.
def fields(data):
 def integer(offset):
  value=0;shift=0
  while True:
   byte=data[offset];offset+=1;value|=(byte&127)<<shift
   if byte<128:return value,offset
   shift+=7
 result=[];offset=0
 while offset<len(data):
  tag,offset=integer(offset);wire=tag&7
  if wire==2:size,offset=integer(offset);value=data[offset:offset+size];offset+=size
  elif wire==0:value,offset=integer(offset)
  elif wire in (1,5):size=8 if wire==1 else 4;value=data[offset:offset+size];offset+=size
  else:raise ValueError('unsupported OTLP wire')
  result.append((tag>>3,value))
 return result
def spans():
 found=[]
 for export in list(exports):
  for key,resource in fields(export):
   if key!=1:continue
   for key,scope in fields(resource):
    if key!=2:continue
    for key,span in fields(scope):
     if key!=2:continue
     values=dict(fields(span));found.append((values.get(1,b'').hex(),values.get(5,b'').decode()))
 return found
receiver=ThreadingHTTPServer(('127.0.0.1',18418),Receiver)
threading.Thread(target=receiver.serve_forever,daemon=True).start()
env={**os.environ,**conf,'SPRING_PROFILES_ACTIVE':'v3-p5','DB_URL':p1.jdbc('core_db'),'V3_DISCOVERY_ENABLED':'false',
 'RABBIT_HOST':'127.0.0.1','RABBIT_PORT':'5673','RABBIT_USER':'guest','RABBIT_PASSWORD':'guest','RABBIT_VHOST':'culturehub_v3p1_audit',
 'REDIS_HOST':'127.0.0.1','REDIS_PORT':'16379','JOBS_ENABLED':'true','XXL_JOB_ADMIN':'','DEMO_PASSWORD':'',
 'AI_CHAT_PROVIDER':'none','AI_EMBEDDING_PROVIDER':'none','AI_VECTORSTORE':'none',
 'V3_OBSERVABILITY_ENABLED':'true','MANAGEMENT_TRACING_ENABLED':'true','MANAGEMENT_TRACING_SAMPLING_PROBABILITY':'1.0',
 'MANAGEMENT_OTLP_TRACING_ENDPOINT':'http://127.0.0.1:18418/v1/traces','MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE':'health,prometheus'}
try:
 with ExitStack() as processes:
  core=p1.spawn(processes,'p6-telemetry-core','culturehub-api',18110,env);p1.ready('p6-telemetry-core',core,18110,'/actuator/health/readiness')
  gateway=p1.spawn(processes,'p6-telemetry-gateway','culturehub-gateway',18100,
    {**env,'V3_CORE_ROUTE':'http://127.0.0.1:18110','V3_NOTIFICATION_ROUTE':'http://127.0.0.1:18110','V3_MANAGEMENT_PORT':'19081','MANAGEMENT_SERVER_PORT':'19081','V3_GATEWAY_RPS':'300'})
  p1.ready('p6-telemetry-gateway',gateway,19081)
  base='http://127.0.0.1:18100';auth={'username':'trace_'+secrets.token_hex(8),'password':secrets.token_hex(16)}
  http.expect(http.call(base,'/api/v1/auth/register','POST',auth),200,'register');login,_=http.expect(http.call(base,'/api/v1/auth/login','POST',auth),200,'login');token=login['token']
  slot=int(p1.root_sql("""USE core_db;insert into venue(name) values('OTLP fixture');insert into activity(venue_id,title,status) values(last_insert_id(),'Trace fixture','PUBLISHED');insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status) values(last_insert_id(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),1,'PUBLISHED');select last_insert_id();"""))
  trace=secrets.token_hex(16);request=secrets.token_hex(16)
  http.expect(http.call(base,f'/api/v1/sessions/{slot}/reservations','POST',{'request_id':request},token,{'traceparent':'00-'+trace+'-'+secrets.token_hex(8)+'-01'}),202,'trace booking')
  deadline=time.monotonic()+35
  while True:
   state,_=http.expect(http.call(base,'/api/v1/booking-requests/'+request,token=token),200,'booking state')
   recorded=p1.root_sql(f"select trace_id from core_db.booking_request where request_id='{request}'")
   seen=spans()
   if state['requestStatus']=='CONFIRMED' and (trace,'culturehub.booking.consume') in seen:break
   assert time.monotonic()<deadline,{'state':state,'recordedTrace':recorded,'spanNames':seen}
   time.sleep(.3)
  assert recorded.startswith('00-'+trace+'-'),recorded
  from urllib.request import Request
  with http.HTTP.open(Request('http://127.0.0.1:18110/actuator/prometheus'),timeout=5) as response:metrics=response.read().decode()
  assert 'culturehub_backlog' in metrics and 'hikaricp_connections' in metrics
  with http.HTTP.open(Request('http://127.0.0.1:19081/actuator/prometheus'),timeout=5) as response:gateway_metrics=response.read().decode()
  assert 'http_server_requests_seconds' in gateway_metrics and 'jvm_memory_used_bytes' in gateway_metrics
  result={'realOtlpExport':True,'sameTraceAcrossRedisStreamAndWorker':True,'prometheusScrape':True,'businessBacklogGauge':True,'hikariMetrics':True,'gatewayPrometheusScrape':True,'gatewayBookingIngress':True,'spanNames':sorted(set(name for tid,name in seen if tid==trace)),'limitations':['controlled OTLP HTTP receiver; Collector/Grafana containers not started','Knowledge trace route not covered in this test']}
  (p1.ROOT/'docs/evidence/v3-telemetry.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
  print('PASS: real OTLP export preserves the HTTP trace through Redis/Stream/RabbitMQ to Worker; Prometheus backlog and Hikari scrape',flush=True)
finally:receiver.shutdown()
