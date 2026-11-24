package cn.culturehub;

import cn.culturehub.v3.BookingMessage;
import cn.culturehub.v3.ServiceBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("v3-p2")
class BookingLedger implements InitializingBean {
  enum Operation {
    DIRECT, JOIN, CONFIRM, PROMOTE, GROUP_HOLD;
    boolean usesHold() { return this == DIRECT || this == PROMOTE || this == GROUP_HOLD; }
  }

  static final String PENDING = "('RECEIVED','QUEUED','PROCESSING')";

  record Request(long sequence, String id, long user, long session, Operation operation,
                 String state, Long queue, Long participation, LocalDateTime expiry, String trace,
                 String epoch, String stream, String error) {
    boolean pending() { return Set.of("RECEIVED", "QUEUED", "PROCESSING").contains(state); }
  }

  record Snapshot(long session, String epoch, long revision, boolean initialized, boolean paused,
                  long seats, boolean waiting, long keepUntil) {}

  private final Store store;
  private final ObjectMapper json;
  private final long ttl;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private io.micrometer.tracing.Tracer tracer;

  BookingLedger(Store store, ObjectMapper json, @Value("${v3.booking.hold-seconds:180}") long ttl) {
    if (ttl < 5 || ttl > 1800) throw new IllegalArgumentException("Booking hold TTL must be 5–1800 seconds");
    this.store = store;
    this.json = json;
    this.ttl = ttl;
  }

  @Override
  public void afterPropertiesSet() {
    store.db.queryForList("select inventory_synced from booking_request limit 0");
    store.db.queryForList("select redis_epoch from booking_session limit 0");
  }

  Store store() {
    return store;
  }

  Request get(String id, boolean lock) {
    var rows = store.db.queryForList("select * from booking_request where request_id=?"
        + (lock ? " for update" : ""), id);
    return rows.isEmpty() ? null : request(rows.get(0));
  }

  @Transactional
  public Request register(String id, long user, long session, Operation operation, Long participation, String trace) {
    var slot = lockSession(session);
    Request existing = get(id, true);
    if (existing != null) return matches(existing, user, session, operation, participation);
    ensureSession(session);
    var now = store.now();
    if (operation == Operation.CONFIRM) {
      var row = store.one("select * from participation where id=? for update", participation);
      Problem.require(Store.id(row, "user_id") == user, "FORBIDDEN", "Qualification belongs to another user");
      Problem.require(Set.of("OFFERED", "RESERVED", "CHECKED_IN").contains(row.get("status")),
          "CONFIRM_EXPIRED", "No valid confirmation opportunity");
      if ("OFFERED".equals(row.get("status"))) {
        Problem.require(now.isBefore(Store.time(row, "confirm_deadline")), "CONFIRM_EXPIRED", "Opportunity expired");
      }
    } else {
      Problem.require(open(slot, now), "REGISTRATION_CLOSED", "Registration closed");
    }
    Problem.require(!snapshot(session, null).paused(), "BOOKING_UNAVAILABLE", "Booking reconciliation in progress");
    Long queue = null;
    if (operation == Operation.JOIN) {
      // FIFO order is committed with the durable request under this session lock.
      // Redis is only a derived projection and is contacted after this transaction.
      queue = Math.addExact(Store.id(slot, "last_waitlist_seq"), 1);
      store.db.update("update activity_session set last_waitlist_seq=? where id=?", queue, session);
      bump(session);
    }
    return insert(id, user, session, operation, queue, participation, now, trace);
  }

  Request insert(String id, long user, long session, Operation operation, Long queue,
                 Long participation, LocalDateTime now, String trace) {
    var expiry = now.plusSeconds(ttl);
    store.db.update("""
        insert into booking_request(request_id,user_id,session_id,operation,state,queue_seq,
          participation_id,trace_id,expires_at) values(?,?,?,?,'RECEIVED',?,?,?,?)
        """, id, user, session, operation.name(), queue, participation, cn.culturehub.v3.TraceCarrier.capture(tracer,trace), expiry);
    if (operation.usesHold()) {
      store.db.update("insert into reservation_hold values(?,?,'PENDING',?)", id, session, expiry);
    }
    return get(id, true);
  }

