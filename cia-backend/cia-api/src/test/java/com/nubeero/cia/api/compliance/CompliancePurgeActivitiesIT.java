package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.compliance.purge.CompliancePurgeActivitiesImpl;
import com.nubeero.cia.compliance.purge.CustomerPiiPurgeService;
import com.nubeero.cia.compliance.purge.CustomerPurgeRepository;
import com.nubeero.cia.compliance.purge.PurgeAuditWriter;
import com.nubeero.cia.compliance.retention.RetentionPolicyService;
import com.nubeero.cia.storage.DocumentStorageService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({CiaCommonAutoConfiguration.class, CustomerPurgeRepository.class, CustomerPiiPurgeService.class,
        PurgeAuditWriter.class, RetentionPolicyService.class, CompliancePurgeActivitiesImpl.class,
        CompliancePurgeActivitiesIT.TestSupportConfig.class})
class CompliancePurgeActivitiesIT extends ComplianceItSupport {

    @Autowired CompliancePurgeActivitiesImpl activities;
    @Autowired EntityManager em;

    // Sunday 2026-06-14 03:00 UTC — matches the default policy window (WEEKLY, day 0, hour 3).
    private static final Instant SUNDAY_0300 =
            ZonedDateTime.of(2026, 6, 14, 3, 0, 0, 0, ZoneOffset.UTC).toInstant();

    @Test
    void optInOff_noPurge() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID elig = seedEligibleCustomer(jdbc);
        // No data_retention_policy row seeded → getOrCreate makes the default (purge_enabled=false).
        var result = activities.purgeTenantAt("test-tenant", SUNDAY_0300);
        assertThat(result.ran()).isFalse();
        assertThat(jdbc.queryForObject("SELECT pii_purged_at FROM customers WHERE id = ?",
            Object.class, elig)).isNull();
    }

    @Test
    void matchedWindow_optInOn_purgesAndStampsAndDebounces() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID elig = seedEligibleCustomer(jdbc);
        enablePurge(jdbc);

        var first = activities.purgeTenantAt("test-tenant", SUNDAY_0300);
        assertThat(first.ran()).isTrue();
        assertThat(first.customersPurged()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pii_purged_at FROM customers WHERE id = ?",
            Object.class, elig)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT last_purge_run_at FROM data_retention_policy",
            Object.class)).isNotNull();

        // Under @DataJpaTest one Hibernate session spans the whole method, so the first getOrCreate()
        // leaves a managed policy entity with last_purge_run_at=null cached; stampLastPurgeRun's native
        // UPDATE bypasses that cache. Clear the context so the second getOrCreate() re-reads the committed
        // stamp — exactly what production does (a fresh session per Temporal activity run).
        em.clear();

        // Debounce: a second fire 30 min later — STILL matches the window hour (03:xx Sunday), so this
        // exercises the debounce gate specifically (last_purge_run_at within 23h), not the window gate.
        var second = activities.purgeTenantAt("test-tenant", SUNDAY_0300.plusSeconds(1800));
        assertThat(second.ran()).isFalse();
        assertThat(second.skippedReason()).isEqualTo("debounced");
    }

    @Test
    void wrongHour_noPurge() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedEligibleCustomer(jdbc);
        enablePurge(jdbc);
        var result = activities.purgeTenantAt("test-tenant", SUNDAY_0300.plusSeconds(3600)); // 04:00
        assertThat(result.ran()).isFalse();
    }

    private UUID seedEligibleCustomer(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, "
            + "first_name, last_name, created_by) VALUES (?,?,?,?,?,?, 'test')",
            id, "CUST-" + id, "INDIVIDUAL", "PASSED", "Ada", "Obi");
        jdbc.update("INSERT INTO policies (id, policy_number, status, customer_id, customer_name, "
            + "product_id, product_name, product_code, product_rate, class_of_business_id, "
            + "class_of_business_name, class_of_business_code, business_type, policy_start_date, "
            + "policy_end_date, total_sum_insured, total_premium, net_premium) "
            + "VALUES (?,?,?,?,?, ?,?,?,?, ?,?,?, ?,?,?, ?,?,?)",
            UUID.randomUUID(), "POL-" + id, "EXPIRED", id, "Ada Obi", UUID.randomUUID(), "Motor",
            "MOTOR", 5.0, UUID.randomUUID(), "Motor", "MOT", "DIRECT",
            LocalDate.now().minusDays(3365), LocalDate.now().minusDays(3000),
            1000000, 50000, 47500);
        return id;
    }

    private void enablePurge(JdbcTemplate jdbc) {
        // Seed the singleton config row: opt-in ON, default window (WEEKLY Sun 03:00 → matches SUNDAY_0300).
        jdbc.update("INSERT INTO data_retention_policy (id, customer_pii_retention_days, purge_enabled, "
            + "purge_frequency, purge_day_of_week, purge_hour_utc, created_at, updated_at, created_by) "
            + "VALUES (?, 2555, true, 'WEEKLY', 0, 3, now(), now(), 'test')", UUID.randomUUID());
    }

    @TestConfiguration
    static class TestSupportConfig {
        @Bean @Primary ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
        @Bean AuditService auditService(AuditLogRepository repo, ObjectMapper mapper) {
            return new AuditService(repo, mapper, mock(ApplicationEventPublisher.class));
        }
        @Bean DocumentStorageService documentStorageService() {
            return mock(DocumentStorageService.class);
        }
    }
}
