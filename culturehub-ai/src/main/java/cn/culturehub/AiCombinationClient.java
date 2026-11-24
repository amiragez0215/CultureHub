package cn.culturehub;
import java.util.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
@FeignClient(name="culturehub-core",contextId="ai-combination-core",url="${V3_CORE_URL:}")
interface AiCombinationClient {
 @PostMapping("/api/v1/combinations") Map<String,Object> submit(@RequestHeader("Authorization") String user,@RequestBody Map<String,Object> request);
 @GetMapping("/api/v1/combinations/by-request/{id}") Map<String,Object> result(@RequestHeader("Authorization") String user,@PathVariable("id") String request);
}
