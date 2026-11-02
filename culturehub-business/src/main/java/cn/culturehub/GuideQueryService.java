package cn.culturehub;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Read-only, bounded SQL. Owner and hard constraints come from Java, never tool arguments. */
@Service
public class GuideQueryService {
  final Store s;

  public GuideQueryService(Store s) {
    this.s = s;
  }

  public List<Map<String, Object>> search(AiGuideGraph.Preferences p, long user) {
    LocalDateTime now = s.now();
    LocalDate from = p.fromDate() == null ? now.toLocalDate() : LocalDate.parse(p.fromDate());
    LocalDate to = p.toDate() == null ? from.plusDays(30) : LocalDate.parse(p.toDate());
    if (to.isBefore(from) || to.isAfter(from.plusDays(90)))
      throw new Problem("INVALID_PREFERENCES", "日期范围必须在 90 天以内", 400);
    StringBuilder sql =
        new StringBuilder(
            "select x.id sessionId,a.id activityId,a.title,a.category,a.audience,a.description,v.id"
                + " venueId,v.name venueName,v.address,x.start_at startAt,x.end_at"
                + " endAt,x.registration_end_at registrationEndAt,x.capacity-x.occupied_count"
                + " remaining,exists(select 1 from participation w where w.session_id=x.id and"
                + " w.status='WAITING') waitlistPriority,current_timestamp(6) asOf from"
                + " activity_session x join activity a on a.id=x.activity_id join venue v on"
                + " v.id=a.venue_id where a.status='PUBLISHED' and x.status='PUBLISHED' and"
                + " v.status='OPEN' and x.registration_start_at<=current_timestamp(6) and"
                + " x.registration_end_at>current_timestamp(6) and x.start_at>current_timestamp(6)"
                + " and x.start_at>=? and x.start_at<? and not exists(select 1 from participation"
                + " own where own.session_id=x.id and own.user_id=? and own.active_guard=1)");
    List<Object> args =
        new ArrayList<>(List.of(from.atStartOfDay(), to.plusDays(1).atStartOfDay(), user));
    if (p.minimumRemaining() != null) {
      if (p.minimumRemaining() < 1) throw new Problem("INVALID_PREFERENCES", "最少剩余名额必须为正整数", 400);
      sql.append(" and x.capacity-x.occupied_count>=?");
      args.add(p.minimumRemaining());
    }
    if (p.fromTime() != null) {
      sql.append(" and time(x.start_at)>=?");
      args.add(p.fromTime());
    }
    if (p.toTime() != null) {
      sql.append(" and time(x.start_at)<?");
      args.add(p.toTime());
    }
    if (p.category() != null) {
      sql.append(" and a.category=?");
      args.add(p.category());
    }
    if (p.audience() != null) {
      sql.append(" and (a.audience='ALL' or a.audience=?)");
      args.add(p.audience());
    }
    if (p.keyword() != null) {
      sql.append(" and (a.title like ? or a.description like ?)");
      args.add("%" + p.keyword() + "%");
      args.add("%" + p.keyword() + "%");
    }
    if (p.venue() != null) {
      sql.append(" and v.name like ?");
      args.add("%" + p.venue() + "%");
    }
    sql.append(" order by x.start_at,x.id limit 20");
    return s.db.queryForList(sql.toString(), args.toArray()).stream().map(this::card).toList();
  }

  public Map<String, Object> current(long session) {
    var raw =
        s.one(
            "select x.id sessionId,a.id activityId,a.title,a.category,a.audience,a.description,v.id"
                + " venueId,v.name venueName,v.address,x.start_at startAt,x.end_at"
                + " endAt,x.registration_start_at registrationStartAt,x.registration_end_at"
                + " registrationEndAt,x.capacity-x.occupied_count remaining,x.status"
                + " sessionStatus,a.status activityStatus,v.status venueStatus,exists(select 1 from"
                + " participation w where w.session_id=x.id and w.status='WAITING')"
                + " waitlistPriority,current_timestamp(6) asOf from activity_session x join"
                + " activity a on a.id=x.activity_id join venue v on v.id=a.venue_id where x.id=?",
            session);
    return card(raw);
  }

  public List<Map<String, Object>> venues(String keyword) {
    if (keyword != null && keyword.length() > 120)
      throw new Problem("VALIDATION_FAILED", "场馆关键词过长", 400);
    return s.db.queryForList(
        "select id,name,introduction,opening_hours,status from venue where status='OPEN' and name"
            + " like ? order by id limit 20",
        "%" + (keyword == null ? "" : keyword) + "%");
  }

  public boolean open(Map<String, Object> row) {
    LocalDateTime now = s.now();
    return "PUBLISHED".equals(row.get("sessionStatus"))
        && "PUBLISHED".equals(row.get("activityStatus"))
        && "OPEN".equals(row.get("venueStatus"))
        && !now.isBefore(time(row, "registrationStartAt"))
        && now.isBefore(time(row, "registrationEndAt"))
        && now.isBefore(time(row, "startAt"));
  }

  public boolean matches(Map<String, Object> row, AiGuideGraph.Preferences p) {
    LocalDateTime start = time(row, "startAt");
    return (p.minimumRemaining() == null
            || ((Number) row.get("remaining")).longValue() >= p.minimumRemaining())
        && (p.fromDate() == null || !start.toLocalDate().isBefore(LocalDate.parse(p.fromDate())))
        && (p.toDate() == null || !start.toLocalDate().isAfter(LocalDate.parse(p.toDate())))
        && (p.fromTime() == null || !start.toLocalTime().isBefore(LocalTime.parse(p.fromTime())))
        && (p.toTime() == null || start.toLocalTime().isBefore(LocalTime.parse(p.toTime())))
        && (p.category() == null || p.category().equals(row.get("category")))
        && (p.audience() == null
            || "ALL".equals(row.get("audience"))
            || p.audience().equals(row.get("audience")))
        && (p.venue() == null || String.valueOf(row.get("venueName")).contains(p.venue()))
        && (p.keyword() == null
            || (row.get("title") + " " + row.get("description")).contains(p.keyword()));
  }

  Map<String, Object> card(Map<String, Object> raw) {
    var row = new LinkedHashMap<>(raw);
    boolean priority = ((Number) row.get("waitlistPriority")).intValue() != 0;
    row.put("bookingAllowed", ((Number) row.get("remaining")).longValue() > 0 && !priority);
    row.put("waitlistAllowed", ((Number) row.get("remaining")).longValue() <= 0);
    row.put("ageNotice", "人群分类仅供参考，未做精确年龄资格认证；亲子主题仍是当前账号一个名额。");
    row.replaceAll(
        (key, value) ->
            value instanceof java.sql.Timestamp t
                ? t.toLocalDateTime().toString()
                : value instanceof LocalDateTime t ? t.toString() : value);
    return row;
  }

  public static LocalDateTime time(Map<String, Object> row, String key) {
    Object value = row.get(key);
    if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
    if (value instanceof LocalDateTime t) return t;
    return LocalDateTime.parse(value.toString());
  }
}
