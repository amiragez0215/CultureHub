package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import cn.culturehub.v3.BookingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@EnabledIfSystemProperty(named = "culturehub.p2LocalTest", matches = "true")
@SpringJUnitConfig(BookingP2LocalTest.Fixture.class)
@ActiveProfiles("v3-p2")
@TestPropertySource(properties = "v3.booking.hold-seconds=5")
class BookingP2LocalTest {
  @Configuration
  @EnableTransactionManagement
  @Import({Store.class, Access.class, Events.class, Cache.class, ParticipationService.class,
      BookingLedger.class, BookingRedis.class, BookingWorker.class, AsyncBooking.class})
  static class Fixture {
    @Bean
    DataSource source() {
      return new DriverManagerDataSource(
          "jdbc:mysql://127.0.0.1:13306/core_db?connectionTimeZone=Asia/Shanghai", "root", "");
    }

    @Bean JdbcTemplate sql(DataSource source) { return new JdbcTemplate(source); }
    @Bean DataSourceTransactionManager tx(DataSource source) { return new DataSourceTransactionManager(source); }
    @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
    @Bean LettuceConnectionFactory factory() { return new LettuceConnectionFactory("127.0.0.1", 16379); }
    @Bean StringRedisTemplate redis(LettuceConnectionFactory factory) { return new StringRedisTemplate(factory); }
  }

  @Autowired Store store;
  @Autowired BookingLedger ledger;
  @Autowired BookingRedis redis;
  @Autowired BookingWorker worker;
  @Autowired AsyncBooking booking;
  @Autowired StringRedisTemplate redisTemplate;
  long session;
  long user;

  @BeforeEach
  void createFixture() {
    long venue = store.insert("insert into venue(name) values('P2 fixture')");
    long activity = store.insert("insert into activity(venue_id,title,status) values(?,'P2','PUBLISHED')", venue);
    session = store.insert("""
        insert into activity_session(activity_id,registration_start_at,registration_end_at,start_at,
          end_at,checkin_open_at,checkin_close_at,capacity,status)
        values(?,date_sub(now(),interval 1 hour),date_add(now(),interval 1 hour),date_add(now(),interval 2 hour),
          date_add(now(),interval 3 hour),date_add(now(),interval 2 hour),date_add(now(),interval 3 hour),1,'PUBLISHED')
        """, activity);
    user = store.insert("insert into app_user(username,password_hash,nickname) values(?, 'fixture','fixture')",
        UUID.randomUUID().toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"nonnumeric-string", "hash"})
  void joinEnqueuesAndPersistsWithCorruptLegacyQueueKey(String legacyType) {
    var direct = register();
    worker.execute(publishable(direct, ledger.snapshot(session, null)));
    assertEquals(0, redis.apply(ledger.snapshot(session, null), direct.id(), false).after());
    long waiter = store.insert("insert into app_user(username,password_hash,nickname) values(?, 'fixture','fixture')",
        UUID.randomUUID().toString());
    var join = ledger.register(UUID.randomUUID().toString(), waiter, session, BookingLedger.Operation.JOIN, null, null);
    assertEquals("RECEIVED", join.state());

    String key = "ch:booking:{" + session + "}:queue-sequence";
    if ("hash".equals(legacyType)) {
      redisTemplate.opsForHash().put(key, "legacy", "value");
    } else {
      redisTemplate.opsForValue().set(key, "not-an-integer");
    }
    redisTemplate.expire(key, Duration.ofMinutes(2));

    booking.enqueue(join);

    var queued = ledger.get(join.id(), false);
    assertEquals("QUEUED", queued.state());
    assertEquals(1L, queued.queue());
    var receipt = redis.receive(session, "join-relay", 1000).stream()
        .filter(message -> join.id().equals(message.request()))
        .findFirst().orElseThrow();
    assertEquals(queued.stream(), receipt.stream());
    worker.execute(BookingMessage.of(join.id(), BookingMessage.COMMAND, session, join.id(),
        receipt.trace(), receipt.stream(), receipt.epoch()));
    var saved = store.one("select status, queue_seq from participation where request_id=?", join.id());
    assertEquals("WAITING", saved.get("status"));
    assertEquals(1, Store.id(saved, "queue_seq"));
    assertEquals(1, store.number("select occupied_count from activity_session where id=?", session));
    if ("hash".equals(legacyType)) {
      assertEquals("value", redisTemplate.opsForHash().get(key, "legacy"));
    } else {
      assertEquals("not-an-integer", redisTemplate.opsForValue().get(key));
    }
  }

