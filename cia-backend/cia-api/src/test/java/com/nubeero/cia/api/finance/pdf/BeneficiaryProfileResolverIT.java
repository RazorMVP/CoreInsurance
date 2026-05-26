package com.nubeero.cia.api.finance.pdf;

import com.nubeero.cia.api.finance.FinanceItSupport;
import com.nubeero.cia.finance.CreditNote;
import com.nubeero.cia.finance.CreditNoteStatus;
import com.nubeero.cia.finance.FinanceEntityType;
import com.nubeero.cia.finance.pdf.BeneficiaryProfile;
import com.nubeero.cia.finance.pdf.BeneficiaryProfileResolverDispatcher;
import com.nubeero.cia.finance.pdf.ClaimBeneficiaryProfileResolver;
import com.nubeero.cia.finance.pdf.CommissionBeneficiaryProfileResolver;
import com.nubeero.cia.finance.pdf.EndorsementRefundBeneficiaryProfileResolver;
import com.nubeero.cia.finance.pdf.FacOutwardBeneficiaryProfileResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link BeneficiaryProfileResolverDispatcher} routing + per-resolver
 * behaviour for all 4 entity types plus the unmapped-type fallback + the
 * missing-referenced-entity fallback for CLAIM.
 *
 * <p>Customer.address is encrypted via {@code pgp_sym_encrypt} at insert,
 * decrypted via JPA {@code @ColumnTransformer} on read — verifying this
 * end-to-end is the key non-trivial test (other resolvers use plain columns).
 *
 * <p>The Hikari pool's {@code connection-init-sql} (see application.yml line 18)
 * sets {@code app.pii_key} on every pooled connection, so both JdbcTemplate
 * INSERTs and Hibernate session reads inherit the same key — no manual SET
 * needed in the test.
 *
 * @since Slice β — Task 9, F7 payment-voucher PDF generation
 */
@Import({
    com.nubeero.cia.common.config.CiaCommonAutoConfiguration.class,
    BeneficiaryProfileResolverDispatcher.class,
    ClaimBeneficiaryProfileResolver.class,
    CommissionBeneficiaryProfileResolver.class,
    FacOutwardBeneficiaryProfileResolver.class,
    EndorsementRefundBeneficiaryProfileResolver.class
})
class BeneficiaryProfileResolverIT extends FinanceItSupport {

    @Autowired BeneficiaryProfileResolverDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("CLAIM credit note resolves to claimant Customer (address decrypted via JPA @ColumnTransformer)")
    void claimResolverDecryptsCustomerAddress() {
        UUID customerId = seedIndividualCustomer("Adaeze", "Okonkwo", "12 Marina St, Lagos");
        UUID policyId   = seedPolicy(customerId, "POL-IT-CLM-001");
        UUID claimId    = seedClaim(policyId, customerId, "CLM-IT-001");
        CreditNote cn   = creditNote(FinanceEntityType.CLAIM, claimId, customerId, "Adaeze Okonkwo");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Adaeze Okonkwo");
        assertThat(profile.addressLine1())
            .as("Customer.address is encrypted at rest; JPA @ColumnTransformer decrypts on read")
            .isEqualTo("12 Marina St, Lagos");
    }

    @Test
    @DisplayName("COMMISSION credit note resolves to Broker when beneficiaryId is a broker")
    void commissionResolverFindsBroker() {
        UUID brokerId = seedBroker("ABC Brokers Ltd", "5 Allen Avenue, Ikeja");
        CreditNote cn = creditNote(FinanceEntityType.COMMISSION, UUID.randomUUID(), brokerId, "ABC Brokers Ltd");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("ABC Brokers Ltd");
        assertThat(profile.addressLine1()).isEqualTo("5 Allen Avenue, Ikeja");
    }

    @Test
    @DisplayName("COMMISSION credit note falls back to Agent when broker lookup misses")
    void commissionResolverFallsBackToAgent() {
        UUID agentId = seedAgent("Tunde Adetayo", "7 Adeola Odeku, V.I.");
        CreditNote cn = creditNote(FinanceEntityType.COMMISSION, UUID.randomUUID(), agentId, "Tunde Adetayo");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Tunde Adetayo");
        assertThat(profile.addressLine1()).isEqualTo("7 Adeola Odeku, V.I.");
    }

