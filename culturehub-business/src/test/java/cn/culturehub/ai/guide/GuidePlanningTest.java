package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.culturehub.AiGuideGraph.Preferences;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GuidePlanningTest {
  @Test
  void verifierFindsCollisionEvenWhenOptimizerOutputWasCorrupted() {
    var s = state(2, false);
    var a = session(1, 10, 12);
    var b = session(2, 11, 13);
    s.candidateSessions = List.of(a, b);
    s.activityCombinations = List.of(new Combination(List.of(a, b), 0, List.of()));
    var proposal =
        new Proposal(
            "turn",
            0,
            "文化活动计划",
            List.of(1L, 2L),
            List.of(
                new ScheduleItem(1, a.startAt(), a.endAt()),
                new ScheduleItem(2, b.startAt(), b.endAt())),
            List.of("时间不冲突"),
            List.of(),
            List.of(),
            List.of(),
            NOW);
    var result = planner.verify(s, proposal);
    assertFalse(result.passed());
    assertTrue(
        result.errors().stream().anyMatch(e -> e.repairTarget().equals("CandidateOptimize")));
  }

  final GuidePlanner planner = new GuidePlanner();
  static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 9, 0);

  Turn state(int count, boolean preferred) {
    var s = new Turn();
    s.identity = new Identity("c", "turn", "trace", 1, "规划", NOW);
    s.requirements =
        new Requirements(
            IntentType.ACTIVITY_PLAN,
            new Preferences(null, null, null, null, null, null, null, null, null, null),
            List.of(),
            null,
            count,
            preferred,
            240,
            10,
            0,
            null,
            false);
    s.deadlineEpochMillis = System.currentTimeMillis() + 10000;
    return s;
  }

  Session session(long id, int start, int end) {
    return new Session(
        id,
        id,
        1,
        "活动",
        "HERITAGE",
        "ALL",
        "介绍",
        "场馆",
        NOW.withHour(start),
        NOW.withHour(end),
        NOW.minusDays(1),
        NOW.plusMinutes(30),
        5,
        false,
        true,
        NOW);
  }

  @Test
  void overlappingPairRejected() {
    var s = state(2, false);
    s.candidateSessions = List.of(session(1, 10, 12), session(2, 11, 13));
    assertTrue(planner.optimize(s).isEmpty());
  }

  @Test
  void hardCountNeverDowngrades() {
    var s = state(2, false);
    s.candidateSessions = List.of(session(1, 10, 11));
    assertTrue(planner.optimize(s).isEmpty());
  }

  @Test
  void preferredCountCanDowngrade() {
    var s = state(2, true);
    s.candidateSessions = List.of(session(1, 10, 11));
    assertEquals(1, planner.optimize(s).get(0).sessions().size());
  }

  @Test
  void existingBookingConflicts() {
    var s = state(1, false);
    s.candidateSessions = List.of(session(1, 10, 11));
    s.existingBookings =
        List.of(new ExistingBooking(8, 9, "RESERVED", NOW.withHour(10), NOW.withHour(11)));
    assertTrue(planner.optimize(s).isEmpty());
  }

  @Test
  void necessaryGapAndTotalDuration() {
    var s = state(2, false);
    s.candidateSessions = List.of(session(1, 10, 11), session(2, 11, 12));
    assertTrue(planner.optimize(s).isEmpty());
    s.candidateSessions = List.of(session(1, 10, 11), session(2, 12, 13));
    assertEquals(2, planner.optimize(s).get(0).sessions().size());
  }

  @Test
  void hallucinatedIdsAndTimesFail() {
    var s = state(1, false);
    s.candidateSessions = List.of(session(1, 10, 11));
    s.activityCombinations = planner.optimize(s);
    var p =
        new Proposal(
            "turn",
            1,
            "文化活动计划",
            List.of(999L),
            List.of(new ScheduleItem(999, NOW, NOW.plusHours(1))),
            List.of("时间不冲突"),
            List.of(),
            List.of(),
            List.of(),
            NOW);
    assertFalse(planner.verify(s, p).passed());
    assertEquals("ProposalGenerate", planner.verify(s, p).errors().get(0).repairTarget());
  }

  @Test
  void outputClaimCannotDeclareBookingSuccess() {
    var s = state(1, false);
    s.candidateSessions = List.of(session(1, 10, 11));
    s.activityCombinations = planner.optimize(s);
    var p =
        new Proposal(
            "turn",
            1,
            "文化活动计划",
            List.of(1L),
            List.of(new ScheduleItem(1, NOW.withHour(10), NOW.withHour(11))),
            List.of("预约成功"),
            List.of(),
            List.of(),
            List.of(),
            NOW);
    assertFalse(planner.verify(s, p).passed());
  }

  @Test
  void boundedRepairStops() {
    var s = state(1, false);
    s.repairTarget = "ProposalGenerate";
    assertEquals("ProposalGenerate", planner.repair(s));
    s.repairTarget = "ProposalGenerate";
    assertEquals("stop", planner.repair(s));
  }
}
