package com.nubeero.cia.finance.gl;

/**
 * Period granularity within a fiscal year.
 *
 * <p>MONTH / QUARTER / HALF_YEAR / YEAR periods are generated eagerly when a
 * fiscal year is activated (Slice 1.6). DAY periods are generated lazily on
 * first reference to avoid 365 rows per tenant per year for a feature most
 * tenants will rarely use.
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
