-- ─────────────────────────────────────────────────────────────────────────────
-- V45 — Adjusters table
--
-- Module 1 (Setup) — NAICOM-licensed loss adjuster master data. Distinct from
-- surveyors (pre-loss inspections — V3 created `surveyors`); adjusters perform
-- post-loss claim assessment.
--
-- Mirrors the surveyors table structure + adds `code` (unique short ref,
-- consistent with brokers / sbus / branches) and `address`.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS adjusters (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(255) NOT NULL,
    code           VARCHAR(20)  NOT NULL UNIQUE,
    type           VARCHAR(20)  NOT NULL DEFAULT 'EXTERNAL',
    license_number VARCHAR(50),
    email          VARCHAR(255),
    phone          VARCHAR(30),
    address        TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(255),
    deleted_at     TIMESTAMPTZ,

    CONSTRAINT ck_adjusters_type CHECK (type IN ('INTERNAL', 'EXTERNAL'))
);

CREATE INDEX idx_adjusters_active ON adjusters (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_adjusters_type ON adjusters (type) WHERE deleted_at IS NULL;
