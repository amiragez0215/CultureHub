package cn.culturehub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("v3-p2")
class BookingRedis {
  record Receipt(String stream, String request, String epoch, String trace) {}
  record Inventory(long before, long after) {}

  private static final String STATE = script("state");
  private static final DefaultRedisScript<String> RESERVE = new DefaultRedisScript<>(STATE + "\n" + script("reserve"), String.class);
  private static final DefaultRedisScript<List> INVENTORY = new DefaultRedisScript<>(STATE + "\n" + script("inventory"), List.class);
  private static final DefaultRedisScript<List> RELAY = new DefaultRedisScript<>(script("relay"), List.class);
  private static final DefaultRedisScript<Long> WAITLIST_INDEX =
      new DefaultRedisScript<>(script("waitlist-index"), Long.class);
  private final StringRedisTemplate redis;
  private String groupStream() { return namespace + ":combinations:stream"; }
  @org.springframework.beans.factory.annotation.Value("${v3.booking.namespace:ch:booking}")
  private String namespace = "ch:booking";

  BookingRedis(StringRedisTemplate redis) {
    this.redis = redis;
  }

  String reserve(BookingLedger.Snapshot snapshot, BookingLedger.Request request, boolean priorWaiting) {
    return redis.execute(RESERVE, keys(snapshot.session()), arguments(snapshot,
        request.id(), request.operation().name(), priorWaiting ? "1" : "0", request.trace()));
  }

  Inventory apply(BookingLedger.Snapshot snapshot, String request, boolean rebuild) {
    List<?> result = redis.execute(INVENTORY, keys(snapshot.session()),
        arguments(snapshot, request == null ? "" : request, rebuild ? "REBUILD" : "SETTLE"));
    if (result == null || result.size() != 2 || ((Number) result.get(1)).longValue() < 0) {
      throw new IllegalStateException("Booking inventory requires reconciliation");
    }
    return new Inventory(((Number) result.get(0)).longValue(), ((Number) result.get(1)).longValue());
  }

  boolean contains(Receipt receipt, long session, boolean hold) {
    var fields = redis.opsForHash().multiGet(keys(session).get(0), List.of("epoch", "request:" + receipt.request()));
    return fields != null && receipt.epoch().equals(fields.get(0))
        && (receipt.stream() + ":" + (hold ? "1" : "0")).equals(fields.get(1));
  }

  List<Receipt> receive(long session, String consumer, long leaseMillis) {
    return receive(keys(session).get(1), consumer, leaseMillis);
  }

  void offerCombination(String id, String trace) {
    redis.execute(RELAY, List.of(groupStream()), id, "OFFER", trace);
  }

  List<Receipt> receiveCombinations(String consumer, long leaseMillis) {
    return receive(groupStream(), consumer, leaseMillis);
  }

  void ackCombination(String stream) {
    redis.execute(RELAY, List.of(groupStream()), stream, "ACK");
  }

  private List<Receipt> receive(String key, String consumer, long leaseMillis) {
    List<?> messages = redis.execute(RELAY, List.of(key), consumer, "READ",
        Long.toString(leaseMillis));
    if (messages == null) return List.of();
    var result = new ArrayList<Receipt>(messages.size() / 4);
    for (int i = 0; i < messages.size(); i += 4) {
      result.add(new Receipt(messages.get(i).toString(), messages.get(i + 1).toString(),
          messages.get(i + 2).toString(), messages.get(i + 3).toString()));
    }
    return result;
  }

  void ack(long session, String id) {
    redis.execute(RELAY, List.of(keys(session).get(1)), id, "ACK");
  }

  void indexWaitlist(BookingLedger.Snapshot snapshot, List<Map<String, Object>> head) {
    var args = new ArrayList<>(List.of(snapshot.epoch(), Long.toString(snapshot.revision()),
        Long.toString(snapshot.keepUntil())));
    for (var row : head) {
      args.add(Long.toString(Store.id(row, "queue_seq")));
      args.add(Long.toString(Store.id(row, "id")));
    }
    redis.execute(WAITLIST_INDEX, List.of(keys(snapshot.session()).get(0),
        namespace + ":{" + snapshot.session() + "}:waitlist"), args.toArray());
  }

  private static Object[] arguments(BookingLedger.Snapshot snapshot, String... extra) {
    var arguments = new ArrayList<>(List.of(snapshot.epoch(), Long.toString(snapshot.revision()),
        Long.toString(snapshot.seats()), snapshot.waiting() ? "1" : "0",
        Long.toString(snapshot.keepUntil()), snapshot.initialized() ? "0" : "1"));
    arguments.addAll(Arrays.asList(extra));
    return arguments.toArray();
  }

  private List<String> keys(long session) {
    String base = namespace + ":{" + session + "}:";
    return List.of(base + "stock", base + "stream");
  }

  private static String script(String name) {
    try {
      return new ClassPathResource("booking/" + name + ".lua").getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("Missing Redis booking script " + name, error);
    }
  }
}
