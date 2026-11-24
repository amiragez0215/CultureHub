package cn.culturehub.knowledge;

import cn.culturehub.v3.ServiceBoundary;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.amqp.core.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class KnowledgeConfiguration {
  static final String EXCHANGE = "culturehub.knowledge.commands";
  static final String ROUTE = "knowledge.ingestion.requested.v1";
  static final String QUEUE = "knowledge.ingestion.worker";

  @Bean
  InitializingBean databaseIdentity(DataSource dataSource) {
    return () -> {
      ServiceBoundary.verifyDatabase(dataSource, "knowledge_db", "culturehub_knowledge");
      new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList("select id from knowledge_document limit 0");
      new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList("select trace_id from knowledge_outbox limit 0");
    };
  }

  @Bean
  static BeanFactoryPostProcessor lazyVectors() {
    return factory -> {
      for (String name : factory.getBeanNamesForType(org.springframework.ai.vectorstore.VectorStore.class, false, false)) {
        if (factory.containsBeanDefinition(name)) factory.getBeanDefinition(name).setLazyInit(true);
      }
    };
  }

  @Bean
  MinioClient objects(@Value("${v3.minio.endpoint}") String endpoint,
                     @Value("${v3.minio.access-key}") String user,
                     @Value("${v3.minio.secret-key}") String secret) {
    var client = MinioClient.builder().endpoint(endpoint).credentials(user, secret).build();
    client.setTimeout(3000, 10000, 10000);
    return client;
  }

  @Bean
  org.springframework.boot.web.client.RestClientCustomizer externalTimeouts() {
    return builder -> {
      var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(java.time.Duration.ofSeconds(3));
      factory.setReadTimeout(java.time.Duration.ofSeconds(20));
      builder.requestFactory(factory);
    };
  }

  @Bean
  JwtDecoder knowledgeTokens(@Value("${v3.jwt-secret}") String secret) {
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
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
            .requestMatchers("/api/v1/knowledge/**", "/internal/v1/knowledge/retrieve").authenticated()
            .anyRequest().denyAll())
        .oauth2ResourceServer(tokens -> tokens.jwt(jwt -> {})).build();
  }

  @Bean
  Declarables topology() {
    var exchange = new DirectExchange(EXCHANGE);
    var dead = new DirectExchange(EXCHANGE + ".dead");
    var queue = QueueBuilder.durable(QUEUE).quorum().withArgument("x-delivery-limit", 10)
        .deadLetterExchange(dead.getName()).deadLetterRoutingKey("dead").build();
    var dlq = QueueBuilder.durable(QUEUE + ".dead").quorum().build();
    return new Declarables(exchange, dead, queue, dlq,
        BindingBuilder.bind(queue).to(exchange).with(ROUTE),
        BindingBuilder.bind(dlq).to(dead).with("dead"));
  }
}
