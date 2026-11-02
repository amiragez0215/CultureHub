package cn.culturehub;

import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable confirmation barrier. Graph/checkpoints never authorize a booking. */
@Service
public class AiBookingProposalService {
  final Store s;
  final Access access;
  final Booking booking;
  final GuideQueryService queries;
  final TransactionTemplate tx;

  @org.springframework.beans.factory.annotation.Value("${culture.ai.proposal-ttl-minutes:5}")
  int proposalTtlMinutes = 5;

  public AiBookingProposalService(
      Store s,
      Access access,
      Booking booking,
      GuideQueryService queries,
      PlatformTransactionManager tm) {
    this.s = s;
    this.access = access;
    this.booking = booking;
    this.queries = queries;
    this.tx = new TransactionTemplate(tm);
  }

  public void supersede(String conversation, long user) {
    s.db.update(
        "update ai_booking_proposal set status=case when expires_at<=current_timestamp(6) then"
            + " 'EXPIRED' else 'REJECTED'"
            + " end,error_code='PROPOSAL_SUPERSEDED',finished_at=current_timestamp(6) where"
            + " conversation_id=? and user_id=? and status='PENDING'",
        conversation,
        user);
  }

  public Map<String, Object> create(String conversation, long session, boolean wait) {
    long user = access.user();
    String id = UUID.randomUUID().toString();
    tx.executeWithoutResult(
        status -> {
          // Serializes replacement within a user; the unique active guard is a second invariant.
          s.one("select id from app_user where id=? for update", user);
          Problem.require(
              s.number(
                      "select count(*) from ai_booking_proposal where user_id=? and"
                          + " conversation_id=? and status='PROCESSING'",
                      user,
                      conversation)
                  == 0,
              "PROPOSAL_PROCESSING",
              "已有方案正在提交，请先查询结果");
          var card = queries.current(session);
          Problem.require(queries.open(card), "REGISTRATION_CLOSED", "场次已不可报名");
          if (wait)
            Problem.require(
                ((Number) card.get("remaining")).longValue() <= 0,
                "SEATS_AVAILABLE",
                "当前仍有名额，请选择直接预约");
          else {
            Problem.require(
                ((Number) card.get("waitlistPriority")).intValue() == 0,
                "WAITLIST_PRIORITY",
                "候补用户优先");
            Problem.require(
                ((Number) card.get("remaining")).longValue() > 0, "SESSION_FULL", "名额已满，可另行选择候补");
          }
          Problem.require(
              s.number(
                      "select count(*) from participation where session_id=? and user_id=? and"
                          + " active_guard=1",
                      session,
                      user)
                  == 0,
              "ALREADY_PARTICIPATING",
              "已拥有有效参与资格");
          supersede(conversation, user);
          LocalDateTime expiry = s.now().plusMinutes(Math.max(1, Math.min(30, proposalTtlMinutes)));
          for (String key : List.of("registrationEndAt", "startAt"))
            if (GuideQueryService.time(card, key).isBefore(expiry))
              expiry = GuideQueryService.time(card, key);
          s.db.update(
              "insert into"
                  + " ai_booking_proposal(id,user_id,conversation_id,activity_id,session_id,request_id,entry_type,expires_at)"
                  + " values(?,?,?,?,?,?,?,?)",
              id,
              user,
              conversation,
              card.get("activityId"),
              session,
              "ai_" + UUID.randomUUID().toString(),
              wait ? "WAITLIST" : "DIRECT",
              expiry);
        });
    return get(id);
  }

  Map<String, Object> owned(String id, boolean lock) {
    var rows =
        s.db.queryForList(
            "select * from ai_booking_proposal where id=?" + (lock ? " for update" : ""), id);
    if (rows.isEmpty()) throw new Problem("PROPOSAL_NOT_FOUND", "方案不存在", 404);
    var row = rows.get(0);
    access.owner(Store.id(row, "user_id"));
    return row;
  }

  Map<String, Object> existing(Map<String, Object> row) {
    return booking.result(
        Store.id(row, "session_id"),
        row.get("request_id").toString(),
        "WAITLIST".equals(row.get("entry_type")));
  }

  public Map<String, Object> get(String id) {
    var row = owned(id, false);
    // Recovery works after expiry/restart. A result lookup never submits a new booking.
    if (List.of("PROCESSING", "SUCCEEDED").contains(row.get("status"))) {
      var original = existing(row);
      if (original != null) {
        s.db.update(
            "update ai_booking_proposal set"
                + " status='SUCCEEDED',participation_id=?,finished_at=coalesce(finished_at,current_timestamp(6)),error_code=null,processing_until=null"
                + " where id=? and status='PROCESSING'",
            original.get("id"),
            id);
        row = owned(id, false);
      }
    }
    if ("PENDING".equals(row.get("status")) && !s.now().isBefore(Store.time(row, "expires_at"))) {
      s.db.update(
          "update ai_booking_proposal set"
              + " status='EXPIRED',error_code='PROPOSAL_EXPIRED',finished_at=current_timestamp(6)"
              + " where id=? and status='PENDING'",
          id);
      row = owned(id, false);
    }
    return view(row);
  }

