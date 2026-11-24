package cn.culturehub;

import cn.culturehub.v3.CombinationBranch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.seata.core.context.RootContext;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.apache.seata.rm.datasource.ConnectionProxy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("v3-p4")
class CombinationTransactions {
  private final BookingLedger ledger;
  private final BookingWorker worker;
  private final ParticipationService participation;
  private final Events events;
  private final Cache cache;
  private final Store store;

  CombinationTransactions(BookingLedger ledger, BookingWorker worker, ParticipationService participation,
      Events events, Cache cache) {
    this.ledger = ledger;
    this.worker = worker;
    this.participation = participation;
    this.events = events;
    this.cache = cache;
    this.store = ledger.store();
  }

  void ensurePartnerUser(long user) {
    store.db.update("""
        insert into app_user(id,username,password_hash,nickname) values(?,?,?,'External booking participant')
        on duplicate key update id=app_user.id
        """, user, "external_" + user, "NO_INTERACTIVE_LOGIN");
    Problem.require(store.number("select count(*) from app_user where id=? and username=? and enabled=true",
        user, "external_" + user) == 1, "FORBIDDEN", "Partner identity mismatch");
  }

  @Transactional
  public BookingLedger.Request registerHold(CombinationBranch branch, long session) {
    var row = lockBranch(branch);
    Problem.require(row.get("outcome") == null, "HOLD_UNAVAILABLE", "Combination branch has settled");
    var existing = ledger.get(branch.hold(session), false);
    if (existing != null) {
      return ledger.matches(existing, branch.user(), session, BookingLedger.Operation.GROUP_HOLD, null);
    }
    return ledger.register(branch.hold(session), branch.user(), session, BookingLedger.Operation.GROUP_HOLD,
        null, branch.trace());
  }

  @Transactional
  public void registerFallback(CombinationBranch branch, long session) {
    var row = lockBranch(branch);
    Problem.require("ROLLED_BACK".equals(row.get("outcome")), "HOLD_UNAVAILABLE", "Original branch has not rolled back");
    var original = ledger.get(branch.hold(session), false);
    if (original != null && (original.pending() || !inventorySynced(original.id()))) {
      throw new Problem("BOOKING_UNAVAILABLE", "Original hold release is pending", 503);
    }
    ledger.register(branch.fallback(session), branch.user(), session, BookingLedger.Operation.DIRECT, null, branch.trace());
  }

  @Transactional
  public void pin(CombinationBranch branch) {
    Problem.require(lockBranch(branch).get("outcome") == null, "HOLD_UNAVAILABLE", "Combination branch has settled");
    branch.sessions().forEach(ledger::lockCombinationSession);
    var now = store.now();
    for (long session : branch.sessions()) {
      var hold = ledger.get(branch.hold(session), true);
      Problem.require(hold != null && now.isBefore(hold.expiry()) && hold.user() == branch.user(),
          "BOOKING_REQUEST_EXPIRED", "Combination hold expired");
      if ("PROCESSING".equals(hold.state())) continue;
      Problem.require("QUEUED".equals(hold.state()), "HOLD_UNAVAILABLE", "Combination hold is unavailable");
      ledger.requireCombinationIdle(session);
      store.db.update("update booking_request set state='PROCESSING' where id=?", hold.sequence());
    }
  }

  @Transactional(timeout = 8)
  public CombinationBranch.Result confirmBranch(CombinationBranch branch) {
    String xid = RootContext.getXID();
    Problem.require(xid != null, "SEATA_XID_REQUIRED", "Global transaction required");
    store.db.execute((ConnectionCallback<Void>) connection -> {
      if (!(DataSourceUtils.getTargetConnection(connection) instanceof ConnectionProxy)) {
        throw new IllegalStateException("AT confirmation requires a Seata-proxied connection");
      }
      return null;
    });
    var result = confirm(branch);
    LoggerFactory.getLogger(getClass()).info("AT branch prepared xid={} group={} trace={}", xid, branch.id(), branch.trace());
    return new CombinationBranch.Result(xid, result);
  }

