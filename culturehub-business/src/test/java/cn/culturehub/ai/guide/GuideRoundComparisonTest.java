package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import cn.culturehub.*;
import cn.culturehub.AiGuideGraph.Preferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Copy this probe to the first-round source snapshot to replay exactly the same fixtures. */
class GuideRoundComparisonTest {
  @Test
  void recordMatchedDeterministicCases() throws Exception {
    var tools = new GuideTools(mock(GuideQueryService.class));
    var knowledge =
        new GuideKnowledge(mock(ObjectProvider.class), mock(Store.class), mock(Access.class));
    var observed = new LinkedHashMap<String, Object>();
    for (var scenario :
        List.of(
            List.of("search", "推荐活动", IntentType.ACTIVITY_SEARCH),
            List.of("detail", "查询场次编号 23", IntentType.ACTIVITY_SEARCH),
            List.of("venue", "文化馆几点开门", IntentType.VENUE_QA),
            List.of("plan", "安排两个活动", IntentType.ACTIVITY_PLAN))) {
      var turn = turn((IntentType) scenario.get(2), scenario.get(1).toString());
      observed.put(
          "tools:" + scenario.get(0), tools.plan(turn).stream().map(ToolCall::name).toList());
    }
    var venue = turn(IntentType.VENUE_QA, "不存在的文化馆几点开门");
    venue.toolPlan = tools.plan(venue);
    venue.toolResults =
        List.of(
            new ToolResult(
                ToolName.VENUE_INFORMATION,
                ResultStatus.EMPTY,
                List.of(),
                List.of(),
                List.of(),
                GuidePlanningTest.NOW,
                "VENUE_NOT_FOUND"));
    observed.put("venue-empty", tools.check(venue).status());
    venue.toolResults =
        List.of(
            new ToolResult(
                ToolName.VENUE_INFORMATION,
                ResultStatus.FAILED,
                List.of(),
                List.of(),
                List.of(),
                GuidePlanningTest.NOW,
                "MYSQL_TIMEOUT"));
    observed.put("mysql-timeout", tools.check(venue).status());
    String query = "礼拜天几点开门";
    var misleading = evidence("related", "本场馆近期开放了传统手工课程。");
    var direct = evidence("direct", "本场馆周日开放时间为 09:00～17:00。");
    observed.put("recall-order", List.of(misleading.chunkId(), direct.chunkId()));
    observed.put(
        "reranked-order",
        knowledge.rerank(List.of(misleading, direct), query).stream()
            .map(Evidence::chunkId)
            .toList());
    var qa = turn(IntentType.VENUE_QA, "这个场馆的开放时间？");
    qa.retrievalNeeds =
        List.of(new RetrievalNeed("opening", "这个场馆的开放时间？", 1L, null, "OPENING_HOURS", true));
    qa.rerankedDocuments = knowledge.rerank(List.of(misleading), qa.identity.latestUserMessage());
    observed.put("related-only-grade", knowledge.grade(qa).get(0).status());
    qa.rerankedDocuments = knowledge.rerank(List.of(direct), qa.identity.latestUserMessage());
    observed.put("direct-grade", knowledge.grade(qa).get(0).status());
    var plan = new GuidePlanningTest().state(1, false);
    plan.candidateSessions = List.of(new GuidePlanningTest().session(1, 10, 11));
    plan.activityCombinations = new GuidePlanner().optimize(plan);
    plan.retrievalNeeds =
        List.of(new RetrievalNeed("question-A", "活动规则", 1L, 1L, "AGE_RULE", true));
    plan.rerankedDocuments =
        List.of(
            new Evidence(
                "wrong",
                "wrong",
                2L,
                2L,
                "ACTIVITY",
                "模拟来源",
                "AGE_RULE",
                "1",
                null,
                "6～12 岁儿童可以参加。",
                0.5,
                1));
    plan.evidenceGrade =
        List.of(
            new Grade(
                "question-A",
                GradeStatus.SUPPORTED,
                List.of("年龄范围"),
                List.of("wrong"),
                List.of(),
                null));
    var draft =
        new Proposal(
            plan.identity.turnId(),
            plan.currentPlanVersion,
            "文化活动计划",
            List.of(1L),
            List.of(
                new ScheduleItem(
                    1L, GuidePlanningTest.NOW.withHour(10), GuidePlanningTest.NOW.withHour(11))),
            List.of("时间不冲突"),
            List.of("wrong"),
            List.of(),
            List.of(),
            GuidePlanningTest.NOW);
    observed.put("wrong-entity-evidence-accepted", new GuidePlanner().verify(plan, draft).passed());
    Files.writeString(
        Path.of("target/ai-v2-round-comparison.json"),
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(observed));
    if (!Boolean.getBoolean("culturehub.firstRoundProbe")) {
      assertEquals(List.of(ToolName.SESSION_DETAILS), observed.get("tools:detail"));
      assertEquals(ResultStatus.EMPTY, observed.get("venue-empty"));
      assertEquals(GradeStatus.INSUFFICIENT, observed.get("related-only-grade"));
      assertEquals(false, observed.get("wrong-entity-evidence-accepted"));
    }
  }

  private Turn turn(IntentType type, String message) {
    var s = new GuidePlanningTest().state(type == IntentType.ACTIVITY_PLAN ? 2 : 1, false);
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
                type == IntentType.VENUE_QA ? "文化馆" : null,
                null,
                null),
            List.of(),
            null,
            s.requirements.requestedActivityCount(),
            false,
            240,
            10,
            0,
            null,
            false);
    return s;
  }

  private Evidence evidence(String id, String text) {
    return new Evidence(
        id, id, 1L, null, "VENUE", "模拟来源", "OPENING_HOURS", "1", null, text, 0.5, 0);
  }
}
