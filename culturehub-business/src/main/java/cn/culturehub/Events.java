package cn.culturehub;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Events {
  final Store s;
  final ObjectMapper json;

  public Events(Store s, ObjectMapper j) {
    this.s = s;
    json = j;
  }

  public void add(String type, long participation, String key) {
    var p =
        s.one(
            "select p.user_id,p.session_id,p.confirm_deadline,x.start_at,a.title from participation"
                + " p join activity_session x on x.id=p.session_id join activity a on"
                + " a.id=x.activity_id where p.id=?",
            participation);
    try {
      var data = new java.util.LinkedHashMap<String, Object>();
      data.put("sessionId", p.get("session_id"));
      data.put("title", p.get("title"));
      data.put("startAt", p.get("start_at").toString());
      if (p.get("confirm_deadline") != null)
        data.put("confirmDeadline", p.get("confirm_deadline").toString());
      var e =
          new Event(
              UUID.randomUUID().toString(),
              type,
              1,
              participation,
              Store.id(p, "user_id"),
              java.time.Instant.now(),
              data);
      e.validate();
      s.db.update(
          "insert into outbox(event_id,business_key,event_type,aggregate_id,payload)"
              + " values(?,?,?,?,?)",
          e.eventId(),
          key,
          type,
          participation,
          json.writeValueAsString(e));
    } catch (org.springframework.dao.DuplicateKeyException e) {
      // Only a replay of the same business event may be treated as successful.
      var existing = s.one("select event_type,aggregate_id from outbox where business_key=?", key);
      if (!type.equals(existing.get("event_type"))
          || Store.id(existing, "aggregate_id") != participation) throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
