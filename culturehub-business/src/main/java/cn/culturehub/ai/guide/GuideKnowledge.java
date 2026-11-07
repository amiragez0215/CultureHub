package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Independent RAG adapter. Retrieved text is never a tool/system instruction. */
@Service
public class GuideKnowledge {
  final ObjectProvider<VectorStore> vectors;
  final Access access;
  final GuideKnowledgeIndex index;
  final ObjectMapper json;
  final StringRedisTemplate redis;
  final String indexKey;

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
    return s.retrievalNeeds.stream()
        .limit(6)
        .map(
            n ->
                new RetrievalQuery(
                    n,
                    n.question()
                        + " "
                        + n.knowledgeType()
                        + " "
                        + (n.venueId() == null ? "公共文化知识" : "指定场馆参与规则")
                        + (s.repairCounters.getOrDefault("RetrievalPlan", 0) > 0
                            ? " 年龄范围 入场须知 陪同要求"
                            : ""),
                    6))
        .toList();
  }

  public List<Evidence> retrieve(Turn state) {
    VectorStore vector = vectors.getIfAvailable();
    if (vector == null) throw new Problem("RAG_UNAVAILABLE", "知识库未配置", 503);
    var snapshot = activeSnapshot();
    if (snapshot == null) return List.of();
    var result = new LinkedHashMap<String, Evidence>();
    int length = 0;
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
      var docs =
          Mono.fromCallable(
                  () ->
                      vector.similaritySearch(
                          SearchRequest.builder()
                              .query(query.rewrittenQuery())
                              .topK(Math.min(10, query.topK()))
                              .filterExpression(expression)
                              .build()))
              .subscribeOn(Schedulers.boundedElastic())
              .block(Duration.ofSeconds(8));
      if (docs == null) continue;
      for (var doc : docs) {
        if (!snapshot.trusts(doc)) continue;
        Evidence evidence = validateScoped(doc, n, state.identity.currentTime().toLocalDate());
        if (evidence == null
            || result.containsKey(evidence.chunkId())
            || length + evidence.text().length() > 12000) continue;
        length += evidence.text().length();
        result.put(evidence.chunkId(), evidence);
      }
    }
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
        0);
  }

  /** Character bigram overlap + entity/type relevance; independent from vector score. */
  public List<Evidence> rerank(List<Evidence> docs, String question) {
    var terms = grams(question);
    return docs.stream()
        .map(
            d -> {
              var overlap = grams(d.text());
              overlap.retainAll(terms);
              double score = (double) overlap.size() / Math.max(1, terms.size());
              return new Evidence(
                  d.documentId(),
                  d.chunkId(),
                  d.venueId(),
                  d.activityId(),
                  d.scope(),
                  d.source(),
                  d.knowledgeType(),
                  d.version(),
                  d.expiresOn(),
                  d.text(),
                  d.retrievalScore(),
                  score);
            })
        .sorted(
            Comparator.comparingDouble(Evidence::rerankScore)
                .reversed()
                .thenComparing(Evidence::chunkId))
        .toList();
  }

  Set<String> grams(String value) {
    var result = new HashSet<String>();
    String t = value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    for (int i = 0; i < t.length() - 1; i++) result.add(t.substring(i, i + 2));
    return result;
  }

  public List<Grade> grade(Turn state) {
    var results = new ArrayList<Grade>();
    for (var need : state.retrievalNeeds) {
      var docs =
          state.rerankedDocuments.stream()
              .filter(
                  e ->
                      Objects.equals(need.venueId(), e.venueId())
                          && e.knowledgeType().equals(need.knowledgeType())
                          && Objects.equals(e.activityId(), need.activityId())
                          && e.rerankScore() > 0)
              .toList();
      boolean age = need.knowledgeType().equals("AGE_RULE");
      var usable =
          docs.stream().filter(d -> !age || d.text().matches("(?s).*\\d+\\s*[岁至到-].*岁.*")).toList();
      Integer child =
          state.requirements == null ? null : state.requirements.hardConstraints().childAge();
      if (age && child != null)
        usable =
            usable.stream()
                .filter(
                    d -> {
                      var matcher =
                          java.util.regex.Pattern.compile("(\\d+)\\s*(?:至|到|[-~])\\s*(\\d+)\\s*岁")
                              .matcher(d.text());
                      return matcher.find()
                          && child >= Integer.parseInt(matcher.group(1))
                          && child <= Integer.parseInt(matcher.group(2));
                    })
                .toList();
      // Conflicting rule statements are never silently merged into an authoritative answer.
      boolean conflict =
          usable.stream().anyMatch(d -> d.text().contains("允许"))
              && usable.stream().anyMatch(d -> d.text().contains("禁止"));
      GradeStatus status =
          conflict
              ? GradeStatus.CONFLICTING
              : usable.isEmpty() ? GradeStatus.INSUFFICIENT : GradeStatus.SUPPORTED;
      if (age
          && !conflict
          && usable.stream().anyMatch(d -> d.text().contains("禁止") || d.text().contains("不得")))
        status = GradeStatus.INSUFFICIENT;
      results.add(
          new Grade(
              need.questionId(),
              status,
              status == GradeStatus.SUPPORTED
                  ? usable.stream().map(Evidence::text).toList()
                  : List.of(),
              usable.stream().map(Evidence::chunkId).toList(),
              status == GradeStatus.SUPPORTED ? List.of() : List.of(need.question()),
              status == GradeStatus.SUPPORTED
                  ? null
                  : conflict ? "来源存在冲突，需人工核实" : "没有可追溯且直接回答该问题的证据"));
    }
    return results;
  }

  public Object reindex() throws Exception {
    access.platformOnly();
    var vector = vectors.getIfAvailable();
    if (vector == null || redis == null)
      throw new Problem("RAG_UNAVAILABLE", "Embedding、Chroma 或知识索引登记未配置", 503);
    var staged = index.stage();
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
