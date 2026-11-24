package cn.culturehub;

import cn.culturehub.v3.ServiceBoundary;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

@Configuration(proxyBeanMethods = false)
@Profile("v3-p1")
@EnableFeignClients(clients = CoreIdentityClient.class)
class NotificationServiceBoundary {
  @Bean
  InitializingBean notificationDatabaseIdentity(DataSource dataSource) {
    return () -> ServiceBoundary.verifyDatabase(dataSource, "notification_db", "culturehub_notification");
  }

  @Bean
  JwtDecoder notificationUserTokens(@Value("${v3.jwt-secret}") String secret) {
    byte[] key = secret.getBytes(StandardCharsets.UTF_8);
    if (key.length < 32) throw new IllegalArgumentException("JWT secret must have >=32 bytes");
    var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer("culturehub"),
        new JwtClaimValidator<String>("sub", ServiceBoundary::validUserId)));
    return decoder;
  }

  @Bean
  SecurityFilterChain notificationSecurity(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests
            .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/me/notifications").authenticated()
            .requestMatchers(HttpMethod.PATCH, "/api/v1/me/notifications/*/read").authenticated()
            .anyRequest().denyAll())
        .oauth2ResourceServer(tokens -> tokens.jwt(jwt -> {}))
        .build();
  }
}

@FeignClient(name = "culturehub-core", url = "${v3.core.url:}")
interface CoreIdentityClient {
  @GetMapping("/internal/v1/core/users/{id}/active")
  Map<String, Boolean> active(
      @PathVariable("id") long id,
      @RequestHeader(ServiceBoundary.CALLER) String caller,
      @RequestHeader(ServiceBoundary.CREDENTIAL) String credential,
      @RequestHeader(ServiceBoundary.TRACE) String trace);
}
