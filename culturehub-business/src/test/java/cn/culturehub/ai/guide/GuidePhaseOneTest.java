package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.culturehub.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GuidePhaseOneTest {
  @Test
  void ordinalRevisionDoesNotResetHardCountOrDuration() {
    var first =
        intent.merge(
            null,
            new IntentPatch(
                IntentType.ACTIVITY_PLAN, null, List.of(), null, 2, false, 240, null, false),
            request("安排两个活动"),
            10);
    var next =
        intent.merge(
            first,
            new IntentPatch(
                IntentType.PLAN_REVISION, null, List.of(), null, 1, false, 1440, 0, false),
            request("第二个活动换早一点"),
            10);
    assertEquals(2, next.requestedActivityCount());
    assertEquals(240, next.planningDurationMinutes());
    assertEquals(2, next.revisionIndex());
  }

  @Test
  void zeroMeansUnspecifiedOnlyWhenUserDidNotRequestZero() {
    var result =
        intent.merge(
            null,
            new IntentPatch(IntentType.ACTIVITY_SEARCH, null, null, null, 1, false, 0, null, false),
            request("查找活动"),
            10);
    assertEquals(1440, result.planningDurationMinutes());
    assertThrows(
        Problem.class,
        () ->
            intent.merge(
                null,
                new IntentPatch(
                    IntentType.ACTIVITY_PLAN, null, null, null, 1, false, 0, null, false),
                request("时长0分钟"),
                10));
  }

  final GuideIntent intent = new GuideIntent();

  Identity request(String message) {
    return new Identity("c", "t", "trace", 7, message, LocalDateTime.of(2026, 11, 7, 9, 0));
  }

  IntentPatch patch(cn.culturehub.AiGuideGraph.Preferences p) {
    return new IntentPatch(IntentType.ACTIVITY_PLAN, p, null, null, null, null, null, null, false);
  }

  @Test
  void relativeTomorrowUsesBusinessClock() {
    var r = intent.merge(null, patch(null), request("明天安排活动"), 10);
    assertEquals("2026-11-08", r.hardConstraints().fromDate());
    assertEquals("2026-11-08", r.hardConstraints().toDate());
  }

  @Test
  void omittedHistoricalHardConstraintSurvives() {
    var p =
        new AiGuideGraph.Preferences(
            null, null, "12:00", "18:00", "HERITAGE", null, null, null, 8, 3);
    var first = intent.merge(null, patch(p), request("两个非遗活动"), 10);
    var second = intent.merge(first, patch(null), request("第二个换早一点"), 10);
    assertEquals("HERITAGE", second.hardConstraints().category());
    assertEquals(3, second.hardConstraints().minimumRemaining());
    assertEquals(8, second.hardConstraints().childAge());
  }

  @Test
  void familyDoesNotBecomeMultipleReservations() {
    var r = intent.merge(null, patch(null), request("带两个孩子"), 10);
    assertEquals(1, r.requestedActivityCount());
  }

  @Test
  void excessiveCountFailsClosed() {
    assertThrows(
        Problem.class,
        () ->
            intent.merge(
                null,
                new IntentPatch(
                    IntentType.ACTIVITY_PLAN, null, null, null, 4, false, null, null, false),
                request("四个"),
                10));
  }

  @Test
  void emptyIsNotFailure() {
    var state = new Turn();
    state.requirements = intent.merge(null, patch(null), request("活动"), 10);
    var tools = new GuideTools(mock(GuideQueryService.class));
    state.toolPlan = tools.plan(state);
    state.toolResults =
        List.of(
            new ToolResult(
                ToolName.SEARCH_SESSIONS,
                ResultStatus.EMPTY,
                List.of(),
                List.of(),
                List.of(),
                request("").currentTime(),
                null),
            new ToolResult(
                ToolName.MY_ACTIVE_BOOKINGS,
                ResultStatus.EMPTY,
                List.of(),
                List.of(),
                List.of(),
                request("").currentTime(),
                null));
    assertEquals(ResultStatus.EMPTY, tools.check(state).status());
    assertEquals(Failure.NONE, tools.check(state).failureType());
  }

  @Test
  void missingBookingsRequiresToolPlanRepair() {
    var state = new Turn();
    state.requirements = intent.merge(null, patch(null), request("活动"), 10);
    var tools = new GuideTools(mock(GuideQueryService.class));
    state.toolPlan = tools.plan(state);
    assertEquals(Failure.TOOL_PLAN_INCOMPLETE, tools.check(state).failureType());
  }

  @Test
  void toolFailureIsDistinct() {
    var state = new Turn();
    state.identity = request("");
    state.requirements = intent.merge(null, patch(null), state.identity, 10);
    var queries = mock(GuideQueryService.class);
    when(queries.activeBookings(7)).thenThrow(new IllegalStateException("db down"));
    var tools = new GuideTools(queries);
    var result = tools.execute(new ToolCall(ToolName.MY_ACTIVE_BOOKINGS, null, null), state);
    assertEquals(ResultStatus.FAILED, result.status());
    assertEquals("IllegalStateException", result.errorType());
  }
}
