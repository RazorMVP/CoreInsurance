package com.nubeero.cia.api.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.integrations.kyc.KycVerificationService;
import com.nubeero.cia.integrations.kyc.MockKycService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring test for the KYC provider selection ({@code cia.kyc.provider}). The
 * concern is launch-safety: {@code KycVerificationService} is a hard
 * constructor dependency of {@code CustomerService}, so the application context
 * MUST always have exactly one such bean — including in a production profile
 * with no provider configured. Before the S142 fix, {@code MockKycService} was
 * gated {@code @Profile("dev | test")}, so a prod boot had NO bean and customer
 * onboarding broke. It is now gated
 * {@code @ConditionalOnProperty(name="cia.kyc.provider", havingValue="mock", matchIfMissing=true)}
 * mirroring the NAICOM/NIID stubs.
 *
 * <p>Uses {@link ApplicationContextRunner} (no DB, no full app boot) scanning
 * only the KYC package, so the {@code @ConditionalOnProperty} gating is the
 * thing under test — independent of Spring profiles.
 */
class KycProviderWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = MockKycService.class)
    static class KycScan {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // ConditionalOnProperty needs the condition-evaluation autoconfig context;
            // a plain user config + component scan is enough here.
            .withUserConfiguration(KycScan.class);

    @Test
    @DisplayName("no cia.kyc.provider set → MockKycService is the active bean (launch-safe default)")
    void defaultsToMockWhenUnset() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(KycVerificationService.class);
            assertThat(ctx.getBean(KycVerificationService.class)).isInstanceOf(MockKycService.class);
        });
    }

    @Test
    @DisplayName("cia.kyc.provider=mock → MockKycService is the active bean")
    void explicitMock() {
        runner.withPropertyValues("cia.kyc.provider=mock").run(ctx -> {
            assertThat(ctx).hasSingleBean(KycVerificationService.class);
            assertThat(ctx.getBean(KycVerificationService.class)).isInstanceOf(MockKycService.class);
        });
    }

    @Test
    @DisplayName("cia.kyc.provider=dojah → Mock is NOT active (live provider takes over)")
    void liveProviderDisablesMock() {
        runner.withPropertyValues("cia.kyc.provider=dojah").run(ctx -> {
            // DojahKycService is @ConditionalOnProperty havingValue=dojah; Mock must step aside.
            assertThat(ctx).doesNotHaveBean(MockKycService.class);
        });
    }
}
