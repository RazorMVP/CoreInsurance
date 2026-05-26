package com.nubeero.cia.api.finance.pdf;

import com.nubeero.cia.api.finance.FinanceItSupport;
import com.nubeero.cia.documents.HtmlToPdfConverter;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.CreditNoteStatus;
import com.nubeero.cia.finance.FinanceEntityType;
import com.nubeero.cia.finance.Payment;
import com.nubeero.cia.finance.PaymentMethod;
import com.nubeero.cia.finance.TransactionStatus;
import com.nubeero.cia.finance.pdf.BeneficiaryProfileResolverDispatcher;
import com.nubeero.cia.finance.pdf.ClaimBeneficiaryProfileResolver;
import com.nubeero.cia.finance.pdf.CommissionBeneficiaryProfileResolver;
import com.nubeero.cia.finance.pdf.EndorsementRefundBeneficiaryProfileResolver;
import com.nubeero.cia.finance.pdf.FacOutwardBeneficiaryProfileResolver;
import com.nubeero.cia.finance.pdf.PaymentVoucherPdfGenerator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.thymeleaf.ThymeleafAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link PaymentVoucherPdfGenerator} contract across all 4 source types
 * routed by {@link BeneficiaryProfileResolverDispatcher}:
 * <ul>
 *   <li>CLAIM        → "CLAIM SETTLEMENT VOUCHER"   + Customer name + decrypted address</li>
 *   <li>COMMISSION   → "COMMISSION VOUCHER"         + Broker name + address</li>
 *   <li>REINSURANCE  → "FAC PREMIUM VOUCHER"        + Reinsurer name + address</li>
 *   <li>ENDORSEMENT  → "ENDORSEMENT REFUND VOUCHER" + Customer name + decrypted address</li>
 * </ul>
 *
 * <p>Customer.address (CLAIM + ENDORSEMENT cases) is encrypted at rest via
 * pgp_sym_encrypt and decrypted by Hibernate {@code @ColumnTransformer} on
 * read — these two tests verify the encryption round-trip end-to-end through
 * the generator.
 *
 * @since Slice β — Task 13, F7 receipt + payment-voucher PDF generation
 */
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    ThymeleafAutoConfiguration.class,
    HtmlToPdfConverter.class,
    BeneficiaryProfileResolverDispatcher.class,
    ClaimBeneficiaryProfileResolver.class,
    CommissionBeneficiaryProfileResolver.class,
    FacOutwardBeneficiaryProfileResolver.class,
    EndorsementRefundBeneficiaryProfileResolver.class,
    PaymentVoucherPdfGenerator.class
})
class PaymentVoucherPdfGeneratorIT extends FinanceItSupport {

