package cn.culturehub;

import java.util.*;

/** Original SQL retained as a result/count oracle, never used by production. */
class LegacyFacts {
  final Store s;

  LegacyFacts(Store s) {
    this.s = s;
  }

  public Map<String, Object> facts(long session) {
    var x = s.one("select * from activity_session where id=?", session);
    var r = new LinkedHashMap<String, Object>();
    r.put("sessionId", session);
    r.put("asOf", s.now());
    r.put("status", x.get("status"));
    long reserved =
        s.number(
            "select count(*) from participation where session_id=? and status in"
                + " ('RESERVED','CHECKED_IN')",
            session);
    long checked =
        s.number(
            "select count(*) from participation where session_id=? and status='CHECKED_IN'",
            session);
    r.put("reserved", reserved);
    r.put("checkedIn", checked);
    r.put("occupied", x.get("occupied_count"));
    for (String st : List.of("WAITING", "OFFERED", "EXPIRED"))
      r.put(
          st.toLowerCase(),
          s.number(
              "select count(*) from participation where session_id=? and status=?", session, st));
    r.put(
        "successCount",
        s.number(
            "select count(*) from participation_history h join participation p on"
                + " p.id=h.participation_id where p.session_id=? and h.to_status='RESERVED'",
            session));
    r.put(
        "cancelCount",
        s.number(
            "select count(*) from participation_history h join participation p on"
                + " p.id=h.participation_id where p.session_id=? and h.from_status='RESERVED' and"
                + " h.to_status='CANCELLED' and h.reason='USER_CANCEL'",
            session));
    r.put(
        "waitlistJoined",
        s.number(
            "select count(*) from participation where session_id=? and entry_type='WAITLIST'",
            session));
    r.put(
        "waitlistConverted",
        s.number(
            "select count(*) from participation where session_id=? and entry_type='WAITLIST' and"
                + " reserved_at is not null",
            session));
    r.put(
        "confirmTimeout",
        s.number(
            "select count(*) from participation where session_id=? and"
                + " end_reason='CONFIRM_TIMEOUT'",
            session));
    boolean finished = !s.now().isBefore(Store.time(x, "end_at"));
    r.put("finished", finished);
    r.put(
        "notCheckedIn",
        finished && !x.get("status").equals("CANCELLED") ? reserved - checked : null);
    r.put(
        "checkinProgress",
        !x.get("status").equals("CANCELLED") && reserved > 0 ? (double) checked / reserved : null);
    r.put(
        "checkinRate",
        finished && !x.get("status").equals("CANCELLED") && reserved > 0
            ? (double) checked / reserved
            : null);
    r.put(
        "reviews",
        s.one("select count(*) count,avg(rating) average from review where session_id=?", session));
    return r;
  }
}
