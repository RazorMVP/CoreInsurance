package com.nubeero.cia.partner.webhook;

import java.util.regex.Pattern;

public final class WebhookDeliverySanitizer {

    static final int MAX_RESPONSE_BODY_LENGTH = 2_048;
    static final int MAX_ERROR_MESSAGE_LENGTH = 512;
    private static final String REDACTED_PAYLOAD = "[redacted: webhook payload is not stored]";
    private static final String REDACTED_VALUE = "[REDACTED]";
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:access[_-]?token|refresh[_-]?token|token|secret|password|authorization|id[_-]?number|bvn|nin|email|phone|address)\"\\s*:\\s*\")[^\"]*(\")");
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");

    private WebhookDeliverySanitizer() {
    }

    public static String redactedPayloadMarker() {
        return REDACTED_PAYLOAD;
    }

    public static String sanitizeResponseBody(String responseBody) {
        if (responseBody == null) {
            return null;
        }
        return truncate(redact(responseBody), MAX_RESPONSE_BODY_LENGTH);
    }

    public static String sanitizeErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return truncate(redact(errorMessage), MAX_ERROR_MESSAGE_LENGTH);
    }

    private static String redact(String value) {
        String redacted = SENSITIVE_JSON_FIELD.matcher(value)
                .replaceAll("$1" + REDACTED_VALUE + "$2");
        return BEARER_TOKEN.matcher(redacted).replaceAll("$1" + REDACTED_VALUE);
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }
}