    @Autowired PaymentVoucherPdfGenerator generator;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("CLAIM source → 'CLAIM SETTLEMENT VOUCHER' header + Customer name + decrypted address")
    void claimSourceRenders() throws Exception {
        UUID customerId = seedIndividualCustomer("Adaeze", "Okonkwo", "12 Marina St, Lagos");
        UUID policyId   = seedPolicy(customerId, "POL-IT-VCH-CLM-001");
        UUID claimId    = seedClaim(policyId, customerId, "CLM-IT-VCH-001");
        Payment p = samplePayment(FinanceEntityType.CLAIM, claimId, customerId, "Adaeze Okonkwo");

        byte[] pdf = generator.generate(p);
        assertThat(pdf).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .contains("CLAIM SETTLEMENT VOUCHER")
                .contains("Adaeze Okonkwo")
                .contains("12 Marina St, Lagos")
                .contains("₦");
        }
    }

    @Test
    @DisplayName("COMMISSION source → 'COMMISSION VOUCHER' header + Broker name + address")
    void commissionSourceRenders() throws Exception {
        UUID brokerId = seedBroker("ABC Brokers Ltd", "5 Allen Avenue, Ikeja");
        Payment p = samplePayment(FinanceEntityType.COMMISSION, UUID.randomUUID(), brokerId, "ABC Brokers Ltd");

        byte[] pdf = generator.generate(p);
        assertThat(pdf).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .contains("COMMISSION VOUCHER")
                .contains("ABC Brokers Ltd")
                .contains("5 Allen Avenue, Ikeja")
                .contains("₦");
        }
    }

    @Test
    @DisplayName("REINSURANCE source → 'FAC PREMIUM VOUCHER' header + Reinsurer name + address")
    void reinsuranceSourceRenders() throws Exception {
        UUID rId = seedReinsurer("Africa Re", "Plot 1, Africa Re Building, Lagos");
        Payment p = samplePayment(FinanceEntityType.REINSURANCE, UUID.randomUUID(), rId, "Africa Re");

        byte[] pdf = generator.generate(p);
        assertThat(pdf).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .contains("FAC PREMIUM VOUCHER")
                .contains("Africa Re")
                .contains("Plot 1, Africa Re Building, Lagos")
                .contains("₦");
        }
    }

    @Test
    @DisplayName("ENDORSEMENT source → 'ENDORSEMENT REFUND VOUCHER' header + Customer name + decrypted address")
    void endorsementSourceRenders() throws Exception {
        UUID customerId = seedIndividualCustomer("Chinwe", "Nwafor", "3 Maitama Ext, Abuja");
        UUID policyId   = seedPolicy(customerId, "POL-IT-VCH-END-001");
        UUID endId      = seedEndorsement(policyId, customerId, "END-IT-VCH-001");
        Payment p = samplePayment(FinanceEntityType.ENDORSEMENT, endId, customerId, "Chinwe Nwafor");

        byte[] pdf = generator.generate(p);
        assertThat(pdf).isNotEmpty();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text)
                .contains("ENDORSEMENT REFUND VOUCHER")
                .contains("Chinwe Nwafor")
                .contains("3 Maitama Ext, Abuja")
                .contains("₦");
        }
    }

    // ── Fixture helpers (copied verbatim from BeneficiaryProfileResolverIT —
    //    these INSERTs are pinned to the production schema and have already
    //    been debugged in Task 9). ─────────────────────────────────────────

    private UUID seedIndividualCustomer(String firstName, String lastName, String plainAddress) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO customers " +
            "  (id, customer_number, customer_type, kyc_status, first_name, last_name, " +
            "   email, address, created_by) " +
            "VALUES (?, ?, 'INDIVIDUAL', 'PASSED', ?, ?, ?, " +
            "        pgp_sym_encrypt(?, current_setting('app.pii_key')), 'test')",
            id, "CUST-IT-" + id.toString().substring(0, 6),
            firstName, lastName,
            firstName.toLowerCase() + "@test.local",
            plainAddress
        );
        return id;
    }

    private UUID seedPolicy(UUID customerId, String policyNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO policies " +
            "  (id, policy_number, status, customer_id, customer_name, " +
            "   product_id, product_name, product_code, product_rate, " +
            "   class_of_business_id, class_of_business_name, class_of_business_code, " +
            "   policy_start_date, policy_end_date, created_by) " +
            "VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'test')",
            id, policyNumber, customerId, "Test Customer",
            UUID.randomUUID(), "Test Product", "TPRD", new BigDecimal("0.0125"),
            UUID.randomUUID(), "Test Class", "TCOB",
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)
        );
        return id;
    }

    private UUID seedClaim(UUID policyId, UUID customerId, String claimNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO claims " +
            "  (id, claim_number, status, " +
            "   policy_id, policy_number, policy_start_date, policy_end_date, " +
            "   customer_id, customer_name, " +
            "   product_id, product_name, " +
            "   class_of_business_id, class_of_business_name, " +
            "   incident_date, reported_date, description, " +
            "   currency_code, created_by) " +
            "VALUES (?, ?, 'REGISTERED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NGN', 'test')",
            id, claimNumber,
            policyId, "POL-FOR-" + claimNumber,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
            customerId, "Test Customer for " + claimNumber,
            UUID.randomUUID(), "Test Product",
            UUID.randomUUID(), "Test Class",
            LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 16), "Test loss"
        );
        return id;
    }

    private UUID seedBroker(String name, String address) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO brokers (id, code, name, address, email, created_by) " +
            "VALUES (?, ?, ?, ?, ?, 'test')",
            id, "BRK-" + id.toString().substring(0, 6), name, address, "broker@test.local"
        );
        return id;
    }

    private UUID seedReinsurer(String name, String address) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO reinsurance_companies (id, name, address, email, created_by) " +
            "VALUES (?, ?, ?, ?, 'test')",
            id, name, address, "reins@test.local"
        );
        return id;
    }

    private UUID seedEndorsement(UUID policyId, UUID customerId, String endorsementNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO endorsements " +
            "  (id, endorsement_number, status, endorsement_type, " +
            "   policy_id, policy_number, " +
            "   customer_id, customer_name, " +
            "   product_id, product_name, product_code, product_rate, " +
            "   class_of_business_id, class_of_business_name, " +
            "   effective_date, policy_end_date, " +
            "   description, currency_code, created_by) " +
            "VALUES (?, ?, 'APPROVED', 'RETURN_PREMIUM', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NGN', 'test')",
            id, endorsementNumber,
            policyId, "POL-IT-" + endorsementNumber,
            customerId, "Customer for " + endorsementNumber,
            UUID.randomUUID(), "Test Product", "TPRD", new BigDecimal("0.0125"),
            UUID.randomUUID(), "Test Class",
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31),
            "Test endorsement"
        );
        return id;
    }

    private Payment samplePayment(FinanceEntityType type, UUID entityId, UUID benId, String benName) {
        CreditNote cn = new CreditNote();
        cn.setCreditNoteNumber("CN-IT-VCH-" + UUID.randomUUID().toString().substring(0, 8));
        cn.setStatus(CreditNoteStatus.OUTSTANDING);
        cn.setEntityType(type);
        cn.setEntityId(entityId);
        cn.setEntityReference("REF-" + type.name());
        cn.setBeneficiaryId(benId);
        cn.setBeneficiaryName(benName);
        cn.setDescription("IT fixture credit note");
        cn.setAmount(new BigDecimal("750000.00"));
        cn.setTotalAmount(new BigDecimal("750000.00"));

        Payment p = new Payment();
        p.setPaymentNumber("PAY-IT-VCH-" + UUID.randomUUID().toString().substring(0, 6));
        p.setCreditNote(cn);
        p.setAmount(new BigDecimal("750000.00"));
        p.setPaymentDate(LocalDate.now());
        p.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        p.setStatus(TransactionStatus.POSTED);
        p.setNarration("IT fixture payment");
        p.setPostedBy("alice");
        return p;
    }
}
