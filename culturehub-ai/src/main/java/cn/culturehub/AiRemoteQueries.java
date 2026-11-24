package cn.culturehub;
import java.util.*;
import java.util.function.Supplier;
import cn.culturehub.v3.ServiceBoundary;
public class AiRemoteQueries extends GuideQueryService {
 final AiCoreClient core;final String credential;
 final ThreadLocal<Map<String,Object>> cardSnapshot=new ThreadLocal<>();
 final ThreadLocal<List<Map<String,Object>>> bookingsSnapshot=new ThreadLocal<>();
 AiRemoteQueries(Store store,AiCoreClient core,String credential){super(store);this.core=core;this.credential=ServiceBoundary.requireCredential(credential);}
 Object call(Map<String,Object> request){try{return core.query(AiRemoteAccess.bearer(),"culturehub-ai",credential,request).get("result");}catch(feign.FeignException failure){throw new Problem("AI_BUSINESS_QUERY_FAILED","Business query unavailable",503);}}
 @SuppressWarnings("unchecked") @Override public List<Map<String,Object>> search(AiGuideGraph.Preferences p,long user,int limit){return (List<Map<String,Object>>)call(Map.of("tool","SEARCH_SESSIONS","preferences",p,"limit",limit));}
 @Override public List<Map<String,Object>> search(AiGuideGraph.Preferences p,long user){return search(p,user,20);}
 @SuppressWarnings("unchecked") @Override public Map<String,Object> current(long session){var cached=cardSnapshot.get();if(cached!=null&&Store.id(cached,"sessionId")==session)return cached;return (Map<String,Object>)call(Map.of("tool","SESSION_DETAILS","sessionId",session));}
 @SuppressWarnings("unchecked") @Override public List<Map<String,Object>> activeBookings(long user){var cached=bookingsSnapshot.get();return cached!=null?cached:(List<Map<String,Object>>)call(Map.of("tool","MY_ACTIVE_BOOKINGS"));}
 @SuppressWarnings("unchecked") @Override public List<Map<String,Object>> venues(String keyword){return (List<Map<String,Object>>)call(Map.of("tool","VENUE_INFORMATION","keyword",keyword==null?"":keyword));}
 public <T> T snapshot(long session,long user,Supplier<T> action){var card=current(session);var bookings=activeBookings(user);cardSnapshot.set(card);bookingsSnapshot.set(bookings);try{return action.get();}finally{cardSnapshot.remove();bookingsSnapshot.remove();}}
}
