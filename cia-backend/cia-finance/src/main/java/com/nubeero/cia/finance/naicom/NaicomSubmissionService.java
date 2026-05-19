package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.finance.gl.FiscalPeriod;
import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.gl.FiscalPeriodRepository;
import com.nubeero.cia.finance.gl.FiscalPeriodStatus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrator for the NAICOM submission lifecycle — the single write-side
 * authority over {@code naicom_submission} + {@code naicom_submission_event}.
 *
 * <p>Module 12 Phase 4 Slice 4.9. Engines (Slices 4.2–4.8) are pure
 * payload generators; this service owns the row write, the state
 * machine, and the audit-trail emission. Slice 4.10 will layer artifact
 * rendering + Temporal upload retry on top of this.
 *
 * <h2>Engine dispatch</h2>
 * <p>The service injects {@code List<NaicomSubmissionEngine>} and indexes
 * by {@link NaicomSubmissionEngine#type()} at startup. {@link #generate}
 * routes by {@code submissionType} — adding a new submission type is a
 * new engine class + corresponding {@link NaicomSubmissionType} enum
 * value, never a switch-case edit.
 *
 * <h2>Preconditions</h2>
 * <ul>
 *   <li>Fiscal period exists and is not soft-deleted (else
 *       {@link FiscalPeriodNotFoundException}).</li>
 *   <li>Fiscal period status is {@code HARD_CLOSED} (else
 *       {@link PeriodNotHardClosedException}).</li>
 * </ul>
 *
 * <h2>State machine (V41 graph)</h2>
 * <pre>
 *   (initial) ──► DRAFT ─loop─► DRAFT          (re-generate while DRAFT)
 *                   │
 *                   ├──► SUBMITTED ──► ACKNOWLEDGED ──► ARCHIVED
 *                   │       │
 *                   │       └──► RETRACTED (terminal)
 * </pre>
 * <ul>
 *   <li>{@link #generate} — initial DRAFT or in-place DRAFT update.
 *       Rejected with {@link PayloadFrozenException} if the live row
 *       is past DRAFT.</li>
 *   <li>{@link #submit} — DRAFT → SUBMITTED.</li>
 *   <li>{@link #acknowledge} — SUBMITTED → ACKNOWLEDGED, with
 *       {@code naicom_uid}.</li>
 *   <li>{@link #retract} — SUBMITTED → RETRACTED (terminal).</li>
 *   <li>{@link #archive} — ACKNOWLEDGED → ARCHIVED.</li>
 * </ul>
 * <p>Every transition emits a {@link NaicomSubmissionEvent} with the
 * actor + reason captured from the caller. The event chain is the
 * auditor's record of the submission's state-machine path.
 *
 * <h2>Idempotency</h2>
 * <p>V41 enforces {@code UNIQUE(submission_type, period_id) WHERE
 * deleted_at IS NULL} — exactly one live submission per pair. The
 * service implements re-generation as "update the existing DRAFT in
 * place" rather than "delete + insert" so the original
 * {@code naicom_submission.id} is preserved across regen iterations
 * and the audit chain stays intact.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class NaicomSubmissionService {

    private final NaicomSubmissionRepository submissionRepository;
    private final NaicomSubmissionEventRepository eventRepository;
    private final FiscalPeriodRepository fiscalPeriodRepository;
    private final List<NaicomSubmissionEngine> engineBeans;

    /** Built at startup from {@link #engineBeans} — never mutated. */
    private final Map<NaicomSubmissionType, NaicomSubmissionEngine> engines =
        new EnumMap<>(NaicomSubmissionType.class);

    @PostConstruct
    void indexEngines() {
        for (NaicomSubmissionEngine engine : engineBeans) {
            NaicomSubmissionEngine prior = engines.put(engine.type(), engine);
            if (prior != null) {
                throw new IllegalStateException(
                    "Duplicate NaicomSubmissionEngine for type " + engine.type()
                    + ": " + prior.getClass().getSimpleName()
                    + " and " + engine.getClass().getSimpleName());
            }
        }
        log.info("NaicomSubmissionService indexed {} engines: {}",
            engines.size(), engines.keySet());
    }

    // ── Generation ─────────────────────────────────────────────────────

    /**
     * Generate (or re-generate, while still in DRAFT) a submission for
     * the given {@code (submissionType, periodId)}. Idempotent in the
     * DRAFT state — calling repeatedly updates {@code payload} in place
     * and appends a {@code DRAFT → DRAFT} event capturing the
     * regeneration {@code reason}.
     */
    public NaicomSubmission generate(NaicomSubmissionType submissionType,
                                      UUID periodId,
                                      String reason,
                                      String actor) {
        FiscalPeriod period = requirePeriod(periodId);
        requireHardClosed(period);
        NaicomSubmissionEngine engine = requireEngine(submissionType);

        Optional<NaicomSubmission> existing = submissionRepository
            .findBySubmissionTypeAndPeriodIdAndDeletedAtIsNull(submissionType, periodId);
        if (existing.isPresent() && existing.get().getState() != NaicomSubmissionState.DRAFT) {
            throw new PayloadFrozenException(existing.get().getId(), existing.get().getState());
        }

        Map<String, Object> payload = engine.computePayload(periodId);

        if (existing.isPresent()) {
            NaicomSubmission row = existing.get();
            // payload column is NOT NULL; use a defensive copy so the engine's
            // returned LinkedHashMap reference is not aliased back to the JPA-
            // managed instance (Hibernate JSON binding handles either, but
            // copying keeps the audit chain on the row clean).
            row.setPayload(new HashMap<>(payload));
            row.setNotes(reason);
            submissionRepository.save(row);
            recordEvent(row.getId(), NaicomSubmissionState.DRAFT,
                NaicomSubmissionState.DRAFT, reason, actor);
            log.info("Regenerated submission {} ({}, period={}) — actor={}",
                row.getId(), submissionType, periodId, actor);
            return row;
        }

        NaicomSubmission row = new NaicomSubmission();
        row.setSubmissionType(submissionType);
        row.setPeriodId(periodId);
        row.setPeriodStart(period.getStartDate());
        row.setPeriodEnd(period.getEndDate());
        row.setState(NaicomSubmissionState.DRAFT);
        row.setPayload(new HashMap<>(payload));
        row.setNotes(reason);
        NaicomSubmission saved = submissionRepository.save(row);
        recordEvent(saved.getId(), null, NaicomSubmissionState.DRAFT, reason, actor);
        log.info("Generated submission {} ({}, period={}) — actor={}",
            saved.getId(), submissionType, periodId, actor);
        return saved;
    }

    // ── Transitions ────────────────────────────────────────────────────

    /**
     * DRAFT → SUBMITTED. Sets {@code submittedAt} + {@code submittedBy}
     * (the V41 CK enforces both NOT NULL once state is SUBMITTED).
     */
    public NaicomSubmission submit(UUID submissionId, String reason, String actor) {
        NaicomSubmission row = requireSubmission(submissionId);
        requireState(row, NaicomSubmissionState.DRAFT, "submit");

        row.setState(NaicomSubmissionState.SUBMITTED);
        row.setSubmittedAt(Instant.now());
        row.setSubmittedBy(actor);
        submissionRepository.save(row);
        recordEvent(row.getId(), NaicomSubmissionState.DRAFT,
            NaicomSubmissionState.SUBMITTED, reason, actor);
        log.info("Submitted submission {} — actor={}", row.getId(), actor);
        return row;
    }

    /**
     * SUBMITTED → ACKNOWLEDGED. Records the NAICOM-side
     * {@code naicom_uid} (the V41 CK requires it NOT NULL once
     * ACKNOWLEDGED) plus {@code acknowledgedAt} + {@code acknowledgedBy}.
     */
    public NaicomSubmission acknowledge(UUID submissionId, String naicomUid, String actor) {
        if (naicomUid == null || naicomUid.isBlank()) {
            throw new IllegalArgumentException("naicomUid is required to acknowledge");
        }
        NaicomSubmission row = requireSubmission(submissionId);
        requireState(row, NaicomSubmissionState.SUBMITTED, "acknowledge");

        row.setState(NaicomSubmissionState.ACKNOWLEDGED);
        row.setAcknowledgedAt(Instant.now());
        row.setAcknowledgedBy(actor);
        row.setNaicomUid(naicomUid);
        submissionRepository.save(row);
        recordEvent(row.getId(), NaicomSubmissionState.SUBMITTED,
            NaicomSubmissionState.ACKNOWLEDGED,
            "naicom_uid=" + naicomUid, actor);
        log.info("Acknowledged submission {} with naicom_uid={} — actor={}",
            row.getId(), naicomUid, actor);
        return row;
    }

    /**
     * SUBMITTED → RETRACTED (terminal). The submitter retracts before
     * NAICOM acknowledges — a corrected fresh submission may then be
     * generated against the same {@code (type, period)}.
     */
    public NaicomSubmission retract(UUID submissionId, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required to retract");
        }
        NaicomSubmission row = requireSubmission(submissionId);
        requireState(row, NaicomSubmissionState.SUBMITTED, "retract");

        row.setState(NaicomSubmissionState.RETRACTED);
        row.setRetractedAt(Instant.now());
        row.setRetractedBy(actor);
        row.setRetractionReason(reason);
        // V41 partial UNIQUE (submission_type, period_id) WHERE deleted_at IS
        // NULL would block a fresh submission for the same (type, period).
        // Soft-delete the retracted row so it vacates the UNIQUE slot — its
        // event chain + payload survive for audit via the soft-delete.
        row.setDeletedAt(Instant.now());
        submissionRepository.save(row);
        recordEvent(row.getId(), NaicomSubmissionState.SUBMITTED,
            NaicomSubmissionState.RETRACTED, reason, actor);
        log.info("Retracted submission {} (reason='{}') — actor={}",
            row.getId(), reason, actor);
        return row;
    }

    /**
     * ACKNOWLEDGED → ARCHIVED. Periodic worker (Slice 4.10) calls this
     * once retention policy demands; the row stays for 7+ years per
     * NDPR but no longer counts against the live-submission UNIQUE
     * slot.
     */
    public NaicomSubmission archive(UUID submissionId, String actor) {
        NaicomSubmission row = requireSubmission(submissionId);
        requireState(row, NaicomSubmissionState.ACKNOWLEDGED, "archive");

        row.setState(NaicomSubmissionState.ARCHIVED);
        row.setArchivedAt(Instant.now());
        // Same partial-UNIQUE rationale as retract().
        row.setDeletedAt(Instant.now());
        submissionRepository.save(row);
        recordEvent(row.getId(), NaicomSubmissionState.ACKNOWLEDGED,
            NaicomSubmissionState.ARCHIVED, null, actor);
        log.info("Archived submission {} — actor={}", row.getId(), actor);
        return row;
    }

    // ── Read-side ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public NaicomSubmission findById(UUID submissionId) {
        return submissionRepository.findById(submissionId)
            .orElseThrow(() -> new NaicomSubmissionNotFoundException(submissionId));
    }

    @Transactional(readOnly = true)
    public List<NaicomSubmissionEvent> findEvents(UUID submissionId) {
        // Guard so a caller hitting /events/{id} for an unknown id gets 404,
        // not an empty list. findById accepts ARCHIVED/RETRACTED rows
        // because they may be soft-deleted but are still audit evidence.
        if (!submissionRepository.existsById(submissionId)) {
            throw new NaicomSubmissionNotFoundException(submissionId);
        }
        return eventRepository.findAllBySubmissionIdOrderByOccurredAtAsc(submissionId);
    }

    @Transactional(readOnly = true)
    public List<NaicomSubmission> findByPeriod(UUID periodId) {
        return submissionRepository.findAllByPeriodIdAndDeletedAtIsNull(periodId);
    }

    @Transactional(readOnly = true)
    public List<NaicomSubmission> findByState(NaicomSubmissionState state) {
        return submissionRepository.findAllByStateAndDeletedAtIsNull(state);
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private FiscalPeriod requirePeriod(UUID periodId) {
        return fiscalPeriodRepository.findById(periodId)
            .filter(p -> p.getDeletedAt() == null)
            .orElseThrow(() -> new FiscalPeriodNotFoundException(periodId));
    }

    private void requireHardClosed(FiscalPeriod period) {
        FiscalPeriodStatus status = period.getStatus();
        if (status != FiscalPeriodStatus.HARD_CLOSED) {
            throw new PeriodNotHardClosedException(period.getId(), status);
        }
    }

    private NaicomSubmissionEngine requireEngine(NaicomSubmissionType type) {
        NaicomSubmissionEngine engine = engines.get(type);
        if (engine == null) {
            throw new IllegalStateException(
                "No engine registered for submission type " + type
                + " — engines indexed: " + engines.keySet());
        }
        return engine;
    }

    private NaicomSubmission requireSubmission(UUID id) {
        // findByIdAndDeletedAtIsNull because transitions on already-
        // soft-deleted (ARCHIVED / RETRACTED) rows must be rejected with
        // a clear NOT_FOUND, not silently accepted as no-ops.
        return submissionRepository.findByIdAndDeletedAtIsNull(id)
            .orElseThrow(() -> new NaicomSubmissionNotFoundException(id));
    }

    private void requireState(NaicomSubmission row, NaicomSubmissionState required, String operation) {
        if (row.getState() != required) {
            throw new IllegalSubmissionStateException(row.getState(), required, operation);
        }
    }

    private void recordEvent(UUID submissionId,
                              NaicomSubmissionState from,
                              NaicomSubmissionState to,
                              String reason,
                              String actor) {
        NaicomSubmissionEvent event = new NaicomSubmissionEvent();
        event.setSubmissionId(submissionId);
        event.setFromState(from);
        event.setToState(to);
        event.setReason(reason);
        event.setActor(actor == null ? "system" : actor);
        event.setOccurredAt(Instant.now());
        eventRepository.save(event);
    }
}
