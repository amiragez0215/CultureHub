-- Apply to ai_db. Additive, repeatable; no cross-service foreign keys.
CREATE TABLE IF NOT EXISTS ai_owner(id BIGINT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS ai_booking_proposal (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  activity_id BIGINT NOT NULL,
  session_id BIGINT NOT NULL,
  request_id VARCHAR(80) NOT NULL,
  entry_type ENUM('DIRECT','WAITLIST') NOT NULL,
  status ENUM('PENDING','PROCESSING','SUCCEEDED','FAILED','REJECTED','EXPIRED') NOT NULL DEFAULT 'PENDING',
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  confirmed_at DATETIME(6),
  finished_at DATETIME(6),
  processing_until DATETIME(6),
  execution_token CHAR(36),
  participation_id BIGINT,
  error_code VARCHAR(60),
  active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','PROCESSING') THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uq_ai_proposal_request(user_id,request_id),
  UNIQUE KEY uq_ai_proposal_active(user_id,conversation_id,active_guard),
  INDEX ix_ai_proposal_user(user_id,created_at,id),
  INDEX ix_ai_proposal_expiry(status,expires_at),
  INDEX ix_ai_proposal_lease(status,processing_until),
  FOREIGN KEY(user_id) REFERENCES ai_owner(id)
);

-- Additive and repeatable on the current database; never drops V1 data.
CREATE TABLE IF NOT EXISTS ai_guide_conversation (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,
 busy_until DATETIME(6),created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at DATETIME(6) NOT NULL,FOREIGN KEY(user_id) REFERENCES ai_owner(id),
 INDEX ix_ai_conversation_owner(user_id,expires_at)
);
CREATE TABLE IF NOT EXISTS ai_activity_plan (
 plan_id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,conversation_id CHAR(36) NOT NULL,
 version INT NOT NULL,constraints_snapshot JSON NOT NULL,
 status ENUM('ACTIVE','SUPERSEDED') NOT NULL DEFAULT 'ACTIVE',verification_status VARCHAR(30) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),expires_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_ai_plan_version(conversation_id,version),
 FOREIGN KEY(user_id) REFERENCES ai_owner(id),FOREIGN KEY(conversation_id) REFERENCES ai_guide_conversation(id),
 INDEX ix_ai_plan_owner(user_id,created_at)
);
CREATE TABLE IF NOT EXISTS ai_activity_plan_item (
 plan_id CHAR(36) NOT NULL,plan_version INT NOT NULL,session_id BIGINT NOT NULL,
 display_order INT NOT NULL,candidate_id CHAR(36) NOT NULL,session_snapshot JSON NOT NULL,
 verification_status VARCHAR(30) NOT NULL DEFAULT 'VERIFIED',selection_status VARCHAR(30) NOT NULL DEFAULT 'UNSELECTED',
 PRIMARY KEY(plan_id,session_id),UNIQUE KEY uk_ai_candidate(candidate_id),
 FOREIGN KEY(plan_id) REFERENCES ai_activity_plan(plan_id)
);
