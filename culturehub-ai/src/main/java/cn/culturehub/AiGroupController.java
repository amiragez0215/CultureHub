package cn.culturehub;
import cn.culturehub.ai.guide.*;
import static cn.culturehub.ai.guide.GuideState.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
/** Durable explicit group authorization; network calls always happen outside the local lock. */
@RestController
class AiGroupController {
 record Confirmation(@NotNull @PositiveOrZero Integer version,boolean confirmed,boolean retainPartial){}
 final Store s;final Access access;final GuidePersistence plans;final GuideQueryService queries;final AiBookingProposalService proposals;final AiCombinationClient groups;final ObjectMapper json;final TransactionTemplate tx;
 AiGroupController(Store s,Access a,GuidePersistence p,GuideQueryService q,AiBookingProposalService b,AiCombinationClient groups,ObjectMapper json,PlatformTransactionManager tm){this.s=s;access=a;plans=p;queries=q;proposals=b;this.groups=groups;this.json=json;tx=new TransactionTemplate(tm);}
 Map<String,Object> owned(String id){var plan=s.one("select * from ai_activity_plan where plan_id=?",id);access.owner(Store.id(plan,"user_id"));return plan;}
 @PostMapping("/api/v1/ai/plans/{id}/combination") Map<String,Object> confirm(@PathVariable String id,@Valid @RequestBody Confirmation input)throws Exception{
  Problem.require(input.confirmed(),"AUTHORIZATION_REQUIRED","Explicit group confirmation required");
  var before=owned(id);String conversation=before.get("conversation_id").toString();
  var items=s.db.queryForList("select session_id from ai_activity_plan_item where plan_id=? order by display_order",id);
  Problem.require(!items.isEmpty()&&items.size()<=3,"PLAN_STALE","Plan has no valid group");
  if(before.get("group_request_id")==null){
   var state=json.readValue(before.get("constraints_snapshot").toString(),Turn.class);
   state.identity=new Identity(conversation,state.identity.turnId(),state.identity.traceId(),access.user(),state.identity.latestUserMessage(),s.now());
   state.existingBookings=queries.activeBookings(access.user()).stream().map(b->new ExistingBooking(Store.id(b,"id"),Store.id(b,"sessionId"),b.get("status").toString(),GuideQueryService.time(b,"startAt"),GuideQueryService.time(b,"endAt"))).toList();
   var live=new ArrayList<Session>();
   for(var item:items){var session=GuideTools.session(queries.current(Store.id(item,"session_id")));
    Problem.require(new GuidePlanner().eligible(session,state),"PLAN_STALE","Live session or existing booking no longer satisfies plan");live.add(session);}
   for(int i=1;i<live.size();i++)Problem.require(live.get(i).venueId()==live.get(0).venueId()
      && !live.get(i).startAt().isBefore(live.get(i-1).endAt().plusMinutes(state.requirements.minimumGapMinutes())),
      "PLAN_STALE","Group schedule or venue changed");
   if(state.requirements.planningDurationMinutes()>0)Problem.require(java.time.Duration.between(live.get(0).startAt(),live.get(live.size()-1).endAt()).toMinutes()<=state.requirements.planningDurationMinutes(),"PLAN_STALE","Group exceeds time budget");
  }
  String request=tx.execute(status->{
   var conversationRow=plans.owned(conversation,true);var plan=s.one("select * from ai_activity_plan where plan_id=? for update",id);
   Problem.require(((Number)plan.get("version")).intValue()==input.version(),"PLAN_SUPERSEDED","Plan version differs");
   if(plan.get("group_request_id")!=null){Object flag=plan.get("group_retain_partial");boolean retain=Boolean.TRUE.equals(flag)||flag instanceof Number n&&n.intValue()!=0;Problem.require(retain==input.retainPartial(),"REQUEST_ID_CONFLICT","Group authorization options differ");return plan.get("group_request_id").toString();}
   Problem.require("ACTIVE".equals(plan.get("status"))&&"VERIFIED".equals(plan.get("verification_status"))&&s.now().isBefore(Store.time(plan,"expires_at"))&&Objects.equals(conversationRow.get("version"),plan.get("version"))&&conversationRow.get("busy_until")==null,"PLAN_SUPERSEDED","Plan is expired or being revised");
   Problem.require(s.number("select count(*) from ai_booking_proposal where user_id=? and conversation_id=? and status='PROCESSING'",access.user(),conversation)==0,"PROPOSAL_PROCESSING","Another booking is already processing");
   proposals.supersede(conversation,access.user());String stable="ai-group-"+UUID.randomUUID();
   s.db.update("update ai_activity_plan set group_request_id=?,group_retain_partial=? where plan_id=?",stable,input.retainPartial(),id);return stable;
  });
  try{return groups.submit(AiRemoteAccess.bearer(),Map.of("requestId",request,"confirmed",true,"retainPartial",input.retainPartial(),"items",items.stream().map(item->Map.of("participant","CORE","sessionId",Store.id(item,"session_id"))).toList()));}
  catch(feign.FeignException unavailable){if(unavailable.status()>=400&&unavailable.status()<500)throw new Problem("GROUP_REQUEST_REJECTED","Group request rejected; recover using the same authorization",unavailable.status());return unknown(id,request);}
 }
 @GetMapping("/api/v1/ai/plans/{id}/combination") Map<String,Object> result(@PathVariable String id){var plan=owned(id);Problem.require(plan.get("group_request_id")!=null,"AUTHORIZATION_REQUIRED","Group has not been authorized");String request=plan.get("group_request_id").toString();try{return groups.result(AiRemoteAccess.bearer(),request);}catch(feign.FeignException unavailable){return unknown(id,request);}}
 @GetMapping("/api/v1/ai/group-requests") Object history(){return s.db.queryForList("select plan_id,version,group_request_id,group_retain_partial,created_at from ai_activity_plan where user_id=? and group_request_id is not null order by created_at desc limit 20",access.user());}
 static Map<String,Object> unknown(String id,String request){return Map.of("planId",id,"requestId",request,"status","PROCESSING","resultUnknown",true,"allReleased",false);}
}
