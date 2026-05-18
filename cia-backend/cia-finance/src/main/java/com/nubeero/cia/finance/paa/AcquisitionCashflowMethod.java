package com.nubeero.cia.finance.paa;

/**
 * Treatment of insurance acquisition cashflows under PAA per IFRS 17 §59(a).
 * Recorded on {@link PaaConfig} as the tenant's accounting policy election.
 *
 * <p>Values match {@code ck_paa_config_acq_method} in V36.
 */
public enum AcquisitionCashflowMethod {

    /** Expense acquisition cashflows when incurred — the §59(a) simplification. Most common for annual GB. */
    EXPENSE_AS_INCURRED,

    /** Defer and amortise over the coverage period — required when contract terms exceed one year. */
    DEFER_AND_AMORTISE
}
