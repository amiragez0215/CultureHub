package cn.culturehub.v3.runtime;

import cn.culturehub.v3.ServiceBoundary;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "culturehub-core", url = "${v3.core.url:}")
interface CoreReadinessClient {
  @GetMapping("/internal/v1/core/readiness")
  Map<String, Object> readiness(
      @RequestHeader(ServiceBoundary.CALLER) String caller,
      @RequestHeader(ServiceBoundary.CREDENTIAL) String credential,
      @RequestHeader(ServiceBoundary.TRACE) String trace);
}
