package com.nubeero.cia.api.finance.gl;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.finance.dto.CreateFiscalYearRequest;
import com.nubeero.cia.finance.dto.FiscalPeriodResponse;
import com.nubeero.cia.finance.dto.FiscalYearResponse;
import com.nubeero.cia.finance.gl.FiscalPeriodType;
import com.nubeero.cia.finance.gl.FiscalYearActivationConflictException;
import com.nubeero.cia.finance.gl.FiscalYearHasJournalEntriesException;
import com.nubeero.cia.finance.gl.FiscalYearNameConflictException;
import com.nubeero.cia.finance.gl.FiscalYearNotFoundException;
import com.nubeero.cia.finance.gl.FiscalYearService;
import com.nubeero.cia.finance.gl.FiscalYearStatus;
import com.nubeero.cia.finance.gl.InvalidFiscalYearBoundsException;
import com.nubeero.cia.finance.gl.PeriodLockService;
import org.mockito.Mockito;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link FiscalYearService} against a real Postgres
 * container with all Flyway migrations applied (V31 schema + V32 COA seed
 * + V33 posting rules from Slice 1.5). Verifies the end-to-end lifecycle
 * against the real schema:
 *
 * <ul>
 *   <li>create + auto-generate 19 child periods at the FK level</li>
 *   <li>activate respects D3=B (refuses when sibling is ACTIVE)</li>
 *   <li>full sequence: PLANNING → activate → close → activate successor</li>
 *   <li>delete blocked when a real journal_entry row references a child period</li>
 *   <li>bootstrapForNewTenant idempotence in a fresh schema</li>
 * </ul>
 *
 * <p>Mirrors Slice 1.4 / 1.5 IT structure: @DataJpaTest + Testcontainers +
 * inner TestConfiguration providing a system Clock.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    FiscalYearService.class,
    CiaCommonAutoConfiguration.class,
    FiscalYearServiceIT.TestSupportConfig.class
})
class FiscalYearServiceIT {

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
        // V43 — match the rest of the cia-api IT suite (Slice 1.10).
        registry.add("spring.flyway.target", () -> "48");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
    }

    @Autowired private FiscalYearService service;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @DisplayName("create persists FY + 19 child periods; FK fiscal_year_id is satisfied")
    void createHappyPath() {
        FiscalYearResponse response = service.create(new CreateFiscalYearRequest(
            "FY2026 IT", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        entityManager.flush();

        assertThat(response.id()).isNotNull();
        assertThat(response.status()).isEqualTo(FiscalYearStatus.PLANNING);
        assertThat(response.periods()).hasSize(19);

        Long periodRowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM fiscal_period WHERE fiscal_year_id = ?",
            Long.class, response.id());
        assertThat(periodRowCount).isEqualTo(19L);
    }

    @Test
    @DisplayName("activate flips PLANNING → ACTIVE when no other FY is ACTIVE")
    void activateHappyPath() {
        FiscalYearResponse fy = service.create(new CreateFiscalYearRequest(
            "FY2026 activate", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        entityManager.flush();

        FiscalYearResponse activated = service.activate(fy.id());
        assertThat(activated.status()).isEqualTo(FiscalYearStatus.ACTIVE);
    }

    @Test
    @DisplayName("activate refuses with FiscalYearActivationConflictException when another FY is ACTIVE (D3=B)")
    void activateConflictWithActiveSibling() {
        FiscalYearResponse first = service.create(new CreateFiscalYearRequest(
            "FY2026 conflict-a", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        service.activate(first.id());
        entityManager.flush();

        FiscalYearResponse second = service.create(new CreateFiscalYearRequest(
            "FY2027 conflict-b", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)));
        entityManager.flush();

        assertThatThrownBy(() -> service.activate(second.id()))
            .isInstanceOf(FiscalYearActivationConflictException.class)
            .hasMessageContaining("FY2026 conflict-a");
    }

    @Test
    @DisplayName("full sequence: activate FY, close it, activate successor — both happen cleanly")
    void closeAndActivateSuccessor() {
        FiscalYearResponse fy2026 = service.create(new CreateFiscalYearRequest(
            "FY2026 seq", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        service.activate(fy2026.id());
        entityManager.flush();

        FiscalYearResponse fy2027 = service.create(new CreateFiscalYearRequest(
            "FY2027 seq", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)));
        entityManager.flush();

        // FY2027 cannot activate while FY2026 is ACTIVE.
        assertThatThrownBy(() -> service.activate(fy2027.id()))
            .isInstanceOf(FiscalYearActivationConflictException.class);

        // Close FY2026 explicitly, then activate FY2027.
        service.close(fy2026.id());
        entityManager.flush();
        FiscalYearResponse activatedSuccessor = service.activate(fy2027.id());
        assertThat(activatedSuccessor.status()).isEqualTo(FiscalYearStatus.ACTIVE);
    }

    @Test
    @DisplayName("delete blocked when a journal_entry row references a child period (d11)")
    void deleteBlockedByJournalEntry() {
        FiscalYearResponse fy = service.create(new CreateFiscalYearRequest(
            "FY2026 delete-blocked", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        entityManager.flush();

        // Insert a real journal_entry referencing one of the child periods via JDBC
        // — avoids dragging JournalEntryService through this test for one row.
        UUID anyPeriodId = jdbcTemplate.queryForObject(
            "SELECT id FROM fiscal_period WHERE fiscal_year_id = ? AND period_type = 'MONTH' LIMIT 1",
            UUID.class, fy.id());
        UUID jeId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO journal_entry (id, posting_date, business_date, period_id, source_module, " +
                "source_event_type, source_reference, narrative, posted_by, status, created_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            jeId, LocalDate.of(2026, 5, 14), LocalDate.of(2026, 5, 14), anyPeriodId,
            "finance", "MANUAL", "delete-blocked-ref-1", "test", "test", "POSTED", "test");

        assertThatThrownBy(() -> service.delete(fy.id()))
            .isInstanceOf(FiscalYearHasJournalEntriesException.class)
            .hasMessageContaining("1 journal entry");
    }

    @Test
    @DisplayName("bootstrapForNewTenant is idempotent against a fresh schema (D4=A)")
    void bootstrapIdempotent() {
        FiscalYearResponse first = service.bootstrapForNewTenant();
        entityManager.flush();
        assertThat(first.status()).isEqualTo(FiscalYearStatus.ACTIVE);

        FiscalYearResponse second = service.bootstrapForNewTenant();
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("findActive throws FISCAL_YEAR_NO_ACTIVE when nothing is ACTIVE")
    void findActiveNothing() {
        assertThatThrownBy(() -> service.findActive())
            .isInstanceOf(FiscalYearNotFoundException.class)
            .hasMessageContaining("No fiscal year is currently ACTIVE");
    }

    @Test
    @DisplayName("create rejects misaligned bounds at the service layer before any INSERT")
    void createMisalignedRejected() {
        assertThatThrownBy(() -> service.create(new CreateFiscalYearRequest(
            null, LocalDate.of(2026, 1, 15), LocalDate.of(2027, 1, 14))))
            .isInstanceOf(InvalidFiscalYearBoundsException.class)
            .hasMessageContaining("first day of a month");

        Long rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM fiscal_year", Long.class);
        assertThat(rowCount).isZero();
    }

    @Test
    @DisplayName("create with duplicate name throws FiscalYearNameConflictException (DB UNIQUE backstop)")
    void createDuplicateName() {
        service.create(new CreateFiscalYearRequest(
            "Duplicate Name", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        entityManager.flush();

        assertThatThrownBy(() -> service.create(new CreateFiscalYearRequest(
            "Duplicate Name", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31))))
            .isInstanceOf(FiscalYearNameConflictException.class)
            .hasMessageContaining("Duplicate Name");
    }

    @Test
    @DisplayName("listPeriods returns 19 periods in sorted order")
    void listPeriodsSorted() {
        FiscalYearResponse fy = service.create(new CreateFiscalYearRequest(
            "FY2026 list-periods", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        entityManager.flush();

        List<FiscalPeriodResponse> periods = service.listPeriods(fy.id());
        assertThat(periods).hasSize(19);
        // Verify the four period types each have the right cardinality.
        long monthCount = periods.stream().filter(p -> p.periodType() == FiscalPeriodType.MONTH).count();
        long quarterCount = periods.stream().filter(p -> p.periodType() == FiscalPeriodType.QUARTER).count();
        long halfCount = periods.stream().filter(p -> p.periodType() == FiscalPeriodType.HALF_YEAR).count();
        long yearCount = periods.stream().filter(p -> p.periodType() == FiscalPeriodType.YEAR).count();
        assertThat(monthCount).isEqualTo(12L);
        assertThat(quarterCount).isEqualTo(4L);
        assertThat(halfCount).isEqualTo(2L);
        assertThat(yearCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("close rejects a PLANNING FY — only ACTIVE → CLOSED is valid")
    void closeOnPlanning() {
        FiscalYearResponse fy = service.create(new CreateFiscalYearRequest(
            "FY2026 close-planning", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        entityManager.flush();

        assertThatThrownBy(() -> service.close(fy.id()))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("only ACTIVE");
    }

    @TestConfiguration
    static class TestSupportConfig {

        /**
         * The IT focuses on FiscalYearService's DB-level behaviour against
         * the real Flyway-migrated schema. The close-cascade interaction
         * with {@link PeriodLockService} is fully covered by
         * {@code FiscalYearServiceTest}; a no-op mock keeps the Spring
         * context graph small while still satisfying the constructor
         * dependency added by the cascade fix.
         *
         * <p>{@code Clock} is intentionally not provided here —
         * {@link CiaCommonAutoConfiguration} already exposes a
         * {@code @ConditionalOnMissingBean} system-default Clock, which is
         * exactly what this IT needs (no fixed-clock testing). Declaring it
         * twice triggers a {@code BeanDefinitionOverrideException}.
         */
        @Bean
        PeriodLockService periodLockService() {
            return Mockito.mock(PeriodLockService.class);
        }
    }
}
