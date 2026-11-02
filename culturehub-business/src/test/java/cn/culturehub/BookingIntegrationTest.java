package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringJUnitConfig(BookingIntegrationTest.Config.class)
abstract class BookingIntegrationTest {
  @Container
  static MySQLContainer<?> mysql =
      new MySQLContainer<>("mysql:8.0.44").withDatabaseName("culturehub");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.4.6-alpine").withExposedPorts(6379);

  @Configuration
  @EnableTransactionManagement
  @Import({
    Store.class,
    Access.class,
    Cache.class,
    Events.class,
    ParticipationService.class,
    Booking.class,
    CheckInService.class,
    Operations.class,
    GuideQueryService.class,
    AiBookingProposalService.class
  })
  static class Config {
    @Bean
    javax.sql.DataSource source() {
      var d =
          new DriverManagerDataSource(
              Boolean.getBoolean("culturehub.localTest")
                  ? "jdbc:mysql://localhost:13306/culturehub?connectionTimeZone=Asia/Shanghai"
                  : mysql.getJdbcUrl(),
              Boolean.getBoolean("culturehub.localTest") ? "root" : mysql.getUsername(),
              Boolean.getBoolean("culturehub.localTest") ? "" : mysql.getPassword());
      return d;
    }

    @Bean
    JdbcTemplate jdbc(javax.sql.DataSource d) {
      return new JdbcTemplate(d);
    }

    @Bean
    PlatformTransactionManager tx(javax.sql.DataSource d) {
      return new DataSourceTransactionManager(d);
    }

    @Bean
    ObjectMapper json() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    LettuceConnectionFactory factory() {
      return new LettuceConnectionFactory(
          Boolean.getBoolean("culturehub.localTest") ? "localhost" : redis.getHost(),
          Boolean.getBoolean("culturehub.localTest") ? 16379 : redis.getMappedPort(6379));
    }

    @Bean
    StringRedisTemplate redis(LettuceConnectionFactory f) {
      return new StringRedisTemplate(f);
    }
  }

  @org.springframework.beans.factory.annotation.Autowired Store s;
  @org.springframework.beans.factory.annotation.Autowired ParticipationService p;
  @org.springframework.beans.factory.annotation.Autowired Booking booking;
  @org.springframework.beans.factory.annotation.Autowired CheckInService check;
  @org.springframework.beans.factory.annotation.Autowired Operations ops;
  @org.springframework.beans.factory.annotation.Autowired Events events;

