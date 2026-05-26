-- V57__add_email_tracking_to_receipts_payments.sql
--
-- Adds nullable email tracking columns for F7 slice γ. Both columns
-- are populated by the Temporal email-workflow activity on successful
-- delivery — left null otherwise.

ALTER TABLE receipts ADD COLUMN email_sent_at TIMESTAMPTZ;
ALTER TABLE receipts ADD COLUMN email_sent_to VARCHAR(255);

ALTER TABLE payments ADD COLUMN email_sent_at TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN email_sent_to VARCHAR(255);
