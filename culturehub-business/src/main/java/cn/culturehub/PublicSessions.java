package cn.culturehub;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;

/** Short-lived public display cache. Never called by booking, proposals or permission checks. */
@Service
public class PublicSessions {
  final Store s;
  final Cache cache;
  static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() {};

  public PublicSessions(Store s, Cache cache) {
    this.s = s;
    this.cache = cache;
  }

  String index(long activity) {
    return "display:activity:" + activity + ":sessions";
  }

  public List<Map<String, Object>> list(long activity) {
    // Authoritative visibility check remains uncached: cancelling the parent takes effect
    // immediately.
    s.one("select id from activity where id=? and status='PUBLISHED'", activity);
    List<Long> ids = null;
    try {
      String value = cache.displayIndex(index(activity));
      if (value != null) ids = cache.j.readValue(value, new TypeReference<List<Long>>() {});
    } catch (Exception invalid) {
    }
    if (ids == null) return cold(activity);
    var keys = ids.stream().map(id -> "display:session:" + id).toList();
    var values = cache.displayMany(keys);
    Map<Long, Map<String, Object>> found = new HashMap<>();
    List<Long> missing = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      try {
        if (values.get(i) == null) throw new IllegalArgumentException();
        var row = cache.j.readValue(values.get(i), ROW);
        if (Store.id(row, "id") != ids.get(i) || Store.id(row, "activity_id") != activity)
          throw new IllegalArgumentException();
        found.put(ids.get(i), row);
      } catch (Exception miss) {
        missing.add(ids.get(i));
      }
    }
    if (!missing.isEmpty()) {
      String marks = String.join(",", Collections.nCopies(missing.size(), "?"));
      List<Object> args = new ArrayList<>(missing);
      args.add(activity);
      var rows =
          s.db.queryForList(
              "select *,current_timestamp(6) as_of from activity_session where id in ("
                  + marks
                  + ") and activity_id=? and status<>'DRAFT'",
              args.toArray());
      Map<String, String> fill = new LinkedHashMap<>();
      for (var raw : rows) {
        var row = normalize(raw);
        found.put(Store.id(row, "id"), row);
        fill.put("display:session:" + row.get("id"), json(row));
      }
      cache.displayPut(fill);
    }
    return ids.stream().filter(found::containsKey).map(found::get).toList();
  }

  List<Map<String, Object>> cold(long activity) {
    var rows =
        s
            .db
            .queryForList(
                "select *,current_timestamp(6) as_of from activity_session where activity_id=? and"
                    + " status<>'DRAFT' order by start_at,id",
                activity)
            .stream()
            .map(this::normalize)
            .toList();
    Map<String, String> fill = new LinkedHashMap<>();
    fill.put(index(activity), json(rows.stream().map(row -> Store.id(row, "id")).toList()));
    rows.forEach(row -> fill.put("display:session:" + row.get("id"), json(row)));
    cache.displayPut(fill);
    return rows;
  }

  Map<String, Object> normalize(Map<String, Object> raw) {
    var row = new LinkedHashMap<>(raw);
    row.replaceAll(
        (key, value) ->
            value instanceof java.sql.Timestamp t
                ? t.toLocalDateTime().toString()
                : value instanceof LocalDateTime t ? t.toString() : value);
    return row;
  }

  String json(Object value) {
    try {
      return cache.j.writeValueAsString(value);
    } catch (Exception invalid) {
      throw new IllegalStateException("Display serialization failed", invalid);
    }
  }
}
