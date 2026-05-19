-- ─────────────────────────────────────────────────────────────────────────────
-- V40 — IFRS 9 §B5.5.39 / IFRS 7 §35M investment movement analysis view
--
-- Module 12 (Period-End Closures) Phase 3 Slice 3.7 — disclosure layer.
--
-- The view {@code ifrs9_investment_movement_analysis} re-shapes the
-- investment_carrying_value + investment_holding tables into the
-- per-(holding, period) disclosure shape IFRS 9 expects:
--
--   AC roll-forward:
--     opening + effective_interest_income + coupon_received
--             − ecl_movement − impairment_loss − disposals
--             = closing
--
--   FVOCI_DEBT roll-forward (both P&L and OCI components):
--     opening + effective_interest_income (P&L)
--             + fair_value_change_oci (OCI)
--             + ecl_movement (P&L expense, OCI offset per §5.7.10A)
--             = closing
--
--   FVPL roll-forward:
--     opening + fair_value_change_pnl (P&L)
--             − disposals
--             = closing
--
-- Holdings without a carrying-value row for the period are excluded
-- (mirrors paa_movement_analysis filter — auditors don't want zero-row
-- noise in the disclosure).
--
-- Holding dimensions (asset_type, classification, ecl_stage, currency_code,
-- isin, security_name, issuer) are preserved so the disclosure can be
-- pivoted by any of them without re-joining at query time.
--
-- This is a regular SQL VIEW, not MATERIALIZED — same reasoning as Slice 2.8:
-- read-rarely, underlying tables are write-once-per-period from the
-- Slices 3.3–3.6 engines, view re-evaluation is inexpensive.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE VIEW ifrs9_investment_movement_analysis AS
SELECT
    fp.id                       AS period_id,
    fp.start_date               AS period_start,
    fp.end_date                 AS period_end,

    h.id                        AS holding_id,
    h.isin                      AS isin,
    h.security_name             AS security_name,
    h.issuer                    AS issuer,
    h.asset_type                AS asset_type,
    h.classification            AS classification,
    h.status                    AS holding_status,
    h.currency_code             AS currency_code,
    h.maturity_date             AS maturity_date,

    cv.opening_balance           AS opening_balance,
    cv.effective_interest_income AS effective_interest_income,
    cv.coupon_received           AS coupon_received,
    cv.fair_value_change_pnl     AS fair_value_change_pnl,
    cv.fair_value_change_oci     AS fair_value_change_oci,
    cv.ecl_movement              AS ecl_movement,
    cv.impairment_loss           AS impairment_loss,
    cv.disposals                 AS disposals,
    cv.closing_balance           AS closing_balance,
    cv.closing_fair_value        AS closing_fair_value,
    cv.ecl_stage                 AS ecl_stage,

    -- §B5.5.39(a) — total income for the period from this instrument
    (cv.effective_interest_income + cv.fair_value_change_pnl) AS total_pnl_income,
    -- §B5.5.39(b) — total OCI movement for the period
    cv.fair_value_change_oci                                  AS total_oci_movement

FROM fiscal_period fp
JOIN investment_carrying_value cv ON cv.period_id = fp.id AND cv.deleted_at IS NULL
JOIN investment_holding h         ON h.id = cv.holding_id  AND h.deleted_at IS NULL
WHERE fp.deleted_at IS NULL;

COMMENT ON VIEW ifrs9_investment_movement_analysis IS
    'IFRS 9 §B5.5.39 / IFRS 7 §35M per-(holding, period) movement analysis. '
    'One row per (holding, period) where investment_carrying_value carries data. '
    'Read on demand by Ifrs9MovementAnalysisService (Slice 3.7) and downstream '
    'NAICOM submission tooling (Phase 4). Component columns are nullable in '
    'practice — e.g. FVPL holdings have null effective_interest_income, AC '
    'holdings have null fair_value_change_*. Disclosure consumers filter by '
    'classification to render the correct per-segment roll-forward.';
