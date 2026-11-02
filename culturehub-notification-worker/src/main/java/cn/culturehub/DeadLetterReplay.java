package cn.culturehub;

import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Explicit operator action only: fixes must be applied before replay. Event IDs remain unchanged.
 */
@Component
@ConditionalOnProperty(name = "culture.replay-dlt", havingValue = "true")
public class DeadLetterReplay implements CommandLineRunner {
  final KafkaProperties config;
  final KafkaTemplate<String, String> producer;

  public DeadLetterReplay(KafkaProperties c, KafkaTemplate<String, String> p) {
    config = c;
    producer = p;
  }

  public void run(String... args) throws Exception {
    var props = new HashMap<String, Object>();
    props.put("bootstrap.servers", config.getBootstrapServers());
    props.put("group.id", "culturehub-manual-dlt-replay");
    props.put("enable.auto.commit", false);
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", StringDeserializer.class);
    props.put("value.deserializer", StringDeserializer.class);
    try (var consumer = new KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of(Event.DLT));
      for (int i = 0; i < 10; i++) {
        var records = consumer.poll(Duration.ofSeconds(1));
        for (var record : records)
          producer
              .send(Event.TOPIC, record.key(), record.value())
              .get(10, java.util.concurrent.TimeUnit.SECONDS);
        if (!records.isEmpty()) consumer.commitSync();
      }
    }
  }
}
