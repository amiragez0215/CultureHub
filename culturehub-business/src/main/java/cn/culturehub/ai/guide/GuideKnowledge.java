package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Independent RAG adapter. Retrieved text is never a tool/system instruction. */
@org.springframework.context.annotation.Profile("!v3-p5 & !v3-partner")
@Service
public class GuideKnowledge {
  final ObjectProvider<VectorStore> vectors;
  final Access access;
  final GuideKnowledgeIndex index;
  final ObjectMapper json;
  final StringRedisTemplate redis;
  final String indexKey;

  @Bean
  static BeanFactoryPostProcessor deferChromaConnection() {
    return factory -> {
      for (String name : factory.getBeanNamesForType(ChromaVectorStore.class, false, false))
        if (factory.containsBeanDefinition(name)) factory.getBeanDefinition(name).setLazyInit(true);
    };
  }

  private VectorStore vector(Duration timeout) {
    try {
      var vector =
          Mono.fromCallable(vectors::getIfAvailable)
              .subscribeOn(Schedulers.boundedElastic())
              .block(timeout);
      if (vector != null) return vector;
    } catch (RuntimeException unavailable) {
      // A failed lazy initialization stays inside the RAG path.
    }
    throw new Problem("RAG_UNAVAILABLE", "Embedding 或 Chroma 暂不可用", 503);
  }

  GuideKnowledgeIndex.Snapshot activeSnapshot() {
    if (redis == null) return null;
    String value = redis.opsForValue().get(indexKey);
    if (value == null) return null;
    try {
      var snapshot = json.readValue(value, GuideKnowledgeIndex.Snapshot.class);
      if (!snapshot.generation().matches("[0-9a-f-]{36}"))
        throw new IllegalArgumentException("Invalid generation");
      return snapshot;
    } catch (Exception invalid) {
      throw new Problem("RAG_INDEX_INVALID", "知识索引登记不可读取", 503);
    }
  }

  boolean trustedChunk(Document document) {
    var snapshot = activeSnapshot();
    return snapshot != null && snapshot.trusts(document);
  }

  public GuideKnowledge(ObjectProvider<VectorStore> vectors, Store store, Access access) {
    this(vectors, store, access, new ObjectMapper(), null, "culturehub");
  }

  @Autowired
  public GuideKnowledge(
      ObjectProvider<VectorStore> vectors,
      Store store,
      Access access,
      ObjectMapper json,
      StringRedisTemplate redis,
      @Value("${spring.ai.vectorstore.chroma.collection-name:culturehub}") String collection) {
    this.vectors = vectors;
    this.access = access;
    this.json = json;
    this.redis = redis;
    this.index = new GuideKnowledgeIndex(store, json);
    this.indexKey = "culturehub:guide:knowledge-index:" + collection;
  }

  public List<RetrievalQuery> plan(Turn s) {
    var checked = new HashMap<String, Grade>();
    s.evidenceGrade.forEach(grade -> checked.put(grade.questionId(), grade));
    return s.retrievalNeeds.stream()
        .filter(
            need ->
                !checked.containsKey(need.questionId())
                    || need.required()
                        && checked.get(need.questionId()).status() == GradeStatus.INSUFFICIENT)
        .sorted(Comparator.comparing(RetrievalNeed::required).reversed())
        .limit(8)
        .map(
            need -> {
              var previous = checked.get(need.questionId());
              String query = need.question();
              if (previous != null) {
                String details =
                    switch (need.knowledgeType()) {
                      case "AGE_RULE" -> "适用年龄下限 上限 仅限儿童 禁止参加";
                      case "GUARDIAN_RULE" -> "未成年人须由监护人陪同 独自入场限制";
                      case "OPENING_HOURS" -> "开馆 闭馆 休馆 营业时段";
                      case "ACTIVITY_BACKGROUND", "BACKGROUND" -> "起源 传统用途 民俗历史";
                      default -> "入场须知 参观限制 适用范围";
                    };
                query = details + " " + query;
              }
              return new RetrievalQuery(need, query, previous == null ? 6 : 8);
            })
        .toList();
  }

