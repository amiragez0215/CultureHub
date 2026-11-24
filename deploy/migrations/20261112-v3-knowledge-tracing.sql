-- Repeatable amendment for knowledge_db only. Existing outbox rows remain valid.
SET @add_trace=IF((SELECT COUNT(*) FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='knowledge_outbox' AND column_name='trace_id')=0,
 'ALTER TABLE knowledge_outbox ADD COLUMN trace_id VARCHAR(64)', 'DO 0');
PREPARE knowledge_ddl FROM @add_trace;
EXECUTE knowledge_ddl;
DEALLOCATE PREPARE knowledge_ddl;
