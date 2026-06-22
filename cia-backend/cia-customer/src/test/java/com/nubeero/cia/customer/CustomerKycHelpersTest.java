package com.nubeero.cia.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.integrations.kyc.KycResult;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure compliance helpers in {@link CustomerService}: ID-expiry
 * validation (NDPR/KYC — expirable ID types must carry an unexpired date), the
 * KYC-outcome → customer-status mapping, and the upload filename-extension helper.
 * Pure (package-private static, no Spring/DB). First tests in {@code cia-customer}
 * ({@code zero-test-modules} backlog).
 */
class CustomerKycHelpersTest {

    // ── validateExpiryDate ─────────────────────────────────────────────────

    @Test
    void expirableIdWithoutExpiry_throwsMissingExpiry() {
        assertThatThrownBy(() -> CustomerService.validateExpiryDate(IdType.PASSPORT, null, "Passport"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrorCode()).isEqualTo("MISSING_EXPIRY_DATE"));
    }

    @Test
    void expirableIdAlreadyExpired_throwsExpired() {
        assertThatThrownBy(() -> CustomerService.validateExpiryDate(
                IdType.DRIVERS_LICENSE, LocalDate.now().minusDays(1), "Licence"))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getErrorCode()).isEqualTo("EXPIRED_ID_DOCUMENT"));
    }

    @Test
    void expirableIdWithFutureExpiry_passes() {
        assertThatCode(() -> CustomerService.validateExpiryDate(
                IdType.PASSPORT, LocalDate.now().plusYears(1), "Passport")).doesNotThrowAnyException();
    }

    @Test
    void nonExpirableIdType_neverRequiresExpiry() {
        // NIN / Voter's card don't expire — a null expiry is fine.
        assertThatCode(() -> CustomerService.validateExpiryDate(IdType.NIN, null, "NIN"))
                .doesNotThrowAnyException();
    }

    // ── applyKycResult ─────────────────────────────────────────────────────

    @Test
    void verifiedResult_setsPassedWithVerifiedAtAndRef() {
        Customer customer = Customer.builder().build();
        applyAndAssert(customer, KycResult.builder().verified(true).verificationId("ref-1").build());

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.PASSED);
        assertThat(customer.getKycVerifiedAt()).isNotNull();
        assertThat(customer.getKycProviderRef()).isEqualTo("ref-1");
        assertThat(customer.getKycFailureReason()).isNull();
    }

    @Test
    void unverifiedResult_setsFailedWithReason() {
        Customer customer = Customer.builder().build();
        applyAndAssert(customer, KycResult.builder()
                .verified(false).failureReason("ID not found").verificationId("ref-2").build());

        assertThat(customer.getKycStatus()).isEqualTo(KycStatus.FAILED);
        assertThat(customer.getKycFailureReason()).isEqualTo("ID not found");
        assertThat(customer.getKycProviderRef()).isEqualTo("ref-2");
    }

    private static void applyAndAssert(Customer customer, KycResult result) {
        CustomerService.applyKycResult(customer, result);
    }

    // ── getExtension ───────────────────────────────────────────────────────

    @Test
    void getExtension_returnsDottedSuffix_orEmpty() {
        assertThat(CustomerService.getExtension("passport.pdf")).isEqualTo(".pdf");
        assertThat(CustomerService.getExtension("archive.tar.gz")).isEqualTo(".gz");
        assertThat(CustomerService.getExtension("noextension")).isEmpty();
        assertThat(CustomerService.getExtension(null)).isEmpty();
    }
}
