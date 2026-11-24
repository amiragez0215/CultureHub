package cn.culturehub.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeIntegrityTest {
  @Test
  void chunksAreStableAndPreserveScopeAndCanonicalHashes() {
    var metadata = Map.<String, Object>of("scope", "VENUE", "visibility", "PUBLIC",
        "knowledge_type", "AGE_RULE", "source", "test-source", "venue_id", 12L);
    String text = "可供检索的独立场馆规则。".repeat(200);
    var first = KnowledgeJobs.chunk("doc-1", 3, metadata, text);
    var second = KnowledgeJobs.chunk("doc-1", 3, metadata, text);
    assertTrue(first.size() > 1);
    for (int i = 0; i < first.size(); i++) {
      var chunk = first.get(i);
      assertEquals(second.get(i).getId(), chunk.getId());
      assertEquals(12L, chunk.getMetadata().get("venueId"));
      assertEquals("test-source", chunk.getMetadata().get("source"));
      assertEquals(3, chunk.getMetadata().get("version"));
      assertEquals(KnowledgeDocuments.hexDigest(chunk.getText().getBytes(StandardCharsets.UTF_8)),
          chunk.getMetadata().get("contentHash"));
      assertTrue(chunk.getText().length() <= 1500);
    }
  }

  @Test
  void malformedMetadataFailsBeforeObjectsOrCoreAreTouched() {
    var core = mock(CoreKnowledgeAccess.class);
    var objects = mock(io.minio.MinioClient.class);
    var database = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
    var service = new KnowledgeDocuments(database, manager, objects, core, "a".repeat(32), "test");
    var problem = assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> service.upload(null, new KnowledgeDocuments.Metadata(null, null, null, "PUBLIC",
            "AGE_RULE", "source", "title", null), "test.md", new byte[]{1}));
    assertEquals(400, problem.getStatusCode().value());
    verifyNoInteractions(core, objects, database);
  }
}
