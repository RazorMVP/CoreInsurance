package com.nubeero.cia.integrations.kyc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@Profile("dev | test")
public class MockKycService implements KycVerificationService {

    @Override
    public KycResult verifyIndividual(IndividualKycRequest request) {
        log.info("[MOCK KYC] verifyIndividual idType={}", request.getIdType());
        return successResult();
    }

    @Override
    public KycResult verifyCorporate(CorporateKycRequest request) {
        log.info("[MOCK KYC] verifyCorporate companyName={}", request.getCompanyName());
        return successResult();
    }

    @Override
    public KycResult verifyDirector(DirectorKycRequest request) {
        log.info("[MOCK KYC] verifyDirector idType={}", request.getIdType());
        return successResult();
    }

    private KycResult successResult() {
        return KycResult.builder()
                .verified(true)
                .verificationId(UUID.randomUUID().toString())
                .verifiedAt(Instant.now())
                .build();
    }
}
