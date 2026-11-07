package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static com.alibaba.cloud.ai.graph.StateGraph.*;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import cn.culturehub.*;
import com.alibaba.cloud.ai.graph.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Fifteen real nodes and conditional routes. No booking dependency or write tool. */
@Service
public class GuideGraphV2 {
  final GuideIntent intent;
  final GuideTools tools;
  final GuideKnowledge rag;
  final GuidePlanner planner;
  final GuideLanguage language;
  final CompiledGraph graph;
  final LettuceGuideSaver saver;

  public GuideGraphV2(
      GuideIntent intent,
      GuideTools tools,
      GuideKnowledge rag,
      GuidePlanner planner,
      GuideLanguage language) {
    this(intent, tools, rag, planner, language, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public GuideGraphV2(
      GuideIntent intent,
      GuideTools tools,
      GuideKnowledge rag,
      GuidePlanner planner,
      GuideLanguage language,
      LettuceGuideSaver saver) {
    this.intent = intent;
    this.tools = tools;
    this.rag = rag;
    this.planner = planner;
    this.language = language;
    this.saver = saver;
    try {
      var g = new StateGraph(() -> Map.of(), new GuideSerializer());
      for (String name :
          List.of(
              "IntentExtract",
              "InformationNeedPlan",
              "ToolPlan",
              "ToolExecute",
              "ToolCheck",
              "RetrievalPlan",
              "Retrieve",
              "Rerank",
              "EvidenceGrade",
              "InformationGate",
              "CandidateOptimize",
              "ProposalGenerate",
              "ProposalVerifier",
              "RepairRouter",
              "DecisionGate"))
        g.addNode(
            name,
            node_async(
                raw -> {
                  Turn s = (Turn) raw.value("guide").orElseThrow();
                  run(name, s);
                  return Map.of("guide", s);
                }));
      g.addEdge(START, "IntentExtract");
      route(
          g,
          "IntentExtract",
          s -> Set.of("CLARIFICATION", "CANCELLED").contains(s.phase) ? "stop" : "needs",
          Map.of("stop", END, "needs", "InformationNeedPlan"));
      route(
          g,
          "InformationNeedPlan",
          s ->
              s.toolCheckResult != null && s.toolCheckResult.status() == ResultStatus.SKIPPED
                  ? "rag"
                  : "tools",
          Map.of("rag", "RetrievalPlan", "tools", "ToolPlan"));
      g.addEdge("ToolPlan", "ToolExecute");
      g.addEdge("ToolExecute", "ToolCheck");
      route(
          g,
          "ToolCheck",
          s ->
              s.failureType != Failure.NONE
                  ? "repair"
                  : s.toolCheckResult.status() == ResultStatus.EMPTY
                      ? "stop"
                      : s.retrievalNeeds.isEmpty() ? "gate" : "rag",
          Map.of(
              "repair",
              "RepairRouter",
              "stop",
              END,
              "gate",
              "InformationGate",
              "rag",
              "RetrievalPlan"));
      g.addEdge("RetrievalPlan", "Retrieve");
      g.addEdge("Retrieve", "Rerank");
      g.addEdge("Rerank", "EvidenceGrade");
      g.addEdge("EvidenceGrade", "InformationGate");
      route(
          g,
          "InformationGate",
          s ->
              s.failureType != Failure.NONE
                  ? "repair"
                  : s.phase.equals("KNOWLEDGE_BLOCKED")
                      ? "stop"
                      : s.requirements.intentType() == IntentType.VENUE_QA
                          ? "generate"
                          : "optimize",
          Map.of(
              "repair",
              "RepairRouter",
              "stop",
              END,
              "generate",
              "ProposalGenerate",
              "optimize",
              "CandidateOptimize"));
      route(
          g,
          "CandidateOptimize",
          s -> s.activityCombinations.isEmpty() ? "repair" : "generate",
          Map.of("repair", "RepairRouter", "generate", "ProposalGenerate"));
      route(
          g,
          "ProposalGenerate",
          s -> s.failureType != Failure.NONE ? "repair" : "verify",
          Map.of("repair", "RepairRouter", "verify", "ProposalVerifier"));
      route(
          g,
          "ProposalVerifier",
          s -> s.verificationResult.passed() ? "gate" : "repair",
          Map.of("gate", "DecisionGate", "repair", "RepairRouter"));
      var repairs = new LinkedHashMap<String, String>();
      for (String n :
          List.of(
              "ToolPlan", "ToolExecute", "RetrievalPlan", "CandidateOptimize", "ProposalGenerate"))
        repairs.put(n, n);
      repairs.put("stop", END);
      route(g, "RepairRouter", s -> s.repairTarget, repairs);
      g.addEdge("DecisionGate", END);
      var config = CompileConfig.builder().recursionLimit(55);
      if (saver != null)
        config.saverConfig(
            com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig.builder()
                .register(saver)
                .build());
      graph = g.compile(config.build());
    } catch (Exception error) {
      throw new IllegalStateException("Cannot compile V2 guide graph", error);
    }
  }

  interface Next {
    String apply(Turn s);
  }

  void route(StateGraph g, String node, Next next, Map<String, String> routes) throws Exception {
    g.addConditionalEdges(
        node, edge_async(raw -> next.apply((Turn) raw.value("guide").orElseThrow())), routes);
  }

  public Turn execute(Turn state, Consumer<Turn> checkpoint) {
    var config =
        RunnableConfig.builder()
            .threadId(
                state.identity.ownerUserId()
                    + ":"
                    + state.identity.conversationId()
                    + ":"
                    + state.identity.turnId())
            .build();
    var result =
        graph.stream(Map.of("guide", state), config)
            .doOnNext(
                output -> checkpoint.accept((Turn) output.state().value("guide").orElseThrow()))
            .blockLast(
                Duration.ofMillis(
                    Math.max(1, state.deadlineEpochMillis - System.currentTimeMillis())));
    return result == null ? state : (Turn) result.state().value("guide").orElseThrow();
  }

  void run(String name, Turn s) {
    long started = System.nanoTime();
    s.currentNode = name;
    s.visitedNodes.add(name);
    if (s.cancelled || Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
    if (System.currentTimeMillis() > s.deadlineEpochMillis)
      throw new Problem("AI_TIMEOUT", "规划超时，未提交预约", 503);
    try {
      switch (name) {
        case "IntentExtract" -> {
          if (s.identity.latestUserMessage().trim().matches("取消|结束|算了|停止|不用了")) {
            s.phase = "CANCELLED";
            break;
          }
          s.requirements = intent.merge(s.requirements, language.understand(s), s.identity, 10);
          s.reuseBusinessFacts =
              s.requirements.wordingOnly()
                  && !s.candidateSessions.isEmpty()
                  && s.identity.latestUserMessage().matches("(?s).*(文案|语气|简短|口语).*")
                  && !s.identity.latestUserMessage().matches("(?s).*(明天|周|早|晚|场馆|必须|换活动).*");
          s.phase =
              s.requirements.clarificationQuestion() != null
                      && !s.requirements.clarificationQuestion().isBlank()
                  ? "CLARIFICATION"
                  : "UNDERSTOOD";
        }
        case "InformationNeedPlan" -> {
          boolean qa = s.requirements.intentType() == IntentType.VENUE_QA;
          s.informationNeeds =
              qa
                  ? List.of(new Need("knowledge", "核对公开规则", "venueId（场馆规则适用）", true))
                  : List.of(
                      new Need("business", "公开场次、余量和本人资格", null, true),
                      new Need("knowledge", "必要规则或活动背景", "venueId/activityId", false),
                      new Need("planning", "同馆、时间、间隔、数量、已有预约冲突", "business", true));
          if (qa && s.requirements.hardConstraints().venue() == null) {
            s.toolCheckResult = new ToolCheckResult(ResultStatus.SKIPPED, Failure.NONE, List.of());
            s.retrievalNeeds =
                List.of(
                    new RetrievalNeed(
                        "global", s.identity.latestUserMessage(), null, null, "BACKGROUND", true));
          }
        }
        case "ToolPlan" -> {
          s.toolPlan = tools.plan(s);
          s.failureType = Failure.NONE;
        }
        case "ToolExecute" -> {
          if (s.reuseBusinessFacts) break;
          var previousResults = s.toolResults;
          s.toolResults = new ArrayList<>();
          s.candidateSessions = new ArrayList<>();
          s.existingBookings = new ArrayList<>();
          for (var call : s.toolPlan) {
            var cached =
                s.repairCounters.getOrDefault("business", 0) > 0
                    ? previousResults.stream()
                        .filter(
                            r ->
                                r.tool() == call.name()
                                    && (r.status() == ResultStatus.SUCCESS
                                        || r.status() == ResultStatus.EMPTY))
                        .findFirst()
                    : Optional.<ToolResult>empty();
            var result = cached.orElseGet(() -> tools.execute(call, s));
            s.toolResults.add(result);
            s.candidateSessions.addAll(result.sessions());
            s.existingBookings.addAll(result.bookings());
          }
        }
        case "ToolCheck" -> {
          s.toolCheckResult = tools.check(s);
          s.failureType = s.toolCheckResult.failureType();
          s.repairTarget =
              s.failureType == Failure.TOOL_EXECUTION_FAILED ? "ToolExecute" : "ToolPlan";
          if (s.toolCheckResult.status() == ResultStatus.EMPTY) s.phase = "NO_RESULTS";
          if (s.failureType == Failure.NONE) {
            boolean qa = s.requirements.intentType() == IntentType.VENUE_QA;
            boolean age = s.requirements.hardConstraints().childAge() != null;
            var ids = new TreeSet<Long>();
            s.candidateSessions.forEach(c -> ids.add(c.venueId()));
            s.toolResults.forEach(r -> r.venues().forEach(v -> ids.add(v.venueId())));
            var needs = new ArrayList<RetrievalNeed>();
            if (qa || age)
              for (long id : ids.stream().limit(6).toList())
                needs.add(
                    new RetrievalNeed(
                        "rules:" + id,
                        s.identity.latestUserMessage(),
                        id,
                        null,
                        age ? "AGE_RULE" : "VENUE_RULE",
                        qa
                            || s.identity.latestUserMessage().contains("必须")
                            || s.identity.latestUserMessage().contains("确认")));
            s.retrievalNeeds = needs;
            if (!qa && s.identity.latestUserMessage().matches("(?s).*(背景|历史|介绍).*")) {
              var seen = new HashSet<Long>();
              for (var candidate : s.candidateSessions)
                if (needs.size() < 6 && seen.add(candidate.activityId()))
                  needs.add(
                      new RetrievalNeed(
                          "activity:" + candidate.activityId(),
                          "活动背景：" + candidate.title(),
                          candidate.venueId(),
                          candidate.activityId(),
                          "ACTIVITY_BACKGROUND",
                          false));
            }
          }
        }
        case "RetrievalPlan" -> {
          s.retrievalPlan = rag.plan(s);
          s.failureType = Failure.NONE;
        }
        case "Retrieve" -> {
          try {
            s.retrievedDocuments = rag.retrieve(s);
            s.knowledgeStatus = ResultStatus.SUCCESS;
          } catch (Exception e) {
            s.knowledgeStatus = ResultStatus.FAILED;
            s.retrievedDocuments = List.of();
          }
        }
        case "Rerank" ->
            s.rerankedDocuments = rag.rerank(s.retrievedDocuments, s.identity.latestUserMessage());
        case "EvidenceGrade" -> {
          if (s.knowledgeStatus == ResultStatus.FAILED)
            s.evidenceGrade =
                s.retrievalNeeds.stream()
                    .map(
                        n ->
                            new Grade(
                                n.questionId(),
                                GradeStatus.UNAVAILABLE,
                                List.of(),
                                List.of(),
                                List.of(n.question()),
                                "知识检索不可用"))
                    .toList();
          else s.evidenceGrade = rag.grade(s);
          s.supportedClaims =
              s.evidenceGrade.stream().flatMap(g -> g.supportedFacts().stream()).toList();
          s.unresolvedKnowledge =
              s.evidenceGrade.stream().flatMap(g -> g.unresolvedQuestions().stream()).toList();
        }
        case "InformationGate" -> {
          s.failureType = Failure.NONE;
          s.phase = "INFORMATION_READY";
          boolean blocked =
              s.retrievalNeeds.stream()
                  .anyMatch(
                      n ->
                          n.required()
                              && s.evidenceGrade.stream()
                                  .noneMatch(
                                      g ->
                                          g.questionId().equals(n.questionId())
                                              && g.status() == GradeStatus.SUPPORTED));
          if (blocked) {
            s.phase = "KNOWLEDGE_BLOCKED";
            if (s.evidenceGrade.stream().noneMatch(g -> g.status() == GradeStatus.UNAVAILABLE)
                && s.repairCounters.getOrDefault("RetrievalPlan", 0) < 1) {
              s.failureType = Failure.RETRIEVAL_INSUFFICIENT;
              s.repairTarget = "RetrievalPlan";
            }
          }
        }
        case "CandidateOptimize" -> {
          s.failureType = Failure.NONE;
          s.activityCombinations = planner.optimize(s);
          if (s.activityCombinations.isEmpty()) {
            s.failureType = Failure.NO_VALID_COMBINATION;
            s.repairTarget = "CandidateOptimize";
            s.phase = "NO_VALID_COMBINATION";
          }
        }
        case "ProposalGenerate" -> {
          try {
            s.failureType = Failure.NONE;
            s.proposal = language.generate(s);
          } catch (Problem e) {
            s.failureType = Failure.OUTPUT_FORMAT_INVALID;
            s.repairTarget = "ProposalGenerate";
          }
        }
        case "ProposalVerifier" -> {
          s.verificationResult = planner.verify(s, s.proposal);
          if (!s.verificationResult.passed()) {
            var error = s.verificationResult.errors().get(0);
            s.failureType = error.errorType();
            s.repairTarget = error.repairTarget();
          }
        }
        case "RepairRouter" -> {
          s.repairTarget = planner.repair(s);
        }
        case "DecisionGate" -> s.phase = "VERIFIED";
        default -> throw new IllegalArgumentException("Unknown node");
      }
    } finally {
      LoggerFactory.getLogger(getClass())
          .info(
              "traceId={} node={} errorType={} elapsedMs={}",
              s.identity.traceId(),
              name,
              s.failureType,
              (System.nanoTime() - started) / 1000000);
    }
  }

  /** Internal native-cursor resume. HTTP callers must first claim/check the MySQL plan version. */
  public Turn resume(Turn identity, Consumer<Turn> observer) {
    if (saver == null) throw new IllegalStateException("No persistent saver");
    var config =
        RunnableConfig.builder()
            .threadId(
                identity.identity.ownerUserId()
                    + ":"
                    + identity.identity.conversationId()
                    + ":"
                    + identity.identity.turnId())
            .build();
    var saved = saver.get(config).orElseThrow();
    config =
        RunnableConfig.builder(config)
            .checkPointId(saved.getId())
            .nextNode(saved.getNextNodeId())
            .build();
    var state = (Turn) saved.getState().get("guide");
    if (!state.identity.equals(identity.identity))
      throw new Problem("PLAN_SUPERSEDED", "检查点归属不一致", 409);
    if (System.currentTimeMillis() > state.deadlineEpochMillis)
      throw new Problem("AI_TIMEOUT", "本轮已超时，请重新规划", 409);
    var output =
        graph.stream(Map.of("guide", state), config)
            .doOnNext(node -> observer.accept((Turn) node.state().value("guide").orElseThrow()))
            .blockLast(
                Duration.ofMillis(
                    Math.max(1, state.deadlineEpochMillis - System.currentTimeMillis())));
    return output == null ? state : (Turn) output.state().value("guide").orElseThrow();
  }
}
