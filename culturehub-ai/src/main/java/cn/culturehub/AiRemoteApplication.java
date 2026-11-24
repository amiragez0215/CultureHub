package cn.culturehub;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.cloud.openfeign.EnableFeignClients;
@SpringBootConfiguration @EnableAutoConfiguration
@EnableFeignClients(clients={AiCoreClient.class,AiKnowledgeClient.class,AiCombinationClient.class})
@Import({AiRemoteConfiguration.class,AiGroupController.class,AiController.class,AiStreamController.class,GuidePlanController.class,ErrorAdvice.class})
public class AiRemoteApplication {public static void main(String[] args){SpringApplication.run(AiRemoteApplication.class,args);}}
