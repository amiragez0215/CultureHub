package cn.culturehub;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
class AiRemoteProposals extends AiBookingProposalService {
 AiRemoteProposals(Store s,Access a,Booking b,GuideQueryService q,PlatformTransactionManager tx){super(s,a,b,q,tx);}
 @Override void lockOwner(long user){s.one("select id from ai_owner where id=? for update",user);}
 @Override boolean hasActiveParticipation(long session,long user){
  var value=queries.current(session).get("ownActiveParticipation");
  Problem.require(value instanceof Boolean,"AI_BUSINESS_QUERY_FAILED","Participation fact missing");
  return (Boolean)value;
 }
 @Override Map<String,Object> qualification(Map<String,Object> row){return booking.result(Store.id(row,"session_id"),row.get("request_id").toString(),"WAITLIST".equals(row.get("entry_type")));}
}
