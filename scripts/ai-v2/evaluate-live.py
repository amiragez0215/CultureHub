# -*- coding: utf-8 -*-
from pathlib import Path
import datetime,json,subprocess,time,urllib.request,urllib.error,socket,shutil
import argparse
root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description='Real-model read-only V1/V2 evaluation; never confirms bookings.')
parser.add_argument('--mysql',default='D:/mysql/mysqlx/bin/mysql.exe')
parser.add_argument('--java',default='D:/Test_Soft/jdk17/bin/java.exe')
parser.add_argument('--fixture',action='store_true',help='Use the isolated two-activity MySQL fixture instead of the demo catalog')
args=parser.parse_args()
database='culturehub_ai_v2_eval' if args.fixture else 'culturehub_rich_demo'
(root/'.tools').mkdir(exist_ok=True)
def sql(text, schema=database):
    r=subprocess.run([args.mysql,'--protocol=TCP','-h127.0.0.1','-P13306','-uroot','--default-character-set=utf8mb4','--batch','--skip-column-names']+([schema] if schema else []),input=text.encode('utf-8'),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if r.returncode: raise RuntimeError(r.stderr.decode('utf-8',errors='replace'))
    return r.stdout.decode().strip()
if args.fixture:
    sql('CREATE DATABASE IF NOT EXISTS culturehub_ai_v2_eval CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;',None)
    if sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='culturehub_ai_v2_eval' AND table_name='app_user';")=='0':
        ddl=(root/'deploy/mysql/01-schema.sql').read_text(encoding='utf-8')
        sql('\n'.join(ddl.splitlines()[2:]))
    if sql('select count(*) from activity_session;')=='0':
        sql("INSERT INTO venue(id,name) VALUES(1,'文化规划验收场馆') ON DUPLICATE KEY UPDATE name=VALUES(name);"
            "INSERT INTO activity(id,venue_id,title,category,description,status) VALUES"
            "(1,1,'剪纸艺术体验','HERITAGE','体验传统剪纸。','PUBLISHED'),"
            "(2,1,'城市历史讲座','LECTURE','了解城市文化和建筑。','PUBLISHED') ON DUPLICATE KEY UPDATE title=VALUES(title);"
            "INSERT INTO activity_session(id,activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status) VALUES"
            "(1,1,now()-interval 1 day,current_date+interval 33 hour,current_date+interval 34 hour,current_date+interval 36 hour,current_date+interval 33 hour,current_date+interval 36 hour,10,'PUBLISHED'),"
            "(2,2,now()-interval 1 day,current_date+interval 37 hour,current_date+interval 38 hour,current_date+interval 40 hour,current_date+interval 37 hour,current_date+interval 40 hour,10,'PUBLISHED');")
    else:
        sql("UPDATE activity_session SET registration_start_at=now()-interval 1 day,registration_end_at=current_date+interval (29+4*id) hour,start_at=current_date+interval (30+4*id) hour,end_at=current_date+interval (32+4*id) hour,checkin_open_at=current_date+interval (29+4*id) hour,checkin_close_at=current_date+interval (32+4*id) hour WHERE id IN (1,2);")
    assert sql('select count(*) from activity_session where id in (1,2);')=='2'
sql((root/'deploy/migrations/20261107-ai-guide-v2.sql').read_text(encoding='utf-8'))
baseline=sql('select count(*) from participation;')
results=[]
evaluation_user='v2_eval_'+str(int(time.time()))
shutil.copyfile(root/'culturehub-api/target/culturehub-api-1.0.0.jar',root/'.tools/v2-live-api.jar')
for enabled,port in [(False,18094),(True,18095)]:
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1',port))==0:raise RuntimeError('Test port already used')
    output=(root/('.tools/v2-live-'+str(enabled)+'.log')).open('wb')
    process=subprocess.Popen([args.java,'-jar',str(root/'.tools/v2-live-api.jar'),
        '--server.address=127.0.0.1','--server.port='+str(port),'--spring.datasource.url=jdbc:mysql://127.0.0.1:13306/'+database+'?connectionTimeZone=Asia/Shanghai',
        '--spring.datasource.password=','--spring.data.redis.port=16379','--culture.jwt.secret=local-v2-readonly-model-check-secret-2026',
        '--culture.demo.password=','--culture.demo.rich=false','--culture.jobs.enabled=false','--culture.ai.guide-v2.enabled='+str(enabled).lower()],
        cwd=root,stdout=output,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
    token=None
    def request(path,body=None):
        h={'Content-Type':'application/json'}
        if token:h['Authorization']='Bearer '+token
        req=urllib.request.Request('http://127.0.0.1:'+str(port)+path,headers=h,data=json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(req,timeout=100) as response:return json.load(response)
    try:
        ready=False
        for attempt in range(100):
            try:request('/api/v1/activities');ready=True;break
            except Exception:time.sleep(.3)
        if not ready:raise RuntimeError('API startup failed')
        if not enabled:request('/api/v1/auth/register',{'username':evaluation_user,'password':'123'})
        token=request('/api/v1/auth/login',{'username':evaluation_user,'password':'123'})['token']
        for message in ['请查找明天可以报名的文化活动，时间和类别都可以。','请在明天安排两个同一场馆且时间不冲突的活动，必须两个，没有合适组合请明确说明。']:
            started=time.monotonic()
            try:
                conversation=request('/api/v1/ai/conversations',{})['id']
                reply=request('/api/v1/ai/conversations/'+conversation+'/messages',{'message':message})
                results.append({'version':'V2' if enabled else 'V1','question':message,'elapsedSeconds':round(time.monotonic()-started,3),'phase':reply.get('phase'),
                    'candidateSessionIds':[c['sessionId'] for c in reply.get('candidates',[])],'plan':reply.get('plan'),'usage':reply.get('usage'),'explanation':reply.get('explanation'),'error':None})
            except Exception as e:
                record={'version':'V2' if enabled else 'V1','question':message,'elapsedSeconds':round(time.monotonic()-started,3),'error':type(e).__name__}
                if isinstance(e,urllib.error.HTTPError):
                    try:record['response']=json.load(e)
                    except Exception:pass
                results.append(record)
    finally:
        process.terminate()
        try:process.wait(15)
        except subprocess.TimeoutExpired:process.kill();process.wait(10)
        output.close()
assert sql('select count(*) from participation;')==baseline,'Read-only model check created participation!'
out=root/'docs/evidence'/('ai-v2-live-two-activity.json' if args.fixture else 'ai-v2-live-model.json')
out.write_text(json.dumps({'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'database':database,'attemptedRealModel':True,'successfulRealResponses':sum(r.get('error') is None for r in results),'provider':'existing external configuration','readOnlyNoBookingConfirmed':True,'tokenCostMeasured':False,'subjectiveQualityScored':False,'results':results},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'results':len(results),'errors':sum(r.get('error') is not None for r in results),'readOnly':True}))
