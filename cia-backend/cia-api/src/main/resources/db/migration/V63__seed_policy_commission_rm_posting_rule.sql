-- ─────────────────────────────────────────────────────────────────────────────
-- V63 — Seed POLICY_COMMISSION_RM posting rule (Task 3.1 — B2 RM commission).
--
-- RM commission accrual posts Dr 5130 (Insurance acquisition expense, same as
-- broker/agent — a commission is an acquisition cost regardless of payee) /
-- Cr 2520 (Staff payables, because the RM is internal staff vs 2320/2330 for
-- external counterparties).
--
-- Idempotent: ON CONFLICT (source_event_type) DO NOTHING.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO posting_rule (
    source_event_type,
    debit_account_code,
    credit_account_code,
    narrative_template,
    is_active,
    created_by
) VALUES
    ('POLICY_COMMISSION_RM',
     '5130', '2520',
     'RM commission payable on policy %s',
     TRUE, 'system-seed')
ON CONFLICT (source_event_type) DO NOTHING;
