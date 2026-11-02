-- Non-destructive, repeatable MySQL 8 migration. Matches WHERE user_id=? ORDER BY id DESC.
SET @has_index=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='participation' AND index_name='ix_user_history');
SET @ddl=IF(@has_index=0,'ALTER TABLE participation ADD INDEX ix_user_history(user_id,id)','SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @old_index=(SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='participation' AND index_name='ix_user');
SET @ddl=IF(@old_index>0,'ALTER TABLE participation DROP INDEX ix_user','SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
