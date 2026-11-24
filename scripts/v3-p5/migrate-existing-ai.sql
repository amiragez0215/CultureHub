-- UNSUPPORTED development helper: NOT a V1-to-V3 migration or supported upgrade path.
-- Maintenance operation, NOT an application startup script.
-- Stop Core AI and the new AI service first; apply 20261112-v3-ai.sql to ai_db.
-- Run with a migration account able to read core_db and write ai_db.
-- Target must be empty; INSERT fails on duplicates to prevent overwriting newer execution states.
-- A transaction rollback leaves source and target intact. Never delete original Core tables here.
START TRANSACTION;
INSERT INTO ai_db.ai_owner(id)
 SELECT user_id FROM core_db.ai_guide_conversation
 UNION SELECT user_id FROM core_db.ai_activity_plan
 UNION SELECT user_id FROM core_db.ai_booking_proposal;
INSERT INTO ai_db.ai_guide_conversation SELECT * FROM core_db.ai_guide_conversation;
INSERT INTO ai_db.ai_activity_plan(plan_id,user_id,conversation_id,version,constraints_snapshot,status,verification_status,created_at,expires_at)
 SELECT plan_id,user_id,conversation_id,version,constraints_snapshot,status,verification_status,created_at,expires_at FROM core_db.ai_activity_plan;
INSERT INTO ai_db.ai_activity_plan_item SELECT * FROM core_db.ai_activity_plan_item;
INSERT INTO ai_db.ai_booking_proposal SELECT * FROM core_db.ai_booking_proposal;
COMMIT;
-- Verify source/target counts and pending/processing request_id values before enabling V3 routes.
