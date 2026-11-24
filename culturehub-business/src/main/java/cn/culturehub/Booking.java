package cn.culturehub;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class Booking {
  final ParticipationService p;
  final Access a;
  final Cache c;
  private AsyncBooking async;

  public Booking(ParticipationService p, Access a, Cache c) {
    this.p = p;
    this.a = a;
    this.c = c;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void useAsyncBooking(AsyncBooking async) {
    this.async = async;
  }

  public Map<String, Object> create(long id, String request, boolean wait) {
    return create(id, request, wait, null);
  }

  public Map<String, Object> create(long id, String request, boolean wait, String trace) {
    long u = a.user();
    Problem.require(
        request != null && request.matches("[A-Za-z0-9_-]{8,80}"),
        "INVALID_REQUEST_ID",
        "request_id required");
    // A committed replay is a read: it must remain recoverable even if Redis is down/limited.
    var previous = p.findExisting(id, u, request, wait);
    if (previous != null) return previous;
    if (async != null) {
      Problem.require(!request.startsWith("v3-"), "INVALID_REQUEST_ID", "Reserved request identifier");
      return async.submit(request, u, id, wait ? BookingLedger.Operation.JOIN : BookingLedger.Operation.DIRECT,
          null, trace);
    }
    c.limit(u, id);
    return p.createTx(id, u, request, wait);
  }

  public Map<String, Object> result(long session, String request, boolean wait) {
    return p.findExisting(session, a.user(), request, wait);
  }

  public Map<String, Object> confirm(long id, String trace) {
    if (async == null) return p.confirm(id);
    long user = a.user();
    var row = p.qualificationOwner(id);
    a.owner(Store.id(row, "user_id"));
    return async.submit("v3-confirm-" + id, user, Store.id(row, "session_id"),
        BookingLedger.Operation.CONFIRM, id, trace);
  }

  public Map<String, Object> status(String request) {
    if (async == null) throw new Problem("NOT_FOUND", "Booking request not found", 404);
    return async.status(request, a.user());
  }

  public String rejection(String request) {
    return async == null ? null : async.rejection(request, a.user());
  }

  public Map<String, Object> reconcile(long session) {
    a.session(session);
    if (async == null) throw new Problem("NOT_FOUND", "Async booking not enabled", 404);
    var result = async.rebuild(session);
    return Map.of("sessionId", session, "beforeAvailable", result.before(), "afterAvailable", result.after());
  }
}
