package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.naicom.ArtifactFormat;
import com.nubeero.cia.finance.naicom.NaicomSubmission;
import com.nubeero.cia.finance.naicom.NaicomSubmissionArtifact;
import com.nubeero.cia.finance.naicom.NaicomSubmissionArtifactRepository;
import com.nubeero.cia.finance.naicom.NaicomSubmissionEvent;
import com.nubeero.cia.finance.naicom.NaicomSubmissionEventRepository;
import com.nubeero.cia.finance.naicom.NaicomSubmissionRepository;
import com.nubeero.cia.finance.naicom.NaicomSubmissionState;
import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 4.1 — repository-round-trip IT for the NAICOM submission entities.
 *
 * <p>Verifies the Java entity layer maps cleanly onto the V41 schema:
 * <ol>
 *   <li>Enum mappings ({@link NaicomSubmissionType} / {@link NaicomSubmissionState}
 *       / {@link ArtifactFormat}) round-trip via JPA.</li>
 *   <li>JSONB {@code payload} on {@link NaicomSubmission} round-trips as
 *       {@code Map<String, Object>} via {@code @JdbcTypeCode(SqlTypes.JSON)}.</li>
 *   <li>Idempotency lookup
 *       {@code findBySubmissionTypeAndPeriodIdAndDeletedAtIsNull} honours
 *       V41's partial UNIQUE index.</li>
 *   <li>Soft-delete on the submission re-opens the idempotency slot.</li>
 *   <li>{@link NaicomSubmissionEvent} is append-only — the row sequence
 *       per submission preserves the state-machine history.</li>
 * </ol>
 *
 * <p>Engine + orchestrator behaviour is intentionally NOT exercised here
 * — those land in slices 4.2–4.9 with their own ITs.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class)
class NaicomSubmissionEntityIT {

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

    @Autowired private NaicomSubmissionRepository submissionRepo;
    @Autowired private NaicomSubmissionArtifactRepository artifactRepo;
    @Autowired private NaicomSubmissionEventRepository eventRepo;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    private UUID periodId;

