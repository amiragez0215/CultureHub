-- Apply to core_db before activating v3-p2. Existing participation remains authoritative.
CREATE TABLE IF NOT EXISTS booking_session (
  session_id BIGINT PRIMARY KEY,
  revision BIGINT NOT NULL DEFAULT 0,
  redis_epoch CHAR(36) NOT NULL,
  redis_ready BOOLEAN NOT NULL DEFAULT FALSE,
  paused BOOLEAN NOT NULL DEFAULT FALSE,
  FOREIGN KEY(session_id) REFERENCES activity_session(id)
);

CREATE TABLE IF NOT EXISTS booking_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  operation ENUM('DIRECT','JOIN','CONFIRM','PROMOTE') NOT NULL,
  state ENUM('RECEIVED','QUEUED','PROCESSING','CONFIRMED','REJECTED','EXPIRED','FAILED_REVIEW') NOT NULL,
  queue_seq BIGINT,
  participation_id BIGINT,
  redis_epoch CHAR(36),
  stream_id VARCHAR(40),
  error_code VARCHAR(80),
  inventory_synced BOOLEAN NOT NULL DEFAULT FALSE,
  trace_id VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6),
  UNIQUE KEY uq_booking_request(request_id),
  KEY ix_booking_recovery(state,expires_at,id),
  KEY ix_booking_fifo(session_id,operation,state,queue_seq,id),
  KEY ix_booking_inventory(session_id,inventory_synced,finished_at,id),
  FOREIGN KEY(user_id) REFERENCES app_user(id),
  FOREIGN KEY(session_id) REFERENCES activity_session(id),
  FOREIGN KEY(participation_id) REFERENCES participation(id)
);

CREATE TABLE IF NOT EXISTS reservation_hold (
  request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  session_id BIGINT NOT NULL,
  state ENUM('PENDING','HELD','CONSUMED','RELEASED') NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  FOREIGN KEY(request_id) REFERENCES booking_request(request_id),
  FOREIGN KEY(session_id) REFERENCES activity_session(id)
);

CREATE TABLE IF NOT EXISTS booking_inventory_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(80) NOT NULL UNIQUE,
  session_id BIGINT NOT NULL,
  request_id VARCHAR(80),
  source VARCHAR(80) NOT NULL,
  before_available INT,
  after_available INT NOT NULL,
  revision BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
