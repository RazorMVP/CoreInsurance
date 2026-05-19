-- ─────────────────────────────────────────────────────────────────────────────
-- V38 — IFRS 17 §103 movement analysis view
--
-- Module 12 (Period-End Closures) Phase 2 Slice 2.8 — disclosure layer.
--
-- The view {@code paa_movement_analysis} re-shapes the roll-forward tables
-- {@code paa_lrc} and {@code paa_lic} into the §103 / §104 / §105 disclosure
-- presentation expected by IFRS 17:
--
--   LRC movement: opening + premium_received − premium_earned
--                       + acquisition_costs_deferred − acquisition_costs_amortised
--                       + loss_component_change
--                       = closing
--
--   LIC movement: opening + claims_incurred − claims_paid
--                       + case_reserve_change + ibnr_change
--                       + risk_adjustment_change + discount_unwind
--                       = closing
--
--   Insurance contract liability = LRC + LIC
--
-- Per-group rows preserve portfolio / cohort_year / onerousness dimensions so
-- the §22 grouping is observable in the disclosure (auditors require this).
--
-- The view filters out (group, period) pairs where neither paa_lrc nor paa_lic
-- carries a row — those are inactive groups in the period and would clutter
-- the disclosure with all-zero entries.
--
-- This is a regular SQL VIEW, not a MATERIALIZED VIEW. Movement analysis is
-- read-rarely (period-end disclosure + ad-hoc audit drilldowns), and the
-- underlying paa_lrc / paa_lic tables are write-once-per-period from the
-- Slice 2.3–2.7 engines — making the view inexpensive to evaluate on demand.
-- Materialisation can be revisited once observed read patterns motivate it.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE VIEW paa_movement_analysis AS
SELECT
    fp.id           AS period_id,
    fp.start_date   AS period_start,
    fp.end_date     AS period_end,
    p.id            AS portfolio_id,
    p.code          AS portfolio_code,
    p.name          AS portfolio_name,
    g.id            AS group_id,
    g.cohort_year   AS cohort_year,
    g.onerousness   AS onerousness,
    g.status        AS group_status,

    -- LRC components (paa_lrc; NULL → 0 for groups with no LRC activity)
    COALESCE(lrc.opening_balance, 0)              AS lrc_opening,
    COALESCE(lrc.premium_received, 0)             AS premium_received,
    COALESCE(lrc.premium_earned, 0)               AS premium_earned,
    COALESCE(lrc.acquisition_costs_deferred, 0)   AS acquisition_costs_deferred,
    COALESCE(lrc.acquisition_costs_amortised, 0)  AS acquisition_costs_amortised,
    COALESCE(lrc.loss_component, 0)               AS loss_component,
    COALESCE(lrc.loss_component_change, 0)        AS loss_component_change,
    COALESCE(lrc.closing_balance, 0)              AS lrc_closing,

    -- LIC components (paa_lic; NULL → 0 for groups with no LIC activity)
    COALESCE(lic.opening_balance, 0)              AS lic_opening,
    COALESCE(lic.claims_incurred, 0)              AS claims_incurred,
    COALESCE(lic.claims_paid, 0)                  AS claims_paid,
    COALESCE(lic.case_reserve_change, 0)          AS case_reserve_change,
    COALESCE(lic.ibnr_estimate, 0)                AS ibnr_estimate,
    COALESCE(lic.ibnr_change, 0)                  AS ibnr_change,
    COALESCE(lic.risk_adjustment, 0)              AS risk_adjustment,
    COALESCE(lic.risk_adjustment_change, 0)       AS risk_adjustment_change,
    COALESCE(lic.discount_unwind, 0)              AS discount_unwind,
    COALESCE(lic.closing_balance, 0)              AS lic_closing,

    -- Total insurance contract liability (LRC + LIC per §99(b))
    COALESCE(lrc.opening_balance, 0) + COALESCE(lic.opening_balance, 0) AS total_opening,
    COALESCE(lrc.closing_balance, 0) + COALESCE(lic.closing_balance, 0) AS total_closing,

    -- Currency (taken from whichever side has data; verified consistent at engine layer)
    COALESCE(lrc.currency_code, lic.currency_code, 'NGN') AS currency_code

FROM fiscal_period fp
CROSS JOIN group_of_contracts g
JOIN portfolio p
    ON p.id = g.portfolio_id
   AND p.deleted_at IS NULL
LEFT JOIN paa_lrc lrc
    ON lrc.period_id = fp.id
   AND lrc.group_id  = g.id
   AND lrc.deleted_at IS NULL
LEFT JOIN paa_lic lic
    ON lic.period_id = fp.id
   AND lic.group_id  = g.id
   AND lic.deleted_at IS NULL
WHERE fp.deleted_at IS NULL
  AND g.deleted_at IS NULL
  AND (lrc.id IS NOT NULL OR lic.id IS NOT NULL);

COMMENT ON VIEW paa_movement_analysis IS
    'IFRS 17 §103 movement analysis: per-(period, group) roll-forward of LRC + LIC '
    'components, with portfolio + cohort_year + onerousness dimensions preserved '
    'for §22 grouping disclosure. One row per (group, period) where either the LRC '
    'or LIC side has activity. Read on demand by MovementAnalysisService '
    '(Slice 2.8) and downstream NAICOM submission tooling (Phase 4).';
