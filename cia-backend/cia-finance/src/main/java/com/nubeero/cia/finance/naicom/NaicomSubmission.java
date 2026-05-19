package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One regulator-bound submission row — a single (submission_type, period_id)
 * occurrence over the submission state machine. See {@link NaicomSubmissionState}
 * for the transition graph.
 *
 * <p>The {@link #payload} JSONB column stores the engine's structured
 * output. Phase 4 engines (slices 4.2–4.8) write into this column;
 * downstream rendering (slice 4.10) reads it. The shape is engine-defined;
 * the entity intentionally treats it as opaque {@code Map<String,Object>}
 * so the database schema does not have to evolve every time an engine's
 * output structure changes — same pattern as {@code JournalEntryLine.dimensionTags}.
 *
 * <p>Idempotency: there is at most one live row per
 * {@code (submission_type, period_id)} (V41
 * {@code uq_naicom_submission_type_period} partial unique index). Re-running
 * the source engine for the same key updates {@link #payload} in place
 * while still in DRAFT; once SUBMITTED, the payload is frozen.
 *
 * <p>Period-lock precondition: submission generation requires the
 * referenced fiscal period to be HARD_CLOSED. Enforced at the service
 * layer (slice 4.9), not by DB constraint — the DB cannot see the latest
 * {@code period_lock} row without a subquery that would obscure the
 * service-layer business rule.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "naicom_submission")
public class NaicomSubmission extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_type", nullable = false, length = 40, updatable = false)
    private NaicomSubmissionType submissionType;

    @Column(name = "period_id", nullable = false, updatable = false)
    private UUID periodId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private NaicomSubmissionState state = NaicomSubmissionState.DRAFT;

    /**
     * Structured engine output. Opaque to the entity layer; rendered by
     * slice 4.10 via Apache PDFBox + RFC 4180 CSV. NOT NULL at the DB level
     * — defaults to {@code '{}'::jsonb}; this {@link HashMap} default
     * keeps the NOT NULL constraint satisfied without callers having to
     * remember to initialise it.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "naicom_uid", length = 64)
    private String naicomUid;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "retracted_at")
    private Instant retractedAt;

    @Column(name = "retracted_by", length = 100)
    private String retractedBy;

    @Column(name = "retraction_reason", columnDefinition = "TEXT")
    private String retractionReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * Convenience predicate — true if the submission is in a state where
     * the payload may still be re-generated. Once {@code SUBMITTED} the
     * payload is frozen regardless of source-data changes.
     */
    public boolean isPayloadMutable() {
        return state == NaicomSubmissionState.DRAFT;
    }
}
