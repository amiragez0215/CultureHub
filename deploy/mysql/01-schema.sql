CREATE DATABASE IF NOT EXISTS culturehub CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE culturehub;
CREATE TABLE app_user(id BIGINT PRIMARY KEY AUTO_INCREMENT,username VARCHAR(40) NOT NULL UNIQUE,password_hash VARCHAR(100) NOT NULL,nickname VARCHAR(80) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6));
CREATE TABLE user_role(user_id BIGINT NOT NULL,role ENUM('USER','VENUE_ADMIN','PLATFORM_ADMIN') NOT NULL,PRIMARY KEY(user_id,role),FOREIGN KEY(user_id) REFERENCES app_user(id));
CREATE TABLE venue(id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(120) NOT NULL,category VARCHAR(40) NOT NULL DEFAULT 'CULTURE_CENTER',address VARCHAR(500) NOT NULL DEFAULT '',introduction TEXT,opening_hours VARCHAR(300) NOT NULL DEFAULT '',status ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',INDEX ix_venue_category(category,status,id));
CREATE TABLE venue_grant(user_id BIGINT NOT NULL,venue_id BIGINT NOT NULL,PRIMARY KEY(user_id,venue_id),FOREIGN KEY(user_id) REFERENCES app_user(id),FOREIGN KEY(venue_id) REFERENCES venue(id));
CREATE TABLE activity(id BIGINT PRIMARY KEY AUTO_INCREMENT,venue_id BIGINT NOT NULL,title VARCHAR(120) NOT NULL,description TEXT,category VARCHAR(50) NOT NULL DEFAULT 'CULTURE',audience VARCHAR(100) NOT NULL DEFAULT 'ALL',plan TEXT,notice TEXT,copywriting TEXT,status ENUM('DRAFT','PUBLISHED','CANCELLED') NOT NULL DEFAULT 'DRAFT',created_by BIGINT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),FOREIGN KEY(venue_id) REFERENCES venue(id),INDEX ix_activity_venue(venue_id,status,id),INDEX ix_activity_category(category,status,id));
CREATE TABLE activity_session(id BIGINT PRIMARY KEY AUTO_INCREMENT,activity_id BIGINT NOT NULL,registration_start_at DATETIME(6) NOT NULL,registration_end_at DATETIME(6) NOT NULL,start_at DATETIME(6) NOT NULL,end_at DATETIME(6) NOT NULL,checkin_open_at DATETIME(6) NOT NULL,checkin_close_at DATETIME(6) NOT NULL,capacity INT NOT NULL,occupied_count INT NOT NULL DEFAULT 0,last_waitlist_seq BIGINT NOT NULL DEFAULT 0,status ENUM('DRAFT','PUBLISHED','CANCELLED') NOT NULL DEFAULT 'DRAFT',FOREIGN KEY(activity_id) REFERENCES activity(id),CHECK(capacity>0 AND occupied_count>=0 AND occupied_count<=capacity),CHECK(registration_start_at<registration_end_at AND registration_end_at<=start_at AND start_at<end_at),CHECK(checkin_open_at<checkin_close_at AND checkin_close_at<=end_at),INDEX ix_session_activity(activity_id,status,start_at),INDEX ix_session_time(status,start_at,id));
CREATE TABLE participation(id BIGINT PRIMARY KEY AUTO_INCREMENT,session_id BIGINT NOT NULL,user_id BIGINT NOT NULL,status ENUM('WAITING','OFFERED','RESERVED','CHECKED_IN','CANCELLED','EXPIRED') NOT NULL,entry_type ENUM('DIRECT','WAITLIST') NOT NULL,request_id VARCHAR(80) NOT NULL,queue_seq BIGINT NULL,offered_at DATETIME(6) NULL,confirm_deadline DATETIME(6) NULL,reserved_at DATETIME(6) NULL,checkin_token VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NULL,ended_at DATETIME(6) NULL,end_reason VARCHAR(40) NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),active_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('WAITING','OFFERED','RESERVED','CHECKED_IN') THEN 1 ELSE NULL END) STORED,FOREIGN KEY(session_id) REFERENCES activity_session(id),FOREIGN KEY(user_id) REFERENCES app_user(id),UNIQUE KEY uq_active(session_id,user_id,active_guard),UNIQUE KEY uq_request(user_id,request_id),UNIQUE KEY uq_queue(session_id,queue_seq),UNIQUE KEY uq_token(checkin_token),INDEX ix_queue(session_id,status,queue_seq),INDEX ix_timeout(status,confirm_deadline,session_id,id),INDEX ix_user_history(user_id,id));
CREATE TABLE participation_history(id BIGINT PRIMARY KEY AUTO_INCREMENT,participation_id BIGINT NOT NULL,from_status VARCHAR(20) NULL,to_status VARCHAR(20) NOT NULL,reason VARCHAR(40) NOT NULL,occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),FOREIGN KEY(participation_id) REFERENCES participation(id),INDEX ix_history(participation_id,id));
CREATE TABLE checkin_record(id BIGINT PRIMARY KEY AUTO_INCREMENT,participation_id BIGINT NOT NULL UNIQUE,operator_id BIGINT NOT NULL,checked_at DATETIME(6) NOT NULL,FOREIGN KEY(participation_id) REFERENCES participation(id),FOREIGN KEY(operator_id) REFERENCES app_user(id));
CREATE TABLE review(id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,session_id BIGINT NOT NULL,participation_id BIGINT NOT NULL,rating INT NOT NULL,content VARCHAR(2000) NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),CHECK(rating BETWEEN 1 AND 5),UNIQUE KEY uq_review(user_id,session_id),INDEX ix_review(session_id,created_at,id),FOREIGN KEY(participation_id) REFERENCES participation(id),FOREIGN KEY(user_id) REFERENCES app_user(id),FOREIGN KEY(session_id) REFERENCES activity_session(id));
CREATE TABLE outbox(event_id CHAR(36) PRIMARY KEY,business_key VARCHAR(150) NOT NULL UNIQUE,event_type VARCHAR(60) NOT NULL,aggregate_id BIGINT NOT NULL,payload JSON NOT NULL,status ENUM('PENDING','PUBLISHED') NOT NULL DEFAULT 'PENDING',attempt_count INT NOT NULL DEFAULT 0,next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),last_error VARCHAR(300),created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),published_at DATETIME(6),INDEX ix_outbox(status,next_attempt_at,event_id));
CREATE TABLE notification(id BIGINT PRIMARY KEY AUTO_INCREMENT,source_event_id CHAR(36) NOT NULL,user_id BIGINT NOT NULL,type VARCHAR(60) NOT NULL,title VARCHAR(150) NOT NULL,body JSON NOT NULL,participation_id BIGINT NOT NULL,occurred_at DATETIME(6) NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),read_at DATETIME(6),UNIQUE KEY uq_notification(source_event_id,user_id,type),INDEX ix_notification(user_id,id),FOREIGN KEY(user_id) REFERENCES app_user(id));
CREATE TABLE ai_report(id BIGINT PRIMARY KEY AUTO_INCREMENT,session_id BIGINT NULL UNIQUE,activity_id BIGINT NULL UNIQUE,content JSON NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),CHECK((session_id IS NULL)<>(activity_id IS NULL)),FOREIGN KEY(session_id) REFERENCES activity_session(id),FOREIGN KEY(activity_id) REFERENCES activity(id));

-- Incremental, repeatable migration. Run against the selected CultureHub database.
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
  FOREIGN KEY(user_id) REFERENCES app_user(id),
  FOREIGN KEY(activity_id) REFERENCES activity(id),
  FOREIGN KEY(session_id) REFERENCES activity_session(id),
  FOREIGN KEY(participation_id) REFERENCES participation(id)
);
