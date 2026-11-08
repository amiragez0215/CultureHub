package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.culturehub.*;
import cn.culturehub.AiGuideGraph.Preferences;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;

class GuideRoundTwoNodesTest {
  final GuideQueryService queries = mock(GuideQueryService.class);
  final GuideTools tools = new GuideTools(queries);
  final GuideKnowledge rag =
      new GuideKnowledge(mock(ObjectProvider.class), mock(Store.class), mock(Access.class));

  @Test
  void choosesToolsFromNeedsAndDistinguishesAnUnknownVenue() {
    var venue = state(IntentType.VENUE_QA, 1, "不存在的文化馆几点开门？", "不存在的文化馆");
    assertEquals(
        List.of(ToolName.VENUE_INFORMATION),
        tools.plan(venue).stream().map(ToolCall::name).toList());
    when(queries.venues("不存在的文化馆")).thenReturn(List.of());
    venue.toolResults = List.of(tools.execute(tools.plan(venue).get(0), venue));
    assertEquals("VENUE_NOT_FOUND", venue.toolResults.get(0).errorType());
    assertEquals(ResultStatus.EMPTY, tools.check(venue).status());
    verify(queries).venues("不存在的文化馆");
    verifyNoMoreInteractions(queries);
    var simple = state(IntentType.ACTIVITY_SEARCH, 1, "看看非遗活动", null);
    assertEquals(
        List.of(ToolName.SEARCH_SESSIONS),
        tools.plan(simple).stream().map(ToolCall::name).toList());
    var multi = state(IntentType.ACTIVITY_PLAN, 2, "安排两个活动", null);
    assertEquals(
        List.of(ToolName.SEARCH_SESSIONS, ToolName.MY_ACTIVE_BOOKINGS),
        tools.plan(multi).stream().map(ToolCall::name).toList());
    var detail = state(IntentType.ACTIVITY_SEARCH, 1, "查询场次编号 23 的详情", null);
    var call = tools.plan(detail).get(0);
    assertEquals("session:23", call.informationNeedId());
    assertEquals(23L, call.sessionId());
    assertEquals(ToolName.SESSION_DETAILS, call.name());
  }

  @Test
  void incompleteOrTimedOutDetailIsTheOnlyReplannedNeed() {
    var s = state(IntentType.ACTIVITY_PLAN, 1, "安排场次23并检查已有预约", null);
    var initial = tools.plan(s);
    assertEquals(2, initial.size());
    var detail = initial.get(0);
    var bookings = initial.get(1);
    when(queries.current(23))
        .thenThrow(new QueryTimeoutException("test"))
        .thenReturn(sessionRow(23));
    var bookingResult = tools.execute(bookings, s);
    s.toolResults = List.of(tools.execute(detail, s), bookingResult);
    assertEquals("MYSQL_TIMEOUT", s.toolResults.get(0).errorType());
    assertEquals(List.of("session:23"), tools.check(s).missing());
    assertEquals(List.of(detail), tools.plan(s));
    var fixed = tools.execute(detail, s);
    s.toolResults = List.of(fixed, bookingResult);
    assertEquals(23L, fixed.sessions().get(0).sessionId());
    assertEquals(ResultStatus.SUCCESS, tools.check(s).status());
    assertTrue(tools.plan(s).isEmpty());
    verify(queries).activeBookings(19);
    assertThrows(
        Problem.class,
        () -> tools.execute(new ToolCall(ToolName.MY_ACTIVE_BOOKINGS, 222L, null), s));
    verify(queries, never()).activeBookings(222L);
  }

  @Test
  void staleOrChangedArgumentsInvalidateOnlyTheirOwnFacts() {
    var s = state(IntentType.ACTIVITY_PLAN, 1, "安排场次23", null);
    var calls = tools.plan(s);
    s.toolResults =
        List.of(
            new ToolResult(
                    ToolName.SESSION_DETAILS,
                    ResultStatus.EMPTY,
                    List.of(),
                    List.of(),
                    List.of(),
                    s.identity.currentTime(),
                    null)
                .forCall(calls.get(0)),
            new ToolResult(
                    ToolName.MY_ACTIVE_BOOKINGS,
                    ResultStatus.EMPTY,
                    List.of(),
                    List.of(),
                    List.of(),
                    s.identity.currentTime(),
                    null)
                .forCall(calls.get(1)));
    s.identity =
        new Identity("c", "next", "trace", 19, "安排场次24", s.identity.currentTime().plusSeconds(5));
    assertEquals(
        List.of("session:24"), tools.plan(s).stream().map(ToolCall::informationNeedId).toList());
    s.identity =
        new Identity("c", "next", "trace", 19, "安排场次23", s.identity.currentTime().plusSeconds(7));
    assertEquals(
        List.of("bookings"), tools.plan(s).stream().map(ToolCall::informationNeedId).toList());
  }

