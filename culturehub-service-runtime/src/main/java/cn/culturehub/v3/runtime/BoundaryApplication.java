package cn.culturehub.v3.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(clients = CoreReadinessClient.class)
public class BoundaryApplication {
  public static void main(String[] args) {
    SpringApplication.run(BoundaryApplication.class, args);
  }
}
