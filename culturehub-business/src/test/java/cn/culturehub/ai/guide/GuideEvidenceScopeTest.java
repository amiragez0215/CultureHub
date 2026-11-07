package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.culturehub.AiGuideGraph.Preferences;
import java.util.*;
import org.junit.jupiter.api.Test;

class GuideEvidenceScopeTest {
  final GuidePlanningTest fixtures = new GuidePlanningTest();

  @Test
  void seventhVenueCannotBorrowEvidenceAndRepairOnlyChecksReplacement() {
    var state = fixtures.state(1, false);
    state.deadlineEpochMillis = System.currentTimeMillis() + 90000;
    state.identity =
        new Identity(
            "conversation", "turn", "trace", 1, "优先剪纸，必须确认8岁儿童能否参加", GuidePlanningTest.NOW);
    var tools = mock(GuideTools.class);
    var rag = mock(GuideKnowledge.class);
    var language = mock(GuideLanguage.class);
    when(language.understand(any()))
        .thenReturn(
            new IntentPatch(
                IntentType.ACTIVITY_SEARCH,
                new Preferences(null, null, null, null, null, null, null, null, 8),
                List.of("剪纸"),
                null,
                1,
                false,
                240,
                null,
                false));
    when(tools.plan(any())).thenReturn(List.of(new ToolCall(ToolName.SEARCH_SESSIONS, null, null)));
    var candidates =
        java.util.stream.LongStream.rangeClosed(1, 7)
            .mapToObj(id -> session(id, id, id == 7 ? "剪纸" : "传统文化"))
            .toList();
    when(tools.execute(any(), any()))
        .thenReturn(
            new ToolResult(
                ToolName.SEARCH_SESSIONS,
                ResultStatus.SUCCESS,
                candidates,
                List.of(),
                List.of(),
                GuidePlanningTest.NOW,
                null));
    when(tools.check(any()))
        .thenReturn(new ToolCheckResult(ResultStatus.SUCCESS, Failure.NONE, List.of()));
    var venuesSearched = new ArrayList<Long>();
    when(rag.plan(any()))
        .thenAnswer(
            call -> {
              Turn turn = call.getArgument(0);
              venuesSearched.add(turn.retrievalNeeds.get(0).venueId());
              return List.of();
            });
    when(rag.retrieve(any()))
        .thenAnswer(
            call -> {
              Turn turn = call.getArgument(0);
              long venue = turn.retrievalNeeds.get(0).venueId();
              var need = turn.retrievalNeeds.get(0);
              return venue == 7
                  ? List.of()
                  : List.of(evidence(venue, need.activityId(), "AGE_RULE"));
            });
    when(rag.rerank(any(Turn.class)))
        .thenAnswer(call -> ((Turn) call.getArgument(0)).retrievedDocuments);
    when(rag.grade(any())).thenCallRealMethod();
    when(language.generate(any())).thenAnswer(call -> proposal(call.getArgument(0)));
    var result =
        new GuideGraphV2(new GuideIntent(), tools, rag, new GuidePlanner(), language)
            .execute(state, ignored -> {});
    assertEquals(List.of(7L, 1L), venuesSearched);
    assertEquals("VERIFIED", result.phase);
    assertEquals(List.of(1L), result.proposal.orderedSessionIds());
    assertEquals(List.of("chunk-1-1"), result.proposal.evidenceReferences());
    assertEquals(1, result.evidenceBindings.get(0).sessionId());
    assertEquals(1, result.visitedNodes.stream().filter("ToolExecute"::equals).count());
    verify(language, times(1)).generate(any());
  }

  @Test
  void verifierRejectsSameVenueEvidenceForTheWrongActivityAndConflictingRequiredRules() {
    var s = fixtures.state(2, false);
    s.candidateSessions = List.of(session(1, 1, "剪纸"), fixtures.session(2, 12, 13));
    s.activityCombinations = new GuidePlanner().optimize(s);
    s.retrievalNeeds = List.of(new RetrievalNeed("a1", "背景", 1L, 1L, "ACTIVITY_BACKGROUND", true));
    s.rerankedDocuments = List.of(evidence(1L, 2L, "ACTIVITY_BACKGROUND"));
    s.evidenceGrade =
        List.of(
            new Grade(
                "a1", GradeStatus.SUPPORTED, List.of("背景"), List.of("chunk-1-2"), List.of(), null));
    assertEquals(GradeStatus.INSUFFICIENT, GuideEvidenceScope.bind(s).get(0).status());
    assertFalse(new GuidePlanner().verify(s, proposal(s)).passed());
    s.evidenceGrade =
        List.of(
            new Grade("a1", GradeStatus.CONFLICTING, List.of(), List.of(), List.of("背景"), "冲突"));
    assertEquals(GradeStatus.CONFLICTING, GuideEvidenceScope.bind(s).get(0).status());
    assertFalse(new GuidePlanner().verify(s, proposal(s)).passed());
  }

  @Test
  void optionalUnknownDoesNotBlockAndBackgroundIsBoundPerActivity() {
    var s = fixtures.state(2, false);
    s.identity = new Identity("conversation", "turn", "trace", 1, "看看活动背景", GuidePlanningTest.NOW);
    s.candidateSessions = List.of(session(1, 1, "剪纸"), fixtures.session(2, 12, 13));
    s.activityCombinations = new GuidePlanner().optimize(s);
    GuideEvidenceScope.reset(s);
    var bindings = GuideEvidenceScope.bind(s);
    assertEquals(List.of(1L, 2L), bindings.stream().map(EvidenceBinding::sessionId).toList());
    assertTrue(
        bindings.stream().allMatch(b -> b.status() == GradeStatus.INSUFFICIENT && !b.required()));
    assertTrue(new GuidePlanner().verify(s, proposal(s)).passed());
  }

  Session session(long id, long venue, String title) {
    var original = fixtures.session(id, 10, 11);
    return new Session(
        id,
        id,
        venue,
        title,
        original.category(),
        original.audience(),
        "",
        "场馆" + venue,
        original.startAt(),
        original.endAt(),
        original.registrationStartAt(),
        original.registrationEndAt(),
        5,
        false,
        true,
        GuidePlanningTest.NOW);
  }

  static Evidence evidence(long venue, Long activity, String type) {
    return new Evidence(
        "doc",
        "chunk-" + venue + "-" + activity,
        venue,
        activity,
        activity == null ? "VENUE" : "ACTIVITY",
        "模拟测试",
        type,
        "1",
        null,
        "6～12 岁儿童可以参加",
        0.5,
        1.0);
  }

  static Proposal proposal(Turn s) {
    var sessions = GuideEvidenceScope.selected(s);
    return new Proposal(
        s.identity.turnId(),
        s.currentPlanVersion,
        "文化活动计划",
        sessions.stream().map(Session::sessionId).toList(),
        sessions.stream()
            .map(c -> new ScheduleItem(c.sessionId(), c.startAt(), c.endAt()))
            .toList(),
        List.of("时间不冲突"),
        s.evidenceGrade.stream()
            .filter(g -> g.status() == GradeStatus.SUPPORTED)
            .flatMap(g -> g.evidenceIds().stream())
            .toList(),
        List.of(),
        List.of(),
        s.identity.currentTime());
  }
}
