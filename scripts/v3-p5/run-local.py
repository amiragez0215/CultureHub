"""Real Core/AI split test. No real LLM claims; injects a verified plan fixture."""
from contextlib import ExitStack
from pathlib import Path
import importlib.util,json,os,secrets,time,uuid
spec=importlib.util.spec_from_file_location("p1",Path(__file__).resolve().parents[1]/"v3-p1/run-local.py")
p1=importlib.util.module_from_spec(spec);spec.loader.exec_module(p1)
conf,_=p1.setup();http=p1.SMOKE
for migration in ("20261111-v3-booking.sql", "20261114-v3-seata-at.sql", "20261114-v3-combination.sql"):
 p1.root_sql("USE core_db;"+(p1.ROOT/"deploy/migrations"/migration).read_text(encoding="utf8"))
p1.root_sql("USE ai_db;"+(p1.ROOT/"deploy/migrations/20261112-v3-ai.sql").read_text(encoding="utf8"))
p1.root_sql("USE ai_db;"+(p1.ROOT/"deploy/migrations/20261112-v3-ai-group.sql").read_text(encoding="utf8"))
env={**os.environ,**conf,"V3_DISCOVERY_ENABLED":"false","RABBIT_HOST":"127.0.0.1","RABBIT_PORT":"5673","RABBIT_USER":"guest","RABBIT_PASSWORD":"guest","RABBIT_VHOST":"culturehub_v3p1_audit","REDIS_HOST":"127.0.0.1","REDIS_PORT":"16379","JOBS_ENABLED":"true","XXL_JOB_ADMIN":"","DEMO_PASSWORD":"","AI_CHAT_PROVIDER":"none","AI_EMBEDDING_PROVIDER":"none","AI_VECTORSTORE":"none","V3_AI_KNOWLEDGE_TOKEN":secrets.token_hex(24)}
corebase="http://127.0.0.1:18110";aibase="http://127.0.0.1:18130"
with ExitStack() as processes:
 core=p1.spawn(processes,"p5-core","culturehub-api",18110,{**env,"SPRING_PROFILES_ACTIVE":"v3-p5","DB_URL":p1.jdbc("core_db")})
 p1.ready("p5-core",core,18110,"/actuator/health/readiness")
 ai=p1.spawn(processes,"p5-ai","culturehub-ai",18130,{**env,"AI_DB_URL":p1.jdbc("ai_db"),"V3_CORE_URL":corebase,"V3_KNOWLEDGE_URL":"http://127.0.0.1:18999"})
 p1.ready("p5-ai",ai,18130)
 def account():
  auth={"username":"p5_"+secrets.token_hex(8),"password":secrets.token_hex(16)}
  user,_=http.expect(http.call(corebase,"/api/v1/auth/register","POST",auth),200,"register")
  login,_=http.expect(http.call(corebase,"/api/v1/auth/login","POST",auth),200,"login")
  return user["id"],login["token"]
 user,token=account();_,other=account()
 conv,_=http.expect(http.call(aibase,"/api/v1/ai/conversations","POST",{},token),200,"AI conversation")
 conversation=conv["id"]
 status,unconfigured,_=http.call(aibase,"/api/v1/ai/conversations/"+conversation+"/messages","POST",{"message":"推荐文化活动"},token)
 assert status==503 and unconfigured.get("code")=="AI_UNAVAILABLE",(status,unconfigured)
 assert p1.root_sql(f"select count(*) from ai_db.ai_guide_conversation where id='{conversation}' and user_id={user}")=="1"
 assert http.call(aibase,"/api/v1/ai/conversations/"+conversation+"/saved-plan",token=other)[0]==403
 assert http.call(corebase,"/api/v1/ai/conversations","POST",{},token)[0]==403
 print("PASS: AI starts without model key; conversation stored in ai_db; foreign owner and Core AI route denied",flush=True)
 slot=int(p1.root_sql("""USE core_db;
 insert into venue(name) values('AI split fixture');
 insert into activity(venue_id,title,status) values(last_insert_id(),'AI activity','PUBLISHED');
 insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status)
 values(last_insert_id(),date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),2,'PUBLISHED');select last_insert_id();"""))
 rpc_headers={"X-CultureHub-Service":"culturehub-ai","X-CultureHub-Service-Token":conf["V3_AI_CORE_TOKEN"]}
 card,_=http.expect(http.call(corebase,"/api/v1/service/ai/query","POST",{"tool":"SESSION_DETAILS","sessionId":slot},token,rpc_headers),200,"RPC card")
 assert http.call(corebase,"/api/v1/service/ai/query","POST",{"tool":"MY_ACTIVE_BOOKINGS"},token)[0] in (400,401)
 plan=str(uuid.uuid4());candidate=str(uuid.uuid4())
 state={"identity":{"conversationId":conversation,"turnId":str(uuid.uuid4()),"traceId":str(uuid.uuid4()),"ownerUserId":user,"latestUserMessage":"test fixture","currentTime":card["result"]["asOf"]},"requirements":{"intentType":"ACTIVITY_SEARCH","hardConstraints":{},"softPreferences":[],"requestedActivityCount":1,"minimumGapMinutes":10}}
 def literal(value):return "CONVERT(0x"+json.dumps(value,ensure_ascii=False).encode().hex()+" USING utf8mb4)"
 p1.root_sql(f"""USE ai_db;
 insert into ai_activity_plan(plan_id,user_id,conversation_id,version,constraints_snapshot,verification_status,expires_at) values('{plan}',{user},'{conversation}',0,{literal(state)},'VERIFIED',date_add(now(),interval 5 minute));
 insert into ai_activity_plan_item(plan_id,plan_version,session_id,display_order,candidate_id,session_snapshot) values('{plan}',0,{slot},0,'{candidate}',{literal(card['result'])});""")
 choice={"candidateId":candidate,"mode":"DIRECT"}
 proposal,_=http.expect(http.call(aibase,f"/api/v1/ai/conversations/{conversation}/proposals","POST",choice,token),200,"select proposal")
 assert proposal["status"]=="PENDING"
 assert p1.root_sql(f"select count(*) from core_db.participation where user_id={user} and session_id={slot}")=="0"
 path="/api/v1/ai/booking-proposals/"+proposal["id"]
 status,body,_=http.call(aibase,path+"/confirm","POST",{},token);assert status in (200,202),(status,body)
 deadline=time.monotonic()+30
 while True:
  body,_=http.expect(http.call(aibase,path,token=token),200,"proposal result")
  if body["status"]=="SUCCEEDED":break
  assert time.monotonic()<deadline,body
  time.sleep(.2)
 http.expect(http.call(aibase,path+"/confirm","POST",{},token),200,"replay confirmation")
 assert p1.root_sql(f"select count(*) from core_db.participation where user_id={user} and session_id={slot}")=="1"
 assert p1.root_sql(f"select status from ai_db.ai_booking_proposal where id='{proposal['id']}'")=="SUCCEEDED"
 print("PASS: selection never books; explicit confirm uses real Redis/Stream/Rabbit/Worker; ai_db recovers one stable Core result",flush=True)

 group_plan=str(uuid.uuid4());group_slots=[]
 activity=int(p1.root_sql(f"select activity_id from core_db.activity_session where id={slot}"))
 for hour in (4,6):
  group_slots.append(int(p1.root_sql(f"""USE core_db;
   insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status)
   values({activity},date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),date_add(now(),interval {hour} hour),date_add(now(),interval {hour+1} hour),date_add(now(),interval {hour} hour),date_add(now(),interval {hour+1} hour),2,'PUBLISHED');select last_insert_id();""")))
 state['requirements']['intentType']='ACTIVITY_PLAN';state['requirements']['requestedActivityCount']=2
 p1.root_sql(f"""USE ai_db;update ai_activity_plan set status='SUPERSEDED' where plan_id='{plan}';update ai_guide_conversation set version=1 where id='{conversation}';
 insert into ai_activity_plan(plan_id,user_id,conversation_id,version,constraints_snapshot,verification_status,expires_at) values('{group_plan}',{user},'{conversation}',1,{literal(state)},'VERIFIED',date_add(now(),interval 5 minute));""")
 for order,session in enumerate(group_slots):
  p1.root_sql(f"insert into ai_db.ai_activity_plan_item(plan_id,plan_version,session_id,display_order,candidate_id,session_snapshot) values('{group_plan}',1,{session},{order},'{uuid.uuid4()}','{{}}')")
 group_path='/api/v1/ai/plans/'+group_plan+'/combination'
 assert http.call(aibase,group_path,'POST',{'version':1,'confirmed':False,'retainPartial':False},token)[0] in (400,409)
 assert p1.root_sql(f"select count(*) from ai_db.ai_activity_plan where plan_id='{group_plan}' and group_request_id is not null")=='0'
 group,_=http.expect(http.call(aibase,group_path,'POST',{'version':1,'confirmed':True,'retainPartial':False},token),200,'explicit AI group authorization')
 deadline=time.monotonic()+40
 while group['status']!='SUCCEEDED':
  assert time.monotonic()<deadline,group
  group,_=http.expect(http.call(aibase,group_path,token=token),200,'AI group status');time.sleep(.2)
 same,_=http.expect(http.call(aibase,group_path,'POST',{'version':1,'confirmed':True,'retainPartial':False},token),200,'AI group replay')
 assert same['id']==group['id']
 assert p1.root_sql(f"select count(*) from core_db.participation where user_id={user} and session_id in ({','.join(map(str,group_slots))}) and status='RESERVED'")=='2'
 print('PASS: AI group authorization persists one stable request; Core confirms both sessions in one local transaction; repeat returns the same group',flush=True)
 history,_=http.expect(http.call(aibase,'/api/v1/ai/group-requests',token=token),200,'saved authorizations')
 assert any(row['plan_id']==group_plan for row in history)
 print('PASS: AI authorization history can recover the original group request after page reload',flush=True)
