-- ─────────────────────────────────────────────────────────────────────────────
-- V48 — Agents table
--
-- Module 1 (Setup) — NAICOM-licensed insurance agent master data. Agents
-- represent the INSURER (the insurance company) and earn commission on
-- policies sold, distinct from Brokers (V3 — represent the INSURED) and from
-- Adjusters (V45 — perform post-loss claim assessment).
--
-- Mirrors the adjusters table structure (V45) with one adaptation: the `type`
-- enum is INDIVIDUAL / CORPORATE (the natural distinction for licensed agents)
-- rather than adjusters' INTERNAL / EXTERNAL. Reuses the same code + license
-- + contact + soft-delete shape so the V47 reasoned-delete convention applies
-- unmodified through `audit_log.reason`.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS agents (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255) NOT NULL,
    code           VARCHAR(20)  NOT NULL UNIQUE,
    type           VARCHAR(20)  NOT NULL DEFAULT 'INDIVIDUAL',
    license_number VARCHAR(50),
    email          VARCHAR(255),
    phone          VARCHAR(30),
    address        TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(255),
    deleted_at     TIMESTAMPTZ,

    CONSTRAINT ck_agents_type CHECK (type IN ('INDIVIDUAL', 'CORPORATE'))
);

CREATE INDEX idx_agents_active ON agents (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_agents_type ON agents (type) WHERE deleted_at IS NULL;