  @Transactional(timeout = 8)
  public void confirmLocal(CombinationBranch branch, String attempt) {
    RootContext.assertNotInGlobalTransaction();
    Problem.require(lockBranch(branch).get("outcome") == null, "HOLD_UNAVAILABLE", "Combination branch has settled");
    var ids = confirm(branch);
    for (int i = 0; i < branch.sessions().size(); i++) {
      var hold = ledger.get(branch.hold(branch.sessions().get(i)), true);
      long id = ids.get(i);
      events.add("RESERVATION_CONFIRMED", id, "confirmed:" + id);
      cache.invalidate(hold.session(), id);
      ledger.finish(hold, "CONFIRMED", id, null);
    }
    store.db.update("update combination_branch set outcome='COMMITTED' where id=?", branch.id());
    int changed = store.db.update("""
        update combination_request set db_result='COMMITTED',status='SETTLING',next_attempt_at=now(6)
        where id=? and attempt=? and status='PROCESSING' and xid is null and next_attempt_at>now(6)
        """, branch.id(), attempt);
    if (changed != 1) throw new IllegalStateException("Combination attempt superseded");
  }

  private List<Long> confirm(CombinationBranch branch) {
    var results = new ArrayList<Long>();
    Problem.require(store.number("select count(*) from app_user where id=? and enabled=true", branch.user()) == 1,
        "FORBIDDEN", "Account disabled");
    for (long session : branch.sessions()) {
      var slot = ledger.lockCombinationSession(session);
      var hold = ledger.get(branch.hold(session), true);
      Problem.require(hold != null && "PROCESSING".equals(hold.state()) && hold.user() == branch.user()
          && session == hold.session() && store.now().isBefore(hold.expiry()),
          "BOOKING_REQUEST_EXPIRED", "Combination hold is no longer valid");
      Problem.require(!worker.hasEarlierWaiter(hold), "WAITLIST_PRIORITY", "Waiting users have priority");
      results.add(Store.id(participation.createLocked(slot, branch.user(), hold.id(), false, null, true, false), "id"));
    }
    return results;
  }

  Long findQualification(CombinationBranch branch, long session) {
    return store.db.query("select id from participation where user_id=? and request_id=? and session_id=?",
        (row, index) -> row.getLong(1), branch.user(), branch.hold(session), session).stream().findFirst().orElse(null);
  }

  @Transactional
  public List<Long> settle(CombinationBranch branch, boolean committed) {
    RootContext.assertNotInGlobalTransaction();
    String outcome = committed ? "COMMITTED" : "ROLLED_BACK";
    var row = lockBranch(branch);
    if (row.get("outcome") != null && !outcome.equals(row.get("outcome"))) {
      throw new IllegalStateException("Combination branch already has a different outcome");
    }
    var ids = new ArrayList<Long>();
    for (long session : branch.sessions()) {
      var hold = ledger.get(branch.hold(session), false);
      if (hold != null) {
        ledger.lockCombinationSession(session);
        hold = ledger.get(hold.id(), true);
      }
      Long id = findQualification(branch, session);
      if (committed != (id != null) || (committed && hold == null)) {
        throw new IllegalStateException("Database result contradicts branch outcome");
      }
      if (id != null) ids.add(id);
      if (hold == null || !hold.pending()) continue;
      if (id != null) {
        events.add("RESERVATION_CONFIRMED", id, "confirmed:" + id);
        cache.invalidate(session, id);
      }
      ledger.finish(hold, committed ? "CONFIRMED" : "REJECTED", id, committed ? null : "GROUP_ROLLED_BACK");
    }
    store.db.update("update combination_branch set outcome=? where id=?", outcome, branch.id());
    return ids;
  }

  boolean inventorySynced(String request) {
    return store.number("select count(*) from booking_request where request_id=? and inventory_synced=true", request) == 1;
  }

  private Map<String, Object> lockBranch(CombinationBranch branch) {
    store.db.update("insert ignore into combination_branch(id,user_id) values(?,?)", branch.id(), branch.user());
    var row = store.one("select * from combination_branch where id=? for update", branch.id());
    Problem.require(Store.id(row, "user_id") == branch.user(), "FORBIDDEN", "Combination branch owner mismatch");
    return row;
  }
}
