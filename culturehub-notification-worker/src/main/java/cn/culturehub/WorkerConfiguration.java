package cn.culturehub;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.*;

@Configuration
public class WorkerConfiguration {
  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
      ConnectionFactory connection) {
    var factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connection);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setPrefetchCount(10);
    factory.setDefaultRequeueRejected(false);
    factory.setRecoveryInterval(5000L);
    return factory;
  }
}
