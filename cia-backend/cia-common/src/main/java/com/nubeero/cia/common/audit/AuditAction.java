package com.nubeero.cia.common.audit;

public enum AuditAction {
    CREATE, UPDATE, DELETE, APPROVE, REJECT, SUBMIT, SEND, CANCEL, REVERSE, EXECUTE,
    /**
     * Period close — written by {@code PeriodLockService} when a fiscal period
     * is moved from OPEN → SOFT_CLOSED or SOFT_CLOSED → HARD_CLOSED. The
     * {@code newValue} payload captures the {@code period_lock} row written.
     */
    CLOSE,
    /**
     * Period reopen — written by {@code PeriodLockService.reopen(...)} when an
     * existing HARD lock is released. NAICOM-grade evidence: every reopen of
     * a hard-closed period gets a dedicated audit row separate from the
     * surrounding UPDATEs.
     */
    REOPEN,
    /**
     * Period lock override — written by {@code PeriodLockInterceptor} when a
     * user holding {@code FINANCE_OVERRIDE_LOCK} writes past the soft-close
     * grace window. Required for the auditor sample test on post-close
     * activity.
     */
    LOCK_OVERRIDE
}
