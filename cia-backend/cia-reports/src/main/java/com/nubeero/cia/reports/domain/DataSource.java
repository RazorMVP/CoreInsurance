package com.nubeero.cia.reports.domain;

public enum DataSource {
    POLICIES,
    CLAIMS,
    FINANCE,
    REINSURANCE,
    CUSTOMERS,
    ENDORSEMENTS,

    // ── Module 12 — Period-End Closures (CLOSURES category) ───────────────────
    // GL Foundation (V31)
    TRIAL_BALANCE,
    GENERAL_LEDGER,
    GL_PERIOD_LOCK,
    // IFRS 17 PAA (V36, V38)
    PAA_LRC,
    PAA_GROUPS,
    IFRS17_MOVEMENT,
    // IFRS 9 (V39, V40)
    IFRS9_HOLDINGS,
    IFRS9_CARRYING,
    IFRS9_MOVEMENT
}
