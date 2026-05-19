-- V34 — add currency_code to the policies table.
--
-- Background: every other money-bearing aggregate already carries an ISO-4217
-- currency_code column (V7 finance, V8 endorsements, V9 claims, V10 reinsurance).
-- The policies table was the only outlier — it inherited the implicit-NGN
-- assumption from the pre-V31 codebase.
--
-- Slice 1.8a's RetroactiveJournalBackfillActivitiesImpl selects currency_code
-- from policies when replaying POLICY_APPROVED → journal entries, so the
-- backfill IT could not run end-to-end without this column. Adding it here
-- (rather than hardcoding 'NGN' in the activity SQL) future-proofs for
-- multi-currency policies, which Phase 2 IFRS 17 measurement work will need
-- anyway.
--
-- Default 'NGN' applies to all existing rows so the migration is non-breaking
-- for tenants whose policies were issued before this slice. New policy
-- aggregates set the column explicitly in PolicyService.create*().

ALTER TABLE policies
    ADD COLUMN currency_code VARCHAR(3) NOT NULL DEFAULT 'NGN';

COMMENT ON COLUMN policies.currency_code IS
    'ISO 4217 currency code for the policy''s premium and sums insured. '
    'Default NGN for Nigeria-first deployments; future tenants can issue '
    'policies in other currencies. Read by the retroactive JE backfill '
    'activity to stamp the right currency on replayed journal entries.';
