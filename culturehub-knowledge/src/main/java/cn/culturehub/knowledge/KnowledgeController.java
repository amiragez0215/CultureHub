package cn.culturehub.knowledge;

import cn.culturehub.v3.ServiceBoundary;
import java.util.*;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class KnowledgeController {
  record Retrieval(String query, long venueId, Long activityId, String knowledgeType, boolean admin) {}
  private final KnowledgeDocuments documents;
  private final ObjectProvider<VectorStore> vectors;
  private final JdbcTemplate database;
  private final String aiToken;

  KnowledgeController(KnowledgeDocuments documents, ObjectProvider<VectorStore> vectors,
      JdbcTemplate database, @Value("${v3.ai-token}") String aiToken) {
    this.documents = documents;
    this.vectors = vectors;
    this.database = database;
    this.aiToken = ServiceBoundary.requireCredential(aiToken);
  }

  @PostMapping(value = "/api/v1/knowledge/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<?> upload(@AuthenticationPrincipal Jwt jwt,
      @RequestPart("metadata") KnowledgeDocuments.Metadata metadata,
      @RequestPart("file") MultipartFile file) throws java.io.IOException {
    return ResponseEntity.accepted().body(documents.upload(jwt, metadata, file.getOriginalFilename(), file.getBytes()));
  }

  @GetMapping("/api/v1/knowledge/documents")
  Object list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") long venueId,
      @RequestParam(defaultValue = "1") int page) {
    return documents.list(jwt, venueId, page);
  }

  @GetMapping("/api/v1/knowledge/documents/{id}")
  Object document(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return documents.read(jwt, id);
  }

  @PostMapping("/api/v1/knowledge/documents/{id}/reindex")
  ResponseEntity<?> reindex(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return ResponseEntity.accepted().body(documents.reindex(jwt, id));
  }

  @DeleteMapping("/api/v1/knowledge/documents/{id}")
  ResponseEntity<?> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return ResponseEntity.accepted().body(documents.delete(jwt, id));
  }

  @GetMapping("/api/v1/knowledge/jobs/{id}")
  Object job(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return documents.job(jwt, id);
  }

  @PostMapping("/internal/v1/knowledge/retrieve")
  Object retrieve(@AuthenticationPrincipal Jwt jwt, @RequestBody Retrieval query,
      @RequestHeader(ServiceBoundary.CALLER) String caller,
      @RequestHeader(ServiceBoundary.CREDENTIAL) String credential) {
    if (!"culturehub-ai".equals(caller) || !ServiceBoundary.authenticates(aiToken, credential)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid service identity");
    }
    if (query.query() == null || query.query().isBlank() || query.query().length() > 2000
        || query.venueId() < 0 || query.activityId() != null && query.activityId() <= 0
        || query.knowledgeType() == null || !query.knowledgeType().matches("[A-Z_]{3,40}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid knowledge query");
    }
    documents.authorize(jwt, query.venueId(), query.activityId() == null ? 0 : query.activityId(), query.admin());
    VectorStore vector = vectors.getIfAvailable();
    if (vector == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Knowledge retrieval unavailable");
    String filter = "(scope == 'GLOBAL' || venueId == " + query.venueId() + ") && knowledge_type == '"
        + query.knowledgeType() + "'" + (query.admin() ? "" : " && visibility == 'PUBLIC'");
    var hits = vector.similaritySearch(SearchRequest.builder().query(query.query()).topK(24)
        .similarityThreshold(0.0).filterExpression(filter).build());
    if (hits == null || hits.isEmpty()) return List.of();
    var ids = hits.stream().map(org.springframework.ai.document.Document::getId).filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) return List.of();
    var args = new ArrayList<Object>(ids);
    args.add(query.venueId());
    args.add(query.activityId() == null ? 0 : query.activityId());
    args.add(query.admin());
    args.add(query.knowledgeType());
    var allowed = database.queryForList("""
        select c.id,c.content,c.metadata from knowledge_chunk c join knowledge_document d on d.id=c.document_id
        where c.id in (
        """ + String.join(",", Collections.nCopies(ids.size(), "?")) + """
        ) and d.deleted=false and d.active_version=c.version
        and (d.expires_at is null or d.expires_at>now(6))
        and (d.scope='GLOBAL' or (d.venue_id=? and (d.scope='VENUE' or d.activity_id=?)))
        and (?=true or d.visibility='PUBLIC') and d.knowledge_type=?
        """, args.toArray());
    var trusted = new HashMap<String, Map<String, Object>>();
    allowed.forEach(row -> trusted.put(row.get("id").toString(), row));
    var result = new ArrayList<Map<String, Object>>();
    for (var hit : hits) {
      var authoritative = trusted.get(hit.getId());
      // Return canonical stored text and metadata, never unverified vector payload.
      if (authoritative != null && result.size() < 8) {
        result.add(Map.of("id", hit.getId(), "content", authoritative.get("content"),
            "metadata", authoritative.get("metadata"), "score", hit.getScore() == null ? 0 : hit.getScore()));
      }
    }
    return result;
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> expected(ResponseStatusException failure) {
    return ResponseEntity.status(failure.getStatusCode()).body(Map.of(
        "code", "KNOWLEDGE_REQUEST_FAILED", "message", Objects.requireNonNullElse(failure.getReason(), "Knowledge request failed")));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception failure) {
    String trace = UUID.randomUUID().toString();
    org.slf4j.LoggerFactory.getLogger(getClass()).error("Knowledge request {}", trace, failure);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
        "code", "KNOWLEDGE_UNAVAILABLE", "message", "Knowledge service unavailable", "traceId", trace));
  }
}
