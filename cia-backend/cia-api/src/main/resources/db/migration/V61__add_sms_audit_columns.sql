-- V61__add_sms_audit_columns.sql
--
-- F7-δ + R7 — SMS delivery audit columns on receipts + payments.
-- Populated by SMS workflow activities on successful delivery.
-- Mirrors V57's email_sent_at / email_sent_to.

ALTER TABLE receipts ADD COLUMN sms_sent_at TIMESTAMPTZ;
ALTER TABLE receipts ADD COLUMN sms_sent_to VARCHAR(50);

ALTER TABLE payments ADD COLUMN sms_sent_at TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN sms_sent_to VARCHAR(50);
