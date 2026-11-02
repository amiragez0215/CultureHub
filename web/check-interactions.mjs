// Vue's own renderer tests page transitions and rendered elements. No additional test framework.
import assert from 'node:assert/strict';
const listeners=new Map(),storage=new Map([['token','test-token']]),scrolls=[];
globalThis.sessionStorage={getItem:k=>storage.get(k)||null,setItem:(k,v)=>storage.set(k,String(v)),removeItem:k=>storage.delete(k)};
globalThis.location={hash:''};const entries=[{hash:'',state:null}];let position=0;
globalThis.history={state:null,pushState(state,unused,hash){entries.splice(++position);entries.push({hash,state});this.state=state;location.hash=hash;},back(){if(position){const e=entries[--position];this.state=e.state;location.hash=e.hash;listeners.get('popstate')?.();}}};
globalThis.window={scrollTo:args=>scrolls.push(args),addEventListener:(type,handler)=>listeners.set(type,handler),removeEventListener:type=>listeners.delete(type),confirm:()=>true};
globalThis.document={querySelector:()=>null,createElement:()=>({innerHTML:'',textContent:''})};
const activity={id:1,venue_id:9,title:'剪纸体验',category:'HERITAGE',audience:'FAMILY',description:'体验传统纸艺',plan:'讲解和体验',notice:'提前到场',status:'PUBLISHED',venue_name:'非遗馆'};
const session={id:10,activity_id:1,start_at:'2099-01-02T10:00:00',end_at:'2099-01-02T12:00:00',registration_start_at:'2020-01-01T00:00:00',registration_end_at:'2099-01-01T10:00:00',capacity:10,occupied_count:1,status:'PUBLISHED'};
const participation=[{id:41,session_id:10,title:'剪纸体验',status:'RESERVED',start_at:session.start_at,end_at:session.end_at},{id:42,session_id:11,title:'已参加的讲座',status:'CHECKED_IN',start_at:'2020-01-01T10:00:00',end_at:'2020-01-01T12:00:00'}];
let savedReview={rating:4,content:'讲解清楚，希望多些交流'};let slowResponse;let savedSession;const apiCalls=[];
const grants=new Map([[2,[9]],[3,[]]]);
let activityCancelled=false;
const guideCandidate={candidateId:'00000000-0000-0000-0000-000000000001',sessionId:10,activityId:1,title:'剪纸体验',venueName:'非遗馆',startAt:session.start_at,endAt:session.end_at,asOf:'2026-11-01T10:00:00',remaining:9,bookingAllowed:true,waitlistAllowed:false,ageNotice:'当前账号一个名额'};
let proposal=null,guideConfirmCalls=0,proposalSequence=0;
globalThis.fetch=async(url,options={})=>{
 apiCalls.push({url,options});
 const path=url.replace('/api/v1','').split('?')[0];let data;
 if(path==='/auth/me')data={user:{id:1,username:'user',nickname:'体验用户'},roles:['USER']};
 else if(path==='/activities')data=activityCancelled?[]:[activity];
 else if(path==='/activities/count')data={total:activityCancelled?0:25};
 else if(path==='/activities/1')data=activity;
 else if(path==='/activities/1/sessions'||path==='/admin/activities/1/sessions')data=[session];
 else if(path==='/admin/activities/1/cancel'){activityCancelled=true;data={ok:true};}
 else if(path==='/admin/activities'||path==='/admin/activities/count'){
   const status=new URL(url,'http://test').searchParams.get('status')||'ACTIVE';
   const visible=activityCancelled?['ALL','CANCELLED'].includes(status):status!=='CANCELLED';
   data=path.endsWith('/count')?{total:visible?(activityCancelled?1:30):0}:(visible?[{...activity,status:activityCancelled?'CANCELLED':activity.status}]:[]);
 }
 else if(path==='/me/participations/count')data={total:23};
 else if(path==='/admin/sessions'&&options.method==='POST'){savedSession=JSON.parse(options.body);data={id:101};}
 else if(path==='/ai/conversations')data={id:'test-conversation'};
 else if(path==='/ai/conversations/test-conversation/messages/stream'){if(proposal?.status==='PENDING')proposal={...proposal,status:'REJECTED'};data={type:'RECOMMENDATION',explanation:'可以了解这项剪纸活动',candidates:[guideCandidate],sources:[],knowledgeNotice:'知识库未配置，使用真实活动查询'};}
 else if(path==='/ai/conversations/test-conversation/proposals'){const choice=JSON.parse(options.body);proposal={id:'proposal-'+(++proposalSequence),proposalId:'proposal-'+proposalSequence,status:'PENDING',entryType:choice.mode,expiresAt:'2099-01-01T10:00:00',serverTime:new Date().toISOString(),session:guideCandidate};data=proposal;}
 else if(/^\/ai\/booking-proposals\/proposal-\d+(\/confirm|\/reject)?$/.test(path)){if(path.endsWith('/confirm')){guideConfirmCalls++;proposal={...proposal,status:'SUCCEEDED',participation:{id:41,status:proposal.entryType==='WAITLIST'?'WAITING':'RESERVED'}};}if(path.endsWith('/reject'))proposal={...proposal,status:'REJECTED'};data=proposal;}
 else if(path==='/admin/ai/activity-plans')data={title:'城市记忆体验',description:'观察地图与分享故事',steps:[{name:'导入',minutes:20,description:'共同观察'},{name:'体验',minutes:40,description:'分享收获'}],notice:'提前到场',copywriting:'一起认识城市'};
 else if(path==='/admin/ai/reports')data={metrics:{reserved:14,checkedIn:8},analysis:{positives:['讲解清晰'],issues:['时间偏短'],suggestions:['增加交流时间'],reviewIds:[1]},generatedAt:'2026-11-01T00:00:00Z',sampleCount:1};
 else if(path==='/admin/me/venues')data=[{id:9,name:'非遗馆',address:'文化街',category:'HERITAGE_HALL',status:'OPEN'},{id:12,name:'图书馆',address:'书香街',category:'LIBRARY',status:'OPEN'}];
 else if(path==='/admin/users')data=[{id:1,username:'admin',nickname:'平台管理员',enabled:true,platform_admin:1},{id:2,username:'manager',nickname:'场馆管理员',enabled:true,platform_admin:0},{id:3,username:'user',nickname:'普通用户',enabled:true,platform_admin:0}];
 else if(/^\/admin\/users\/\d+\/venue-grants$/.test(path)){const id=Number(path.split('/')[3]);if(options.method==='PUT'){grants.set(id,JSON.parse(options.body));data={ok:true};}else data={user_id:id,venue_ids:grants.get(id)||[]};}
 else if(path==='/admin/sessions/10/statistics')data={reserved:1,checkedIn:0,checkinProgress:0,checkinRate:null};
 else if(path==='/venues')data=[{id:9,name:'非遗馆',category:'HERITAGE_HALL',address:'文化街',introduction:'公共文化空间',openingHours:'09:00—17:00',status:'OPEN'},{id:12,name:'图书馆',category:'LIBRARY',address:'书香街',status:'OPEN'}];
 else if(path==='/venues/9')data={id:9,name:'非遗馆',address:'文化街',introduction:'公共文化空间',opening_hours:'09:00—17:00',status:'OPEN'};
 else if(path==='/me/participations')data=participation;
 else if(path==='/participations/41/credential')data={id:41,user_id:1,session_id:10,status:'RESERVED',checkin_token:'test-random-credential-1234567890'};
 else if(path==='/sessions/11/my-review'){if(options.method==='PUT'){savedReview=JSON.parse(options.body);data={ok:true};}else data=savedReview;}
 else if(path==='/me/notifications'){if(slowResponse)await slowResponse;data=[];}
 else throw Error('Unexpected API '+url);
 if(path.endsWith('/messages/stream')){
   const events=[['status',{node:'IntentExtract'}],['delta',{text:'可以了解'}],['delta',{text:'这项剪纸活动'}],['proposal',{candidates:data.candidates}],['done',data]];
   const bytes=new TextEncoder().encode(events.map(([name,value])=>'event: '+name+'\ndata: '+JSON.stringify(value)+'\n\n').join(''));
   return {ok:true,status:200,headers:new Headers({'content-type':'text/event-stream'}),body:new ReadableStream({start(controller){for(let i=0;i<bytes.length;i+=7)controller.enqueue(bytes.slice(i,i+7));controller.close();}})};
 }
 return{ok:true,status:200,text:async()=>JSON.stringify(data)};
};
const {createRenderer,nextTick}=await import('vue');const {App}=await import('./src/main.js');
const {parse}=await import('@vue/compiler-dom');
function element(type,text=''){const events=new Map();const node={type,tagName:type.toUpperCase(),text,props:{},children:[],parent:null,style:{},value:'',events,addEventListener(name,handler){events.set(name,handler);},removeEventListener(name){events.delete(name);},setAttribute(){},removeAttribute(){}};Object.defineProperty(node,'options',{get(){return node.children.filter(c=>c.type==='option');}});return node;}
function insertNode(n,p,anchor){if(n.parent){const old=n.parent.children;old.splice(old.indexOf(n),1);}n.parent=p;const i=anchor?p.children.indexOf(anchor):-1;if(i<0)p.children.push(n);else p.children.splice(i,0,n);}
function staticNode(ast){if(ast.type===2)return element('#text',ast.content);if(ast.type===3)return element('#comment',ast.content);const node=element(ast.tag);for(const prop of ast.props||[]){assert.equal(prop.type,6);node.props[prop.name]=prop.value?.content??true;if(prop.name==='value')node.value=prop.value.content;}for(const child of ast.children||[])insertNode(staticNode(child),node);return node;}
const renderer=createRenderer({createElement:element,createText:text=>element('#text',text),createComment:text=>element('#comment',text),setText:(n,t)=>n.text=t,setElementText(n,t){n.text=t;n.children=[];},parentNode:n=>n.parent,nextSibling(n){const list=n.parent?.children||[];return list[list.indexOf(n)+1]||null;},insert:insertNode,insertStaticContent(content,parent,anchor){const nodes=parse(content).children.map(staticNode);for(const node of nodes)insertNode(node,parent,anchor);return[nodes[0],nodes.at(-1)];},remove(n){const list=n.parent?.children;if(list)list.splice(list.indexOf(n),1);},patchProp:(n,k,old,v)=>n.props[k]=v});
const root=element('root');const app=renderer.createApp(App);app.config.warnHandler=msg=>{if(!msg.includes('Feature flag')&&!msg.startsWith('Unhandled error'))throw Error(msg);};const vm=app.mount(root);
const text=n=>n.text+(n.children||[]).map(text).join('');
const find=(n,p)=>p(n)?n:(n.children||[]).map(c=>find(c,p)).find(Boolean);
async function settle(){for(let i=0;i<8;i++){await new Promise(r=>setTimeout(r,3));await nextTick();}}
async function click(name){await nextTick();const button=find(root,n=>n.type==='button'&&text(n).trim()===name);assert.ok(button,'Missing button '+name);assert.ok(!button.props.disabled,'Disabled '+name);const event={preventDefault(){},stopPropagation(){}};if(button.props.onClick)await button.props.onClick(event);else{let form=button.parent;while(form&&form.type!=='form')form=form.parent;assert.ok(form?.props.onSubmit,'No handler for '+name);await form.props.onSubmit(event);}await settle();}
try{
 await settle();assert.equal(vm.page,'activities');assert.equal(vm.pageCount,2);assert.match(text(root),/共 25 个活动/);assert.match(text(root),/共 2 页/);
 vm.venueFilter=9;await vm.search();assert.ok(apiCalls.some(c=>c.url.includes('/activities/count?')&&c.url.includes('venueId=9')));vm.venueFilter=0;
 await click('查看场次 →');assert.equal(vm.page,'activityDetail');assert.match(location.hash,/activityDetail\/1/);assert.match(text(root),/选择参与场次/);assert.equal(find(root,n=>n.props.class==='cards'),undefined);assert.equal(vm.section,'activities');
 await click('← 返回上一页');assert.equal(vm.page,'activities');
 await click('文化场馆');vm.venueCategory='LIBRARY';await nextTick();assert.equal(vm.visibleVenues.length,1);assert.equal(vm.visibleVenues[0].id,12);assert.match(text(root),/公共图书馆/);vm.venueCategory='';await nextTick();await click('参观详情 →');assert.equal(vm.page,'venueDetail');assert.match(text(root),/场馆地址/);assert.equal(vm.section,'venuePublic');
 await click('← 返回上一页');assert.equal(vm.page,'venuePublic');
 await click('我的参与');assert.equal(vm.total,23);assert.equal(vm.pageCount,2);await click('查看电子凭证 →');assert.equal(vm.page,'credential');assert.ok(vm.qr.startsWith('data:image/png'));assert.ok(find(root,n=>n.type==='img'&&n.props.alt==='个人签到二维码'));assert.match(text(root),/核销码/);assert.equal(vm.section,'mine');
 await click('返回我的参与');assert.equal(vm.page,'mine');
 await click('提交 / 修改评价 →');assert.equal(vm.page,'review');assert.equal(vm.form.rating,4);assert.equal(vm.form.content,savedReview.content);assert.match(text(root),/已参加的讲座/);assert.ok(find(root,n=>n.type==='button'&&text(n)==='取消并返回'));
 vm.form.content='更新后的真实体验';await vm.saveReview();await settle();assert.equal(vm.page,'mine');assert.equal(savedReview.content,'更新后的真实体验');assert.equal(vm.toast,'评价已保存');
 await vm.go('activities');await vm.paginate(1);assert.equal(vm.pageNo,2);await vm.search();assert.equal(vm.pageNo,1);await vm.paginate(1);await vm.nav('mine');assert.equal(vm.pageNo,1);await vm.back();await settle();assert.equal(vm.pageNo,2);
 let release;slowResponse=new Promise(r=>release=r);const pending=vm.nav('notifications');await vm.nav('activities');release();await pending;await settle();assert.equal(vm.page,'activities');assert.equal(vm.rows[0].id,1);
 const originalFetch=globalThis.fetch;let rejectOld;
 globalThis.fetch=(url,options)=>url.includes('/me/notifications')?new Promise((resolve,reject)=>rejectOld=reject):originalFetch(url,options);
 const obsolete=vm.nav('notifications');await vm.nav('activities');rejectOld(Error('旧页面请求失败'));await obsolete;assert.equal(vm.error,'');globalThis.fetch=originalFetch;
 let releaseVenue;globalThis.fetch=(url,options)=>url.endsWith('/venues/9')?new Promise(resolve=>releaseVenue=()=>originalFetch(url,options).then(resolve)):originalFetch(url,options);
 const obsoleteVenue=vm.showVenue(9);await vm.nav('activities');releaseVenue();await obsoleteVenue;assert.equal(vm.venueInfo,null);globalThis.fetch=originalFetch;
 vm.me={user:{username:'admin',nickname:'平台管理员'},roles:['PLATFORM_ADMIN']};await vm.go('manage');await settle();assert.equal(vm.pageCount,2);assert.equal(vm.total,30);
 await click('管理场次');assert.equal(vm.page,'adminDetail');assert.equal(vm.section,'manage');
 await click('签到统计');assert.equal(vm.resultTitle,'场次签到统计');assert.match(text(root),/实时签到进度/);assert.match(text(root),/场次结束后生成/);
 await vm.newSession();await settle();assert.equal(vm.page,'editSession');assert.ok(find(root,n=>n.type==='button'&&text(n)==='取消并返回'));
 const dateInput=find(root,n=>n.type==='input'&&n.props.name==='registration_start_at_date');dateInput.props.onInput({target:{value:'2026-01-02'}});await nextTick();
 const timeInput=find(root,n=>n.type==='input'&&n.props.name==='registration_start_at_time');timeInput.props.onInput({target:{value:'09:30'}});await nextTick();assert.equal(vm.form.registration_start_at,'2026-01-02T09:30');
 const capacityInput=find(root,n=>n.type==='input'&&n.props.name==='capacity');capacityInput.props.onInput({target:{value:'35'}});await nextTick();assert.equal(vm.form.capacity,35);
 assert.equal(vm.sessionFields.registration_start_at.date,'2026-01-02');await vm.saveSession();await settle();assert.equal(savedSession.capacity,35);assert.equal(savedSession.registration_start_at,'2026-01-02T09:30');assert.equal(vm.page,'adminDetail');
 await vm.newSession();await settle();vm.updateCapacity('0');savedSession=null;await vm.saveSession();assert.equal(savedSession,null);assert.match(vm.error,/预约人数/);vm.updateCapacity('20');vm.updateSessionTime('registration_start_at','date','');await vm.saveSession();assert.match(vm.error,/完整填写/);assert.equal(savedSession,null);
 await click('取消并返回');assert.equal(vm.page,'adminDetail');
 await click('← 返回上一页');assert.equal(vm.page,'manage');await click('＋ 创建活动');assert.equal(vm.page,'editActivity');await click('取消并返回');assert.equal(vm.page,'manage');
 location.hash='#/credential/41';history.state=null;await listeners.get('popstate')();await settle();assert.equal(vm.page,'credential');assert.ok(vm.qr);await click('返回我的参与');assert.equal(vm.page,'mine');
 await vm.go('grants');await settle();assert.match(text(root),/无需查询或手填编号/);assert.equal(vm.grantUserId,0);assert.ok(find(root,n=>n.type==='option'&&n.props.value===1).props.disabled);
 vm.grantUserId=2;await vm.selectGrantUser();await settle();assert.deepEqual([...vm.grantVenueIds],[9]);assert.equal(vm.grantChanged,false);
 const libraryChoice=find(root,n=>n.type==='input'&&n.props.name==='grant_venue_12');assert.ok(libraryChoice);libraryChoice.props.onChange({target:{checked:true}});await nextTick();assert.deepEqual([...vm.grantVenueIds],[9,12]);assert.equal(vm.grantChanged,true);
 vm.grantVenueQuery='图书';await nextTick();assert.equal(vm.grantVenues.length,1);assert.equal(vm.grantVenueIds.length,2);await click('保存场馆授权');assert.deepEqual(grants.get(2),[9,12]);assert.equal(vm.grantChanged,false);assert.match(vm.toast,/共 2 个场馆/);
 vm.grantVenueQuery='';await nextTick();vm.toggleGrantVenue(9,false);await click('撤销未保存修改');assert.deepEqual([...vm.grantVenueIds],[9,12]);
 vm.toggleGrantVenue(9,false);vm.toggleGrantVenue(12,false);window.confirm=()=>false;await vm.grant();assert.deepEqual(grants.get(2),[9,12]);window.confirm=()=>true;await vm.grant();assert.deepEqual(grants.get(2),[]);
 let releaseGrant;const stableFetch=globalThis.fetch;globalThis.fetch=(url,options)=>url.endsWith('/admin/users/2/venue-grants')?new Promise(resolve=>releaseGrant=()=>stableFetch(url,options).then(resolve)):stableFetch(url,options);
 const olderGrant=vm.selectGrantUser();vm.grantUserId=3;await vm.selectGrantUser();releaseGrant();await olderGrant;assert.equal(vm.grantUserId,3);assert.deepEqual([...vm.grantVenueIds],[]);globalThis.fetch=stableFetch;
 await vm.go('guide');vm.question='我想体验剪纸';await vm.chat();await settle();assert.equal(vm.messages.at(-1).cards[0].activityId,1);assert.match(text(root),/查看活动与场次/);
 assert.equal(guideConfirmCalls,0);await click('选择此场次');assert.equal(vm.guideProposal.status,'PENDING');assert.equal(guideConfirmCalls,0);assert.match(text(root),/尚未提交/);await click('确认预约');assert.equal(vm.guideProposal.status,'SUCCEEDED');assert.equal(guideConfirmCalls,1);assert.match(text(root),/已获得正式参与资格/);
 proposal={...proposal,participation:{id:41,status:'CANCELLED'}};await vm.refreshGuideProposal();assert.match(vm.guideResultText(vm.guideProposal),/已取消/);assert.doesNotMatch(vm.guideResultText(vm.guideProposal),/预约成功/);
 vm.question='改为周日下午';await vm.chat();await settle();assert.equal(vm.messages.filter(m=>m.current&&m.cards).length,1);
 await vm.selectGuideCandidate(guideCandidate,'DIRECT');await vm.rejectGuideProposal();assert.equal(vm.guideProposal.status,'REJECTED');assert.equal(guideConfirmCalls,1);
 guideCandidate.remaining=0;guideCandidate.bookingAllowed=false;guideCandidate.waitlistAllowed=true;vm.question='满额也可以候补';await vm.chat();await settle();await click('选择候补（需另行确认）');assert.equal(vm.guideProposal.entryType,'WAITLIST');assert.equal(guideConfirmCalls,1);await click('确认加入候补');assert.match(vm.guideResultText(vm.guideProposal),/尚未获得正式名额/);assert.equal(guideConfirmCalls,2);
 await vm.go('plan');vm.form.theme='城市记忆';await vm.plan();await settle();assert.equal(vm.page,'editActivity');assert.equal(vm.form.title,'城市记忆体验');assert.match(vm.form.plan,/1\. 导入（20分钟）/);assert.doesNotMatch(vm.form.plan,/"minutes"/);
 await vm.go('report');vm.form.scope_id=10;await vm.report();await settle();assert.match(text(root),/改进建议/);assert.match(text(root),/增加交流时间/);assert.equal(vm.result.metrics.checkedIn,8);
 await vm.go('manage');await vm.paginate(1);assert.equal(vm.pageNo,2);await click('取消');assert.equal(vm.rows.length,0);assert.equal(vm.total,0);assert.equal(vm.pageNo,1);assert.match(vm.toast,/已取消并下架/);
 vm.managementStatus='CANCELLED';await vm.search();assert.equal(vm.rows[0].status,'CANCELLED');assert.equal(vm.total,1);assert.ok(find(root,n=>n.type==='button'&&text(n).trim()==='取消').props.disabled);
 vm.managementStatus='ALL';await vm.search();assert.equal(vm.rows.length,1);vm.managementStatus='PUBLISHED';await vm.search();assert.equal(vm.rows.length,0);await vm.go('activities');assert.equal(vm.total,0);
 vm.me={user:{username:'manager'},roles:['VENUE_ADMIN']};await vm.go('grants');assert.match(vm.error,/仅向平台管理员/);
 assert.equal(vm.messages.length,0);assert.equal(vm.guideProposal,null);
  await vm.go('guide');
  const beforeStreamingFetch=globalThis.fetch;let beginStream;const streamStarted=new Promise(resolve=>beginStream=resolve);
  globalThis.fetch=async(url,options)=>{
    if(!url.endsWith('/messages/stream'))return beforeStreamingFetch(url,options);
    return {ok:true,headers:new Headers({'content-type':'text/event-stream'}),body:new ReadableStream({start(controller){controller.enqueue(new TextEncoder().encode('event: status\ndata: {"node":"InformationRetrieve"}\n\nevent: delta\ndata: {"text":"正在逐步介绍文化活动。"}\n\n'));beginStream();}})};
  };
  vm.question='测试逐步显示';const streamingTask=vm.chat();await streamStarted;await settle();
  assert.equal(vm.guideStreaming,true);assert.match(text(root),/正在逐步介绍文化活动/);assert.equal(vm.messages.at(-1).cards.length,0);
  await click('停止生成');await streamingTask;await settle();assert.equal(vm.guideStreaming,false);assert.equal(vm.busy,false);assert.match(vm.messages.at(-1).text,/已停止生成/);assert.equal(vm.messages.at(-1).current,false);
  globalThis.fetch=beforeStreamingFetch;
  let releaseChat,markStarted;const startedChat=new Promise(resolve=>markStarted=resolve);const ordinaryFetch=globalThis.fetch;
 globalThis.fetch=(url,options)=>url.endsWith('/messages/stream')?new Promise(resolve=>{releaseChat=()=>ordinaryFetch(url,options).then(resolve);markStarted();}):ordinaryFetch(url,options);
 vm.question='旧账号的问题';const delayedChat=vm.chat();await startedChat;vm.me={user:{username:'another-user'},roles:['USER']};releaseChat();await delayedChat;await settle();assert.equal(vm.messages.length,0);assert.equal(vm.conversation,'');assert.equal(vm.guideProposal,null);globalThis.fetch=ordinaryFetch;
 assert.ok(scrolls.length>0);assert.match(text(root),/探索活动/);
 console.log('PASS: Vue interaction regression; Graph session selection does not book, explicit booking/waitlist confirmation, rejection, current qualification, stale cards and cross-user response isolation; original management/grants/AI2/AI3 regression');
}finally{app.unmount();}
