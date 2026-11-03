package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(
    named = "culturehub.localTest",
    matches = "true")
class CatalogLocalTest extends OptimizationIntegrationTest {
  @Autowired CatalogQueryMapper queries;
  @Autowired CatalogReadService reads;

  @Test
  void lambdaEntityReadsAndMapNullsPreserveContract() {
    assertEquals(1L, reads.published(1).venueId);
    assertEquals(1, reads.sessions(1).size());
    assertNotNull(reads.sessions(1).get(0).registrationStartAt);
    assertTrue(reads.reviews(1).isEmpty());
    assertTrue(management.activities(false, "", "", 1, 0).get(0).containsKey("description"));
    s.db.update("update activity set status='DRAFT' where id=1");
    assertThrows(Problem.class, () -> reads.published(1));
  }

  @Test
  void dynamicFiltersDatesCountAndPrivilegeAreConsistent() {
    identity(8);
    assertEquals(1, management.activityCount(false, "Test", "", 1));
    assertEquals(0, management.activityCount(false, "x' OR 1=1 --", "", 1));
    assertEquals(0, management.activityCount(false, "", "", 1, "ALL", "2000-01-01", "2001-01-01"));
    assertThrows(Problem.class, () -> management.activities(false, "", "", 0, 0));
    identity(1);
    assertEquals(0, management.activityCount(true, "", "", 0));
    s.db.update("insert into venue_grant(user_id,venue_id) values(1,1)");
    assertEquals(1, management.activityCount(true, "", "", 0));
    s.db.update("update activity set status='DRAFT' where id=1");
    assertEquals(0, management.activityCount(false, "", "", 0));
    assertEquals(1, management.activityCount(true, "", "", 0, "ALL"));
  }

  @Test
  void historyResultMapMaintainsNullableQueueAndOwnerIsolation() {
    long id = create(1, false);
    var rows = queries.history(1, 0);
    assertEquals(1, rows.size());
    var row = rows.get(0);
    assertEquals(id, row.id);
    assertEquals(1L, row.venue_id);
    assertEquals("Test", row.venue_name);
    assertEquals("RESERVED", row.status);
    assertNull(row.queue_seq);
    assertNotNull(row.server_time);
    assertTrue(queries.history(2, 0).isEmpty());
    assertTrue(queries.history(1, 20).isEmpty());
  }

  @Test
  void activityJoinDoesNotMultiplyPaginationRows() {
    duplicateSession();
    identity(8);
    assertEquals(1, management.activities(false, "", "", 1, 0).size());
    assertEquals(1, management.activityCount(false, "", "", 0));
    assertTrue(management.activities(false, "", "", 2, 0).isEmpty());
  }
}
