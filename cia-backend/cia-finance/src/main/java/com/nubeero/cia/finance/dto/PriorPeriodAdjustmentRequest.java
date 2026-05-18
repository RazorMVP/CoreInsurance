package com.nubeero.cia.finance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Wire contract for {@code POST /api/v1/finance/journal-entries/prior-period-adjustment}
 * — Slice 1.7c.
 *
 * <p>The IAS-8 prior-period-adjustment path. Audit-found errors in closed
 * periods land here as adjustments dated in the OPEN period — they never
 * force a reopen of the closed period, which would invalidate the audit
 * sign-off for that period.
 *
 * <p>Differences from a normal {@link PostJournalEntryRequest}:
 * <ul>
 *   <li>{@code businessDate} is NOT supplied — the service stamps it as
 *       today's date so the PPA lands in the currently-open period.</li>
 *   <li>{@code reason} is mandatory — the IAS-8-style disclosure narrative
 *       audit reports surface. NOT NULL at the application layer; the V35
 *       column is nullable only because legitimate non-PPA rows omit it.</li>
 *   <li>{@code sourceModule} / {@code sourceEventType} are not configurable
 *       — the service forces them to {@code finance} /
 *       {@code PRIOR_PERIOD_ADJUSTMENT} so every PPA is filterable by the
 *       idempotency triple.</li>
 *   <li>Endpoint is gated by {@code FINANCE_APPROVE_PPA}, distinct from the
 *       {@code FINANCE_CREATE} required for normal posts — segregation of
 *       duties (the same officer who books a JE cannot approve its
 *       restatement).</li>
 * </ul>
 */
public record PriorPeriodAdjustmentRequest(
    @NotBlank
    @Size(max = 100)
    String sourceReference,

    @NotBlank
    @Size(max = 4000)
    String reason,

    String narrative,

    @NotEmpty
    @Size(min = 2)
    @Valid
    List<JournalEntryLineRequest> lines
) {}
