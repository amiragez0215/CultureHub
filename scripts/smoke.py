"""HTTP smoke against an already-running local API; creates uniquely named users."""
import json,urllib.request,urllib.error,uuid,os
base=os.environ.get('API_URL','http://localhost:18080/api/v1')
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def req(path,method='GET',body=None,token=None,status=200):
 data=None if body is None else json.dumps(body).encode()
 headers={'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 try:
  with opener.open(urllib.request.Request(base+path,data=data,headers=headers,method=method),timeout=20) as r: code=r.status;value=json.load(r)
 except urllib.error.HTTPError as e:code=e.code;value=json.load(e)
 assert code==status,(path,code,value)
 return value
password=os.environ.get('DEMO_PASSWORD','123')
admin=req('/auth/login','POST',{'username':'admin','password':password})['token']
tokens=[]
for i in range(3):
 name='smoke_'+uuid.uuid4().hex[:12];req('/auth/register','POST',{'username':name,'password':password});tokens.append(req('/auth/login','POST',{'username':name,'password':password})['token'])
req('/venues');req('/admin/activities',token=admin)
activity=req('/admin/activities','POST',{'venue_id':1,'title':'Smoke workshop','description':'HTTP integration','category':'ART'},admin)['id']
from datetime import datetime,timedelta
now=datetime.now();stamp=lambda d:d.isoformat(timespec='seconds')
session=req('/admin/sessions','POST',{'activity_id':activity,'registration_start_at':stamp(now-timedelta(hours=1)),'registration_end_at':stamp(now+timedelta(minutes=20)),'start_at':stamp(now+timedelta(minutes=30)),'end_at':stamp(now+timedelta(hours=1)),'checkin_open_at':stamp(now-timedelta(minutes=1)),'checkin_close_at':stamp(now+timedelta(hours=1)),'capacity':1},admin)['id']
req('/admin/activities/'+str(activity)+'/publish','POST',{},admin)
req('/admin/sessions/'+str(session)+'/statistics',token=tokens[0],status=403)
key=uuid.uuid4().hex
first=req('/sessions/'+str(session)+'/reservations','POST',{'request_id':key},tokens[0]);assert req('/me/participations/by-request/'+key,token=tokens[0])['id']==first['id']
req('/sessions/'+str(session)+'/reservations','POST',{'request_id':uuid.uuid4().hex},tokens[2],status=409)
wait=req('/sessions/'+str(session)+'/waitlist','POST',{'request_id':uuid.uuid4().hex},tokens[1])
req('/participations/'+str(first['id'])+'/cancel','POST',{},tokens[0])
assert req('/participations/'+str(wait['id']),token=tokens[1])['status']=='OFFERED'
req('/participations/'+str(wait['id'])+'/confirm','POST',{},tokens[1])
credential=req('/participations/'+str(wait['id'])+'/credential',token=tokens[1])
check={'session_id':session,'token':credential['checkin_token']}
x=req('/admin/check-ins','POST',check,admin);assert req('/admin/check-ins','POST',check,admin)['id']==x['id']
req('/participations/'+str(wait['id'])+'/cancel','POST',{},tokens[1],status=409)
stats=req('/admin/sessions/'+str(session)+'/statistics',token=admin);assert stats['checkedIn']==1
req('/admin/ai/activity-plans','POST',{'venue_id':1,'theme':'Art','minutes':60},tokens[0],status=403)
req('/admin/ai/activity-plans','POST',{'venue_id':1,'theme':'Art','minutes':60},admin,status=503)
print(json.dumps({'result':'PASS','activityId':activity,'sessionId':session,'checks':['registration','JWT login','public queries','admin activity/session publish','scope denial','request result lookup','capacity denial','waitlist','cancel/promote','confirm','credential','check-in idempotence','cancel after check-in denied','statistics','AI permission','missing-model error']},ensure_ascii=True))
