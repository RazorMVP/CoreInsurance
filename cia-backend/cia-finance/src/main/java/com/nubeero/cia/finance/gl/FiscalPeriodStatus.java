package com.nubeero.cia.finance.gl;

/**
 * Lifecycle status of a fiscal period.
 *
 * <ul>
 *   <li>{@code OPEN} — accepting journal entries.</li>
 *   <li>{@code SOFT_CLOSED} — only adjustments via grace window allowed (Slice 1.7).</li>
 *   <li>{@code HARD_CLOSED} — terminal; no postings unless explicitly reopened.</li>
 *   <li>{@code REOPENED} — was hard-closed then reopened by an authorised user.</li>
 * </ul>
 *
 * <p>Slice 1.4 only treats {@code OPEN} (and, behind a service rule in Slice
 * 1.7, {@code SOFT_CLOSED} during the grace window) as postable. Hard-close
 * enforcement is centralised in {@code PeriodLockService} — this slice does
 * not gate on status itself.
 */
public enum FiscalPeriodStatus {
    OPEN,
    SOFT_CLOSED,
    HARD_CLOSED,
    REOPENED
}
