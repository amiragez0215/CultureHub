package cn.culturehub;

import cn.culturehub.v3.CombinationBranch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("v3-p4")
class CombinationParticipant {
  private final BookingLedger ledger;
  private final BookingRedis redis;
  private final CombinationTransactions transactions;
  private final AsyncBooking booking;
  private final Cache cache;
  private final String identity;

  CombinationParticipant(BookingLedger ledger, BookingRedis redis, CombinationTransactions transactions, AsyncBooking booking, Cache cache,
      @Value("${v3.participant:CORE}") String identity) {
    this.ledger = ledger;
    this.redis = redis;
    this.transactions = transactions;
    this.booking = booking;
    this.cache = cache;
    this.identity = identity;
  }

  CombinationBranch.Single fallback(CombinationBranch branch) {
    long session = branch.sessions().get(0);
    String request = branch.fallback(session);
    var saved = ledger.get(request, false);
    if (saved != null && !saved.pending()) {
      return new CombinationBranch.Single(saved.state(), saved.participation(), saved.error());
    }
    try {
      if (saved == null) {
        if (!"CORE".equals(identity)) transactions.ensurePartnerUser(branch.user());
        cache.limit(branch.user(), session);
        transactions.registerFallback(branch, session);
      }
      booking.submit(request, branch.user(), session, BookingLedger.Operation.DIRECT, null, branch.trace());
    } catch (Problem rejected) {
      if (rejected.status >= 500 || rejected.status == 429) throw rejected;
      return new CombinationBranch.Single("REJECTED", null, rejected.code);
    }
    saved = ledger.get(request, false);
    return new CombinationBranch.Single(saved.state(), saved.participation(), saved.error());
  }

  void prepare(CombinationBranch branch) {
    if (!"CORE".equals(identity)) transactions.ensurePartnerUser(branch.user());
    for (long session : branch.sessions()) {
      if (ledger.get(branch.hold(session), false) == null) cache.limit(branch.user(), session);
      var request = transactions.registerHold(branch, session);
      Problem.require(request.pending() && ledger.store().now().isBefore(request.expiry()),
          "BOOKING_REQUEST_EXPIRED", "Combination hold expired");
      if (!"RECEIVED".equals(request.state())) continue;
      var snapshot = ledger.snapshot(session, null);
      if (snapshot.paused()) throw new Problem("BOOKING_UNAVAILABLE", "Session temporarily unavailable", 503);
      String receipt = redis.reserve(snapshot, request, false);
      if ("UNSAFE".equals(receipt)) {
        ledger.pauseForInventoryLoss(session, snapshot.epoch());
        throw new Problem("BOOKING_UNAVAILABLE", "Session inventory requires reconciliation", 503);
      }
      if ("WAITLIST_PRIORITY".equals(receipt) || "SESSION_FULL".equals(receipt)) {
        throw new Problem(receipt, "Combination hold rejected");
      }
      if (!"group:1".equals(receipt)) {
        throw new Problem("BOOKING_UNAVAILABLE", "Cannot establish combination hold", 503);
      }
      ledger.queued(request, snapshot, "group");
    }
  }

  void pin(CombinationBranch branch) {
    for (long session : branch.sessions()) {
      var request = ledger.get(branch.hold(session), false);
      if (request == null || request.stream() == null || request.epoch() == null
          || !redis.contains(new BookingRedis.Receipt(request.stream(), request.id(),
          request.epoch(), request.trace()), session, true)) {
        throw new Problem("BOOKING_UNAVAILABLE", "Combination hold receipt is unavailable", 503);
      }
    }
    transactions.pin(branch);
  }

  CombinationBranch.Proof proof(CombinationBranch branch, String xid) {
    int bookings = (int) branch.sessions().stream()
        .filter(session -> transactions.findQualification(branch, session) != null).count();
    int undo = (int) ledger.store().number("select count(*) from undo_log where xid=? and log_status=0", xid);
    return new CombinationBranch.Proof(bookings, undo);
  }

  CombinationBranch.Settlement settle(CombinationBranch branch, boolean committed) {
    var ids = transactions.settle(branch, committed);
    boolean synced = true;
    for (long session : branch.sessions()) {
      var request = ledger.get(branch.hold(session), false);
      if (request == null) continue;
      if (transactions.inventorySynced(request.id())) continue;
      try {
        var snapshot = ledger.snapshot(session, null);
        if (snapshot.paused()) {
          synced = false;
          continue;
        }
        redis.apply(snapshot, request.id(), false);
        ledger.markReady(snapshot);
        ledger.inventorySynced(request.id());
      } catch (RuntimeException unavailable) {
        synced = false;
      }
    }
    return new CombinationBranch.Settlement(committed, synced, ids);
  }
}
