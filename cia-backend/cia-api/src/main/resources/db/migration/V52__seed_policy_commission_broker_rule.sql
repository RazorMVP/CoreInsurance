-- ─────────────────────────────────────────────────────────────────────────────
-- V52 — Seed POLICY_COMMISSION_BROKER posting rule (Slice 84c)
--
-- Closes audit finding E from the Session 84 PRD §2.1.17 drift report:
-- broker commission becomes payable at policy approval and needs both a GL
-- journal entry and a payables credit note. This migration adds the rule for
-- the JE side; the credit-note side is wired in PolicyCommissionCreditNoteListener.
--
-- Posting accounts (V32 seed):
--   5130 Insurance acquisition expense        (Dr — recognise the expense)
--   2320 Commission payable - Brokers         (Cr — liability owed to broker)
--
-- This is the BROKER-only path. V51 only ever populates the commission snapshot
-- for broker-attributed policies today (Open Question #11 in PRD v2.7 — agent
-- and relationship-manager attribution at the policy level is pending). When
-- Q#11 lands, a follow-up slice adds POLICY_COMMISSION_AGENT → Dr 5130 / Cr 2330.
-- Relationship-manager commission would route through 2520 (Staff payables) as
-- a payroll incentive rather than a commission CN.
--
-- Idempotent: ON CONFLICT (source_event_type) DO NOTHING — re-running is safe.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO posting_rule (
    source_event_type,
    debit_account_code,
    credit_account_code,
    narrative_template,
    is_active,
    created_by
) VALUES
    ('POLICY_COMMISSION_BROKER',
     '5130', '2320',
     'Broker commission payable on policy %s for %s',
     TRUE, 'system-seed')
ON CONFLICT (source_event_type) DO NOTHING;
