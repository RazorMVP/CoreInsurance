package com.nubeero.cia.integrations.kyc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "cia.kyc.provider", havingValue = "dojah")
public class DojahKycService implements KycVerificationService {

    @Value("${cia.kyc.provider-url}")
    private String providerUrl;

    @Override
    public KycResult verifyIndividual(IndividualKycRequest request) {
        throw new UnsupportedOperationException("Dojah KYC integration not yet implemented");
    }

    @Override
    public KycResult verifyCorporate(CorporateKycRequest request) {
        throw new UnsupportedOperationException("Dojah KYC integration not yet implemented");
    }

    @Override
    public KycResult verifyDirector(DirectorKycRequest request) {
        throw new UnsupportedOperationException("Dojah KYC integration not yet implemented");
    }
}
