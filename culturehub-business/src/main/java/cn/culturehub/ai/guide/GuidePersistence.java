package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL controls versions/ownership; Redis is short-lived memory, never a booking ledger. */
@org.springframework.context.annotation.Profile("!v3-p5 & !v3-partner")
@Service
public class GuidePersistence {
  private static final DefaultRedisScript<Long> REMEMBER =
      new DefaultRedisScript<>(
          "redis.call('RPUSH', KEYS[1], ARGV[1]); redis.call('LTRIM', KEYS[1], -20, -1);"
              + " return redis.call('EXPIRE', KEYS[1], 1800)",
          Long.class);
  final Store s;
  final Access access;
  final AiBookingProposalService proposals;
  final GuideQueryService queries;
  final ObjectMapper json;
  final StringRedisTemplate redis;
  final TransactionTemplate tx;
  @org.springframework.beans.factory.annotation.Value("${culture.ai.owner-table:app_user}")
  String ownerTable = "app_user";

  public GuidePersistence(
      Store s,
      Access a,
      AiBookingProposalService p,
      GuideQueryService q,
      ObjectMapper j,
      StringRedisTemplate r,
      PlatformTransactionManager tm) {
    this.s = s;
    access = a;
    proposals = p;
    queries = q;
    json = j;
    redis = r;
    tx = new TransactionTemplate(tm);
  }

  public String create() {
    long user = access.user();
    String id = UUID.randomUUID().toString();
    tx.executeWithoutResult(
        t -> {
          Problem.require(Set.of("app_user","ai_owner").contains(ownerTable), "AI_CONFIGURATION_INVALID", "Invalid owner table");
          s.one("select id from " + ownerTable + " where id=? for update", user);
          Problem.require(
              s.number(
                      "select count(*) from ai_guide_conversation where user_id=? and"
                          + " expires_at>now(6)",
                      user)
                  < 5,
              "AI_SESSION_LIMIT",
              "有效会话已达上限");
          s.db.update(
              "insert into ai_guide_conversation(id,user_id,expires_at)"
                  + " values(?,?,date_add(now(6),interval 30 minute))",
              id,
              user);
        });
    return id;
  }

  public Object conversations() {
    return s.db.queryForList(
        "select id,version,expires_at from ai_guide_conversation where user_id=? and"
            + " expires_at>now(6) order by created_at desc limit 5",
        access.user());
  }

