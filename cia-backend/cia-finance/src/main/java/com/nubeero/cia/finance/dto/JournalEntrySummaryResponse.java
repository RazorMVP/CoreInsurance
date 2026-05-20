package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.gl.JournalEntryStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lightweight read model for the {@code GET /api/v1/finance/journal-entries}
 * list endpoint. Excludes the line array to keep page payloads bounded —
 * callers drill into the detail via {@code GET /journal-entries/{id}} when
 * they need the lines.
 *
 * <p>{@code lineCount} and {@code totalDebit} are pre-aggregated by the
 * service so the browser table can render summary columns without a
 * follow-up call. {@code totalDebit == totalCredit} for every POSTED entry
 * (enforced by the gateway).
 *
 * @since Module 12, Slice 1.4 / Phase 5 frontend slice F5.4
 */
public record JournalEntrySummaryResponse(
    UUID id,
    LocalDate postingDate,
    LocalDate businessDate,
    UUID periodId,
    String sourceModule,
    String sourceEventType,
    String sourceReference,
    String narrative,
    String postedBy,
    JournalEntryStatus status,
    UUID reversalOf,
    boolean priorPeriodAdjustment,
    Instant createdAt,
    int lineCount,
    BigDecimal totalDebit
) {}
