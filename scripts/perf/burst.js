import http from 'k6/http';
import {Counter} from 'k6/metrics';
import {check} from 'k6';
// Export a fresh dedicated session/token fixture before each run; never target production.
const fixture=JSON.parse(open(__ENV.FIXTURE||'../../.tools/perf-fixture.json'));
const rateLimited=new Counter('rate_limited'),full=new Counter('session_full'),errors=new Counter('system_errors');
export const options={scenarios:{burst:{executor:'per-vu-iterations',vus:Number(__ENV.VUS||50),iterations:1,maxDuration:'60s'}}};
export default function(){
  if(!__ENV.SESSION_ID)throw new Error('Fresh dedicated SESSION_ID required');
  const same=__ENV.MODE==='idempotent';
  if(same&&!__ENV.REQUEST_ID)throw new Error('Stable REQUEST_ID required for idempotent mode');
  const r=http.post('http://127.0.0.1:18090/api/v1/sessions/'+__ENV.SESSION_ID+'/reservations',JSON.stringify({request_id:same?__ENV.REQUEST_ID:'k6_'+__VU+'_'+Date.now()}),{headers:{'Content-Type':'application/json',Authorization:'Bearer '+fixture.tokens[same?0:__VU-1]}});
  let code='';try{code=r.json('code')||'';}catch{}
  if(r.status===429)rateLimited.add(1);if(code==='SESSION_FULL')full.add(1);if(r.status===0||r.status>=500)errors.add(1);
  check(r,{'no system failure':v=>v.status>0&&v.status<500});
}
export function handleSummary(data){return {'k6-summary.json':JSON.stringify(data,null,2)};}
