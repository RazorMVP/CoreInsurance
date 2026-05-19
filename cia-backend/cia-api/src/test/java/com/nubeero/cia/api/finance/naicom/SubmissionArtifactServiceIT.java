package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.finance.naicom.ArtifactFormat;
import com.nubeero.cia.finance.naicom.ArtifactNotFoundException;
import com.nubeero.cia.finance.naicom.CsvArtifactRenderer;
import com.nubeero.cia.finance.naicom.JsonArtifactRenderer;
import com.nubeero.cia.finance.naicom.NaicomSubmission;
import com.nubeero.cia.finance.naicom.NaicomSubmissionArtifact;
import com.nubeero.cia.finance.naicom.NaicomSubmissionEngine;
import com.nubeero.cia.finance.naicom.NaicomSubmissionService;
import com.nubeero.cia.finance.naicom.NaicomSubmissionState;
import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import com.nubeero.cia.finance.naicom.NiidStatusSnapshotEngine;
import com.nubeero.cia.finance.naicom.PdfArtifactRenderer;
import com.nubeero.cia.finance.naicom.SubmissionArtifactService;
import com.nubeero.cia.storage.DocumentStorageService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 4.10 IT for {@link SubmissionArtifactService} — exercises render,
 * SHA-256 checksum, storage, listing, idempotent re-render (soft-delete
 * old + insert new), and download streaming.
 *
 * <p>Uses an in-memory {@link DocumentStorageService} provided via
 * {@code @TestConfiguration} so the IT runs without MinIO. The contract
 * with the production MinIO / S3 / GCS adapters is identical at the
 * {@code DocumentStorageService} boundary.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    NaicomSubmissionService.class,
    SubmissionArtifactService.class,
    NiidStatusSnapshotEngine.class,
    JsonArtifactRenderer.class,
    CsvArtifactRenderer.class,
    PdfArtifactRenderer.class,
    SubmissionArtifactServiceIT.InMemoryStorageConfig.class
})
class SubmissionArtifactServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciatest")
            .withUsername("ciatest")
            .withPassword("ciatest");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "43");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private SubmissionArtifactService artifactService;
    @Autowired private NaicomSubmissionService submissionService;
    @Autowired private InMemoryStorage storage;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager em;
    @Autowired private List<NaicomSubmissionEngine> wiredEngines;

    private UUID hardClosedPeriodId;
    private static final java.time.LocalDate PERIOD_START = java.time.LocalDate.of(2026, 1, 1);
    private static final java.time.LocalDate PERIOD_END = java.time.LocalDate.of(2026, 3, 31);
    private static final String TENANT = "tenant-test";

    @BeforeEach
    void seed() {
        TenantContext.setTenantId(TENANT);
        storage.reset();
        jdbcTemplate.update("DELETE FROM naicom_submission_event");
        jdbcTemplate.update("DELETE FROM naicom_submission_artifact");
        jdbcTemplate.update("DELETE FROM naicom_submission");
        jdbcTemplate.update("DELETE FROM policies");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-N10-2026",
            java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31),
            "ACTIVE", "test");
        hardClosedPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            hardClosedPeriodId, fyId, "QUARTER", PERIOD_START, PERIOD_END,
            "HARD_CLOSED", "test");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("three renderers indexed; cover JSON + CSV + PDF formats")
    void renderersIndexed() {
        // Just an existence check via dispatch — render() succeeds for all three.
        NaicomSubmission s = generateDraft();
        for (ArtifactFormat f : List.of(ArtifactFormat.JSON, ArtifactFormat.CSV, ArtifactFormat.PDF)) {
            NaicomSubmissionArtifact a = artifactService.render(s.getId(), f, "alice");
            assertThat(a.getFormat()).isEqualTo(f);
            assertThat(a.getSizeBytes()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("JSON render — bytes uploaded, sha256 + size + path persisted")
    void renderJson() {
        NaicomSubmission s = generateDraft();
        NaicomSubmissionArtifact a = artifactService.render(s.getId(), ArtifactFormat.JSON, "alice");
        em.flush();

        // Storage call sequenced correctly: same path the artifact row claims.
        byte[] uploaded = storage.read(TENANT, a.getStoragePath());
        assertThat(uploaded).isNotNull();
        assertThat((long) uploaded.length).isEqualTo(a.getSizeBytes());

        // SHA-256 hex check — 64 lowercase hex chars matching the V41 CHECK.
        assertThat(a.getSha256Hex())
            .matches("^[0-9a-f]{64}$")
            .isEqualTo(sha256(uploaded));

        // The JSON content includes the engine's payload "submissionType".
        String json = new String(uploaded, StandardCharsets.UTF_8);
        assertThat(json).contains("\"submissionType\"")
            .contains(NaicomSubmissionType.NIID_STATUS_SNAPSHOT.name());

        // Path convention check.
        assertThat(a.getStoragePath())
            .startsWith("naicom-submissions/")
            .contains(s.getId().toString())
            .endsWith(".json");
    }

    @Test
    @DisplayName("CSV render — RFC 4180 sections + UTF-8 BOM + Excel-friendly")
    void renderCsv() {
        NaicomSubmission s = generateDraft();
        NaicomSubmissionArtifact a = artifactService.render(s.getId(), ArtifactFormat.CSV, "alice");
        em.flush();

        byte[] bytes = storage.read(TENANT, a.getStoragePath());
        String csv = new String(bytes, StandardCharsets.UTF_8);

        // Excel BOM
        assertThat(csv.charAt(0)).isEqualTo('﻿');
        // Header comment lines
        assertThat(csv).contains("# NAICOM Submission");
        assertThat(csv).contains("# Period: " + PERIOD_START);
        // Top-level scalar section
        assertThat(csv).contains("[SECTION: top-level]");
        assertThat(csv).contains("submissionType,NIID_STATUS_SNAPSHOT");
        // Engine emits byClassOfBusiness + pending lists; expect at least one section
        // for an empty-period payload (lists exist as empty), even if list is empty.
        assertThat(csv).contains("[SECTION: byClassOfBusiness]")
            .as("byClassOfBusiness section appears even when payload list is empty");
        assertThat(csv).contains("[SECTION: pending]");
    }

    @Test
    @DisplayName("PDF render — valid PDF document, non-empty bytes")
    void renderPdf() {
        NaicomSubmission s = generateDraft();
        NaicomSubmissionArtifact a = artifactService.render(s.getId(), ArtifactFormat.PDF, "alice");
        em.flush();

        byte[] bytes = storage.read(TENANT, a.getStoragePath());
        // %PDF-x.y header is mandatory at byte 0.
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII))
            .as("PDF magic header present")
            .isEqualTo("%PDF-");
        assertThat(bytes.length).isGreaterThan(500);
    }

    @Test
    @DisplayName("re-render same (submission, format) soft-deletes prior + inserts new row")
    void rerenderIsIdempotent() {
        NaicomSubmission s = generateDraft();
        NaicomSubmissionArtifact first = artifactService.render(s.getId(), ArtifactFormat.JSON, "alice");
        em.flush();
        NaicomSubmissionArtifact second = artifactService.render(s.getId(), ArtifactFormat.JSON, "bob");
        em.flush();

        // Live row is the new one; previous still in the table with deleted_at set.
        assertThat(second.getId()).isNotEqualTo(first.getId());
        Integer liveCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM naicom_submission_artifact " +
            "WHERE submission_id = ? AND format = 'JSON' AND deleted_at IS NULL",
            Integer.class, s.getId());
        assertThat(liveCount).isEqualTo(1);
        Integer totalCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM naicom_submission_artifact " +
            "WHERE submission_id = ? AND format = 'JSON'",
            Integer.class, s.getId());
        assertThat(totalCount)
            .as("both attempts survive — re-render is a soft-replace, not delete")
            .isEqualTo(2);

        // findBySubmission returns live only
        assertThat(artifactService.findBySubmission(s.getId()))
            .extracting(NaicomSubmissionArtifact::getId)
            .containsExactly(second.getId());
    }

    @Test
    @DisplayName("multiple formats coexist for one submission")
    void multipleFormatsCoexist() {
        NaicomSubmission s = generateDraft();
        artifactService.render(s.getId(), ArtifactFormat.JSON, "alice");
        artifactService.render(s.getId(), ArtifactFormat.CSV, "alice");
        artifactService.render(s.getId(), ArtifactFormat.PDF, "alice");
        em.flush();

        List<NaicomSubmissionArtifact> live = artifactService.findBySubmission(s.getId());
        assertThat(live).hasSize(3);
        assertThat(live).extracting(NaicomSubmissionArtifact::getFormat)
            .containsExactlyInAnyOrder(ArtifactFormat.JSON, ArtifactFormat.CSV, ArtifactFormat.PDF);
    }

    @Test
    @DisplayName("openDownload streams the artifact bytes + correct mime + filename")
    void openDownload() throws IOException {
        NaicomSubmission s = generateDraft();
        NaicomSubmissionArtifact a = artifactService.render(s.getId(), ArtifactFormat.JSON, "alice");
        em.flush();

        SubmissionArtifactService.ArtifactDownload dl =
            artifactService.openDownload(s.getId(), ArtifactFormat.JSON);
        try (InputStream stream = dl.stream()) {
            byte[] downloaded = stream.readAllBytes();
            assertThat((long) downloaded.length).isEqualTo(a.getSizeBytes());
            assertThat(sha256(downloaded)).isEqualTo(a.getSha256Hex());
        }
        assertThat(dl.mimeType()).isEqualTo("application/json");
        assertThat(dl.filename()).endsWith(".json").contains(s.getId().toString());
    }

    @Test
    @DisplayName("download throws ArtifactNotFoundException when no live artifact")
    void downloadMissing() {
        NaicomSubmission s = generateDraft();
        assertThatThrownBy(() -> artifactService.openDownload(s.getId(), ArtifactFormat.JSON))
            .isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    @DisplayName("rendering for unknown submission id throws NaicomSubmissionNotFoundException")
    void renderUnknownSubmission() {
        assertThatThrownBy(() -> artifactService.render(
                UUID.randomUUID(), ArtifactFormat.JSON, "alice"))
            .isInstanceOf(com.nubeero.cia.finance.naicom.NaicomSubmissionNotFoundException.class);
    }

    @Test
    @DisplayName("renders work against SUBMITTED submissions (post-DRAFT) — payload is frozen, not the artifact")
    void rendersAcrossLifecycleStates() {
        NaicomSubmission draft = generateDraft();
        // First render while DRAFT
        NaicomSubmissionArtifact draftArtifact =
            artifactService.render(draft.getId(), ArtifactFormat.JSON, "alice");
        em.flush();
        // Transition to SUBMITTED then re-render — re-rendering of the
        // frozen payload is still allowed (it is the bytes that mirror the
        // frozen payload; auditors may need to re-export them).
        submissionService.submit(draft.getId(), "filed", "alice");
        em.flush();
        NaicomSubmissionArtifact afterSubmit =
            artifactService.render(draft.getId(), ArtifactFormat.JSON, "alice");
        em.flush();

        assertThat(afterSubmit.getSha256Hex())
            .as("payload is frozen → bytes (and sha) are identical pre/post submit")
            .isEqualTo(draftArtifact.getSha256Hex());
    }

    @Test
    @DisplayName("listBySubmission returns only non-deleted (live) artifacts")
    void listFiltersDeleted() {
        NaicomSubmission s = generateDraft();
        artifactService.render(s.getId(), ArtifactFormat.JSON, "alice");
        artifactService.render(s.getId(), ArtifactFormat.JSON, "alice");  // soft-deletes the first
        artifactService.render(s.getId(), ArtifactFormat.CSV, "alice");
        em.flush();

        // 2 live, 1 soft-deleted (the superseded JSON) in the table.
        Integer total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM naicom_submission_artifact WHERE submission_id = ?",
            Integer.class, s.getId());
        assertThat(total).isEqualTo(3);
        assertThat(artifactService.findBySubmission(s.getId())).hasSize(2);
    }

    @Test
    @DisplayName("JSON rendering is deterministic — two identical-payload renders produce identical sha")
    void jsonDeterminism() {
        NaicomSubmission s1 = generateDraft();
        NaicomSubmissionArtifact a1 = artifactService.render(s1.getId(), ArtifactFormat.JSON, "alice");
        // Regenerate the payload — the underlying engine produces identical
        // structure for an empty period, but a new generatedAt timestamp in
        // the payload may shift bytes. So we render once more for the SAME
        // submission entity (which doesn't recompute the payload).
        NaicomSubmissionArtifact a2 = artifactService.render(s1.getId(), ArtifactFormat.JSON, "alice");
        em.flush();
        assertThat(a2.getSha256Hex())
            .as("same submission payload → same JSON bytes → same sha256")
            .isEqualTo(a1.getSha256Hex());
    }

    @Test
    @DisplayName("dispatch wired — engine + 3 renderers all visible in the application context")
    void wiringSmokeTest() {
        assertThat(wiredEngines).hasSize(1);
        // Render coverage proves the three renderers are wired.
        NaicomSubmission s = generateDraft();
        for (ArtifactFormat f : List.of(ArtifactFormat.JSON, ArtifactFormat.CSV, ArtifactFormat.PDF)) {
            assertThat(artifactService.render(s.getId(), f, "smoke")).isNotNull();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private NaicomSubmission generateDraft() {
        NaicomSubmission s = submissionService.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "initial", "alice");
        em.flush();
        // Submission entity's payload is now populated.
        assertThat(s.getState()).isEqualTo(NaicomSubmissionState.DRAFT);
        return s;
    }

    private static String sha256(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // ── In-memory DocumentStorageService for tests ────────────────────────

    @TestConfiguration
    static class InMemoryStorageConfig {
        @Bean
        @Primary
        InMemoryStorage inMemoryStorage() {
            return new InMemoryStorage();
        }
    }

    /**
     * Minimal in-memory implementation of {@link DocumentStorageService}.
     * Records every upload by {@code (tenant, path)} for assertion. The
     * production MinIO / S3 adapters are not on the test classpath in
     * {@code @DataJpaTest} slices, so this is the seam used to exercise
     * the upload + download contract.
     */
    static class InMemoryStorage implements DocumentStorageService {
        private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

        @Override
        public String upload(String tenantId, String path, InputStream content, String mimeType) {
            try {
                blobs.put(key(tenantId, path), content.readAllBytes());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return path;
        }

        @Override
        public InputStream download(String tenantId, String path) {
            byte[] data = blobs.get(key(tenantId, path));
            if (data == null) {
                throw new IllegalStateException("Missing blob: " + key(tenantId, path));
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public void delete(String tenantId, String path) {
            blobs.remove(key(tenantId, path));
        }

        @Override
        public String presignedUrl(String tenantId, String path, long expirySeconds) {
            return "memory://" + key(tenantId, path);
        }

        byte[] read(String tenantId, String path) {
            return blobs.get(key(tenantId, path));
        }

        void reset() {
            blobs.clear();
        }

        private static String key(String tenant, String path) {
            return tenant + ":" + path;
        }
    }

    /** Unused — silences a stale-bean diagnostic if InMemoryStorage shifts. */
    @SuppressWarnings("unused")
    private void touch(HashMap<?, ?> ignored) {}
}
