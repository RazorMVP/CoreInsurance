package com.nubeero.cia.finance.gl;

/**
 * Lock kind stored on a {@link PeriodLock} row.
 *
 * <p>Names match the V31 {@code period_lock.lock_type} CHECK constraint
 * (SOFT / HARD) — <strong>not</strong> the longer {@code FiscalPeriodStatus}
 * names. The two enums intentionally diverge: {@code FiscalPeriodStatus} is
 * the period's current-state cache (OPEN / SOFT_CLOSED / HARD_CLOSED /
 * REOPENED), while this enum records what kind of close action wrote a
 * given audit row.
 *
 * @since Module 12, Slice 1.7
 */
public enum LockType {
    /**
     * Soft close — writes against periods locked SOFT are accepted while
     * within the per-lock {@code grace_window_until}, and beyond the window
     * accepted only for users holding {@code FINANCE_OVERRIDE_LOCK}. Every
     * override produces an audit entry with action {@code LOCK_OVERRIDE}.
     */
    SOFT,
    /**
     * Hard close — terminal. Writes are unconditionally rejected unless the
     * lock is first {@code reopen}ed (which releases the HARD row by setting
     * its {@code released_at}). Reopening a hard-closed period requires
     * {@code FINANCE_REOPEN_PERIOD} and emits a {@code PeriodReopenedEvent}
     * that notifies the configured CFO + compliance distribution.
     */
    HARD
}
