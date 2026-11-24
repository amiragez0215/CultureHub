package cn.culturehub;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@Profile("v3-p2")
class AsyncBooking {
  private final BookingLedger ledger;
  private final BookingRedis redis;
  private final Cache cache;
  private final BookingWorker worker;

  AsyncBooking(BookingLedger ledger, BookingRedis redis, Cache cache, BookingWorker worker) {
    this.ledger = ledger;
    this.redis = redis;
    this.cache = cache;
    this.worker = worker;
  }

  Map<String, Object> submit(String id, long user, long session, BookingLedger.Operation operation,
                             Long participation, String trace) {
    var request = ledger.get(id, false);
    if (request == null) {
      if (operation == BookingLedger.Operation.DIRECT || operation == BookingLedger.Operation.JOIN) {
        cache.limit(user, session);
      }
      try {
        request = ledger.register(id, user, session, operation, participation, trace);
      } catch (DuplicateKeyException raced) {
        request = ledger.get(id, false);
        if (request == null) throw raced;
      }
    }
    ledger.matches(request, user, session, operation, participation);
    if ("RECEIVED".equals(request.state())) enqueue(request);
    return view(ledger.get(id, false));
  }

  void enqueue(BookingLedger.Request request) {
    if (!ledger.store().now().isBefore(request.expiry())) {
      ledger.expire(request);
      return;
    }
    var snapshot = ledger.snapshot(request.session(), null);
    if (snapshot.paused()) throw unavailable();
    boolean priorWaiting = request.queue() != null && request.operation() == BookingLedger.Operation.JOIN
        && ledger.snapshot(request.session(), request.queue()).waiting();
    String receipt;
    try {
      receipt = redis.reserve(snapshot, request, priorWaiting);
    } catch (org.springframework.dao.DataAccessException unavailable) {
      throw unavailable();
    }
    if (receipt == null || receipt.equals("UNSAFE")) {
      ledger.pauseForInventoryLoss(request.session(), snapshot.epoch());
      throw unavailable();
    }
    if (receipt.equals("CLOSED")) return;
    if (Set.of("WAITLIST_PRIORITY", "SESSION_FULL", "SEATS_AVAILABLE").contains(receipt)) {
      // A promotion can wait for pending reservations to settle, without losing its place.
      if (request.operation() != BookingLedger.Operation.PROMOTE) ledger.reject(request, receipt);
      return;
    }
    int separator = receipt.lastIndexOf(':');
    if (separator <= 0) throw unavailable();
    ledger.queued(request, snapshot, receipt.substring(0, separator));
  }

  Map<String, Object> status(String id, long user) {
    var request = ledger.get(id, false);
    if (request == null || request.user() != user || request.operation() == BookingLedger.Operation.PROMOTE) {
      throw new Problem("NOT_FOUND", "Booking request not found", 404);
    }
    return view(request);
  }

  String rejection(String id, long user) {
    var request = ledger.get(id, false);
    return request == null || request.user() != user || request.pending() ? null : request.error();
  }

  BookingRedis.Inventory rebuild(long session) {
    var snapshot = ledger.pauseForRebuild(session);
    for (int attempt = 0; attempt < 3; attempt++) {
      var inventory = redis.apply(snapshot, null, true);
      if (ledger.resume(snapshot, inventory.before(), inventory.after())) {
        redis.indexWaitlist(snapshot, ledger.waitlistHead(snapshot));
        var promotion = worker.planPromotion(session, null);
        if (promotion != null) {
          try {
            enqueue(promotion);
          } catch (Problem retryLater) {
            // The durable RECEIVED request is retried by recovery.
          }
        }
        return inventory;
      }
      var latest = ledger.snapshot(session, null);
      if (!latest.epoch().equals(snapshot.epoch())) break;
      snapshot = latest;
    }
    throw unavailable();
  }

  private static Problem unavailable() {
    return new Problem("BOOKING_UNAVAILABLE", "Booking is temporarily unavailable. Retry the same request.", 503);
  }

  private Map<String, Object> view(BookingLedger.Request request) {
    var result = new LinkedHashMap<String, Object>();
    result.put("asyncBooking", true);
    result.put("requestId", request.id());
    result.put("sessionId", request.session());
    result.put("requestStatus", request.state());
    result.put("status", request.pending()
        ? (request.operation() == BookingLedger.Operation.JOIN ? "WAITLIST_PENDING" : "ACCEPTED") : request.state());
    result.put("errorCode", request.error());
    if ("CONFIRMED".equals(request.state()) && request.participation() != null) {
      var qualification = ledger.store().one("""
          select id,session_id,status,queue_seq,confirm_deadline from participation where id=? and user_id=?
          """, request.participation(), request.user());
      result.put("participation", qualification);
      result.put("id", qualification.get("id"));
    }
    return result;
  }
}