  /**
   * BookingExecute: runs on the authenticated confirmation HTTP thread, outside the claim
   * transaction.
   */
  public Map<String, Object> confirm(String id, boolean liveConversation) {
    get(id);
    String token = UUID.randomUUID().toString();
    var claimed =
        tx.execute(
            status -> {
              var row = owned(id, true);
              String phase = row.get("status").toString();
              if (!phase.equals("PENDING") && !phase.equals("PROCESSING")) return false;
              LocalDateTime now = s.now();
              if (phase.equals("PROCESSING")
                  && row.get("processing_until") != null
                  && now.isBefore(Store.time(row, "processing_until"))) return false;
              if (!liveConversation) {
                if (phase.equals("PENDING"))
                  s.db.update(
                      "update ai_booking_proposal set"
                          + " status='EXPIRED',error_code='CONVERSATION_EXPIRED',finished_at=current_timestamp(6)"
                          + " where id=?",
                      id);
                else
                  s.db.update(
                      "update ai_booking_proposal set error_code='BOOKING_RESULT_UNKNOWN' where"
                          + " id=?",
                      id);
                return false;
              }
              if (!now.isBefore(Store.time(row, "expires_at"))) {
                if (phase.equals("PENDING"))
                  s.db.update(
                      "update ai_booking_proposal set"
                          + " status='EXPIRED',error_code='PROPOSAL_EXPIRED',finished_at=current_timestamp(6)"
                          + " where id=?",
                      id);
                else
                  s.db.update(
                      "update ai_booking_proposal set error_code='BOOKING_RESULT_UNKNOWN' where"
                          + " id=?",
                      id);
                return false;
              }
              s.db.update(
                  "update ai_booking_proposal set"
                      + " status='PROCESSING',confirmed_at=coalesce(confirmed_at,current_timestamp(6)),processing_until=date_add(current_timestamp(6),interval"
                      + " 60 second),execution_token=?,error_code=null where id=?",
                  token,
                  id);
              return true;
            });
    if (!Boolean.TRUE.equals(claimed)) return get(id);
    var row = owned(id, false);
    long started = System.nanoTime();
    String code = "OK";
    try {
      var original =
          booking.create(
              Store.id(row, "session_id"),
              row.get("request_id").toString(),
              "WAITLIST".equals(row.get("entry_type")));
      s.db.update(
          "update ai_booking_proposal set"
              + " status='SUCCEEDED',participation_id=?,finished_at=current_timestamp(6),processing_until=null,error_code=null"
              + " where id=? and status='PROCESSING' and execution_token=?",
          original.get("id"),
          id,
          token);
    } catch (Problem problem) {
      code = problem.code;
      // Another attempt may have committed under the same durable request key.
      var original = existing(row);
      if (original != null)
        s.db.update(
            "update ai_booking_proposal set"
                + " status='SUCCEEDED',participation_id=?,finished_at=current_timestamp(6),processing_until=null,error_code=null"
                + " where id=? and status='PROCESSING' and execution_token=?",
            original.get("id"),
            id,
            token);
      else if (Set.of("RATE_LIMITED", "RATE_LIMIT_UNAVAILABLE").contains(problem.code))
        s.db.update(
            "update ai_booking_proposal set"
                + " error_code=?,processing_until=date_add(current_timestamp(6),interval 3 second)"
                + " where id=? and status='PROCESSING' and execution_token=?",
            problem.code,
            id,
            token);
      else
        s.db.update(
            "update ai_booking_proposal set"
                + " status='FAILED',error_code=?,finished_at=current_timestamp(6),processing_until=null"
                + " where id=? and status='PROCESSING' and execution_token=?",
            problem.code,
            id,
            token);
    } catch (Exception unknown) {
      code = "BOOKING_RESULT_UNKNOWN";
      s.db.update(
          "update ai_booking_proposal set"
              + " error_code='BOOKING_RESULT_UNKNOWN',processing_until=date_add(current_timestamp(6),interval"
              + " 5 second) where id=? and status='PROCESSING' and execution_token=?",
          id,
          token);
    } finally {
      LoggerFactory.getLogger(getClass())
          .info(
              "node=BookingExecute traceId={} proposalId={} code={} elapsedMs={}",
              token,
              id,
              code,
              (System.nanoTime() - started) / 1_000_000);
    }
    return get(id);
  }

  public Map<String, Object> reject(String id) {
    tx.executeWithoutResult(
        status -> {
          var row = owned(id, true);
          Problem.require(
              !"PROCESSING".equals(row.get("status")), "PROPOSAL_PROCESSING", "请求正在提交，不能撤回；请先查询结果");
          Problem.require(
              !"SUCCEEDED".equals(row.get("status")),
              "PROPOSAL_ALREADY_HANDLED",
              "已提交成功，请通过我的参与管理资格");
          s.db.update(
              "update ai_booking_proposal set"
                  + " status='REJECTED',error_code='PROPOSAL_REJECTED',finished_at=current_timestamp(6)"
                  + " where id=? and status='PENDING'",
              id);
        });
    return get(id);
  }

  Map<String, Object> view(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", row.get("id"));
    result.put("proposalId", row.get("id"));
    result.put("conversationId", row.get("conversation_id"));
    result.put("status", row.get("status"));
    result.put("entryType", row.get("entry_type"));
    result.put("expiresAt", row.get("expires_at"));
    result.put("serverTime", s.now());
    result.put("processingUntil", row.get("processing_until"));
    result.put("errorCode", row.get("error_code"));
    result.put("session", queries.current(Store.id(row, "session_id")));
    result.put("people", 1);
    result.put(
        "type", "PENDING".equals(row.get("status")) ? "PENDING_CONFIRMATION" : "BOOKING_RESULT");
    if (row.get("participation_id") != null)
      result.put(
          "participation",
          s.one(
              "select id,session_id,status,queue_seq,confirm_deadline from participation where id=?"
                  + " and user_id=?",
              row.get("participation_id"),
              access.user()));
    result.put("canConfirm", "PENDING".equals(row.get("status")));
    result.put(
        "canRetry",
        "PROCESSING".equals(row.get("status"))
            && row.get("processing_until") != null
            && s.now().isBefore(Store.time(row, "expires_at"))
            && !s.now().isBefore(Store.time(row, "processing_until")));
    return result;
  }

  public String conversation(String id) {
    return owned(id, false).get("conversation_id").toString();
  }
}