    @Test
    @DisplayName("REINSURANCE credit note resolves to ReinsuranceCompany")
    void facOutwardResolverFindsReinsurer() {
        UUID rId = seedReinsurer("Africa Re", "Plot 1, Africa Re Building, Lagos");
        CreditNote cn = creditNote(FinanceEntityType.REINSURANCE, UUID.randomUUID(), rId, "Africa Re");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Africa Re");
        assertThat(profile.addressLine1()).isEqualTo("Plot 1, Africa Re Building, Lagos");
    }

    @Test
    @DisplayName("ENDORSEMENT credit note resolves via direct Endorsement→Customer hop (denormalised customerId; decrypted address)")
    void endorsementRefundResolverFindsCustomer() {
        UUID customerId = seedIndividualCustomer("Chinwe", "Nwafor", "3 Maitama Ext, Abuja");
        UUID policyId   = seedPolicy(customerId, "POL-IT-END-001");
        UUID endId      = seedEndorsement(policyId, customerId, "END-IT-001");
        CreditNote cn   = creditNote(FinanceEntityType.ENDORSEMENT, endId, customerId, "Chinwe Nwafor");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Chinwe Nwafor");
        assertThat(profile.addressLine1()).isEqualTo("3 Maitama Ext, Abuja");
    }

    @Test
    @DisplayName("POLICY entity type (unmapped) falls back to denormalised beneficiaryName")
    void unmappedPolicyEntityTypeFallsBack() {
        CreditNote cn = creditNote(FinanceEntityType.POLICY, UUID.randomUUID(), UUID.randomUUID(), "Some Beneficiary");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Some Beneficiary");
        assertThat(profile.addressLine1()).isNull();
    }

    @Test
    @DisplayName("CLAIM_EXPENSE entity type (unmapped) falls back to denormalised beneficiaryName")
    void claimExpenseFallsBack() {
        CreditNote cn = creditNote(FinanceEntityType.CLAIM_EXPENSE, UUID.randomUUID(), UUID.randomUUID(), "Inspection Surveyor");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        assertThat(profile.name()).isEqualTo("Inspection Surveyor");
        assertThat(profile.addressLine1()).isNull();
    }

    @Test
    @DisplayName("CLAIM with missing customer falls back to claim's denormalised customerName")
    void claimResolverWithMissingCustomerFallsBack() {
        UUID nonexistentCustomerId = UUID.randomUUID();
        UUID policyId = seedPolicy(nonexistentCustomerId, "POL-IT-CLM-002");
        UUID claimId  = seedClaim(policyId, nonexistentCustomerId, "CLM-IT-002");
        CreditNote cn = creditNote(FinanceEntityType.CLAIM, claimId, UUID.randomUUID(), "Should Not Be Used");

        BeneficiaryProfile profile = dispatcher.resolve(cn);

        // Resolver returns BeneficiaryProfile.nameOnly(claim.customerName) — see ClaimResolver.
        assertThat(profile.name()).startsWith("Test Customer for CLM-");
        assertThat(profile.addressLine1()).isNull();
    }

    // ── Fixture helpers ────────────────────────────────────────────────────

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

    /**
     * Seeds a minimal policy row to satisfy the {@code claims.policy_id}
     * and {@code endorsements.policy_id} FKs. All product / class snapshot
     * fields are synthetic UUIDs / placeholder strings — none of them
     * impact the resolver under test.
     */
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

    private UUID seedAgent(String name, String address) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO agents (id, code, type, name, address, email, created_by) " +
            "VALUES (?, ?, 'INDIVIDUAL', ?, ?, ?, 'test')",
            id, "AGT-" + id.toString().substring(0, 6), name, address, "agent@test.local"
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

    private CreditNote creditNote(FinanceEntityType type, UUID entityId, UUID benId, String benName) {
        CreditNote cn = new CreditNote();
        cn.setCreditNoteNumber("CN-IT-" + UUID.randomUUID().toString().substring(0, 8));
        cn.setStatus(CreditNoteStatus.OUTSTANDING);
        cn.setEntityType(type);
        cn.setEntityId(entityId);
        cn.setEntityReference("REF-" + type.name());
        cn.setBeneficiaryId(benId);
        cn.setBeneficiaryName(benName);
        cn.setDescription("IT fixture credit note");
        cn.setAmount(new BigDecimal("500000.00"));
        cn.setTotalAmount(new BigDecimal("500000.00"));
        return cn;
    }
}
