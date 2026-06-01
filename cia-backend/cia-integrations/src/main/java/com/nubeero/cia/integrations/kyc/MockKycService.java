package com.nubeero.cia.integrations.kyc;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Default / stub KYC provider — the active {@link KycVerificationService} when
 * {@code cia.kyc.provider} is {@code mock} or unset ({@code matchIfMissing=true}).
 *
 * <p>This is the launch-safe fallback, mirroring {@code StubNaicomService} /
 * {@code StubNiidService}: with no live provider configured, customer onboarding
 * still completes rather than failing on a missing bean (KYC is a hard
 * constructor dependency of {@code CustomerService}). It returns a successful
 * verification so onboarding is not blocked while a real provider
 * ({@code dojah} / {@code prembly}) is pending credentials.
 *
 * <p><b>Compliance note:</b> a {@code mock} result is NOT real identity
 * verification — it attests nothing. The class logs a WARN at startup so an
 * operator running it outside dev sees that KYC is effectively deferred, and
 * every call logs the request. Switching to a real provider is a config change
 * ({@code KYC_PROVIDER=dojah}), no code change. Tighter "deferred ⇒ PENDING
 * status" semantics (rather than auto-PASS) are tracked as a follow-up
 * (backlog {@code kyc-deferred-pending-status}).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "cia.kyc.provider", havingValue = "mock", matchIfMissing = true)
public class MockKycService implements KycVerificationService {

    @PostConstruct
    void warnStub() {
        log.warn("[KYC] MockKycService active (cia.kyc.provider=mock or unset) — KYC is a "
                + "PASS-THROUGH STUB performing NO real identity verification. Set KYC_PROVIDER "
                + "to a live provider (dojah/prembly) before production onboarding.");
    }

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
