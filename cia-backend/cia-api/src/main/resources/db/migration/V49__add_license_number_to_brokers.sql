-- ─────────────────────────────────────────────────────────────────────────────
-- V49 — Add NAICOM license number to brokers
--
-- Module 1 (Setup). Brokers in Nigerian insurance are NAICOM-licensed
-- intermediaries; the field exists for every other NAICOM-regulated
-- counterparty in this module (insurance_companies.naicom_license, surveyors
-- + adjusters + agents.license_number) but was missing on `brokers` —
-- a documentation + UI gap rather than a deliberate schema choice.
--
-- Nullable for migration safety; existing broker rows stay valid. New brokers
-- entered through the UI from V49+ supply the licence at create time.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE brokers
    ADD COLUMN license_number VARCHAR(50);
