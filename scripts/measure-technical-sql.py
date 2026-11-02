# -*- coding: utf-8 -*-
"""Read-only EXPLAIN and repeat measurements; never invent production throughput from demo data."""
import argparse,json,re,subprocess,time
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--mysql',default='mysql');p.add_argument('--port',type=int,default=3306)
p.add_argument('--database',default='culturehub');p.add_argument('--user',default='root')
p.add_argument('--before',default='docs/evidence/technical-sql-before.json')
p.add_argument('--output',default='docs/evidence/technical-sql-after.json');args=p.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_]+',args.database):raise SystemExit('Invalid database')
cli=[args.mysql,'--no-defaults','--host=127.0.0.1','--port='+str(args.port),'--user='+args.user,'--database='+args.database,'--default-character-set=utf8mb4','--batch','--raw']
def sql(query):return subprocess.check_output(cli+['-e',query]).decode('utf-8')
before=json.loads(Path(args.before).read_text(encoding='utf-8'));queries={k:v['sql'] for k,v in before['queries'].items()}
ids=sql('select id from activity_session order by id limit 20').splitlines()[1:]
marks=','.join(str(int(n)) for n in ids) or '0'
queries['batch_participation']='select session_id,sum(status in (\'RESERVED\',\'CHECKED_IN\')) reserved,sum(status=\'CHECKED_IN\') checkedIn,sum(status=\'WAITING\') waiting,sum(status=\'OFFERED\') offered from participation where session_id in ('+marks+') group by session_id'
queries['batch_history']='select p.session_id,sum(h.to_status=\'RESERVED\') successCount,sum(h.from_status=\'RESERVED\' and h.to_status=\'CANCELLED\' and h.reason=\'USER_CANCEL\') cancelCount from participation p join participation_history h on h.participation_id=p.id where p.session_id in ('+marks+') group by p.session_id'
queries['activity_list_retained']="select a.id,a.title,(select min(x.start_at) from activity_session x where x.activity_id=a.id and x.status='PUBLISHED' and x.start_at>current_timestamp) next_start,(select count(*) from activity_session x where x.activity_id=a.id and x.status='PUBLISHED') session_count from activity a where a.status='PUBLISHED' order by a.id desc limit 20"
record={'database':args.database,'mysql':sql('select version()'),'scale':sql('select (select count(*) from activity_session) sessions,(select count(*) from participation) participations,(select count(*) from participation_history) transitions'),'queries':{},'timingNote':before['timingNote'],'statementCounts':{'originalDashboardExcludingAuthorization':'14 * N + 4','newDashboardNonEmptyExcludingAuthorization':6,'originalSingleSessionFacts':14,'newFactsBatch1To200':4}}
for name,query in queries.items():
    plan=sql('EXPLAIN '+query);durations=[]
    for _ in range(3):
        started=time.perf_counter();sql(query);durations.append(round((time.perf_counter()-started)*1000,3))
    record['queries'][name]={'sql':query,'explain':plan,'clientWallMs':durations}
# Compare complete old/new aggregation SQL shapes, same 20 IDs, one connection per repetition.
old=[]
for session in ids:
    filters=["status in ('RESERVED','CHECKED_IN')","status='CHECKED_IN'","status='WAITING'","status='OFFERED'","status='EXPIRED'","entry_type='WAITLIST'","entry_type='WAITLIST' and reserved_at is not null","end_reason='CONFIRM_TIMEOUT'"]
    old += ['select * from activity_session where id='+session,'select current_timestamp(6)']
    old += ['select count(*) from participation where session_id='+session+' and '+condition for condition in filters]
    old += ["select count(*) from participation_history h join participation p on p.id=h.participation_id where p.session_id="+session+" and h.to_status='RESERVED'","select count(*) from participation_history h join participation p on p.id=h.participation_id where p.session_id="+session+" and h.from_status='RESERVED' and h.to_status='CANCELLED' and h.reason='USER_CANCEL'",'select current_timestamp(6)','select count(*),avg(rating) from review where session_id='+session]
new=['select id,status,end_at,occupied_count,current_timestamp(6) as_of from activity_session where id in ('+marks+')',"select session_id,sum(status in ('RESERVED','CHECKED_IN')),sum(status='CHECKED_IN'),sum(status='WAITING'),sum(status='OFFERED'),sum(status='EXPIRED'),sum(entry_type='WAITLIST'),sum(entry_type='WAITLIST' and reserved_at is not null),sum(end_reason='CONFIRM_TIMEOUT') from participation where session_id in ("+marks+') group by session_id',queries['batch_history'],'select session_id,count(*),avg(rating) from review where session_id in ('+marks+') group by session_id']
record['batchMeasurement']={'sessions':len(ids),'note':'SQL work and mysql-client wall time only; not an HTTP load test. Same sample IDs, three repetitions, process/connection startup included.'}
for name,statements in [('original',old),('optimized',new)]:
    durations=[]
    for _ in range(3):
        started=time.perf_counter();sql(';'.join(statements));durations.append(round((time.perf_counter()-started)*1000,3))
    record['batchMeasurement'][name]={'statements':len(statements),'clientWallMs':durations}
Path(args.output).write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8');print('Read-only SQL evidence saved:',args.output)
