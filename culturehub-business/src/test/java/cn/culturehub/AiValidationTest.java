package cn.culturehub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

class AiValidationTest {
  @Test
  void structuredPlanHasValidatedDuration() {
    ChatModel model = mock(ChatModel.class);
    ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(model);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"title\":\"Workshop\",\"description\":\"Learn\",\"steps\":[{\"name\":\"Practice\",\"minutes\":60,\"description\":\"Work\"}],\"notice\":\"On"
                                + " time\",\"copywriting\":\"Welcome\"}")))));
    Store store = mock(Store.class);
    when(store.one(anyString(), any())).thenReturn(Map.of("name", "Museum", "introduction", "Art"));
    Access access = mock(Access.class);
    var ai =
        new AiService(
            provider,
            mock(ObjectProvider.class),
            store,
            access,
            mock(Operations.class),
            Validation.buildDefaultValidatorFactory().getValidator(),
            new ObjectMapper());
    assertEquals(
        60, ai.plan(Map.of("venue_id", 1, "theme", "Art", "minutes", 60)).steps().get(0).minutes());
    assertThrows(
        Problem.class, () -> ai.plan(Map.of("venue_id", 1, "theme", "Art", "minutes", 30)));
    verify(access, times(2)).venue(1);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"title\":\"test\",\"description\":\"test\",\"steps\":[{\"name\":\"a\",\"minutes\":2147483647,\"description\":\"test\"},{\"name\":\"b\",\"minutes\":2147483647,\"description\":\"test\"},{\"name\":\"c\",\"minutes\":62,\"description\":\"test\"}],\"notice\":\"test\",\"copywriting\":\"test\"}")))));
    assertEquals(
        "AI_OUTPUT_INVALID",
        assertThrows(
                Problem.class, () -> ai.plan(Map.of("venue_id", 1, "theme", "test", "minutes", 60)))
            .code);
  }

  @Test
  void badEventRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Event("x", "UNKNOWN", 1, 1, 1, java.time.Instant.now(), Map.of()).validate());
  }
}
