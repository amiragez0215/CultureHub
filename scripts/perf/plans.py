"""Read-only SQL plans and wall-time comparisons on the dedicated performance fixture."""
import json,subprocess,time,argparse,shutil
from pathlib import Path
root=Path(__file__).resolve().parents[2];f=json.loads((root/'.tools/perf-fixture.json').read_text());assert f['database'].endswith('_perf')
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--mysql',default=shutil.which('mysql') or 'mysql');p.add_argument('--port',type=int,default=13306);a=p.parse_args()
cli=[a.mysql,'--no-defaults','--host=127.0.0.1','--port='+str(a.port),'--user=root','--database='+f['database'],'--batch','--raw']
def sql(q):return subprocess.check_output(cli,input=q.encode()).decode()
queries={
 'activity-before':"select a.*,v.name venue_name,(select min(x.start_at) from activity_session x where x.activity_id=a.id and x.status='PUBLISHED' and x.end_at>current_timestamp) next_start_at,(select count(*) from activity_session x where x.activity_id=a.id and x.status='PUBLISHED') session_count from activity a join venue v on v.id=a.venue_id where (0=1 or a.status='PUBLISHED') and (1=1 or exists(select 1 from venue_grant g where g.venue_id=a.venue_id and g.user_id=0)) and a.title like '%%' and (''='' or a.category='') and (%d=0 or a.venue_id=%d) and ('PUBLISHED'='ALL' or ('PUBLISHED'='ACTIVE' and a.status<>'CANCELLED') or a.status='PUBLISHED') order by a.id desc limit 20"%(f['venue'],f['venue']),
 'activity-after':"select a.*,v.name venue_name,(select min(x.start_at) from activity_session x where x.activity_id=a.id and x.status='PUBLISHED' and x.end_at>current_timestamp) next_start_at,(select count(*) from activity_session x where x.activity_id=a.id and x.status='PUBLISHED') session_count from activity a join venue v on v.id=a.venue_id where a.status='PUBLISHED' and a.venue_id=%d order by a.id desc limit 20"%f['venue']}
uid=f['users'][0]
for offset in (0,20000):
 queries['history-join-'+str(offset)]="select p.id,p.session_id,p.status,a.title,v.name from participation p join activity_session x on x.id=p.session_id join activity a on a.id=x.activity_id join venue v on v.id=a.venue_id where p.user_id=%d order by p.id desc limit 20 offset %d"%(uid,offset)
 queries['history-deferred-'+str(offset)]="select p.id,p.session_id,p.status,a.title,v.name from (select id,session_id,status from participation where user_id=%d order by id desc limit 20 offset %d) p join activity_session x on x.id=p.session_id join activity a on a.id=x.activity_id join venue v on v.id=a.venue_id order by p.id desc"%(uid,offset)
record={'schema':f['database'],'scale':sql('select (select count(*) from activity) activities,(select count(*) from participation) participations'),'note':'Each measurement includes mysql client startup/connection; not pure server latency. Deferred history is evaluated, not automatically adopted.','queries':{}}
for name,q in queries.items():
 times=[];output=sql(q)
 for _ in range(3):start=time.perf_counter();sql(q);times.append((time.perf_counter()-start)*1000)
 record['queries'][name]={'sql':q,'explain':sql('explain '+q),'clientWallMs':times}
for offset in (0,20000):assert sql(queries['history-join-'+str(offset)])==sql(queries['history-deferred-'+str(offset)]),'Pagination content changed'
out=root/'docs/evidence/orm-query-plans.json';out.write_text(json.dumps(record,indent=2),encoding='utf-8');print('Plans and equal-row deep pagination comparison saved.')
