package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PublisherIntegrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.44");

  @Container
  static KafkaContainer kafka =
      new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));

  JdbcTemplate db;
  Publisher publisher;
  KafkaTemplate<String, String> producer;

  @BeforeEach
  void setup() throws Exception {
    db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    db.execute("DROP TABLE IF EXISTS outbox");
    db.execute(
        "CREATE TABLE outbox(event_id VARCHAR(36) PRIMARY KEY,aggregate_id BIGINT,payload"
            + " TEXT,status VARCHAR(20) DEFAULT 'PENDING',attempt_count INT DEFAULT"
            + " 0,next_attempt_at DATETIME DEFAULT CURRENT_TIMESTAMP,last_error"
            + " VARCHAR(200),created_at DATETIME DEFAULT CURRENT_TIMESTAMP,published_at DATETIME)");
    var props = new HashMap<String, Object>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    producer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    publisher = new Publisher(new Store(db), producer);
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      try {
        admin.createTopics(List.of(new NewTopic(Event.TOPIC, 3, (short) 1))).all().get();
      } catch (Exception exists) {
      }
    }
    db.update("insert into outbox(event_id,aggregate_id,payload) values('test',1,'{}')");
  }

  @AfterEach
  void close() {
    producer.destroy();
  }

  @Test
  void successThenMarkerFailureRetainsRetry() {
    db.execute(
        "CREATE TRIGGER reject_published BEFORE UPDATE ON outbox FOR EACH ROW BEGIN IF"
            + " NEW.status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test marker"
            + " failure'; END IF; END");
    assertThrows(IllegalStateException.class, () -> publisher.publish());
    assertEquals("PENDING", db.queryForObject("select status from outbox", String.class));
    db.execute("DROP TRIGGER reject_published");
    db.update("update outbox set next_attempt_at=now()");
    publisher.publish();
    assertEquals("PUBLISHED", db.queryForObject("select status from outbox", String.class));
  }

  @Test
  void brokerSendFailureRetainsEvent() {
    var broken =
        new KafkaTemplate<String, String>(
            new DefaultKafkaProducerFactory<>(
                Map.of(
                    "bootstrap.servers",
                    "localhost:1",
                    "key.serializer",
                    StringSerializer.class,
                    "value.serializer",
                    StringSerializer.class,
                    "max.block.ms",
                    300,
                    "delivery.timeout.ms",
                    1000,
                    "request.timeout.ms",
                    500)));
    try {
      assertThrows(
          IllegalStateException.class, () -> new Publisher(new Store(db), broken).publish());
      assertEquals(1, db.queryForObject("select attempt_count from outbox", Integer.class));
    } finally {
      broken.destroy();
    }
  }
}
