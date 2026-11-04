package cn.culturehub;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.stereotype.Service;

@Service
public class Publisher {
  private static final Logger log = LoggerFactory.getLogger(Publisher.class);
  final Store s;
  final RabbitTransport transport;

  public Publisher(Store s, RabbitTransport transport) {
    this.s = s;
    this.transport = transport;
  }

  // Atomic conditional claim uses the existing retry time as a 30s crash-recoverable lease.
  // attempt_count fences stale publishers. No transaction/row lock spans broker I/O.
  public void publish() {
    for (var row :
        s.db.queryForList(
            "select * from outbox where status='PENDING' and next_attempt_at<=current_timestamp(6)"
                + " order by created_at,event_id limit 100")) {
      String id = row.get("event_id").toString();
      int attempt = ((Number) row.get("attempt_count")).intValue() + 1;
      if (s.db.update(
              "update outbox set"
                  + " attempt_count=attempt_count+1,next_attempt_at=date_add(current_timestamp(6),interval"
                  + " 30 second) where event_id=? and status='PENDING' and"
                  + " next_attempt_at<=current_timestamp(6) and attempt_count=?",
              id,
              attempt - 1)
          != 1) continue;
      try {
        var props = new MessageProperties();
        props.setMessageId(id);
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        transport.send(
            RabbitTopology.EXCHANGE,
            RabbitTopology.route(row.get("event_type").toString()),
            new Message(row.get("payload").toString().getBytes(StandardCharsets.UTF_8), props));
        s.db.update(
            "update outbox set status='PUBLISHED',published_at=current_timestamp,last_error=null"
                + " where event_id=? and status='PENDING' and attempt_count=?",
            id,
            attempt);
      } catch (Exception error) {
        // A poison/unroutable event must not starve the rest of this batch. The fencing
        // condition also prevents a stale publisher from overwriting a newer attempt.
        int backoffSeconds = (int) Math.min(300L, 10L << Math.min(attempt - 1, 5));
        try {
          s.db.update(
              "update outbox set next_attempt_at=date_add(current_timestamp(6),interval ?"
                  + " second),last_error=? where event_id=? and status='PENDING' and"
                  + " attempt_count=?",
              backoffSeconds,
              failureSummary(error),
              id,
              attempt);
        } catch (Exception recordFailure) {
          // If even the failure marker cannot be written, the original 30s claim expires.
          error.addSuppressed(recordFailure);
        }
        log.warn(
            "Outbox event {} attempt {} retained for retry in {}s",
            id,
            attempt,
            backoffSeconds,
            error);
        if (error instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          return; // Shutdown/cancellation is the only per-message failure that ends this batch.
        }
      }
    }
  }

  private static String failureSummary(Exception error) {
    String text = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
    return text.replace('\n', ' ').replace('\r', ' ').substring(0, Math.min(300, text.length()));
  }
}