    @BeforeEach
    void seedFiscalPeriod() {
        jdbcTemplate.update("DELETE FROM naicom_submission_event");
        jdbcTemplate.update("DELETE FROM naicom_submission_artifact");
        jdbcTemplate.update("DELETE FROM naicom_submission");
        jdbcTemplate.update("DELETE FROM fiscal_period");
        jdbcTemplate.update("DELETE FROM fiscal_year");

        UUID fyId = UUID.randomUUID();
        periodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_year (id, name, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            fyId, "FY-NAICOM-IT-2026",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            periodId, fyId, "MONTH",
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), "HARD_CLOSED", "test");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Entity round-trip
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("submission persists with enum mappings + JSONB payload round-tripping correctly")
    void roundTripWithJsonbPayload() {
        // Use kobo-precise figures (Naira amounts realistically carry 2dp).
        // Whole-number doubles serialize to JSON as integers and round-trip
        // as Integer (not Double), so we'd otherwise have to compare via
        // Number.doubleValue() — kobo precision sidesteps the surface
        // mismatch and mirrors production data shape.
        NaicomSubmission sub = freshDraft(NaicomSubmissionType.PREMIUM_BORDEREAUX);
        sub.getPayload().put("totalGwp", 12_345_000.50);
        sub.getPayload().put("policyCount", 142);
        sub.getPayload().put("classBreakdown", List.of(
            Map.of("class", "Motor", "premium", 5_000_000.25),
            Map.of("class", "Fire", "premium", 7_344_999.75)));

        NaicomSubmission saved = submissionRepo.save(sub);
        entityManager.flush();
        entityManager.clear();

        NaicomSubmission reloaded = submissionRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSubmissionType()).isEqualTo(NaicomSubmissionType.PREMIUM_BORDEREAUX);
        assertThat(reloaded.getState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(reloaded.getPeriodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(reloaded.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(reloaded.getPayload())
            .containsEntry("totalGwp", 12_345_000.50)
            .containsEntry("policyCount", 142);
        assertThat(reloaded.getPayload().get("classBreakdown")).isInstanceOf(List.class);
        assertThat(reloaded.isPayloadMutable()).isTrue();
    }

    @Test
    @DisplayName("default payload is empty map (not null) — satisfies NOT NULL constraint without explicit init")
    void defaultPayloadIsEmptyMap() {
        NaicomSubmission sub = freshDraft(NaicomSubmissionType.CLAIMS_BORDEREAUX);
        // Do NOT touch payload.
        NaicomSubmission saved = submissionRepo.save(sub);
        entityManager.flush();

        NaicomSubmission reloaded = submissionRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPayload()).isNotNull().isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Idempotency lookup
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("findBySubmissionTypeAndPeriodIdAndDeletedAtIsNull returns the live submission")
    void idempotencyLookupReturnsLive() {
        NaicomSubmission saved = submissionRepo.save(freshDraft(NaicomSubmissionType.PRUDENTIAL_RETURN));
        entityManager.flush();

        Optional<NaicomSubmission> found = submissionRepo
            .findBySubmissionTypeAndPeriodIdAndDeletedAtIsNull(NaicomSubmissionType.PRUDENTIAL_RETURN, periodId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("after soft-delete the idempotency lookup returns empty and a fresh insert succeeds")
    void softDeleteReopensIdempotencySlot() {
        NaicomSubmission first = submissionRepo.save(freshDraft(NaicomSubmissionType.RI_QUARTERLY_RETURN));
        entityManager.flush();

        first.softDelete();
        submissionRepo.save(first);
        entityManager.flush();

        assertThat(submissionRepo
                .findBySubmissionTypeAndPeriodIdAndDeletedAtIsNull(NaicomSubmissionType.RI_QUARTERLY_RETURN, periodId))
            .as("soft-deleted submission is filtered out of the idempotency lookup")
            .isEmpty();

        // A second live row for the same (type, period) is now allowed.
        NaicomSubmission second = submissionRepo.save(freshDraft(NaicomSubmissionType.RI_QUARTERLY_RETURN));
        entityManager.flush();

        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    @DisplayName("attempting two live submissions for the same (type, period) violates V41 UNIQUE — exception bubbles")
    void duplicateLiveSubmissionRejected() {
        submissionRepo.save(freshDraft(NaicomSubmissionType.PREMIUM_BORDEREAUX));
        entityManager.flush();

        NaicomSubmission duplicate = freshDraft(NaicomSubmissionType.PREMIUM_BORDEREAUX);
        submissionRepo.save(duplicate);
        assertThatThrownBy(entityManager::flush)
            .hasMessageContaining("uq_naicom_submission_type_period");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Artifact behaviour
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("artifact round-trips with format enum + sha-256 + size + storage path")
    void artifactRoundTrip() {
        NaicomSubmission sub = submissionRepo.save(freshDraft(NaicomSubmissionType.CLAIMS_BORDEREAUX));
        entityManager.flush();

        NaicomSubmissionArtifact pdf = new NaicomSubmissionArtifact();
        pdf.setSubmissionId(sub.getId());
        pdf.setFormat(ArtifactFormat.PDF);
        pdf.setStoragePath("naicom/2026-04/claims-bordereaux.pdf");
        pdf.setSizeBytes(123456L);
        pdf.setSha256Hex("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        pdf.setRenderedAt(Instant.now());
        pdf.setRenderedBy("test-rendering");
        artifactRepo.save(pdf);
        entityManager.flush();

        Optional<NaicomSubmissionArtifact> found = artifactRepo
            .findBySubmissionIdAndFormatAndDeletedAtIsNull(sub.getId(), ArtifactFormat.PDF);
        assertThat(found).isPresent();
        assertThat(found.get().getSizeBytes()).isEqualTo(123456L);
        assertThat(found.get().getStoragePath()).contains("claims-bordereaux.pdf");
    }

    @Test
    @DisplayName("two artifacts on the same submission with distinct formats coexist")
    void distinctFormatsCoexist() {
        NaicomSubmission sub = submissionRepo.save(freshDraft(NaicomSubmissionType.PREMIUM_BORDEREAUX));
        entityManager.flush();

        artifactRepo.save(artifact(sub.getId(), ArtifactFormat.PDF, "p.pdf"));
        artifactRepo.save(artifact(sub.getId(), ArtifactFormat.CSV, "p.csv"));
        entityManager.flush();

        List<NaicomSubmissionArtifact> artifacts = artifactRepo
            .findAllBySubmissionIdAndDeletedAtIsNull(sub.getId());
        assertThat(artifacts).hasSize(2);
        assertThat(artifacts).extracting(NaicomSubmissionArtifact::getFormat)
            .containsExactlyInAnyOrder(ArtifactFormat.PDF, ArtifactFormat.CSV);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Event audit history
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("events for a submission are returned oldest-first — auditor traversal order")
    void eventsOrderedOldestFirst() {
        NaicomSubmission sub = submissionRepo.save(freshDraft(NaicomSubmissionType.IFRS17_DISCLOSURE));
        entityManager.flush();

        // Initial DRAFT creation (from_state IS NULL).
        eventRepo.save(event(sub.getId(), null, NaicomSubmissionState.DRAFT, "Initial generation", "engine"));
        // DRAFT re-generation.
        eventRepo.save(event(sub.getId(), NaicomSubmissionState.DRAFT, NaicomSubmissionState.DRAFT,
                "Re-generation: figures refreshed", "engine"));
        // DRAFT → SUBMITTED.
        eventRepo.save(event(sub.getId(), NaicomSubmissionState.DRAFT, NaicomSubmissionState.SUBMITTED,
                "CFO submits", "cfo@tenant"));
        entityManager.flush();

        List<NaicomSubmissionEvent> chain = eventRepo
            .findAllBySubmissionIdOrderByOccurredAtAsc(sub.getId());

        assertThat(chain).hasSize(3);
        assertThat(chain.get(0).getFromState()).isNull();
        assertThat(chain.get(0).getToState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(chain.get(1).getFromState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(chain.get(1).getToState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(chain.get(2).getToState()).isEqualTo(NaicomSubmissionState.SUBMITTED);
        assertThat(chain.get(2).getActor()).isEqualTo("cfo@tenant");
    }

    // ── Fixture helpers ────────────────────────────────────────────────────
    private NaicomSubmission freshDraft(NaicomSubmissionType type) {
        NaicomSubmission s = new NaicomSubmission();
        s.setSubmissionType(type);
        s.setPeriodId(periodId);
        s.setPeriodStart(LocalDate.of(2026, 4, 1));
        s.setPeriodEnd(LocalDate.of(2026, 4, 30));
        return s;
    }

    private static NaicomSubmissionArtifact artifact(UUID subId, ArtifactFormat fmt, String path) {
        NaicomSubmissionArtifact a = new NaicomSubmissionArtifact();
        a.setSubmissionId(subId);
        a.setFormat(fmt);
        a.setStoragePath("naicom/2026-04/" + path);
        a.setSizeBytes(1024L);
        a.setSha256Hex("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        a.setRenderedAt(Instant.now());
        a.setRenderedBy("test");
        return a;
    }

    private static NaicomSubmissionEvent event(UUID subId, NaicomSubmissionState from,
                                                NaicomSubmissionState to, String reason, String actor) {
        NaicomSubmissionEvent e = new NaicomSubmissionEvent();
        e.setSubmissionId(subId);
        e.setFromState(from);
        e.setToState(to);
        e.setReason(reason);
        e.setActor(actor);
        e.setOccurredAt(Instant.now());
        return e;
    }
}
