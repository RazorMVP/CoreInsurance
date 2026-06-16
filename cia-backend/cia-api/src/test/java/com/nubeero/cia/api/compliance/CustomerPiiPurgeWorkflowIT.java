package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.compliance.purge.CompliancePurgeActivitiesImpl;
import com.nubeero.cia.compliance.purge.CustomerPiiPurgeWorkflow;
import com.nubeero.cia.compliance.purge.CustomerPiiPurgeWorkflowImpl;
import com.nubeero.cia.compliance.purge.CustomerPiiPurgeService;
import com.nubeero.cia.compliance.purge.CustomerPurgeRepository;
import com.nubeero.cia.compliance.purge.PurgeAuditWriter;
import com.nubeero.cia.compliance.retention.RetentionPolicyService;
import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Import({CiaCommonAutoConfiguration.class, CustomerPurgeRepository.class, CustomerPiiPurgeService.class,
        PurgeAuditWriter.class, RetentionPolicyService.class, CompliancePurgeActivitiesImpl.class,
        CustomerPiiPurgeWorkflowIT.TestSupportConfig.class})
class CustomerPiiPurgeWorkflowIT extends ComplianceItSupport {

    @Autowired CompliancePurgeActivitiesImpl activities;
    private TestWorkflowEnvironment env;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TemporalQueues.COMPLIANCE_QUEUE);
        worker.registerWorkflowImplementationTypes(CustomerPiiPurgeWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
        // The @Transactional(NOT_SUPPORTED) test below commits its seed rows (so the Temporal worker
        // thread, on a separate pooled connection, can see them). @DataJpaTest's auto-rollback does not
        // cover committed rows, so clean them up explicitly to keep sibling ITs isolated.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM policies");
        jdbc.update("DELETE FROM customers");
        jdbc.update("DELETE FROM data_retention_policy");
        jdbc.update("DELETE FROM public.tenants WHERE schema_name = 'public'");
    }

    // Runs OUTSIDE @DataJpaTest's rollback transaction (NOT_SUPPORTED). The workflow dispatches the
    // purge through a Temporal worker thread on a different pooled connection, which can only see the
    // seeded rows if they are committed — hence no surrounding test transaction. @AfterEach cleans up.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sweep_purgesEligibleCustomerInActiveTenant() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // The activity's listActiveTenants() reads public.tenants; in the single-schema @DataJpaTest
        // harness "public" IS the schema holding the seeded data, so register it as the active tenant.
        jdbc.update("INSERT INTO public.tenants (id, schema_name, name, subdomain, active) "
            + "VALUES (?, 'public', 'Test', 'test', TRUE) ON CONFLICT DO NOTHING", UUID.randomUUID());
        UUID elig = seedEligibleCustomer(jdbc);
        enablePurgeMatchingNow(jdbc);

        CustomerPiiPurgeWorkflow wf = client.newWorkflowStub(CustomerPiiPurgeWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(TemporalQueues.COMPLIANCE_QUEUE)
                .setWorkflowId("purge-it-" + elig).build());
        wf.purge();

        assertThat(jdbc.queryForObject("SELECT pii_purged_at FROM customers WHERE id = ?",
            Object.class, elig)).isNotNull();
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

    /** Seed the singleton config with opt-in ON and the window set to the CURRENT UTC hour+day. */
    private void enablePurgeMatchingNow(JdbcTemplate jdbc) {
        ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
        int hour = nowUtc.getHour();
        int configDow = nowUtc.getDayOfWeek().getValue() % 7;  // SUN(7)->0 .. SAT(6)->6
        jdbc.update("INSERT INTO data_retention_policy (id, customer_pii_retention_days, purge_enabled, "
            + "purge_frequency, purge_day_of_week, purge_hour_utc, created_at, updated_at, created_by) "
            + "VALUES (?, 2555, true, 'WEEKLY', ?, ?, now(), now(), 'test')",
            UUID.randomUUID(), configDow, hour);
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
