-- Optional cleanup AFTER draining the previous runtime and backing up its data.
-- Run against each of core_db, partner_db_a and partner_db_b.
-- Do not run if any legacy combination is still pending or needs compensation.
DROP TABLE IF EXISTS saga_booking_link;
DROP TABLE IF EXISTS saga_participant_hold;
DROP TABLE IF EXISTS saga_participant_command;
DROP TABLE IF EXISTS saga_participant;
-- Retain old booking_request, participation, inventory audit and Outbox rows.
-- The old saga_db is a historical backup and is no longer provisioned or used.
