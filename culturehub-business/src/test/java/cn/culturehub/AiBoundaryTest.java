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
import org.springframework.jdbc.core.JdbcTemplate;

class AiBoundaryTest {
  AiService service(ChatModel model, Store s, Access a, Operations o) {
    ObjectProvider<ChatModel> models = mock(ObjectProvider.class);
    when(models.getIfAvailable()).thenReturn(model);
    return new AiService(
        models,
        mock(ObjectProvider.class),
        s,
        a,
        o,
        Validation.buildDefaultValidatorFactory().getValidator(),
        new ObjectMapper().findAndRegisterModules());
  }

  @Test
  void reportWithoutReviewsUsesJavaMetricsAndNoModel() {
    Store s = mock(Store.class);
    when(s.now()).thenReturn(java.time.LocalDateTime.of(2026, 10, 31, 12, 0));
    when(s.one(contains("activity_session"), any()))
        .thenReturn(
            Map.of(
                "status", "PUBLISHED", "end_at", java.time.LocalDateTime.of(2026, 10, 30, 12, 0)));
    var db = mock(JdbcTemplate.class);
    try {
      var f = Store.class.getDeclaredField("db");
      f.setAccessible(true);
      f.set(s, db);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    Operations ops = mock(Operations.class);
    when(ops.facts(1)).thenReturn(Map.of("reserved", 3, "checkedIn", 2, "checkinRate", 2.0 / 3));
    ChatModel model = mock(ChatModel.class);
    var result = (Map<?, ?>) service(model, s, mock(Access.class), ops).report(1);
    assertEquals(3, ((Map<?, ?>) result.get("metrics")).get("reserved"));
    verifyNoInteractions(model);
  }

  @Test
  void planPermissionCheckedBeforeModel() {
    Access a = mock(Access.class);
    doThrow(new Problem("FORBIDDEN", "denied", 403)).when(a).venue(2);
    ChatModel model = mock(ChatModel.class);
    var ai = service(model, mock(Store.class), a, mock(Operations.class));
    assertThrows(
        Problem.class, () -> ai.plan(Map.of("venue_id", 2, "theme", "art", "minutes", 60)));
    verifyNoInteractions(model);
  }

  @Test
  void invalidReviewReferenceCannotOverwriteExistingReport() {
    Store store = mock(Store.class);
    var db = mock(JdbcTemplate.class);
    try {
      var field = Store.class.getDeclaredField("db");
      field.setAccessible(true);
      field.set(store, db);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    when(store.now()).thenReturn(java.time.LocalDateTime.of(2026, 11, 1, 12, 0));
    when(store.one(contains("activity_session"), any()))
        .thenReturn(
            Map.of(
                "status", "PUBLISHED", "end_at", java.time.LocalDateTime.of(2026, 10, 30, 12, 0)));
    when(db.queryForList(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", 1L, "rating", 5, "content", "讲解清楚")));
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"positives\":[\"讲解清楚\"],\"issues\":[],\"suggestions\":[],\"reviewIds\":[999]}")))));
    Operations operations = mock(Operations.class);
    when(operations.facts(1)).thenReturn(Map.of("reserved", 14, "checkedIn", 8));
    assertEquals(
        "AI_OUTPUT_INVALID",
        assertThrows(
                Problem.class,
                () -> service(model, store, mock(Access.class), operations).report(1))
            .code);
    verify(db, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void disabledCredentialAndOverflowDurationAreRejected() {
    var openai = mock(org.springframework.ai.openai.OpenAiChatModel.class);
    assertEquals(
        "AI_UNAVAILABLE",
        assertThrows(
                Problem.class,
                () ->
                    service(openai, mock(Store.class), mock(Access.class), mock(Operations.class))
                        .client())
            .code);
    var ai = service(null, mock(Store.class), mock(Access.class), mock(Operations.class));
    assertEquals(
        400,
        assertThrows(
                Problem.class,
                () -> ai.plan(Map.of("venue_id", 1, "theme", "文化活动", "minutes", 4294967356L)))
            .status);
  }
}
