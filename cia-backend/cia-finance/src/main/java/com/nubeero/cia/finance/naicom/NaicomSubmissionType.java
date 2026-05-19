package com.nubeero.cia.finance.naicom;

/**
 * Catalogue of NAICOM regulatory submission types produced by the
 * Module 12 Phase 4 submission engines.
 *
 * <p>The N0n codes follow the existing Module 11 SYSTEM-report catalogue
 * (see {@code V18__seed_system_report_definitions.sql}); the IFRS-17 /
 * IFRS-9 disclosure types are additional submissions Phase 4 produces from
 * the V38 / V40 disclosure views.
 *
 * <p>{@code NIID_STATUS_SNAPSHOT} (N07) is technically not a NAICOM
 * submission — it is a NIID-side compliance snapshot — but it shares the
 * Phase 4 submission infrastructure (state machine, artifact rendering,
 * period-lock precondition, audit history) so it lives here.
 */
public enum NaicomSubmissionType {
    /** N01 — Annual revenue account (premium earned, claims incurred, expenses, P&L per class). */
    ANNUAL_REVENUE_ACCOUNT,
    /** N02 — Annual balance sheet (assets, liabilities, shareholders' funds — prescribed format). */
    BALANCE_SHEET,
    /** N03 — Quarterly prudential return (solvency margin, premium reserves, investment positions). */
    PRUDENTIAL_RETURN,
    /** N04 — RI quarterly returns (ceded premium and claims by treaty and reinsurer). */
    RI_QUARTERLY_RETURN,
    /** N05 — Premium bordereaux (policy-level premium register). */
    PREMIUM_BORDEREAUX,
    /** N06 — Claims bordereaux (claim-level loss register). */
    CLAIMS_BORDEREAUX,
    /** N07 — NIID upload status snapshot at period close. NIID-side, not NAICOM, but uses the same infrastructure. */
    NIID_STATUS_SNAPSHOT,
    /** N08 — Investment statement (investments by type, value, yield, % of total assets). */
    INVESTMENT_STATEMENT,
    /** IFRS 17 §103/104/105 disclosure pack (reads V38 paa_movement_analysis). */
    IFRS17_DISCLOSURE,
    /** IFRS 9 §B5.5.39 + IFRS 7 §35M disclosure pack (reads V40 ifrs9_investment_movement_analysis). */
    IFRS9_DISCLOSURE
}
