package cn.culturehub;

import cn.culturehub.v3.CombinationBranch;
import cn.culturehub.v3.TraceCarrier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.apache.seata.core.model.GlobalStatus;
import org.apache.seata.tm.api.GlobalTransactionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("v3-p4 & !v3-partner")
class CombinationBooking {
  record Item(String participant, long sessionId) {}
  record Submission(String requestId, boolean confirmed, boolean retainPartial, List<Item> items) {}
  private static final Logger LOG = LoggerFactory.getLogger(CombinationBooking.class);
  private static final Set<String> PARTICIPANTS = Set.of("CORE", "PARTNER_A", "PARTNER_B");
  private final Store store;
  private final ObjectMapper json;
  private final BookingRedis redis;
  private final CombinationPeers peers;
  private final CombinationTransactions local;
  private final CombinationGlobalTransaction global;
  private final boolean seataEnabled;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private io.micrometer.tracing.Tracer tracer;

  CombinationBooking(Store store, ObjectMapper json, BookingRedis redis, CombinationPeers peers,
      CombinationTransactions local, CombinationGlobalTransaction global, @Value("${seata.enabled:false}") boolean enabled) {
    this.store = store;
    this.json = json;
    this.redis = redis;
    this.peers = peers;
    this.local = local;
    this.global = global;
    this.seataEnabled = enabled;
  }

  Map<String, Object> submit(long user, Submission submission, boolean demo) {
    if (!(submission != null && submission.confirmed() && submission.requestId() != null
        && submission.requestId().matches("[A-Za-z0-9_-]{8,80}") && submission.items() != null
        && !submission.items().isEmpty() && submission.items().size() <= 3)) {
      throw new Problem("VALIDATION_FAILED", "Explicit confirmation and one to three sessions are required", 400);
    }
    if (!submission.items().stream().allMatch(item -> item != null && item.participant() != null
        && PARTICIPANTS.contains(item.participant()) && item.sessionId() > 0
        && (demo || "CORE".equals(item.participant()))) || new HashSet<>(submission.items()).size() != submission.items().size()) {
      throw new Problem("VALIDATION_FAILED", "Invalid or duplicate combination session", 400);
    }
    boolean remote = submission.items().stream().anyMatch(item -> !"CORE".equals(item.participant()));
    var items = submission.items().stream().sorted(Comparator.comparing(Item::participant).thenComparingLong(Item::sessionId)).toList();
    var existing = store.db.queryForList("select * from combination_request where request_id=?", submission.requestId());
    if (!existing.isEmpty()) return replay(existing.get(0), user, items, submission.retainPartial());
    String id = UUID.randomUUID().toString();
    String payload = encode(items);
    if (remote && !seataEnabled) throw new Problem("SEATA_UNAVAILABLE", "Cross-service booking is unavailable", 503);
    try {
      store.db.update("""
          insert into combination_request(id,request_id,user_id,items,retain_partial,trace_id,expires_at)
          values(?,?,?,?,?,?,date_add(now(6),interval 180 second))
          """, id, submission.requestId(), user, payload, submission.retainPartial(), TraceCarrier.capture(tracer, id));
    } catch (DuplicateKeyException concurrentInsert) {
      return replay(store.one("select * from combination_request where request_id=?", submission.requestId()),
          user, items, submission.retainPartial());
    }
    dispatch(id);
    return get(id, user);
  }

  private Map<String, Object> replay(Map<String, Object> row, long user, List<Item> items, boolean retainPartial) {
    if (Store.id(row, "user_id") != user || !items.equals(items(row)) || flag(row.get("retain_partial")) != retainPartial) {
      throw new Problem("REQUEST_ID_CONFLICT", "Request parameters differ", 409);
    }
    return view(row);
  }

  Map<String, Object> get(String id, long user) {
    return view(store.one("select * from combination_request where id=? and user_id=?", id, user));
  }

  Map<String, Object> byRequest(String request, long user) {
    return view(store.one("select * from combination_request where request_id=? and user_id=?", request, user));
  }

  Object history(long user, int page) {
    if (page < 1 || page > 10000) throw new Problem("VALIDATION_FAILED", "Invalid page", 400);
    return Map.of("total", store.number("select count(*) from combination_request where user_id=?", user),
        "page", page, "pageSize", 20, "records", store.db.queryForList("""
          select id,request_id,status,retain_partial,created_at from combination_request
          where user_id=? order by created_at desc,id desc limit 20 offset ?
          """, user, (page - 1) * 20));
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var value = new LinkedHashMap<String, Object>();
    for (String key : List.of("id", "request_id", "status", "retain_partial", "db_result", "error_code", "created_at")) {
      value.put(key, row.get(key));
    }
    value.put("allReleased", "FAILED".equals(row.get("status")));
    value.put("requiresAttention", "MANUAL_INTERVENTION".equals(row.get("status")));
    value.put("steps", row.get("results") == null ? items(row) : decode(row.get("results"), new TypeReference<List<Object>>() {}));
    return value;
  }

