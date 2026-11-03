package cn.culturehub;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Management {

  final Store s;

  final Access a;

  final ParticipationService p;

  final VenueMapper venues;
  final Cache cache;
  final CatalogQueryMapper queries;

  public Management(
      Store s,
      Access a,
      ParticipationService p,
      VenueMapper venues,
      Cache cache,
      CatalogQueryMapper queries) {

    this.s = s;

    this.a = a;

    this.p = p;

    this.venues = venues;
    this.cache = cache;
    this.queries = queries;
  }

  public Object venueEntities() {

    return venues.selectList(
        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<VenueMapper.Venue>()
            .orderByAsc("id"));
  }

  public List<Map<String, Object>> venues() {

    return s.db.queryForList("select * from venue order by id");
  }

  public List<Map<String, Object>> mine() {

    return a.platform()
        ? venues()
        : s.db.queryForList(
            "select v.* from venue v join venue_grant g on v.id=g.venue_id where g.user_id=?",
            a.user());
  }

  @Transactional
  public long venue(Map<String, Object> m) {

    a.platformOnly();

    String name = text(m, "name", 120);
    String category =
        Inputs.code(
            m,
            "category",
            "CULTURE_CENTER",
            Set.of(
                "MUSEUM",
                "HERITAGE_HALL",
                "ART_GALLERY",
                "READING_ROOM",
                "CULTURE_CENTER",
                "FOLK_HALL",
                "SCIENCE_CENTER",
                "LIBRARY"));
    String venueStatus = Inputs.code(m, "status", "OPEN", Set.of("OPEN", "CLOSED"));

    long id = Inputs.optionalId(m);

    var entity = new VenueMapper.Venue();
    entity.name = name;
    entity.category = category;
    entity.status = venueStatus;
    entity.address = optionalText(m, "address", 500);
    entity.openingHours = optionalText(m, "opening_hours", 300);
    entity.introduction = m.get("introduction") == null ? null : m.get("introduction").toString();
    if (entity.introduction != null && entity.introduction.length() > 10000)
      throw new Problem("VALIDATION_FAILED", "场馆介绍最多10000字", 400);
    if (id == 0) {
      venues.insert(entity);
      return entity.id;
    }
    if (venues.selectById(id) == null) throw new Problem("NOT_FOUND", "场馆不存在", 404);
    entity.id = id;
    venues.updateById(entity);
    return id;
  }

  static String optionalText(Map<String, Object> values, String key, int max) {
    Object raw = values.get(key);
    String value = raw == null ? "" : raw.toString();
    if (value.length() > max) throw new Problem("VALIDATION_FAILED", key + "内容过长", 400);
    return value;
  }

  @Transactional
  public void grant(long user, List<Long> venues) {

    a.platformOnly();

    s.one("select id from app_user where id=?", user);

    s.db.update("insert ignore into user_role(user_id,role) values(?,'VENUE_ADMIN')", user);

    s.db.update("delete from venue_grant where user_id=?", user);

    if (venues == null || venues.stream().anyMatch(Objects::isNull))
      throw new Problem("VALIDATION_FAILED", "场馆编号不能为空", 400);

    venues = venues.stream().distinct().toList();

    for (long v : venues) s.one("select id from venue where id=?", v);

    for (long v : venues)
      s.db.update("insert into venue_grant(user_id,venue_id) values(?,?)", user, v);
  }

  public static String text(Map<String, Object> m, String key, int max) {

    return Inputs.text(m, key, max);
  }

  @Transactional
  public long activity(Map<String, Object> m) {

    long venue = Store.id(m, "venue_id");

    a.venue(venue);

    long id = Inputs.optionalId(m);

    String title = text(m, "title", 120);

    String category =
        Inputs.code(
            m,
            "category",
            "CULTURE",
            Set.of("CULTURE", "HERITAGE", "LECTURE", "ART", "FAMILY", "TRADITION"));

    String audience =
        Inputs.code(
            m, "audience", "ALL", Set.of("ALL", "FAMILY", "CHILDREN", "YOUTH", "ADULT", "SENIOR"));

    if (id == 0)
      return s.insert(
          "insert into"
              + " activity(venue_id,title,description,category,audience,plan,notice,copywriting,created_by)"
              + " values(?,?,?,?,?,?,?,?,?)",
          venue,
          title,
          m.getOrDefault("description", ""),
          category,
          audience,
          m.getOrDefault("plan", ""),
          m.getOrDefault("notice", ""),
          m.getOrDefault("copywriting", ""),
          a.user());

    var old = s.one("select * from activity where id=? for update", id);

    a.venue(Store.id(old, "venue_id"));

    Problem.require(
        Store.id(old, "venue_id") == venue && !old.get("status").equals("CANCELLED"),
        "ACTIVITY_IMMUTABLE",
        "Venue/status cannot change");

    s.db.update(
        "update activity set"
            + " title=?,description=?,category=?,audience=?,plan=?,notice=?,copywriting=? where"
            + " id=?",
        title,
        m.getOrDefault("description", ""),
        category,
        audience,
        m.getOrDefault("plan", ""),
        m.getOrDefault("notice", ""),
        m.getOrDefault("copywriting", ""),
        id);

    return id;
  }

  @Transactional
  public long session(Map<String, Object> m) {

    long activity = Store.id(m, "activity_id");

    var ac = s.one("select * from activity where id=? for update", activity);

    a.venue(Store.id(ac, "venue_id"));

    Problem.require(
        !ac.get("status").equals("CANCELLED"), "ACTIVITY_CANCELLED", "Activity cancelled");

    var rs = Inputs.time(m, "registration_start_at");

    var re = Inputs.time(m, "registration_end_at");

    var st = Inputs.time(m, "start_at");

    var en = Inputs.time(m, "end_at");

    var co = Inputs.time(m, "checkin_open_at");

    var cc = Inputs.time(m, "checkin_close_at");

    long cap = Inputs.number(m, "capacity");

    if (!(rs.isBefore(re)
        && !re.isAfter(st)
        && st.isBefore(en)
        && co.isBefore(cc)
        && !cc.isAfter(en)
        && cap > 0
        && cap <= Integer.MAX_VALUE))
      throw new Problem("INVALID_WINDOW", "请检查报名、活动和签到时间，以及预约人数上限", 400);

    long id = Inputs.optionalId(m);

    if (id == 0) {
      long created =
          s.insert(
              "insert into"
                  + " activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity)"
                  + " values(?,?,?,?,?,?,?,?)",
              activity,
              rs,
              re,
              st,
              en,
              co,
              cc,
              cap);
      cache.invalidateActivity(activity);
      return created;
    }

    var x = p.lock(id);

    Problem.require(
        Store.id(x, "activity_id") == activity
            && s.number("select count(*) from participation where session_id=?", id) == 0
            && !x.get("status").equals("CANCELLED"),
        "SESSION_IMMUTABLE",
        "Session has participation history or is cancelled");

    s.db.update(
        "update activity_session set"
            + " registration_start_at=?,registration_end_at=?,start_at=?,end_at=?,checkin_open_at=?,checkin_close_at=?,capacity=?"
            + " where id=?",
        rs,
        re,
        st,
        en,
        co,
        cc,
        cap,
        id);

    cache.invalidate(id, 0);
    cache.invalidateActivity(activity);
    return id;
  }

  @Transactional
  public void publish(long id) {

    var x = s.one("select * from activity where id=? for update", id);

    a.venue(Store.id(x, "venue_id"));

    Problem.require(
        !x.get("status").equals("CANCELLED"), "ACTIVITY_CANCELLED", "Activity cancelled");

    Problem.require(
        s.number("select count(*) from venue where id=? and status='OPEN'", x.get("venue_id")) == 1,
        "VENUE_CLOSED",
        "Venue closed");

    var xs =
        s.db.queryForList(
            "select * from activity_session where activity_id=? order by id for update", id);

    Problem.require(
        xs.stream()
            .anyMatch(
                v ->
                    !v.get("status").equals("CANCELLED")
                        && s.now().isBefore(Store.time(v, "start_at"))),
        "NO_SESSION",
        "No upcoming session");

    s.db.update("update activity set status='PUBLISHED' where id=?", id);

    s.db.update(
        "update activity_session set status='PUBLISHED' where activity_id=? and status='DRAFT' and"
            + " start_at>current_timestamp",
        id);
    cache.invalidateActivity(id);
    for (var session : xs) cache.invalidate(Store.id(session, "id"), 0);
  }

  @Transactional
  public void cancel(long id) {

    var x = s.one("select * from activity where id=? for update", id);

    a.venue(Store.id(x, "venue_id"));

    if (x.get("status").equals("CANCELLED")) return;

    var xs =
        s.db.queryForList(
            "select id from activity_session where activity_id=? order by id for update", id);

    for (var row : xs) p.cancelSessionInternal(Store.id(row, "id"));

    s.db.update("update activity set status='CANCELLED' where id=?", id);
    cache.invalidateActivity(id);
  }

  public List<Map<String, Object>> activities(boolean admin, String q, String category, int page) {
    return activities(admin, q, category, page, 0);
  }

  private CatalogQueryMapper.Filter filter(
      boolean admin, String q, String category, long venue, String status, String from, String to) {
    if (venue < 0 || !Set.of("ACTIVE", "ALL", "DRAFT", "PUBLISHED", "CANCELLED").contains(status))
      throw new Problem("VALIDATION_FAILED", "Invalid activity filter", 400);
    var begin = from == null ? null : java.time.LocalDate.parse(from).atStartOfDay();
    var end = to == null ? null : java.time.LocalDate.parse(to).atStartOfDay();
    if (begin != null && end != null && !begin.isBefore(end))
      throw new Problem("VALIDATION_FAILED", "from must precede to (exclusive)", 400);
    return new CatalogQueryMapper.Filter(
        admin && !a.platform(),
        admin ? a.user() : 0,
        q,
        category,
        venue,
        admin ? status : "PUBLISHED",
        begin,
        end);
  }

  public long activityCount(boolean admin, String q, String category, long venue) {
    return activityCount(admin, q, category, venue, "ACTIVE");
  }

  public long activityCount(boolean admin, String q, String category, long venue, String status) {
    return activityCount(admin, q, category, venue, status, null, null);
  }

  public long activityCount(
      boolean admin, String q, String category, long venue, String status, String from, String to) {
    return queries.countActivities(filter(admin, q, category, venue, status, from, to));
  }

  public List<Map<String, Object>> activities(
      boolean admin, String q, String category, int page, long venue) {
    return activities(admin, q, category, page, venue, "ACTIVE");
  }

  public List<Map<String, Object>> activities(
      boolean admin, String q, String category, int page, long venue, String status) {
    return activities(admin, q, category, page, venue, status, null, null);
  }

  public List<Map<String, Object>> activities(
      boolean admin,
      String q,
      String category,
      int page,
      long venue,
      String status,
      String from,
      String to) {
    return queries.activities(
        filter(admin, q, category, venue, status, from, to), Inputs.offset(page));
  }
}
