package com.nubeero.cia.finance.gl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result envelope from {@link PeriodLockService#checkWrite}. Carries the
 * structured payload that ends up in the {@code PeriodLockedException}
 * response body — the frontend toast renders these fields directly without
 * a second API round-trip.
 *
 * @param outcome           ALLOW / REJECT / OVERRIDE
 * @param periodId          fiscal_period id the check resolved against
 *                          ({@code null} if no period encloses the lock date —
 *                          which is itself an outright REJECT)
 * @param periodLabel       human-readable label (e.g. "May 2026") for the
 *                          frontend toast
 * @param status            current {@link FiscalPeriodStatus} of the period
 * @param graceEndsAt       timestamp the grace window closes for SOFT locks,
 *                          {@code null} otherwise
 * @param overrideRoles     Keycloak roles that could grant a write (e.g.
 *                          {@code [FINANCE_OVERRIDE_LOCK]} for grace bypass)
 * @param reason            human-readable rejection reason
 *
 * @since Module 12, Slice 1.7
 */
public record LockDecision(
    LockOutcome outcome,
    UUID periodId,
    String periodLabel,
    FiscalPeriodStatus status,
    Instant graceEndsAt,
    List<String> overrideRoles,
    String reason
) {
    public static LockDecision allow() {
        return new LockDecision(LockOutcome.ALLOW, null, null, null, null, List.of(), null);
    }

    public static LockDecision allow(UUID periodId, String periodLabel, FiscalPeriodStatus status) {
        return new LockDecision(LockOutcome.ALLOW, periodId, periodLabel, status, null, List.of(), null);
    }

    public static LockDecision override(UUID periodId, String periodLabel, FiscalPeriodStatus status,
                                        Instant graceEndsAt) {
        return new LockDecision(LockOutcome.OVERRIDE, periodId, periodLabel, status, graceEndsAt,
            List.of("FINANCE_OVERRIDE_LOCK"), null);
    }

    public static LockDecision reject(UUID periodId, String periodLabel, FiscalPeriodStatus status,
                                      Instant graceEndsAt, List<String> overrideRoles, String reason) {
        return new LockDecision(LockOutcome.REJECT, periodId, periodLabel, status, graceEndsAt,
            overrideRoles, reason);
    }
}
