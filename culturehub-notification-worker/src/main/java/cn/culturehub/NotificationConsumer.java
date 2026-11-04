package cn.culturehub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationConsumer {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final ObjectMapper j;

  public NotificationConsumer(
      JdbcTemplate d,
      org.springframework.transaction.PlatformTransactionManager tm,
      ObjectMapper j) {
    db = d;
    tx = new TransactionTemplate(tm);
    this.j = j;
  }

  public void consume(String raw) throws Exception {
    Event e = j.readValue(raw, Event.class);
    e.validate();
    tx.executeWithoutResult(
        t -> {
          try {
            db.update(
                "insert into"
                    + " notification(source_event_id,user_id,type,title,body,participation_id,occurred_at)"
                    + " values(?,?,?,?,?,?,?)",
                e.eventId(),
                e.recipientUserId(),
                e.eventType(),
                e.data().getOrDefault("title", "CultureHub"),
                raw,
                e.aggregateId(),
                java.sql.Timestamp.from(e.occurredAt()));
          } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            if (db.queryForObject(
                    "select count(*) from notification where source_event_id=? and user_id=? and"
                        + " type=?",
                    Long.class,
                    e.eventId(),
                    e.recipientUserId(),
                    e.eventType())
                != 1) throw duplicate;
          }
        });
  }
}
