package com.nubeero.cia.finance.gl;

/**
 * IFRS 9 classification and measurement role tags assigned to specific
 * chart-of-accounts leaves.
 * <p>
 * Each constant must match a role string seeded in
 * {@code V32__seed_chart_of_accounts.sql}; the V32 test asserts that every
 * fixture row's {@code ifrs9_role} resolves to one of these names. Adding a
 * new role is therefore a two-part PR: a Flyway migration adding the role to
 * any account that needs it, plus the enum constant addition here.
 * <p>
 * Phase 3 (investments) consumes this enum directly when classifying holdings
 * and posting ECL / fair-value movements.
 */
public enum Ifrs9Role {

    // ── Classification buckets ──────────────────────────────────────────────
    FVPL,
    FVOCI_DEBT,
    FVOCI_EQUITY,
    AMORTISED_COST,

    // ── Expected Credit Loss ────────────────────────────────────────────────
    ECL_ALLOWANCE,
    ECL_EXPENSE,

    // ── OCI reserves ────────────────────────────────────────────────────────
    OCI_DEBT_RESERVE,
    OCI_EQUITY_RESERVE,

    // ── Interest income (effective interest method) ─────────────────────────
    INTEREST_AC,
    INTEREST_FVOCI,

    // ── Fair value movements ────────────────────────────────────────────────
    FVPL_GAINS,
    FVPL_LOSSES
}
