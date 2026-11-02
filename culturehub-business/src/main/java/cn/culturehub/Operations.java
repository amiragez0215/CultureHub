package cn.culturehub;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Operations {
  final Store s;
  final Access a;
  final ParticipationService p;
  final Events e;
  final Cache c;

  public Operations(Store s, Access a, ParticipationService p, Events e, Cache c) {
    this.s = s;
    this.a = a;
    this.p = p;
    this.e = e;
    this.c = c;
  }

  @Transactional
  public void review(long session, int rating, String content) {
    long u = a.user();
    var x = p.lock(session);
    Problem.require(
        !x.get("status").equals("CANCELLED") && !s.now().isBefore(Store.time(x, "end_at")),
        "REVIEW_NOT_OPEN",
        "Session not completed");
    Problem.require(
        rating >= 1 && rating <= 5 && content != null && content.length() <= 2000,
        "INVALID_REVIEW",
        "Rating 1-5 and content <=2000 required");
    var q =
        s.one(
            "select p.id from participation p join checkin_record c on c.participation_id=p.id"
                + " where p.user_id=? and p.session_id=? and p.status='CHECKED_IN'",
            u,
            session);
    s.db.update(
        "insert into review(user_id,session_id,participation_id,rating,content) values(?,?,?,?,?)"
            + " on duplicate key update"
            + " rating=values(rating),content=values(content),updated_at=current_timestamp",
        u,
        session,
        q.get("id"),
        rating,
        content);
    c.invalidate(session, 0);
  }

  @Transactional(readOnly = true)
  public Object stats(long session) {
    a.session(session);
    return c.get("stats:" + session, () -> facts(session));
  }

  @Transactional(readOnly = true)
  public Object dashboard(long venue, String from, String to) {
    a.venue(venue);
    java.time.LocalDate begin = java.time.LocalDate.parse(from),
        end = java.time.LocalDate.parse(to);
    Problem.require(begin.isBefore(end), "INVALID_RANGE", "from must precede to");
    var selected =
        s.db.queryForList(
            "select x.id,x.activity_id from activity_session x join activity a on"
                + " a.id=x.activity_id where a.venue_id=? and x.start_at>=? and x.start_at<? order"
                + " by x.id limit 201",
            venue,
            from,
            to);
    Problem.require(
        selected.size() <= 200,
        "REPORT_RANGE_TOO_WIDE",
        "Choose a narrower range with at most 200 sessions");
    var rows = factsBatch(selected.stream().map(x -> Store.id(x, "id")).toList());
    var summary = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "reserved",
            "checkedIn",
            "occupied",
            "successCount",
            "cancelCount",
            "waitlistJoined",
            "waitlistConverted",
            "confirmTimeout"))
      summary.put(key, rows.stream().mapToLong(r -> ((Number) r.get(key)).longValue()).sum());
    long reserved = ((Number) summary.get("reserved")).longValue(),
        checked = ((Number) summary.get("checkedIn")).longValue();
    summary.put("checkinProgress", reserved == 0 ? null : (double) checked / reserved);
    long finalReserved =
        rows.stream()
            .filter(
                r -> Boolean.TRUE.equals(r.get("finished")) && !"CANCELLED".equals(r.get("status")))
            .mapToLong(r -> ((Number) r.get("reserved")).longValue())
            .sum();
    long finalChecked =
        rows.stream()
            .filter(
                r -> Boolean.TRUE.equals(r.get("finished")) && !"CANCELLED".equals(r.get("status")))
            .mapToLong(r -> ((Number) r.get("checkedIn")).longValue())
            .sum();
    summary.put("checkinRate", finalReserved == 0 ? null : (double) finalChecked / finalReserved);
    summary.put(
        "activityCount", selected.stream().map(x -> Store.id(x, "activity_id")).distinct().count());
    summary.put(
        "reviews",
        s.one(
            "select count(*) count,avg(r.rating) average from review r join activity_session x on"
                + " x.id=r.session_id join activity a on a.id=x.activity_id where a.venue_id=? and"
                + " x.start_at>=? and x.start_at<?",
            venue,
            from,
            to));
    summary.put("asOf", rows.isEmpty() ? s.now() : rows.get(0).get("asOf"));
    return Map.of("summary", summary, "sessions", rows, "limit", 200);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> facts(long session) {
    return factsBatch(List.of(session)).get(0);
  }

  /**
   * Four SQL statements for 1..200 sessions, no per-session COUNT loop. All facts remain in MySQL.
   */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> factsBatch(Collection<Long> requested) {
    var ids = requested.stream().distinct().toList();
    if (ids.isEmpty()) return List.of();
    if (ids.size() > 200) throw new Problem("REPORT_RANGE_TOO_WIDE", "最多查询200个场次", 400);
    String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    Object[] args = ids.toArray();
    var sessions =
        s.db.queryForList(
            "select id,status,end_at,occupied_count,current_timestamp(6) as_of from"
                + " activity_session where id in ("
                + marks
                + ")",
            args);
    if (sessions.size() != ids.size()) throw new Problem("NOT_FOUND", "部分场次不存在", 404);
    var participation =
        s.db.queryForList(
            "select session_id,sum(status in ('RESERVED','CHECKED_IN'))"
                + " reserved,sum(status='CHECKED_IN') checkedIn,sum(status='WAITING')"
                + " waiting,sum(status='OFFERED') offered,sum(status='EXPIRED')"
                + " expired,sum(entry_type='WAITLIST') waitlistJoined,sum(entry_type='WAITLIST' and"
                + " reserved_at is not null) waitlistConverted,sum(end_reason='CONFIRM_TIMEOUT')"
                + " confirmTimeout from participation where session_id in ("
                + marks
                + ") group by session_id",
            args);
    var history =
        s.db.queryForList(
            "select p.session_id,sum(h.to_status='RESERVED')"
                + " successCount,sum(h.from_status='RESERVED' and h.to_status='CANCELLED' and"
                + " h.reason='USER_CANCEL') cancelCount from participation p join"
                + " participation_history h on h.participation_id=p.id where p.session_id in ("
                + marks
                + ") group by p.session_id",
            args);
    var reviews =
        s.db.queryForList(
            "select session_id,count(*) count,avg(rating) average from review where session_id in ("
                + marks
                + ") group by session_id",
            args);
    var pBy = index(participation, "session_id");
    var hBy = index(history, "session_id");
    var rBy = index(reviews, "session_id");
    var xBy = index(sessions, "id");
    List<Map<String, Object>> result = new ArrayList<>();
    for (long id : ids) {
      var x = xBy.get(id);
      var counts = pBy.getOrDefault(id, Map.of());
      var events = hBy.getOrDefault(id, Map.of());
      var row = new LinkedHashMap<String, Object>();
      row.put("sessionId", id);
      row.put("asOf", x.get("as_of"));
      row.put("status", x.get("status"));
      row.put("occupied", x.get("occupied_count"));
      for (String key :
          List.of(
              "reserved",
              "checkedIn",
              "waiting",
              "offered",
              "expired",
              "waitlistJoined",
              "waitlistConverted",
              "confirmTimeout")) row.put(key, number(counts, key));
      row.put("successCount", number(events, "successCount"));
      row.put("cancelCount", number(events, "cancelCount"));
      long reserved = number(counts, "reserved"), checked = number(counts, "checkedIn");
      boolean finished = !Store.time(x, "as_of").isBefore(Store.time(x, "end_at")),
          cancelled = x.get("status").equals("CANCELLED");
      row.put("finished", finished);
      row.put("notCheckedIn", finished && !cancelled ? reserved - checked : null);
      row.put("checkinProgress", !cancelled && reserved > 0 ? (double) checked / reserved : null);
      row.put(
          "checkinRate",
          finished && !cancelled && reserved > 0 ? (double) checked / reserved : null);
      var review = new LinkedHashMap<String, Object>();
      var aggregate = rBy.getOrDefault(id, Map.of());
      review.put("count", number(aggregate, "count"));
      review.put("average", aggregate.get("average"));
      row.put("reviews", review);
      result.add(row);
    }
    return result;
  }

  static Map<Long, Map<String, Object>> index(List<Map<String, Object>> rows, String key) {
    Map<Long, Map<String, Object>> result = new HashMap<>();
    for (var row : rows) result.put(Store.id(row, key), row);
    return result;
  }

  static long number(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Number n ? n.longValue() : 0;
  }

  @Transactional
  public void remind(long session) {
    var x = p.lock(session);
    var n = s.now();
    if (!x.get("status").equals("PUBLISHED")
        || !n.isBefore(Store.time(x, "start_at"))
        || n.isBefore(Store.time(x, "start_at").minusMinutes(30))) return;
    for (var q :
        s.db.queryForList(
            "select id from participation where session_id=? and status in"
                + " ('RESERVED','CHECKED_IN')",
            session)) {
      long id = Store.id(q, "id");
      if (s.number("select count(*) from outbox where business_key=?", "reminder:" + id) == 0)
        e.add("SESSION_START_REMINDER", id, "reminder:" + id);
    }
  }
}