  Request matches(Request row, long user, long session, Operation operation, Long participation) {
    if (row.user() != user || row.session() != session || row.operation() != operation
        || (operation == Operation.CONFIRM && !Objects.equals(row.participation(), participation))) {
      throw new Problem("REQUEST_ID_CONFLICT", "Request parameters differ", 409);
    }
    return row;
  }

  @Transactional
  public void queued(Request request, Snapshot snapshot, String stream) {
    lockSession(request.session());
    if (store.db.update("""
        update booking_request set state='QUEUED',redis_epoch=?,stream_id=?
        where request_id=? and state='RECEIVED'
        """, snapshot.epoch(), stream, request.id()) == 1) {
      store.db.update("update reservation_hold set state='HELD' where request_id=? and state='PENDING'", request.id());
    }
    markReady(snapshot);
  }

  void markReady(Snapshot snapshot) {
    store.db.update("update booking_session set redis_ready=true where session_id=? and redis_epoch=?",
        snapshot.session(), snapshot.epoch());
  }

  Map<String, Object> lockSession(long session) {
    var slot = lockCombinationSession(session);
    requireCombinationIdle(session);
    return slot;
  }

  void requireCombinationIdle(long session) {
    if (!store.db.queryForList("""
        select id from booking_request where session_id=? and operation='GROUP_HOLD' and state='PROCESSING'
        limit 1 for update
        """, Long.class, session).isEmpty()) {
      throw new Problem("COMBINATION_IN_PROGRESS", "Session has an unresolved combination transaction", 503);
    }
  }

  Map<String, Object> lockCombinationSession(long session) {
    return store.one("select * from activity_session where id=? for update", session);
  }

  static boolean open(Map<String, Object> row, LocalDateTime now) {
    return "PUBLISHED".equals(row.get("status"))
        && !now.isBefore(Store.time(row, "registration_start_at"))
        && now.isBefore(Store.time(row, "registration_end_at"))
        && now.isBefore(Store.time(row, "start_at"));
  }

  void ensureSession(long session) {
    store.db.update("""
        insert into booking_session(session_id,redis_epoch) values(?,?)
        on duplicate key update session_id=booking_session.session_id
        """, session, UUID.randomUUID().toString());
  }

  Snapshot snapshot(long session, Long joinSequence) {
    var row = store.one("""
        select b.*,x.end_at,x.capacity-x.occupied_count seats,
          (b.paused or exists(select 1 from booking_request r where r.session_id=x.id
            and r.operation='GROUP_HOLD' and r.state='PROCESSING')) as unavailable,
          (exists(select 1 from participation p where p.session_id=x.id and p.status='WAITING')
          or exists(select 1 from booking_request r where r.session_id=x.id and r.operation='JOIN'
            and r.state in ('RECEIVED','QUEUED','PROCESSING') and (? is null or r.queue_seq<?))) as waiting
        from booking_session b join activity_session x on x.id=b.session_id where b.session_id=?
        """, joinSequence, joinSequence, session);
    long retention = Store.time(row, "end_at").plusDays(1).atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    return new Snapshot(session, row.get("redis_epoch").toString(), Store.id(row, "revision"),
        flag(row.get("redis_ready")), flag(row.get("unavailable")),
        Store.id(row, "seats"), flag(row.get("waiting")), retention);
  }

  void changed(long session, Request request, boolean release) {
    ensureSession(session);
    bump(session);
    String event = UUID.randomUUID().toString();
    var message = BookingMessage.of(event, release ? BookingMessage.SEAT_RELEASED : BookingMessage.SETTLE,
        session, request == null ? null : request.id(),
        request == null ? ServiceBoundary.traceId(null) : request.trace(), null, null);
    try {
      store.db.update("""
          insert into outbox(event_id,business_key,event_type,aggregate_id,payload) values(?,?,?,?,?)
          """, event, "v3-inventory:" + event, release ? BookingMessage.OUTBOX_RELEASE : BookingMessage.OUTBOX_SETTLE,
          session, json.writeValueAsString(message));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("Cannot encode booking inventory change", error);
    }
  }

  void bump(long session) {
    store.db.update("update booking_session set revision=revision+1 where session_id=?", session);
  }

