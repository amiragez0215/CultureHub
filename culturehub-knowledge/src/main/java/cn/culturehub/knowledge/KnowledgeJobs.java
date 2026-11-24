package cn.culturehub.knowledge;

import cn.culturehub.RabbitTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.tika.Tika;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class KnowledgeJobs {
  private final JdbcTemplate database;
  private final TransactionTemplate tx;
  private final RabbitTransport transport;
  private final ObjectMapper json;
  private final MinioClient objects;
  private final String bucket;
  private final ObjectProvider<VectorStore> vectors;
  private final ExecutorService parsers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(1), runnable -> {
    Thread worker = new Thread(runnable, "knowledge-parser");
    worker.setDaemon(true);
    return worker;
  }, new ThreadPoolExecutor.AbortPolicy());

  KnowledgeJobs(JdbcTemplate database, PlatformTransactionManager manager, RabbitTransport transport,
      ObjectMapper json, MinioClient objects, ObjectProvider<VectorStore> vectors,
      @Value("${v3.minio.bucket}") String bucket) {
    this.database = database;
    this.tx = new TransactionTemplate(manager);
    this.transport = transport;
    this.json = json;
    this.objects = objects;
    this.vectors = vectors;
    this.bucket = bucket;
  }

  @jakarta.annotation.PreDestroy
  void stop() { parsers.shutdownNow(); }

  @Scheduled(fixedDelayString = "${v3.knowledge.publish-delay-ms:1000}")
  public void publish() {
    for (var row : database.queryForList("""
        select * from knowledge_outbox where status='PENDING' and next_attempt_at<=now(6)
        order by created_at,event_id limit 50
        """)) {
      String id = row.get("event_id").toString();
      int attempt = ((Number) row.get("attempt")).intValue() + 1;
      if (database.update("""
          update knowledge_outbox set attempt=?,next_attempt_at=date_add(now(6),interval 30 second)
          where event_id=? and status='PENDING' and attempt=? and next_attempt_at<=now(6)
          """, attempt, id, attempt - 1) != 1) continue;
      try {
        String job = row.get("job_id").toString();
        var body = Map.of("messageId", id, "messageType", KnowledgeConfiguration.ROUTE, "schemaVersion", 1,
            "jobId", job, "correlationId", job, "traceId", Objects.toString(row.get("trace_id"),id), "occurredAt", Instant.now().toString());
        var properties = new MessageProperties();
        properties.setMessageId(id);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        transport.send(KnowledgeConfiguration.EXCHANGE, KnowledgeConfiguration.ROUTE,
            new Message(json.writeValueAsBytes(body), properties));
        database.update("""
            update knowledge_outbox set status='PUBLISHED',last_error=null where event_id=? and attempt=?
            """, id, attempt);
      } catch (Exception failure) {
        if (failure instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          return;
        }
        database.update("""
            update knowledge_outbox set next_attempt_at=date_add(now(6),interval ? second),last_error=?
            where event_id=? and status='PENDING' and attempt=?
            """, Math.min(300, 5L << Math.min(6, attempt)), failure.getClass().getSimpleName(), id, attempt);
      }
    }
  }

  @Scheduled(fixedDelayString = "${v3.knowledge.recovery-delay-ms:5000}")
  public void recover() {
    tx.executeWithoutResult(status -> {
      var rows = database.queryForList("""
          select id from ingestion_job where status not in ('SUCCEEDED','FAILED','QUEUED')
          and lease_until<=now(6) order by lease_until,id limit 50 for update skip locked
          """);
      for (var row : rows) {
        String id = row.get("id").toString();
        database.update("update ingestion_job set status='QUEUED',execution_token=null where id=?", id);
        database.update("""
            insert into knowledge_outbox(event_id,job_id,trace_id)
            select ?,?,trace_id from knowledge_outbox where job_id=? order by created_at limit 1
            """, UUID.randomUUID().toString(), id, id);
      }
    });
  }

  @Scheduled(fixedDelayString = "${v3.knowledge.cleanup-delay-ms:30000}")
  public void cleanupInactiveChunks() {
    var stale = database.queryForList("""
        select c.id from knowledge_chunk c join knowledge_document d on d.id=c.document_id
        where (d.deleted=true or (d.active_version is not null and c.version<>d.active_version))
        and not exists(select 1 from ingestion_job j where j.document_id=c.document_id
          and j.version=c.version and j.status not in ('SUCCEEDED','FAILED'))
        order by c.id limit 50
        """);
    if (stale.isEmpty()) return;
    VectorStore vector = vectors.getIfAvailable();
    if (vector == null) return;
    for (var row : stale) {
      String id = row.get("id").toString();
      vector.delete(List.of(id));
      database.update("delete from knowledge_chunk where id=?", id);
    }
  }

  @RabbitListener(queues = KnowledgeConfiguration.QUEUE)
  public void consume(Message message, Channel channel) throws Exception {
    long tag = message.getMessageProperties().getDeliveryTag();
    String jobId;
    try {
      var body = json.readTree(message.getBody());
      jobId = body.path("jobId").asText();
      if (body.path("schemaVersion").asInt() != 1 || !jobId.matches("[0-9a-f-]{36}")
          || !KnowledgeConfiguration.ROUTE.equals(body.path("messageType").asText())) {
        throw new IllegalArgumentException("Invalid ingestion message");
      }
    } catch (Exception poison) {
      channel.basicReject(tag, false);
      return;
    }
    String token = UUID.randomUUID().toString();
    int claimed = database.update("""
        update ingestion_job set status='PARSING',attempts=attempts+1,execution_token=?,
          lease_until=date_add(now(6),interval 120 second)
        where id=? and status='QUEUED'
        """, token, jobId);
    if (claimed != 1) {
      // The lease owner or recovery job holds the durable obligation.
      channel.basicAck(tag, false);
      return;
    }
    try {
      process(jobId, token);
      channel.basicAck(tag, false);
    } catch (Exception failure) {
      boolean dead = Boolean.TRUE.equals(tx.execute(status -> {
        var row = job(jobId, true);
        if (!token.equals(row.get("execution_token"))) return false;
        int attempts = ((Number) row.get("attempts")).intValue();
        boolean exhausted = attempts >= 4 || failure instanceof IllegalArgumentException;
        database.update("""
            update ingestion_job set status=?,last_error=?,lease_until=date_add(now(6),interval ? second)
            where id=? and execution_token=?
            """, exhausted ? "FAILED" : "RETRY_WAIT", failure.getClass().getSimpleName(),
            Math.min(300, 5L << attempts), jobId, token);
        return exhausted;
      }));
      if (dead) channel.basicReject(tag, false);
      else channel.basicAck(tag, false);
    }
  }

  private void process(String id, String token) throws Exception {
    var job = job(id, false);
    String document = job.get("document_id").toString();
    int version = ((Number) job.get("version")).intValue();
    var metadata = database.queryForMap("select * from knowledge_document where id=?", document);
    VectorStore vector = vectors.getIfAvailable();
    if (vector == null) throw new IllegalStateException("Embedding and Chroma must be configured");
    if ("DELETE".equals(job.get("operation"))) {
      for (var row : database.queryForList("select distinct object_key from knowledge_document_version where document_id=?", document)) {
        objects.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(row.get("object_key").toString()).build());
      }
      deleteVectors(document, null, vector);
      success(id, token, document, version, false);
      return;
    }
    if (flag(metadata.get("deleted"))) {
      success(id, token, document, version, false);
      return;
    }
    var source = database.queryForMap("select * from knowledge_document_version where document_id=? and version=?", document, version);
    byte[] content;
    try (var stream = objects.getObject(GetObjectArgs.builder().bucket(bucket).object(source.get("object_key").toString()).build())) {
      content = stream.readNBytes(20 * 1024 * 1024 + 1);
    }
    if (content.length > 20 * 1024 * 1024 || !KnowledgeDocuments.hexDigest(content).equals(source.get("sha256"))) {
      throw new IllegalArgumentException("Document integrity mismatch");
    }
    Future<String> parsed = parsers.submit(() -> new Tika().parseToString(new ByteArrayInputStream(content)));
    String text;
    try {
      text = parsed.get(20, TimeUnit.SECONDS);
    } finally {
      parsed.cancel(true);
    }
    if (text.isBlank() || text.length() > 200_000) throw new IllegalArgumentException("Document text is empty or too large");
    stage(id, token, "CHUNKING");
    var chunks = chunk(document, version, metadata, text);
    tx.executeWithoutResult(status -> {
      requireOwner(id, token);
      for (var chunk : chunks) {
        try {
          database.update("""
              insert into knowledge_chunk(id,document_id,version,content,metadata) values(?,?,?,?,?)
              on duplicate key update content=values(content),metadata=values(metadata)
              """, chunk.getId(), document, version, chunk.getText(), json.writeValueAsString(chunk.getMetadata()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException encoding) {
          throw new IllegalStateException(encoding);
        }
      }
    });
    for (int offset = 0; offset < chunks.size(); offset += 8) {
      stage(id, token, "EMBEDDING");
      vector.add(chunks.subList(offset, Math.min(chunks.size(), offset + 8)));
    }
    stage(id, token, "INDEXING");
    success(id, token, document, version, true);
  }

  static List<Document> chunk(String id, int version, Map<String, Object> metadata, String text) {
    var chunks = new ArrayList<Document>();
    for (int start = 0; start < text.length(); start += 1300) {
      String chunkId = id + ":" + version + ":" + chunks.size();
      var tags = new HashMap<String, Object>();
      tags.put("documentId", id);
      tags.put("chunkId", chunkId);
      tags.put("version", version);
      for (String key : List.of("scope", "visibility", "knowledge_type", "source")) tags.put(key, metadata.get(key));
      tags.put("venueId", KnowledgeDocuments.number(metadata, "venue_id"));
      tags.put("activityId", KnowledgeDocuments.number(metadata, "activity_id"));
      tags.put("contentHash", KnowledgeDocuments.hexDigest(text.substring(start, Math.min(text.length(), start + 1500)).getBytes(StandardCharsets.UTF_8)));
      chunks.add(new Document(chunkId, text.substring(start, Math.min(text.length(), start + 1500)), tags));
    }
    return List.copyOf(chunks);
  }

  private void stage(String id, String token, String stage) {
    if (database.update("""
        update ingestion_job set status=?,lease_until=date_add(now(6),interval 120 second)
        where id=? and execution_token=? and status not in ('SUCCEEDED','FAILED')
        """, stage, id, token) != 1) throw new IllegalStateException("Ingestion lease lost");
  }

  private void success(String id, String token, String document, int version, boolean activate) {
    tx.executeWithoutResult(status -> {
      requireOwner(id, token);
      var current = database.queryForMap("select * from knowledge_document where id=? for update", document);
      if (activate && !flag(current.get("deleted"))
          && ((Number) current.get("latest_version")).intValue() == version) {
        database.update("update knowledge_document set active_version=? where id=?", version, document);
      }
      database.update("update ingestion_job set status='SUCCEEDED',last_error=null,finished_at=now(6) where id=? and execution_token=?",
          id, token);
    });
  }

  private void deleteVectors(String document, Integer version, VectorStore vector) {
    for (var row : database.queryForList("select id from knowledge_chunk where document_id=?"
        + (version == null ? "" : " and version=" + version), document)) {
      vector.delete(List.of(row.get("id").toString()));
    }
  }

  private Map<String, Object> job(String id, boolean lock) {
    return database.queryForMap("select * from ingestion_job where id=?" + (lock ? " for update" : ""), id);
  }

  private void requireOwner(String id, String token) {
    if (!token.equals(job(id, true).get("execution_token"))) throw new IllegalStateException("Ingestion lease lost");
  }

  private static boolean flag(Object value) {
    return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
  }
}
