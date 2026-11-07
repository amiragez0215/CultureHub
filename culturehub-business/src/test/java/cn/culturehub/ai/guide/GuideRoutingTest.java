package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.culturehub.AiGuideGraph.Preferences;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GuideRoutingTest {
  @Test
  void globalRuleQuerySkipsToolsAndFailsClosedWhenChromaMissing() {
    var fixture = new GuidePlanningTest();
    var state = fixture.state(1, false);
    var tools = mock(GuideTools.class);
    var language = mock(GuideLanguage.class);
    when(language.understand(any()))
        .thenReturn(
            new IntentPatch(
                IntentType.VENUE_QA, null, List.of(), null, 1, false, 240, null, false));
    var rag =
        new GuideKnowledge(
            mock(org.springframework.beans.factory.ObjectProvider.class),
            mock(cn.culturehub.Store.class),
            mock(cn.culturehub.Access.class));
    state.deadlineEpochMillis=System.currentTimeMillis()+90000;
    var result =
        new GuideGraphV2(new GuideIntent(), tools, rag, new GuidePlanner(), language)
            .execute(state, s -> {});
    assertEquals("KNOWLEDGE_BLOCKED", result.phase);
    assertEquals(ResultStatus.SKIPPED, result.toolCheckResult.status());
    assertEquals(GradeStatus.UNAVAILABLE, result.evidenceGrade.get(0).status());
    verifyNoInteractions(tools);
    verify(language, never()).generate(any());
  }

  @Test
  void independentChainsAndTargetedGenerationRepairVisitAllFifteenNodes() {
    var fixture = new GuidePlanningTest();
    var state = fixture.state(1, false);
    state.currentPlanVersion = 1;
    var tools = mock(GuideTools.class);
    var rag = mock(GuideKnowledge.class);
    var language = mock(GuideLanguage.class);
    when(language.understand(any()))
        .thenReturn(
            new IntentPatch(
                IntentType.ACTIVITY_PLAN,
                new Preferences(null, null, null, null, null, null, null, null, 8),
                List.of(),
                null,
                1,
                false,
                240,
                null,
                false));
    when(tools.plan(any()))
        .thenReturn(
            List.of(
                new ToolCall(ToolName.SEARCH_SESSIONS, null, null),
                new ToolCall(ToolName.MY_ACTIVE_BOOKINGS, null, null)));
    when(tools.execute(any(), any()))
        .thenAnswer(
            call -> {
              ToolCall request = call.getArgument(0);
              return new ToolResult(
                  request.name(),
                  request.name() == ToolName.SEARCH_SESSIONS
                      ? ResultStatus.SUCCESS
                      : ResultStatus.EMPTY,
                  request.name() == ToolName.SEARCH_SESSIONS
                      ? List.of(fixture.session(1, 10, 11))
                      : List.of(),
                  List.of(),
                  List.of(),
                  GuidePlanningTest.NOW,
                  null);
            });
    when(tools.check(any())).thenCallRealMethod();
    when(rag.plan(any())).thenReturn(List.of());
    when(rag.retrieve(any())).thenReturn(List.of());
    when(rag.rerank(any(), any())).thenReturn(List.of());
    when(rag.grade(any()))
        .thenReturn(
            List.of(
                new Grade(
                    "rules:1",
                    GradeStatus.INSUFFICIENT,
                    List.of(),
                    List.of(),
                    List.of("年龄规则不足"),
                    "无证据")));
    var attempts = new AtomicInteger();
    when(language.generate(any()))
        .thenAnswer(
            call -> {
              Turn s = call.getArgument(0);
              boolean bad = attempts.getAndIncrement() == 0;
              return new Proposal(
                  s.identity.turnId(),
                  s.currentPlanVersion,
                  "文化活动计划",
                  List.of(bad ? 999L : 1L),
                  List.of(
                      new ScheduleItem(
                          bad ? 999 : 1,
                          GuidePlanningTest.NOW.withHour(10),
                          GuidePlanningTest.NOW.withHour(11))),
                  List.of("时间不冲突"),
                  List.of(),
                  List.of(),
                  List.of("尚未预约"),
                  GuidePlanningTest.NOW);
            });
    var graph = new GuideGraphV2(new GuideIntent(), tools, rag, new GuidePlanner(), language);
    state.deadlineEpochMillis=System.currentTimeMillis()+90000;
    Turn output = graph.execute(state, s -> {});
    assertEquals("VERIFIED", output.phase);
    assertEquals(15, new HashSet<>(output.visitedNodes).size());
    assertEquals(2, output.visitedNodes.stream().filter("ProposalGenerate"::equals).count());
    assertEquals(1, output.visitedNodes.stream().filter("ToolExecute"::equals).count());
    assertEquals(1, output.visitedNodes.stream().filter("IntentExtract"::equals).count());
  }
}
