package cn.culturehub.ai.guide;

import static cn.culturehub.ai.guide.GuideState.*;

import cn.culturehub.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Independent RAG adapter. Retrieved text is never a tool/system instruction. */
@Service
public class GuideKnowledge {
  final ObjectProvider<VectorStore> vectors;
  final Store store;
  final Access access;
  volatile Map<String, String> trustedChunks;
  Map<String, Map<String, Object>> trustedMetadata;

  boolean trustedChunk(Document document) {
    try {
      if (trustedChunks == null)
        synchronized (this) {
          if (trustedChunks == null) {
            var registry = new HashMap<String, String>();
            var metadata = new HashMap<String, Map<String, Object>>();
            var resources = new PathMatchingResourcePatternResolver();
            var manifest =
                new ObjectMapper()
                    .readTree(
                        resources
                            .getResource("classpath:knowledge/manifest.json")
                            .getInputStream());
            for (var entry : manifest) {
              String file = entry.get("file").asText(), version = entry.get("version").asText();
              Map<String, Object> attributes;
              try {
                attributes = scopeMetadata(entry);
              } catch (Problem missing) {
                continue;
              }
              metadata.put(file + ":" + version, attributes);
              String id =
                  UUID.nameUUIDFromBytes((file + ":" + version).getBytes(StandardCharsets.UTF_8))
                      .toString();
              int index = 0;
              String text =
                  resources
                      .getResource("classpath:knowledge/" + file)
                      .getContentAsString(StandardCharsets.UTF_8);
              for (var chunk : new TokenTextSplitter().apply(List.of(new Document(text))))
                registry.put(file + ":" + version + ":" + id + ":" + index++, chunk.getText());
            }
            trustedMetadata = Map.copyOf(metadata);
            trustedChunks = Map.copyOf(registry);
          }
        }
      var m = document.getMetadata();
      String key = m.get("source") + ":" + m.get("version") + ":" + m.get("chunkId");
      var expected = trustedMetadata.get(m.get("source") + ":" + m.get("version"));
      if (expected == null
          || expected.entrySet().stream()
              .anyMatch(
                  e ->
                      !Objects.equals(e.getValue(), m.get(e.getKey()))
                          && !(e.getValue() instanceof Number a
                              && m.get(e.getKey()) instanceof Number b
                              && a.longValue() == b.longValue()))) return false;
      return Objects.equals(trustedChunks.get(key), document.getText())
          && m.get("chunkId").toString().startsWith(m.get("documentId") + ":");
    } catch (Exception failure) {
      return false;
    }
  }

  public GuideKnowledge(ObjectProvider<VectorStore> vectors, Store store, Access access) {
    this.vectors = vectors;
    this.store = store;
    this.access = access;
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
    var result = new LinkedHashMap<String, Evidence>();
    int length = 0;
    for (var query : state.retrievalPlan) {
      var n = query.need();
      String filter = "visibility == 'PUBLIC' && schemaVersion == 2 && active == true";
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
        Evidence evidence = validate(doc, n, state.identity.currentTime().toLocalDate());
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
    var m = d.getMetadata();
    if (!(m.get("schemaVersion") instanceof Number) || !trustedChunk(d)) return null;
    if (!"PUBLIC".equals(m.get("visibility"))
        || !Boolean.TRUE.equals(m.get("active"))
        || !Objects.equals(((Number) m.getOrDefault("schemaVersion", 0)).intValue(), 2))
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

  Map<String, Object> scopeMetadata(com.fasterxml.jackson.databind.JsonNode entry) {
    String scope = entry.get("scope").asText();
    var tags = new LinkedHashMap<String, Object>();
    tags.put("scope", scope);
    tags.put("knowledgeType", entry.get("knowledgeType").asText());
    tags.put("active", entry.path("active").asBoolean(true));
    if (entry.hasNonNull("expiresOn"))
      tags.put("expiresOn", LocalDate.parse(entry.get("expiresOn").asText()).toString());
    if (scope.equals("VENUE")) {
      var venues =
          store.db.queryForList(
              "select id from venue where name=? and status='OPEN'",
              entry.get("venueName").asText());
      Problem.require(venues.size() == 1, "KNOWLEDGE_SCOPE_MISSING", "知识场馆尚未初始化或名称不唯一");
      tags.put("venueId", Store.id(venues.get(0), "id"));
    } else if (scope.equals("ACTIVITY")) {
      var rows =
          store.db.queryForList(
              "select a.id,a.venue_id from activity a join venue v on v.id=a.venue_id where"
                  + " a.title=? and v.name=? and a.status='PUBLISHED' and v.status='OPEN'",
              entry.get("activityTitle").asText(),
              entry.get("venueName").asText());
      Problem.require(rows.size() == 1, "KNOWLEDGE_SCOPE_MISSING", "知识活动尚未初始化或名称不唯一");
      tags.put("activityId", Store.id(rows.get(0), "id"));
      tags.put("venueId", Store.id(rows.get(0), "venue_id"));
    } else Problem.require(scope.equals("GLOBAL"), "KNOWLEDGE_INVALID", "知识作用域无效");
    return tags;
  }

  public Object reindex() throws Exception {
    access.platformOnly();
    var vector = vectors.getIfAvailable();
    if (vector == null) throw new Problem("RAG_UNAVAILABLE", "Embedding/Chroma 未配置", 503);
    var docs = new ArrayList<Document>();
    var resources = new PathMatchingResourcePatternResolver();
    var metadata =
        new ObjectMapper()
            .readTree(resources.getResource("classpath:knowledge/manifest.json").getInputStream());
    for (var entry : metadata) {
      String file = entry.get("file").asText();
      Problem.require(file.matches("[^/\\\\]+\\.md"), "KNOWLEDGE_INVALID", "知识文件名无效");
      var attrs = new LinkedHashMap<String, Object>(scopeMetadata(entry));
      attrs.put("visibility", "PUBLIC");
      attrs.put("schemaVersion", 2);
      attrs.put("source", file);
      attrs.put("version", entry.get("version").asText());
      String documentId =
          UUID.nameUUIDFromBytes(
                  (file + ":" + attrs.get("version")).getBytes(StandardCharsets.UTF_8))
              .toString();
      attrs.put("documentId", documentId);
      String text =
          resources
              .getResource("classpath:knowledge/" + file)
              .getContentAsString(StandardCharsets.UTF_8);
      int index = 0;
      for (var chunk : new TokenTextSplitter().apply(List.of(new Document(text, attrs)))) {
        var tags = new LinkedHashMap<>(attrs);
        String id = documentId + ":" + index++;
        tags.put("chunkId", id);
        docs.add(new Document(id, chunk.getText(), tags));
      }
    }
    // Validate the entire manifest before changing the index. Chroma itself has no cross-call
    // transaction.
    Mono.fromRunnable(
            () -> {
              vector.delete("visibility == 'PUBLIC'");
              vector.add(docs);
            })
        .subscribeOn(Schedulers.boundedElastic())
        .block(Duration.ofSeconds(60));
    return Map.of("chunks", docs.size(), "schemaVersion", 2);
  }
}
