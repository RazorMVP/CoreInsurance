package com.nubeero.cia.integrations.kyc;

public interface KycVerificationService {
    KycResult verifyIndividual(IndividualKycRequest request);
    KycResult verifyCorporate(CorporateKycRequest request);
    KycResult verifyDirector(DirectorKycRequest request);
}
