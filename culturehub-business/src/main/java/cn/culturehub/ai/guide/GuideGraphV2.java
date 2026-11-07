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
              s.phase.equals("CLARIFICATION")
                  ? "stop"
                  : s.toolCheckResult != null && s.toolCheckResult.status() == ResultStatus.SKIPPED
                      ? "rag"
                      : "tools",
          Map.of("stop", END, "rag", "RetrievalPlan", "tools", "ToolPlan"));
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
                      : s.requirements.intentType() != IntentType.VENUE_QA && !s.detailOnly
                          ? "optimize"
                          : s.retrievalNeeds.isEmpty() ? "gate" : "rag",
          Map.of(
              "repair",
              "RepairRouter",
              "stop",
              END,
              "gate",
              "InformationGate",
              "rag",
              "RetrievalPlan",
              "optimize",
              "CandidateOptimize"));
      route(
          g,
          "CandidateOptimize",
          s ->
              s.activityCombinations.isEmpty()
                  ? "repair"
                  : s.retrievalNeeds.isEmpty() ? "gate" : "rag",
          Map.of("repair", "RepairRouter", "gate", "InformationGate", "rag", "RetrievalPlan"));
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
                  : s.phase.equals("KNOWLEDGE_BLOCKED") ? "stop" : "generate",
          Map.of("repair", "RepairRouter", "stop", END, "generate", "ProposalGenerate"));
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
          var needs = GuideTools.needs(s);
          s.detailOnly =
              s.requirements.intentType() == IntentType.ACTIVITY_SEARCH
                  && GuideTools.requestedSession(s.identity.latestUserMessage()) != null
                  && !s.identity.latestUserMessage().matches("(?s).*(帮我预约|帮我报名|安排|规划).*");
          s.knowledgeQuestions = GuideKnowledgeQuestions.plan(s);
          var all = new ArrayList<>(needs);
          for (var question : s.knowledgeQuestions)
            all.add(
                new Need(
                    question.id(),
                    question.question(),
                    needs.isEmpty() ? null : needs.get(0).id(),
                    question.required()));
          if (s.requirements.intentType() != IntentType.VENUE_QA && !s.detailOnly)
            all.add(new Need("planning", "按业务时间与硬条件核验组合", needs.get(0).id(), true));
          s.informationNeeds = List.copyOf(all);
          s.toolResults =
              s.toolResults.stream()
                  .filter(
                      r ->
                          needs.stream()
                              .anyMatch(
                                  n ->
                                      n.id().equals(r.informationNeedId())
                                          && n.arguments().equals(r.arguments())))
                  .toList();
          if (needs.isEmpty()) {
            s.toolCheckResult = new ToolCheckResult(ResultStatus.SKIPPED, Failure.NONE, List.of());
            GuideEvidenceScope.reset(s);
            if (s.retrievalNeeds.isEmpty()) s.phase = "CLARIFICATION";
          }
        }
        case "ToolPlan" -> {
          s.toolPlan = tools.plan(s);
          s.failureType = Failure.NONE;
        }
        case "ToolExecute" -> {
          var results = new LinkedHashMap<String, ToolResult>();
          s.toolResults.forEach(result -> results.put(result.informationNeedId(), result));
          for (var call : s.toolPlan)
            results.put(call.informationNeedId(), tools.execute(call, s).forCall(call));
          s.toolResults = List.copyOf(results.values());
          s.candidateSessions = s.toolResults.stream().flatMap(r -> r.sessions().stream()).toList();
          s.existingBookings = s.toolResults.stream().flatMap(r -> r.bookings().stream()).toList();
        }
        case "ToolCheck" -> {
          s.toolCheckResult = tools.check(s);
          s.failureType = s.toolCheckResult.failureType();
          s.repairTarget =
              s.failureType == Failure.TOOL_EXECUTION_FAILED ? "ToolExecute" : "ToolPlan";
          if (s.toolCheckResult.status() == ResultStatus.EMPTY) s.phase = "NO_RESULTS";
          if (s.failureType == Failure.NONE
              && (s.requirements.intentType() == IntentType.VENUE_QA || s.detailOnly))
            GuideEvidenceScope.reset(s);
        }
        case "RetrievalPlan" -> {
          s.retrievalPlan = rag.plan(s);
          s.failureType = Failure.NONE;
        }
        case "Retrieve" -> {
          try {
            s.retrievedDocuments = rag.retrieve(s);
            s.knowledgeStatus =
                s.unavailableQuestions.isEmpty() ? ResultStatus.SUCCESS : ResultStatus.PARTIAL;
          } catch (Exception e) {
            s.knowledgeStatus = ResultStatus.FAILED;
            s.retrievedDocuments = List.of();
          }
        }
        case "Rerank" -> s.rerankedDocuments = rag.rerank(s);
        case "EvidenceGrade" -> {
          s.evidenceGrade = rag.grade(s);
          s.supportedClaims =
              s.evidenceGrade.stream().flatMap(g -> g.supportedFacts().stream()).toList();
          s.unresolvedKnowledge =
              s.evidenceGrade.stream().flatMap(g -> g.unresolvedQuestions().stream()).toList();
        }
        case "InformationGate" -> {
          s.failureType = Failure.NONE;
          s.phase = "INFORMATION_READY";
          s.evidenceBindings = GuideEvidenceScope.bind(s);
          var blocked =
              s.evidenceBindings.stream()
                  .filter(binding -> binding.required() && !binding.satisfiesRequirement())
                  .toList();
          if (!blocked.isEmpty()) {
            s.phase = "KNOWLEDGE_BLOCKED";
            if (blocked.stream().noneMatch(b -> b.status() == GradeStatus.UNAVAILABLE)
                && s.repairCounters.getOrDefault("RetrievalPlan", 0) < 1) {
              s.failureType =
                  blocked.stream().anyMatch(b -> b.status() == GradeStatus.CONFLICTING)
                      ? Failure.EVIDENCE_CONFLICT
                      : Failure.RETRIEVAL_INSUFFICIENT;
              s.repairTarget = "RetrievalPlan";
            }
          }
        }
        case "CandidateOptimize" -> {
          s.failureType = Failure.NONE;
          s.activityCombinations = planner.optimize(s);
          s.evidenceCandidateIndex = 0;
          GuideEvidenceScope.reset(s);
          if (s.activityCombinations.isEmpty()) {
            s.failureType = Failure.NO_VALID_COMBINATION;
            s.repairTarget = "CandidateOptimize";
            s.phase = "NO_VALID_COMBINATION";
          }
        }
        case "ProposalGenerate" -> {
          try {
            s.failureType = Failure.NONE;
            s.proposal =
                s.requirements.intentType() == IntentType.VENUE_QA || s.detailOnly
                    ? planner.informationalProposal(s)
                    : language.generate(s);
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
          if ("RetrievalPlan".equals(s.repairTarget)) GuideEvidenceScope.advance(s);
          if ("ToolExecute".equals(s.repairTarget)) s.toolPlan = tools.plan(s);
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
