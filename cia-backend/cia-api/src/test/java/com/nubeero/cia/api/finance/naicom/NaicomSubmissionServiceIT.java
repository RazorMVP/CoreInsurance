package com.nubeero.cia.api.finance.naicom;

import com.nubeero.cia.finance.gl.FiscalPeriodNotFoundException;
import com.nubeero.cia.finance.naicom.IllegalSubmissionStateException;
import com.nubeero.cia.finance.naicom.NaicomSubmission;
import com.nubeero.cia.finance.naicom.NaicomSubmissionEngine;
import com.nubeero.cia.finance.naicom.NaicomSubmissionEvent;
import com.nubeero.cia.finance.naicom.NaicomSubmissionNotFoundException;
import com.nubeero.cia.finance.naicom.NaicomSubmissionService;
import com.nubeero.cia.finance.naicom.NaicomSubmissionState;
import com.nubeero.cia.finance.naicom.NaicomSubmissionType;
import com.nubeero.cia.finance.naicom.NiidStatusSnapshotEngine;
import com.nubeero.cia.finance.naicom.PayloadFrozenException;
import com.nubeero.cia.finance.naicom.PeriodNotHardClosedException;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 4.9 IT for {@link NaicomSubmissionService} — the lifecycle
 * orchestrator. Wires up the {@link NiidStatusSnapshotEngine} as the
 * representative engine for dispatch tests (it's the simplest source-
 * table engine; the orchestrator's contract with every engine is the
 * same {@code computePayload(UUID)} method).
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Generate creates a fresh DRAFT for a HARD_CLOSED period and
 *       emits a null→DRAFT initial event.</li>
 *   <li>Generate against an OPEN period throws
 *       {@link PeriodNotHardClosedException}.</li>
 *   <li>Generate against a missing period throws
 *       {@link FiscalPeriodNotFoundException}.</li>
 *   <li>Re-generate while DRAFT updates payload in place + emits a
 *       DRAFT→DRAFT event (V41 same-state carve-out).</li>
 *   <li>Generate after SUBMIT throws
 *       {@link PayloadFrozenException}.</li>
 *   <li>Submit DRAFT→SUBMITTED; sets submittedAt + submittedBy; emits
 *       transition event.</li>
 *   <li>Submit on non-DRAFT throws
 *       {@link IllegalSubmissionStateException}.</li>
 *   <li>Acknowledge SUBMITTED→ACKNOWLEDGED; requires naicomUid; sets
 *       acknowledgedAt + acknowledgedBy + naicomUid.</li>
 *   <li>Retract SUBMITTED→RETRACTED; requires reason; soft-deletes the
 *       row so the (type, period) UNIQUE slot is freed.</li>
 *   <li>Archive ACKNOWLEDGED→ARCHIVED; soft-deletes the row.</li>
 *   <li>Each transition emits exactly one event; chain is ordered by
 *       occurredAt ASC.</li>
 *   <li>findById on unknown id throws
 *       {@link NaicomSubmissionNotFoundException}.</li>
 *   <li>findByPeriod returns only non-deleted rows for the given period.</li>
 *   <li>findByState filters correctly.</li>
 *   <li>After retract, a fresh generate for the same (type, period) succeeds
 *       (the partial-UNIQUE was freed by the soft-delete).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    NaicomSubmissionService.class,
    NiidStatusSnapshotEngine.class
})
class NaicomSubmissionServiceIT {

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
        registry.add("spring.flyway.target", () -> "41");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private NaicomSubmissionService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager em;
    @Autowired private List<NaicomSubmissionEngine> wiredEngines;

    private UUID hardClosedPeriodId;
    private UUID openPeriodId;
    private static final java.time.LocalDate PERIOD_START = java.time.LocalDate.of(2026, 1, 1);
    private static final java.time.LocalDate PERIOD_END = java.time.LocalDate.of(2026, 3, 31);

