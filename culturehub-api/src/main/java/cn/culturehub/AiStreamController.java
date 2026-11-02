package cn.culturehub;

import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.http.MediaType;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.*;

/** Bounded MVC streaming workers. Authentication is captured from the JWT HTTP request. */
@RestController
@RequestMapping("/api/v1/ai/conversations")
public class AiStreamController {
  final AiGuideGraph guide;
  final ExecutorService executor =
      new ThreadPoolExecutor(
          0,
          4,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            var t = new Thread(r, "ai-sse");
            t.setDaemon(true);
            return t;
          });

  public AiStreamController(AiGuideGraph guide) {
    this.guide = guide;
  }

  @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream(@PathVariable String id, @Valid @RequestBody AiController.Message message) {
    guide.checkConversation(
        id); // before headers: missing/foreign conversations return ordinary 409/403.
    var emitter = new SseEmitter(95_000L);
    var channel = new Channel(emitter);
    var future = new AtomicReference<Future<?>>();
    Runnable cancel =
        () -> {
          channel.close();
          var f = future.get();
          if (f != null) f.cancel(true);
        };
    emitter.onCompletion(cancel);
    emitter.onError(error -> cancel.run());
    emitter.onTimeout(
        () -> {
          channel.error("AI_TIMEOUT", "回答超时，请重试；未提交预约");
          cancel.run();
          emitter.complete();
        });
    try {
      var task =
          executor.submit(
              new DelegatingSecurityContextRunnable(
                  () -> {
                    try {
                      channel.emit("status", Map.of("node", "CONNECTED"));
                      var result = guide.message(id, message.message(), channel);
                      channel.emit("done", result);
                    } catch (Exception error) {
                      if (!channel.isCancelled()) {
                        if (error instanceof Problem p) channel.error(p.code, p.getMessage());
                        else channel.error("AI_FAILED", "回答暂不可用，请稍后重试；未提交预约");
                      }
                    } finally {
                      emitter.complete();
                      channel.close();
                    }
                  }));
      future.set(task);
      if (channel.isCancelled()) task.cancel(true);
    } catch (RejectedExecutionException full) {
      channel.close();
      throw new Problem("AI_BUSY", "导览繁忙，请稍后重试", 503);
    }
    return emitter;
  }

  static class Channel implements AiGuideGraph.TurnEvents {
    final SseEmitter emitter;
    final AtomicBoolean closed = new AtomicBoolean();
    final Sinks.One<Void> cancellation = Sinks.one();
    long sequence;

    Channel(SseEmitter emitter) {
      this.emitter = emitter;
    }

    public boolean isCancelled() {
      return closed.get();
    }

    public Mono<Void> cancelled() {
      return cancellation.asMono();
    }

    void close() {
      if (closed.compareAndSet(false, true)) cancellation.tryEmitEmpty();
    }

    public synchronized void emit(String event, Object value) {
      if (isCancelled()) throw new CancellationException();
      try {
        emitter.send(SseEmitter.event().id(Long.toString(++sequence)).name(event).data(value));
      } catch (IOException | IllegalStateException disconnected) {
        close();
        throw new CancellationException("SSE disconnected");
      }
    }

    void error(String code, String message) {
      try {
        emit("error", Map.of("code", code, "message", message));
      } catch (Exception ignored) {
      }
    }
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }
}
