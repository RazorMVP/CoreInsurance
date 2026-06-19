-- ─────────────────────────────────────────────────────────────────────────────
-- V72 — Clauses table (policy clause bank)
--
-- Module 1 (Setup) — the clause master surfaced in Setup → Policy Specifications.
-- Quotes (V73) and policies (V74) snapshot the selected clauses' title/text at
-- selection time into their own `selected_clauses` JSONB, so an issued document
-- keeps its exact clause text regardless of later edits here.
--
-- Mirrors the agents table (V48): code-less master data with the V47 reasoned
-- soft-delete shape (audit_log.reason). `product_ids` is a JSONB list of product
-- UUIDs the clause applies to; empty = applies to all products.
--
-- The seed is the eight clauses that previously lived only in the frontend mock
-- (INITIAL_CLAUSES). Deterministic UUIDs; product_ids left empty (the mock's
-- '1'..'4' product refs do not map to real per-tenant product UUIDs — admins
-- associate clauses with products via the Clause Bank CRUD).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS clauses (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title          VARCHAR(200) NOT NULL,
    text           TEXT         NOT NULL,
    type           VARCHAR(30)  NOT NULL DEFAULT 'STANDARD',
    applicability  VARCHAR(20)  NOT NULL DEFAULT 'OPTIONAL',
    product_ids    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by     VARCHAR(255),
    deleted_at     TIMESTAMPTZ,

    CONSTRAINT ck_clauses_type CHECK (type IN ('STANDARD', 'EXCLUSION', 'SPECIAL_CONDITION', 'WARRANTY')),
    CONSTRAINT ck_clauses_applicability CHECK (applicability IN ('MANDATORY', 'OPTIONAL'))
);

CREATE INDEX idx_clauses_active ON clauses (deleted_at) WHERE deleted_at IS NULL;

INSERT INTO clauses (id, title, text, type, applicability) VALUES
  ('00000000-0000-0000-0000-0000000000c1', 'Third Party Liability',
   'Indemnity for third party bodily injury and property damage as per the Motor Vehicles (Third Party Insurance) Act.',
   'STANDARD', 'MANDATORY'),
  ('00000000-0000-0000-0000-0000000000c2', 'Own Damage',
   'Covers accidental damage to the insured vehicle including fire, theft and malicious damage.',
   'STANDARD', 'MANDATORY'),
  ('00000000-0000-0000-0000-0000000000c3', 'Exclusion — Racing',
   'This policy does not cover loss or damage arising from or whilst the vehicle is used in racing, rallying or similar events.',
   'EXCLUSION', 'OPTIONAL'),
  ('00000000-0000-0000-0000-0000000000c4', 'Special Condition — Alarm System',
   'It is a special condition of this policy that a NSIA-approved burglar alarm system is installed and in full operation throughout the period of insurance.',
   'SPECIAL_CONDITION', 'OPTIONAL'),
  ('00000000-0000-0000-0000-0000000000c5', 'Burglary & Housebreaking',
   'Indemnity against loss or damage resulting from burglary, housebreaking or theft involving forcible entry.',
   'STANDARD', 'MANDATORY'),
  ('00000000-0000-0000-0000-0000000000c6', 'Exclusion — Wear & Tear',
   'This policy excludes damage attributable to gradual deterioration, wear and tear or inherent vice.',
   'EXCLUSION', 'OPTIONAL'),
  ('00000000-0000-0000-0000-0000000000c7', 'Marine — Institute Cargo',
   'Coverage in accordance with the Institute Cargo Clauses (A) for all risks of physical loss or damage.',
   'STANDARD', 'MANDATORY'),
  ('00000000-0000-0000-0000-0000000000c8', 'Warranty — Security Survey',
   'It is warranted that a security survey be completed and recommendations implemented within 30 days of policy inception.',
   'WARRANTY', 'OPTIONAL');
