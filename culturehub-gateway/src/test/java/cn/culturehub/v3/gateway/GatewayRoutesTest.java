package cn.culturehub.v3.gateway;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import java.net.*;import java.net.http.*;import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"V3_DISCOVERY_ENABLED=false","management.server.port=0","management.endpoints.web.exposure.include=health,prometheus","v3.gateway.requests-per-second=2","v3.jwt-secret=01234567890123456789012345678901"})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class GatewayRoutesTest {
 static com.sun.net.httpserver.HttpServer backend;
 static {try{backend=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);backend.createContext("/",exchange->{byte[] body="{}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});backend.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void routes(DynamicPropertyRegistry r){for(String role:List.of("core","ai","knowledge","notification"))r.add("v3.routes."+role,()->"http://127.0.0.1:"+backend.getAddress().getPort());}
 @LocalServerPort int port;@LocalManagementPort int management;@Autowired RouteLocator locator;
 @AfterAll static void stop(){backend.stop(0);}
 String token()throws Exception{var enc=Base64.getUrlEncoder().withoutPadding();String head=enc.encodeToString("{\"alg\":\"HS256\"}".getBytes());String body=enc.encodeToString(("{\"iss\":\"culturehub\",\"sub\":\"17\",\"exp\":"+(java.time.Instant.now().getEpochSecond()+300)+"}").getBytes());var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("01234567890123456789012345678901".getBytes(),"HmacSHA256"));return head+"."+body+"."+enc.encodeToString(mac.doFinal((head+"."+body).getBytes()));}
 @Test void routesAreUniqueAndEveryBusinessRouteIsLimited()throws Exception{
  var ids=locator.getRoutes().map(route->route.getId()).collectList().block();assertEquals(5,ids.size());assertEquals(5,new HashSet<>(ids).size());
  var client=HttpClient.newHttpClient();String jwt=token();
  for(String path:List.of("/api/v1/ai/conversations","/api/v1/knowledge/documents","/api/v1/combinations","/api/v1/me/notifications","/api/v1/activities")){
   int limited=0;for(int i=0;i<12;i++){int status=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Authorization","Bearer "+jwt).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();assertTrue(status==200||status==429,path+" returned "+status);if(status==429)limited++;}assertTrue(limited>0,path+" was never limited");
  }
 }
 @Test void prometheusIsOnlyExposedOnTheManagementListener()throws Exception{
  var c=HttpClient.newHttpClient();assertEquals(200,c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/prometheus")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
  assertNotEquals(200,c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/prometheus")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
 }
}