  @Test
  void parsesSpecificQuestionsAndOnlyRelevantRuleCanAnswer() {
    assertEquals(
        "OPENING_HOURS",
        GuideKnowledgeQuestions.plan(state(IntentType.VENUE_QA, 1, "礼拜天开门吗？", "场馆A"))
            .get(0)
            .type());
    assertEquals(
        "AGE_RULE",
        GuideKnowledgeQuestions.plan(state(IntentType.ACTIVITY_SEARCH, 1, "8岁能不能参加剪纸？", null))
            .get(0)
            .type());
    assertEquals(
        "GUARDIAN_RULE",
        GuideKnowledgeQuestions.plan(state(IntentType.VENUE_QA, 1, "必须有家长陪同吗？", "场馆A"))
            .get(0)
            .type());
    assertEquals(
        "ACTIVITY_BACKGROUND",
        GuideKnowledgeQuestions.plan(state(IntentType.ACTIVITY_SEARCH, 1, "这项非遗活动有什么历史背景？", null))
            .get(0)
            .type());

    var s = state(IntentType.VENUE_QA, 1, "礼拜天开门吗", "场馆A");
    s.retrievalNeeds =
        List.of(new RetrievalNeed("open", "礼拜天开门吗", 1L, null, "OPENING_HOURS", true));
    var irrelevant = evidence("irrelevant", "本场馆近期开放了传统手工课程", "OPENING_HOURS", "open");
    var direct = evidence("direct", "本场馆周日开放时间为 09:00～17:00。", "OPENING_HOURS", "open");
    s.retrievedDocuments = List.of(irrelevant, direct);
    s.rerankedDocuments = rag.rerank(s);
    assertEquals("direct", s.rerankedDocuments.get(0).chunkId());
    assertEquals(GradeStatus.SUPPORTED, rag.grade(s).get(0).status());
    s.rerankedDocuments = List.of(irrelevant);
    assertEquals(GradeStatus.INSUFFICIENT, rag.grade(s).get(0).status());
    s.rerankedDocuments =
        List.of(evidence("other-question", direct.text(), "OPENING_HOURS", "age"));
    assertEquals(GradeStatus.INSUFFICIENT, rag.grade(s).get(0).status());
    s.unavailableQuestions.add("open");
    assertEquals(GradeStatus.UNAVAILABLE, rag.grade(s).get(0).status());
  }

  @Test
  void conditionalRuleDoesNotProveIndependentAdmission() {
    var s = state(IntentType.ACTIVITY_PLAN, 1, "8 岁儿童必须独自入场", null);
    s.retrievalNeeds =
        List.of(
            new RetrievalNeed("age", "适用年龄", 1L, null, "AGE_RULE", true),
            new RetrievalNeed(
                "guardian",
                "儿童能否单独入场",
                1L,
                null,
                "GUARDIAN_RULE",
                true,
                RequirementTarget.INDEPENDENT_ADMISSION));
    String conditional = "6～12 岁儿童可以参加，但须有家长陪同。";
    s.rerankedDocuments =
        List.of(
            evidence("conditional", conditional, "AGE_RULE", "age"),
            evidence("guardian", conditional, "GUARDIAN_RULE", "guardian"));
    var grades = rag.grade(s);
    assertEquals("须有家长或监护人陪同", grades.get(0).claims().get(0).condition());
    assertEquals(GradeStatus.SUPPORTED, grades.get(1).status());
    assertEquals(RequirementVerdict.NOT_MATCHED, grades.get(1).requirementVerdict());
    assertTrue(grades.get(1).unresolvedQuestions().isEmpty());
    s.rerankedDocuments =
        List.of(
            evidence("required", conditional, "GUARDIAN_RULE", "guardian"),
            evidence("independent", "可以单独入场，无需家长陪同。", "GUARDIAN_RULE", "guardian"));
    assertEquals(GradeStatus.CONFLICTING, rag.grade(s).get(1).status());
  }

  Turn state(IntentType intent, int count, String message, String venue) {
    var s = new GuidePlanningTest().state(count, false);
    s.identity = new Identity("c", "turn", "trace", 19, message, GuidePlanningTest.NOW);
    s.requirements =
        new Requirements(
            intent,
            new Preferences(null, null, null, null, null, null, null, venue, null, null),
            List.of(),
            null,
            count,
            false,
            240,
            10,
            0,
            null,
            false);
    return s;
  }

  Map<String, Object> sessionRow(long id) {
    var now = GuidePlanningTest.NOW;
    return Map.ofEntries(
        Map.entry("sessionId", id),
        Map.entry("activityId", 3L),
        Map.entry("venueId", 4L),
        Map.entry("title", "非遗剪纸"),
        Map.entry("startAt", now.plusHours(2)),
        Map.entry("endAt", now.plusHours(3)),
        Map.entry("registrationStartAt", now.minusDays(1)),
        Map.entry("registrationEndAt", now.plusHours(1)),
        Map.entry("sessionStatus", "PUBLISHED"),
        Map.entry("activityStatus", "PUBLISHED"),
        Map.entry("venueStatus", "OPEN"),
        Map.entry("remaining", 3),
        Map.entry("waitlistPriority", 0),
        Map.entry("asOf", now));
  }

  Evidence evidence(String id, String text, String type, String question) {
    return new Evidence(id, id, 1L, null, "VENUE", "模拟知识", type, "2", null, text, 0.5, 0, question);
  }
}
