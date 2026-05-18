package com.nubeero.cia.finance.paa;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Combined response from {@link PaaPeriodCloseService#closePeriod(UUID)} —
 * the LRC + LIC engine outputs alongside the §83 / §84 Insurance Service
 * Result for the period. Each engine result is null when the engine was
 * skipped because that engine had already run for the period (idempotent
 * orchestration).
 *
 * <p>Module 12 Phase 2 Slice 2.5.
 */
public record PaaPeriodCloseResult(

    UUID periodId,
    LocalDate periodStart,
    LocalDate periodEnd,
    /** Result of the LRC engine run, or null if it had already run for this period. */
    LrcRecognitionResult lrc,
    /** Result of the LIC engine run, or null if it had already run for this period. */
    LicRecognitionResult lic,
    /** §83/§84 disclosure view, always present (always re-derivable from paa_lrc + paa_lic). */
    InsuranceServiceResult insuranceServiceResult

) {}