  void finish(Request request, String state, Long qualification, String reason) {
    int count = store.db.update("""
        update booking_request set state=?,participation_id=coalesce(?,participation_id),error_code=?,
          finished_at=current_timestamp(6)
        where request_id=? and state in ('RECEIVED','QUEUED','PROCESSING')
        """, state, qualification, reason, request.id());
    if (count != 1) throw new IllegalStateException("Booking request changed during its session transaction");
    store.db.update("""
        update reservation_hold set state=? where request_id=? and state in ('PENDING','HELD')
        """, "CONFIRMED".equals(state) ? "CONSUMED" : "RELEASED", request.id());
    changed(request.session(), request, request.operation().usesHold() && !"CONFIRMED".equals(state));
  }

  @Transactional
  public void reject(Request original, String reason) {
    lockSession(original.session());
    Request request = get(original.id(), true);
    if (request.pending()) finish(request, "REJECTED", null, reason);
  }

  @Transactional
  public void expire(Request original) {
    lockSession(original.session());
    Request request = get(original.id(), true);
    if (request.pending() && !store.now().isBefore(request.expiry())) {
      finish(request, "EXPIRED", null, "BOOKING_REQUEST_EXPIRED");
    }
  }

  @Transactional
  public Snapshot pauseForRebuild(long session) {
    lockSession(session);
    ensureSession(session);
    Problem.require(store.number("select count(*) from booking_request where session_id=? and state in " + PENDING,
        session) == 0, "BOOKING_REQUEST_PENDING", "Settle live booking requests before rebuilding");
    store.db.update("""
        update booking_session set paused=true,redis_ready=false,redis_epoch=?,revision=revision+1 where session_id=?
        """, UUID.randomUUID().toString(), session);
    return snapshot(session, null);
  }

  @Transactional
  public boolean resume(Snapshot snapshot, long before, long after) {
    lockSession(snapshot.session());
    if (store.db.update("""
        update booking_session set paused=false,redis_ready=true
        where session_id=? and paused=true and redis_epoch=? and revision=?
        """, snapshot.session(), snapshot.epoch(), snapshot.revision()) == 1) {
      audit(snapshot, UUID.randomUUID().toString(), null, "RECONCILIATION", before, after);
      return true;
    }
    return false;
  }

  @Transactional
  public void pauseIfStillPending(Request original, String epoch) {
    lockSession(original.session());
    var current = get(original.id(), true);
    if (current.pending()) {
      store.db.update("update booking_session set paused=true where session_id=? and redis_epoch=?",
          current.session(), epoch);
    }
  }

  void inventorySynced(String request) {
    if (request != null) {
      store.db.update("update booking_request set inventory_synced=true where request_id=? and finished_at is not null", request);
    }
  }

  @Transactional
  public Snapshot pauseForInventoryLoss(long session, String epoch) {
    lockSession(session);
    store.db.update("update booking_session set paused=true where session_id=? and redis_epoch=?", session, epoch);
    return snapshot(session, null);
  }

  List<Map<String, Object>> waitlistHead(Snapshot snapshot) {
    return store.db.queryForList("""
        select p.id,p.queue_seq from participation p
        join booking_session b on b.session_id=p.session_id
        where p.session_id=? and p.status='WAITING' and b.revision=? and b.redis_epoch=?
        order by p.queue_seq limit 64
        """, snapshot.session(), snapshot.revision(), snapshot.epoch());
  }

  void audit(Snapshot snapshot, String event, String request, String source, long before, long after) {
    store.db.update("""
        insert ignore into booking_inventory_audit(event_id,session_id,request_id,source,
          before_available,after_available,revision) values(?,?,?,?,?,?,?)
        """, event, snapshot.session(), request, source, before < 0 ? null : before, after, snapshot.revision());
  }

  static Request request(Map<String, Object> row) {
    return new Request(Store.id(row, "id"), row.get("request_id").toString(), Store.id(row, "user_id"),
        Store.id(row, "session_id"), Operation.valueOf(row.get("operation").toString()),
        row.get("state").toString(), numberOrNull(row, "queue_seq"), numberOrNull(row, "participation_id"),
        Store.time(row, "expires_at"), row.get("trace_id").toString(), (String) row.get("redis_epoch"),
        (String) row.get("stream_id"), (String) row.get("error_code"));
  }

  private static Long numberOrNull(Map<String, Object> row, String key) {
    return row.get(key) == null ? null : Store.id(row, key);
  }

  private static boolean flag(Object value) {
    return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() != 0);
  }
}
