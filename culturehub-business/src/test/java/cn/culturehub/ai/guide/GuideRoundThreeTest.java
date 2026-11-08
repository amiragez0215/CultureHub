package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.culturehub.*;
import cn.culturehub.AiGuideGraph.Preferences;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class GuideRoundThreeTest {
  private final GuideKnowledge rag =
      new GuideKnowledge(mock(ObjectProvider.class), mock(Store.class), mock(Access.class));
  private final GuidePlanner planner = new GuidePlanner();
  private final GuideEvidenceScopeTest fixtures = new GuideEvidenceScopeTest();
  private static final String ACCOMPANIED = "6～12 岁儿童可以参加，但必须由监护人陪同。";
  private static final String INDEPENDENT = "允许儿童独自参加，无需监护人陪同。";

  @Test
  void consultationReturnsConfirmedNegativeWithoutRepairAndExposesItsSource() {
    var turn = run("8 岁能独自入场吗？", IntentType.VENUE_QA, List.of(), n -> List.of(doc(n, ACCOMPANIED)));
    assertEquals("VERIFIED", turn.phase);
    assertTrue(turn.proposal.orderedSessionIds().isEmpty());
    assertFalse(turn.visitedNodes.contains("RepairRouter"));
    assertEquals(List.of("rule-1"), turn.proposal.evidenceReferences());
    var grade = turn.evidenceGrade.get(0);
    assertEquals(GradeStatus.SUPPORTED, grade.status());
    assertEquals(RequirementVerdict.NOT_APPLICABLE, grade.requirementVerdict());
    assertEquals(Conclusion.ACCOMPANIMENT_REQUIRED, grade.conclusion());
    assertTrue(grade.unresolvedQuestions().isEmpty());
    assertTrue(planner.verify(turn, turn.proposal).passed());
    var view = new GuideAgentV2(null, null, null, null).evidence(turn).get(0);
    assertEquals(GradeStatus.SUPPORTED, view.get("evidenceStatus"));
    assertEquals(Conclusion.ACCOMPANIMENT_REQUIRED, view.get("conclusion"));
    assertEquals(List.of("虚构测试规则#rule-1"), view.get("sources"));
    assertTrue(view.get("explanation").toString().contains("监护人陪同"));
  }

  @Test
  void requiredIndependentAdmissionSkipsRejectedVenueAndChecksNextCandidate() {
    var turn =
        run(
            "必须找一个允许孩子独自参加的活动",
            IntentType.ACTIVITY_PLAN,
            List.of(fixtures.session(1, 1, "第一活动"), fixtures.session(2, 2, "第二活动")),
            n -> List.of(doc(n, n.venueId() == 1 ? "儿童须有家长陪同入场。" : INDEPENDENT)));
    assertEquals("VERIFIED", turn.phase);
    assertEquals(List.of(2L), turn.proposal.orderedSessionIds());
    assertEquals(List.of("rule-2"), turn.proposal.evidenceReferences());
    assertEquals(RequirementVerdict.MATCHED, turn.evidenceGrade.get(0).requirementVerdict());
    assertEquals(2, turn.visitedNodes.stream().filter("Retrieve"::equals).count());
  }

  @Test
  void confirmedMismatchWithoutAlternativeDoesNotSearchAgain() {
    var turn =
        run(
            "必须找一个允许孩子独自参加的活动",
            IntentType.ACTIVITY_PLAN,
            List.of(fixtures.session(1, 1, "第一活动")),
            n -> List.of(doc(n, "儿童须有家长陪同入场。")));
    assertEquals("NO_VALID_COMBINATION", turn.phase);
    assertNull(turn.proposal);
    assertEquals(GradeStatus.SUPPORTED, turn.evidenceGrade.get(0).status());
    assertEquals(RequirementVerdict.NOT_MATCHED, turn.evidenceGrade.get(0).requirementVerdict());
    assertTrue(turn.unresolvedKnowledge.isEmpty());
    assertEquals(1, turn.visitedNodes.stream().filter("Retrieve"::equals).count());
    assertTrue(rag.plan(turn).isEmpty());
    var errors = planner.verify(turn, GuideEvidenceScopeTest.proposal(turn)).errors();
    assertTrue(errors.stream().anyMatch(e -> e.errorType() == Failure.HARD_CONSTRAINT_CONFLICT));
    assertTrue(errors.stream().noneMatch(e -> e.errorType() == Failure.RETRIEVAL_INSUFFICIENT));
  }

  @Test
  void planningWithAQuestionAboutCompanionsDoesNotRequireIndependentAdmission() {
    var turn =
        run(
            "请推荐一个活动，孩子能独自入场吗？",
            IntentType.ACTIVITY_SEARCH,
            List.of(fixtures.session(1, 1, "第一活动")),
            n -> List.of(doc(n, "儿童须有家长陪同入场。")));
    assertEquals("VERIFIED", turn.phase);
    assertEquals(List.of(1L), turn.proposal.orderedSessionIds());
    assertEquals(RequirementVerdict.NOT_APPLICABLE, turn.evidenceGrade.get(0).requirementVerdict());
    assertFalse(turn.visitedNodes.contains("RepairRouter"));
  }

  @Test
  void ageEligibilityKeepsExclusiveAndRecommendedRangesDistinct() {
    var s = state("8 岁能不能参加这个活动？", IntentType.VENUE_QA);
    var need = new RetrievalNeed("age", "参与年龄", 1L, 5L, "AGE_RULE", true);
    s.retrievalNeeds = List.of(need);
    s.rerankedDocuments = List.of(doc(need, "本活动仅限 10～12 岁儿童参加。"));
    var denied = rag.grade(s).get(0);
    assertEquals(Conclusion.DENIED, denied.conclusion());
    assertEquals(GradeStatus.SUPPORTED, denied.status());
    assertEquals(RequirementVerdict.NOT_APPLICABLE, denied.requirementVerdict());
    assertTrue(denied.unresolvedQuestions().isEmpty());
    assertEquals(10, denied.claims().get(0).minAge());
    assertEquals(12, denied.claims().get(0).maxAge());

    var hard =
        new RetrievalNeed(
            "age", "参与年龄", 1L, 5L, "AGE_RULE", true, RequirementTarget.AGE_ELIGIBILITY);
    s.retrievalNeeds = List.of(hard);
    assertEquals(RequirementVerdict.NOT_MATCHED, rag.grade(s).get(0).requirementVerdict());
    s.rerankedDocuments = List.of(doc(hard, "仅限现场报名，推荐 10～12 岁儿童参加。"));
    var recommended = rag.grade(s).get(0);
    assertEquals(GradeStatus.INSUFFICIENT, recommended.status());
    assertEquals(RequirementVerdict.UNKNOWN, recommended.requirementVerdict());
    assertEquals(Conclusion.RECOMMENDED, recommended.claims().get(0).conclusion());
    s.rerankedDocuments = List.of(doc(hard, "活动介绍：我们将了解当地文化历史。"));
    assertEquals(GradeStatus.INSUFFICIENT, rag.grade(s).get(0).status());
    s.rerankedDocuments = List.of(doc(hard, ACCOMPANIED));
    var allowed = rag.grade(s).get(0);
    assertEquals(Conclusion.ALLOWED, allowed.conclusion());
    assertEquals("须有家长或监护人陪同", allowed.claims().get(0).condition());
  }

  @Test
  void consultationAboutClosedHoursIsAnAnswer() {
    var turn =
        run(
            "测试文化馆周日开门吗？",
            IntentType.VENUE_QA,
            List.of(),
            n -> List.of(doc(n, "周日闭馆，周一开放 09:00～17:00。")));
    assertEquals("VERIFIED", turn.phase);
    assertEquals(Conclusion.CLOSED, turn.evidenceGrade.get(0).conclusion());
    assertEquals(RequirementVerdict.NOT_APPLICABLE, turn.evidenceGrade.get(0).requirementVerdict());
    assertFalse(turn.visitedNodes.contains("RepairRouter"));
  }

  @Test
  void conditionalPermissionDoesNotClaimAnUnknownAgeRequirementWasMet() {
    var s = state("必须找一个允许孩子独自参加的活动", IntentType.ACTIVITY_PLAN);
    var need =
        new RetrievalNeed(
            "guardian",
            "能否独自参加",
            1L,
            null,
            "GUARDIAN_RULE",
            true,
            RequirementTarget.INDEPENDENT_ADMISSION);
    s.retrievalNeeds = List.of(need);
    s.rerankedDocuments = List.of(doc(need, "允许符合年龄要求的儿童独自参加指定活动。"));
    var grade = rag.grade(s).get(0);
    assertEquals(GradeStatus.SUPPORTED, grade.status());
    assertEquals(Conclusion.INDEPENDENT_ALLOWED, grade.conclusion());
    assertEquals("须符合年龄要求", grade.claims().get(0).condition());
    assertEquals(RequirementVerdict.UNKNOWN, grade.requirementVerdict());
    s.evidenceGrade = List.of(grade);
    assertTrue(rag.plan(s).isEmpty());
  }

  @Test
  void agentReturnsKnowledgeAnswerWithNoBookingCardsOrProposalEvent() {
    String question = "8 岁能独自入场吗？";
    var s = state(question, IntentType.VENUE_QA);
    var graph = graph(s, List.of(), n -> List.of(doc(n, ACCOMPANIED)));
    var persistence = mock(GuidePersistence.class);
    when(persistence.begin("c")).thenReturn(1);
    when(persistence.save(any())).thenReturn(List.of());
    var access = mock(Access.class);
    when(access.user()).thenReturn(1L);
    var store = mock(Store.class);
    when(store.now()).thenReturn(s.identity.currentTime());
    var events = mock(AiGuideGraph.TurnEvents.class);
    var reply = new GuideAgentV2(graph, persistence, access, store).message("c", question, events);
    assertEquals("KNOWLEDGE_ANSWER", reply.get("phase"));
    assertEquals(List.of(), reply.get("candidates"));
    assertEquals(List.of("虚构测试规则#rule-1"), reply.get("sources"));
    assertTrue(reply.get("explanation").toString().contains("必须由监护人陪同"));
    verify(events, never()).emit(eq("proposal"), any());
    verify(persistence, never()).confirm(any());
  }

  @Test
  void conflictUnavailableAndInsufficientRemainDistinctAndOnlyInsufficientIsRewritten() {
    var turn =
        run(
            "测试文化馆周日开门吗？",
            IntentType.VENUE_QA,
            List.of(),
            n -> List.of(doc(n, "周日闭馆。"), doc(n, "周日开放 09:00～17:00。")));
    assertEquals("KNOWLEDGE_BLOCKED", turn.phase);
    assertEquals(GradeStatus.CONFLICTING, turn.evidenceGrade.get(0).status());
    assertTrue(rag.plan(turn).isEmpty());
    turn.knowledgeStatus = ResultStatus.FAILED;
    turn.evidenceGrade = rag.grade(turn);
    assertEquals(GradeStatus.UNAVAILABLE, turn.evidenceGrade.get(0).status());
    assertTrue(rag.plan(turn).isEmpty());
    turn.knowledgeStatus = ResultStatus.SUCCESS;
    turn.rerankedDocuments = List.of();
    turn.evidenceGrade = rag.grade(turn);
    assertEquals(GradeStatus.INSUFFICIENT, turn.evidenceGrade.get(0).status());
    assertEquals(1, rag.plan(turn).size());
  }

  @Test
  void changingTheHardRuleReplansItWithoutUsingTheOldVerdict() {
    var first = state("必须找一个允许孩子独自参加的活动", IntentType.ACTIVITY_PLAN);
    var previous = GuideKnowledgeQuestions.plan(first);
    assertEquals(RequirementTarget.INDEPENDENT_ADMISSION, previous.get(0).target());
    var updated = state("改成可以由家长陪同，想问需要陪同吗？", IntentType.PLAN_REVISION);
    updated.previousKnowledgeQuestions = previous;
    var plan = GuideKnowledgeQuestions.plan(updated);
    assertEquals(RequirementTarget.NONE, plan.get(0).target());
    assertEquals(
        RequirementTarget.NONE,
        GuideKnowledgeQuestions.plan(state("推荐活动，必须确认 8 岁能不能参加", IntentType.ACTIVITY_SEARCH))
            .get(0)
            .target());
    assertEquals(
        RequirementTarget.AGE_ELIGIBILITY,
        GuideKnowledgeQuestions.plan(state("必须找一个 8 岁能参加的活动", IntentType.ACTIVITY_PLAN))
            .get(0)
            .target());
  }

  private Turn run(
      String message,
      IntentType intent,
      List<Session> sessions,
      Function<RetrievalNeed, List<Evidence>> answers) {
    var s = state(message, intent);
    return graph(s, sessions, answers).execute(s, ignored -> {});
  }

  private GuideGraphV2 graph(
      Turn s, List<Session> sessions, Function<RetrievalNeed, List<Evidence>> answers) {
    var intent = s.requirements.intentType();
    var language = mock(GuideLanguage.class);
    when(language.understand(any()))
        .thenReturn(
            new IntentPatch(
                intent,
                s.requirements.hardConstraints(),
                List.of(),
                null,
                1,
                false,
                240,
                null,
                false));
    when(language.generate(any()))
        .thenAnswer(call -> GuideEvidenceScopeTest.proposal(call.getArgument(0)));
    var tools = mock(GuideTools.class);
    when(tools.plan(any()))
        .thenReturn(
            List.of(
                new ToolCall(
                    intent == IntentType.VENUE_QA
                        ? ToolName.VENUE_INFORMATION
                        : ToolName.SEARCH_SESSIONS,
                    null,
                    null)));
    when(tools.execute(any(), any()))
        .thenAnswer(
            call ->
                new ToolResult(
                    ((ToolCall) call.getArgument(0)).name(),
                    ResultStatus.SUCCESS,
                    sessions,
                    List.of(),
                    intent == IntentType.VENUE_QA
                        ? List.of(new Venue(1L, "测试文化馆", "", ""))
                        : List.of(),
                    s.identity.currentTime(),
                    null));
    when(tools.check(any()))
        .thenReturn(new ToolCheckResult(ResultStatus.SUCCESS, Failure.NONE, List.of()));
    var controlled = spy(rag);
    doAnswer(
            call ->
                ((Turn) call.getArgument(0))
                    .retrievalPlan.stream().flatMap(q -> answers.apply(q.need()).stream()).toList())
        .when(controlled)
        .retrieve(any());
    return new GuideGraphV2(new GuideIntent(), tools, controlled, planner, language);
  }

  private Turn state(String message, IntentType type) {
    var s = new GuidePlanningTest().state(1, false);
    s.identity = new Identity("c", "turn", "trace", 1, message, GuidePlanningTest.NOW);
    s.requirements =
        new Requirements(
            type,
            new Preferences(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                type == IntentType.VENUE_QA ? "测试文化馆" : null,
                null,
                null),
            List.of(),
            null,
            1,
            false,
            240,
            10,
            0,
            null,
            false);
    s.deadlineEpochMillis = System.currentTimeMillis() + 90000;
    return s;
  }

  static Evidence doc(RetrievalNeed need, String text) {
    return new Evidence(
        "doc",
        "rule-" + need.venueId(),
        need.venueId(),
        need.activityId(),
        need.activityId() == null ? "VENUE" : "ACTIVITY",
        "虚构测试规则",
        need.knowledgeType(),
        "1",
        null,
        text,
        0.5,
        0,
        need.questionId());
  }
}
