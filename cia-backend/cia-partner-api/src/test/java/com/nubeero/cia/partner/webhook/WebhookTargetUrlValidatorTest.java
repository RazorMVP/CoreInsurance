package com.nubeero.cia.partner.webhook;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookTargetUrlValidatorTest {

    @Test
    void acceptsHttpsUrlResolvingToPublicAddress() {
        assertThat(WebhookTargetUrlValidator.validate("https://8.8.8.8/webhooks/core-insurance"))
                .hasToString("https://8.8.8.8/webhooks/core-insurance");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8/webhook",
            "https://user:pass@8.8.8.8/webhook",
            "https://localhost/webhook",
            "https://127.0.0.1/webhook",
            "https://0.0.0.0/webhook",
            "https://10.0.0.10/webhook",
            "https://172.16.0.10/webhook",
            "https://192.168.1.20/webhook",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/webhook",
            "https://198.18.0.1/webhook",
            "https://[::1]/webhook",
            "https://[fc00::1]/webhook"
    })
    void rejectsUnsafeTargets(String targetUrl) {
        assertThatThrownBy(() -> WebhookTargetUrlValidator.validate(targetUrl))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Webhook target URL");
    }
}
