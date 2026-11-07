package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.culturehub.AiGuideGraph.Preferences;
import java.util.*;
import org.junit.jupiter.api.Test;

class GuideInteractionTest {
  @Test
  void latestCorrectionsRetainOtherConstraintsAndUseTheActualPreviousVenue() {
    var intent = new GuideIntent();
    var old =
        intent.merge(
            null,
            new IntentPatch(
                IntentType.ACTIVITY_PLAN,
                new Preferences(
                    null, null, "12:00", "18:00", "FAMILY", "FAMILY", "亲子", null, null, 3),
                List.of("亲子"),
                null,
                2,
                false,
                240,
                null,
                false),
            request("下午安排两个亲子活动"),
            10);
    var revise =
        new IntentPatch(
            IntentType.PLAN_REVISION, null, List.of(), null, null, null, null, null, false);
    var venue = intent.merge(old, revise, request("还是刚才那个场馆"), 10, "市文化博物馆");
    assertEquals("市文化博物馆", venue.hardConstraints().venue());
    var duration = intent.merge(venue, revise, request("我说的三个小时是总时间"), 10);
    assertEquals(180, duration.planningDurationMinutes());
    var topic = intent.merge(duration, revise, request("不要亲子活动了，改成书法。"), 10);
    assertEquals("书法", topic.hardConstraints().keyword());
    assertNull(topic.hardConstraints().category());
    assertNull(topic.hardConstraints().audience());
    assertTrue(topic.softPreferences().isEmpty());
    assertEquals(2, topic.requestedActivityCount());
    assertEquals(180, topic.planningDurationMinutes());
    assertEquals("12:00", topic.hardConstraints().fromTime());
    assertEquals(3, topic.hardConstraints().minimumRemaining());
  }

  @Test
  void graphPassesRecentMessagesOnlyToUnderstanding() {
    var s = new GuidePlanningTest().state(1, false);
    s.deadlineEpochMillis = System.currentTimeMillis() + 90000;
    s.recentMessages = List.of(new ConversationExchange("前一轮偏好下午", "已记录"));
    var language = mock(GuideLanguage.class);
    when(language.understand(any()))
        .thenAnswer(
            call -> {
              Turn current = call.getArgument(0);
              assertEquals("前一轮偏好下午", current.recentMessages.get(0).user());
              return new IntentPatch(
                  IntentType.ACTIVITY_PLAN,
                  null,
                  List.of(),
                  "要安排几项活动？",
                  null,
                  null,
                  null,
                  null,
                  false);
            });
    var result =
        new GuideGraphV2(
                new GuideIntent(),
                mock(GuideTools.class),
                mock(GuideKnowledge.class),
                new GuidePlanner(),
                language)
            .execute(s, ignored -> {});
    assertEquals("CLARIFICATION", result.phase);
    assertTrue(result.recentMessages.isEmpty());
  }

  @Test
  void narrationUsesBusinessFactsAndRejectsInventedOrMismatchedReferences() {
    var fixture = new GuidePlanningTest();
    var turn = fixture.state(1, false);
    turn.candidateSessions = List.of(fixture.session(1, 10, 11));
    turn.activityCombinations = new GuidePlanner().optimize(turn);
    turn.proposal = GuideEvidenceScopeTest.proposal(turn);
    var rendered = GuideNarration.publish(turn);
    assertEquals("category:1", rendered.recommendationRefs().get(0).factId());
    assertTrue(rendered.recommendationReasons().get(0).contains("非遗体验"));
    var invalid =
        new Proposal(
            rendered.planId(),
            rendered.planVersion(),
            rendered.title(),
            rendered.orderedSessionIds(),
            rendered.scheduleItems(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            rendered.generatedAt(),
            List.of(new RecommendationRef("category:1", 2L, null, null, NarrationStyle.DIRECT)));
    assertFalse(new GuidePlanner().verify(turn, invalid).passed());
    var wrongQuestion =
        new RecommendationRef(null, 1L, "age", "opening-doc", NarrationStyle.DIRECT);
    assertTrue(
        GuideNarration.statement(
                turn,
                wrongQuestion,
                List.of(
                    new EvidenceBinding(
                        1L,
                        1L,
                        1L,
                        "opening",
                        true,
                        GradeStatus.SUPPORTED,
                        List.of("opening-doc"),
                        true)))
            .isEmpty());
  }

  private Identity request(String message) {
    return new Identity("conversation", "turn", "trace", 1, message, GuidePlanningTest.NOW);
  }
}
