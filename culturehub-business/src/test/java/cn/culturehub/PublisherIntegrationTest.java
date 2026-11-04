package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker = true)
class PublisherIntegrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.44");
  @Container static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1.4-management");
  JdbcTemplate db;
  Publisher publisher;
  CachingConnectionFactory factory;

  @BeforeEach
  void setup() {
    db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    db.execute("DROP TABLE IF EXISTS outbox");
    db.execute(
        "CREATE TABLE outbox(event_id VARCHAR(36) PRIMARY KEY,event_type VARCHAR(60),aggregate_id"
            + " BIGINT,payload TEXT,status VARCHAR(20) DEFAULT 'PENDING',attempt_count INT DEFAULT"
            + " 0,next_attempt_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6),last_error"
            + " VARCHAR(200),created_at DATETIME DEFAULT CURRENT_TIMESTAMP,published_at DATETIME)");
    factory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
    factory.setUsername(rabbit.getAdminUsername());
    factory.setPassword(rabbit.getAdminPassword());
    factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    factory.setPublisherReturns(true);
    var admin = new RabbitAdmin(factory);
    for (var declaration : new RabbitTopology().notificationTopology().getDeclarables()) {
      if (declaration instanceof org.springframework.amqp.core.Exchange exchange)
        admin.declareExchange(exchange);
      if (declaration instanceof org.springframework.amqp.core.Queue queue)
        admin.declareQueue(queue);
      if (declaration instanceof org.springframework.amqp.core.Binding binding)
        admin.declareBinding(binding);
    }
    publisher = new Publisher(new Store(db), new RabbitTransport(new RabbitTemplate(factory)));
    db.update(
        "insert into outbox(event_id,event_type,aggregate_id,payload)"
            + " values('test','RESERVATION_CONFIRMED',1,'{}')");
  }

  @AfterEach
  void close() {
    factory.destroy();
  }

  @Test
  void successThenMarkerFailureRetainsRetry() {
    db.execute(
        "CREATE TRIGGER reject_published BEFORE UPDATE ON outbox FOR EACH ROW BEGIN IF"
            + " NEW.status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test marker"
            + " failure'; END IF; END");
    assertDoesNotThrow(() -> publisher.publish());
    assertEquals("PENDING", db.queryForObject("select status from outbox", String.class));
    db.execute("DROP TRIGGER reject_published");
    db.update("update outbox set next_attempt_at=now()");
    publisher.publish();
    assertEquals("PUBLISHED", db.queryForObject("select status from outbox", String.class));
  }

  @Test
  void brokerSendFailureRetainsEvent() {
    var broken = new CachingConnectionFactory("127.0.0.1", 1);
    broken.setConnectionTimeout(300);
    broken.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    broken.setPublisherReturns(true);
    try {
      assertDoesNotThrow(
          () ->
              new Publisher(new Store(db), new RabbitTransport(new RabbitTemplate(broken)))
                  .publish());
      assertEquals(1, db.queryForObject("select attempt_count from outbox", Integer.class));
    } finally {
      broken.destroy();
    }
  }

  @Test
  void unroutableConfirmDoesNotMarkPublished() {
    new RabbitAdmin(factory).deleteQueue(RabbitTopology.QUEUE);
    assertDoesNotThrow(() -> publisher.publish());
    assertEquals("PENDING", db.queryForObject("select status from outbox", String.class));
  }
}
