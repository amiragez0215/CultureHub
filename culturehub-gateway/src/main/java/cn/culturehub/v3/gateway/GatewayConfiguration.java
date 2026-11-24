package cn.culturehub.v3.gateway;

import cn.culturehub.v3.ServiceBoundary;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
class GatewayConfiguration {
  @Bean
  RouteLocator cultureHubRoutes(
      RouteLocatorBuilder builder,
      @Value("${v3.routes.core}") String core,
      @Value("${v3.routes.notification}") String notification,
      @Value("${v3.routes.knowledge:lb://culturehub-knowledge}") String knowledge,
      @Value("${v3.routes.ai:lb://culturehub-ai}") String ai) {
    return builder.routes()
        .route("ai", r -> r.order(-10).path("/api/v1/ai/**").uri(ai))
        .route("combinations", r -> r.order(-10).path("/api/v1/combinations", "/api/v1/combinations/**",
            "/api/v1/demo/combinations", "/api/v1/demo/combinations/**").uri(core))
        .route("knowledge", r -> r.order(-10).path("/api/v1/knowledge/**").uri(knowledge))
        .route("notification", r -> r.order(-10)
            .path("/api/v1/me/notifications", "/api/v1/me/notifications/**").uri(notification))
        .route("core", r -> r.path("/api/v1/**").uri(core))
        .build();
  }

  @Bean
  @org.springframework.core.annotation.Order(-1)
  SecurityWebFilterChain managementSecurity(ServerHttpSecurity http,org.springframework.core.env.Environment env){
    return http.securityMatcher(exchange->{
      Integer port=env.getProperty("local.management.port",Integer.class,-1);
      boolean internal=exchange.getRequest().getLocalAddress()!=null
          && exchange.getRequest().getLocalAddress().getPort()==port
          && !port.equals(env.getProperty("local.server.port",Integer.class,-2));
      return internal?org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.match():org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult.notMatch();
    }).csrf(ServerHttpSecurity.CsrfSpec::disable)
      .authorizeExchange(r->r.pathMatchers("/actuator/health","/actuator/prometheus").permitAll().anyExchange().denyAll()).build();
  }

  @Bean
  ReactiveJwtDecoder userTokens(@Value("${v3.jwt-secret}") String secret) {
    byte[] key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32) throw new IllegalArgumentException("JWT secret must have >=32 bytes");
    var decoder = NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer("culturehub"),
        new JwtClaimValidator<String>("sub", ServiceBoundary::validUserId)));
    return decoder;
  }

  @Bean
  SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange(requests -> requests
            .pathMatchers("/internal/**").denyAll()
            .pathMatchers(HttpMethod.GET, "/actuator/health").permitAll()
            .pathMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
            .pathMatchers(HttpMethod.GET, "/api/v1/activities", "/api/v1/activities/**",
                "/api/v1/venues", "/api/v1/venues/**").permitAll()
            .pathMatchers("/api/v1/**").authenticated()
            .anyExchange().denyAll())
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((exchange, exception) -> error(exchange, HttpStatus.UNAUTHORIZED))
            .accessDeniedHandler((exchange, exception) -> error(exchange, HttpStatus.FORBIDDEN)))
        .oauth2ResourceServer(tokens -> tokens
            .authenticationEntryPoint((exchange, exception) -> error(exchange, HttpStatus.UNAUTHORIZED))
            .jwt(jwt -> {}))
        .build();
  }

  @Bean
  WebFilter removeExternalServiceHeaders() {
    return (exchange, chain) -> {
      String trace = ServiceBoundary.traceId(exchange.getRequest().getHeaders().getFirst(ServiceBoundary.TRACE));
      var request = exchange.getRequest().mutate().headers(headers -> {
        headers.remove(ServiceBoundary.CALLER);
        headers.remove(ServiceBoundary.CREDENTIAL);
        headers.remove("X-User-Id");
        headers.remove("TX_XID");
        headers.set(ServiceBoundary.TRACE, trace);
      }).build();
      exchange.getResponse().beforeCommit(() -> {
        exchange.getResponse().getHeaders().set(ServiceBoundary.TRACE, trace);
        return Mono.empty();
      });
      return chain.filter(exchange.mutate().request(request).build());
    };
  }

  @Bean
  ApplicationRunner gatewayFlowRules(@Value("${v3.gateway.requests-per-second}") int requestsPerSecond) {
    if (requestsPerSecond <= 0) throw new IllegalArgumentException("Gateway rate limit must be positive");
    return args -> GatewayRuleManager.loadRules(Set.of(
        new GatewayFlowRule("core").setCount(requestsPerSecond),
        new GatewayFlowRule("notification").setCount(requestsPerSecond),
        new GatewayFlowRule("ai").setCount(requestsPerSecond),
        new GatewayFlowRule("knowledge").setCount(requestsPerSecond),
        new GatewayFlowRule("combinations").setCount(requestsPerSecond)));
  }

  private Mono<Void> error(ServerWebExchange exchange, HttpStatus status) {
    var response = exchange.getResponse();
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    String body = status == HttpStatus.UNAUTHORIZED
        ? "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Login required\"}"
        : "{\"code\":\"FORBIDDEN\",\"message\":\"Permission denied\"}";
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
  }
}
