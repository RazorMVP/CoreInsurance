package com.nubeero.cia.finance.gl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day's row in a {@link PeriodLockService#previewLock} report. Lets
 * bulk-operation callers (Module 8 bulk receipts, Slice 1.8 retroactive
 * backfill, Module 6 bordereaux generation) discover up-front which dates
 * in their working set would land in a HARD-closed period (outright reject)
 * versus a SOFT-closed past-grace period (rejected unless caller holds
 * {@code FINANCE_OVERRIDE_LOCK}).
 *
 * @param date              the business date the row reports on
 * @param periodId          {@code null} if no fiscal period covers the date
 * @param periodLabel       "May 2026" / "Q2 2026" / "(no period)"
 * @param status            current {@link FiscalPeriodStatus}, or {@code null}
 *                          if there's no enclosing period
 * @param graceWindowUntil  end of the SOFT-close grace window if applicable;
 *                          {@code null} otherwise
 * @param requiresOverride  {@code true} when SOFT-close grace has elapsed —
 *                          the caller needs {@code FINANCE_OVERRIDE_LOCK} to
 *                          write on this date
 * @param rejected          {@code true} when no override path exists — HARD
 *                          close or no enclosing period
 *
 * @since Module 12, Slice 1.7
 */
public record LockReportEntry(
    LocalDate date,
    UUID periodId,
    String periodLabel,
    FiscalPeriodStatus status,
    Instant graceWindowUntil,
    boolean requiresOverride,
    boolean rejected
) {}
