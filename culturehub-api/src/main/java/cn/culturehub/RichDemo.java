package cn.culturehub;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in synthetic fixture import. One transaction; existing imported catalog is never reset. */
@Component
@Order(20)
@ConditionalOnProperty(name = "culture.demo.rich", havingValue = "true")
public class RichDemo implements CommandLineRunner {
  final Store s;
  final PasswordEncoder password;
  final ObjectMapper json;
  final TransactionTemplate tx;
  final String demoPassword;

  public RichDemo(
      Store s,
      PasswordEncoder p,
      ObjectMapper j,
      org.springframework.transaction.PlatformTransactionManager tm,
      @Value("${culture.demo.password:}") String secret) {
    this.s = s;
    password = p;
    json = j;
    tx = new TransactionTemplate(tm);
    demoPassword = secret;
  }

  public void run(String... args) throws Exception {
    if (demoPassword.isBlank()) throw new IllegalStateException("Rich demo requires DEMO_PASSWORD");
    try (var input = getClass().getResourceAsStream("/demo/catalog.json")) {
      var catalog = json.readTree(input);
      tx.executeWithoutResult(
          t -> {
            if (s.number("select count(*) from activity where title='无障碍阅读体验'") > 0) {
              s.db.update(
                  "insert ignore into venue_grant select u.id,v.id from app_user u join venue v on"
                      + " v.name='市文化博物馆' where u.username='venueadmin'");
              return;
            }
            List<Long> venues = new ArrayList<>();
            for (var v : catalog.get("venues")) {
              var rows =
                  s.db.queryForList("select id from venue where name=?", v.get("name").asText());
              long id =
                  rows.isEmpty()
                      ? s.insert(
                          "insert into venue(name,address,introduction,opening_hours,category)"
                              + " values(?,?,?,?,?)",
                          v.get("name").asText(),
                          v.get("address").asText(),
                          v.get("introduction").asText(),
                          v.get("opening_hours").asText(),
                          v.path("category").asText("CULTURE_CENTER"))
                      : Store.id(rows.get(0), "id");
              venues.add(id);
            }
            List<Long> users = new ArrayList<>();
            String hash = password.encode(demoPassword);
            if (s.number("select count(*) from user_role where role='PLATFORM_ADMIN'") == 0) {
              var admins = s.db.queryForList("select id from app_user where username='admin'");
              long admin =
                  admins.isEmpty()
                      ? s.insert(
                          "insert into app_user(username,password_hash,nickname)"
                              + " values('admin',?,'平台管理员')",
                          hash)
                      : Store.id(admins.get(0), "id");
              s.db.update("insert ignore into user_role values(?,'PLATFORM_ADMIN')", admin);
            }
            for (int i = 0; i < 24; i++) {
              String name = i == 0 ? "user" : String.format("demo%02d", i);
              var rows = s.db.queryForList("select id from app_user where username=?", name);
              long id =
                  rows.isEmpty()
                      ? s.insert(
                          "insert into app_user(username,password_hash,nickname) values(?,?,?)",
                          name,
                          hash,
                          "体验用户" + (i + 1))
                      : Store.id(rows.get(0), "id");
              s.db.update("insert ignore into user_role values(?,'USER')", id);
              users.add(id);
            }
            for (int i = 0; i < venues.size(); i++) {
              String name = "manager" + (i + 1);
              var rows = s.db.queryForList("select id from app_user where username=?", name);
              long id =
                  rows.isEmpty()
                      ? s.insert(
                          "insert into app_user(username,password_hash,nickname) values(?,?,?)",
                          name,
                          hash,
                          "场馆管理员" + (i + 1))
                      : Store.id(rows.get(0), "id");
              s.db.update("insert ignore into user_role values(?,'VENUE_ADMIN')", id);
              s.db.update("insert ignore into venue_grant values(?,?)", id, venues.get(i));
            }
            s.db.update(
                "insert ignore into venue_grant select id,? from app_user where"
                    + " username='venueadmin'",
                venues.get(0));
            long operator =
                s.number("select user_id from user_role where role='PLATFORM_ADMIN' limit 1");
            LocalDateTime now = s.now().withNano(0);
            int index = 0;
            for (var a : catalog.get("activities")) {
              long activity =
                  s.insert(
                      "insert into"
                          + " activity(venue_id,title,description,category,audience,plan,notice,copywriting,status,created_by)"
                          + " values(?,?,?,?,?,?,?,?,?,?)",
                      venues.get(a.get("venue").asInt()),
                      a.get("title").asText(),
                      a.get("description").asText(),
                      a.get("category").asText(),
                      a.get("audience").asText(),
                      a.get("plan").asText(),
                      a.path("notice").asText("免费参与，一人一名额。请提前十分钟到场。"),
                      a.get("description").asText(),
                      index % 10 == 9 ? "DRAFT" : "PUBLISHED",
                      operator);
              for (int j = 0; j < (index < 20 ? 2 : 1); j++) {
                int scenario = (index + j) % 6;
                boolean ended = scenario == 3;
                LocalDateTime start =
                    ended
                        ? now.minusDays(2 + index % 6)
                        : now.plusDays(1 + index % 10).withHour(j == 0 ? 10 : 14).withMinute(0);
                String state =
                    index % 10 == 9 ? "DRAFT" : scenario == 4 ? "CANCELLED" : "PUBLISHED";
                int capacity = ended ? 16 : scenario == 1 || scenario == 2 ? 4 : 12 + index % 4 * 4;
                long session =
                    s.insert(
                        "insert into"
                            + " activity_session(activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status)"
                            + " values(?,?,?,?,?,?,?,?,?)",
                        activity,
                        now.minusDays(20),
                        start.minusHours(1),
                        start,
                        start.plusHours(2),
                        ended ? start.minusMinutes(30) : now.minusHours(1),
                        start.plusMinutes(90),
                        capacity,
                        state);
                if (state.equals("DRAFT")) continue;
                List<String> states = new ArrayList<>();
                if (state.equals("CANCELLED"))
                  states.addAll(List.of("CANCELLED", "CANCELLED", "CANCELLED"));
                else if (ended) {
                  for (int k = 0; k < 14; k++) states.add(k < 8 ? "CHECKED_IN" : "RESERVED");
                  states.add("CANCELLED");
                  states.add("EXPIRED");
                } else if (scenario == 1)
                  states.addAll(
                      List.of(
                          "RESERVED", "RESERVED", "RESERVED", "RESERVED", "WAITING", "WAITING"));
                else if (scenario == 2)
                  states.addAll(List.of("RESERVED", "RESERVED", "RESERVED", "OFFERED", "WAITING"));
                else states.addAll(List.of("RESERVED", "RESERVED"));
                long seq = 0;
                for (int k = 0; k < states.size(); k++) {
                  String status = states.get(k);
                  boolean waiting = Set.of("WAITING", "OFFERED", "EXPIRED").contains(status);
                  long u = users.get(k);
                  LocalDateTime reserved = start.minusDays(1);
                  long id =
                      s.insert(
                          "insert into"
                              + " participation(session_id,user_id,status,entry_type,request_id,queue_seq,offered_at,confirm_deadline,reserved_at,checkin_token,ended_at,end_reason,created_at)"
                              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                          session,
                          u,
                          status,
                          waiting ? "WAITLIST" : "DIRECT",
                          "rich-demo-" + session + "-" + k,
                          waiting ? ++seq : null,
                          status.equals("OFFERED") ? now : null,
                          status.equals("OFFERED") ? now.plusMinutes(10) : null,
                          waiting ? null : reserved,
                          waiting ? null : ParticipationService.token(),
                          Set.of("CANCELLED", "EXPIRED").contains(status)
                              ? reserved.plusMinutes(5)
                              : null,
                          status.equals("CANCELLED")
                              ? (state.equals("CANCELLED") ? "SESSION_CANCEL" : "USER_CANCEL")
                              : status.equals("EXPIRED") ? "REGISTRATION_CLOSED" : null,
                          reserved);
                  String initial = waiting ? "WAITING" : "RESERVED";
                  s.db.update(
                      "insert into"
                          + " participation_history(participation_id,from_status,to_status,reason,occurred_at)"
                          + " values(?,null,?,'CREATED',?)",
                      id,
                      initial,
                      reserved);
                  if (!initial.equals(status))
                    s.db.update(
                        "insert into"
                            + " participation_history(participation_id,from_status,to_status,reason,occurred_at)"
                            + " values(?,?,?,?,?)",
                        id,
                        initial,
                        status,
                        status.equals("CANCELLED")
                            ? (state.equals("CANCELLED") ? "SESSION_CANCEL" : "USER_CANCEL")
                            : status.equals("EXPIRED")
                                ? "REGISTRATION_CLOSED"
                                : status.equals("CHECKED_IN") ? "CHECKIN" : "PROMOTED",
                        status.equals("CHECKED_IN") ? start.plusMinutes(5) : now);
                  if (status.equals("CHECKED_IN")) {
                    s.db.update(
                        "insert into checkin_record(participation_id,operator_id,checked_at)"
                            + " values(?,?,?)",
                        id,
                        operator,
                        start.plusMinutes(5));
                    s.db.update(
                        "insert into"
                            + " review(user_id,session_id,participation_id,rating,content,created_at,updated_at)"
                            + " values(?,?,?,?,?,?,?)",
                        u,
                        session,
                        id,
                        3 + k % 3,
                        a.get("title").asText()
                            + "："
                            + List.of(
                                    "讲解清晰，动手环节很有收获，希望多留一些交流时间。",
                                    "工作人员耐心，流程顺畅，建议补充更明确的到场指引。",
                                    "内容丰富，适合初次体验，材料准备很充分。",
                                    "整体体验不错，分组人数可以再少一些。")
                                .get(k % 4),
                        start.plusHours(3),
                        start.plusHours(3));
                  }
                  if (k == 0) {
                    String event = UUID.randomUUID().toString();
                    String body;
                    try {
                      body =
                          json.writeValueAsString(
                              new Event(
                                  event,
                                  state.equals("CANCELLED")
                                      ? "SESSION_CANCELLED"
                                      : "RESERVATION_CONFIRMED",
                                  1,
                                  id,
                                  u,
                                  Instant.now(),
                                  Map.of(
                                      "title",
                                      a.get("title").asText(),
                                      "sessionId",
                                      session,
                                      "startAt",
                                      start.toString())));
                    } catch (Exception ex) {
                      throw new IllegalStateException(ex);
                    }
                    s.db.update(
                        "insert into"
                            + " notification(source_event_id,user_id,type,title,body,participation_id,occurred_at)"
                            + " values(?,?,?,?,?,?,?)",
                        event,
                        u,
                        state.equals("CANCELLED") ? "SESSION_CANCELLED" : "RESERVATION_CONFIRMED",
                        a.get("title").asText(),
                        body,
                        id,
                        now);
                  }
                }
                s.db.update(
                    "update activity_session set occupied_count=(select count(*) from participation"
                        + " where session_id=? and status in"
                        + " ('OFFERED','RESERVED','CHECKED_IN')),last_waitlist_seq=? where id=?",
                    session,
                    seq,
                    session);
              }
              index++;
            }
            long invalid =
                s.number(
                    "select count(*) from activity_session x where x.occupied_count<>(select"
                        + " count(*) from participation p where p.session_id=x.id and p.status in"
                        + " ('OFFERED','RESERVED','CHECKED_IN'))");
            if (invalid != 0) throw new IllegalStateException("Demo capacity mismatch");
          });
    }
  }
}
