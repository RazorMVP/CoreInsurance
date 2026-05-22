-- ─────────────────────────────────────────────────────────────────────────────
-- V54 — Seed POLICY_COMMISSION_AGENT posting rule (Slice 84d).
--
-- Mirror of V52's POLICY_COMMISSION_BROKER, but credits 2330 (Commission
-- payable - Agents, from V32 COA seed) instead of 2320. SubledgerPostingService
-- picks the rule based on event.commissionSourceType — same chain shape, just
-- a different table-driven Cr account.
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
    ('POLICY_COMMISSION_AGENT',
     '5130', '2330',
     'Agent commission payable on policy %s for %s',
     TRUE, 'system-seed')
ON CONFLICT (source_event_type) DO NOTHING;
