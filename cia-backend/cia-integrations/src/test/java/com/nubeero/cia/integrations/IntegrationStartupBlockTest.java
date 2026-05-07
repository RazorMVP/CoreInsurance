package com.nubeero.cia.integrations;

import com.nubeero.cia.integrations.kyc.DojahKycService;
import com.nubeero.cia.integrations.kyc.PremblyKycService;
import com.nubeero.cia.integrations.naicom.NaicomRestService;
import com.nubeero.cia.integrations.niid.NiidRestService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationStartupBlockTest {

    @Test
    void dojahLiveProviderFailsStartupUntilGoLiveImplementationExists() {
        DojahKycService service = new DojahKycService();

        assertThatThrownBy(service::failUntilLiveIntegrationIsImplemented)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dojah KYC live integration is not implemented yet");
    }

    @Test
    void premblyLiveProviderFailsStartupUntilGoLiveImplementationExists() {
        PremblyKycService service = new PremblyKycService();

        assertThatThrownBy(service::failUntilLiveIntegrationIsImplemented)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Prembly KYC live integration is not implemented yet");
    }

    @Test
    void naicomLiveProviderFailsStartupUntilGoLiveImplementationExists() {
        NaicomRestService service = new NaicomRestService();

        assertThatThrownBy(service::failUntilLiveIntegrationIsImplemented)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAICOM live integration is not implemented yet");
    }

    @Test
    void niidLiveProviderFailsStartupUntilGoLiveImplementationExists() {
        NiidRestService service = new NiidRestService();

        assertThatThrownBy(service::failUntilLiveIntegrationIsImplemented)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NIID live integration is not implemented yet");
    }
}
