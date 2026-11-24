package cn.culturehub;

import cn.culturehub.v3.ServiceBoundary;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("v3-p1")
class CoreServiceBoundary {
  @Bean
  InitializingBean coreDatabaseIdentity(DataSource dataSource,
      @Value("${v3.core.schema:core_db}") String schema,
      @Value("${v3.core.account:culturehub_core}") String account) {
    if (!Map.of("core_db","culturehub_core","partner_db_a","culturehub_partner_a","partner_db_b","culturehub_partner_b")
        .getOrDefault(schema, "").equals(account)) throw new IllegalArgumentException("Invalid participant database identity");
    return () -> ServiceBoundary.verifyDatabase(dataSource, schema, account);
  }

  @Bean
  @Order(0)
  SecurityFilterChain internalCoreRoutes(HttpSecurity http) throws Exception {
    return http.securityMatcher("/internal/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests
            .requestMatchers(HttpMethod.GET, "/internal/v1/core/readiness", "/internal/v1/core/users/*/active",
                "/internal/v1/combination/catalog").permitAll()
            .requestMatchers(HttpMethod.POST, "/internal/v1/combination/*").permitAll()
            .anyRequest().denyAll())
        .build();
  }

  @Bean
  @Order(1)
  SecurityFilterChain coreBoundaryRoutes(HttpSecurity http) throws Exception {
    return http.securityMatcher("/api/v1/me/notifications", "/api/v1/me/notifications/**",
            "/actuator/health/readiness", "/actuator/health/liveness")
        .authorizeHttpRequests(requests -> requests
            .requestMatchers(HttpMethod.GET, "/actuator/health/readiness", "/actuator/health/liveness").permitAll()
            .anyRequest().denyAll())
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, failure) -> response.setStatus(403))
            .accessDeniedHandler((request, response, failure) -> response.setStatus(403)))
        .build();
  }
}

@RestController
@Profile("v3-p1")
@RequestMapping("/internal/v1/core")
class CoreInternalController {
  private final Store store;
  private final Map<String, String> callers;

  CoreInternalController(
      Store store,
      @Value("${v3.internal.ai}") String ai,
      @Value("${v3.internal.knowledge}") String knowledge,
      @Value("${v3.internal.combination}") String combination,
      @Value("${v3.internal.notification}") String notification) {
    this.store = store;
    callers = Map.of(
        "culturehub-ai", ServiceBoundary.requireCredential(ai),
        "culturehub-knowledge", ServiceBoundary.requireCredential(knowledge),
        "culturehub-notification", ServiceBoundary.requireCredential(notification));
    if (new java.util.HashSet<>(callers.values()).size() != callers.size()
        || (!combination.isBlank() && callers.containsValue(ServiceBoundary.requireCredential(combination)))) {
      throw new IllegalArgumentException("Each Core caller needs its own service credential");
    }
  }

  @GetMapping("/readiness")
  Map<String, Object> readiness(
      @RequestHeader(name = ServiceBoundary.CALLER, required = false) String caller,
      @RequestHeader(name = ServiceBoundary.CREDENTIAL, required = false) String credential,
      @RequestHeader(name = ServiceBoundary.TRACE, required = false) String trace) {
    authorize(caller, credential, false);
    return Map.of("service", "culturehub-core", "schema",
        store.db.queryForObject("select database()", String.class),
        "traceId", ServiceBoundary.traceId(trace));
  }

  @GetMapping("/users/{id}/active")
  Map<String, Boolean> active(
      @PathVariable long id,
      @RequestHeader(name = ServiceBoundary.CALLER, required = false) String caller,
      @RequestHeader(name = ServiceBoundary.CREDENTIAL, required = false) String credential) {
    authorize(caller, credential, true);
    return Map.of("active", store.number("select count(*) from app_user where id=? and enabled=1", id) == 1);
  }

  private void authorize(String caller, String credential, boolean notificationEndpoint) {
    if (caller == null || !ServiceBoundary.authenticates(callers.get(caller), credential)) {
      throw new Problem("SERVICE_UNAUTHENTICATED", "Invalid service credentials", 401);
    }
    if (notificationEndpoint != caller.equals("culturehub-notification")) {
      throw new Problem("SERVICE_FORBIDDEN", "Service is not permitted on this endpoint", 403);
    }
  }
}
