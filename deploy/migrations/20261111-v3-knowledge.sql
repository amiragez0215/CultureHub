-- Apply only to knowledge_db, owned by the Knowledge service.
CREATE TABLE IF NOT EXISTS knowledge_document (
  id CHAR(36) PRIMARY KEY,
  venue_id BIGINT,
  activity_id BIGINT,
  scope ENUM('GLOBAL','VENUE','ACTIVITY') NOT NULL,
  visibility ENUM('PUBLIC','ADMIN') NOT NULL,
  knowledge_type VARCHAR(40) NOT NULL,
  source VARCHAR(500) NOT NULL,
  title VARCHAR(200) NOT NULL,
  active_version INT,
  latest_version INT NOT NULL DEFAULT 1,
  deleted BOOLEAN NOT NULL DEFAULT FALSE,
  expires_at DATETIME(6),
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX ix_knowledge_scope(venue_id,deleted,visibility,id)
);
CREATE TABLE IF NOT EXISTS knowledge_document_version (
  document_id CHAR(36) NOT NULL,
  version INT NOT NULL,
  object_key VARCHAR(150) NOT NULL,
  file_type VARCHAR(10) NOT NULL,
  sha256 CHAR(64) NOT NULL,
  PRIMARY KEY(document_id,version),
  FOREIGN KEY(document_id) REFERENCES knowledge_document(id)
);
CREATE TABLE IF NOT EXISTS ingestion_job (
  id CHAR(36) PRIMARY KEY,
  document_id CHAR(36) NOT NULL,
  version INT NOT NULL,
  operation ENUM('INDEX','DELETE') NOT NULL,
  status ENUM('QUEUED','PARSING','CHUNKING','EMBEDDING','INDEXING','SUCCEEDED','RETRY_WAIT','FAILED') NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  lease_until DATETIME(6),
  execution_token CHAR(36),
  last_error VARCHAR(100),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  finished_at DATETIME(6),
  FOREIGN KEY(document_id) REFERENCES knowledge_document(id),
  INDEX ix_ingestion_recovery(status,lease_until,id)
);
CREATE TABLE IF NOT EXISTS knowledge_chunk (
  id VARCHAR(100) PRIMARY KEY,
  document_id CHAR(36) NOT NULL,
  version INT NOT NULL,
  content TEXT NOT NULL,
  metadata JSON NOT NULL,
  FOREIGN KEY(document_id) REFERENCES knowledge_document(id),
  INDEX ix_chunk_version(document_id,version,id)
);
CREATE TABLE IF NOT EXISTS knowledge_outbox (
  event_id CHAR(36) PRIMARY KEY,
  job_id CHAR(36) NOT NULL,
  trace_id VARCHAR(64),
  status ENUM('PENDING','PUBLISHED') NOT NULL DEFAULT 'PENDING',
  attempt INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error VARCHAR(100),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(job_id) REFERENCES ingestion_job(id),
  INDEX ix_knowledge_publish(status,next_attempt_at,event_id)
);
