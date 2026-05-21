package com.nubeero.cia.finance.gl;

/**
 * Period granularity within a fiscal year.
 *
 * <p>MONTH / QUARTER / HALF_YEAR / YEAR periods are generated eagerly when a
 * fiscal year is created (Slice 1.6). {@link #DAY} is allowed by the V31
 * {@code ck_fiscal_period_type} CHECK constraint but is <b>not produced by
 * any current code path</b> — it remains in the enum to keep the V31 schema
 * authoritative and to leave room for a future per-day granularity if one
 * is ever needed. Until then, no caller resolves or writes DAY rows.
 *
 * <p>Slice 1.4 (gateway) resolves only the MONTH period for a given business
 * date — see {@link FiscalPeriodResolver}.
 */
public enum FiscalPeriodType {
    DAY,
    MONTH,
    QUARTER,
    HALF_YEAR,
    YEAR
}
