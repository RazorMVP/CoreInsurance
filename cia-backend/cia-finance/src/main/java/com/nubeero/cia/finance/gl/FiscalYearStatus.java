package com.nubeero.cia.finance.gl;

/**
 * Lifecycle status of a {@link FiscalYear} row.
 *
 * <ul>
 *   <li>{@code PLANNING} — created but not yet the current FY. Child periods
 *       already exist (D2=A — generated at create time) but no caller flow
 *       targets a PLANNING year by default.</li>
 *   <li>{@code ACTIVE} — the tenant's current fiscal year. Only one row may
 *       hold this status at a time. Enforced by {@code FiscalYearService},
 *       not by a DB constraint — the V31 design comment explains the
 *       enforcement happens at the service layer because the transition
 *       between years needs to be atomic.</li>
 *   <li>{@code CLOSED} — terminal: the year is finished. Period-level locks
 *       (Slice 1.7) govern whether postings can still happen against
 *       individual periods, so {@code CLOSED} at the FY level means "no
 *       longer the current year", not "no more posting allowed".</li>
 * </ul>
 *
 * <p>D3=B locks the activation rule: if a FY is already {@code ACTIVE},
 * activating another FY throws {@link FiscalYearActivationConflictException}
 * (422) rather than auto-deactivating the prior. Admin must explicitly
 * {@code close()} the prior FY first.
 */
public enum FiscalYearStatus {
    PLANNING,
    ACTIVE,
    CLOSED
}