  @Test
  void fifoRegistrationDoesNotRequireRedisAndPersistsStrictOrder() {
    var key="ch:booking:{"+session+"}:queue-sequence";
    redisTemplate.opsForValue().set(key,"not-an-integer");
    var one=ledger.register(UUID.randomUUID().toString(),user,session,BookingLedger.Operation.JOIN,null,null);
    var two=ledger.register(UUID.randomUUID().toString(),user,session,BookingLedger.Operation.JOIN,null,null);
    assertEquals(1L,one.queue());assertEquals(2L,two.queue());
    assertEquals("not-an-integer",redisTemplate.opsForValue().get(key));
    assertEquals(2,store.number("select last_waitlist_seq from activity_session where id=?",session));
    redisTemplate.delete(key);
    var three=ledger.register(UUID.randomUUID().toString(),user,session,BookingLedger.Operation.JOIN,null,null);
    assertEquals(3L,three.queue());
  }

  @Test
  void workerFinishesBeforeQueuedUpdateAndRedeliveryDoesNotRepeatSideEffects() {
    var request = register();
    var snapshot = ledger.snapshot(session, null);
    var message = publishable(request, snapshot);
    worker.execute(message);
    ledger.queued(request, snapshot, message.streamId());
    worker.execute(message);

    var committed = ledger.get(request.id(), false);
    assertEquals("CONFIRMED", committed.state());
    assertEquals(message.streamId(), committed.stream());
    assertEquals(1, store.number("select occupied_count from activity_session where id=?", session));
    assertEquals(1, store.number("select count(*) from participation where request_id=?", request.id()));
    assertEquals(1, store.number("select count(*) from outbox where business_key=?",
        "confirmed:" + committed.participation()));
  }

  @Test
  void workerThatLockedBeforeExpiryCommitsWhileTimeoutWaits() throws Exception {
    var request = register();
    var snapshot = ledger.snapshot(session, null);
    var message = publishable(request, snapshot);
    ledger.queued(request, snapshot, message.streamId());
    String trigger = "p2_hold_" + request.id().replace("-", "");
    // The session and request locks are held while MySQL delays the Worker transaction.
    store.db.execute("create trigger " + trigger + " before update on booking_request for each row"
        + " set @p2_delay=if(OLD.request_id='" + request.id() + "' and NEW.state='PROCESSING',sleep(7),0)");
    var pool = Executors.newFixedThreadPool(2);
    try {
      var commit = pool.submit(() -> worker.execute(message));
      var expiry = pool.submit(() -> {
        try {
          Thread.sleep(5500);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(error);
        }
        ledger.expire(request);
      });
      commit.get(15, TimeUnit.SECONDS);
      expiry.get(15, TimeUnit.SECONDS);
      assertEquals("CONFIRMED", ledger.get(request.id(), false).state());
      assertEquals("CONSUMED", store.one("select state from reservation_hold where request_id=?", request.id()).get("state"));
      assertEquals(1, store.number("select occupied_count from activity_session where id=?", session));
    } finally {
      pool.shutdownNow();
      store.db.execute("drop trigger " + trigger);
    }
  }

