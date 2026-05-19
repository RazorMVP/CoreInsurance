package com.nubeero.cia.finance.naicom;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.storage.DocumentStorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates submission-artifact rendering, storage upload, metadata
 * persistence, and download streaming.
 *
 * <p>Module 12 Phase 4 Slice 4.10. Pipes the engine-generated payload
 * through a {@link NaicomArtifactRenderer}, hashes the rendered bytes
 * (SHA-256), uploads them through {@link DocumentStorageService}, and
 * writes a {@link NaicomSubmissionArtifact} row carrying the storage
 * path + checksum + size as tamper evidence.
 *
 * <h2>Renderer dispatch</h2>
 * <p>The service injects {@code List<NaicomArtifactRenderer>} and
 * indexes by {@link NaicomArtifactRenderer#format()} at startup —
 * mirrors Slice 4.9's {@link NaicomSubmissionService} engine-dispatch
 * pattern. Adding a new format (XML, e.g.) is a new renderer class,
 * not a switch edit.
 *
 * <h2>Idempotency at the (submission, format) grain</h2>
 * <p>V41's {@code uq_naicom_submission_artifact_format} guarantees at
 * most one live artifact per pair. Re-rendering soft-deletes the
 * existing live row and inserts a fresh one — every rendering attempt
 * survives in the table as audit evidence, while the partial UNIQUE
 * keeps the lookup unambiguous.
 *
 * <h2>Storage path convention</h2>
 * <pre>
 *   naicom-submissions/{submission_id}/{format}/{submission_type}-{period_end}.{ext}
 * </pre>
 * <p>Tenancy is supplied to {@link DocumentStorageService} via
 * {@link TenantContext#getTenantId()} — the same convention every
 * cia-finance / cia-policy / cia-claims storage consumer follows.
 */
@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class SubmissionArtifactService {

    private final NaicomSubmissionRepository submissionRepository;
    private final NaicomSubmissionArtifactRepository artifactRepository;
    private final DocumentStorageService documentStorageService;
    private final List<NaicomArtifactRenderer> rendererBeans;

    private final Map<ArtifactFormat, NaicomArtifactRenderer> renderers =
        new EnumMap<>(ArtifactFormat.class);

    @PostConstruct
    void indexRenderers() {
        for (NaicomArtifactRenderer r : rendererBeans) {
            NaicomArtifactRenderer prior = renderers.put(r.format(), r);
            if (prior != null) {
                throw new IllegalStateException(
                    "Duplicate NaicomArtifactRenderer for format " + r.format()
                    + ": " + prior.getClass().getSimpleName()
                    + " and " + r.getClass().getSimpleName());
            }
        }
        log.info("SubmissionArtifactService indexed {} renderers: {}",
            renderers.size(), renderers.keySet());
    }

    // ── Write side ─────────────────────────────────────────────────────

    /**
     * Render an artifact for the given submission + format. Returns the
     * persisted {@link NaicomSubmissionArtifact} row. If a live artifact
     * already exists for the same (submission, format) it is soft-
     * deleted before the fresh row is inserted; the partial UNIQUE
     * (V41 {@code uq_naicom_submission_artifact_format}) is honoured.
     *
     * <p>Caller passes {@code actor} for the audit trail. Tenancy comes
     * from {@link TenantContext}.
     */
    public NaicomSubmissionArtifact render(UUID submissionId,
                                            ArtifactFormat format,
                                            String actor) {
        NaicomSubmission submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new NaicomSubmissionNotFoundException(submissionId));

        NaicomArtifactRenderer renderer = renderers.get(format);
        if (renderer == null) {
            throw new IllegalStateException(
                "No renderer registered for artifact format " + format
                + " — registered: " + renderers.keySet());
        }

        byte[] bytes = renderer.render(submission);
        String sha = sha256Hex(bytes);
        String path = storagePath(submission, renderer);

        try (InputStream in = new ByteArrayInputStream(bytes)) {
            documentStorageService.upload(
                TenantContext.getTenantId(),
                path,
                in,
                renderer.mimeType());
        } catch (java.io.IOException e) {
            // ByteArrayInputStream.close() doesn't throw — defensive only.
            throw new IllegalStateException("Failed to close upload stream", e);
        }

        // Soft-delete any existing live artifact for the same (submission, format).
        // saveAndFlush is load-bearing: the partial UNIQUE
        // uq_naicom_submission_artifact_format only excludes deleted_at IS
        // NOT NULL rows, so the soft-delete UPDATE must hit the DB BEFORE
        // the new INSERT. Without the explicit flush Hibernate batches
        // both writes and Postgres sees two live rows during the INSERT,
        // failing the UNIQUE.
        Optional<NaicomSubmissionArtifact> existing = artifactRepository
            .findBySubmissionIdAndFormatAndDeletedAtIsNull(submissionId, format);
        existing.ifPresent(prior -> {
            prior.setDeletedAt(Instant.now());
            artifactRepository.saveAndFlush(prior);
        });

        NaicomSubmissionArtifact artifact = new NaicomSubmissionArtifact();
        artifact.setSubmissionId(submissionId);
        artifact.setFormat(format);
        artifact.setStoragePath(path);
        artifact.setSizeBytes(bytes.length);
        artifact.setSha256Hex(sha);
        artifact.setRenderedAt(Instant.now());
        artifact.setRenderedBy(actor);
        NaicomSubmissionArtifact saved = artifactRepository.save(artifact);

        log.info("Rendered {} artifact for submission {} — {} bytes, sha256={}, path={}",
            format, submissionId, bytes.length, sha, path);
        return saved;
    }

    // ── Read side ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<NaicomSubmissionArtifact> findBySubmission(UUID submissionId) {
        return artifactRepository.findAllBySubmissionIdAndDeletedAtIsNull(submissionId);
    }

    @Transactional(readOnly = true)
    public NaicomSubmissionArtifact getLive(UUID submissionId, ArtifactFormat format) {
        return artifactRepository
            .findBySubmissionIdAndFormatAndDeletedAtIsNull(submissionId, format)
            .orElseThrow(() -> new ArtifactNotFoundException(submissionId, format));
    }

    /**
     * Open a stream over the live artifact's bytes for download. The
     * caller (controller) owns closing.
     */
    @Transactional(readOnly = true)
    public ArtifactDownload openDownload(UUID submissionId, ArtifactFormat format) {
        NaicomSubmissionArtifact artifact = getLive(submissionId, format);
        InputStream stream = documentStorageService.download(
            TenantContext.getTenantId(), artifact.getStoragePath());

        NaicomArtifactRenderer renderer = renderers.get(format);
        String mimeType = renderer == null ? "application/octet-stream" : renderer.mimeType();
        String extension = renderer == null ? format.name().toLowerCase() : renderer.fileExtension();

        return new ArtifactDownload(
            stream,
            mimeType,
            artifact.getSizeBytes(),
            downloadFilename(artifact, extension));
    }

    // ── Internal helpers ───────────────────────────────────────────────

    private static String storagePath(NaicomSubmission s, NaicomArtifactRenderer r) {
        return "naicom-submissions/"
            + s.getId() + "/"
            + r.format().name().toLowerCase() + "/"
            + s.getSubmissionType().name().toLowerCase() + "-"
            + s.getPeriodEnd() + "."
            + r.fileExtension();
    }

    private static String downloadFilename(NaicomSubmissionArtifact a, String extension) {
        return "naicom-" + a.getSubmissionId() + "-"
            + a.getFormat().name().toLowerCase() + "."
            + extension;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec — never absent.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Streamed-download wire DTO. */
    public record ArtifactDownload(
        InputStream stream,
        String mimeType,
        long sizeBytes,
        String filename
    ) {}
}