  @Test
  void repeatedBusinessEventIsDeduplicated() {
    long id = create(1, false);
    events.add("RESERVATION_CONFIRMED", id, "confirmed:" + id);
    assertEquals(1, s.number("select count(*) from outbox"));
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () -> events.add("SESSION_CANCELLED", id, "confirmed:" + id));
  }

  @Test
  void ongoingCheckinIsProgressNotFinalRate() {
    long id = create(1, false);
    identity(8);
    check.check(
        1,
        s.one("select checkin_token from participation where id=?", id)
            .get("checkin_token")
            .toString());
    var metrics = ops.facts(1);
    assertNull(metrics.get("checkinRate"));
    assertEquals(1.0, metrics.get("checkinProgress"));
  }

  static void identity(long id) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("" + id, "", List.of()));
  }

  @BeforeEach
  void setup() throws Exception {
    if (s.number(
            "select count(*) from information_schema.tables where table_schema='culturehub' and"
                + " table_name='app_user'")
        == 0) {
      try (var c = s.db.getDataSource().getConnection()) {
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
            c, new org.springframework.core.io.FileSystemResource("../deploy/mysql/01-schema.sql"));
      }
    }
    try (var connection = s.db.getDataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET FOREIGN_KEY_CHECKS=0");
      for (String t :
          List.of(
              "ai_booking_proposal",
              "notification",
              "outbox",
              "review",
              "checkin_record",
              "participation_history",
              "participation",
              "activity_session",
              "activity",
              "venue_grant",
              "user_role",
              "app_user",
              "venue",
              "ai_report")) statement.execute("TRUNCATE TABLE " + t);
      statement.execute("SET FOREIGN_KEY_CHECKS=1");
    }
    for (int i = 1; i <= 8; i++)
      s.db.update(
          "insert into app_user(id,username,password_hash,nickname) values(?,?,?,?)",
          i,
          "u" + i,
          "hash",
          "u" + i);
    s.db.update("insert into user_role values(8,'PLATFORM_ADMIN')");
    s.db.update("insert into venue(id,name) values(1,'Test')");
    s.db.update("insert into activity(id,venue_id,title,status) values(1,1,'Test','PUBLISHED')");
    s.db.update(
        "insert into"
            + " activity_session(id,activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status)"
            + " values(1,1,date_sub(now(),interval 1 day),date_add(now(),interval 1"
            + " hour),date_add(now(),interval 2 hour),date_add(now(),interval 3"
            + " hour),date_sub(now(),interval 1 hour),date_add(now(),interval 3"
            + " hour),1,'PUBLISHED')");
    identity(1);
  }

  void invariant() {
    assertEquals(
        s.number("select occupied_count from activity_session where id=1"),
        s.number(
            "select count(*) from participation where session_id=1 and status in"
                + " ('OFFERED','RESERVED','CHECKED_IN')"));
    assertTrue(s.number("select occupied_count from activity_session where id=1") <= 1);
  }

  void race(Runnable a, Runnable b) throws Exception {
    var gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var x =
          pool.submit(
              () -> {
                await(gate);
                a.run();
              });
      var y =
          pool.submit(
              () -> {
                await(gate);
                b.run();
              });
      gate.countDown();
      x.get(20, TimeUnit.SECONDS);
      y.get(20, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  static void await(CountDownLatch x) {
    try {
      x.await();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  long create(long u, boolean wait) {
    identity(u);
    return Store.id(p.createTx(1, u, UUID.randomUUID().toString(), wait), "id");
  }

  @Test
  void luaRateLimitAndUnavailable() {
    identity(1);
    for (int i = 0; i < 5; i++) booking.c.limit(1, 999);
    assertThrows(Problem.class, () -> booking.c.limit(1, 999));
  }

  @Test
  void oldRequestCannotRecreateCancelledQualification() {
    String key = UUID.randomUUID().toString();
    long id = Store.id(p.createTx(1, 1, key, false), "id");
    p.cancel(id);
    var original = p.createTx(1, 1, key, false);
    assertEquals("CANCELLED", original.get("status"));
    assertEquals(0, s.number("select occupied_count from activity_session where id=1"));
    create(1, false);
    assertEquals("CANCELLED", p.cancel(id).get("status"));
    invariant();
  }

  @Test
  void concurrentWaitlistSequence() throws Exception {
    create(1, false);
    race(() -> create(2, true), () -> create(3, true));
    assertEquals(
        2, s.number("select count(distinct queue_seq) from participation where status='WAITING'"));
    identity(1);
    p.cancel(1);
    assertEquals(1, s.number("select queue_seq from participation where status='OFFERED'"));
    invariant();
  }

  @Test
  void statisticsAndReviewAreDatabaseDerived() {
    long id = create(1, false);
    identity(8);
    check.check(
        1,
        s.one("select checkin_token from participation where id=?", id)
            .get("checkin_token")
            .toString());
    s.db.update(
        "update activity_session set registration_start_at=date_sub(now(),interval 5"
            + " hour),registration_end_at=date_sub(now(),interval 4"
            + " hour),start_at=date_sub(now(),interval 3 hour),end_at=date_sub(now(),interval 1"
            + " hour),checkin_open_at=date_sub(now(),interval 4"
            + " hour),checkin_close_at=date_sub(now(),interval 1 hour) where id=1");
    identity(1);
    ops.review(1, 5, "Excellent");
    ops.review(1, 4, "Updated");
    assertEquals(1, s.number("select count(*) from review"));
    var facts = ops.facts(1);
    assertEquals(1L, facts.get("checkedIn"));
    assertEquals(1.0, facts.get("checkinRate"));
    identity(8);
    var dashboard = (Map<?, ?>) ops.dashboard(1, "2020-01-01", "2030-01-01");
    var summary = (Map<?, ?>) dashboard.get("summary");
    assertEquals(1L, summary.get("checkedIn"));
    assertEquals(1L, summary.get("activityCount"));
  }

  @Test
  void lastSeatRace() throws Exception {
    race(
        () -> {
          identity(1);
          try {
            create(1, false);
          } catch (Problem e) {
          }
        },
        () -> {
          identity(2);
          try {
            create(2, false);
          } catch (Problem e) {
          }
        });
    assertEquals(1, s.number("select count(*) from participation"));
    invariant();
  }

  @Test
  void duplicateRequest() throws Exception {
    String key = UUID.randomUUID().toString();
    race(
        () -> {
          identity(1);
          p.createTx(1, 1, key, false);
        },
        () -> {
          identity(1);
          p.createTx(1, 1, key, false);
        });
    assertEquals(1, s.number("select count(*) from participation"));
    assertEquals(1, s.number("select count(*) from outbox"));
    invariant();
  }

  @Test
  void cancellationPromotesWithoutOvertaking() throws Exception {
    long owner = create(1, false), waiter = create(2, true);
    race(
        () -> {
          identity(1);
          p.cancel(owner);
        },
        () -> {
          identity(3);
          try {
            create(3, false);
          } catch (Problem e) {
          }
        });
    assertEquals(
        "OFFERED", s.one("select status from participation where id=?", waiter).get("status"));
    invariant();
  }

  @Test
  void expiryIsIdempotent() {
    long owner = create(1, false), first = create(2, true), second = create(3, true);
    identity(1);
    p.cancel(owner);
    s.db.update(
        "update participation set confirm_deadline=date_sub(now(),interval 1 second) where id=?",
        first);
    p.expire(first);
    p.expire(first);
    assertEquals(
        "OFFERED", s.one("select status from participation where id=?", second).get("status"));
    invariant();
  }

  @Test
  void expiredConfirmationRace() throws Exception {
    long owner = create(1, false), waiter = create(2, true);
    identity(1);
    p.cancel(owner);
    s.db.update(
        "update participation set confirm_deadline=date_sub(now(),interval 1 second) where id=?",
        waiter);
    race(
        () -> {
          identity(2);
          assertThrows(Problem.class, () -> p.confirm(waiter));
        },
        () -> p.expire(waiter));
    assertEquals(
        "EXPIRED", s.one("select status from participation where id=?", waiter).get("status"));
    invariant();
  }

  @Test
  void checkinAndCancelRace() throws Exception {
    long id = create(1, false);
    String token =
        s.one("select checkin_token from participation where id=?", id)
            .get("checkin_token")
            .toString();
    race(
        () -> {
          identity(8);
          try {
            check.check(1, token);
          } catch (Problem e) {
          }
        },
        () -> {
          identity(1);
          try {
            p.cancel(id);
          } catch (Problem e) {
          }
        });
    String state =
        s.one("select status from participation where id=?", id).get("status").toString();
    assertTrue(Set.of("CANCELLED", "CHECKED_IN").contains(state));
    assertEquals(
        state.equals("CHECKED_IN") ? 1 : 0, s.number("select count(*) from checkin_record"));
    invariant();
  }

  @Test
  void doubleCheckin() {
    long id = create(1, false);
    String token =
        s.one("select checkin_token from participation where id=?", id)
            .get("checkin_token")
            .toString();
    identity(8);
    check.check(1, token);
    check.check(1, token);
    assertEquals(1, s.number("select count(*) from checkin_record"));
    invariant();
  }

  @Test
  void unauthorizedVenue() {
    create(1, false);
    identity(2);
    assertThrows(Problem.class, () -> ops.stats(1));
  }

  @Test
  void failedTransactionRollsBackAndRetries() {
    s.db.execute(
        "CREATE TRIGGER reject_outbox BEFORE INSERT ON outbox FOR EACH ROW SIGNAL SQLSTATE '45000'"
            + " SET MESSAGE_TEXT='test failure'");
    String key = UUID.randomUUID().toString();
    try {
      assertThrows(Exception.class, () -> p.createTx(1, 1, key, false));
      assertEquals(0, s.number("select count(*) from participation"));
      assertEquals(0, s.number("select occupied_count from activity_session where id=1"));
    } finally {
      s.db.execute("DROP TRIGGER reject_outbox");
    }
    p.createTx(1, 1, key, false);
    invariant();
  }
}