  private void dispatch(String id) {
    if (store.db.update("""
        update combination_request set next_attempt_at=date_add(now(6),interval 10 second)
        where id=? and status='RECEIVED' and next_attempt_at<=now(6)
        """, id) != 1) return;
    try {
      redis.offerCombination(id, (String) row(id).get("trace_id"));
    } catch (RuntimeException unavailable) {
      LOG.debug("Combination {} dispatch retained for retry", id, unavailable);
    }
  }

  // Only the booking command consumer claims a fresh attempt. Recovery re-offers pending
  // commands and reconciles known XIDs without starting a second global transaction.
  void execute(String id) {
    String attempt = UUID.randomUUID().toString();
    if (store.db.update("""
        update combination_request set attempt=?,status='PROCESSING',next_attempt_at=date_add(now(6),interval 60 second)
        where id=? and status='RECEIVED'
        """, attempt, id) != 1) return;
    var row = row(id);
    var branches = branches(row);
    try {
      if (!store.now().isBefore(Store.time(row, "expires_at"))) {
        outcome(id, attempt, "ROLLED_BACK", "BOOKING_REQUEST_EXPIRED");
      } else {
        for (var entry : branches.entrySet()) peers.prepare(entry.getKey(), entry.getValue());
        for (var entry : branches.entrySet()) peers.pin(entry.getKey(), entry.getValue());
        if (branches.keySet().equals(Set.of("CORE"))) local.confirmLocal(branches.get("CORE"), attempt);
        else {
          global.confirm(id, attempt, branches);
          resolve(row(id));
        }
      }
    } catch (Exception failure) {
      var saved = row(id);
      if (!attempt.equals(saved.get("attempt"))) return;
      if (saved.get("xid") != null) {
        LOG.warn("AT combination {} awaits TC recovery for xid={}", id, saved.get("xid"), failure);
        resolve(saved);
      } else if ("PROCESSING".equals(saved.get("status"))) {
        if (retryable(failure) && store.now().isBefore(Store.time(saved, "expires_at"))) {
          LOG.debug("Combination {} preparation deferred", id, failure);
          requeue(id, attempt);
        }
        else outcome(id, attempt, "ROLLED_BACK", failure instanceof Problem p ? p.code : "GROUP_REJECTED");
      }
    }
    settleIfReady(row(id));
  }

  private boolean retryable(Exception failure) {
    return !(failure instanceof Problem p && p.status < 500)
        && !(failure instanceof org.springframework.web.client.HttpClientErrorException);
  }

  private void requeue(String id, Object attempt) {
    store.db.update("""
        update combination_request set status='RECEIVED',next_attempt_at=date_add(now(6),interval 2 second)
        where id=? and attempt=? and status='PROCESSING' and xid is null
        """, id, attempt);
  }

  private void outcome(String id, Object attempt, String result, String error) {
    store.db.update("""
        update combination_request set db_result=?,error_code=?,status='SETTLING',next_attempt_at=now(6)
        where id=? and attempt=? and status='PROCESSING'
        """, result, error, id, attempt);
  }

  private void resolve(Map<String, Object> row) {
    String id = (String) row.get("id");
    if (!"PROCESSING".equals(row.get("status")) || !(row.get("xid") instanceof String xid)) return;
    try {
      GlobalStatus status = GlobalTransactionContext.reload(xid).getStatus();
      if (status == GlobalStatus.Committed || status == GlobalStatus.AsyncCommitting) {
        outcome(id, row.get("attempt"), "COMMITTED", null);
      } else if (status == GlobalStatus.Rollbacked || status == GlobalStatus.TimeoutRollbacked) {
        outcome(id, row.get("attempt"), "ROLLED_BACK", "GLOBAL_ROLLBACK");
      } else if (status == GlobalStatus.Finished) {
        // The TC removes finished sessions. Verify every participant and the absence
        // of live undo images before inferring an outcome from the durable writes.
        int expected = 0;
        int found = 0;
        int undo = 0;
        for (var entry : branches(row).entrySet()) {
          var proof = peers.proof(entry.getKey(), entry.getValue(), xid);
          expected += entry.getValue().sessions().size();
          found += proof.bookings();
          undo += proof.undoRecords();
        }
        if (undo == 0 && (found == 0 || found == expected)) {
          outcome(id, row.get("attempt"), found == 0 ? "ROLLED_BACK" : "COMMITTED",
              found == 0 ? "GLOBAL_ROLLBACK" : null);
        }
      }
    } catch (Exception unavailable) {
      LOG.debug("Cannot yet establish AT outcome for {} xid={}", id, xid, unavailable);
    }
    store.db.update("""
        update combination_request set status='MANUAL_INTERVENTION',error_code='GLOBAL_RESULT_UNKNOWN'
        where id=? and status='PROCESSING' and expires_at<date_sub(now(6),interval 10 minute)
        """, id);
  }

