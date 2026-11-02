package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Real MySQL/Redis transaction tests; inherits the isolated booking fixtures and invariants. */
abstract class ProposalIntegrationTest extends BookingIntegrationTest {
  @Autowired AiBookingProposalService proposals;
  @Autowired GuideQueryService queries;
  String conversation = UUID.randomUUID().toString();

  String pending() {
    return proposals.create(conversation, 1, false).get("id").toString();
  }

  long count() {
    return s.number("select count(*) from participation");
  }

  @Test
  void unconfirmedProposalAndRejectionNeverBook() {
    String id = pending();
    assertEquals(0, count());
    assertEquals("PENDING", proposals.get(id).get("status"));
    assertEquals("REJECTED", proposals.reject(id).get("status"));
    assertEquals("REJECTED", proposals.confirm(id, true).get("status"));
    assertEquals(0, count());
    invariant();
  }

  @Test
  void confirmationBooksThroughOriginalEntryAndOutbox() {
    String id = pending();
    var result = proposals.confirm(id, true);
    assertEquals("SUCCEEDED", result.get("status"));
    assertEquals(1, count());
    assertEquals(
        1, s.number("select count(*) from outbox where event_type='RESERVATION_CONFIRMED'"));
    assertFalse(result.containsKey("request_id"));
    assertFalse(result.toString().contains("checkin_token"));
    invariant();
  }

  @Test
  void concurrentConfirmationsCreateExactlyOneQualification() throws Exception {
    String id = pending();
    race(
        () -> {
          identity(1);
          proposals.confirm(id, true);
        },
        () -> {
          identity(1);
          proposals.confirm(id, true);
        });
    identity(1);
    assertEquals("SUCCEEDED", proposals.get(id).get("status"));
    assertEquals(1, count());
    invariant();
  }

  @Test
  void foreignProposalCannotBeReadConfirmedOrRejected() {
    String id = pending();
    identity(2);
    assertEquals(403, assertThrows(Problem.class, () -> proposals.get(id)).status);
    assertEquals(403, assertThrows(Problem.class, () -> proposals.confirm(id, true)).status);
    assertEquals(403, assertThrows(Problem.class, () -> proposals.reject(id)).status);
    assertEquals(0, count());
  }

  @Test
  void expiredOrLostConversationCannotStartBooking() {
    String id = pending();
    s.db.update(
        "update ai_booking_proposal set expires_at=date_sub(now(),interval 1 minute) where id=?",
        id);
    assertEquals("EXPIRED", proposals.confirm(id, true).get("status"));
    assertEquals(0, count());
    id = pending();
    assertEquals("EXPIRED", proposals.confirm(id, false).get("status"));
    assertEquals(0, count());
  }

  @Test
  void fullAtConfirmationDoesNotImplicitlyWaitlist() {
    String id = pending();
    identity(2);
    booking.create(1, UUID.randomUUID().toString(), false);
    identity(1);
    var failed = proposals.confirm(id, true);
    assertEquals("FAILED", failed.get("status"));
    assertEquals("SESSION_FULL", failed.get("errorCode"));
    assertEquals(0, s.number("select count(*) from participation where user_id=1"));
    String wait = proposals.create(conversation, 1, true).get("id").toString();
    var joined = proposals.confirm(wait, true);
    assertEquals("SUCCEEDED", joined.get("status"));
    assertEquals("WAITING", ((Map<?, ?>) joined.get("participation")).get("status"));
    invariant();
  }

  @Test
  void crashAfterBookingCommitRecoversEvenAfterConversationAndProposalExpire() {
    String id = pending();
    var row = proposals.owned(id, false);
    s.db.update(
        "update ai_booking_proposal set"
            + " status='PROCESSING',processing_until=date_sub(now(),interval 1 minute) where id=?",
        id);
    booking.create(1, row.get("request_id").toString(), false);
    s.db.update(
        "update ai_booking_proposal set expires_at=date_sub(now(),interval 1 minute) where id=?",
        id);
    assertEquals("SUCCEEDED", proposals.confirm(id, false).get("status"));
    assertEquals(1, count());
    invariant();
  }

  @Test
  void expiredLeaseRetriesSameRequestAndConverges() {
    String id = pending();
    s.db.update(
        "update ai_booking_proposal set"
            + " status='PROCESSING',processing_until=date_sub(now(),interval 1 minute) where id=?",
        id);
    assertEquals("SUCCEEDED", proposals.confirm(id, true).get("status"));
    assertEquals(1, count());
    invariant();
  }

