package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CoreBoundaryAuthorizationTest {
  private static final String AI = "a".repeat(32);
  private static final String KNOWLEDGE = "k".repeat(32);
  private static final String COMBINATION = "c".repeat(32);
  private static final String NOTIFICATION = "n".repeat(32);

  @Test
  void serviceCredentialsAreBoundToCallerAndEndpoint() {
    var sql = mock(JdbcTemplate.class);
    var controller = new CoreInternalController(new Store(sql), AI, KNOWLEDGE, COMBINATION, NOTIFICATION);

    assertEquals(401, assertThrows(Problem.class,
        () -> controller.readiness(null, null, "trace-test")).status);
    assertEquals(401, assertThrows(Problem.class,
        () -> controller.readiness("culturehub-ai", KNOWLEDGE, "trace-test")).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> controller.active(1, "culturehub-ai", AI)).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> controller.readiness("culturehub-notification", NOTIFICATION, "trace-test")).status);
    verifyNoInteractions(sql);

    when(sql.queryForObject("select database()", String.class)).thenReturn("core_db");
    var result = controller.readiness("culturehub-ai", AI, "trace-test");
    assertEquals("core_db", result.get("schema"));
    assertEquals("trace-test", result.get("traceId"));
  }

  @Test
  void sharedOrMalformedCredentialsFailConfiguration() {
    assertThrows(IllegalArgumentException.class, () ->
        new CoreInternalController(null, AI, AI, COMBINATION, NOTIFICATION));
    assertThrows(IllegalArgumentException.class, () ->
        new CoreInternalController(null, "short", KNOWLEDGE, COMBINATION, NOTIFICATION));
    assertThrows(IllegalArgumentException.class, () ->
        new CoreInternalController(null, AI, KNOWLEDGE, AI, NOTIFICATION));
  }
}
