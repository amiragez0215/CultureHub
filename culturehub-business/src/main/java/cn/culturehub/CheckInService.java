package cn.culturehub;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckInService {
  final Store s;
  final Access a;
  final ParticipationService p;
  final Cache c;

  public CheckInService(Store s, Access a, ParticipationService p, Cache c) {
    this.s = s;
    this.a = a;
    this.p = p;
    this.c = c;
  }

  public Object status(long id) {
    var q = s.one("select user_id from participation where id=?", id);
    a.owner(Store.id(q, "user_id"));
    return c.get(
        "checkin:" + id,
        () ->
            s.one(
                "select p.status,c.checked_at from participation p left join checkin_record c on"
                    + " c.participation_id=p.id where p.id=?",
                id));
  }

  public Map<String, Object> credential(long id) {
    var x =
        s.one(
            "select id,user_id,status,checkin_token,session_id from participation where id=?", id);
    a.owner(Store.id(x, "user_id"));
    return x;
  }

  @Transactional
  public Map<String, Object> check(long session, String token) {
    a.session(session);
    var x = p.lock(session);
    var q =
        s.one(
            "select * from participation where session_id=? and checkin_token=? for update",
            session,
            token);
    Problem.require(
        x.get("status").equals("PUBLISHED"), "SESSION_CANCELLED", "Session unavailable");
    long id = Store.id(q, "id");
    if (q.get("status").equals("CHECKED_IN"))
      return s.one("select * from checkin_record where participation_id=?", id);
    var n = s.now();
    Problem.require(
        q.get("status").equals("RESERVED"), "INVALID_QUALIFICATION", "Not a valid reservation");
    Problem.require(
        !n.isBefore(Store.time(x, "checkin_open_at"))
            && n.isBefore(Store.time(x, "checkin_close_at")),
        "CHECKIN_WINDOW_CLOSED",
        "Outside check-in window");
    p.change(q, "CHECKED_IN", "CHECKIN");
    s.db.update(
        "insert into checkin_record(participation_id,operator_id,checked_at) values(?,?,?)",
        id,
        a.user(),
        n);
    return s.one("select * from checkin_record where participation_id=?", id);
  }
}
