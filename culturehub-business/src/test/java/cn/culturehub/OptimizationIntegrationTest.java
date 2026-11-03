package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig({BookingIntegrationTest.Config.class, OptimizationIntegrationTest.Extra.class})
abstract class OptimizationIntegrationTest extends BookingIntegrationTest {
  @Configuration
  @Import({Management.class, PublicSessions.class, CatalogReadService.class})
  static class Extra {
    @Bean
    org.apache.ibatis.session.SqlSessionFactory sqlFactory(javax.sql.DataSource source)
        throws Exception {
      var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
      factory.setDataSource(source);
      var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
      configuration.setCallSettersOnNulls(true);
      factory.setConfiguration(configuration);
      factory.setMapperLocations(
          new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
              .getResources("classpath*:mapper/*.xml"));
      return factory.getObject();
    }

    @Bean
    org.mybatis.spring.mapper.MapperFactoryBean<CatalogQueryMapper> queryMapper(
        org.apache.ibatis.session.SqlSessionFactory factory) {
      var mapper = new org.mybatis.spring.mapper.MapperFactoryBean<>(CatalogQueryMapper.class);
      mapper.setSqlSessionFactory(factory);
      return mapper;
    }

    @Bean
    org.mybatis.spring.mapper.MapperFactoryBean<ActivityMapper> activityMapper(
        org.apache.ibatis.session.SqlSessionFactory factory) {
      var mapper = new org.mybatis.spring.mapper.MapperFactoryBean<>(ActivityMapper.class);
      mapper.setSqlSessionFactory(factory);
      return mapper;
    }

    @Bean
    org.mybatis.spring.mapper.MapperFactoryBean<SessionMapper> sessionMapper(
        org.apache.ibatis.session.SqlSessionFactory factory) {
      var mapper = new org.mybatis.spring.mapper.MapperFactoryBean<>(SessionMapper.class);
      mapper.setSqlSessionFactory(factory);
      return mapper;
    }

    @Bean
    org.mybatis.spring.mapper.MapperFactoryBean<ReviewMapper> reviewMapper(
        org.apache.ibatis.session.SqlSessionFactory factory) {
      var mapper = new org.mybatis.spring.mapper.MapperFactoryBean<>(ReviewMapper.class);
      mapper.setSqlSessionFactory(factory);
      return mapper;
    }

    @Bean
    org.mybatis.spring.mapper.MapperFactoryBean<VenueMapper> venueMapper(
        org.apache.ibatis.session.SqlSessionFactory factory) {
      var mapper = new org.mybatis.spring.mapper.MapperFactoryBean<>(VenueMapper.class);
      mapper.setSqlSessionFactory(factory);
      return mapper;
    }
  }

  @org.springframework.beans.factory.annotation.Autowired Management management;
  @org.springframework.beans.factory.annotation.Autowired PublicSessions display;
  @org.springframework.beans.factory.annotation.Autowired Cache cache;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager tx;

  @org.springframework.beans.factory.annotation.Autowired VenueMapper venues;

