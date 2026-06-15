package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.compliance.dsar.DsarExport;
import com.nubeero.cia.compliance.dsar.DsarGatherService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({CiaCommonAutoConfiguration.class, DsarGatherService.class})
class DsarGatherServiceIT extends ComplianceItSupport {

    @Autowired DsarGatherService gather;

    @Test
    void gathers_decryptedCustomer_directors_documents_and_relatedRecords() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, " +
                "first_name, last_name, email, phone, " +
                "id_number, id_document_url, address, created_by) VALUES (?,?,?,?,?,?,?,?, " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'test')",
                customerId, "CUST-DSAR-1", "INDIVIDUAL", "PASSED",
                "Ada", "Obi", "ada@test.local", "08030000000",
                "NIN12345678901", "kyc/2026/ada-id.pdf", "12 Marina St, Lagos");

        UUID directorId = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_directors (id, customer_id, first_name, last_name, " +
                "id_number, id_document_url, kyc_status) VALUES (?,?,?,?, " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'PASSED')",
                directorId, customerId, "Bola", "Obi", "NIN99999999999", "kyc/2026/bola-id.pdf");

        jdbc.update("INSERT INTO customer_documents (id, customer_id, document_type, document_name, " +
                "document_path) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), customerId, "ID_CARD", "nin.pdf", "kyc/2026/nin.pdf");

        jdbc.update("INSERT INTO policies (id, policy_number, status, customer_id, customer_name, " +
                "product_id, product_name, product_code, product_rate, " +
                "class_of_business_id, class_of_business_name, class_of_business_code, " +
                "business_type, policy_start_date, policy_end_date, " +
                "total_sum_insured, total_premium, net_premium) " +
                "VALUES (?,?,?,?,?, ?,?,?,?, ?,?,?, ?,?,?, ?,?,?)",
                UUID.randomUUID(), "POL-DSAR-1", "ACTIVE", customerId, "Ada Obi",
                UUID.randomUUID(), "Motor Comprehensive", "MOTOR", 5.0,
                UUID.randomUUID(), "Motor", "MOT",
                "DIRECT", java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31),
                1000000, 50000, 47500);

        // debit_note (customer_id) + receipt joined via debit_note_id — guards the receipts join.
        UUID dnId = UUID.randomUUID();
        jdbc.update("INSERT INTO debit_notes (id, debit_note_number, status, entity_type, entity_id, " +
                "entity_reference, customer_id, customer_name, description, amount, tax_amount, " +
                "total_amount, paid_amount, currency_code) VALUES (?,?,?,?,?, ?,?,?,?,?, ?,?,?,?)",
                dnId, "DN-DSAR-1", "POSTED", "POLICY", UUID.randomUUID(),
                "POL-DSAR-1", customerId, "Ada Obi", "Premium", 47500, 0, 47500, 0, "NGN");
        jdbc.update("INSERT INTO receipts (id, receipt_number, debit_note_id, amount, payment_date, " +
                "payment_method, status) VALUES (?,?,?,?,?, ?,?)",
                UUID.randomUUID(), "RCT-DSAR-1", dnId, 47500,
                java.time.LocalDate.of(2026, 2, 1), "TRANSFER", "POSTED");

        // credit_note with beneficiary_id = the customer + payment joined via credit_note_id —
        // guards the non-obvious beneficiary-linked payables path (credit_notes has no customer_id).
        UUID cnId = UUID.randomUUID();
        jdbc.update("INSERT INTO credit_notes (id, credit_note_number, status, entity_type, entity_id, " +
                "entity_reference, beneficiary_id, beneficiary_name, description, amount, tax_amount, " +
                "total_amount, paid_amount, currency_code) VALUES (?,?,?,?,?, ?,?,?,?,?, ?,?,?,?)",
                cnId, "CN-DSAR-1", "POSTED", "CLAIM", UUID.randomUUID(),
                "CLM-DSAR-1", customerId, "Ada Obi", "Claim settlement", 200000, 0, 200000, 0, "NGN");
        jdbc.update("INSERT INTO payments (id, payment_number, credit_note_id, amount, payment_date, " +
                "payment_method, status) VALUES (?,?,?,?,?, ?,?)",
                UUID.randomUUID(), "PMT-DSAR-1", cnId, 200000,
                java.time.LocalDate.of(2026, 7, 1), "TRANSFER", "POSTED");

        DsarExport export = gather.gather(customerId);

        assertThat(export.customerNumber()).isEqualTo("CUST-DSAR-1");
        assertThat(export.customer().get("id_number")).isEqualTo("NIN12345678901");      // decrypted
        assertThat(export.customer().get("id_document_url")).isEqualTo("kyc/2026/ada-id.pdf"); // decrypted
        assertThat(export.customer().get("address")).isEqualTo("12 Marina St, Lagos");   // decrypted
        assertThat(export.directors()).hasSize(1);
        assertThat(export.directors().get(0).get("id_number")).isEqualTo("NIN99999999999"); // decrypted
        assertThat(export.directors().get(0).get("id_document_url")).isEqualTo("kyc/2026/bola-id.pdf"); // decrypted
        assertThat(export.documents()).hasSize(1);
        assertThat(export.documents().get(0).get("document_path")).isEqualTo("kyc/2026/nin.pdf");
        assertThat(export.policies()).hasSize(1);
        assertThat(export.policies().get(0).get("policy_number")).isEqualTo("POL-DSAR-1");
        // finance linkages (the non-obvious joins)
        assertThat(export.debitNotes()).hasSize(1);
        assertThat(export.receipts()).hasSize(1);
        assertThat(export.receipts().get(0).get("receipt_number")).isEqualTo("RCT-DSAR-1");
        assertThat(export.creditNotes()).hasSize(1);   // beneficiary-linked payables
        assertThat(export.payments()).hasSize(1);       // joined via credit_note (beneficiary_id)
        assertThat(export.payments().get(0).get("payment_number")).isEqualTo("PMT-DSAR-1");
    }
}
