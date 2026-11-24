-- Core only. Transport and settlement state is deliberately outside AT.
CREATE TABLE IF NOT EXISTS combination_request (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  user_id BIGINT NOT NULL,
  items JSON NOT NULL,
  retain_partial BOOLEAN NOT NULL DEFAULT FALSE,
  trace_id VARCHAR(64) NOT NULL,
  status ENUM('RECEIVED','PROCESSING','SETTLING','FALLBACK','SUCCEEDED','FAILED','PARTIALLY_SUCCEEDED','MANUAL_INTERVENTION') NOT NULL DEFAULT 'RECEIVED',
  db_result ENUM('COMMITTED','ROLLED_BACK'),
  attempt CHAR(36),
  xid VARCHAR(128),
  error_code VARCHAR(80),
  results JSON,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX ix_combination_recovery(status,next_attempt_at,id),
  INDEX ix_combination_owner(user_id,created_at,id),
  FOREIGN KEY(user_id) REFERENCES app_user(id)
) ENGINE=InnoDB;
