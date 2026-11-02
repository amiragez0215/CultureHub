package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

class AiGuideGraphTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final Store store = mock(Store.class);
  final Access access = mock(Access.class);
  final ChatModel model = mock(ChatModel.class);
  final ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
  final ObjectProvider<VectorStore> vectors = mock(ObjectProvider.class);
  final AiBookingProposalService proposals = mock(AiBookingProposalService.class);
  final GuideQueryService queries = spy(new GuideQueryService(store));
  final AtomicLong user = new AtomicLong(1);
  final Deque<String> replies = new ArrayDeque<>();
  AiGuideGraph guide;
  static final AiGuideGraph.Preferences PREF =
      new AiGuideGraph.Preferences(
          "2026-11-07", "2026-11-07", "12:00", "18:00", "HERITAGE", null, "剪纸", null, 8);

  @BeforeEach
  void setup() {
    when(store.now()).thenReturn(LocalDateTime.of(2026, 11, 1, 9, 0));
    when(access.user()).thenAnswer(i -> user.get());
    doAnswer(
            i -> {
              if ((long) i.getArgument(0) != user.get())
                throw new Problem("FORBIDDEN", "denied", 403);
              return null;
            })
        .when(access)
        .owner(anyLong());
    when(models.getIfAvailable()).thenReturn(model);
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            i -> {
              if (replies.isEmpty()) throw new AssertionError("Unexpected extra model call");
              return new ChatResponse(
                  List.of(new Generation(new AssistantMessage(replies.removeFirst()))));
            });
    doReturn(List.of(card(42, "2026-11-07T14:00:00"))).when(queries).search(any(), anyLong());
    doAnswer(i -> card((long) i.getArgument(0), "2026-11-07T14:00:00"))
        .when(queries)
        .current(anyLong());
    guide =
        new AiGuideGraph(
            new AiService(
                models,
                vectors,
                store,
                access,
                mock(Operations.class),
                Validation.buildDefaultValidatorFactory().getValidator(),
                json),
            access,
            queries,
            proposals,
            json);
  }

  Map<String, Object> card(long id, String start) {
    var c = new LinkedHashMap<String, Object>();
    c.put("sessionId", id);
    c.put("activityId", 10L);
    c.put("title", "非遗剪纸");
    c.put("category", "HERITAGE");
    c.put("audience", "ALL");
    c.put("description", "剪纸体验");
    c.put("venueName", "非遗馆");
    c.put("remaining", 2);
    c.put("waitlistPriority", 0);
    c.put("startAt", start);
    c.put("registrationStartAt", "2026-10-01T00:00:00");
    c.put("registrationEndAt", "2026-11-07T12:00:00");
    c.put("endAt", "2026-11-07T16:00:00");
    c.put("sessionStatus", "PUBLISHED");
    c.put("activityStatus", "PUBLISHED");
    c.put("venueStatus", "OPEN");
    return c;
  }

  String intent(AiGuideGraph.Preferences p, boolean clarify) throws Exception {
    return json.writeValueAsString(
        new AiGuideGraph.Intent(p, clarify, clarify ? "你希望哪一天参加？" : null));
  }

  void ready() throws Exception {
    replies.add(intent(PREF, false));
    replies.add("{\"explanation\":\"可以了解剪纸体验\",\"sessionIds\":[42]}");
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> candidates(Map<String, Object> r) {
    return (List<Map<String, Object>>) r.get("candidates");
  }

  static class RecordingEvents implements AiGuideGraph.TurnEvents {
    final List<String> names = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<Object> values = new java.util.concurrent.CopyOnWriteArrayList<>();
    final reactor.core.publisher.Sinks.One<Void> stop = reactor.core.publisher.Sinks.one();
    volatile boolean closed;

    public void emit(String name, Object value) {
      names.add(name);
      values.add(value);
    }

    public boolean isCancelled() {
      return closed;
    }

    public reactor.core.publisher.Mono<Void> cancelled() {
      return stop.asMono();
    }

    void cancel() {
      closed = true;
      stop.tryEmitEmpty();
    }
  }

  @Test
  void realStreamPublisherProducesMultipleSafeChunksBeforeVerifiedCards() throws Exception {
    ready();
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.just("可以", "体验剪纸。", "预", "约成功。", "请先选择场次。")
                .map(
                    text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text))))));
    var events = new RecordingEvents();
    String id = guide.conversation();
    var result = guide.message(id, "周六剪纸", events);
    assertEquals("RECOMMENDATION", result.get("type"));
    assertEquals(3, events.names.stream().filter("delta"::equals).count());
    assertEquals("proposal", events.names.get(events.names.size() - 1));
    assertFalse(result.get("explanation").toString().contains("预约成功"));
    assertEquals(1, candidates(result).size());
    assertTrue(guide.observers.isEmpty());
    assertFalse(guide.owned(id).busy);
    verify(model).stream(any(Prompt.class));
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void streamingProviderFailureReleasesConversationAndNoBooking() throws Exception {
    ready();
    when(model.stream(any(Prompt.class)))
        .thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("offline")));
    String id = guide.conversation();
    var events = new RecordingEvents();
    assertThrows(Problem.class, () -> guide.message(id, "周六剪纸", events));
    assertFalse(events.names.contains("proposal"));
    assertTrue(guide.observers.isEmpty());
    assertFalse(guide.owned(id).busy);
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void streamingTimeoutHasExplicitErrorCode() throws Exception {
    ready();
    when(model.call(any(Prompt.class)))
        .thenThrow(
            new IllegalStateException(new java.util.concurrent.TimeoutException("controlled")));
    var events = new RecordingEvents();
    assertEquals(
        "AI_TIMEOUT",
        assertThrows(Problem.class, () -> guide.message(guide.conversation(), "周六剪纸", events))
            .code);
    assertFalse(events.names.contains("proposal"));
    assertTrue(guide.observers.isEmpty());
  }

  @Test
  void optionalExplanationConnectionResetKeepsVerifiedCardsWithoutBooking() throws Exception {
    ready();
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.error(new java.net.SocketException("Connection reset")));
    var events = new RecordingEvents();
    var result = guide.message(guide.conversation(), "周六剪纸", events);
    assertEquals("RECOMMENDATION", result.get("phase"));
    assertEquals(1, candidates(result).size());
    assertTrue(result.get("explanation").toString().contains("连接中断"));
    assertTrue(events.names.contains("proposal"));
    assertTrue(guide.observers.isEmpty());
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void minimumRemainingIsVerifiedAndExplainsSinglePersonBoundary() throws Exception {
    var preferences =
        new AiGuideGraph.Preferences(null, null, null, null, null, "YOUTH", null, null, null, 3);
    replies.add(intent(preferences, false));
    replies.add("{\"explanation\":\"可以了解这项活动\",\"sessionIds\":[42]}");
    var valid = card(42, "2026-11-07T14:00:00");
    valid.put("remaining", 3);
    doReturn(List.of(valid)).when(queries).search(any(), anyLong());
    doReturn(valid).when(queries).current(42);
    String id = guide.conversation();
    var result = guide.message(id, "都可以，剩余人数达到3人以上的，我要带我老婆和孩子一起去");
    assertEquals(3, guide.owned(id).preferences.minimumRemaining());
    assertEquals(1, candidates(result).size());
    assertTrue(result.get("explanation").toString().contains("当前账号一次只能预约 1 个名额"));
    valid.put("remaining", 2);
    assertFalse(queries.matches(valid, preferences));
    assertThrows(
        Problem.class,
        () ->
            guide.validate(
                new AiGuideGraph.Preferences(
                    null, null, null, null, null, null, null, null, null, 0)));
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void streamCancellationStopsUpstreamAndNeverExposesCandidates() throws Exception {
    ready();
    var disposed = new java.util.concurrent.atomic.AtomicBoolean();
    var events =
        new RecordingEvents() {
          public void emit(String name, Object value) {
            super.emit(name, value);
            if (name.equals("delta")) cancel();
          }
        };
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.just("第一句话。", "第二句话。")
                .delayElements(Duration.ofMillis(10))
                .map(text -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
                .doFinally(signal -> disposed.set(true)));
    String id = guide.conversation();
    assertThrows(Problem.class, () -> guide.message(id, "周六剪纸", events));
    assertTrue(disposed.get());
    assertTrue(guide.observers.isEmpty());
    assertFalse(guide.owned(id).busy);
    assertFalse(events.names.contains("proposal"));
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void vagueQuestionClarifiesWithoutQueryOrBooking() throws Exception {
    replies.add(
        intent(
            new AiGuideGraph.Preferences(null, null, null, null, null, null, null, null, null),
            true));
    var r = guide.message(guide.conversation(), "周末想参加活动");
    assertEquals("CLARIFICATION", r.get("type"));
    assertTrue(candidates(r).isEmpty());
    verify(queries, never()).search(any(), anyLong());
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void changedDateReplacesPreferenceAndInvalidatesOldCandidate() throws Exception {
    ready();
    String id = guide.conversation();
    var first = guide.message(id, "周六下午剪纸");
    String old = candidates(first).get(0).get("candidateId").toString();
    var sunday =
        new AiGuideGraph.Preferences(
            "2026-11-08", "2026-11-08", "12:00", "18:00", "HERITAGE", null, "剪纸", null, 8);
    replies.add(intent(sunday, false));
    replies.add("{\"explanation\":\"周日的场次\",\"sessionIds\":[43]}");
    doReturn(List.of(card(43, "2026-11-08T14:00:00"))).when(queries).search(eq(sunday), eq(1L));
    doReturn(card(43, "2026-11-08T14:00:00")).when(queries).current(43);
    var second = guide.message(id, "改成周日下午，其他条件不变");
    assertEquals(sunday, second.get("preferences"));
    assertEquals(43L, candidates(second).get(0).get("sessionId"));
    assertEquals(
        "PROPOSAL_EXPIRED",
        assertThrows(Problem.class, () -> guide.select(id, old, "DIRECT")).code);
  }

  @Test
  void emptySearchDoesNotAskModelToInventCandidates() throws Exception {
    replies.add(intent(PREF, false));
    doReturn(List.of()).when(queries).search(any(), anyLong());
    var r = guide.message(guide.conversation(), "周六剪纸");
    assertEquals("NO_RESULT", r.get("type"));
    verify(model, times(1)).call(any(Prompt.class));
  }

  @Test
  void inventedSessionIsRejectedAndRetryIsBounded() throws Exception {
    replies.add(intent(PREF, false));
    for (int n = 0; n < 2; n++) replies.add("{\"explanation\":\"预约成功\",\"sessionIds\":[99999]}");
    var r = guide.message(guide.conversation(), "周六剪纸");
    assertEquals("NO_RESULT", r.get("type"));
    assertTrue(candidates(r).isEmpty());
    verify(queries, times(2)).search(any(), anyLong());
    verify(queries, never()).current(99999);
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void unavailableKnowledgeDegradesToRealData() throws Exception {
    ready();
    VectorStore v = mock(VectorStore.class);
    when(vectors.getIfAvailable()).thenReturn(v);
    when(v.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
        .thenThrow(new IllegalStateException("offline"));
    var r = guide.message(guide.conversation(), "周六剪纸");
    assertEquals("RECOMMENDATION", r.get("type"));
    assertTrue(r.get("knowledgeNotice").toString().contains("降级"));
  }

  @Test
  void foreignConversationIsRejected() {
    String id = guide.conversation();
    user.set(2);
    assertEquals(403, assertThrows(Problem.class, () -> guide.message(id, "hello")).status);
    verifyNoInteractions(model);
  }

  @Test
  void noModelDoesNotPreventGraphConstructionButRequestIsUnavailable() {
    when(models.getIfAvailable()).thenReturn(null);
    String id = guide.conversation();
    assertEquals(
        "AI_UNAVAILABLE", assertThrows(Problem.class, () -> guide.message(id, "周末活动")).code);
  }

  @Test
  void toolFailureFailsWithoutWriteAndWithoutLoop() throws Exception {
    replies.add(intent(PREF, false));
    doThrow(new IllegalStateException("offline")).when(queries).search(any(), anyLong());
    assertEquals(
        "AI_FAILED",
        assertThrows(Problem.class, () -> guide.message(guide.conversation(), "周六活动")).code);
    verify(queries, times(1)).search(any(), anyLong());
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void modelCannotClaimSuccessWithoutConfirmation() throws Exception {
    replies.add(intent(PREF, false));
    replies.add("{\"explanation\":\"已经预约成功\",\"sessionIds\":[42]}");
    var r = guide.message(guide.conversation(), "就这个吧");
    assertTrue(r.get("explanation").toString().contains("尚未提交预约"));
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void selectionRequiresOwnedFreshCandidateAndStillDoesNotBook() throws Exception {
    ready();
    String id = guide.conversation();
    var r = guide.message(id, "周六剪纸");
    when(proposals.create(id, 42, false)).thenReturn(Map.of("status", "PENDING"));
    assertEquals(
        "PENDING",
        guide
            .select(id, candidates(r).get(0).get("candidateId").toString(), "DIRECT")
            .get("status"));
    verify(proposals, times(1)).create(id, 42, false);
    verify(proposals, never()).confirm(anyString(), anyBoolean());
  }

  @Test
  void endedOrUnpublishedCandidateCannotBeConfirmed() throws Exception {
    replies.add(intent(PREF, false));
    for (int n = 0; n < 2; n++) replies.add("{\"explanation\":\"参考\",\"sessionIds\":[42]}");
    var closed = card(42, "2026-11-07T14:00:00");
    closed.put("activityStatus", "CANCELLED");
    doReturn(closed).when(queries).current(42);
    assertEquals("NO_RESULT", guide.message(guide.conversation(), "周六剪纸").get("type"));
  }

  @Test
  void expiredMemoryCannotStartAnotherTurn() {
    String id = guide.conversation();
    guide.conversations.get(id).last = Instant.now().minusSeconds(1900);
    assertEquals(
        "CONVERSATION_EXPIRED", assertThrows(Problem.class, () -> guide.message(id, "周六")).code);
  }

  @Test
  void venueInformationDoesNotDemandAnActivityDate() throws Exception {
    replies.add(
        json.writeValueAsString(
            new AiGuideGraph.Intent(
                new AiGuideGraph.Preferences(null, null, null, null, null, null, null, "非遗馆", null),
                false,
                null,
                "VENUE_INFO")));
    replies.add("{\"explanation\":\"非遗馆开放时间为09:00至17:00。\",\"sessionIds\":[]}");
    doReturn(List.of(Map.of("name", "非遗馆", "opening_hours", "09:00至17:00")))
        .when(queries)
        .venues("非遗馆");
    assertEquals("MESSAGE", guide.message(guide.conversation(), "非遗馆几点开放？").get("type"));
    verify(queries, never()).search(any(), anyLong());
    verify(proposals, never()).create(anyString(), anyLong(), anyBoolean());
  }

  @Test
  void unavailableKnowledgeProviderAlsoDegradesSafely() throws Exception {
    ready();
    when(vectors.getIfAvailable()).thenThrow(new IllegalStateException("unavailable"));
    var result = guide.message(guide.conversation(), "周六剪纸");
    assertEquals("RECOMMENDATION", result.get("type"));
    assertTrue(result.get("knowledgeNotice").toString().contains("降级"));
  }

  @Test
  void toolBudgetFailurePropagatesThroughTheRealSpringToolManager() {
    var tools = new AiGuideGraph.ReadTools(List.of(card(42, "2026-11-07T14:00:00")), "budget-test");
    var options =
        org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
            .toolCallbacks(org.springframework.ai.support.ToolCallbacks.from(tools))
            .build();
    var manager =
        org.springframework.ai.model.tool.DefaultToolCallingManager.builder()
            .toolExecutionExceptionProcessor(
                new org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor(
                    true))
            .build();
    var output =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "test-call", "function", "availableSessions", "{}")))
            .build();
    var response = new ChatResponse(List.of(new Generation(output)));
    var prompt = new Prompt("budget test", options);
    for (int i = 0; i < 4; i++) assertNotNull(manager.executeToolCalls(prompt, response));
    var error =
        assertThrows(
            org.springframework.ai.tool.execution.ToolExecutionException.class,
            () -> manager.executeToolCalls(prompt, response));
    assertEquals("AI_TOOL_LIMIT", ((Problem) error.getCause()).code);
  }
}
