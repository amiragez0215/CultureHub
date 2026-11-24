package cn.culturehub;
import java.util.*;
class AiRemoteBooking extends Booking {
 final AiCoreClient core; final com.fasterxml.jackson.databind.ObjectMapper json;
 AiRemoteBooking(Access access,AiCoreClient core,com.fasterxml.jackson.databind.ObjectMapper json){super(null,access,null);this.core=core;this.json=json;}
 @Override public Map<String,Object> create(long session,String request,boolean wait){return create(session,request,wait,null);}
 @Override public Map<String,Object> create(long session,String request,boolean wait,String trace){try{return wait?core.waitlist(AiRemoteAccess.bearer(),session,Map.of("request_id",request)):core.reserve(AiRemoteAccess.bearer(),session,Map.of("request_id",request));}catch(feign.FeignException failure){String code="BOOKING_RESULT_UNKNOWN";try{if(failure.status()>=400&&failure.status()<500)code=json.readTree(failure.contentUTF8()).path("code").asText(code);}catch(Exception ignored){}throw new Problem(code,"Booking request was not confirmed by Core",failure.status()>=400&&failure.status()<500?failure.status():503);}}
 @Override public Map<String,Object> status(String request){try{return core.status(AiRemoteAccess.bearer(),request);}catch(feign.FeignException.NotFound absent){return null;}}
 @Override @SuppressWarnings("unchecked") public Map<String,Object> result(long session,String request,boolean wait){var state=status(request);return state!=null&&"CONFIRMED".equals(state.get("requestStatus"))?(Map<String,Object>)state.get("participation"):null;}
 @Override public String rejection(String request){var state=status(request);return state==null||Set.of("RECEIVED","QUEUED","PROCESSING","CONFIRMED").contains(state.get("requestStatus"))?null:Objects.toString(state.get("errorCode"),"BOOKING_FAILED");}
}
