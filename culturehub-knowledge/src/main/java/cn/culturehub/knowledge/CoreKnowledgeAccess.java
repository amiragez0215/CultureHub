package cn.culturehub.knowledge;

import cn.culturehub.v3.ServiceBoundary;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "culturehub-core", url = "${v3.core.url:}")
interface CoreKnowledgeAccess {
  @GetMapping("/api/v1/service/knowledge/scope")
  Map<String, Object> scope(
      @RequestHeader("Authorization") String authorization,
      @RequestHeader(ServiceBoundary.CALLER) String caller,
      @RequestHeader(ServiceBoundary.CREDENTIAL) String token,
      @RequestParam("venue") long venue,
      @RequestParam("activity") long activity,
      @RequestParam("write") boolean write);
}
