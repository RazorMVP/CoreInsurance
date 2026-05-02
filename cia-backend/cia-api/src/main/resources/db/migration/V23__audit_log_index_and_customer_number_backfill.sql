-- V23: post-review fixes
--   1. Composite index on audit_log to support AlertDetectionService.checkBulkDelete()
--      which counts (user_id, action, timestamp >= ?) on every audit write.
--   2. Backfill customer_number for any rows that pre-date V20 — older customers
--      have NULL customer_number, which breaks toSummary() mappings. Use a
--      'LEGACY/{8-char id suffix}' placeholder; format-generated numbers
--      use the configured prefix (e.g. 'CUST/') and won't collide.

CREATE INDEX IF NOT EXISTS idx_audit_log_user_action_ts
    ON audit_log (user_id, action, timestamp DESC);

UPDATE customers
SET    customer_number = 'LEGACY/' || SUBSTRING(id::text FROM 1 FOR 8)
WHERE  customer_number IS NULL;
