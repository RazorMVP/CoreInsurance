package com.nubeero.cia.finance.gl;

/**
 * IFRS 17 measurement role tags assigned to specific chart-of-accounts leaves.
 * <p>
 * Each constant must match a role string seeded in
 * {@code V32__seed_chart_of_accounts.sql}; the V32 test asserts that every
 * fixture row's {@code ifrs17_role} resolves to one of these names. Adding a
 * new role is therefore a two-part PR: a Flyway migration adding the role to
 * any account that needs it, plus the enum constant addition here.
 * <p>
 * The role column itself is intentionally a free-text VARCHAR(50) in V31 — the
 * vocabulary is locked here in Java rather than at the database boundary, so
 * that adding a measurement edge case during Phase 2 / Phase 3 work does not
 * require a database CHECK constraint migration. Posting rule lookups (slices
 * 2.x / 3.x) consume this enum directly.
 */
public enum Ifrs17Role {

    // ── LRC (Liability for Remaining Coverage) ──────────────────────────────
    LRC_BEL,
    LRC_RA,
    LRC_LC,

    // ── LIC (Liability for Incurred Claims) ─────────────────────────────────
    LIC_OCR,
    LIC_IBNR,
    LIC_RA,
    LIC_CHE,

    // ── Reinsurance contract held (Asset side) ──────────────────────────────
    LRC_REINSURANCE,
    LIC_REINSURANCE,

    // ── Insurance revenue ───────────────────────────────────────────────────
    REVENUE_LRC_RELEASE,
    REVENUE_ACQ_RECOVERY,
    REVENUE_RA_RELEASE,
    REVENUE_EXP_ADJ,

    // ── Reinsurance income (ceded) ──────────────────────────────────────────
    REINSURANCE_RECOVERY,

    // ── Insurance service expense ───────────────────────────────────────────
    INCURRED_CLAIMS,
    LIC_CHANGE,
    ACQ_EXPENSE,
    OTHER_DIRECT_EXPENSE,
    LC_CHANGE,

    // ── Reinsurance expense (outward) ───────────────────────────────────────
    REINSURANCE_PREMIUM,
    REINSURANCE_LRC_CHANGE,

    // ── Finance OCI option / insurance finance expense ──────────────────────
    INSURANCE_FINANCE_EXPENSE,
    INSURANCE_FINANCE_OCI
}
