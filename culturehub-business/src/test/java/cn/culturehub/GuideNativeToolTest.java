package cn.culturehub;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

class GuideNativeToolTest {
  @Test
  void nativeResponseInvokesTheSpringCallbackExactlyOnce() {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            call -> {
              Prompt prompt = call.getArgument(0);
              var options = (ToolCallingChatOptions) prompt.getOptions();
              assertEquals(
                  List.of("getSessionDetails"),
                  options.getToolCallbacks().stream()
                      .map(t -> t.getToolDefinition().name())
                      .toList());
              assertFalse(options.getInternalToolExecutionEnabled());
              return response("getSessionDetails", "{}");
            });
    var calls = new AtomicInteger();
    var turn = new Turn();
    var result =
        language(model)
            .sessionDetailsTool(
                turn,
                8,
                () -> {
                  calls.incrementAndGet();
                  return new ToolResult(
                      ToolName.SESSION_DETAILS,
                      ResultStatus.EMPTY,
                      List.of(),
                      List.of(),
                      List.of(),
                      LocalDateTime.now(),
                      null);
                });
    assertEquals(ResultStatus.EMPTY, result.status());
    assertEquals(1, calls.get());
    assertEquals(1, turn.nativeToolCalls);
    assertEquals(1, turn.llmCalls);
  }

  @Test
  void injectedNameOrIdentityIsRejectedBeforeJavaQueries() {
    var model = mock(ChatModel.class);
    var language = language(model);
    for (var invalid :
        List.of(response("createBooking", "{}"), response("getSessionDetails", "{\"userId\":5}"))) {
      when(model.call(any(Prompt.class))).thenReturn(invalid);
      var error =
          assertThrows(
              Problem.class,
              () ->
                  language.sessionDetailsTool(new Turn(), 8, () -> fail("No query is authorized")));
      assertEquals("INVALID_TOOL_CALL", error.code);
    }
  }

  private GuideLanguageModel language(ChatModel model) {
    var service = mock(AiService.class);
    var client = ChatClient.create(model);
    when(service.client()).thenReturn(client);
    return new GuideLanguageModel(service, new ObjectMapper());
  }

  private ChatResponse response(String tool, String arguments) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall("tool-1", "function", tool, arguments)))
                    .build())));
  }
}
