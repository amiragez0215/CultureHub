package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Real isolated MySQL, Redis and RabbitMQ; never substitutes broker responses. */
@EnabledIfSystemProperty(named = "culturehub.rabbitLocalTest", matches = "true")
class RabbitReliabilityLocalTest extends RabbitEndToEndTest {
  void waitUntil(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(50);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for actual delivery");
  }

  String eventId() {
    return s.one("select event_id from outbox").get("event_id").toString();
  }

  Message message() {
    var props = new MessageProperties();
    props.setMessageId(eventId());
    return new Message(
        s.one("select payload from outbox")
            .get("payload")
            .toString()
            .getBytes(StandardCharsets.UTF_8),
        props);
  }

  void observeRetries(AtomicInteger highest) {
    listener.stop();
    var consumer =
        new NotificationConsumer(
            s.db,
            new DataSourceTransactionManager(s.db.getDataSource()),
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    var handler = new RabbitNotificationListener(consumer, transport);
    listener.setMessageListener(
        (ChannelAwareMessageListener)
            (m, channel) -> {
              Number retry = m.getMessageProperties().getHeader("x-retry-attempt");
              highest.accumulateAndGet(retry == null ? 0 : retry.intValue(), Math::max);
              handler.receive(m, channel);
            });
    listener.start();
  }

  @Test
  void productionQuorumTopologyCanBeRedeclaredWithoutParameterConflict() {
    for (int i = 0; i < 2; i++) {
      for (var declaration : new RabbitTopology().notificationTopology().getDeclarables()) {
        if (declaration instanceof Exchange exchange) admin.declareExchange(exchange);
        if (declaration instanceof org.springframework.amqp.core.Queue queue) {
          assertEquals("quorum", queue.getArguments().get("x-queue-type"));
          assertTrue(queue.isDurable());
          assertFalse(queue.isAutoDelete());
          assertFalse(queue.isExclusive());
          admin.declareQueue(queue);
          assertNotNull(admin.getQueueProperties(queue.getName()));
        }
        if (declaration instanceof Binding binding) admin.declareBinding(binding);
      }
    }
  }

  @Test
  void realReturnInFirstEventDoesNotBlockLaterEventAndRecovers() throws Exception {
    create(1, false);
    String first = eventId();
    // Topic '*' matches exactly one word, so this event intentionally has no matching route.
    s.db.update(
        "update outbox set event_type='invalid.route',created_at=date_sub(now(),interval 1 day)");
    s.db.update(
        "insert into outbox(event_id,business_key,event_type,aggregate_id,payload)"
            + " select 'healthy-event','healthy-event','RESERVATION_CONFIRMED',aggregate_id,"
            + " json_set(payload,'$.eventId','healthy-event') from outbox where event_id=?",
        first);
    var publisher = new Publisher(s, transport);
    assertDoesNotThrow(publisher::publish);
    waitUntil(
        () ->
            s.number("select count(*) from notification where source_event_id='healthy-event'")
                == 1);
    assertEquals(
        "PUBLISHED",
        s.one("select status from outbox where event_id='healthy-event'").get("status"));
    assertEquals(
        "PENDING", s.one("select status from outbox where event_id=?", first).get("status"));
    assertEquals(1, s.number("select attempt_count from outbox where event_id=?", first));
    assertTrue(s.number("select next_attempt_at>now(6) from outbox where event_id=?", first) == 1);
    assertTrue(
        s.one("select last_error from outbox where event_id=?", first)
            .get("last_error")
            .toString()
            .contains("NO_ROUTE"));
    publisher.publish();
    assertEquals(1, s.number("select attempt_count from outbox where event_id=?", first));
    s.db.update("update outbox set next_attempt_at=now(6) where event_id=?", first);
    publisher.publish(); // It is still unroutable: a second actual Return must remain pending.
    assertEquals(2, s.number("select attempt_count from outbox where event_id=?", first));
    assertEquals(
        "PENDING", s.one("select status from outbox where event_id=?", first).get("status"));
    assertTrue(
        s.number(
                "select timestampdiff(second,now(6),next_attempt_at) from outbox where event_id=?",
                first)
            >= 18);
    assertEquals(1, s.number("select count(*) from notification"));
    s.db.update(
        "update outbox set event_type='RESERVATION_CONFIRMED',next_attempt_at=now(6) where"
            + " event_id=?",
        first);
    publisher.publish();
    waitUntil(() -> s.number("select count(*) from notification") == 2);
    assertEquals(2, s.number("select count(*) from outbox where status='PUBLISHED'"));
    assertEquals(3, s.number("select attempt_count from outbox where event_id=?", first));
  }

  @Test
  void markerDatabaseFailureAfterConfirmRetainsStableEventForRetry() throws Exception {
    create(1, false);
    s.db.execute(
        "CREATE TRIGGER reject_published BEFORE UPDATE ON outbox FOR EACH ROW BEGIN IF"
            + " NEW.status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='marker"
            + " failure'; END IF; END");
    try {
      assertDoesNotThrow(() -> new Publisher(s, transport).publish());
      awaitNotice();
      assertEquals("PENDING", s.one("select status from outbox").get("status"));
    } finally {
      s.db.execute("DROP TRIGGER reject_published");
    }
    s.db.update("update outbox set next_attempt_at=now(6)");
    new Publisher(s, transport).publish();
    Thread.sleep(500);
    assertEquals("PUBLISHED", s.one("select status from outbox").get("status"));
    assertEquals(1, s.number("select count(*) from notification"));
  }

  @Test
  void transientDatabaseFailureActuallyRetriesAfterDelayThenRecovers() throws Exception {
    var retry = new AtomicInteger();
    observeRetries(retry);
    create(1, false);
    s.db.execute(
        "CREATE TRIGGER notification_failure BEFORE INSERT ON notification FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='temporary test database failure'");
    try {
      new Publisher(s, transport).publish();
      waitUntil(() -> retry.get() >= 1);
      assertEquals(0, s.number("select count(*) from notification"));
    } finally {
      s.db.execute("DROP TRIGGER notification_failure");
    }
    awaitNotice();
    assertTrue(retry.get() >= 1);
  }

  @Test
  void persistentFailureHasThreeDelayedRetriesThenDeadLetterAndManualReplay() throws Exception {
    create(1, false);
    String id = eventId();
    s.db.execute(
        "CREATE TRIGGER notification_failure BEFORE INSERT ON notification FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='persistent test database failure'");
    long started = System.nanoTime();
    Message dead;
    var template = new org.springframework.amqp.rabbit.core.RabbitTemplate(factory);
    try {
      new Publisher(s, transport).publish();
      dead = template.receive(RabbitTopology.DLQ, 30000);
      assertNotNull(dead);
      assertEquals(id, dead.getMessageProperties().getMessageId());
      assertEquals(
          3, ((Number) dead.getMessageProperties().getHeader("x-retry-attempt")).intValue());
      assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(3));
      assertEquals(0, s.number("select count(*) from notification"));
      assertNull(template.receive(RabbitTopology.DLQ, 500));
    } finally {
      s.db.execute("DROP TRIGGER notification_failure");
    }
    transport.send(RabbitTopology.RECOVERY, "dead", dead);
    new DeadLetterReplay(template, transport).run();
    awaitNotice();
    assertNull(template.receive(RabbitTopology.DLQ, 500));
  }

