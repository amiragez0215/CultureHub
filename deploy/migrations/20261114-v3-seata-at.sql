-- Apply to core_db, partner_db_a and partner_db_b after v3-booking.sql.
CREATE TABLE IF NOT EXISTS undo_log (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL,
  log_created DATETIME(6) NOT NULL,
  log_modified DATETIME(6) NOT NULL,
  UNIQUE KEY ux_undo_log (xid,branch_id)
) ENGINE=InnoDB;

-- Preserve the enum ordinals of pre-cutover rows. Only GROUP_HOLD is used by the new code.
ALTER TABLE booking_request MODIFY operation
  ENUM('DIRECT','JOIN','CONFIRM','PROMOTE','SAGA_RESERVE','SAGA_CONFIRM','SAGA_CANCEL','GROUP_HOLD') NOT NULL;

-- Serializes local hold registration with settlement. A terminal row also
-- fences a prepare HTTP request that arrives late after a timeout.
CREATE TABLE IF NOT EXISTS combination_branch (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  user_id BIGINT NOT NULL,
  outcome ENUM('COMMITTED','ROLLED_BACK')
) ENGINE=InnoDB;
