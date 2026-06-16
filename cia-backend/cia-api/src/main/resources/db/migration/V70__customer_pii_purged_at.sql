-- V70: NDPR retention-purge idempotency sentinel.
-- Set to now() when a customer's master PII is anonymized by the retention purge
-- (Slice B). NULL = never purged. The purge eligibility query filters on
-- pii_purged_at IS NULL so an anonymized customer is never re-processed.
ALTER TABLE customers ADD COLUMN IF NOT EXISTS pii_purged_at TIMESTAMPTZ;

-- Partial index: the hourly purge sweep repeatedly scans for not-yet-purged
-- customers; index only the rows it cares about.
CREATE INDEX IF NOT EXISTS ix_customers_pii_not_purged
    ON customers (id) WHERE pii_purged_at IS NULL;
