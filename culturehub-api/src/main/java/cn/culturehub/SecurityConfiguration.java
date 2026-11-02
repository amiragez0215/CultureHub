package cn.culturehub;

import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  JwtEncoder encoder(@Value("${culture.jwt.secret}") String secret) {
    if (secret.length() < 32)
      throw new IllegalArgumentException("JWT secret must have >=32 characters");
    return new NimbusJwtEncoder(
        new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Bean
  JwtDecoder decoder(@Value("${culture.jwt.secret}") String secret) {
    var decoder =
        NimbusJwtDecoder.withSecretKey(
                new SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"))
            .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
            .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("culturehub"));
    return decoder;
  }

  @Bean
  SecurityFilterChain filter(HttpSecurity http, Store store) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(
            c ->
                c.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            c ->
                c.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC)
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/register", "/api/v1/auth/login", "/actuator/health")
                    .permitAll()
                    .requestMatchers(
                        org.springframework.http.HttpMethod.GET,
                        "/api/v1/activities/**",
                        "/api/v1/venues",
                        "/api/v1/venues/**")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasAnyRole("PLATFORM_ADMIN", "VENUE_ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (request, response, error) -> {
                      response.setStatus(403);
                      response.setContentType("application/json");
                      response
                          .getWriter()
                          .write("{\"code\":\"FORBIDDEN\",\"message\":\"Permission denied\"}");
                    }))
        .oauth2ResourceServer(
            o ->
                o.accessDeniedHandler(
                        (request, response, error) -> {
                          response.setStatus(403);
                          response.setContentType("application/json");
                          response
                              .getWriter()
                              .write("{\"code\":\"FORBIDDEN\",\"message\":\"Permission denied\"}");
                        })
                    .authenticationEntryPoint(
                        (request, response, error) -> {
                          response.setStatus(401);
                          response.setContentType("application/json");
                          response
                              .getWriter()
                              .write(
                                  "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Login required\"}");
                        })
                    .jwt(
                        j ->
                            j.jwtAuthenticationConverter(
                                jwt -> {
                                  long id = Long.parseLong(jwt.getSubject());
                                  if (store.number(
                                          "select count(*) from app_user where id=? and enabled=1",
                                          id)
                                      != 1)
                                    throw new org.springframework.security.oauth2.core
                                        .OAuth2AuthenticationException("Account disabled");
                                  var roles =
                                      store.db.queryForList(
                                          "select role from user_role where user_id=?",
                                          String.class,
                                          id);
                                  var authorities =
                                      roles.stream()
                                          .map(
                                              r ->
                                                  new org.springframework.security.core.authority
                                                      .SimpleGrantedAuthority("ROLE_" + r))
                                          .toList();
                                  return new org.springframework.security.oauth2.server.resource
                                      .authentication.JwtAuthenticationToken(
                                      jwt, authorities, jwt.getSubject());
                                })))
        .build();
  }
}
