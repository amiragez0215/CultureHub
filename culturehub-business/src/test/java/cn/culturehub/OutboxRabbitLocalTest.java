package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

/** Real MySQL transactions; broker replies controlled, not claimed as broker integration. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(
    named = "culturehub.localTest",
    matches = "true")
class OutboxRabbitLocalTest extends BookingIntegrationTest {
  @Test
  void firstPersistentFailureDoesNotBlockBatchAndBackoffRecovers() throws Exception {
    create(1, false);
    String failed = s.one("select event_id from outbox").get("event_id").toString();
    s.db.update("update outbox set created_at=date_sub(now(),interval 1 day)");
    s.db.update(
        "insert into outbox(event_id,event_type,aggregate_id,payload,business_key)"
            + " values('later-event','RESERVATION_CONFIRMED',1,'{}','later-event')");
    var transport = mock(RabbitTransport.class);
    var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(
            i -> {
              Message message = i.getArgument(2);
              if (failed.equals(message.getMessageProperties().getMessageId()) && failing.get())
                throw new IllegalStateException("persistent controlled failure");
              return null;
            })
        .when(transport)
        .send(anyString(), anyString(), any());
    var publisher = new Publisher(s, transport);
    publisher.publish();
    assertEquals(
        "PUBLISHED", s.one("select status from outbox where event_id='later-event'").get("status"));
    assertEquals(
        "PENDING", s.one("select status from outbox where event_id=?", failed).get("status"));
    assertEquals(1, s.number("select attempt_count from outbox where event_id=?", failed));
    assertEquals(
        "IllegalStateException: persistent controlled failure",
        s.one("select last_error from outbox where event_id=?", failed).get("last_error"));
    assertTrue(
        s.number(
                "select timestampdiff(second,now(6),next_attempt_at) from outbox where event_id=?",
                failed)
            >= 8);
    publisher.publish();
    verify(transport, times(2)).send(anyString(), anyString(), any());
    s.db.update(
        "update outbox set next_attempt_at=date_sub(now(),interval 1 second) where event_id=?",
        failed);
    publisher.publish();
    assertEquals(2, s.number("select attempt_count from outbox where event_id=?", failed));
    assertTrue(
        s.number(
                "select timestampdiff(second,now(6),next_attempt_at) from outbox where event_id=?",
                failed)
            >= 18);
    failing.set(false);
    s.db.update(
        "update outbox set next_attempt_at=date_sub(now(),interval 1 second) where event_id=?",
        failed);
    publisher.publish();
    assertEquals(2, s.number("select count(*) from outbox where status='PUBLISHED'"));
    assertEquals(3, s.number("select attempt_count from outbox where event_id=?", failed));
    assertNull(s.one("select last_error from outbox where event_id=?", failed).get("last_error"));
  }

  @Test
  void brokerFailureDoesNotRollbackReservationAndStableEventRecovers() throws Exception {
    create(1, false);
    String event = s.one("select event_id from outbox").get("event_id").toString();
    var transport = mock(RabbitTransport.class);
    doThrow(new IllegalStateException("broker unavailable"))
        .doNothing()
        .when(transport)
        .send(anyString(), anyString(), any());
    var publisher = new Publisher(s, transport);
    assertDoesNotThrow(publisher::publish);
    assertEquals("PENDING", s.one("select status from outbox").get("status"));
    assertEquals(1, s.number("select occupied_count from activity_session where id=1"));
    assertEquals(1, s.number("select count(*) from participation where status='RESERVED'"));
    s.db.update("update outbox set next_attempt_at=now()");
    publisher.publish();
    assertEquals("PUBLISHED", s.one("select status from outbox").get("status"));
    verify(transport, times(2))
        .send(
            anyString(),
            anyString(),
            argThat(m -> event.equals(m.getMessageProperties().getMessageId())));
  }

  @Test
  void concurrentPublishersClaimOnlyOnceAndDoNotHoldTransaction() throws Exception {
    create(1, false);
    var transport = mock(RabbitTransport.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            i -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return null;
            })
        .when(transport)
        .send(anyString(), anyString(), any(Message.class));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(() -> new Publisher(s, transport).publish());
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      new Publisher(s, transport).publish();
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
      verify(transport, times(1)).send(anyString(), anyString(), any());
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void abandonedLeaseIsRecoveredWithoutNewEvent() throws Exception {
    create(1, false);
    s.db.update(
        "update outbox set next_attempt_at=date_add(now(),interval 30 second),attempt_count=1");
    var transport = mock(RabbitTransport.class);
    var publisher = new Publisher(s, transport);
    publisher.publish();
    verifyNoInteractions(transport);
    s.db.update("update outbox set next_attempt_at=date_sub(now(),interval 1 second)");
    publisher.publish();
    assertEquals(1, s.number("select count(*) from outbox"));
    assertEquals("PUBLISHED", s.one("select status from outbox").get("status"));
  }

  NotificationConsumer notificationConsumer() {
    return new NotificationConsumer(
        s.db,
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(s.db.getDataSource()),
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
  }

  @Test
  void realNotificationTransactionCommitsBeforeAckAndDeduplicates() throws Exception {
    create(1, false);
    String raw = s.one("select payload from outbox").get("payload").toString();
    var consumer = notificationConsumer();
    var channel = mock(com.rabbitmq.client.Channel.class);
    var props = new org.springframework.amqp.core.MessageProperties();
    props.setDeliveryTag(77);
    doAnswer(
            i -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              assertEquals(1, s.number("select count(*) from notification"));
              return null;
            })
        .when(channel)
        .basicAck(77, false);
    new RabbitNotificationListener(consumer, mock(RabbitTransport.class))
        .receive(
            new Message(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8), props), channel);
    consumer.consume(raw);
    assertEquals(1, s.number("select count(*) from notification"));
    verify(channel).basicAck(77, false);
  }

  @Test
  void realNotificationFailureRollsBackAndControlledRetryRecovers() throws Exception {
    create(1, false);
    String raw = s.one("select payload from outbox").get("payload").toString();
    var consumer = notificationConsumer();
    var transport = mock(RabbitTransport.class);
    var retry = new java.util.concurrent.atomic.AtomicReference<Message>();
    doAnswer(
            i -> {
              retry.set(i.getArgument(2));
              return null;
            })
        .when(transport)
        .send(anyString(), anyString(), any());
    var channel = mock(com.rabbitmq.client.Channel.class);
    var handler = new RabbitNotificationListener(consumer, transport);
    s.db.execute(
        "CREATE TRIGGER notification_failure BEFORE INSERT ON notification FOR EACH ROW SIGNAL"
            + " SQLSTATE '45000' SET MESSAGE_TEXT='controlled transient database failure'");
    try {
      handler.receive(
          new Message(
              raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              new org.springframework.amqp.core.MessageProperties()),
          channel);
      assertEquals(0, s.number("select count(*) from notification"));
      assertNotNull(retry.get());
    } finally {
      s.db.execute("DROP TRIGGER notification_failure");
    }
    handler.receive(retry.get(), channel);
    assertEquals(1, s.number("select count(*) from notification"));
    consumer.consume(raw);
    assertEquals(1, s.number("select count(*) from notification"));
  }
}
