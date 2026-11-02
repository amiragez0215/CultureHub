package cn.culturehub;

import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class Publisher {
  final Store s;
  final KafkaTemplate<String, String> k;

  public Publisher(Store s, KafkaTemplate<String, String> k) {
    this.s = s;
    this.k = k;
  }

  // No database transaction is held during the network send. A crash before marking permits a safe
  // duplicate.
  public synchronized void publish() {
    for (var e :
        s.db.queryForList(
            "select * from outbox where status='PENDING' and next_attempt_at<=current_timestamp"
                + " order by created_at,event_id limit 100")) {
      String id = e.get("event_id").toString();
      try {
        k.send(Event.TOPIC, e.get("aggregate_id").toString(), e.get("payload").toString())
            .get(10, TimeUnit.SECONDS);
        s.db.update(
            "update outbox set status='PUBLISHED',published_at=current_timestamp,last_error=null"
                + " where event_id=? and status='PENDING'",
            id);
      } catch (Exception ex) {
        s.db.update(
            "update outbox set"
                + " attempt_count=attempt_count+1,next_attempt_at=date_add(current_timestamp,interval"
                + " 10 second),last_error=? where event_id=?",
            ex.getClass().getSimpleName(),
            id);
        throw new IllegalStateException("Outbox retry retained: " + id, ex);
      }
    }
  }
}
