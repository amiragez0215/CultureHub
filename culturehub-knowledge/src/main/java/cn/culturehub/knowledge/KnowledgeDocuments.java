package cn.culturehub.knowledge;

import cn.culturehub.v3.ServiceBoundary;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class KnowledgeDocuments {
  record Metadata(Long venueId, Long activityId, String scope, String visibility,
                  String knowledgeType, String source, String title, LocalDateTime expiresAt) {}
  private final JdbcTemplate database;
  private final TransactionTemplate tx;
  private final MinioClient objects;
  private final String bucket;
  private final CoreKnowledgeAccess core;
  private final String credential;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private io.micrometer.tracing.Tracer tracer;

  KnowledgeDocuments(JdbcTemplate database, PlatformTransactionManager manager, MinioClient objects,
      CoreKnowledgeAccess core, @Value("${v3.core.token}") String credential,
      @Value("${v3.minio.bucket}") String bucket) {
    this.database = database;
    this.tx = new TransactionTemplate(manager);
    this.objects = objects;
    this.core = core;
    this.credential = ServiceBoundary.requireCredential(credential);
    this.bucket = bucket;
  }

  void authorize(Jwt jwt, long venue, long activity, boolean write) {
    try {
      core.scope("Bearer " + jwt.getTokenValue(), "culturehub-knowledge", credential, venue, activity, write);
    } catch (FeignException denied) {
      int status = denied.status();
      throw new ResponseStatusException(status == 401 || status == 403 || status == 404
          ? HttpStatus.valueOf(status) : HttpStatus.SERVICE_UNAVAILABLE, "Knowledge scope unavailable");
    }
  }

  Map<String, Object> upload(Jwt jwt, Metadata metadata, String filename, byte[] content) {
    validate(metadata);
    authorize(jwt, zero(metadata.venueId()), zero(metadata.activityId()), true);
    if (content.length == 0 || content.length > 20 * 1024 * 1024) invalid("Invalid document size");
    String type = filename == null ? "" : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    if (!Set.of("pdf", "docx", "md", "txt").contains(type)) invalid("Unsupported document type");
    String id = UUID.randomUUID().toString();
    String key = id + "/source." + type;
    try {
      ensureBucket();
      objects.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
          .stream(new ByteArrayInputStream(content), content.length, -1).contentType("application/octet-stream").build());
      return tx.execute(status -> {
        database.update("""
            insert into knowledge_document(id,venue_id,activity_id,scope,visibility,knowledge_type,source,
              title,expires_at,created_by) values(?,?,?,?,?,?,?,?,?,?)
            """, id, metadata.venueId(), metadata.activityId(), metadata.scope(), metadata.visibility(),
            metadata.knowledgeType(), metadata.source(), metadata.title(), metadata.expiresAt(), Long.parseLong(jwt.getSubject()));
        database.update("insert into knowledge_document_version values(?,1,?,?,?)",
            id, key, type, hexDigest(content));
        return newJob(id, 1, "INDEX");
      });
    } catch (Exception failure) {
      // An immutable source object is never referenced by a successful transaction with another ID.
      if (database.queryForObject("select count(*) from knowledge_document where id=?", Long.class, id) == 0) {
        try {
          objects.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception cleanupDeferred) {
          failure.addSuppressed(cleanupDeferred);
        }
      }
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document upload failed", failure);
    }
  }

  Map<String, Object> read(Jwt jwt, String id) {
    var row = ownedDocument(id, false);
    authorize(jwt, number(row, "venue_id"), number(row, "activity_id"), true);
    return row;
  }

  List<Map<String, Object>> list(Jwt jwt, long venue, int page) {
    if (page < 1 || page > 10000) invalid("Invalid page");
    authorize(jwt, venue, 0, true);
    return database.queryForList("""
        select id,title,scope,visibility,knowledge_type,active_version,latest_version,created_at
        from knowledge_document where deleted=false and (?=0 or venue_id=?)
        order by created_at desc,id limit 20 offset ?
        """, venue, venue, (page - 1) * 20);
  }

  Map<String, Object> reindex(Jwt jwt, String id) {
    var source = read(jwt, id);
    return tx.execute(status -> {
      var row = ownedDocument(id, true);
      int next = ((Number) row.get("latest_version")).intValue() + 1;
      database.update("""
          insert into knowledge_document_version(document_id,version,object_key,file_type,sha256)
          select document_id,?,object_key,file_type,sha256 from knowledge_document_version
          where document_id=? and version=?
          """, next, id, row.get("latest_version"));
      database.update("update knowledge_document set latest_version=? where id=?", next, id);
      return newJob(id, next, "INDEX");
    });
  }

  Map<String, Object> delete(Jwt jwt, String id) {
    read(jwt, id);
    return tx.execute(status -> {
      var row = ownedDocument(id, true);
      database.update("update knowledge_document set deleted=true,active_version=null where id=?", id);
      return newJob(id, ((Number) row.get("latest_version")).intValue(), "DELETE");
    });
  }

  Map<String, Object> job(Jwt jwt, String id) {
    var rows = database.queryForList("select * from ingestion_job where id=?", id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
    var row = rows.get(0);
    var document = ownedDocument(row.get("document_id").toString(), false, true);
    authorize(jwt, number(document, "venue_id"), number(document, "activity_id"), true);
    row.remove("execution_token");
    return row;
  }

  private Map<String, Object> newJob(String document, int version, String operation) {
    String job = UUID.randomUUID().toString();
    database.update("insert into ingestion_job(id,document_id,version,operation,status) values(?,?,?,?,'QUEUED')",
        job, document, version, operation);
    database.update("insert into knowledge_outbox(event_id,job_id,trace_id) values(?,?,?)", UUID.randomUUID().toString(), job,
        cn.culturehub.v3.TraceCarrier.capture(tracer,job));
    return Map.of("documentId", document, "version", version, "jobId", job, "status", "QUEUED");
  }

  private Map<String, Object> ownedDocument(String id, boolean lock) {
    return ownedDocument(id, lock, false);
  }

  private Map<String, Object> ownedDocument(String id, boolean lock, boolean deletedAllowed) {
    var rows = database.queryForList("select * from knowledge_document where id=?"
        + (deletedAllowed ? "" : " and deleted=false") + (lock ? " for update" : ""), id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
    return rows.get(0);
  }

  private synchronized void ensureBucket() throws Exception {
    if (!objects.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
      objects.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }
  }

  private void validate(Metadata value) {
    if (value == null || value.scope() == null || value.visibility() == null
        || !Set.of("GLOBAL", "VENUE", "ACTIVITY").contains(value.scope())
        || !Set.of("PUBLIC", "ADMIN").contains(value.visibility())
        || value.title() == null || value.title().isBlank() || value.title().length() > 200
        || value.source() == null || value.source().isBlank() || value.source().length() > 500
        || value.knowledgeType() == null || !value.knowledgeType().matches("[A-Z_]{3,40}")) invalid("Invalid metadata");
    boolean global = "GLOBAL".equals(value.scope()), activity = "ACTIVITY".equals(value.scope());
    if (global != (value.venueId() == null) || (!global && value.venueId() <= 0)
        || activity != (value.activityId() != null) || (activity && value.activityId() <= 0)) invalid("Invalid entity scope");
  }

  static String hexDigest(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static long number(Map<String, Object> row, String key) {
    return row.get(key) instanceof Number value ? value.longValue() : 0;
  }

  private static long zero(Long value) { return value == null ? 0 : value; }
  private static void invalid(String message) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