  public List<Map<String, Object>> cards(String conversation) {
    owned(conversation, false);
    var rows =
        s.db.queryForList(
            "select i.candidate_id,i.session_snapshot from ai_activity_plan_item i join"
                + " ai_activity_plan p on p.plan_id=i.plan_id join ai_guide_conversation c on"
                + " c.id=p.conversation_id where p.conversation_id=? and p.user_id=? and"
                + " p.status='ACTIVE' and p.version=c.version and p.expires_at>now(6) order by"
                + " i.display_order",
            conversation,
            access.user());
    return rows.stream()
        .map(
            row -> {
              try {
                var session = json.readValue(row.get("session_snapshot").toString(), Session.class);
                var card =
                    json.convertValue(
                        session,
                        new com.fasterxml.jackson.core.type.TypeReference<
                            LinkedHashMap<String, Object>>() {});
                card.put("candidateId", row.get("candidate_id"));
                card.put("bookingAllowed", session.remaining() > 0 && !session.waitlistPriority());
                card.put("waitlistAllowed", session.remaining() <= 0);
                card.put("ageNotice", "已恢复的规划快照；确认时会重新查询资格和余量。");
                return (Map<String, Object>) card;
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .toList();
  }

  public Object pendingProposal(String conversation) {
    owned(conversation, false);
    var rows =
        s.db.queryForList(
            "select id from ai_booking_proposal where conversation_id=? and user_id=? and status in"
                + " ('PENDING','PROCESSING') order by created_at desc limit 1",
            conversation,
            access.user());
    return rows.isEmpty() ? null : proposals.get(rows.get(0).get("id").toString());
  }

  public Map<String, Object> owned(String id, boolean lock) {
    var rows =
        s.db.queryForList(
            "select * from ai_guide_conversation where id=?" + (lock ? " for update" : ""), id);
    if (rows.isEmpty()) throw new Problem("CONVERSATION_EXPIRED", "会话已过期，请开始新对话", 409);
    var row = rows.get(0);
    access.owner(Store.id(row, "user_id"));
    Problem.require(
        s.now().isBefore(Store.time(row, "expires_at")), "CONVERSATION_EXPIRED", "会话已过期");
    return row;
  }

  public int begin(String id) {
    return tx.execute(
        t -> {
          var row = owned(id, true);
          Problem.require(
              row.get("busy_until") == null || !s.now().isBefore(Store.time(row, "busy_until")),
              "CONVERSATION_BUSY",
              "请等待当前规划完成");
          proposals.supersede(id, access.user());
          s.db.update(
              "update ai_activity_plan set status='SUPERSEDED' where conversation_id=? and"
                  + " status='ACTIVE'",
              id);
          int version = ((Number) row.get("version")).intValue() + 1;
          s.db.update(
              "update ai_guide_conversation set version=?,busy_until=date_add(now(6),interval 100"
                  + " second),expires_at=date_add(now(6),interval 30 minute) where id=?",
              version,
              id);
          return version;
        });
  }

  public void release(String id, int version) {
    s.db.update(
        "update ai_guide_conversation set busy_until=null where id=? and version=?", id, version);
  }

  public Turn previous(String id) {
    owned(id, false);
    try {
      String snapshot = redis.opsForValue().get("ai:v2:checkpoint:" + access.user() + ":" + id);
      if (snapshot != null) {
        var cached = json.readValue(snapshot, Turn.class);
        if (cached.identity != null
            && cached.identity.ownerUserId() == access.user()
            && id.equals(cached.identity.conversationId())) return cached;
      }
    } catch (Exception ignored) {
    }
    var rows =
        s.db.queryForList(
            "select constraints_snapshot from ai_activity_plan where conversation_id=? order by"
                + " version desc limit 1",
            id);
    if (rows.isEmpty()) return null;
    try {
      return json.readValue(rows.get(0).get("constraints_snapshot").toString(), Turn.class);
    } catch (Exception e) {
      throw new Problem("AI_STATE_INVALID", "已保存的规划无法读取", 503);
    }
  }

  public Turn activePlan(String id) {
    owned(id, false);
    var rows =
        s.db.queryForList(
            "select p.constraints_snapshot from ai_activity_plan p join ai_guide_conversation c on"
                + " c.id=p.conversation_id where p.conversation_id=? and p.user_id=? and"
                + " p.status='ACTIVE' and p.verification_status='VERIFIED' and p.version=c.version"
                + " and p.expires_at>now(6) limit 1",
            id,
            access.user());
    if (rows.isEmpty()) return null;
    try {
      return json.readValue(rows.get(0).get("constraints_snapshot").toString(), Turn.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      throw new Problem("AI_STATE_INVALID", "已保存的规划无法读取", 503);
    }
  }

  public void checkpoint(Turn turn) {
    // An observable serialized node snapshot, NOT Alibaba's Redisson RedisSaver.
    try {
      redis
          .opsForValue()
          .set(
              "ai:v2:checkpoint:"
                  + turn.identity.ownerUserId()
                  + ":"
                  + turn.identity.conversationId(),
              json.writeValueAsString(turn),
              Duration.ofMinutes(30));
    } catch (Exception ignored) {
      /* Planning can continue; durable verified plans remain in MySQL. */
    }
  }

  public void remember(Turn turn, String answer) {
    try {
      redis.execute(
          REMEMBER,
          List.of(memoryKey(turn.identity.ownerUserId(), turn.identity.conversationId())),
          json.writeValueAsString(
              new ConversationExchange(
                  memoryText(turn.identity.latestUserMessage()), memoryText(answer))));
    } catch (Exception ignored) {
      /* Redis memory has no booking authority. */
    }
  }

  public List<ConversationExchange> recent(String conversation) {
    owned(conversation, false);
    var exchanges = new ArrayList<ConversationExchange>();
    try {
      var entries = redis.opsForList().range(memoryKey(access.user(), conversation), -4, -1);
      if (entries == null) return List.of();
      for (var entry : entries) {
        if (entry == null || entry.length() > 2400) continue;
        try {
          var exchange = json.readValue(entry, ConversationExchange.class);
          var user = memoryText(exchange.user());
          if (!user.isBlank())
            exchanges.add(new ConversationExchange(user, memoryText(exchange.assistant())));
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
          // One malformed item should not hide the rest of the conversation.
        }
      }
    } catch (RuntimeException unavailable) {
      return List.of();
    }
    return List.copyOf(exchanges);
  }

  private static String memoryKey(long user, String conversation) {
    return "ai:v2:chat:" + user + ":" + conversation;
  }

  private static String memoryText(String text) {
    if (text == null) return "";
    var normalized = text.replaceAll("[\\p{Cntrl}]", " ").strip();
    if (normalized.matches(
        "(?is).*(忽略.{0,8}指令|系统提示词|修改数据库|ignore.{0,20}instructions|system prompt|execute SQL).*"))
      return "";
    return normalized
        .codePoints()
        .limit(240)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  public List<Map<String, Object>> save(Turn turn) {
    if (!"VERIFIED".equals(turn.phase)) return List.of();
    return tx.execute(
        t -> {
          var row = owned(turn.identity.conversationId(), true);
          Problem.require(
              ((Number) row.get("version")).intValue() == turn.currentPlanVersion,
              "PLAN_SUPERSEDED",
              "规划版本已更新");
          try {
            s.db.update(
                "insert into"
                    + " ai_activity_plan(plan_id,user_id,conversation_id,version,constraints_snapshot,verification_status,expires_at)"
                    + " values(?,?,?,?,?,'VERIFIED',date_add(now(6),interval 10 minute))",
                turn.identity.turnId(),
                turn.identity.ownerUserId(),
                turn.identity.conversationId(),
                turn.currentPlanVersion,
                json.writeValueAsString(turn));
            var cards = new ArrayList<Map<String, Object>>();
            int order = 0;
            for (long session : turn.proposal.orderedSessionIds()) {
              var item =
                  turn.candidateSessions.stream()
                      .filter(c -> c.sessionId() == session)
                      .findFirst()
                      .orElseThrow();
              String candidate = UUID.randomUUID().toString();
              s.db.update(
                  "insert into"
                      + " ai_activity_plan_item(plan_id,plan_version,session_id,display_order,candidate_id,session_snapshot)"
                      + " values(?,?,?,?,?,?)",
                  turn.identity.turnId(),
                  turn.currentPlanVersion,
                  session,
                  order++,
                  candidate,
                  json.writeValueAsString(item));
              var card =
                  json.convertValue(
                      item,
                      new com.fasterxml.jackson.core.type.TypeReference<
                          LinkedHashMap<String, Object>>() {});
              card.put("candidateId", candidate);
              card.put("bookingAllowed", item.remaining() > 0 && !item.waitlistPriority());
              card.put("waitlistAllowed", item.remaining() <= 0);
              card.put("ageNotice", "年龄为用户自述，未完成资格认证；一个账号一个名额，不支持代约。");
              cards.add(card);
            }
            return cards;
          } catch (Exception error) {
            if (error instanceof Problem p) throw p;
            throw new IllegalStateException(error);
          }
        });
  }

  public Map<String, Object> select(String conversation, String candidate, String mode) {
    Problem.require(Set.of("DIRECT", "WAITLIST").contains(mode), "VALIDATION_FAILED", "请选择预约或候补");
    return tx.execute(
        t -> {
          var c = owned(conversation, true);
          Problem.require(c.get("busy_until") == null, "CONVERSATION_BUSY", "请等待当前规划完成");
          var item =
              s.one(
                  "select i.*,p.constraints_snapshot from ai_activity_plan_item i join"
                      + " ai_activity_plan p on p.plan_id=i.plan_id where i.candidate_id=? and"
                      + " p.conversation_id=? and p.user_id=? and p.status='ACTIVE' and"
                      + " p.verification_status='VERIFIED' and p.version=? and p.expires_at>now(6)",
                  candidate,
                  conversation,
                  access.user(),
                  c.get("version"));
          try {
            Turn state = json.readValue(item.get("constraints_snapshot").toString(), Turn.class);
            long session = Store.id(item, "session_id");
            var current = queries.current(session);
            state.existingBookings =
                queries.activeBookings(access.user()).stream()
                    .map(
                        b ->
                            new ExistingBooking(
                                Store.id(b, "id"),
                                Store.id(b, "sessionId"),
                                b.get("status").toString(),
                                GuideQueryService.time(b, "startAt"),
                                GuideQueryService.time(b, "endAt")))
                    .toList();
            state.identity =
                new Identity(
                    conversation,
                    state.identity.turnId(),
                    state.identity.traceId(),
                    access.user(),
                    state.identity.latestUserMessage(),
                    s.now());
            Problem.require(
                queries.open(current)
                    && queries.matches(current, state.requirements.hardConstraints())
                    && new GuidePlanner().eligible(GuideTools.session(current), state),
                "PLAN_STALE",
                "场次或已有预约已变化，请重新规划");
            var proposal = proposals.create(conversation, session, mode.equals("WAITLIST"));
            s.db.update(
                "update ai_booking_proposal b join ai_activity_plan p on p.plan_id=? set"
                    + " b.expires_at=least(b.expires_at,p.expires_at) where b.id=? and b.user_id=?"
                    + " and b.status='PENDING'",
                item.get("plan_id"),
                proposal.get("id"),
                access.user());
            s.db.update(
                "update ai_activity_plan_item set selection_status='PROPOSED' where candidate_id=?",
                candidate);
            return proposals.get(proposal.get("id").toString());
          } catch (Problem e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }

  public Map<String, Object> confirm(String id) {
    String conversation = proposals.conversation(id);
    var result = proposals.get(id);
    // Recover a claimed execution using its original request ID even while the plan changes.
    if (!"PENDING".equals(result.get("status"))) return proposals.confirm(id, true);
    var row = owned(conversation, false);
    Problem.require(row.get("busy_until") == null, "CONVERSATION_BUSY", "规划正在更新");
    long session =
        s.number(
            "select session_id from ai_booking_proposal where id=? and user_id=?",
            id,
            access.user());
    var current = queries.current(session);
    var start = GuideQueryService.time(current, "startAt");
    var end = GuideQueryService.time(current, "endAt");
    Problem.require(
        queries.activeBookings(access.user()).stream()
            .noneMatch(
                b ->
                    Store.id(b, "sessionId") != session
                        && GuidePlanner.overlaps(
                            start,
                            end,
                            GuideQueryService.time(b, "startAt"),
                            GuideQueryService.time(b, "endAt"))),
        "PLAN_SCHEDULE_CONFLICT",
        "已有预约发生变化，此场次与您的参与时间冲突，请重新规划");
    return proposals.confirm(
        id, true); // Concurrent revision supersedes only PENDING under DB row locks.
  }
}
