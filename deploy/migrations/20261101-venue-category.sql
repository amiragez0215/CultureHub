-- Run against the chosen CultureHub database before starting the updated API.
-- Repeatable schema-only migration; existing rows receive the general culture center category.
SET @category_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='venue' AND column_name='category');
SET @ddl = IF(@category_exists=0, 'ALTER TABLE venue ADD COLUMN category VARCHAR(40) NOT NULL DEFAULT ''CULTURE_CENTER''', 'SELECT 1');
PREPARE venue_category_migration FROM @ddl;
EXECUTE venue_category_migration;
DEALLOCATE PREPARE venue_category_migration;
SET @index_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='venue' AND index_name='ix_venue_category');
SET @ddl = IF(@index_exists=0, 'ALTER TABLE venue ADD INDEX ix_venue_category(category,status,id)', 'SELECT 1');
PREPARE venue_category_migration FROM @ddl;
EXECUTE venue_category_migration;
DEALLOCATE PREPARE venue_category_migration;
