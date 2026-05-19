package com.nubeero.cia.finance.gl;

/**
 * Tri-state result of a period-lock check returned from
 * {@link PeriodLockService#checkWrite(com.nubeero.cia.common.entity.LockableByPeriod)}.
 *
 * @since Module 12, Slice 1.7
 */
public enum LockOutcome {
    /**
     * No lock applies, or a lock applies but the entity's lock date is
     * within the OPEN/grace path — proceed with the write.
     */
    ALLOW,
    /**
     * Write must be blocked. The interceptor raises {@link
     * PeriodLockedException} carrying a {@link LockDecision} with the
     * structured rejection payload (period label, status, grace end,
     * override roles).
     */
    REJECT,
    /**
     * Write proceeds because the caller holds an override role. The
     * interceptor logs a {@link com.nubeero.cia.common.audit.AuditAction#LOCK_OVERRIDE}
     * audit row before returning. Distinct from ALLOW so the audit trail
     * captures every grace-window bypass.
     */
    OVERRIDE
}
