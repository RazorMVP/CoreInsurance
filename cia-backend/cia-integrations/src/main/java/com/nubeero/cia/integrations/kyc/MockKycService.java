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
        log.info("[MOCK KYC] verifyIndividual idType={} idNumber={}", request.getIdType(), request.getIdNumber());
        return successResult();
    }

    @Override
    public KycResult verifyCorporate(CorporateKycRequest request) {
        log.info("[MOCK KYC] verifyCorporate rcNumber={}", request.getRcNumber());
        return successResult();
    }

    @Override
    public KycResult verifyDirector(DirectorKycRequest request) {
        log.info("[MOCK KYC] verifyDirector idType={} idNumber={}", request.getIdType(), request.getIdNumber());
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
