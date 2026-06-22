package com.nubeero.cia.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.integrations.kyc.CorporateKycRequest;
import com.nubeero.cia.integrations.kyc.DirectorKycRequest;
import com.nubeero.cia.integrations.kyc.IndividualKycRequest;
import com.nubeero.cia.integrations.kyc.KycResult;
import com.nubeero.cia.integrations.kyc.MockKycService;
import com.nubeero.cia.integrations.naicom.NaicomUploadRequest;
import com.nubeero.cia.integrations.naicom.NaicomUploadResult;
import com.nubeero.cia.integrations.naicom.StubNaicomService;
import com.nubeero.cia.integrations.niid.NiidUploadRequest;
import com.nubeero.cia.integrations.niid.NiidUploadResult;
import com.nubeero.cia.integrations.niid.StubNiidService;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the default integration adapters — the beans that are
 * actually active out of the box ({@code @ConditionalOnProperty(matchIfMissing
 * = true)}): the NAICOM/NIID upload stubs and the mock KYC provider. These are
 * the launch-safe fallbacks, so their success contract (always-succeed with a
 * recognisable stub id) is what onboarding + post-approval upload rely on until
 * live providers are wired. First tests in {@code cia-integrations}
 * ({@code zero-test-modules} backlog).
 */
class StubIntegrationServicesTest {

    @Test
    void stubNaicom_uploadPolicy_succeedsWithStubUid() {
        NaicomUploadResult result = new StubNaicomService()
                .uploadPolicy(NaicomUploadRequest.builder().policyNumber("POL-001").build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getNaicomUid()).startsWith("NAICOM-STUB-");
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void stubNiid_uploadPolicy_succeedsWithStubRef() {
        NiidUploadResult result = new StubNiidService()
                .uploadPolicy(NiidUploadRequest.builder().policyNumber("POL-002").vehicleRegNumber("ABC-123").build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getNiidRef()).startsWith("NIID-STUB-");
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void mockKyc_individual_isVerifiedWithId() {
        KycResult result = new MockKycService()
                .verifyIndividual(IndividualKycRequest.builder().idType("NIN").idNumber("12345678901").build());

        assertThat(result.isVerified()).isTrue();
        assertThat(result.getVerificationId()).isNotBlank();
        assertThat(result.getVerifiedAt()).isNotNull();
        assertThat(result.getFailureReason()).isNull();
    }

    @Test
    void mockKyc_corporate_isVerified() {
        KycResult result = new MockKycService()
                .verifyCorporate(CorporateKycRequest.builder().rcNumber("RC-999").companyName("Acme Ltd").build());
        assertThat(result.isVerified()).isTrue();
        assertThat(result.getVerificationId()).isNotBlank();
    }

    @Test
    void mockKyc_director_isVerified() {
        KycResult result = new MockKycService()
                .verifyDirector(DirectorKycRequest.builder().idType("PASSPORT").idNumber("A0123456").build());
        assertThat(result.isVerified()).isTrue();
        assertThat(result.getVerificationId()).isNotBlank();
    }
}
