package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = WorkerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerKafkaTest {
  @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry registry;

  @Test
  void listenerRestartRecoversQueuedEvent() throws Exception {
    var container = registry.getListenerContainer("notifications");
    container.stop();
    String id = UUID.randomUUID().toString();
    String raw =
        json.writeValueAsString(
            new Event(
                id, "RESERVATION_CONFIRMED", 1, 1, 1, Instant.now(), Map.of("title", "Restart")));
    producer.send(Event.TOPIC, "restart", raw).get(10, TimeUnit.SECONDS);
    container.start();
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < until
        && db.queryForObject(
                "select count(*) from notification where source_event_id=?", Integer.class, id)
            == 0) Thread.sleep(100);
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from notification where source_event_id=?", Integer.class, id));
  }

  @Test
  void transientDatabaseFailureRetries() throws Exception {
    db.execute(
        "CREATE TRIGGER reject_temporarily BEFORE INSERT ON notification FOR EACH ROW SIGNAL"
            + " SQLSTATE '45000' SET MESSAGE_TEXT='temporary test failure'");
    String id = UUID.randomUUID().toString();
    try {
      String raw =
          json.writeValueAsString(
              new Event(
                  id, "RESERVATION_CONFIRMED", 1, 1, 1, Instant.now(), Map.of("title", "Retry")));
      producer.send(Event.TOPIC, "retry", raw).get(10, TimeUnit.SECONDS);
      Thread.sleep(500);
    } finally {
      db.execute("DROP TRIGGER reject_temporarily");
    }
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < until
        && db.queryForObject(
                "select count(*) from notification where source_event_id=?", Integer.class, id)
            == 0) Thread.sleep(100);
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from notification where source_event_id=?", Integer.class, id));
  }

  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.0.44").withInitScript("worker-schema.sql");

  @Container
  static KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", mysql::getJdbcUrl);
    r.add("spring.datasource.username", mysql::getUsername);
    r.add("spring.datasource.password", mysql::getPassword);
    r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
  }

  @Autowired JdbcTemplate db;
  @Autowired KafkaTemplate<String, String> producer;
  @Autowired ObjectMapper json;

  @Test
  void duplicateDeliveryAndReplayPersistOnce() throws Exception {
    String id = UUID.randomUUID().toString();
    String raw =
        json.writeValueAsString(
            new Event(
                id, "RESERVATION_CONFIRMED", 1, 1, 1, Instant.now(), Map.of("title", "Test")));
    producer.send(Event.TOPIC, "key", raw).get(10, TimeUnit.SECONDS);
    producer.send(Event.TOPIC, "key", raw).get(10, TimeUnit.SECONDS);
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < until
        && db.queryForObject(
                "select count(*) from notification where source_event_id=?", Integer.class, id)
            == 0) Thread.sleep(100);
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from notification where source_event_id=?", Integer.class, id));
    var restart =
        new NotificationConsumer(
            db,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                db.getDataSource()),
            json);
    restart.consume(raw);
    assertEquals(
        1,
        db.queryForObject(
            "select count(*) from notification where source_event_id=?", Integer.class, id));
  }

  @Test
  void poisonMessageIsRetainedInDeadLetter() throws Exception {
    try (var c =
        new KafkaConsumer<String, String>(
            Map.of(
                "bootstrap.servers",
                kafka.getBootstrapServers(),
                "group.id",
                "dlt-test-" + UUID.randomUUID(),
                "auto.offset.reset",
                "earliest",
                "key.deserializer",
                StringDeserializer.class,
                "value.deserializer",
                StringDeserializer.class))) {
      c.subscribe(List.of(Event.DLT));
      producer.send(Event.TOPIC, "poison", "invalid-json").get(10, TimeUnit.SECONDS);
      boolean found = false;
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!found && System.nanoTime() < until)
        for (var r : c.poll(Duration.ofMillis(500)))
          if (r.value().equals("invalid-json")) found = true;
      assertTrue(found);
    }
  }
}
