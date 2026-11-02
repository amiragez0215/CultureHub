"""Read-only audit for a dedicated demo DB, using mysql CLI."""
import argparse,json,subprocess
p=argparse.ArgumentParser();p.add_argument('--mysql',default='mysql');p.add_argument('--database',default='culturehub_demo');p.add_argument('--port',type=int,default=3306);p.add_argument('--user',default='root');args=p.parse_args()
if not args.database.endswith('_demo'):raise SystemExit('Use a dedicated _demo database')
connection=[args.mysql,'--no-defaults','--host=127.0.0.1','--port='+str(args.port),'--user='+args.user,'--database='+args.database,'--batch','--skip-column-names','--default-character-set=utf8mb4']
def query(sql):return subprocess.check_output(connection+['-e',sql]).decode('utf-8').strip()
counts={table:int(query('select count(*) from '+table)) for table in ['venue','activity','activity_session','app_user','participation','checkin_record','review']}
checks={
 'capacityMismatch':"select count(*) from activity_session x where x.occupied_count<>(select count(*) from participation p where p.session_id=x.id and p.status in ('OFFERED','RESERVED','CHECKED_IN')) or x.occupied_count>x.capacity",
 'duplicateActive':"select count(*) from (select user_id,session_id from participation where active_guard=1 group by user_id,session_id having count(*)>1) t",
 'checkinMismatch':"select count(*) from participation p left join checkin_record c on c.participation_id=p.id where (p.status='CHECKED_IN' and c.id is null) or (c.id is not null and p.status not in ('CHECKED_IN','CANCELLED'))",
 'invalidReviews':"select count(*) from review r join participation p on p.id=r.participation_id join activity_session x on x.id=p.session_id left join checkin_record c on c.participation_id=p.id where c.id is null or p.status<>'CHECKED_IN' or x.end_at>current_timestamp or x.status='CANCELLED'",
 'cancelledOccupancy':"select count(*) from activity_session where status='CANCELLED' and occupied_count<>0",
 'invalidOfferDeadline':"select count(*) from participation p join activity_session x on x.id=p.session_id where p.status='OFFERED' and (p.confirm_deadline is null or p.confirm_deadline>x.registration_end_at or p.confirm_deadline>x.start_at)"
}
violations={key:int(query(sql)) for key,sql in checks.items()}
assert all(v==0 for v in violations.values()),violations
print(json.dumps({'counts':counts,'violations':violations},ensure_ascii=False,indent=2))
