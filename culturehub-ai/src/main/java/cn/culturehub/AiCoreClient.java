package cn.culturehub;
import java.util.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import cn.culturehub.v3.ServiceBoundary;
@FeignClient(name="culturehub-core",url="${V3_CORE_URL:}")
interface AiCoreClient {
 @GetMapping("/api/v1/service/ai/identity") Map<String,Object> identity(@RequestHeader("Authorization") String user,@RequestHeader(ServiceBoundary.CALLER) String caller,@RequestHeader(ServiceBoundary.CREDENTIAL) String credential);
 @PostMapping("/api/v1/service/ai/query") Map<String,Object> query(@RequestHeader("Authorization") String user,@RequestHeader(ServiceBoundary.CALLER) String caller,@RequestHeader(ServiceBoundary.CREDENTIAL) String credential,@RequestBody Map<String,Object> input);
 @PostMapping("/api/v1/sessions/{id}/reservations") Map<String,Object> reserve(@RequestHeader("Authorization") String user,@PathVariable("id") long id,@RequestBody Map<String,String> request);
 @PostMapping("/api/v1/sessions/{id}/waitlist") Map<String,Object> waitlist(@RequestHeader("Authorization") String user,@PathVariable("id") long id,@RequestBody Map<String,String> request);
 @GetMapping("/api/v1/booking-requests/{id}") Map<String,Object> status(@RequestHeader("Authorization") String user,@PathVariable("id") String id);
}
