package cn.culturehub;

import java.time.Instant;
import java.util.Map;

public record Event(
    String eventId,
    String eventType,
    int eventVersion,
    long aggregateId,
    long recipientUserId,
    Instant occurredAt,
    Map<String, Object> data) {
  public static final String TOPIC = "culturehub.notification.events.v1";
  public static final String DLT = TOPIC + ".dlt";

  public void validate() {
    if (eventId == null
        || eventId.isBlank()
        || eventVersion != 1
        || recipientUserId <= 0
        || occurredAt == null
        || data == null
        || !java.util.Set.of(
                "RESERVATION_CONFIRMED",
                "RESERVATION_CANCELLED",
                "WAITLIST_OFFERED",
                "SESSION_START_REMINDER",
                "SESSION_CANCELLED")
            .contains(eventType)) throw new IllegalArgumentException("Invalid event contract");
  }
}
