package cn.culturehub;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;

import cn.culturehub.ai.guide.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(
    named = "culturehub.localTest",
    matches = "true")
@SpringJUnitConfig({BookingIntegrationTest.Config.class, GuideV2LocalTest.Config.class})
class GuideV2LocalTest extends BookingIntegrationTest {
  @Test
  void newBookingAfterProposalBlocksConflictingConfirmation() {
    String c = agent.conversation();
    var reply = agent.message(c, "查询", null);
    var card = ((List<Map<String, Object>>) reply.get("candidates")).get(0);
    String id = agent.select(c, card.get("candidateId").toString(), "DIRECT").get("id").toString();
    s.db.update(
        "insert into activity(id,venue_id,title,status) values(2,1,'Conflicting"
            + " activity','PUBLISHED')");
    s.db.update(
        "insert into"
            + " activity_session(id,activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status)"
            + " select"
            + " 2,2,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status"
            + " from activity_session where id=1");
    booking.create(2, UUID.randomUUID().toString(), false);
    assertEquals(
        "PLAN_SCHEDULE_CONFLICT", assertThrows(Problem.class, () -> agent.confirm(id)).code);
    assertEquals(0, s.number("select count(*) from participation where session_id=1"));
    assertEquals(1, s.number("select count(*) from participation where session_id=2"));
  }

  @Test
  void twoRealSessionsArePlannedButConfirmedIndependently() {
    s.db.update(
        "insert into activity(id,venue_id,title,status) values(2,1,'Second cultural"
            + " activity','PUBLISHED')");
    s.db.update(
        "insert into"
            + " activity_session(id,activity_id,registration_start_at,registration_end_at,start_at,end_at,checkin_open_at,checkin_close_at,capacity,status)"
            + " values(2,2,now()-interval 1 day,now()+interval 1 hour,now()+interval 4"
            + " hour,now()+interval 5 hour,now(),now()+interval 5 hour,1,'PUBLISHED')");
    String c = agent.conversation();
    var result = agent.message(c, "安排两个活动", null);
    var cards = (List<Map<String, Object>>) result.get("candidates");
    assertEquals(2, cards.size());
    assertEquals(0, s.number("select count(*) from participation"));
    for (var card : cards) {
      var proposal = agent.select(c, card.get("candidateId").toString(), "DIRECT");
      agent.confirm(proposal.get("id").toString());
    }
    assertEquals(2, s.number("select count(*) from participation"));
    assertEquals(2, s.number("select count(distinct request_id) from participation"));
  }

