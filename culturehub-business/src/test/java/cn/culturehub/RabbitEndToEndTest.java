package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Runs the original Booking service -> Outbox -> real broker -> original DB notification consumer.
 */
abstract class RabbitEndToEndTest extends BookingIntegrationTest {
  @org.testcontainers.junit.jupiter.Container
  static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1.4-management");

  CachingConnectionFactory factory;
  RabbitAdmin admin;
  RabbitTransport transport;
  SimpleMessageListenerContainer listener;

  @BeforeEach
  void brokerSetup() {
    boolean local = Boolean.getBoolean("culturehub.rabbitLocalTest");
    factory =
        new CachingConnectionFactory(
            local ? "127.0.0.1" : rabbit.getHost(),
            local ? Integer.getInteger("rabbit.port", 5672) : rabbit.getAmqpPort());
    factory.setUsername(
        local ? System.getenv().getOrDefault("RABBIT_USER", "guest") : rabbit.getAdminUsername());
    factory.setPassword(
        local
            ? System.getenv().getOrDefault("RABBIT_PASSWORD", "guest")
            : rabbit.getAdminPassword());
    factory.setVirtualHost(local ? System.getenv().getOrDefault("RABBIT_VHOST", "/") : "/");
    factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    factory.setPublisherReturns(true);
    admin = new RabbitAdmin(factory);
    for (var declaration : new RabbitTopology().notificationTopology().getDeclarables()) {
      if (declaration instanceof Exchange exchange) admin.declareExchange(exchange);
      if (declaration instanceof org.springframework.amqp.core.Queue queue)
        admin.declareQueue(queue);
      if (declaration instanceof Binding binding) admin.declareBinding(binding);
    }
    for (String name : List.of(RabbitTopology.QUEUE, RabbitTopology.RETRY, RabbitTopology.DLQ)) {
      assertEquals(
          0,
          ((Number) admin.getQueueProperties(name).get(RabbitAdmin.QUEUE_CONSUMER_COUNT))
              .intValue(),
          "Test vhost has other active consumers; use an isolated vhost and stop test services"
              + " first");
      admin.purgeQueue(name);
    }
    transport = new RabbitTransport(new RabbitTemplate(factory));
    var consumer =
        new NotificationConsumer(
            s.db,
            new DataSourceTransactionManager(s.db.getDataSource()),
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    var handler = new RabbitNotificationListener(consumer, transport);
    listener = new SimpleMessageListenerContainer(factory);
    listener.setQueueNames(RabbitTopology.QUEUE, RabbitTopology.RETRY);
    listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    listener.setMessageListener(
        (org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener)
            handler::receive);
    listener.start();
  }

  @AfterEach
  void brokerClose() {
    if (listener != null) listener.stop();
    if (factory != null) factory.destroy();
  }

  void awaitNotice() throws Exception {
    long until = System.nanoTime() + 30_000_000_000L;
    while (s.number("select count(*) from notification") == 0 && System.nanoTime() < until)
      Thread.sleep(100);
    assertEquals(1, s.number("select count(*) from notification"));
  }

  @Test
  void bookingOutboxConfirmedDeliveryAndDuplicateProduceOneNotice() throws Exception {
    identity(1);
    booking.create(1, UUID.randomUUID().toString(), false);
    assertEquals(1, s.number("select count(*) from outbox where status='PENDING'"));
    new Publisher(s, transport).publish();
    awaitNotice();
    assertEquals(1, s.number("select count(*) from outbox where status='PUBLISHED'"));
    assertEquals(1, s.number("select occupied_count from activity_session where id=1"));
    s.db.update("update outbox set status='PENDING',next_attempt_at=now()");
    new Publisher(s, transport).publish();
    Thread.sleep(500);
    assertEquals(1, s.number("select count(*) from notification"));
  }

  @Test
  void unavailableBrokerDoesNotRollbackBookingAndRecovers() throws Exception {
    var unavailable = new CachingConnectionFactory("127.0.0.1", 1);
    unavailable.setConnectionTimeout(300);
    unavailable.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    unavailable.setPublisherReturns(true);
    identity(1);
    booking.create(1, UUID.randomUUID().toString(), false);
    try {
      assertDoesNotThrow(
          () -> new Publisher(s, new RabbitTransport(new RabbitTemplate(unavailable))).publish());
      assertEquals(1, s.number("select count(*) from participation where status='RESERVED'"));
      assertEquals(1, s.number("select count(*) from outbox where status='PENDING'"));
    } finally {
      unavailable.destroy();
    }
    s.db.update("update outbox set next_attempt_at=now()");
    new Publisher(s, transport).publish();
    awaitNotice();
    assertEquals(1, s.number("select count(*) from notification"));
  }
}
