package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.compliance.dsar.DsarExportService;
import com.nubeero.cia.compliance.dsar.DsarGatherService;
import com.nubeero.cia.compliance.dsar.DsarJsonRenderer;
import com.nubeero.cia.compliance.dsar.DsarPdfRenderer;
import com.nubeero.cia.documents.HtmlToPdfConverter;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({CiaCommonAutoConfiguration.class, DsarExportService.class, DsarGatherService.class,
        DsarJsonRenderer.class, DsarPdfRenderer.class, HtmlToPdfConverter.class,
        DsarExportServiceIT.TestSupportConfig.class})
class DsarExportServiceIT extends ComplianceItSupport {

    @Autowired DsarExportService service;

    @Test
    void zipBundleContainsBothJsonAndPdf_andWritesMetadataOnlyAudit() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, " +
                "first_name, last_name, " +
                "id_number, address, created_by) VALUES (?,?,?,?,?,?, " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'test')",
                customerId, "CUST-ZIP-1", "INDIVIDUAL", "PASSED", "Ada", "Obi",
                "NIN-SECRET-123", "12 Marina St");

        byte[] zip = service.exportZip("test-tenant", customerId, "dpo-user");

        boolean hasJson = false, hasPdf = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            var e = zin.getNextEntry();
            while (e != null) {
                if (e.getName().endsWith(".json")) hasJson = true;
                if (e.getName().endsWith(".pdf")) hasPdf = true;
                e = zin.getNextEntry();
            }
        }
        assertThat(hasJson).isTrue();
        assertThat(hasPdf).isTrue();

        // exactly one SEND audit row for this customer
        Long audits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'SEND'",
                Long.class, customerId.toString());
        assertThat(audits).isEqualTo(1L);

        // CRITICAL: the audit row must NOT contain the decrypted PII payload (no NIN, no address)
        String auditJson = jdbc.queryForObject(
                "SELECT COALESCE(new_value::text,'') || COALESCE(old_value::text,'') " +
                "FROM audit_log WHERE entity_id = ? AND action = 'SEND'",
                String.class, customerId.toString());
        assertThat(auditJson).doesNotContain("NIN-SECRET-123").doesNotContain("12 Marina St");
    }

    @Test
    void singleFileJsonExport_alsoWritesMetadataOnlyAudit() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, " +
                "first_name, last_name, id_number, created_by) VALUES (?,?,?,?,?,?, " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'test')",
                customerId, "CUST-JSON-1", "INDIVIDUAL", "PASSED", "Ngozi", "Eze", "NIN-JSON-SECRET");

        byte[] jsonBytes = service.renderJson(customerId, "dpo-user");
        assertThat(new String(jsonBytes)).contains("CUST-JSON-1");

        // a single-file (JSON) export is still a full-PII disclosure → audited exactly once, no PII.
        Long audits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'SEND'",
                Long.class, customerId.toString());
        assertThat(audits).isEqualTo(1L);
        String auditJson = jdbc.queryForObject(
                "SELECT COALESCE(new_value::text,'') FROM audit_log WHERE entity_id = ? AND action = 'SEND'",
                String.class, customerId.toString());
        assertThat(auditJson).doesNotContain("NIN-JSON-SECRET");
    }

    /**
     * Wires {@link AuditService} for this {@code @DataJpaTest} slice — mirrors the
     * finance audit ITs (e.g. {@code PaymentReverseAuditIT.TestSupportConfig}).
     * {@code AuditLogRepository} + the {@code AuditLog} entity are discovered by the
     * default {@code @DataJpaTest} scan rooted at {@code CiaApplication}; only the
     * {@code @Service}-layer {@code AuditService} and its {@code ObjectMapper} need
     * explicit beans here.
     */
    @TestConfiguration
    static class TestSupportConfig {

        @Bean
        @Primary
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        AuditService auditService(AuditLogRepository repo, ObjectMapper mapper) {
            return new AuditService(repo, mapper, mock(ApplicationEventPublisher.class));
        }
    }
}
