-- Additive and repeatable on the current database; never drops V1 data.
CREATE TABLE IF NOT EXISTS ai_guide_conversation (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,
 busy_until DATETIME(6),created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at DATETIME(6) NOT NULL,FOREIGN KEY(user_id) REFERENCES app_user(id),
 INDEX ix_ai_conversation_owner(user_id,expires_at)
);
CREATE TABLE IF NOT EXISTS ai_activity_plan (
 plan_id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,conversation_id CHAR(36) NOT NULL,
 version INT NOT NULL,constraints_snapshot JSON NOT NULL,
 status ENUM('ACTIVE','SUPERSEDED') NOT NULL DEFAULT 'ACTIVE',verification_status VARCHAR(30) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),expires_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_ai_plan_version(conversation_id,version),
 FOREIGN KEY(user_id) REFERENCES app_user(id),FOREIGN KEY(conversation_id) REFERENCES ai_guide_conversation(id),
 INDEX ix_ai_plan_owner(user_id,created_at)
);
CREATE TABLE IF NOT EXISTS ai_activity_plan_item (
 plan_id CHAR(36) NOT NULL,plan_version INT NOT NULL,session_id BIGINT NOT NULL,
 display_order INT NOT NULL,candidate_id CHAR(36) NOT NULL,session_snapshot JSON NOT NULL,
 verification_status VARCHAR(30) NOT NULL DEFAULT 'VERIFIED',selection_status VARCHAR(30) NOT NULL DEFAULT 'UNSELECTED',
 PRIMARY KEY(plan_id,session_id),UNIQUE KEY uk_ai_candidate(candidate_id),
 FOREIGN KEY(plan_id) REFERENCES ai_activity_plan(plan_id),FOREIGN KEY(session_id) REFERENCES activity_session(id)
);
