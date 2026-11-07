package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import cn.culturehub.Access;
import cn.culturehub.Store;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

/** Runs only with an explicitly configured, isolated Chroma and actual embedding endpoint. */
@EnabledIfSystemProperty(named = "culturehub.chromaLocalTest", matches = "true")
class GuideChromaLocalTest {
  @Test
  void manifestToEmbeddingToScopedGrade() throws Exception {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(2000);
    requestFactory.setReadTimeout(5000);
    var api =
        ChromaApi.builder()
            .baseUrl(System.getProperty("chroma.test.url", "http://127.0.0.1:18090"))
            .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
            .build();
    var model =
        new OpenAiEmbeddingModel(
            OpenAiApi.builder()
                .baseUrl(System.getProperty("chroma.test.embedding.url", "http://127.0.0.1:18091"))
                .apiKey(System.getenv().getOrDefault("CHROMA_TEST_EMBEDDING_KEY", "loopback-test"))
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build(),
            MetadataMode.NONE,
            OpenAiEmbeddingOptions.builder()
                .model(System.getProperty("chroma.test.embedding.model", "all-MiniLM-L6-v2"))
                .build());
    String collection = "guide-round2-test-" + UUID.randomUUID();
    var vector =
        ChromaVectorStore.builder(api, model)
            .collectionName(collection)
            .initializeSchema(true)
            .build();
    vector.afterPropertiesSet();
    var redis = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("redis.port", 16379));
    redis.afterPropertiesSet();
    redis.start();
    var registry = new StringRedisTemplate(redis);
    var db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                System.getProperty(
                    "chroma.test.jdbc",
                    "jdbc:mysql://127.0.0.1:13306/culturehub_rich_demo?characterEncoding=UTF-8"),
                "root",
                ""));
    var rag =
        new GuideKnowledge(
            new StaticListableBeanFactory(Map.of("vectors", vector))
                .getBeanProvider(VectorStore.class),
            new Store(db),
            mock(Access.class),
            new ObjectMapper(),
            registry,
            collection);
    try {
      long museum = db.queryForObject("select id from venue where name=?", Long.class, "市文化博物馆");
      long community =
          db.queryForObject("select id from venue where name=?", Long.class, "社区文化艺术中心");
      vector.add(
          List.of(
              new Document(
                  "unrelated public corpus",
                  Map.of("visibility", "PUBLIC", "indexNamespace", "other-module"))));
      rag.reindex();
      assertEquals(
          1,
          vector
              .similaritySearch(
                  SearchRequest.builder()
                      .query("unrelated public corpus")
                      .filterExpression("indexNamespace == 'other-module'")
                      .topK(2)
                      .build())
              .size());
      var turn = new GuidePlanningTest().state(1, false);
      turn.identity =
          new Identity("c", "t", "trace", 1, "能否参加", LocalDateTime.of(2026, 11, 7, 9, 0));
      turn.retrievalNeeds =
          List.of(
              new RetrievalNeed("museum", "儿童年龄规则", museum, null, "AGE_RULE", true),
              new RetrievalNeed("community", "儿童年龄规则", community, null, "AGE_RULE", true),
              new RetrievalNeed("missing", "家长陪同要求", community, null, "GUARDIAN_RULE", true));
      turn.retrievalPlan = rag.plan(turn);
      turn.retrievedDocuments = rag.retrieve(turn);
      assertTrue(
          turn.retrievedDocuments.stream()
              .anyMatch(d -> d.documentId().equals("museum-child-policy")));
      assertTrue(
          turn.retrievedDocuments.stream()
              .anyMatch(d -> d.documentId().equals("community-child-policy")));
      assertTrue(
          turn.retrievedDocuments.stream()
              .noneMatch(d -> d.documentId().equals("museum-child-expired")));
      turn.rerankedDocuments = rag.rerank(turn.retrievedDocuments, "儿童年龄规则");
      var grades = rag.grade(turn);
      assertEquals(GradeStatus.SUPPORTED, grades.get(0).status());
      assertEquals(GradeStatus.SUPPORTED, grades.get(1).status());
      assertTrue(grades.get(0).evidenceIds().stream().allMatch(id -> id.startsWith("museum-")));
      assertTrue(grades.get(1).evidenceIds().stream().allMatch(id -> id.startsWith("community-")));
      assertEquals(GradeStatus.INSUFFICIENT, grades.get(2).status());
    } finally {
      registry.delete(rag.indexKey);
      redis.destroy();
      api.deleteCollection("default_tenant", "default_database", collection);
    }
  }
}
