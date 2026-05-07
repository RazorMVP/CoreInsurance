package com.nubeero.cia.partner.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliverySanitizerTest {

    @Test
    void replacesPayloadWithMarkerInsteadOfStoringSensitiveBody() {
        assertThat(WebhookDeliverySanitizer.redactedPayloadMarker())
                .doesNotContain("policyNumber")
                .contains("redacted");
    }

    @Test
    void redactsSensitiveJsonFieldsFromResponseBody() {
        String response = """
                {"email":"ada@example.com","phone":"+2348000000000","access_token":"secret-token","status":"ok"}
                """;

        String sanitized = WebhookDeliverySanitizer.sanitizeResponseBody(response);

        assertThat(sanitized)
                .contains("\"email\":\"[REDACTED]\"")
                .contains("\"phone\":\"[REDACTED]\"")
                .contains("\"access_token\":\"[REDACTED]\"")
                .doesNotContain("ada@example.com")
                .doesNotContain("secret-token");
    }

    @Test
    void truncatesOversizedResponseBodies() {
        String sanitized = WebhookDeliverySanitizer.sanitizeResponseBody("x".repeat(3_000));

        assertThat(sanitized)
                .hasSize(WebhookDeliverySanitizer.MAX_RESPONSE_BODY_LENGTH + "...[truncated]".length())
                .endsWith("...[truncated]");
    }

    @Test
    void redactsBearerTokensFromErrorMessages() {
        String sanitized = WebhookDeliverySanitizer.sanitizeErrorMessage(
                "upstream rejected Authorization: Bearer abc.def.ghi");

        assertThat(sanitized)
                .contains("Bearer [REDACTED]")
                .doesNotContain("abc.def.ghi");
    }
}
