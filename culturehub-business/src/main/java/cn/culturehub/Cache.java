package cn.culturehub;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class Cache {
  final StringRedisTemplate r;
  final ObjectMapper j;

  public Cache(StringRedisTemplate r, ObjectMapper j) {
    this.r = r;
    this.j = j;
  }

  public void limit(long user, long session) {
    try {
      String lua =
          "local a=redis.call('INCR',KEYS[1]); if a==1 then redis.call('EXPIRE',KEYS[1],2) end;"
              + " local b=redis.call('INCR',KEYS[2]); if b==1 then redis.call('EXPIRE',KEYS[2],1)"
              + " end; if a>5 or b>100 then return 0 end; return 1";
      var n =
          r.execute(
              new DefaultRedisScript<Long>(lua, Long.class),
              List.of("rate:user:" + user + ":" + session, "rate:session:" + session));
      if (n == null || n == 0) throw new Problem("RATE_LIMITED", "Too many requests", 429);
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem("RATE_LIMIT_UNAVAILABLE", "New registrations temporarily unavailable", 503);
    }
  }

  public void invalidate(long session, long participation) {
    evictAfterCommit(
        List.of("stats:" + session, "checkin:" + participation, "display:session:" + session));
  }

  public void invalidateActivity(long activity) {
    evictAfterCommit(List.of("display:activity:" + activity + ":sessions"));
  }

  void evictAfterCommit(Collection<String> keys) {
    Runnable f =
        () -> {
          try {
            r.delete(keys);
          } catch (Exception ignored) {
          }
        };
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isSynchronizationActive())
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                public void afterCommit() {
                  f.run();
                }
              });
    else f.run();
  }

  public String displayIndex(String key) {
    try {
      return r.opsForValue().get(key);
    } catch (Exception unavailable) {
      return null;
    }
  }

  /** A single MGET preserves input order, including nulls; failure is an all-miss. */
  public List<String> displayMany(List<String> keys) {
    if (keys.isEmpty()) return List.of();
    try {
      var values = r.opsForValue().multiGet(keys);
      if (values != null && values.size() == keys.size()) return values;
    } catch (Exception ignored) {
    }
    return new ArrayList<>(Collections.nCopies(keys.size(), null));
  }

  /**
   * One pipelined write with expiry on every key, never MSET followed by non-atomic expiry loops.
   */
  public void displayPut(Map<String, String> values) {
    if (values.isEmpty()) return;
    try {
      r.executePipelined(
          (org.springframework.data.redis.core.RedisCallback<Object>)
              connection -> {
                values.forEach(
                    (key, value) ->
                        connection
                            .stringCommands()
                            .setEx(
                                key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                15,
                                value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return null;
              });
    } catch (Exception ignored) {
    }
  }

  public Object get(String key, Supplier<Object> load) {
    try {
      String v = r.opsForValue().get(key);
      if (v != null) return j.readValue(v, Object.class);
    } catch (Exception ignored) {
    }
    Object v = load.get();
    try {
      r.opsForValue().set(key, j.writeValueAsString(v), Duration.ofSeconds(15));
    } catch (Exception ignored) {
    }
    return v;
  }
}
