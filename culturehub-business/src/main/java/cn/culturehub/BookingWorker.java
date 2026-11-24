package cn.culturehub;

import cn.culturehub.v3.BookingMessage;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("v3-p2")
class BookingWorker {
  private final BookingLedger ledger;
  private final ParticipationService participation;
  private final long offerMinutes;

  BookingWorker(BookingLedger ledger, ParticipationService participation,
                @Value("${v3.booking.offer-minutes:5}") long offerMinutes) {
    if (offerMinutes < 1 || offerMinutes > 60) throw new IllegalArgumentException("Invalid offer duration");
    this.ledger = ledger;
    this.participation = participation;
    this.offerMinutes = offerMinutes;
  }

  @Transactional
  public void execute(BookingMessage message) {
    var slot = ledger.lockSession(message.sessionId());
    var request = ledger.get(message.requestId(), true);
    if (request == null || request.session() != message.sessionId()) throw new IllegalArgumentException("Unknown booking request");
    if (!request.pending()) return;
    if (!ledger.store().now().isBefore(request.expiry())) {
      ledger.finish(request, "EXPIRED", null, "BOOKING_REQUEST_EXPIRED");
      return;
    }
    var snapshot = ledger.snapshot(request.session(), null);
    if (snapshot.paused() || !snapshot.epoch().equals(message.redisEpoch())
        || (request.stream() != null && !request.stream().equals(message.streamId()))) {
      throw new IllegalStateException("Booking receipt differs from the durable request");
    }
    ledger.store().db.update("""
        update booking_request set state='PROCESSING',redis_epoch=?,stream_id=?
        where request_id=? and state in ('RECEIVED','QUEUED')
        """, message.redisEpoch(), message.streamId(), request.id());
    ledger.markReady(snapshot);
    Problem.require(ledger.store().number("select count(*) from app_user where id=? and enabled=1",
        request.user()) == 1, "FORBIDDEN", "Account disabled");
    long qualification = switch (request.operation()) {
      case DIRECT -> {
        Problem.require(!hasEarlierWaiter(request), "WAITLIST_PRIORITY", "Waiting users have priority");
        yield Store.id(participation.createLocked(slot, request.user(), request.id(), false, null, true), "id");
      }
      case JOIN -> Store.id(participation.createLocked(slot, request.user(), request.id(), true, request.queue(), true), "id");
      case CONFIRM -> {
        var candidate = participation.qualification(request.participation());
        Problem.require(Store.id(candidate, "user_id") == request.user()
            && Store.id(candidate, "session_id") == request.session(), "FORBIDDEN", "Qualification differs");
        yield Store.id(participation.confirmLocked(slot, candidate), "id");
      }
      case PROMOTE -> offer(slot, request);
      case GROUP_HOLD -> throw new IllegalStateException("Combination hold is confirmed by the group worker");
    };
    ledger.finish(request, "CONFIRMED", qualification, null);
  }

  boolean hasEarlierWaiter(BookingLedger.Request request) {
    return ledger.store().number("""
        select (exists(select 1 from booking_request r where r.session_id=? and r.operation='JOIN'
                   and r.state in ('RECEIVED','QUEUED','PROCESSING') and r.id<?)
             or exists(select 1 from participation p left join booking_request r
                   on r.request_id=p.request_id and r.user_id=p.user_id and r.session_id=p.session_id
                   where p.session_id=? and p.status='WAITING' and (r.id is null or r.id<?)))
        """, request.session(), request.sequence(), request.session(), request.sequence()) != 0;
  }

  private long offer(Map<String, Object> slot, BookingLedger.Request request) {
    var now = ledger.store().now();
    Problem.require(BookingLedger.open(slot, now), "REGISTRATION_CLOSED", "Registration closed");
    var head = head(request.session());
    Problem.require(head != null && Store.id(head, "id") == request.participation()
        && !earlierJoin(request.session(), Store.id(head, "queue_seq"))
        && !hasOffer(request.session()), "WAITLIST_HEAD_CHANGED", "Waitlist head changed");
    participation.offerLocked(slot, head, now, offerMinutes);
    return request.participation();
  }

  @Transactional
  public BookingLedger.Request planPromotion(long session, String trace) {
    var slot = ledger.lockSession(session);
    var now = ledger.store().now();
    if (!BookingLedger.open(slot, now) || Store.id(slot, "occupied_count") >= Store.id(slot, "capacity")
        || hasOffer(session) || ledger.snapshot(session, null).paused()) return null;
    if (ledger.store().number("select count(*) from booking_request where session_id=? and operation='PROMOTE' and state in "
        + BookingLedger.PENDING, session) != 0) return null;
    var head = head(session);
    if (head == null || earlierJoin(session, Store.id(head, "queue_seq"))) return null;
    return ledger.insert("v3-offer-" + UUID.randomUUID(), Store.id(head, "user_id"), session,
        BookingLedger.Operation.PROMOTE, Store.id(head, "queue_seq"), Store.id(head, "id"), now, trace);
  }

  private boolean hasOffer(long session) {
    return ledger.store().number("select count(*) from participation where session_id=? and status='OFFERED'", session) != 0;
  }

  private boolean earlierJoin(long session, long sequence) {
    return ledger.store().number("""
        select count(*) from booking_request where session_id=? and operation='JOIN' and queue_seq<?
          and state in ('RECEIVED','QUEUED','PROCESSING')
        """, session, sequence) != 0;
  }

  private Map<String, Object> head(long session) {
    var rows = ledger.store().db.queryForList("""
        select * from participation where session_id=? and status='WAITING' order by queue_seq limit 1 for update
        """, session);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