  @Test
  void malformedJsonIsRetainedInDeadLetter() throws Exception {
    var props = new MessageProperties();
    props.setMessageId("poison-event");
    transport.send(
        RabbitTopology.EXCHANGE,
        "notification.replay",
        new Message("invalid-json".getBytes(StandardCharsets.UTF_8), props));
    Message dead =
        new org.springframework.amqp.rabbit.core.RabbitTemplate(factory)
            .receive(RabbitTopology.DLQ, 30000);
    assertNotNull(dead);
    assertEquals("invalid-json", new String(dead.getBody(), StandardCharsets.UTF_8));
    assertEquals("poison-event", dead.getMessageProperties().getMessageId());
    assertEquals(0, s.number("select count(*) from notification"));
  }

  @Test
  void actualListenerRestartRecoversQueuedEvent() throws Exception {
    listener.stop();
    create(1, false);
    new Publisher(s, transport).publish();
    assertEquals(0, s.number("select count(*) from notification"));
    assertEquals(
        1,
        ((Number)
                admin
                    .getQueueProperties(RabbitTopology.QUEUE)
                    .get(org.springframework.amqp.rabbit.core.RabbitAdmin.QUEUE_MESSAGE_COUNT))
            .intValue());
    listener.start();
    awaitNotice();
  }

  @Test
  void actualWorkerJvmExitAfterCommitBeforeAckRedeliversAndDeduplicates() throws Exception {
    listener.stop();
    create(1, false);
    new Publisher(s, transport).publish();
    var output = Files.createTempFile(Path.of("target"), "culturehub-lost-ack-", ".log");
    var child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                LostAckWorkerProcess.class.getName(),
                Integer.toString(Integer.getInteger("rabbit.port", 5672)))
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Fault worker did not terminate");
      assertEquals(86, child.exitValue(), Files.readString(output));
      assertEquals(1, s.number("select count(*) from notification"));
      var redelivery = new AtomicInteger();
      var consumer =
          new NotificationConsumer(
              s.db,
              new DataSourceTransactionManager(s.db.getDataSource()),
              new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
      var handler = new RabbitNotificationListener(consumer, transport);
      listener.setMessageListener(
          (ChannelAwareMessageListener)
              (m, channel) -> {
                assertEquals(eventId(), m.getMessageProperties().getMessageId());
                assertTrue(m.getMessageProperties().isRedelivered());
                handler.receive(m, channel);
                redelivery.incrementAndGet();
              });
      listener.start();
      waitUntil(() -> redelivery.get() == 1);
      assertEquals(1, s.number("select count(*) from notification"));
      assertEquals(
          0,
          ((Number)
                  admin
                      .getQueueProperties(RabbitTopology.QUEUE)
                      .get(org.springframework.amqp.rabbit.core.RabbitAdmin.QUEUE_MESSAGE_COUNT))
              .intValue());
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Fault worker cleanup failed");
      }
      Files.deleteIfExists(output);
    }
  }
}