  @Test
  void pendingStreamIsClaimedAfterRelayDiesAndRetainsOriginalRequest() throws Exception {
    var request = register();
    var snapshot = ledger.snapshot(session, null);
    var command = publishable(request, snapshot);
    ledger.queued(request, snapshot, command.streamId());
    var first = redis.receive(session, "first-relay", 1000);
    assertEquals(1, first.size());
    assertEquals(request.id(), first.get(0).request());
    assertTrue(redis.receive(session, "second-relay", 1000).isEmpty());
    Thread.sleep(1100);
    var recovered = redis.receive(session, "second-relay", 1000);
    assertEquals(first, recovered);
    redis.ack(session, recovered.get(0).stream());
    assertTrue(redis.receive(session, "third-relay", 1000).isEmpty());
    assertEquals("QUEUED", ledger.get(request.id(), false).state());
  }

  @Test
  void expiredHoldReleaseIsIdempotentAndAllowsExactlyOneNewReservation() {
    var request = register();
    var snapshot = ledger.snapshot(session, null);
    var message = publishable(request, snapshot);
    ledger.queued(request, snapshot, message.streamId());
    store.db.update("update booking_request set expires_at=date_sub(now(6),interval 1 second) where request_id=?", request.id());
    ledger.expire(request);
    ledger.expire(request);
    worker.execute(message);
    assertEquals("EXPIRED", ledger.get(request.id(), false).state());
    assertEquals(0, store.number("select occupied_count from activity_session where id=?", session));
    var fresh = ledger.snapshot(session, null);
    var first = redis.apply(fresh, request.id(), false);
    var duplicate = redis.apply(fresh, request.id(), false);
    assertEquals(1, first.after());
    assertEquals(first.after(), duplicate.after());
    var next = register();
    assertTrue(redis.reserve(fresh, next, false).endsWith(":1"));
    assertEquals("SESSION_FULL", redis.reserve(fresh, register(), false));
  }

  @Test
  void inventoryLossRequiresExplicitReconciliationAndPreservesCommittedCapacity() {
    var request = register();
    var snapshot = ledger.snapshot(session, null);
    var message = publishable(request, snapshot);
    ledger.queued(request, snapshot, message.streamId());
    worker.execute(message);
    var pending = register();
    assertThrows(Problem.class, () -> ledger.pauseForRebuild(session), "No rebuild with live requests");
    store.db.update("update booking_request set expires_at=date_sub(now(6),interval 1 second) where request_id=?", pending.id());
    ledger.expire(pending);
    redisTemplate.delete(java.util.List.of("ch:booking:{" + session + "}:stock",
        "ch:booking:{" + session + "}:stream"));
    var next = register();
    assertEquals("UNSAFE", redis.reserve(ledger.snapshot(session, null), next, false));
    store.db.update("update booking_request set expires_at=date_sub(now(6),interval 1 second) where request_id=?", next.id());
    ledger.expire(next);
    var rebuild = ledger.pauseForRebuild(session);
    var restored = redis.apply(rebuild, null, true);
    assertEquals(0, restored.after());
    assertTrue(ledger.resume(rebuild, restored.before(), restored.after()));
    assertEquals(1, store.number("select occupied_count from activity_session where id=?", session));
    assertEquals(1, store.number("select count(*) from booking_inventory_audit where session_id=? and source='RECONCILIATION'", session));
  }

  private BookingLedger.Request register() {
    return ledger.register(UUID.randomUUID().toString(), user, session,
        BookingLedger.Operation.DIRECT, null, null);
  }

  private BookingMessage publishable(BookingLedger.Request request, BookingLedger.Snapshot snapshot) {
    String reserved = redis.reserve(snapshot, request, false);
    assertTrue(reserved.endsWith(":1"), reserved);
    assertEquals(reserved, redis.reserve(snapshot, request, false));
    String stream = reserved.substring(0, reserved.length() - 2);
    return BookingMessage.of(request.id(), BookingMessage.COMMAND, session, request.id(),
        request.trace(), stream, snapshot.epoch());
  }
}
