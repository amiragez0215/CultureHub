#!/bin/sh
(
  set -eu
  export MYSQL_PWD="${MYSQL_ROOT_PASSWORD?MYSQL_ROOT_PASSWORD must be set}"
  schema_dir="${V3_BASE_SCHEMA_DIR:-/culturehub-schema}"

  # Only the init process receives the administrative password.
  # MySQL's schema grants use literal escaped underscores.
  accounts() {
    while read -r schema user password; do
      case "$password" in
        *[!a-zA-Z0-9_-]*|'') echo "Invalid password format for $user" >&2; return 1 ;;
      esac
      if [ "${#password}" -lt 20 ] || [ "${#password}" -gt 128 ]; then
        echo "Database password must contain 20–128 safe ASCII characters for $user" >&2
        return 1
      fi
      escaped_schema=$(printf '%s' "$schema" | sed 's/_/\\_/g')
      printf 'CREATE DATABASE IF NOT EXISTS `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n' "$schema"
      printf "CREATE USER '%s'@'%%' IDENTIFIED BY '%s';\n" "$user" "$password"
      printf "GRANT SELECT, INSERT, UPDATE, DELETE ON \`%s\`.* TO '%s'@'%%';\n" "$escaped_schema" "$user"
    done <<EOF
core_db culturehub_core ${CORE_DB_PASSWORD:?CORE_DB_PASSWORD is required}
notification_db culturehub_notification ${NOTIFICATION_DB_PASSWORD:?NOTIFICATION_DB_PASSWORD is required}
ai_db culturehub_ai ${AI_DB_PASSWORD:?AI_DB_PASSWORD is required}
knowledge_db culturehub_knowledge ${KNOWLEDGE_DB_PASSWORD:?KNOWLEDGE_DB_PASSWORD is required}
xxl_job culturehub_jobs ${JOBS_DB_PASSWORD:?JOBS_DB_PASSWORD is required}
EOF
  }

  mysql_admin() {
    if [ -n "${V3_MYSQL_HOST:-}" ]; then
      mysql --no-defaults --protocol=TCP -h "$V3_MYSQL_HOST" -P "${V3_MYSQL_PORT:-3306}" -uroot --default-character-set=utf8mb4 "$@"
    else
      mysql --protocol=socket -uroot --default-character-set=utf8mb4 "$@"
    fi
  }

  account_sql=$(accounts)
  printf '%s\n' "$account_sql" | mysql_admin
  sed -e '1s/EXISTS culturehub /EXISTS core_db /' -e '2s/USE culturehub;/USE core_db;/' "$schema_dir/01-schema.sql" | mysql_admin
  mysql_admin < "$schema_dir/03-xxl-job.sql"
  mysql_admin <<'SQL'
UPDATE xxl_job.xxl_job_group SET address_list='http://core:9999/' WHERE app_name='culturehub-api';
CREATE TABLE notification_db.notification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_event_id CHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  type VARCHAR(60) NOT NULL,
  title VARCHAR(150) NOT NULL,
  body JSON NOT NULL,
  participation_id BIGINT NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  read_at DATETIME(6),
  UNIQUE KEY uq_notification(source_event_id,user_id,type),
  INDEX ix_notification(user_id,id)
);
SQL
  if [ "${V3_BOOKING_ENABLED:-false}" = 'true' ]; then
    mysql_admin core_db < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261111-v3-booking.sql"
    mysql_admin xxl_job <<'SQL'
INSERT INTO xxl_job_info(job_group,job_desc,add_time,update_time,author,schedule_type,schedule_conf,
  misfire_strategy,executor_route_strategy,executor_handler,executor_block_strategy,glue_type,trigger_status)
VALUES
  (1,'Booking request recovery',NOW(),NOW(),'CultureHub','CRON','0/5 * * * * ?','FIRE_ONCE_NOW',
   'FIRST','bookingRequestRecovery','SERIAL_EXECUTION','BEAN',1),
  (1,'Booking inventory reconciliation',NOW(),NOW(),'CultureHub','CRON','0/30 * * * * ?','FIRE_ONCE_NOW',
   'FIRST','bookingInventoryReconciliation','SERIAL_EXECUTION','BEAN',1);
SQL
  fi
  if [ "${V3_KNOWLEDGE_ENABLED:-false}" = 'true' ]; then
    mysql_admin knowledge_db < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261111-v3-knowledge.sql"
    mysql_admin knowledge_db < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261112-v3-knowledge-tracing.sql"
  fi
  if [ "${V3_AI_ENABLED:-false}" = 'true' ]; then
    mysql_admin ai_db < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261112-v3-ai.sql"
    mysql_admin ai_db < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261112-v3-ai-group.sql"
  fi
  if [ "${V3_COMBINATION_ENABLED:-false}" = 'true' ]; then
    mysql_admin core_db < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261114-v3-seata-at.sql"
    mysql_admin core_db < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261114-v3-combination.sql"
    for suffix in a b; do
      case "$suffix" in
        a) password=${PARTNER_A_DB_PASSWORD:?Set PARTNER_A_DB_PASSWORD} ;;
        b) password=${PARTNER_B_DB_PASSWORD:?Set PARTNER_B_DB_PASSWORD} ;;
      esac
      case "$password" in *[!a-zA-Z0-9_-]*|'') echo 'Invalid Partner password' >&2; exit 1 ;; esac
      [ "${#password}" -ge 20 ] && [ "${#password}" -le 128 ] || exit 1
      schema="partner_db_$suffix"
      escaped_schema=$(printf '%s' "$schema" | sed 's/_/\\_/g')
      mysql_admin <<SQL
CREATE DATABASE IF NOT EXISTS \`$schema\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'culturehub_partner_$suffix'@'%' IDENTIFIED BY '$password';
GRANT SELECT,INSERT,UPDATE,DELETE ON \`$escaped_schema\`.* TO 'culturehub_partner_$suffix'@'%';
SQL
      sed "s/EXISTS culturehub /EXISTS $schema /;s/USE culturehub;/USE $schema;/" "$schema_dir/01-schema.sql" | mysql_admin
      mysql_admin "$schema" < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261111-v3-booking.sql"
      mysql_admin "$schema" < "${V3_MIGRATION_DIR:-/culturehub-migrations}/20261114-v3-seata-at.sql"
      if [ "${V3_DEMO_ENABLED:-false}" = 'true' ]; then
        mysql_admin "$schema" < "${V3_MIGRATION_DIR:-/culturehub-migrations}/demo-partner-sessions.sql"
      fi
    done
  fi
)