  @Test
  void ongoingLeaseCannotBeRejectedOrRunAgain() {
    String id = pending();
    s.db.update(
        "update ai_booking_proposal set"
            + " status='PROCESSING',processing_until=date_add(now(),interval 1 minute) where id=?",
        id);
    assertEquals("PROCESSING", proposals.confirm(id, true).get("status"));
    assertEquals(
        "PROPOSAL_PROCESSING", assertThrows(Problem.class, () -> proposals.reject(id)).code);
    assertEquals(0, count());
  }

  @Test
  void newSelectionSupersedesPendingButNotProcessing() {
    String id = pending();
    String newer = pending();
    assertEquals("REJECTED", proposals.get(id).get("status"));
    assertEquals("PENDING", proposals.get(newer).get("status"));
    s.db.update("update ai_booking_proposal set status='PROCESSING' where id=?", newer);
    assertEquals("PROPOSAL_PROCESSING", assertThrows(Problem.class, () -> pending()).code);
    assertEquals(0, count());
  }

  @Test
  void repeatedCommittedRequestBypassesBrokenRedisButNewBookingCannot() {
    String request = UUID.randomUUID().toString();
    var original = booking.create(1, request, false);
    Cache broken = org.mockito.Mockito.mock(Cache.class);
    org.mockito.Mockito.doThrow(new Problem("RATE_LIMIT_UNAVAILABLE", "offline", 503))
        .when(broken)
        .limit(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    Booking entry = new Booking(p, sAccess(), broken);
    assertEquals(original.get("id"), entry.create(1, request, false).get("id"));
    assertThrows(Problem.class, () -> entry.create(1, UUID.randomUUID().toString(), false));
    assertEquals(1, count());
    invariant();
  }

  Access sAccess() {
    return new Access(s);
  }

  @Test
  void confirmedResultReflectsLaterCancellationWithoutRebooking() {
    String id = pending();
    var success = proposals.confirm(id, true);
    long qualification =
        ((Number) ((Map<?, ?>) success.get("participation")).get("id")).longValue();
    p.cancel(qualification);
    var replay = proposals.confirm(id, true);
    assertEquals("SUCCEEDED", replay.get("status"));
    assertEquals("CANCELLED", ((Map<?, ?>) replay.get("participation")).get("status"));
    assertEquals(1, count());
    invariant();
  }

  @Test
  void redisFailureRemainsRecoverableAndDoesNotWriteQualification() {
    String id = pending();
    Booking unavailable = org.mockito.Mockito.mock(Booking.class);
    org.mockito.Mockito.when(
            unavailable.create(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()))
        .thenThrow(new Problem("RATE_LIMIT_UNAVAILABLE", "offline", 503));
    org.mockito.Mockito.when(
            unavailable.result(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(null);
    var service =
        new AiBookingProposalService(
            s,
            new Access(s),
            unavailable,
            queries,
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                s.db.getDataSource()));
    assertEquals("PROCESSING", service.confirm(id, true).get("status"));
    assertEquals(0, count());
    s.db.update(
        "update ai_booking_proposal set processing_until=date_sub(now(),interval 1 minute) where"
            + " id=?",
        id);
    assertEquals("SUCCEEDED", proposals.confirm(id, true).get("status"));
    assertEquals(1, count());
    invariant();
  }

  @Test
  void rejectAndConfirmRaceCannotCancelAnAlreadySubmittedBooking() throws Exception {
    String id = pending();
    race(
        () -> {
          identity(1);
          proposals.confirm(id, true);
        },
        () -> {
          identity(1);
          try {
            proposals.reject(id);
          } catch (Problem expected) {
            assertTrue(
                Set.of("PROPOSAL_PROCESSING", "PROPOSAL_ALREADY_HANDLED").contains(expected.code));
          }
        });
    identity(1);
    String status = proposals.get(id).get("status").toString();
    assertTrue(Set.of("SUCCEEDED", "REJECTED").contains(status));
    assertEquals(status.equals("SUCCEEDED") ? 1 : 0, count());
    invariant();
  }

  @Test
  void proposalExpiryCannotExceedRegistrationEnd() {
    s.db.update(
        "update activity_session set registration_end_at=date_add(now(),interval 2 minute) where"
            + " id=1");
    String id = pending();
    assertFalse(
        Store.time(proposals.owned(id, false), "expires_at")
            .isAfter(Store.time(p.lock(1), "registration_end_at")));
  }
}