  private void settleIfReady(Map<String, Object> row) {
    String id = (String) row.get("id");
    if (!"SETTLING".equals(row.get("status")) || store.db.update("""
        update combination_request set next_attempt_at=date_add(now(6),interval 15 second)
        where id=? and status='SETTLING' and next_attempt_at<=now(6)
        """, id) != 1) return;
    boolean committed = "COMMITTED".equals(row.get("db_result"));
    var results = new ArrayList<Map<String, Object>>();
    boolean complete = true;
    for (var entry : branches(row).entrySet()) {
      try {
        var result = peers.settle(entry.getKey(), entry.getValue(), committed);
        complete &= result.synced();
        results.add(Map.of("participant", entry.getKey(), "session_ids", entry.getValue().sessions(),
            "participationIds", result.participationIds(), "holdStatus", result.synced() ? "SYNCED" : "PENDING"));
      } catch (RuntimeException unavailable) {
        complete = false;
        results.add(Map.of("participant", entry.getKey(), "session_ids", entry.getValue().sessions(), "holdStatus", "PENDING"));
        LOG.debug("Combination {} settlement retained for {}", id, entry.getKey(), unavailable);
      }
    }
    String status = "SETTLING";
    if (complete) {
      status = committed ? "SUCCEEDED" : flag(row.get("retain_partial")) ? "FALLBACK" : "FAILED";
    } else if (store.now().isAfter(Store.time(row, "expires_at").plusMinutes(10))) {
      status = "MANUAL_INTERVENTION";
    }
    store.db.update("""
        update combination_request set results=?,status=?,error_code=if(?='MANUAL_INTERVENTION','HOLD_SETTLEMENT_PENDING',error_code)
        where id=? and status='SETTLING'
        """, encode(results), status, status, id);
  }

  private void fallback(Map<String, Object> row) {
    String id = (String) row.get("id");
    if (store.db.update("""
        update combination_request set next_attempt_at=date_add(now(6),interval 15 second)
        where id=? and status='FALLBACK' and next_attempt_at<=now(6)
        """, id) != 1) return;
    var results = new ArrayList<Map<String, Object>>();
    boolean complete = true;
    int confirmed = 0;
    for (var item : items(row)) {
      var branch = new CombinationBranch(id, Store.id(row, "user_id"), List.of(item.sessionId()), (String) row.get("trace_id"));
      try {
        var state = peers.fallback(item.participant(), branch);
        if ("CONFIRMED".equals(state.status())) confirmed++;
        complete &= Set.of("CONFIRMED", "REJECTED", "EXPIRED").contains(state.status());
        results.add(Map.of("participant", item.participant(), "sessionId", item.sessionId(), "booking", state));
      } catch (RuntimeException unavailable) {
        complete = false;
        results.add(Map.of("participant", item.participant(), "sessionId", item.sessionId(), "status", "PENDING"));
      }
    }
    String status = complete ? confirmed == 0 ? "FAILED" : "PARTIALLY_SUCCEEDED" : "FALLBACK";
    if (!complete && store.now().isAfter(Store.time(row, "expires_at").plusMinutes(10))) status = "MANUAL_INTERVENTION";
    store.db.update("update combination_request set results=?,status=? where id=? and status='FALLBACK'",
        encode(results), status, id);
  }

  @Scheduled(fixedDelayString = "${v3.combination.recovery-delay-ms:1000}")
  public void recover() {
    for (var row : store.db.queryForList("""
        select * from combination_request where status in ('RECEIVED','PROCESSING','SETTLING','FALLBACK')
          and next_attempt_at<=now(6) order by next_attempt_at,id limit 10
        """)) {
      String id = (String) row.get("id");
      try {
        switch ((String) row.get("status")) {
          case "RECEIVED" -> dispatch(id);
          case "PROCESSING" -> {
            if (row.get("xid") == null) requeue(id, row.get("attempt"));
            else {
              if (store.db.update("""
                  update combination_request set next_attempt_at=date_add(now(6),interval 5 second)
                  where id=? and status='PROCESSING' and next_attempt_at<=now(6)
                  """, id) == 1) resolve(row);
            }
          }
          case "SETTLING" -> settleIfReady(row);
          case "FALLBACK" -> fallback(row);
          default -> { }
        }
      } catch (RuntimeException unavailable) {
        LOG.debug("Combination {} recovery deferred", id, unavailable);
      }
    }
  }

  private Map<String, Object> row(String id) {
    return store.one("select * from combination_request where id=?", id);
  }

  private Map<String, CombinationBranch> branches(Map<String, Object> row) {
    var groups = new TreeMap<String, List<Long>>();
    for (var item : items(row)) groups.computeIfAbsent(item.participant(), ignored -> new ArrayList<>()).add(item.sessionId());
    var branches = new TreeMap<String, CombinationBranch>();
    groups.forEach((name, sessions) -> branches.put(name, new CombinationBranch((String) row.get("id"),
        Store.id(row, "user_id"), sessions, (String) row.get("trace_id"))));
    return branches;
  }

  private List<Item> items(Map<String, Object> row) {
    return decode(row.get("items"), new TypeReference<>() {});
  }

  private <T> T decode(Object value, TypeReference<T> type) {
    try {
      return json.readValue(value.toString(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Invalid persisted combination payload", invalid);
    }
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new IllegalStateException("Cannot encode combination payload", invalid);
    }
  }

  private static boolean flag(Object value) {
    return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
  }
}
