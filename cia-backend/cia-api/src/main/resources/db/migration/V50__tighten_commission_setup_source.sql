-- V50: enforce CommissionSourceType as an enum on commission_setups.
--
-- The column was originally a free-text VARCHAR(50) defaulting to 'ALL', which
-- did not model the three real commission sources documented in PRD §2.1.17:
-- AGENT (insurer-side), BROKER (insured-side), RELATIONSHIP_MANAGER (insurer
-- staff). Backfill any non-canonical values to BROKER (the historical default
-- in NAICOM-licensed insurance distribution) before tightening the column.
--
-- Order matters: backfill → rename → drop default → add CHECK. Reversing this
-- would either fail the CHECK on legacy rows or leave the DEFAULT clause
-- referencing the old column name.

UPDATE commission_setups
   SET broker_type = 'BROKER'
 WHERE broker_type NOT IN ('AGENT', 'BROKER', 'RELATIONSHIP_MANAGER');

ALTER TABLE commission_setups
  RENAME COLUMN broker_type TO commission_source;

ALTER TABLE commission_setups
  ALTER COLUMN commission_source DROP DEFAULT;

ALTER TABLE commission_setups
  ADD CONSTRAINT ck_commission_source
  CHECK (commission_source IN ('AGENT', 'BROKER', 'RELATIONSHIP_MANAGER'));
