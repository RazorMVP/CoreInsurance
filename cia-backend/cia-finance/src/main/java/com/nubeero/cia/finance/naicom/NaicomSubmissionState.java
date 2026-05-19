package com.nubeero.cia.finance.naicom;

/**
 * State machine for a {@link NaicomSubmission}.
 *
 * <pre>
 *      (none) ──► DRAFT ──► SUBMITTED ──► ACKNOWLEDGED ──► ARCHIVED
 *                   ▲          │
 *                   └──────────┘   (re-generate while DRAFT)
 *                              │
 *                              └──► RETRACTED  (terminal)
 * </pre>
 *
 * <p>Transition rules (enforced by {@code NaicomSubmissionService} in Slice
 * 4.9; static field-presence invariants enforced by V41 CHECK constraints):
 * <ul>
 *   <li>{@code (initial) → DRAFT} — created by an engine on first generation.</li>
 *   <li>{@code DRAFT → DRAFT} — re-generation while still in DRAFT updates
 *       {@code payload}; emits a {@link NaicomSubmissionEvent} carrying the
 *       re-generation {@code reason}. This is the only same-state
 *       transition the DB will accept (see V41
 *       {@code ck_naicom_submission_event_no_op_only_draft}).</li>
 *   <li>{@code DRAFT → SUBMITTED} — CFO / regulatory officer submits the pack.
 *       Requires {@code submitted_at} + {@code submitted_by} (V41 CK).</li>
 *   <li>{@code SUBMITTED → ACKNOWLEDGED} — NAICOM acknowledges receipt;
 *       {@code naicom_uid} populated. Requires {@code acknowledged_at} +
 *       {@code naicom_uid} (V41 CK).</li>
 *   <li>{@code ACKNOWLEDGED → ARCHIVED} — period-archive worker moves
 *       acknowledged submissions to ARCHIVED; required for the 7-year
 *       retention floor without consuming the live-submission UNIQUE slot.</li>
 *   <li>{@code SUBMITTED → RETRACTED} — submitter retracts before NAICOM
 *       acknowledges. Terminal. Requires {@code retracted_at} +
 *       {@code retracted_by} (V41 CK).</li>
 *   <li>{@code ARCHIVED} and {@code RETRACTED} are terminal states.</li>
 * </ul>
 *
 * <p>Note that once {@code SUBMITTED}, the payload is frozen — re-running
 * the source engine for the same {@code (submission_type, period_id)} is
 * rejected at the service layer. Auditors require this guarantee.
 */
public enum NaicomSubmissionState {
    DRAFT,
    SUBMITTED,
    ACKNOWLEDGED,
    ARCHIVED,
    RETRACTED
}
