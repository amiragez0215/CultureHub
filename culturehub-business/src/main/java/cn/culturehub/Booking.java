package cn.culturehub;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class Booking {
  final ParticipationService p;
  final Access a;
  final Cache c;

  public Booking(ParticipationService p, Access a, Cache c) {
    this.p = p;
    this.a = a;
    this.c = c;
  }

  public Map<String, Object> create(long id, String request, boolean wait) {
    long u = a.user();
    Problem.require(
        request != null && request.matches("[A-Za-z0-9_-]{8,80}"),
        "INVALID_REQUEST_ID",
        "request_id required");
    // A committed replay is a read: it must remain recoverable even if Redis is down/limited.
    var previous = p.findExisting(id, u, request, wait);
    if (previous != null) return previous;
    c.limit(u, id);
    return p.createTx(id, u, request, wait);
  }

  public Map<String, Object> result(long session, String request, boolean wait) {
    return p.findExisting(session, a.user(), request, wait);
  }
}
