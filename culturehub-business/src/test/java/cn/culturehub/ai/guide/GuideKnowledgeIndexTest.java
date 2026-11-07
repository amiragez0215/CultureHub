package cn.culturehub.ai.guide;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.culturehub.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

class GuideKnowledgeIndexTest {
  @Test
  void failedStageCannotChangeRegistryAndNeverDeletesOtherData() throws Exception {
    var db = mock(JdbcTemplate.class);
    when(db.queryForList(contains("from venue"), anyString()))
        .thenReturn(List.of(Map.of("venue_id", 5L)));
    when(db.queryForList(contains("from activity"), anyString(), anyString()))
        .thenReturn(List.of(Map.of("id", 7L, "venue_id", 5L)));
    var vector = mock(VectorStore.class);
    var redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    var values = (ValueOperations<String, String>) mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var json = new ObjectMapper();
    var knowledge =
        new GuideKnowledge(
            new StaticListableBeanFactory(Map.of("vectors", vector))
                .getBeanProvider(VectorStore.class),
            new Store(db),
            mock(Access.class),
            json,
            redis,
            "fixture");
    var writes = ArgumentCaptor.forClass(String.class);
    knowledge.reindex();
    verify(values).set(eq(knowledge.indexKey), writes.capture());
    var snapshot = json.readValue(writes.getValue(), GuideKnowledgeIndex.Snapshot.class);
    when(values.get(knowledge.indexKey)).thenReturn(writes.getValue());
    @SuppressWarnings("unchecked")
    var uploaded =
        (ArgumentCaptor<List<Document>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
    verify(vector).add(uploaded.capture());
    var original = uploaded.getValue().get(0);
    assertTrue(knowledge.trustedChunk(original));
    assertFalse(knowledge.trustedChunk(new Document("forged", original.getMetadata())));
    doThrow(new IllegalStateException("Interrupted upsert")).when(vector).add(anyList());
    assertEquals("RAG_INDEX_WRITE_FAILED", assertThrows(Problem.class, knowledge::reindex).code);
    assertEquals(snapshot, knowledge.activeSnapshot());
    verify(values, times(1)).set(anyString(), anyString());
    verify(vector, never()).delete(anyString());
    verify(vector, never()).delete(anyList());
  }

  @Test
  void rejectsIncompleteMetadataAndMissingRequiredCatalog() {
    var db = mock(JdbcTemplate.class);
    var json = new ObjectMapper();
    var index = new GuideKnowledgeIndex(new Store(db), json);
    var incomplete = json.createArrayNode();
    incomplete.addObject().put("file", "venue-rules.md");
    assertThrows(Problem.class, () -> index.stage(incomplete));
    assertThrows(Problem.class, index::stage);
  }
}