  public List<Evidence> retrieve(Turn state) {
    long budget =
        Math.max(1, Math.min(8000, state.deadlineEpochMillis - System.currentTimeMillis()));
    VectorStore vector = vector(Duration.ofMillis(budget));
    var snapshot = activeSnapshot();
    if (snapshot == null) return List.of();
    boolean sameGeneration = snapshot.generation().equals(state.knowledgeGeneration);
    state.knowledgeGeneration = snapshot.generation();
    var result = new LinkedHashMap<String, Evidence>();
    int length = 0;
    for (var existing : sameGeneration ? state.retrievedDocuments : List.<Evidence>of()) {
      result.put(existing.questionId() + ":" + existing.chunkId(), existing);
      length += existing.text().length();
    }
    var unavailable =
        new LinkedHashSet<>(sameGeneration ? state.unavailableQuestions : Set.<String>of());
    for (var query : state.retrievalPlan) {
      var n = query.need();
      String filter =
          "guideVisibility == 'PUBLIC' && schemaVersion == "
              + GuideKnowledgeIndex.SCHEMA
              + " && active == true && indexNamespace == '"
              + GuideKnowledgeIndex.NAMESPACE
              + "' && indexGeneration == '"
              + snapshot.generation()
              + "'";
      filter +=
          n.activityId() != null
              ? " && scope == 'ACTIVITY' && activityId == "
                  + n.activityId()
                  + " && venueId == "
                  + n.venueId()
              : n.venueId() == null
                  ? " && scope == 'GLOBAL'"
                  : " && scope == 'VENUE' && venueId == " + n.venueId();
      filter += " && knowledgeType == '" + n.knowledgeType() + "'";
      String expression = filter;
      List<Document> docs;
      try {
        long remaining = state.deadlineEpochMillis - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("RAG deadline");
        docs =
            Mono.fromCallable(
                    () ->
                        vector.similaritySearch(
                            SearchRequest.builder()
                                .query(query.rewrittenQuery())
                                .topK(Math.min(10, query.topK()))
                                .filterExpression(expression)
                                .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .block(Duration.ofMillis(Math.min(8000, remaining)));
        unavailable.remove(n.questionId());
      } catch (RuntimeException failure) {
        unavailable.add(n.questionId());
        continue;
      }
      if (docs == null) continue;
      for (var doc : docs) {
        if (!snapshot.trusts(doc)) continue;
        Evidence evidence = validateScoped(doc, n, state.identity.currentTime().toLocalDate());
        if (evidence == null
            || result.containsKey(n.questionId() + ":" + evidence.chunkId())
            || length + evidence.text().length() > 24000) continue;
        length += evidence.text().length();
        result.put(n.questionId() + ":" + evidence.chunkId(), evidence);
      }
    }
    state.unavailableQuestions = unavailable;
    return List.copyOf(result.values());
  }

  public Evidence validate(Document d, RetrievalNeed n, LocalDate now) {
    return trustedChunk(d) ? validateScoped(d, n, now) : null;
  }

  private Evidence validateScoped(Document d, RetrievalNeed n, LocalDate now) {
    var m = d.getMetadata();
    if (!(m.get("schemaVersion") instanceof Number)) return null;
    if (!"PUBLIC".equals(m.get("guideVisibility"))
        || !Boolean.TRUE.equals(m.get("active"))
        || !Objects.equals(
            ((Number) m.getOrDefault("schemaVersion", 0)).intValue(), GuideKnowledgeIndex.SCHEMA))
      return null;
    for (String key :
        List.of("documentId", "chunkId", "source", "version", "scope", "knowledgeType"))
      if (!(m.get(key) instanceof String value) || value.isBlank()) return null;
    Long venue = m.get("venueId") instanceof Number v ? v.longValue() : null;
    Long activity = m.get("activityId") instanceof Number v ? v.longValue() : null;
    if (n.activityId() != null
        ? (!"ACTIVITY".equals(m.get("scope"))
            || !n.activityId().equals(activity)
            || !Objects.equals(n.venueId(), venue))
        : n.venueId() == null
            ? !"GLOBAL".equals(m.get("scope"))
            : !n.venueId().equals(venue) || !"VENUE".equals(m.get("scope"))) return null;
    if (!n.knowledgeType().equals(m.get("knowledgeType"))) return null;
    LocalDate expires = null;
    try {
      if (m.get("expiresOn") != null && !m.get("expiresOn").toString().isBlank())
        expires = LocalDate.parse(m.get("expiresOn").toString());
    } catch (Exception invalid) {
      return null;
    }
    if (expires != null && expires.isBefore(now)) return null;
    String text = d.getText();
    if (text == null
        || text.isBlank()
        || text.length() > 5000
        || text.matches("(?s).*(忽略之前指令|调用工具修改|ignore previous instructions|execute SQL).*"))
      return null;
    return new Evidence(
        m.get("documentId").toString(),
        m.get("chunkId").toString(),
        venue,
        activity,
        m.get("scope").toString(),
        m.get("source").toString(),
        m.get("knowledgeType").toString(),
        m.get("version").toString(),
        expires,
        text,
        d.getScore() == null ? 0 : d.getScore(),
        0,
        n.questionId());
  }

  /** A lexical baseline with query synonyms and a bonus for directly extractable rule forms. */
  public List<Evidence> rerank(Turn turn) {
    return turn.retrievalNeeds.stream()
        .flatMap(
            need ->
                rerank(
                    turn.retrievedDocuments.stream()
                        .filter(d -> GuideEvidenceScope.scoped(d, need))
                        .toList(),
                    need.question())
                    .stream())
        .toList();
  }

  public List<Evidence> rerank(List<Evidence> docs, String question) {
    var queryTerms = GuideRuleAssessment.terms(question);
    var words = grams(question);
    return docs.stream()
        .map(
            doc -> {
              var terms = GuideRuleAssessment.terms(doc.text());
              terms.retainAll(queryTerms);
              var overlap = grams(doc.text());
              overlap.retainAll(words);
              var probe =
                  new RetrievalNeed(
                      doc.questionId(),
                      question,
                      doc.venueId(),
                      doc.activityId(),
                      doc.knowledgeType(),
                      false);
              double direct = GuideRuleAssessment.claims(probe, doc, null).isEmpty() ? 0 : 2;
              double score =
                  direct + terms.size() + (double) overlap.size() / Math.max(1, words.size());
              return new Evidence(
                  doc.documentId(),
                  doc.chunkId(),
                  doc.venueId(),
                  doc.activityId(),
                  doc.scope(),
                  doc.source(),
                  doc.knowledgeType(),
                  doc.version(),
                  doc.expiresOn(),
                  doc.text(),
                  doc.retrievalScore(),
                  score,
                  doc.questionId());
            })
        .sorted(
            Comparator.comparingDouble(Evidence::rerankScore)
                .reversed()
                .thenComparing(Comparator.comparingDouble(Evidence::retrievalScore).reversed())
                .thenComparing(Evidence::chunkId))
        .toList();
  }

  private Set<String> grams(String text) {
    var grams = new HashSet<String>();
    String compact = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    for (int i = 0; i < compact.length() - 1; i++) grams.add(compact.substring(i, i + 2));
    return grams;
  }

  public List<Grade> grade(Turn state) {
    Integer child = GuideKnowledgeQuestions.childAge(state);
    return state.retrievalNeeds.stream()
        .map(
            need -> {
              if (state.knowledgeStatus == ResultStatus.FAILED
                  || state.unavailableQuestions.contains(need.questionId()))
                return new Grade(
                    need.questionId(),
                    GradeStatus.UNAVAILABLE,
                    List.of(),
                    List.of(),
                    List.of(need.question()),
                    "知识检索暂不可用",
                    List.of(),
                    GuideRuleAssessment.verdict(need, GradeStatus.UNAVAILABLE, List.of(), child));

              List<Claim> claims =
                  state.rerankedDocuments.stream()
                      .filter(e -> GuideEvidenceScope.scoped(e, need))
                      .flatMap(e -> GuideRuleAssessment.claims(need, e, child).stream())
                      .toList();
              boolean conflict = GuideRuleAssessment.conflict(claims, child);
              boolean recommendationOnly =
                  !claims.isEmpty()
                      && claims.stream().allMatch(c -> c.conclusion() == Conclusion.RECOMMENDED);
              boolean answered =
                  !claims.isEmpty() && (!recommendationOnly || need.question().contains("推荐年龄"));
              var status =
                  conflict
                      ? GradeStatus.CONFLICTING
                      : answered ? GradeStatus.SUPPORTED : GradeStatus.INSUFFICIENT;
              var facts =
                  status == GradeStatus.SUPPORTED || recommendationOnly
                      ? claims.stream().map(Claim::statement).distinct().toList()
                      : List.<String>of();
              return new Grade(
                  need.questionId(),
                  status,
                  facts,
                  claims.stream().map(Claim::evidenceId).distinct().toList(),
                  status == GradeStatus.SUPPORTED ? List.of() : List.of(need.question()),
                  switch (status) {
                    case SUPPORTED -> null;
                    case CONFLICTING -> "有效来源对同一规则存在冲突";
                    case INSUFFICIENT ->
                        recommendationOnly ? "现有资料只有推荐年龄，不能据此确定报名资格" : "证据没有直接回答该问题";
                    case UNAVAILABLE -> "知识检索暂不可用";
                  },
                  claims,
                  GuideRuleAssessment.verdict(need, status, claims, child));
            })
        .toList();
  }

  public Object reindex() throws Exception {
    access.platformOnly();
    if (redis == null) throw new Problem("RAG_UNAVAILABLE", "知识索引登记未配置", 503);
    var vector = vector(Duration.ofSeconds(8));
    return publish(vector, index.stage());
  }

  Map<String, Object> publish(VectorStore vector, GuideKnowledgeIndex.Staged staged)
      throws Exception {
    // A unique generation stays invisible until every Chroma write succeeds.
    // Failure or timeout cannot publish it, even if a remote upsert finishes later.
    String registry = json.writeValueAsString(staged.snapshot());
    try {
      Mono.fromRunnable(() -> vector.add(staged.documents()))
          .subscribeOn(Schedulers.boundedElastic())
          .block(Duration.ofSeconds(60));
    } catch (RuntimeException unavailable) {
      throw new Problem("RAG_INDEX_WRITE_FAILED", "知识索引写入失败，仍使用上一已发布版本", 503);
    }
    redis.opsForValue().set(indexKey, registry);
    return Map.of(
        "chunks", staged.documents().size(),
        "schemaVersion", GuideKnowledgeIndex.SCHEMA,
        "generation", staged.snapshot().generation(),
        "skippedDemoCatalog", staged.skipped());
  }
}
