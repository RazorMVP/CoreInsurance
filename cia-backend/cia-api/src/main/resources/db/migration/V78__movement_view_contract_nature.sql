-- ─────────────────────────────────────────────────────────────────────────────
-- V78 — paa_movement_analysis view gains contract_nature
--
-- FAC / IFRS-17 PAA workstream, Task 6 — downstream contract_nature
-- surfacing. Direct policies vs. facultative reinsurance (inward/outward)
-- are already segregated into their own groups via portfolio.contract_nature
-- (V76) — this migration carries that dimension into the §103 movement
-- disclosure view so RI-vs-direct is distinguishable in disclosure output
-- (Ifrs17DisclosureEngine, the CLOSURES reports) without a second query.
--
-- CREATE OR REPLACE VIEW in PostgreSQL may only ADD new columns at the END
-- of the SELECT list — it cannot reorder or remove existing output columns.
-- This migration is therefore a byte-for-byte copy of V38's body with a
-- single trailing column appended: p.contract_nature AS contract_nature.
-- No other change. The view already JOINs portfolio p ON p.id = g.portfolio_id,
-- so no new join is needed.
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
    COALESCE(lrc.currency_code, lic.currency_code, 'NGN') AS currency_code,

    -- Contract nature (V76) — DIRECT / FAC_INWARD / FAC_OUTWARD. Appended
    -- last per the CREATE OR REPLACE VIEW column-append-only constraint.
    p.contract_nature AS contract_nature

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
    'components, with portfolio + cohort_year + onerousness + contract_nature '
    'dimensions preserved for §22 grouping disclosure and RI-vs-direct '
    'distinguishability. One row per (group, period) where either the LRC '
    'or LIC side has activity. Read on demand by MovementAnalysisService '
    '(Slice 2.8) and downstream NAICOM submission tooling (Phase 4). '
    'contract_nature added V78 (FAC / IFRS-17 PAA workstream Task 6).';
