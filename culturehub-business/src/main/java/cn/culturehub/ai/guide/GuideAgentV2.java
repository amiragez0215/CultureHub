package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class GuideAgentV2 {
  final GuideGraphV2 graph;
  final GuidePersistence persistence;
  final Access access;
  final Store store;

  public GuideAgentV2(GuideGraphV2 g, GuidePersistence p, Access a, Store s) {
    graph = g;
    persistence = p;
    access = a;
    store = s;
  }

  public String conversation() {
    return persistence.create();
  }

  public void check(String id) {
    persistence.owned(id, false);
  }

  public Map<String, Object> message(
      String conversation, String message, AiGuideGraph.TurnEvents events) {
    Problem.require(
        message != null && !message.isBlank() && message.length() <= 2000,
        "INVALID_QUESTION",
        "问题长度应为1至2000字");
    long owner = access.user();
    int version = persistence.begin(conversation);
    var state = new Turn();
    String turn = UUID.randomUUID().toString();
    state.identity =
        new Identity(conversation, turn, UUID.randomUUID().toString(), owner, message, store.now());
    state.currentPlanVersion = version;
    state.deadlineEpochMillis = System.currentTimeMillis() + 90000;
    try {
      Turn prior = persistence.previous(conversation);
      state.requirements = prior == null ? null : prior.requirements;
      if (prior != null) {
        state.previousPlan = prior.proposal;
        state.previousKnowledgeQuestions = prior.knowledgeQuestions;
        if (Duration.between(prior.identity.currentTime(), state.identity.currentTime())
                .getSeconds()
            < 30) {
          state.candidateSessions = prior.candidateSessions;
          state.existingBookings = prior.existingBookings;
          state.toolResults = prior.toolResults;
        }
      }
      state =
          graph.execute(
              state,
              s -> {
                if (events != null) {
                  if (events.isCancelled()) throw new java.util.concurrent.CancellationException();
                  events.emit("status", Map.of("node", s.currentNode));
                }
                persistence.checkpoint(s);
              });
      if (events != null && events.isCancelled())
        throw new java.util.concurrent.CancellationException();
      access.owner(owner);
      var candidates = persistence.save(state);
      String answer =
          switch (state.phase) {
            case "VERIFIED" ->
                state.requirements.intentType() == IntentType.VENUE_QA
                    ? "请查看下方公开知识来源；无法确认的规则请向场馆核实。"
                    : "已生成可行活动安排。每个场次需分别选择并确认；当前尚未预约。";
            case "CLARIFICATION" ->
                Objects.requireNonNullElse(
                    state.requirements.clarificationQuestion(), "请说明要查询哪个场馆。");
            case "CANCELLED" -> "已停止本轮规划，未提交预约。";
            case "KNOWLEDGE_BLOCKED" -> "现有证据不足以确认您要求的场馆规则，未将该条件标记为满足。";
            case "NO_RESULTS" ->
                state.toolResults.stream().anyMatch(r -> "VENUE_NOT_FOUND".equals(r.errorType()))
                    ? "未找到指定场馆，请核对场馆名称。"
                    : "当前条件没有匹配的公开场次，可修改日期或活动条件。";
            default -> "当前约束下未能生成经过验证的方案，请调整条件后重试。";
          };
      persistence.remember(state, answer);
      var result = new LinkedHashMap<String, Object>();
      result.put("traceId", state.identity.traceId());
      result.put("phase", state.phase.equals("VERIFIED") ? "RECOMMENDATION" : state.phase);
      result.put(
          "type",
          state.phase.equals("VERIFIED")
              ? "RECOMMENDATION"
              : state.phase.equals("CLARIFICATION") ? "CLARIFICATION" : "NO_RESULT");
      result.put("explanation", answer);
      result.put("candidates", candidates);
      result.put("plan", state.phase.equals("VERIFIED") ? state.proposal : null);
      result.put("planVersion", version);
      result.put(
          "sources",
          state.rerankedDocuments.stream().map(e -> e.source() + "#" + e.chunkId()).toList());
      result.put("evidence", state.evidenceGrade);
      result.put("evidenceBindings", state.evidenceBindings);
      result.put("businessAnswers", state.businessAnswers);
      result.put(
          "sessionDetails",
          state.detailOnly && state.phase.equals("VERIFIED") ? state.candidateSessions : List.of());
      result.put("knowledgeNotice", String.join("；", state.unresolvedKnowledge));
      result.put(
          "preferences",
          state.requirements == null ? Map.of() : state.requirements.hardConstraints());
      result.put("notice", "一个账号一个名额；候补需单独授权。名额以Java最终提交为准。");
      result.put("visitedNodes", state.visitedNodes);
      result.put(
          "usage",
          Map.of(
              "llmCalls",
              state.llmCalls,
              "toolCalls",
              state.toolCalls,
              "nativeToolCalls",
              state.nativeToolCalls,
              "promptTokens",
              state.promptTokens,
              "completionTokens",
              state.completionTokens));
      result.put("knowledgeStatus", state.knowledgeStatus);
      if (events != null) {
        for (int offset = 0; offset < answer.length(); offset += 16)
          events.emit(
              "delta",
              Map.of("text", answer.substring(offset, Math.min(answer.length(), offset + 16))));
        events.emit("proposal", Map.of("candidates", candidates));
      }
      return result;
    } catch (RuntimeException error) {
      for (Throwable cause = error; cause != null; cause = cause.getCause()) {
        if (cause instanceof Problem p) throw p;
        if (cause instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
        if (cause instanceof java.util.concurrent.TimeoutException)
          throw new Problem("AI_TIMEOUT", "规划超时；未提交预约", 503);
      }
      throw new Problem("AI_FAILED", "规划暂不可用；未提交预约", 503);
    } finally {
      persistence.release(conversation, version);
    }
  }

  public Map<String, Object> select(String conversation, String candidate, String mode) {
    return persistence.select(conversation, candidate, mode);
  }

  public Map<String, Object> confirm(String id) {
    return persistence.confirm(id);
  }

  public Object recover(String conversation) {
    check(conversation);
    var state = persistence.previous(conversation);
    var result = new LinkedHashMap<String, Object>();
    result.put("plan", state == null ? null : state.proposal);
    result.put("candidates", persistence.cards(conversation));
    result.put("bookingProposal", persistence.pendingProposal(conversation));
    result.put("explanation", "已恢复保存的规划，名额仍以确认时查询为准。");
    return result;
  }

  public Object conversations() {
    return persistence.conversations();
  }
}
