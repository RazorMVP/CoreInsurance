package com.nubeero.cia.finance.naicom;

import java.util.Map;
import java.util.UUID;

/**
 * Common contract every NAICOM submission engine implements so the
 * {@link NaicomSubmissionService} orchestrator can dispatch by submission
 * type without coupling to concrete engine classes.
 *
 * <p>Module 12 Phase 4 Slice 4.9 — introduces the dispatch interface.
 * Each of the 10 Phase 4 engines (Slices 4.2–4.8) implements this
 * interface; the orchestrator auto-discovers them via Spring's
 * {@code List<NaicomSubmissionEngine>} injection and indexes by
 * {@link #type()} at construction.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #type()} is stable per implementation — it's the static
 *       routing key. Two engines must never report the same type.</li>
 *   <li>{@link #computePayload(UUID)} is a pure read — no DB writes, no
 *       JE postings, no side effects. The orchestrator owns the
 *       {@code naicom_submission} row write + state machine transitions.</li>
 *   <li>Implementations may throw any RuntimeException for missing /
 *       deleted fiscal periods; the orchestrator surfaces those as
 *       404s via {@code FiscalPeriodNotFoundException}'s
 *       {@code @ResponseStatus}.</li>
 * </ul>
 */
public interface NaicomSubmissionEngine {

    /**
     * The submission type this engine generates. Used as the dispatch
     * key by {@link NaicomSubmissionService}.
     */
    NaicomSubmissionType type();

    /**
     * Generate the structured payload for the given fiscal period.
     * Pure read — orchestrator persists the result into
     * {@code naicom_submission.payload}.
     */
    Map<String, Object> computePayload(UUID periodId);
}
