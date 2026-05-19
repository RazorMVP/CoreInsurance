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

import java.time.Instant;
import java.util.UUID;

/**
 * A rendered artifact (PDF / CSV / JSON / XML) for a {@link NaicomSubmission}.
 * Storage path points to a {@code DocumentStorageService} blob; the
 * artifact row carries the cryptographic checksum so callers can detect
 * tampering at the storage layer between rendering and submission upload.
 *
 * <p>Exactly one live artifact per {@code (submission_id, format)} pair
 * (V41 {@code uq_naicom_submission_artifact_format}). Re-rendering replaces
 * via soft-delete + insert so every rendering attempt survives in the
 * audit history.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "naicom_submission_artifact")
public class NaicomSubmissionArtifact extends BaseEntity {

    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 10, updatable = false)
    private ArtifactFormat format;

    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * SHA-256 of the rendered bytes, lowercase hex. Exactly 64 characters
     * (V41 {@code ck_naicom_submission_artifact_sha256_length}).
     */
    @Column(name = "sha256_hex", nullable = false, length = 64)
    private String sha256Hex;

    @Column(name = "rendered_at", nullable = false)
    private Instant renderedAt = Instant.now();

    @Column(name = "rendered_by", length = 100)
    private String renderedBy;
}
