package com.nubeero.cia.finance.naicom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only state-transition history for a {@link NaicomSubmission}.
 * The row sequence per submission IS the audit trail — mirrors the
 * {@code period_lock}-as-Type-2-SCD pattern from V31 (no separate
 * history table).
 *
 * <p>Does NOT extend {@code BaseEntity} because:
 * <ul>
 *   <li>Events are immutable — no {@code updated_at}, no soft delete.</li>
 *   <li>The {@code occurred_at} business timestamp is more meaningful
 *       than a generic {@code created_at} for auditors traversing the
 *       chain.</li>
 *   <li>Auditing-listener side-effects (CreatedBy / LastModifiedDate /
 *       LastModifiedBy from {@code AuditingEntityListener}) would record
 *       the wrong actor for these rows — the {@code actor} field is
 *       set explicitly by {@code NaicomSubmissionService} (slice 4.9)
 *       from the request-time authenticated principal.</li>
 * </ul>
 *
 * <p>The {@link #fromState} field is null only for the initial
 * DRAFT-creation event; all other transitions populate both endpoints.
 * V41 {@code ck_naicom_submission_event_no_op_only_draft} permits only
 * the {@code DRAFT → DRAFT} same-state transition (re-generation while
 * still in DRAFT).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "naicom_submission_event")
public class NaicomSubmissionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 20, updatable = false)
    private NaicomSubmissionState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 20, updatable = false)
    private NaicomSubmissionState toState;

    @Column(name = "reason", columnDefinition = "TEXT", updatable = false)
    private String reason;

    @Column(name = "actor", nullable = false, length = 100, updatable = false)
    private String actor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();
}
