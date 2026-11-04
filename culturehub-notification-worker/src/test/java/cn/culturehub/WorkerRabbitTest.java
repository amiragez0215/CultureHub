package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = WorkerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerRabbitTest {
  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.0.44").withInitScript("worker-schema.sql");

  @Container static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1.4-management");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", mysql::getJdbcUrl);
    r.add("spring.datasource.username", mysql::getUsername);
    r.add("spring.datasource.password", mysql::getPassword);
    r.add("spring.rabbitmq.host", rabbit::getHost);
    r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
    r.add("spring.rabbitmq.username", rabbit::getAdminUsername);
    r.add("spring.rabbitmq.password", rabbit::getAdminPassword);
  }

  @Autowired RabbitListenerEndpointRegistry registry;
  @Autowired RabbitTransport transport;
  @Autowired RabbitTemplate template;
  @Autowired JdbcTemplate db;
  @Autowired NotificationConsumer consumer;

  String raw(String id) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules()
        .writeValueAsString(
            new Event(
                id, "RESERVATION_CONFIRMED", 1, 1, 1, Instant.now(), Map.of("title", "Test")));
  }

  void send(String raw) throws Exception {
    transport.send(
        RabbitTopology.EXCHANGE,
        "notification.RESERVATION_CONFIRMED",
        new Message(
            raw.getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties()));
  }

  long count(String id) {
    return db.queryForObject(
        "select count(*) from notification where source_event_id=?", Long.class, id);
  }

  void await(String id) throws Exception {
    long until = System.nanoTime() + 30_000_000_000L;
    while (count(id) == 0 && System.nanoTime() < until) Thread.sleep(100);
    assertEquals(1, count(id));
  }

  @Test
  void listenerRestartRecoversQueuedEvent() throws Exception {
    var listener = registry.getListenerContainer("notifications");
    listener.stop();
    String id = UUID.randomUUID().toString();
    try {
      send(raw(id));
      listener.start();
      await(id);
    } finally {
      listener.start();
    }
  }

  @Test
  void transientDatabaseFailureRetries() throws Exception {
    String id = UUID.randomUUID().toString();
    db.execute(
        "CREATE TRIGGER reject_temporarily BEFORE INSERT ON notification FOR EACH ROW SIGNAL"
            + " SQLSTATE '45000' SET MESSAGE_TEXT='temporary test failure'");
    try {
      send(raw(id));
      Thread.sleep(500);
      assertEquals(0, count(id));
    } finally {
      db.execute("DROP TRIGGER reject_temporarily");
    }
    await(id);
  }

  @Test
  void duplicateDeliveryAndReplayPersistOnce() throws Exception {
    String id = UUID.randomUUID().toString();
    String raw = raw(id);
    send(raw);
    send(raw);
    await(id);
    consumer.consume(raw);
    assertEquals(1, count(id));
  }

  @Test
  void poisonMessageIsRetainedInDeadLetter() throws Exception {
    send("invalid-json");
    Message dead = template.receive(RabbitTopology.DLQ, 30000);
    assertNotNull(dead);
    assertEquals(
        "invalid-json", new String(dead.getBody(), java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void manualDeadLetterReplayPreservesEventId() throws Exception {
    String id = UUID.randomUUID().toString();
    var props = new MessageProperties();
    props.setMessageId(id);
    props.setHeader("x-retry-attempt", 3);
    transport.send(
        RabbitTopology.RECOVERY,
        "dead",
        new Message(raw(id).getBytes(java.nio.charset.StandardCharsets.UTF_8), props));
    new DeadLetterReplay(template, transport).run();
    await(id);
    assertEquals(1, count(id));
  }

  @Test
  void retryExhaustionIsDeadLettered() throws Exception {
    db.execute(
        "CREATE TRIGGER reject_forever BEFORE INSERT ON notification FOR EACH ROW SIGNAL SQLSTATE"
            + " '45000' SET MESSAGE_TEXT='persistent test failure'");
    String id = UUID.randomUUID().toString();
    try {
      send(raw(id));
      Message dead = template.receive(RabbitTopology.DLQ, 30000);
      assertNotNull(dead);
      assertEquals(
          3, ((Number) dead.getMessageProperties().getHeader("x-retry-attempt")).intValue());
      assertEquals(0, count(id));
    } finally {
      db.execute("DROP TRIGGER reject_forever");
    }
  }

  @Test
  void commitBeforeLostAckRedeliversSafely() throws Exception {
    var listener = registry.getListenerContainer("notifications");
    listener.stop();
    String id = UUID.randomUUID().toString();
    String raw = raw(id);
    try {
      send(raw);
      template.execute(
          channel -> {
            var delivery = channel.basicGet(RabbitTopology.QUEUE, false);
            assertNotNull(delivery);
            try {
              consumer.consume(
                  new String(delivery.getBody(), java.nio.charset.StandardCharsets.UTF_8));
              channel.abort();
              return null;
            } catch (Exception error) {
              throw new IllegalStateException(error);
            }
          });
      listener.start();
      await(id);
      Thread.sleep(300);
      assertEquals(1, count(id));
    } finally {
      listener.start();
    }
  }
}
