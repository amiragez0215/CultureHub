package cn.culturehub.v3.runtime;

import cn.culturehub.v3.ServiceBoundary;
import feign.FeignException;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/service")
class ServiceReadiness implements InitializingBean {
  private enum Role {
    AI("ai_db"), KNOWLEDGE("knowledge_db");

    final String schema;
    final String service;
    final String user;

    Role(String schema) {
      this.schema = schema;
      String suffix = name().toLowerCase(Locale.ROOT);
      this.service = "culturehub-" + suffix;
      this.user = "culturehub_" + suffix;
    }
  }

  private final DataSource database;
  private final CoreReadinessClient core;
  private final Role role;
  private final String operatorToken;
  private final String coreToken;

  ServiceReadiness(
      DataSource database, CoreReadinessClient core,
      @Value("${v3.role}") String role,
      @Value("${v3.probe-token}") String operatorToken,
      @Value("${v3.core-token}") String coreToken) {
    this.database = database;
    this.core = core;
    this.role = Role.valueOf(role.toUpperCase(Locale.ROOT));
    this.operatorToken = ServiceBoundary.requireCredential(operatorToken);
    this.coreToken = ServiceBoundary.requireCredential(coreToken);
    if (this.operatorToken.equals(this.coreToken)) {
      throw new IllegalArgumentException("Use different operator and Core credentials");
    }
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    verifyDatabase();
  }

  @GetMapping("/readiness")
  ResponseEntity<?> readiness(
      @RequestHeader(name = ServiceBoundary.CREDENTIAL, required = false) String credential,
      @RequestHeader(name = ServiceBoundary.TRACE, required = false) String requestedTrace) {
    String trace = ServiceBoundary.traceId(requestedTrace);
    if (!ServiceBoundary.authenticates(operatorToken, credential)) {
      return error(HttpStatus.UNAUTHORIZED, "SERVICE_UNAUTHENTICATED", trace);
    }
    try {
      verifyDatabase();
      return ResponseEntity.ok(Map.of("service", role.service, "schema", role.schema, "traceId", trace));
    } catch (java.sql.SQLException | IllegalStateException unavailable) {
      return error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", trace);
    }
  }

  @GetMapping("/core-readiness")
  ResponseEntity<?> coreReadiness(
      @RequestHeader(name = ServiceBoundary.CREDENTIAL, required = false) String credential,
      @RequestHeader(name = ServiceBoundary.TRACE, required = false) String requestedTrace) {
    String trace = ServiceBoundary.traceId(requestedTrace);
    if (!ServiceBoundary.authenticates(operatorToken, credential)) {
      return error(HttpStatus.UNAUTHORIZED, "SERVICE_UNAUTHENTICATED", trace);
    }
    try {
      return ResponseEntity.ok(core.readiness(role.service, coreToken, trace));
    } catch (FeignException unavailable) {
      return error(HttpStatus.SERVICE_UNAVAILABLE, "CORE_UNAVAILABLE", trace);
    }
  }

  private void verifyDatabase() throws java.sql.SQLException {
    ServiceBoundary.verifyDatabase(database, role.schema, role.user);
  }

  private ResponseEntity<?> error(HttpStatus status, String code, String trace) {
    return ResponseEntity.status(status).body(Map.of("code", code, "traceId", trace));
  }
}
