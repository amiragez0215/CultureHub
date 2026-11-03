-- Repeatable compatibility audit: existing V1 indexes are retained, never dropped.
-- Adds only a missing named index. Run against the intended schema, not an implicit USE.
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='activity' AND index_name='ix_activity_venue')=0,'ALTER TABLE activity ADD INDEX ix_activity_venue(venue_id,status,id)','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='activity_session' AND index_name='ix_session_activity')=0,'ALTER TABLE activity_session ADD INDEX ix_session_activity(activity_id,status,start_at)','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='participation' AND index_name='ix_user_history')=0,'ALTER TABLE participation ADD INDEX ix_user_history(user_id,id)','SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
