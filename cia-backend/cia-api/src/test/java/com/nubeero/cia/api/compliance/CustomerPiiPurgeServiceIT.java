package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.compliance.purge.CustomerPiiPurgeService;
import com.nubeero.cia.compliance.purge.CustomerPurgeRepository;
import com.nubeero.cia.compliance.purge.PurgeAuditWriter;
import com.nubeero.cia.storage.DocumentStorageService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({CiaCommonAutoConfiguration.class, CustomerPurgeRepository.class,
        CustomerPiiPurgeService.class, PurgeAuditWriter.class,
        CustomerPiiPurgeServiceIT.TestSupportConfig.class})
class CustomerPiiPurgeServiceIT extends ComplianceItSupport {

    @Autowired CustomerPiiPurgeService service;
    @Autowired DocumentStorageService storage;   // mock from TestSupportConfig

    @Test
    void anonymizesCustomer_deletesDirectorsAndBlobs_auditsMetadataOnly_idempotent() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, "
            + "first_name, last_name, id_number, id_document_url, address, created_by) VALUES (?,?,?,?,?,?, "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'test')",
            id, "CUST-P", "INDIVIDUAL", "PASSED", "Ada", "Obi",
            "NIN-SECRET", "kyc/ada-id.pdf", "12 Marina St");
        jdbc.update("INSERT INTO customer_directors (id, customer_id, first_name, last_name, "
            + "id_number, id_document_url, kyc_status) VALUES (?,?,?,?, "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'PASSED')",
            UUID.randomUUID(), id, "Bola", "Obi", "NIN-DIR", "kyc/bola-id.pdf");
        jdbc.update("INSERT INTO customer_documents (id, customer_id, document_type, document_name, "
            + "document_path) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), id, "ID_CARD", "nin.pdf", "kyc/nin.pdf");
        UUID policyId = UUID.randomUUID();
        jdbc.update("INSERT INTO policies (id, policy_number, status, customer_id, customer_name, "
            + "product_id, product_name, product_code, product_rate, class_of_business_id, "
            + "class_of_business_name, class_of_business_code, business_type, policy_start_date, "
            + "policy_end_date, total_sum_insured, total_premium, net_premium) "
            + "VALUES (?,?,?,?,?, ?,?,?,?, ?,?,?, ?,?,?, ?,?,?)",
            policyId, "POL-P", "EXPIRED", id, "Ada Obi", UUID.randomUUID(), "Motor", "MOTOR", 5.0,
            UUID.randomUUID(), "Motor", "MOT", "DIRECT",
            java.time.LocalDate.now().minusYears(2), java.time.LocalDate.now().minusYears(1),
            1000000, 50000, 47500);

        int retentionDays = 2555;
        service.purgeCustomer("test-tenant", id, retentionDays);

        var row = jdbc.queryForMap("SELECT first_name, last_name, id_number, address, email, "
            + "pii_purged_at, customer_number FROM customers WHERE id = ?", id);
        assertThat(row.get("first_name")).isEqualTo("[ERASED]");
        assertThat(row.get("id_number")).isNull();
        assertThat(row.get("address")).isNull();
        assertThat(row.get("pii_purged_at")).isNotNull();
        assertThat(row.get("customer_number")).isEqualTo("CUST-P");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_directors WHERE customer_id = ?",
            Long.class, id)).isZero();
        verify(storage).delete("test-tenant", "kyc/ada-id.pdf");
        verify(storage).delete("test-tenant", "kyc/bola-id.pdf");
        verify(storage).delete("test-tenant", "kyc/nin.pdf");

        assertThat(jdbc.queryForObject("SELECT customer_name FROM policies WHERE id = ?",
            String.class, policyId)).isEqualTo("Ada Obi");

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'DELETE'",
            Long.class, id.toString())).isEqualTo(1L);
        String audit = jdbc.queryForObject("SELECT COALESCE(new_value::text,'') || COALESCE(old_value::text,'') "
            + "FROM audit_log WHERE entity_id = ? AND action = 'DELETE'", String.class, id.toString());
        assertThat(audit).doesNotContain("NIN-SECRET").doesNotContain("12 Marina St").doesNotContain("Ada");

        service.purgeCustomer("test-tenant", id, retentionDays);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'DELETE'",
            Long.class, id.toString())).isEqualTo(1L);
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
