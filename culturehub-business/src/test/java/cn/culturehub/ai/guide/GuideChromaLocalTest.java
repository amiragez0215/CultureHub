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
    var db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                System.getProperty(
                    "chroma.test.jdbc",
                    "jdbc:mysql://127.0.0.1:13306/culturehub_rich_demo?characterEncoding=UTF-8"),
                "root",
                ""));
    withChroma(
        db,
        (vector, rag, offline) -> {
          long museum =
              db.queryForObject("select id from venue where name=?", Long.class, "市文化博物馆");
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
                  new RetrievalNeed("missing", "家长陪同要求", community, null, "GUARDIAN_RULE", true),
                  new RetrievalNeed(
                      "other-activity", "活动背景", museum, 1000000L, "ACTIVITY_BACKGROUND", false));
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
          turn.rerankedDocuments = rag.rerank(turn);
          var grades = rag.grade(turn);
          assertEquals(GradeStatus.SUPPORTED, grades.get(0).status());
          assertEquals(GradeStatus.SUPPORTED, grades.get(1).status());
          assertTrue(grades.get(0).evidenceIds().stream().allMatch(id -> id.startsWith("museum-")));
          assertTrue(
              grades.get(1).evidenceIds().stream().allMatch(id -> id.startsWith("community-")));
          assertEquals(GradeStatus.INSUFFICIENT, grades.get(2).status());
          assertEquals(GradeStatus.INSUFFICIENT, grades.get(3).status());
          System.out.printf(
              "Chroma expected scoped policy documents recalled: %d/2; first museum recall: %s;"
                  + " rerank first: %s%n",
              turn.retrievedDocuments.stream()
                  .filter(
                      d ->
                          Set.of("museum-child-policy", "community-child-policy")
                              .contains(d.documentId()))
                  .count(),
              turn.retrievedDocuments.stream()
                  .filter(d -> "museum".equals(d.questionId()))
                  .findFirst()
                  .map(Evidence::documentId)
                  .orElse("none"),
              turn.rerankedDocuments.stream()
                  .filter(d -> "museum".equals(d.questionId()))
                  .findFirst()
                  .map(Evidence::documentId)
                  .orElse("none"));
          var generation = rag.activeSnapshot().generation();
          offline.set(true);
          assertEquals(
              "RAG_INDEX_WRITE_FAILED",
              assertThrows(cn.culturehub.Problem.class, rag::reindex).code);
          assertEquals(generation, rag.activeSnapshot().generation());
          turn.deadlineEpochMillis = System.currentTimeMillis() + 30000;
          turn.retrievedDocuments = rag.retrieve(turn);
          turn.rerankedDocuments = rag.rerank(turn);
          assertTrue(rag.grade(turn).stream().allMatch(g -> g.status() == GradeStatus.UNAVAILABLE));
          offline.set(false);
          turn.retrievedDocuments = rag.retrieve(turn);
          turn.rerankedDocuments = rag.rerank(turn);
          assertEquals(GradeStatus.SUPPORTED, rag.grade(turn).get(0).status());
          System.out.println(
              "Chroma injected transport outage: UNAVAILABLE; generation kept; old corpus usable"
                  + " after recovery");
        });
  }

  @Test
  void standaloneFictitiousCorpusResolvesBothDirectionsWithoutDemoDatabase() throws Exception {
    String server =
        System.getProperty("chroma.test.fixture.server", "jdbc:mysql://127.0.0.1:13306");
    String user = System.getenv().getOrDefault("CHROMA_TEST_MYSQL_USER", "root");
    String password = System.getenv().getOrDefault("CHROMA_TEST_MYSQL_PASSWORD", "");
    var bootstrap =
        new DriverManagerDataSource(server + "/?characterEncoding=UTF-8", user, password);
    try (var connection = bootstrap.getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
          connection,
          new org.springframework.core.io.support.EncodedResource(
              new org.springframework.core.io.ClassPathResource("round3-rag/catalog.sql"),
              java.nio.charset.StandardCharsets.UTF_8));
    }
    var db =
        new JdbcTemplate(
            new DriverManagerDataSource(
                server + "/culturehub_round3_rag?characterEncoding=UTF-8", user, password));
    withChroma(
        db,
        (vector, rag, offline) -> {
          var staged = rag.index.stage("round3-rag/");
          rag.publish(vector, staged);
          var turn = new GuidePlanningTest().state(1, false);
          turn.identity = new Identity("c", "t", "trace", 1, "8 岁儿童规则", GuidePlanningTest.NOW);
          turn.deadlineEpochMillis = System.currentTimeMillis() + 60000;
          turn.retrievalNeeds =
              List.of(
                  new RetrievalNeed(
                      "denied",
                      "8 岁儿童年龄资格",
                      1L,
                      11L,
                      "AGE_RULE",
                      true,
                      RequirementTarget.AGE_ELIGIBILITY),
                  new RetrievalNeed("recommended", "8 岁儿童年龄资格", 1L, 12L, "AGE_RULE", true),
                  new RetrievalNeed("accompanied", "8 岁儿童年龄资格", 1L, 13L, "AGE_RULE", true),
                  new RetrievalNeed("conflict", "8 岁儿童年龄资格", 1L, 14L, "AGE_RULE", true),
                  new RetrievalNeed("history", "8 岁儿童年龄资格", 1L, 15L, "AGE_RULE", true),
                  new RetrievalNeed("wrong-venue", "8 岁儿童年龄资格", 2L, 11L, "AGE_RULE", true),
                  new RetrievalNeed("closed", "周日开门吗", 1L, null, "OPENING_HOURS", true),
                  new RetrievalNeed("open", "周日开门吗", 2L, null, "OPENING_HOURS", true));
          turn.retrievalPlan = rag.plan(turn);
          turn.retrievedDocuments = rag.retrieve(turn);
          turn.rerankedDocuments = rag.rerank(turn);
          var grades = rag.grade(turn);
          assertEquals(
              List.of(
                  GradeStatus.SUPPORTED,
                  GradeStatus.INSUFFICIENT,
                  GradeStatus.SUPPORTED,
                  GradeStatus.CONFLICTING,
                  GradeStatus.INSUFFICIENT,
                  GradeStatus.INSUFFICIENT,
                  GradeStatus.SUPPORTED,
                  GradeStatus.SUPPORTED),
              grades.stream().map(Grade::status).toList());
          assertEquals(Conclusion.DENIED, grades.get(0).conclusion());
          assertEquals(RequirementVerdict.NOT_MATCHED, grades.get(0).requirementVerdict());
          assertEquals(List.of("r3-a-exclusive:fixture-1:0"), grades.get(0).evidenceIds());
          assertEquals("须有家长或监护人陪同", grades.get(2).claims().get(0).condition());
          assertEquals(Conclusion.CLOSED, grades.get(6).conclusion());
          assertEquals(Conclusion.OPEN, grades.get(7).conclusion());
          turn.retrievalNeeds =
              List.of(
                  new RetrievalNeed("guardian-a", "儿童能否独自参加", 1L, null, "GUARDIAN_RULE", true),
                  new RetrievalNeed(
                      "guardian-b",
                      "儿童能否独自参加",
                      2L,
                      null,
                      "GUARDIAN_RULE",
                      true,
                      RequirementTarget.INDEPENDENT_ADMISSION));
          turn.retrievedDocuments = List.of();
          turn.retrievalPlan = rag.plan(turn);
          turn.retrievedDocuments = rag.retrieve(turn);
          turn.rerankedDocuments = rag.rerank(turn);
          var guardian = rag.grade(turn);
          assertEquals(Conclusion.ACCOMPANIMENT_REQUIRED, guardian.get(0).conclusion());
          assertEquals(RequirementVerdict.NOT_APPLICABLE, guardian.get(0).requirementVerdict());
          assertEquals(Conclusion.INDEPENDENT_ALLOWED, guardian.get(1).conclusion());
          assertEquals(RequirementVerdict.MATCHED, guardian.get(1).requirementVerdict());
          var checked = new ArrayList<>(grades);
          checked.addAll(guardian);
          for (var grade : checked)
            System.out.printf(
                "Round3 Chroma %s %s %s %s %s%n",
                grade.questionId(),
                grade.status(),
                grade.conclusion(),
                grade.requirementVerdict(),
                grade.evidenceIds());
        });
  }

  @FunctionalInterface
  private interface ChromaCheck {
    void check(
        VectorStore vector, GuideKnowledge rag, java.util.concurrent.atomic.AtomicBoolean offline)
        throws Exception;
  }

  private void withChroma(JdbcTemplate db, ChromaCheck check) throws Exception {
    var offline = new java.util.concurrent.atomic.AtomicBoolean();
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(2000);
    requestFactory.setReadTimeout(5000);
    var api =
        ChromaApi.builder()
            .baseUrl(System.getProperty("chroma.test.url", "http://127.0.0.1:18090"))
            .restClientBuilder(
                RestClient.builder()
                    .requestFactory(requestFactory)
                    .requestInterceptor(
                        (request, body, execution) -> {
                          if (offline.get())
                            throw new java.io.IOException(
                                "Acceptance: Chroma transport interrupted");
                          return execution.execute(request, body);
                        }))
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
    String collection = "guide-rag-test-" + UUID.randomUUID();
    var vector =
        ChromaVectorStore.builder(api, model)
            .tenantName("default_tenant")
            .databaseName("default_database")
            .collectionName(collection)
            .initializeSchema(true)
            .build();
    vector.afterPropertiesSet();
    var redis = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("redis.port", 16379));
    redis.afterPropertiesSet();
    redis.start();
    var registry = new StringRedisTemplate(redis);
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
      check.check(vector, rag, offline);
    } finally {
      offline.set(false);
      registry.delete(rag.indexKey);
      redis.destroy();
      try {
        api.deleteCollection("default_tenant", "default_database", collection);
      } catch (org.springframework.web.client.HttpClientErrorException.NotFound alreadyRemoved) {
        // Initialization may have failed before creating the test-only collection.
      }
    }
  }
}
