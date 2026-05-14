package com.nubeero.cia.finance.dto;

import com.nubeero.cia.finance.gl.JournalEntryStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read model for a posted journal entry, including all of its lines. Used
 * by both the {@code GET /journal-entries/{id}} endpoint and as the
 * response to {@code POST}/{@code reverse}. {@code reversalOf} is non-null
 * only on reversal entries (the original points the other way via
 * {@code status=REVERSED}).
 */
public record JournalEntryResponse(
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
    Instant createdAt,
    List<JournalEntryLineResponse> lines
) {}
