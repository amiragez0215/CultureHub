package cn.culturehub;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParticipationService {
  final Store s;
  final Access access;
  final Cache cache;
  final Events events;

  public ParticipationService(Store s, Access a, Cache c, Events e) {
    this.s = s;
    access = a;
    cache = c;
    events = e;
  }

  public Map<String, Object> lock(long id) {
    return s.one("select * from activity_session where id=? for update", id);
  }

  public Map<String, Object> qualification(long id) {
    return s.one("select * from participation where id=? for update", id);
  }

  public static String token() {
    byte[] b = new byte[32];
    new java.security.SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  void history(long id, String from, String to, String reason) {
    s.db.update(
        "insert into participation_history(participation_id,from_status,to_status,reason)"
            + " values(?,?,?,?)",
        id,
        from,
        to,
        reason);
  }

  void occupancy(long session, int delta) {
    Problem.require(
        s.db.update(
                "update activity_session set occupied_count=occupied_count+? where id=? and"
                    + " occupied_count+? between 0 and capacity",
                delta,
                session,
                delta)
            == 1,
        "CAPACITY_CONFLICT",
        "Capacity invariant violated");
  }

  void change(Map<String, Object> p, String to, String reason) {
    long id = Store.id(p, "id");
    String old = (String) p.get("status");
    Problem.require(
        s.db.update(
                "update participation set status=?,end_reason=?,ended_at=case when ? in"
                    + " ('CANCELLED','EXPIRED') then current_timestamp(6) else ended_at end where"
                    + " id=? and status=?",
                to,
                reason,
                to,
                id,
                old)
            == 1,
        "STATE_CONFLICT",
        "State changed");
    history(id, old, to, reason);
    cache.invalidate(Store.id(p, "session_id"), id);
  }

  boolean open(Map<String, Object> x, LocalDateTime n) {
    return x.get("status").equals("PUBLISHED")
        && !n.isBefore(Store.time(x, "registration_start_at"))
        && n.isBefore(Store.time(x, "registration_end_at"))
        && n.isBefore(Store.time(x, "start_at"));
  }

  // Called by a separate transactional facade because Spring self-invocation is not intercepted.
  public Map<String, Object> findExisting(long session, long user, String request, boolean wait) {
    var rows =
        s.db.queryForList(
            "select * from participation where user_id=? and request_id=?", user, request);
    if (rows.isEmpty()) return null;
    var old = rows.get(0);
    Problem.require(
        Store.id(old, "session_id") == session
            && old.get("entry_type").equals(wait ? "WAITLIST" : "DIRECT"),
        "REQUEST_ID_REUSED",
        "Request parameters differ");
    return old;
  }

  // Called through the Spring transaction proxy; session locking remains the final capacity
  // authority.
  @Transactional
  public Map<String, Object> createTx(long session, long u, String request, boolean wait) {
    var x = lock(session);
    var n = s.now();
    var prev =
        s.db.queryForList(
            "select * from participation where user_id=? and request_id=? for update", u, request);
    if (!prev.isEmpty()) {
      var p = prev.get(0);
      Problem.require(
          Store.id(p, "session_id") == session
              && p.get("entry_type").equals(wait ? "WAITLIST" : "DIRECT"),
          "REQUEST_ID_REUSED",
          "Request parameters differ");
      return p;
    }
    Problem.require(open(x, n), "REGISTRATION_CLOSED", "Registration closed");
    Problem.require(
        s.number(
                "select count(*) from participation where session_id=? and user_id=? and"
                    + " active_guard=1",
                session,
                u)
            == 0,
        "ALREADY_PARTICIPATING",
        "An active qualification already exists");
    long used = Store.id(x, "occupied_count"), capacity = Store.id(x, "capacity");
    Long seq = null;
    String status;
    if (wait) {
      Problem.require(used >= capacity, "SEATS_AVAILABLE", "Please reserve directly");
      seq = Store.id(x, "last_waitlist_seq") + 1;
      s.db.update("update activity_session set last_waitlist_seq=? where id=?", seq, session);
      status = "WAITING";
    } else {
      Problem.require(
          s.number(
                  "select count(*) from participation where session_id=? and status='WAITING'",
                  session)
              == 0,
          "WAITLIST_PRIORITY",
          "Waiting users have priority");
      Problem.require(used < capacity, "SESSION_FULL", "Session full");
      occupancy(session, 1);
      status = "RESERVED";
    }
    long id =
        s.insert(
            "insert into"
                + " participation(session_id,user_id,status,entry_type,request_id,queue_seq,reserved_at,checkin_token)"
                + " values(?,?,?,?,?,?,?,?)",
            session,
            u,
            status,
            wait ? "WAITLIST" : "DIRECT",
            request,
            seq,
            wait ? null : n,
            wait ? null : token());
    history(id, null, status, "CREATED");
    if (!wait) events.add("RESERVATION_CONFIRMED", id, "confirmed:" + id);
    cache.invalidate(session, id);
    return s.one("select * from participation where id=?", id);
  }

  @Transactional
  public Map<String, Object> cancel(long id) {
    long session = s.number("select session_id from participation where id=?", id);
    var x = lock(session);
    var p = qualification(id);
    access.owner(Store.id(p, "user_id"));
    var n = s.now();
    String state = (String) p.get("status");
    if (Set.of("CANCELLED", "EXPIRED").contains(state)) return p;
    Problem.require(
        !state.equals("CHECKED_IN"), "ALREADY_CHECKED_IN", "Cannot cancel after check-in");
    Problem.require(
        n.isBefore(Store.time(x, "start_at")), "CANCEL_CLOSED", "Session already started");
    if (state.equals("OFFERED"))
      Problem.require(
          n.isBefore(Store.time(p, "confirm_deadline")), "CONFIRM_EXPIRED", "Opportunity expired");
    change(p, "CANCELLED", "USER_CANCEL");
    if (!state.equals("WAITING")) {
      occupancy(session, -1);
      if (state.equals("RESERVED")) events.add("RESERVATION_CANCELLED", id, "cancelled:" + id);
      promote(x, n);
    }
    return s.one("select * from participation where id=?", id);
  }

  @Transactional
  public Map<String, Object> confirm(long id) {
    long session = s.number("select session_id from participation where id=?", id);
    var x = lock(session);
    var p = qualification(id);
    access.owner(Store.id(p, "user_id"));
    String st = (String) p.get("status");
    if (Set.of("RESERVED", "CHECKED_IN").contains(st)) return p;
    var n = s.now();
    Problem.require(
        st.equals("OFFERED") && open(x, n) && n.isBefore(Store.time(p, "confirm_deadline")),
        "CONFIRM_EXPIRED",
        "No valid confirmation opportunity");
    change(p, "RESERVED", "USER_CONFIRM");
    s.db.update(
        "update participation set reserved_at=?,checkin_token=? where id=?", n, token(), id);
    events.add("RESERVATION_CONFIRMED", id, "confirmed:" + id);
    return s.one("select * from participation where id=?", id);
  }

  void promote(Map<String, Object> x, LocalDateTime n) {
    long session = Store.id(x, "id");
    if (!open(x, n)) return;
    while (s.number("select capacity-occupied_count from activity_session where id=?", session)
        > 0) {
      var rows =
          s.db.queryForList(
              "select * from participation where session_id=? and status='WAITING' order by"
                  + " queue_seq limit 1 for update",
              session);
      if (rows.isEmpty()) return;
      var p = rows.get(0);
      long id = Store.id(p, "id");
      var end = n.plusMinutes(10);
      if (end.isAfter(Store.time(x, "registration_end_at")))
        end = Store.time(x, "registration_end_at");
      if (end.isAfter(Store.time(x, "start_at"))) end = Store.time(x, "start_at");
      change(p, "OFFERED", "PROMOTED");
      s.db.update(
          "update participation set offered_at=?,confirm_deadline=? where id=?", n, end, id);
      occupancy(session, 1);
      events.add("WAITLIST_OFFERED", id, "offered:" + id);
    }
  }

  @Transactional
  public void expire(long id) {
    long session = s.number("select session_id from participation where id=?", id);
    var x = lock(session);
    var p = qualification(id);
    var n = s.now();
    String st = (String) p.get("status");
    if (st.equals("OFFERED") && !n.isBefore(Store.time(p, "confirm_deadline"))) {
      change(p, "EXPIRED", "CONFIRM_TIMEOUT");
      occupancy(session, -1);
      promote(x, n);
    } else if (st.equals("WAITING") && !n.isBefore(Store.time(x, "registration_end_at")))
      change(p, "EXPIRED", "REGISTRATION_CLOSED");
  }

  @Transactional
  public void cancelSession(long id) {
    access.session(id);
    cancelSessionInternal(id);
  }

  public void cancelSessionInternal(long id) {
    var x = lock(id);
    if (x.get("status").equals("CANCELLED")) return;
    Problem.require(
        s.now().isBefore(Store.time(x, "start_at")),
        "SESSION_STARTED",
        "Cannot cancel a started session");
    var ps =
        s.db.queryForList(
            "select * from participation where session_id=? and active_guard=1 for update", id);
    for (var p : ps) {
      change(p, "CANCELLED", "SESSION_CANCEL");
      events.add("SESSION_CANCELLED", Store.id(p, "id"), "session-cancel:" + Store.id(p, "id"));
    }
    s.db.update("update activity_session set status='CANCELLED',occupied_count=0 where id=?", id);
    cache.invalidate(id, 0);
  }
}
