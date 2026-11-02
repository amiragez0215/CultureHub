package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiStreamControllerTest {
  AiGuideGraph guide;
  AiStreamController controller;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    guide = mock(AiGuideGraph.class);
    controller = new AiStreamController(guide);
    mvc =
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ErrorAdvice()).build();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("17", "", List.of()));
  }

  @AfterEach
  void close() {
    controller.stop();
    SecurityContextHolder.clearContext();
  }

  MvcResult open(String message) throws Exception {
    return mvc.perform(
            post("/api/v1/ai/conversations/c/messages/stream")
                .contentType("application/json")
                .content(message))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  String finish(MvcResult result) throws Exception {
    result.getAsyncResult(3000);
    return mvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void mvcStreamsEventsWithCapturedAuthenticationAndCleansWorkerContext() throws Exception {
    var owner = new java.util.concurrent.atomic.AtomicReference<String>();
    when(guide.message(eq("c"), eq("问题"), any()))
        .thenAnswer(
            invocation -> {
              owner.set(SecurityContextHolder.getContext().getAuthentication().getName());
              AiGuideGraph.TurnEvents stream = invocation.getArgument(2);
              stream.emit("delta", Map.of("text", "第一句。"));
              stream.emit("delta", Map.of("text", "第二句。"));
              stream.emit("proposal", Map.of("candidates", List.of()));
              return Map.of("phase", "MESSAGE", "explanation", "第一句。第二句。");
            });
    String body = finish(open("{\"message\":\"问题\"}"));
    assertEquals("17", owner.get());
    assertTrue(body.indexOf("event:status") < body.indexOf("event:delta"));
    assertTrue(body.indexOf("event:proposal") < body.indexOf("event:done"));
    assertTrue(body.contains("第一句。"));
    assertTrue(body.contains("第二句。"));
    assertEquals("17", SecurityContextHolder.getContext().getAuthentication().getName());
    assertNull(
        controller
            .executor
            .submit(() -> SecurityContextHolder.getContext().getAuthentication())
            .get(1, TimeUnit.SECONDS));
  }

  @Test
  void providerTimeoutIsAnErrorEventWithoutStackOrDone() throws Exception {
    when(guide.message(anyString(), anyString(), any()))
        .thenThrow(new Problem("AI_TIMEOUT", "回答超时", 503));
    String body = finish(open("{\"message\":\"问题\"}"));
    assertTrue(body.contains("event:error"));
    assertTrue(body.contains("AI_TIMEOUT"));
    assertFalse(body.contains("event:done"));
    assertFalse(body.contains("Exception"));
  }

  @Test
  void authorizationAndValidationFailBeforeSseHeaders() throws Exception {
    doThrow(new Problem("FORBIDDEN", "无权访问", 403)).when(guide).checkConversation("other");
    mvc.perform(
            post("/api/v1/ai/conversations/other/messages/stream")
                .contentType("application/json")
                .content("{\"message\":\"问题\"}"))
        .andExpect(status().isForbidden())
        .andExpect(request().asyncNotStarted());
    mvc.perform(
            post("/api/v1/ai/conversations/c/messages/stream")
                .contentType("application/json")
                .content("{\"message\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(request().asyncNotStarted());
    verify(guide, never()).message(anyString(), anyString(), any());
  }

  @Test
  void channelCancellationIsIdempotentAndStopsFutureEmits() {
    var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
    var channel = new AiStreamController.Channel(emitter);
    channel.close();
    channel.close();
    assertTrue(channel.isCancelled());
    assertNull(channel.cancelled().block(java.time.Duration.ofMillis(100)));
    assertThrows(CancellationException.class, () -> channel.emit("delta", Map.of("text", "late")));
  }

  @Test void saturatedWorkerPoolFailsFastInsteadOfQueueingUnboundedRequests()throws Exception {
    var started=new CountDownLatch(4);var release=new CountDownLatch(1);
    try{
      for(int i=0;i<4;i++)controller.executor.submit(()->{started.countDown();try{release.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}});
      assertTrue(started.await(1,TimeUnit.SECONDS));
      mvc.perform(post("/api/v1/ai/conversations/c/messages/stream").contentType("application/json").content("{\"message\":\"问题\"}"))
        .andExpect(status().isServiceUnavailable()).andExpect(request().asyncNotStarted());
      verify(guide,never()).message(anyString(),anyString(),any());
    }finally{release.countDown();}
  }

  @Test void disconnectedTransportClosesChannelAndCancellationSignal()throws Exception {
    var emitter=mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
    doThrow(new java.io.IOException("client disconnected")).when(emitter).send(any(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder.class));
    var channel=new AiStreamController.Channel(emitter);
    assertThrows(CancellationException.class,()->channel.emit("delta",Map.of("text","一句话。")));
    assertTrue(channel.isCancelled());assertNull(channel.cancelled().block(java.time.Duration.ofMillis(100)));
  }
}
