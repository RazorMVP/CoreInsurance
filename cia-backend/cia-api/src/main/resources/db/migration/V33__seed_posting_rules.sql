-- ─────────────────────────────────────────────────────────────────────────────
-- V33 — Seed default posting rules (Module 12 Slice 1.5)
--
-- One row per sub-ledger event type handled by SubledgerPostingService's
-- table-driven posting path. Five events here; the sixth (FAC_PREMIUM_CEDED)
-- is a compound 3-line posting handled inline in the service because
-- posting_rule's (1 Dr + 1 Cr per row) shape cannot express it.
--
-- COA codes referenced match V32 seed:
--   1120 Bank current accounts
--   1310 Premium receivable - Direct
--   2110 LRC - Best estimate of liabilities
--   2140 LIC - Outstanding claims reserve
--   2350 Claims payable
--   5110 Incurred claims
--   5140 Other directly attributable expenses
--
-- Narrative templates use java.lang.String.format positional %s placeholders.
-- The number/order of placeholders MUST match the narrativeArgs passed by the
-- corresponding @EventListener method in SubledgerPostingService.
--
-- Idempotent: ON CONFLICT (source_event_type) DO NOTHING — re-running the
-- migration (e.g. when slice-test contexts recycle the schema) is safe.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO posting_rule (
    source_event_type,
    debit_account_code,
    credit_account_code,
    narrative_template,
    is_active,
    created_by
) VALUES
    -- 1. Policy approved → set up premium receivable + IFRS 17 LRC BEL
    ('POLICY_APPROVED',
     '1310', '2110',
     'Premium booking for policy %s',
     TRUE, 'system-seed'),

    -- 2. Claim approved → recognise incurred claims P&L + LIC OCR reserve
    ('CLAIM_APPROVED',
     '5110', '2140',
     'Claim approval for %s on policy %s',
     TRUE, 'system-seed'),

    -- 3. Claim settled → clear LIC OCR reserve + reduce bank balance
    ('CLAIM_SETTLED',
     '2140', '1120',
     'Settlement of claim %s',
     TRUE, 'system-seed'),

    -- 4. Claim expense approved → recognise direct expense + claim payable
    ('CLAIM_EXPENSE_APPROVED',
     '5140', '2350',
     'Claim expense %s on claim %s',
     TRUE, 'system-seed'),

    -- 5a. Endorsement (premium adjustment > 0) → same shape as POLICY_APPROVED
    ('ENDORSEMENT_PREMIUM_ADDITIONAL',
     '1310', '2110',
     'Endorsement %s additional premium for policy %s',
     TRUE, 'system-seed'),

    -- 5b. Endorsement (premium adjustment < 0) → mirror of 5a (Dr/Cr swapped)
    ('ENDORSEMENT_PREMIUM_REFUND',
     '2110', '1310',
     'Endorsement %s premium refund for policy %s',
     TRUE, 'system-seed')
ON CONFLICT (source_event_type) DO NOTHING;
