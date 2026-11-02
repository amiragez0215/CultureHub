package cn.culturehub;

import java.time.*;
import java.util.*;

/** Request validation shared by controllers and transactional services. */
public final class Inputs {
  private Inputs() {}

  public static String text(Map<String, ?> m, String key, int max) {
    Object x = m == null ? null : m.get(key);
    if (!(x instanceof String v) || v.isBlank() || v.length() > max)
      throw new Problem("VALIDATION_FAILED", "请检查字段：" + key, 400);
    return v.trim();
  }

  public static long number(Map<String, ?> m, String key) {
    Object x = m == null ? null : m.get(key);
    if (!(x instanceof Number n)
        || !Double.isFinite(n.doubleValue())
        || n.doubleValue() != n.longValue()
        || n.longValue() <= 0) throw new Problem("VALIDATION_FAILED", "必须提供正整数：" + key, 400);
    return n.longValue();
  }

  public static long optionalId(Map<String, ?> m) {
    return m.containsKey("id") ? number(m, "id") : 0;
  }

  public static LocalDateTime time(Map<String, ?> m, String key) {
    try {
      return LocalDateTime.parse(text(m, key, 40));
    } catch (java.time.format.DateTimeParseException e) {
      throw new Problem("VALIDATION_FAILED", "时间格式不正确：" + key, 400);
    }
  }

  public static String code(Map<String, ?> m, String key, String fallback, Set<String> allowed) {
    String v = m.containsKey(key) ? text(m, key, 50) : fallback;
    if (!allowed.contains(v)) throw new Problem("VALIDATION_FAILED", "选项编码不正确：" + key, 400);
    return v;
  }

  public static int offset(int page) {
    if (page < 1 || page > 10000) throw new Problem("VALIDATION_FAILED", "页码超出范围", 400);
    return (page - 1) * 20;
  }
}