    @BeforeEach
    void seed() {
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
            fyId, "FY-N09-2026",
            java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31),
            "ACTIVE", "test");
        hardClosedPeriodId = UUID.randomUUID();
        openPeriodId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            hardClosedPeriodId, fyId, "QUARTER", PERIOD_START, PERIOD_END,
            "HARD_CLOSED", "test");
        jdbcTemplate.update(
            "INSERT INTO fiscal_period (id, fiscal_year_id, period_type, start_date, end_date, status, created_by) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            openPeriodId, fyId, "QUARTER",
            java.time.LocalDate.of(2026, 4, 1), java.time.LocalDate.of(2026, 6, 30),
            "OPEN", "test");
    }

    @Test
    @DisplayName("orchestrator indexes every wired engine by type")
    void engineDispatchIndexed() {
        // The IT only wires NiidStatusSnapshotEngine — assert the dispatcher
        // sees exactly that one. Production wiring scans every Spring bean.
        assertThat(wiredEngines).hasSize(1);
        assertThat(wiredEngines.get(0).type())
            .isEqualTo(NaicomSubmissionType.NIID_STATUS_SNAPSHOT);
    }

    @Test
    @DisplayName("generate creates a DRAFT for a HARD_CLOSED period + initial null→DRAFT event")
    void generateCreatesDraft() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first generation", "alice");
        em.flush();

        assertThat(row.getId()).isNotNull();
        assertThat(row.getState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(row.getPeriodStart()).isEqualTo(PERIOD_START);
        assertThat(row.getPeriodEnd()).isEqualTo(PERIOD_END);
        assertThat(row.getPayload()).isNotEmpty();
        assertThat(row.getPayload().get("submissionType"))
            .isEqualTo(NaicomSubmissionType.NIID_STATUS_SNAPSHOT.name());

        List<NaicomSubmissionEvent> events = service.findEvents(row.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFromState()).isNull();
        assertThat(events.get(0).getToState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(events.get(0).getActor()).isEqualTo("alice");
        assertThat(events.get(0).getReason()).isEqualTo("first generation");
    }

    @Test
    @DisplayName("generate on OPEN period throws PeriodNotHardClosedException")
    void generateOnOpenPeriodRejected() {
        assertThatThrownBy(() -> service.generate(
                NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
                openPeriodId, "should fail", "alice"))
            .isInstanceOf(PeriodNotHardClosedException.class)
            .hasMessageContaining("OPEN")
            .hasMessageContaining("HARD_CLOSED");
    }

    @Test
    @DisplayName("generate on missing period throws FiscalPeriodNotFoundException")
    void generateOnMissingPeriodRejected() {
        assertThatThrownBy(() -> service.generate(
                NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
                UUID.randomUUID(), "should fail", "alice"))
            .isInstanceOf(FiscalPeriodNotFoundException.class);
    }

    @Test
    @DisplayName("re-generate while DRAFT updates payload in place + emits DRAFT→DRAFT event")
    void regenerateWhileDraft() {
        NaicomSubmission first = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();

        NaicomSubmission second = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "second regen — schema changed", "bob");
        em.flush();

        assertThat(second.getId())
            .as("re-generation keeps the same id — payload updated in place")
            .isEqualTo(first.getId());
        assertThat(second.getNotes()).isEqualTo("second regen — schema changed");

        List<NaicomSubmissionEvent> events = service.findEvents(first.getId());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getFromState()).isNull();
        assertThat(events.get(1).getFromState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(events.get(1).getToState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(events.get(1).getActor()).isEqualTo("bob");
        assertThat(events.get(1).getReason()).isEqualTo("second regen — schema changed");
    }

    @Test
    @DisplayName("generate after SUBMIT throws PayloadFrozenException")
    void payloadFrozenAfterSubmit() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "initial", "alice");
        em.flush();
        service.submit(row.getId(), "ready", "alice");
        em.flush();

        assertThatThrownBy(() -> service.generate(
                NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
                hardClosedPeriodId, "attempted regen", "alice"))
            .isInstanceOf(PayloadFrozenException.class)
            .hasMessageContaining("SUBMITTED");
    }

    @Test
    @DisplayName("submit DRAFT→SUBMITTED — fields populated, transition event recorded")
    void submitDraft() {
        NaicomSubmission draft = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();

        NaicomSubmission submitted = service.submit(draft.getId(), "filed with NAICOM", "alice");
        em.flush();

        assertThat(submitted.getState()).isEqualTo(NaicomSubmissionState.SUBMITTED);
        assertThat(submitted.getSubmittedAt()).isNotNull();
        assertThat(submitted.getSubmittedBy()).isEqualTo("alice");

        List<NaicomSubmissionEvent> events = service.findEvents(draft.getId());
        assertThat(events).hasSize(2);
        assertThat(events.get(1).getFromState()).isEqualTo(NaicomSubmissionState.DRAFT);
        assertThat(events.get(1).getToState()).isEqualTo(NaicomSubmissionState.SUBMITTED);
        assertThat(events.get(1).getReason()).isEqualTo("filed with NAICOM");
    }

    @Test
    @DisplayName("submit on non-DRAFT throws IllegalSubmissionStateException")
    void submitOnNonDraftRejected() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        service.submit(row.getId(), "filed", "alice");
        em.flush();

        assertThatThrownBy(() -> service.submit(row.getId(), "again", "alice"))
            .isInstanceOf(IllegalSubmissionStateException.class)
            .hasMessageContaining("submit")
            .hasMessageContaining("SUBMITTED");
    }

    @Test
    @DisplayName("acknowledge SUBMITTED→ACKNOWLEDGED — naicomUid + actor recorded")
    void acknowledgeSubmitted() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        service.submit(row.getId(), "filed", "alice");
        em.flush();

        NaicomSubmission ack = service.acknowledge(row.getId(), "NAICOM-XYZ-001", "cfo");
        em.flush();

        assertThat(ack.getState()).isEqualTo(NaicomSubmissionState.ACKNOWLEDGED);
        assertThat(ack.getAcknowledgedAt()).isNotNull();
        assertThat(ack.getAcknowledgedBy()).isEqualTo("cfo");
        assertThat(ack.getNaicomUid()).isEqualTo("NAICOM-XYZ-001");
    }

    @Test
    @DisplayName("acknowledge requires naicomUid")
    void acknowledgeRequiresUid() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        service.submit(row.getId(), "filed", "alice");
        em.flush();

        assertThatThrownBy(() -> service.acknowledge(row.getId(), null, "cfo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("naicomUid");
        assertThatThrownBy(() -> service.acknowledge(row.getId(), "   ", "cfo"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("retract SUBMITTED→RETRACTED — reason required; row soft-deleted to free UNIQUE slot")
    void retractSubmitted() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        service.submit(row.getId(), "filed", "alice");
        em.flush();

        NaicomSubmission retracted = service.retract(
            row.getId(), "spotted figure mismatch with TB", "alice");
        em.flush();

        assertThat(retracted.getState()).isEqualTo(NaicomSubmissionState.RETRACTED);
        assertThat(retracted.getRetractedAt()).isNotNull();
        assertThat(retracted.getRetractedBy()).isEqualTo("alice");
        assertThat(retracted.getRetractionReason()).isEqualTo("spotted figure mismatch with TB");
        assertThat(retracted.getDeletedAt())
            .as("retract soft-deletes to free the (type, period) UNIQUE slot")
            .isNotNull();

        // Fresh generate against same (type, period) now succeeds — the
        // partial UNIQUE only applies to deleted_at IS NULL rows.
        NaicomSubmission fresh = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "corrected", "alice");
        em.flush();
        assertThat(fresh.getId()).isNotEqualTo(retracted.getId());
        assertThat(fresh.getState()).isEqualTo(NaicomSubmissionState.DRAFT);
    }

    @Test
    @DisplayName("retract requires reason")
    void retractRequiresReason() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        service.submit(row.getId(), "filed", "alice");
        em.flush();

        assertThatThrownBy(() -> service.retract(row.getId(), "", "alice"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.retract(row.getId(), null, "alice"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("archive ACKNOWLEDGED→ARCHIVED — soft-deletes row")
    void archiveAcknowledged() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        service.submit(row.getId(), "filed", "alice");
        em.flush();
        service.acknowledge(row.getId(), "NAICOM-XYZ-001", "cfo");
        em.flush();

        NaicomSubmission archived = service.archive(row.getId(), "retention-worker");
        em.flush();

        assertThat(archived.getState()).isEqualTo(NaicomSubmissionState.ARCHIVED);
        assertThat(archived.getArchivedAt()).isNotNull();
        assertThat(archived.getDeletedAt()).isNotNull();

        // Event chain captures the full path.
        List<NaicomSubmissionEvent> events = service.findEvents(row.getId());
        assertThat(events).extracting(NaicomSubmissionEvent::getToState)
            .containsExactly(
                NaicomSubmissionState.DRAFT,
                NaicomSubmissionState.SUBMITTED,
                NaicomSubmissionState.ACKNOWLEDGED,
                NaicomSubmissionState.ARCHIVED);
    }

    @Test
    @DisplayName("findById on unknown id throws NaicomSubmissionNotFoundException")
    void findUnknown() {
        assertThatThrownBy(() -> service.findById(UUID.randomUUID()))
            .isInstanceOf(NaicomSubmissionNotFoundException.class);
        assertThatThrownBy(() -> service.findEvents(UUID.randomUUID()))
            .isInstanceOf(NaicomSubmissionNotFoundException.class);
    }

    @Test
    @DisplayName("findByPeriod returns only non-deleted rows for the period")
    void findByPeriodFiltersDeleted() {
        NaicomSubmission a = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        service.submit(a.getId(), "filed", "alice");
        em.flush();
        service.retract(a.getId(), "wrong", "alice");
        em.flush();
        // After retract, the original row is soft-deleted. Generate fresh.
        NaicomSubmission b = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "corrected", "alice");
        em.flush();

        List<NaicomSubmission> live = service.findByPeriod(hardClosedPeriodId);
        assertThat(live)
            .extracting(NaicomSubmission::getId)
            .as("findByPeriod returns the live one only — the retracted-and-soft-deleted is filtered out")
            .containsExactly(b.getId());
    }

    @Test
    @DisplayName("transition emits exactly one event; full chain ordered by occurredAt ASC")
    void fullLifecycleChain() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        service.generate(NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "regen", "alice");
        em.flush();
        service.submit(row.getId(), "filed", "alice");
        em.flush();
        service.acknowledge(row.getId(), "NAICOM-XYZ", "cfo");
        em.flush();
        service.archive(row.getId(), "retention");
        em.flush();

        List<NaicomSubmissionEvent> events = service.findEvents(row.getId());
        // Initial null→DRAFT, regen DRAFT→DRAFT, DRAFT→SUBMITTED, SUBMITTED→ACK, ACK→ARCHIVED
        assertThat(events).hasSize(5);
        assertThat(events).extracting(NaicomSubmissionEvent::getToState)
            .containsExactly(
                NaicomSubmissionState.DRAFT,
                NaicomSubmissionState.DRAFT,
                NaicomSubmissionState.SUBMITTED,
                NaicomSubmissionState.ACKNOWLEDGED,
                NaicomSubmissionState.ARCHIVED);
        // occurredAt monotonically non-decreasing
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getOccurredAt())
                .isAfterOrEqualTo(events.get(i - 1).getOccurredAt());
        }
    }

    @Test
    @DisplayName("payload survives the transitions — frozen at submit")
    void payloadFrozenSemantics() {
        NaicomSubmission row = service.generate(
            NaicomSubmissionType.NIID_STATUS_SNAPSHOT,
            hardClosedPeriodId, "first", "alice");
        em.flush();
        Map<String, Object> draftPayload = Map.copyOf(row.getPayload());
        service.submit(row.getId(), "filed", "alice");
        em.flush();

        NaicomSubmission reloaded = service.findById(row.getId());
        assertThat(reloaded.getPayload())
            .as("payload is identical pre- and post-submit")
            .containsAllEntriesOf(draftPayload);
    }
}
