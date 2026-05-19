package com.nubeero.cia.finance.paa;

/**
 * Lifecycle status of an IFRS 17 group of contracts. New contracts can only
 * be assigned to {@link #OPEN} groups; {@link #CLOSED} indicates the cohort
 * window has elapsed and no further contracts can join.
 *
 * <p>Values match {@code ck_group_status} in V36.
 */
public enum GroupStatus {

    /** Cohort still accepting new contract assignments. */
    OPEN,

    /** Cohort window elapsed; no further assignments accepted. Existing contracts continue to be measured. */
    CLOSED
}
