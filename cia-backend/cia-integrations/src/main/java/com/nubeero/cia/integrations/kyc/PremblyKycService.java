package com.nubeero.cia.integrations.kyc;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "cia.kyc.provider", havingValue = "prembly")
public class PremblyKycService implements KycVerificationService {

    @Value("${cia.kyc.provider-url}")
    private String providerUrl;

    @PostConstruct
    public void failUntilLiveIntegrationIsImplemented() {
        throw new IllegalStateException(
                "Prembly KYC live integration is not implemented yet. " +
                "Do not enable KYC_PROVIDER=prembly until go-live provider contract work is complete."
        );
    }

    @Override
    public KycResult verifyIndividual(IndividualKycRequest request) {
        throw new UnsupportedOperationException("Prembly KYC integration not yet implemented");
    }

    @Override
    public KycResult verifyCorporate(CorporateKycRequest request) {
        throw new UnsupportedOperationException("Prembly KYC integration not yet implemented");
    }

    @Override
    public KycResult verifyDirector(DirectorKycRequest request) {
        throw new UnsupportedOperationException("Prembly KYC integration not yet implemented");
    }
}
