package cn.culturehub.ai.guide;

import cn.culturehub.Problem;
import cn.culturehub.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ClassPathResource;

/** Manifest validation and the trusted snapshot published with an index generation. */
final class GuideKnowledgeIndex {
  static final String NAMESPACE = "culturehub-guide-v2";
  static final int SCHEMA = 3;
  static final Set<String> TYPES =
      Set.of(
          "BACKGROUND",
          "VENUE_RULE",
          "AGE_RULE",
          "ACTIVITY_BACKGROUND",
          "OPENING_HOURS",
          "GUARDIAN_RULE");
  static final int MAX_CHUNKS = 256;

  record Fingerprint(Map<String, Object> attributes, String digest) {
    boolean matches(Document doc) {
      return digest.equals(hash(doc.getText()))
          && attributes.entrySet().stream()
              .allMatch(
                  e -> {
                    var actual = doc.getMetadata().get(e.getKey());
                    return Objects.equals(actual, e.getValue())
                        || actual instanceof Number a
                            && e.getValue() instanceof Number b
                            && Double.compare(a.doubleValue(), b.doubleValue()) == 0;
                  });
    }
  }

  record Snapshot(String generation, Map<String, Fingerprint> chunks) {
    boolean trusts(Document doc) {
      var fingerprint = chunks.get(doc.getMetadata().get("chunkId"));
      return fingerprint != null && fingerprint.matches(doc);
    }
  }

  record Staged(Snapshot snapshot, List<Document> documents, List<String> skipped) {}

  private final Store store;
  private final ObjectMapper json;

  GuideKnowledgeIndex(Store store, ObjectMapper json) {
    this.store = store;
    this.json = json;
  }

  Staged stage() throws Exception {
    return stage("knowledge/");
  }

  Staged stage(String resourceRoot) throws Exception {
    var resource = new ClassPathResource(resourceRoot + "manifest.json");
    try (var input = resource.getInputStream()) {
      return stage(json.readTree(input), resourceRoot);
    } catch (java.io.IOException missing) {
      throw new Problem("KNOWLEDGE_INVALID", "无法读取知识清单或知识文件", 400);
    }
  }

  Staged stage(JsonNode manifest) throws Exception {
    return stage(manifest, "knowledge/");
  }

  private Staged stage(JsonNode manifest, String resourceRoot) throws Exception {
    Problem.require(
        manifest != null && manifest.isArray() && !manifest.isEmpty() && manifest.size() <= 128,
        "KNOWLEDGE_INVALID",
        "知识清单应包含 1 至 128 条记录");
    String generation = UUID.randomUUID().toString();
    var trusted = new LinkedHashMap<String, Fingerprint>();
    var documents = new ArrayList<Document>();
    var skipped = new ArrayList<String>();
    var ids = new HashSet<String>();
    for (var entry : manifest) {
      String id = required(entry, "documentId"), file = required(entry, "file");
      String version = required(entry, "version"), source = required(entry, "source");
      String type = required(entry, "knowledgeType"), scope = required(entry, "scope");
      String visibility = required(entry, "visibility");
      Problem.require(
          ids.add(id)
              && id.matches("[a-z0-9][a-z0-9-]{0,79}")
              && file.matches("[^/\\\\]+\\.md")
              && TYPES.contains(type)
              && Set.of("PUBLIC", "PRIVATE").contains(visibility)
              && entry.path("active").isBoolean(),
          "KNOWLEDGE_INVALID",
          "知识文档标识、类型或可见性无效");
      String text =
          new ClassPathResource(resourceRoot + file).getContentAsString(StandardCharsets.UTF_8);
      Problem.require(!text.isBlank() && text.length() <= 24000, "KNOWLEDGE_INVALID", "知识文档内容无效");
      var tags = new LinkedHashMap<String, Object>();
      tags.put("indexNamespace", NAMESPACE);
      tags.put("indexGeneration", generation);
      tags.put("scope", scope);
      tags.put("knowledgeType", type);
      tags.put("active", entry.path("active").asBoolean());
      // Legacy retrieval matches visibility=PUBLIC without a namespace.
      tags.put("guideVisibility", visibility);
      tags.put("schemaVersion", SCHEMA);
      tags.put("source", source);
      tags.put("version", version);
      tags.put("documentId", id);
      if (entry.hasNonNull("expiresOn")) {
        try {
          tags.put("expiresOn", LocalDate.parse(required(entry, "expiresOn")).toString());
        } catch (java.time.format.DateTimeParseException invalid) {
          throw new Problem("KNOWLEDGE_INVALID", "知识有效期格式无效", 400);
        }
      }
      Problem.require(
          Set.of("GLOBAL", "VENUE", "ACTIVITY").contains(scope)
              && (!scope.equals("GLOBAL")
                  || !entry.hasNonNull("venueName") && !entry.hasNonNull("activityTitle"))
              && (!scope.equals("VENUE") || !entry.hasNonNull("activityTitle"))
              && (!scope.equals("GLOBAL") || type.equals("BACKGROUND"))
              && (!type.equals("ACTIVITY_BACKGROUND") || scope.equals("ACTIVITY")),
          "KNOWLEDGE_INVALID",
          "知识作用域与类型不一致");
      if (!scope.equals("GLOBAL")) {
        String venue = required(entry, "venueName");
        var matches =
            scope.equals("VENUE")
                ? store.db.queryForList(
                    "select id as venue_id from venue where name=? and status='OPEN'", venue)
                : store.db.queryForList(
                    "select a.id, a.venue_id from activity a join venue v on v.id=a.venue_id where"
                        + " a.title=? and v.name=? and a.status='PUBLISHED' and v.status='OPEN'",
                    required(entry, "activityTitle"),
                    venue);
        if (matches.isEmpty() && entry.path("optionalDemoCatalog").asBoolean(false)) {
          skipped.add(id);
          continue;
        }
        Problem.require(matches.size() == 1, "KNOWLEDGE_SCOPE_MISSING", "知识关联场馆或活动不存在，或名称不唯一");
        tags.put("venueId", Store.id(matches.get(0), "venue_id"));
        if (scope.equals("ACTIVITY")) tags.put("activityId", Store.id(matches.get(0), "id"));
      }
      if (!"PUBLIC".equals(visibility) || !entry.path("active").asBoolean()) continue;
      int index = 0;
      for (var chunk : new TokenTextSplitter().apply(List.of(new Document(text)))) {
        String chunkId = id + ":" + version + ":" + index++;
        String physicalId = generation + ":" + chunkId;
        var attributes = new LinkedHashMap<>(tags);
        attributes.put("chunkId", chunkId);
        String content = chunk.getText();
        Problem.require(
            documents.size() < MAX_CHUNKS && content.length() <= 5000,
            "KNOWLEDGE_INVALID",
            "知识分片超出限制");
        trusted.put(chunkId, new Fingerprint(Map.copyOf(attributes), hash(content)));
        documents.add(new Document(physicalId, content, attributes));
      }
    }
    Problem.require(!documents.isEmpty(), "KNOWLEDGE_INVALID", "没有可发布的公开知识");
    return new Staged(
        new Snapshot(generation, Map.copyOf(trusted)),
        List.copyOf(documents),
        List.copyOf(skipped));
  }

  private static String required(JsonNode entry, String key) {
    var value = entry.path(key);
    Problem.require(
        value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 200,
        "KNOWLEDGE_INVALID",
        "知识清单缺少有效字段 " + key);
    return value.asText();
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }
}
