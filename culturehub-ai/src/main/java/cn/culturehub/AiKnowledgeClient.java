package cn.culturehub;
import java.util.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import cn.culturehub.v3.ServiceBoundary;
@FeignClient(name="culturehub-knowledge",url="${V3_KNOWLEDGE_URL:}")
interface AiKnowledgeClient {
 @PostMapping("/internal/v1/knowledge/retrieve") List<Map<String,Object>> retrieve(@RequestHeader("Authorization") String user,@RequestHeader(ServiceBoundary.CALLER) String caller,@RequestHeader(ServiceBoundary.CREDENTIAL) String credential,@RequestBody Map<String,Object> input);
}
