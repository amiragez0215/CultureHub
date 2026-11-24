package cn.culturehub;
import cn.culturehub.ai.guide.*;
import cn.culturehub.v3.ServiceBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.filter.OncePerRequestFilter;
@Configuration(proxyBeanMethods=false)
@Import({GuideIntent.class,GuideTools.class,GuidePlanner.class,GuideLanguageModel.class,GuideGraphV2.class,GuideAgentV2.class,LettuceGuideSaver.class,AiGuideGraph.class})
public class AiRemoteConfiguration {
 @Bean Store localStore(JdbcTemplate database){return new Store(database);}
 @Bean Access access(Store store){return new AiRemoteAccess(store);}
 @Bean AiRemoteQueries queries(Store s,AiCoreClient client,@Value("${V3_AI_CORE_TOKEN}") String token){return new AiRemoteQueries(s,client,token);}
 @Bean Booking booking(Access a,AiCoreClient core,ObjectMapper json){return new AiRemoteBooking(a,core,json);}
 @Bean AiBookingProposalService proposals(Store s,Access a,Booking b,GuideQueryService q,PlatformTransactionManager tx){return new AiRemoteProposals(s,a,b,q,tx);}
 @Bean GuidePersistence persistence(Store s,Access a,AiBookingProposalService p,AiRemoteQueries q,ObjectMapper j,StringRedisTemplate r,PlatformTransactionManager tx){return new AiServicePersistence(s,a,p,q,j,r,tx);}
 @Bean GuideKnowledge knowledge(ObjectProvider<VectorStore> vectors,Store s,Access a,AiKnowledgeClient client,@Value("${V3_AI_KNOWLEDGE_TOKEN}") String token,ObjectMapper json){return new AiRemoteKnowledge(vectors,s,a,client,token,json);}
 @Bean AiService modelClient(ObjectProvider<ChatModel> m,ObjectProvider<VectorStore> v,Store s,Access a,jakarta.validation.Validator validator,ObjectMapper json){return new AiService(m,v,s,a,null,validator,json);}
 @Bean InitializingBean databaseIdentity(DataSource source){return ()->{ServiceBoundary.verifyDatabase(source,"ai_db","culturehub_ai");new JdbcTemplate(source).queryForList("select id from ai_owner limit 0");new JdbcTemplate(source).queryForList("select group_request_id,group_retain_partial from ai_activity_plan limit 0");};}
 @Bean JwtDecoder jwt(@Value("${culture.jwt.secret}") String secret){
  if(secret.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalArgumentException("JWT secret too short");
  var decoder=NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("culturehub"),new JwtClaimValidator<String>("sub",ServiceBoundary::validUserId)));return decoder;
 }
 @Bean SecurityFilterChain security(HttpSecurity http,AiCoreClient core,Store s,@Value("${V3_AI_CORE_TOKEN}") String secret)throws Exception{
  String credential=ServiceBoundary.requireCredential(secret);
  var identity=new OncePerRequestFilter(){
   @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
    if(!request.getRequestURI().startsWith("/api/v1/ai/") || org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()==null){chain.doFilter(request,response);return;}
    try{var user=core.identity(AiRemoteAccess.bearer(),"culturehub-ai",credential);long owner=((Number)user.get("userId")).longValue();
     if(owner!=Long.parseLong(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName()))throw new Problem("FORBIDDEN","Identity mismatch",403);
     s.db.update("insert ignore into ai_owner(id) values(?)",owner);
    }catch(Exception unavailable){response.setStatus(unavailable instanceof feign.FeignException f&&f.status()==403?403:503);response.setContentType("application/json");response.getWriter().write("{\"code\":\"AI_IDENTITY_UNAVAILABLE\",\"message\":\"Identity validation unavailable\"}");return;}
    chain.doFilter(request,response);
   }};
  return http.csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(c->c.requestMatchers("/actuator/health", "/actuator/prometheus").permitAll().requestMatchers("/api/v1/ai/**").authenticated().anyRequest().denyAll()).oauth2ResourceServer(c->c.jwt(j->{})).addFilterAfter(identity,BearerTokenAuthenticationFilter.class).build();
 }
 @Bean org.springframework.boot.web.client.RestClientCustomizer modelTimeouts(){return builder->{var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();factory.setConnectTimeout(java.time.Duration.ofSeconds(3));factory.setReadTimeout(java.time.Duration.ofSeconds(30));builder.requestFactory(factory);};}
}
