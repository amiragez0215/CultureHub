package cn.culturehub.ai.guide;
import cn.culturehub.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
public class AiServicePersistence extends GuidePersistence {
 private final Store local;private final Access owner;private final AiRemoteQueries remote;
 public AiServicePersistence(Store s,Access a,AiBookingProposalService p,AiRemoteQueries q,ObjectMapper j,StringRedisTemplate r,PlatformTransactionManager tm){super(s,a,p,q,j,r,tm);local=s;owner=a;remote=q;}
 @Override public Map<String,Object> select(String conversation,String candidate,String mode){
  owned(conversation,false);
  long session=local.number("select i.session_id from ai_activity_plan_item i join ai_activity_plan p on p.plan_id=i.plan_id where i.candidate_id=? and p.conversation_id=? and p.user_id=?",candidate,conversation,owner.user());
  return remote.snapshot(session,owner.user(),()->tx.execute(status->{
    owned(conversation,true);
    var binding=local.one("select p.group_request_id from ai_activity_plan p join ai_activity_plan_item i on i.plan_id=p.plan_id where i.candidate_id=? and p.user_id=?",candidate,owner.user());
    Problem.require(binding.get("group_request_id")==null,"GROUP_PROCESSING","Group has already been authorized");
    return super.select(conversation,candidate,mode);
  }));
 }
}