  @Test
  void nativeCheckpointRestoresCursorWithoutRepeatingIntent() {
    String c = agent.conversation();
    int version = persistence.begin(c);
    var turn = new Turn();
    turn.identity =
        new Identity(
            c, UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, "查询", s.now());
    turn.currentPlanVersion = version;
    turn.deadlineEpochMillis = System.currentTimeMillis() + 90000;
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              graph.execute(
                  turn,
                  state -> {
                    if ("ToolCheck".equals(state.currentNode))
                      throw new java.util.concurrent.CancellationException();
                  }));
      var restored = graph.resume(turn, state -> {});
      assertEquals("VERIFIED", restored.phase);
      assertEquals(1, restored.visitedNodes.stream().filter("IntentExtract"::equals).count());
      assertEquals(0, s.number("select count(*) from participation"));
    } finally {
      persistence.release(c, version);
    }
  }

  @Configuration
  @Import({
    GuideAgentV2.class,
    LettuceGuideSaver.class,
    GuidePersistence.class,
    GuideGraphV2.class,
    GuideTools.class,
    GuideIntent.class,
    GuideKnowledge.class,
    GuidePlanner.class
  })
  static class Config {
    @Bean
    GuideLanguage language() {
      return new GuideLanguage() {
        public IntentPatch understand(Turn s) {
          return new IntentPatch(
              s.identity.latestUserMessage().contains("两个活动")
                  ? IntentType.ACTIVITY_PLAN
                  : IntentType.ACTIVITY_SEARCH,
              null,
              List.of(),
              null,
              s.identity.latestUserMessage().contains("两个活动") ? 2 : 1,
              false,
              240,
              null,
              false);
        }

        public Proposal generate(Turn s) {
          var selected = s.activityCombinations.get(0).sessions();
          return new Proposal(
              s.identity.turnId(),
              s.currentPlanVersion,
              "文化活动计划",
              selected.stream().map(Session::sessionId).toList(),
              selected.stream()
                  .map(c -> new ScheduleItem(c.sessionId(), c.startAt(), c.endAt()))
                  .toList(),
              List.of("时间不冲突"),
              List.of(),
              s.unresolvedKnowledge,
              List.of("尚未预约"),
              s.identity.currentTime());
        }
      };
    }
  }

  @org.springframework.beans.factory.annotation.Autowired GuideAgentV2 agent;
  @org.springframework.beans.factory.annotation.Autowired GuidePersistence persistence;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.data.redis.core.StringRedisTemplate redis;

  @org.springframework.beans.factory.annotation.Autowired GuideGraphV2 graph;
  @org.springframework.beans.factory.annotation.Autowired Access access;

  @org.springframework.beans.factory.annotation.Autowired
  com.fasterxml.jackson.databind.ObjectMapper json;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager manager;

  @BeforeEach
  void v2Schema() throws Exception {
    try (var c = s.db.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
          c,
          new org.springframework.core.io.FileSystemResource(
              "../deploy/migrations/20261107-ai-guide-v2.sql"));
    }
    s.db.update("delete from ai_activity_plan_item");
    s.db.update("delete from ai_activity_plan");
    s.db.update("delete from ai_guide_conversation");
  }

  @Test
  void realGraphCreatesPlanButNeverBooksUntilExplicitConfirmation() {
    String conversation = agent.conversation();
    var result = agent.message(conversation, "查找活动", null);
    assertEquals("RECOMMENDATION", result.get("phase"));
    assertEquals(0, s.number("select count(*) from participation"));
    assertEquals(1, s.number("select count(*) from ai_activity_plan"));
    var candidate = ((List<Map<String, Object>>) result.get("candidates")).get(0);
    var proposal = agent.select(conversation, candidate.get("candidateId").toString(), "DIRECT");
    assertEquals(0, s.number("select count(*) from participation"));
    assertEquals("SUCCEEDED", agent.confirm(proposal.get("id").toString()).get("status"));
    assertEquals(1, s.number("select count(*) from participation"));
    assertEquals("SUCCEEDED", agent.confirm(proposal.get("id").toString()).get("status"));
    assertEquals(1, s.number("select count(*) from participation"));
  }

  @Test
  void changedPlanInvalidatesPendingAndRetainsVersions() {
    String conversation = agent.conversation();
    var result = agent.message(conversation, "查找", null);
    var candidate = ((List<Map<String, Object>>) result.get("candidates")).get(0);
    var proposal = agent.select(conversation, candidate.get("candidateId").toString(), "DIRECT");
    agent.message(conversation, "重新规划", null);
    assertEquals(2, s.number("select count(*) from ai_activity_plan"));
    assertEquals("REJECTED", agent.confirm(proposal.get("id").toString()).get("status"));
    assertThrows(
        Problem.class,
        () -> agent.select(conversation, candidate.get("candidateId").toString(), "DIRECT"));
    assertEquals(0, s.number("select count(*) from participation"));
  }

  @Test
  void conversationOwnershipSurvivesMemoryLoss() {
    String c = agent.conversation();
    agent.message(c, "活动", null);
    identity(2);
    assertThrows(Problem.class, () -> agent.recover(c));
    identity(1);
    assertNotNull(agent.recover(c));
  }

  @Test
  void reconstructingAgentAfterRedisLossRecoversSavedPlanAndPendingProposal() {
    String c = agent.conversation();
    var result = agent.message(c, "查询", null);
    var card = ((List<Map<String, Object>>) result.get("candidates")).get(0);
    var proposal = agent.select(c, card.get("candidateId").toString(), "DIRECT");
    redis.delete(List.of("ai:v2:checkpoint:1:" + c, "ai:v2:chat:1:" + c));
    var fresh =
        new GuideAgentV2(
            graph,
            new GuidePersistence(
                s, access, proposals(), new GuideQueryService(s), json, redis, manager),
            access,
            s);
    assertNotNull(fresh.recover(c));
    assertEquals("SUCCEEDED", fresh.confirm(proposal.get("id").toString()).get("status"));
    invariant();
  }

  AiBookingProposalService proposals() {
    return new AiBookingProposalService(s, access, booking, new GuideQueryService(s), manager);
  }

  @Test
  void concurrentConfirmationsCreateOnlyOneQualification() throws Exception {
    String c = agent.conversation();
    var result = agent.message(c, "查询", null);
    var card = ((List<Map<String, Object>>) result.get("candidates")).get(0);
    String id = agent.select(c, card.get("candidateId").toString(), "DIRECT").get("id").toString();
    race(
        () -> {
          identity(1);
          agent.confirm(id);
        },
        () -> {
          identity(1);
          agent.confirm(id);
        });
    assertEquals("SUCCEEDED", agent.confirm(id).get("status"));
    assertEquals(1, s.number("select count(*) from participation"));
    assertEquals(1, s.number("select count(*) from outbox"));
    invariant();
  }

  @Test
  void concurrentRevisionAndConfirmationRespectClaimBoundary() throws Exception {
    String c = agent.conversation();
    var result = agent.message(c, "查询", null);
    var card = ((List<Map<String, Object>>) result.get("candidates")).get(0);
    String id = agent.select(c, card.get("candidateId").toString(), "DIRECT").get("id").toString();
    race(
        () -> {
          identity(1);
          agent.message(c, "换一个计划", null);
        },
        () -> {
          identity(1);
          try {
            agent.confirm(id);
          } catch (Problem e) {
            assertEquals("CONVERSATION_BUSY", e.code);
          }
        });
    String status = proposals().get(id).get("status").toString();
    assertTrue(Set.of("REJECTED", "SUCCEEDED").contains(status));
    assertEquals(
        status.equals("SUCCEEDED") ? 1 : 0, s.number("select count(*) from participation"));
    invariant();
  }

  @Test
  void cancelledGraphNeverCreatesParticipation() {
    String c = agent.conversation();
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            agent.message(
                c,
                "查询",
                new AiGuideGraph.TurnEvents() {
                  public void emit(String e, Object v) {}

                  public reactor.core.publisher.Mono<Void> cancelled() {
                    return reactor.core.publisher.Mono.empty();
                  }

                  public boolean isCancelled() {
                    return true;
                  }
                }));
    assertEquals(0, s.number("select count(*) from participation"));
    assertEquals(0, s.number("select count(*) from ai_activity_plan"));
  }
}
