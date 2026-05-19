-- ─────────────────────────────────────────────────────────────────────────────
-- V43 — backfill class_of_business_id on historical journal_entry_line rows
--
-- Module 12 (Period-End Closures) Slice 1.10a. The V42 migration added
-- the column; this migration populates it for every JE posted before
-- the SubledgerPostingService refactor (the same Slice 1.10a) started
-- writing it directly.
--
-- The originating policy / claim is resolved by parsing
-- journal_entry.source_reference (an idempotency-triple UUID) against
-- the appropriate business table, then projecting the class. Each
-- subledger event type has its own resolution path because the
-- source_reference identifies a different entity per type:
--
--   source_module  | source_event_type            | source_reference is
--   ---------------+------------------------------+--------------------
--   policy         | POLICY_APPROVED              | policies.id
--   claim          | CLAIM_APPROVED               | claims.id
--   claim          | CLAIM_SETTLED                | claims.id
--   claim          | CLAIM_EXPENSE_APPROVED       | claim_expenses.id
--   endorsement    | ENDORSEMENT_PREMIUM_*        | endorsements.id
--   reinsurance    | FAC_PREMIUM_CEDED            | ri_fac_covers.id
--
-- ── Design decisions ────────────────────────────────────────────────────────
--   • Per-event-type UPDATE statements. A single dynamic query joining
--     every source table via UNION would be brittle (column-availability
--     varies, status-filter differences); five small UPDATEs are easier
--     to audit and rerun.
--
--   • Idempotent. Every UPDATE filters on
--     {@code jel.class_of_business_id IS NULL} so re-running the
--     migration (e.g., via Flyway's repair/clean-state recovery) only
--     populates rows that still need it. Flyway itself runs each V*.sql
--     once per database, but this idempotency safety-net protects
--     against partial-failure replays.
--
--   • Phase 2 (PAA) and Phase 3 (IFRS-9) JEs are intentionally NOT
--     touched. PAA JEs have source_module = 'paa' and resolve class
--     from contract groups (deferred to a future slice — see Slice 1.10
--     scoping in cia-log.md). IFRS-9 investment JEs have no class
--     semantics by design (investments are not COB-classifiable).
--
--   • Soft-deleted source rows are silently skipped: the joined source
--     tables filter on {@code deleted_at IS NULL} so a backfill target
--     whose policy / claim has been deleted leaves the JE line with a
--     null class — ops-investigatable rather than fabricated data.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Policy approvals — source_reference is policies.id ──────────────────────
UPDATE journal_entry_line jel
SET class_of_business_id = p.class_of_business_id
FROM journal_entry je,
     policies p
WHERE jel.journal_entry_id = je.id
  AND je.source_module = 'policy'
  AND je.source_event_type = 'POLICY_APPROVED'
  AND p.id::text = je.source_reference
  AND p.deleted_at IS NULL
  AND je.deleted_at IS NULL
  AND jel.deleted_at IS NULL
  AND jel.class_of_business_id IS NULL;

-- ── Claim approvals + settlements — source_reference is claims.id ──────────
UPDATE journal_entry_line jel
SET class_of_business_id = c.class_of_business_id
FROM journal_entry je,
     claims c
WHERE jel.journal_entry_id = je.id
  AND je.source_module = 'claim'
  AND je.source_event_type IN ('CLAIM_APPROVED', 'CLAIM_SETTLED')
  AND c.id::text = je.source_reference
  AND c.deleted_at IS NULL
  AND je.deleted_at IS NULL
  AND jel.deleted_at IS NULL
  AND jel.class_of_business_id IS NULL;

-- ── Claim expenses — source_reference is claim_expenses.id ─────────────────
UPDATE journal_entry_line jel
SET class_of_business_id = c.class_of_business_id
FROM journal_entry je,
     claim_expenses ce,
     claims c
WHERE jel.journal_entry_id = je.id
  AND je.source_module = 'claim'
  AND je.source_event_type = 'CLAIM_EXPENSE_APPROVED'
  AND ce.id::text = je.source_reference
  AND c.id = ce.claim_id
  AND ce.deleted_at IS NULL
  AND c.deleted_at IS NULL
  AND je.deleted_at IS NULL
  AND jel.deleted_at IS NULL
  AND jel.class_of_business_id IS NULL;

-- ── Endorsements — source_reference is endorsements.id ─────────────────────
UPDATE journal_entry_line jel
SET class_of_business_id = p.class_of_business_id
FROM journal_entry je,
     endorsements e,
     policies p
WHERE jel.journal_entry_id = je.id
  AND je.source_module = 'endorsement'
  AND je.source_event_type IN ('ENDORSEMENT_PREMIUM_ADDITIONAL', 'ENDORSEMENT_PREMIUM_REFUND')
  AND e.id::text = je.source_reference
  AND p.id = e.policy_id
  AND e.deleted_at IS NULL
  AND p.deleted_at IS NULL
  AND je.deleted_at IS NULL
  AND jel.deleted_at IS NULL
  AND jel.class_of_business_id IS NULL;

-- ── FAC outward cessions — source_reference is ri_fac_covers.id ─────────────
UPDATE journal_entry_line jel
SET class_of_business_id = p.class_of_business_id
FROM journal_entry je,
     ri_fac_covers rfc,
     policies p
WHERE jel.journal_entry_id = je.id
  AND je.source_module = 'reinsurance'
  AND je.source_event_type = 'FAC_PREMIUM_CEDED'
  AND rfc.id::text = je.source_reference
  AND p.id = rfc.policy_id
  AND rfc.deleted_at IS NULL
  AND p.deleted_at IS NULL
  AND je.deleted_at IS NULL
  AND jel.deleted_at IS NULL
  AND jel.class_of_business_id IS NULL;
