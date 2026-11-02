package cn.culturehub;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Store {
  public final JdbcTemplate db;

  public Store(JdbcTemplate db) {
    this.db = db;
  }

  public Map<String, Object> one(String sql, Object... args) {
    var l = db.queryForList(sql, args);
    if (l.isEmpty()) throw new Problem("NOT_FOUND", "Record not found", 404);
    return l.get(0);
  }

  public long number(String sql, Object... args) {
    try {
      Long result = db.queryForObject(sql, Long.class, args);
      if (result == null) throw new Problem("NOT_FOUND", "未找到记录", 404);
      return result;
    } catch (org.springframework.dao.EmptyResultDataAccessException e) {
      throw new Problem("NOT_FOUND", "未找到记录", 404);
    }
  }

  public long insert(String sql, Object... args) {
    var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
    db.update(
        c -> {
          var s = c.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
          for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
          return s;
        },
        keys);
    return keys.getKey().longValue();
  }

  public static long id(Map<String, Object> m, String k) {
    Object value = m == null ? null : m.get(k);
    if (!(value instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() != n.longValue())
      throw new Problem("VALIDATION_FAILED", "Numeric field required: " + k, 400);
    return n.longValue();
  }

  public java.time.LocalDateTime now() {
    return db.queryForObject("select current_timestamp(6)", java.time.LocalDateTime.class);
  }

  public static java.time.LocalDateTime time(Map<String, Object> m, String k) {
    var x = m.get(k);
    return x instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (java.time.LocalDateTime) x;
  }
}
