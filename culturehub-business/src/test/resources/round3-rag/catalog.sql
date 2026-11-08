-- Isolated, completely fictitious RAG fixtures. Never points at the main CultureHub schema.
CREATE DATABASE IF NOT EXISTS culturehub_round3_rag CHARACTER SET utf8mb4;
USE culturehub_round3_rag;
CREATE TABLE IF NOT EXISTS venue (
  id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL UNIQUE,
  status VARCHAR(20) NOT NULL
);
CREATE TABLE IF NOT EXISTS activity (
  id BIGINT PRIMARY KEY,
  venue_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  status VARCHAR(20) NOT NULL
);
INSERT IGNORE INTO venue VALUES (1, '测试文化馆 A', 'OPEN'), (2, '测试文化馆 B', 'OPEN');
INSERT IGNORE INTO activity VALUES
  (11, 1, '虚构年龄限定体验', 'PUBLISHED'),
  (12, 1, '虚构推荐年龄体验', 'PUBLISHED'),
  (13, 1, '虚构陪同体验', 'PUBLISHED'),
  (14, 1, '虚构冲突规则体验', 'PUBLISHED'),
  (15, 1, '虚构历史体验', 'PUBLISHED');
