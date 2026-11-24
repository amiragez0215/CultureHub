package cn.culturehub.v3;

import java.time.Instant;

public record BookingMessage(
    String messageId, String messageType, int schemaVersion, Instant occurredAt,
    String correlationId, String traceId, String producer, long sessionId,
    String requestId, String streamId, String redisEpoch) {
  public static final String INGRESS = "culturehub.booking.ingress";
  public static final String DOMAIN = "culturehub.domain.events";
  public static final String COMMAND = "booking.request.accepted.v1";
  public static final String COMBINATION = "booking.combination.accepted.v1";
  public static final String SETTLE = "booking.inventory.changed.v1";
  public static final String SEAT_RELEASED = "seat.released.v1";
  public static final String REQUEST_QUEUE = "booking.request.worker";
  public static final String INVENTORY_QUEUE = "waitlist.promotion";
  public static final String DEAD_EXCHANGE = "culturehub.booking.dead";
  public static final String OUTBOX_PREFIX = "V3_BOOKING_";
  public static final String OUTBOX_SETTLE = OUTBOX_PREFIX + "SETTLE";
  public static final String OUTBOX_RELEASE = OUTBOX_PREFIX + "SEAT_RELEASED";

  public static BookingMessage of(String id, String type, long session, String request,
      String trace, String stream, String epoch) {
    return new BookingMessage(id, type, 1, Instant.now(), request == null ? id : request,
        trace, "culturehub-core", session, request, stream, epoch);
  }

  public boolean valid() {
    if (schemaVersion != 1 || sessionId <= 0 || messageId == null || correlationId == null
        || traceId == null || occurredAt == null || !"culturehub-core".equals(producer)) return false;
    if (COMBINATION.equals(messageType)) return requestId != null;
    return COMMAND.equals(messageType)
        ? requestId != null && streamId != null && redisEpoch != null
        : SETTLE.equals(messageType) || SEAT_RELEASED.equals(messageType);
  }
}
