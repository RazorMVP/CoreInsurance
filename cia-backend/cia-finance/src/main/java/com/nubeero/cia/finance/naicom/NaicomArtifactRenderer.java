package com.nubeero.cia.finance.naicom;

import java.util.Map;

/**
 * Renders a {@link NaicomSubmission}'s structured payload into a binary
 * artifact (PDF / CSV / JSON / XML). The {@link SubmissionArtifactService}
 * orchestrator picks the right renderer by format and pipes the bytes
 * into {@code DocumentStorageService}.
 *
 * <p>Module 12 Phase 4 Slice 4.10. Renderers must be deterministic for a
 * given payload — the rendered bytes are hashed (SHA-256) and persisted
 * into {@code naicom_submission_artifact.sha256_hex} as tamper evidence,
 * so two runs over the same payload must produce identical bytes.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #format()} is stable per implementation — the dispatch key.</li>
 *   <li>{@link #mimeType()} is the canonical Content-Type for downloads.</li>
 *   <li>{@link #fileExtension()} (without dot) is appended to the
 *       generated filename when streamed back to a download client.</li>
 *   <li>{@link #render(NaicomSubmission)} returns deterministic bytes.
 *       Implementations must avoid {@code Instant.now()} / system clocks
 *       inside the rendered output — the only timestamp source is the
 *       submission's own fields (period dates, submittedAt, etc.) which
 *       are stable on the entity.</li>
 * </ul>
 */
public interface NaicomArtifactRenderer {

    ArtifactFormat format();

    String mimeType();

    /** File extension without leading dot, e.g. {@code "pdf"}. */
    String fileExtension();

    /**
     * Render the submission's payload to bytes. Must be deterministic for
     * the same {@link NaicomSubmission#getPayload() payload} — the SHA-256
     * checksum stored in {@code naicom_submission_artifact} relies on it.
     */
    byte[] render(NaicomSubmission submission);

    /**
     * Convenience accessor for renderer implementations — every payload
     * the engines emit puts {@code submissionType} as the first key, but
     * implementations should defensively read it through this helper so a
     * malformed payload doesn't NPE the renderer.
     */
    static String submissionTypeLabel(NaicomSubmission submission) {
        Map<String, Object> payload = submission.getPayload();
        Object label = payload == null ? null : payload.get("submissionType");
        return label == null
            ? submission.getSubmissionType().name()
            : label.toString();
    }
}
