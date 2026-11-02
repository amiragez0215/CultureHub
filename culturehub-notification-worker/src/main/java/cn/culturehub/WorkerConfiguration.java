package cn.culturehub;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class WorkerConfiguration {
  @Bean
  DefaultErrorHandler handler(KafkaTemplate<String, String> template) {
    var recover =
        new DeadLetterPublishingRecoverer(
            template, (r, e) -> new TopicPartition(Event.DLT, r.partition()));
    recover.setFailIfSendResultIsError(true);
    var h = new DefaultErrorHandler(recover, new FixedBackOff(1000, 3));
    h.addNotRetryableExceptions(
        IllegalArgumentException.class, com.fasterxml.jackson.core.JsonProcessingException.class);
    return h;
  }

  @Bean
  org.apache.kafka.clients.admin.NewTopic topic() {
    return org.springframework.kafka.config.TopicBuilder.name(Event.TOPIC)
        .partitions(3)
        .replicas(1)
        .build();
  }

  @Bean
  org.apache.kafka.clients.admin.NewTopic dlt() {
    return org.springframework.kafka.config.TopicBuilder.name(Event.DLT)
        .partitions(3)
        .replicas(1)
        .build();
  }
}
