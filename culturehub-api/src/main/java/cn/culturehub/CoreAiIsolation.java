package cn.culturehub;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
@Profile("v3-p5")
class CoreAiIsolation {
  @Bean @Order(1)
  SecurityFilterChain isolatedAiRoutes(HttpSecurity http)throws Exception {
    return http.securityMatcher("/api/v1/ai/**","/api/v1/admin/ai/**")
      .csrf(csrf->csrf.disable()).authorizeHttpRequests(requests->requests.anyRequest().denyAll())
      .exceptionHandling(errors->errors.authenticationEntryPoint((req,res,err)->res.setStatus(403))
        .accessDeniedHandler((req,res,err)->res.setStatus(403))).build();
  }
}