  @BeforeEach
  void clearDisplay() throws Exception {
    cache.r.delete(
        List.of("display:activity:1:sessions", "display:session:1", "display:session:2"));
    if (s.number(
            "select count(*) from information_schema.columns where table_schema=database() and"
                + " table_name='venue' and column_name='category'")
        == 0)
      try (var connection = s.db.getDataSource().getConnection()) {
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
            connection,
            new org.springframework.core.io.FileSystemResource(
                "../deploy/migrations/20261101-venue-category.sql"));
      }
  }

  @Test
  void batchStatisticsEqualOriginalAcrossWaitlistAndEndedStates() {
    long first = create(1, false);
    long offered = create(2, true);
    create(3, true);
    identity(1);
    p.cancel(first);
    assertEquals(
        "OFFERED", s.one("select status from participation where id=?", offered).get("status"));
    identity(2);
    p.confirm(offered);
    var before = new LegacyFacts(s).facts(1);
    var after = ops.facts(1);
    before.remove("asOf");
    after.remove("asOf");
    assertEquals(before, after);
    s.db.update(
        "update activity_session set registration_start_at=date_sub(now(),interval 4"
            + " day),registration_end_at=date_sub(now(),interval 3"
            + " day),start_at=date_sub(now(),interval 2 day),end_at=date_sub(now(),interval 1"
            + " day),checkin_open_at=date_sub(now(),interval 2"
            + " day),checkin_close_at=date_sub(now(),interval 1 day) where id=1");
    before = new LegacyFacts(s).facts(1);
    after = ops.facts(1);
    before.remove("asOf");
    after.remove("asOf");
    assertEquals(before, after);
    s.db.update("update activity_session set status='CANCELLED' where id=1");
    before = new LegacyFacts(s).facts(1);
    after = ops.facts(1);
    before.remove("asOf");
    after.remove("asOf");
    assertEquals(before, after);
  }

  @Test
  void batchUsesFourQueriesForMultipleSessionsAndDashboardConstantSix() {
    duplicateSession();
    var jdbc = spy(s.db);
    var counted = new Store(jdbc);
    var countedOps = new Operations(counted, new Access(s), p, events, cache);
    var rows = countedOps.factsBatch(List.of(2L, 1L, 2L));
    assertEquals(List.of(2L, 1L), rows.stream().map(r -> r.get("sessionId")).toList());
    assertEquals(4, sqlCalls(jdbc));
    clearInvocations(jdbc);
    identity(8);
    countedOps.dashboard(1, "2000-01-01", "2099-01-01");
    assertEquals(6, sqlCalls(jdbc));
    clearInvocations(jdbc);
    new LegacyFacts(counted).facts(1);
    assertEquals(14, sqlCalls(jdbc));
  }

  static long sqlCalls(Object jdbc) {
    return mockingDetails(jdbc).getInvocations().stream()
        .filter(
            i ->
                i.getMethod().getName().equals("queryForList")
                    || (i.getMethod().getName().equals("queryForObject")
                        && i.getMethod().getParameterTypes()[1] == Class.class))
        .count();
  }

  void duplicateSession() {
    s.db.update(
        "insert into"
            + " activity_session(id,activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status)"
            + " select"
            + " 2,activity_id,registration_start_at,registration_end_at,date_add(start_at,interval"
            + " 1 day),date_add(end_at,interval 1"
            + " day),checkin_open_at,checkin_close_at,capacity,status from activity_session where"
            + " id=1");
  }

  @Test
  void publicCacheColdHotPartialMissAndOrder() {
    duplicateSession();
    var cold = display.list(1);
    assertEquals(List.of(1L, 2L), cold.stream().map(r -> Store.id(r, "id")).toList());
    assertDisplayEquals(cold, display.list(1));
    assertTrue(
        cache.r.getExpire("display:session:1") > 0 && cache.r.getExpire("display:session:1") <= 15);
    cache.r.delete("display:session:1");
    var jdbc = spy(s.db);
    var counted = new PublicSessions(new Store(jdbc), cache);
    assertDisplayEquals(cold, counted.list(1));
    assertEquals(2, sqlCalls(jdbc)); // visibility + one IN backfill
    cache.r.opsForValue().set("display:session:1", "broken-json");
    assertDisplayEquals(cold, display.list(1));
    cache.r.delete(List.of("display:session:1", "display:session:2"));
    clearInvocations(jdbc);
    assertDisplayEquals(cold, counted.list(1));
    assertEquals(2, sqlCalls(jdbc)); // all rows missing, still one IN
    cache.r.opsForValue().set("display:activity:1:sessions", "broken-index");
    assertDisplayEquals(cold, display.list(1));
    s.db.update("update activity set status='CANCELLED' where id=1");
    assertThrows(Problem.class, () -> display.list(1));
  }

  @Test
  void hotDisplayUsesOneMgetAndOneVisibilityQuery() {
    duplicateSession();
    display.list(1);
    var redis = spy(cache.r);
    var values = spy(cache.r.opsForValue());
    doReturn(values).when(redis).opsForValue();
    var jdbc = spy(s.db);
    new PublicSessions(new Store(jdbc), new Cache(redis, cache.j)).list(1);
    verify(values, times(1)).get("display:activity:1:sessions");
    verify(values, times(1)).multiGet(List.of("display:session:1", "display:session:2"));
    verify(values, never()).get("display:session:1");
    verify(values, never()).get("display:session:2");
    assertEquals(1, sqlCalls(jdbc));
  }

  void assertDisplayEquals(List<Map<String, Object>> expected, List<Map<String, Object>> actual) {
    assertEquals(expected.size(), actual.size());
    for (int i = 0; i < expected.size(); i++) {
      var left = new LinkedHashMap<>(expected.get(i));
      var right = new LinkedHashMap<>(actual.get(i));
      assertNotNull(left.remove("as_of"));
      assertNotNull(right.remove("as_of"));
      assertEquals(display.json(left), display.json(right));
    }
  }

  @Test
  void cacheFailureFallsBackToBatchDatabase() {
    duplicateSession();
    var unavailable = spy(cache);
    doReturn(null).when(unavailable).displayIndex(anyString());
    doNothing().when(unavailable).displayPut(anyMap());
    assertEquals(2, new PublicSessions(s, unavailable).list(1).size());
    var broken = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    when(broken.opsForValue()).thenThrow(new IllegalStateException("offline"));
    var fallback = new Cache(broken, cache.j);
    assertEquals(Arrays.asList(null, null), fallback.displayMany(List.of("a", "b")));
    assertEquals(2, new PublicSessions(s, fallback).list(1).size());
  }

  @Test
  void invalidationWaitsForCommitRollbackRetainsDisplayBookingUsesDatabase() {
    display.list(1);
    String key = "display:session:1";
    assertThrows(
        IllegalStateException.class,
        () ->
            new TransactionTemplate(tx)
                .execute(
                    status -> {
                      cache.invalidate(1, 0);
                      assertNotNull(cache.r.opsForValue().get(key));
                      throw new IllegalStateException("rollback");
                    }));
    assertNotNull(cache.r.opsForValue().get(key));
    create(1, false);
    assertNull(cache.r.opsForValue().get(key));
    cache.r.opsForValue().set(key, "{\"occupied_count\":0}");
    identity(2);
    assertThrows(Problem.class, () -> booking.create(1, UUID.randomUUID().toString(), false));
    invariant();
  }

  @Test
  void mapperGeneratedIdNullableUpdateAndJdbcShareTransaction() {
    identity(8);
    long id = management.venue(Map.of("name", "ORM venue", "introduction", "original"));
    assertTrue(id > 1);
    assertEquals("ORM venue", venues.selectById(id).name);
    var nullable = new HashMap<String, Object>();
    nullable.put("id", id);
    nullable.put("name", "ORM venue");
    nullable.put("introduction", null);
    management.venue(nullable);
    assertNull(venues.selectById(id).introduction);
    long count = s.number("select count(*) from venue");
    assertThrows(
        IllegalStateException.class,
        () ->
            new TransactionTemplate(tx)
                .execute(
                    status -> {
                      management.venue(Map.of("name", "rolled back"));
                      s.db.update("update venue set name='also rollback' where id=1");
                      throw new IllegalStateException("rollback both APIs");
                    }));
    assertEquals(count, s.number("select count(*) from venue"));
    assertEquals("Test", venues.selectById(1).name);
    identity(1);
    assertEquals(
        403,
        assertThrows(Problem.class, () -> management.venue(Map.of("name", "forbidden"))).status);
  }

  @Test
  void guideCapacityThresholdFiltersInRealMySqlAndRechecksChanges() {
    duplicateSession();
    s.db.update("update activity_session set capacity=3 where id=2");
    var queries = new GuideQueryService(s);
    var preferences =
        new AiGuideGraph.Preferences(null, null, null, null, null, null, null, null, null, 3);
    var found = queries.search(preferences, 1);
    assertEquals(1, found.size());
    assertEquals(2L, Store.id(found.get(0), "sessionId"));
    assertTrue(queries.matches(queries.current(2), preferences));
    // Real reservations through the original service reduce the queried availability.
    booking.create(2, UUID.randomUUID().toString(), false);
    assertFalse(queries.matches(queries.current(2), preferences));
    assertTrue(queries.search(preferences, 3).isEmpty());
    assertEquals(1, s.number("select occupied_count from activity_session where id=2"));
  }
}
