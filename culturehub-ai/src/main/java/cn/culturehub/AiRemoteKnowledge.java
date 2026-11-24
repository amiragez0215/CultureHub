package cn.culturehub;
import cn.culturehub.ai.guide.*;
import static cn.culturehub.ai.guide.GuideState.*;
import cn.culturehub.v3.ServiceBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
class AiRemoteKnowledge extends GuideKnowledge {
 final AiKnowledgeClient knowledge;final String credential;final ObjectMapper mapper;
 AiRemoteKnowledge(ObjectProvider<VectorStore> empty,Store s,Access access,AiKnowledgeClient client,String token,ObjectMapper mapper){super(empty,s,access);knowledge=client;credential=ServiceBoundary.requireCredential(token);this.mapper=mapper;}
 @Override public List<Evidence> retrieve(Turn turn){
  var found=new LinkedHashMap<String,Evidence>();var unavailable=new HashSet<String>();int length=0;
  for(var query:turn.retrievalPlan){
   var need=query.need();
   if(System.currentTimeMillis()>=turn.deadlineEpochMillis){unavailable.add(need.questionId());continue;}
   try{
    var input=new LinkedHashMap<String,Object>();input.put("query",query.rewrittenQuery());input.put("venueId",need.venueId()==null?0:need.venueId());input.put("activityId",need.activityId());input.put("knowledgeType",need.knowledgeType());input.put("admin",false);
    var rows=knowledge.retrieve(AiRemoteAccess.bearer(),"culturehub-ai",credential,input);
    for(var row:rows){
     var metadata=mapper.readValue(row.get("metadata").toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
     String scope=Objects.toString(metadata.get("scope"),"");long venue=((Number)metadata.getOrDefault("venueId",0)).longValue();long activity=((Number)metadata.getOrDefault("activityId",0)).longValue();
     if(!"PUBLIC".equals(metadata.get("visibility"))||!need.knowledgeType().equals(metadata.get("knowledge_type")))continue;
     if(need.activityId()!=null?(!"ACTIVITY".equals(scope)||need.activityId()!=activity||!Objects.equals(need.venueId(),venue)):need.venueId()==null?!"GLOBAL".equals(scope):!"VENUE".equals(scope)||need.venueId()!=venue)continue;
     String text=Objects.toString(row.get("content"),"");String source=Objects.toString(metadata.get("source"),"");
     if(source.isBlank()||text.isBlank()||text.length()>5000||length+text.length()>24000
       ||text.matches("(?is).*(忽略之前指令|调用工具修改|ignore previous instructions|execute SQL).*"))continue;
     var evidence=new Evidence(metadata.get("documentId").toString(),metadata.get("chunkId").toString(),venue==0?null:venue,activity==0?null:activity,scope,source,need.knowledgeType(),metadata.get("version").toString(),null,text,row.get("score") instanceof Number n?n.doubleValue():0,0,need.questionId());
     if(found.putIfAbsent(need.questionId()+":"+evidence.chunkId(),evidence)==null)length+=text.length();
    }
   }catch(Exception failure){unavailable.add(need.questionId());}
  }
  turn.unavailableQuestions=unavailable;return List.copyOf(found.values());
 }
}
