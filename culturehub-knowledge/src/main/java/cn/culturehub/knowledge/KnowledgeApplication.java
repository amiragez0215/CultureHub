package cn.culturehub.knowledge;

import cn.culturehub.RabbitTransport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients(clients = CoreKnowledgeAccess.class)
@EnableScheduling
@Import(RabbitTransport.class)
public class KnowledgeApplication {
  public static void main(String[] args) {
    SpringApplication.run(KnowledgeApplication.class, args);
  }
}
