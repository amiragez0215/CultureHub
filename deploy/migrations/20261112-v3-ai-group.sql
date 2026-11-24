-- Repeatable, ai_db only; a binding is recorded solely after explicit user confirmation.
SET @add_group=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_activity_plan' AND column_name='group_request_id')=0,
 'ALTER TABLE ai_activity_plan ADD COLUMN group_request_id VARCHAR(80), ADD COLUMN group_retain_partial BOOLEAN NOT NULL DEFAULT FALSE, ADD UNIQUE KEY uq_ai_group_request(group_request_id)', 'DO 0');
PREPARE ai_ddl FROM @add_group;
EXECUTE ai_ddl;
DEALLOCATE PREPARE ai_ddl;
