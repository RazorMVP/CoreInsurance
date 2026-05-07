package com.nubeero.cia.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditValueSanitizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditValueSanitizer sanitizer = new AuditValueSanitizer(objectMapper);

    @Test
    void redactsPiiFieldsFromAuditSnapshots() throws Exception {
        String json = sanitizer.sanitizeToJson(Map.of(
                "firstName", "Ada",
                "idNumber", "12345678901",
                "email", "ada@example.com",
                "address", "1 Insurance Road",
                "safeStatus", "ACTIVE"));

        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("firstName").asText()).isEqualTo("[REDACTED]");
        assertThat(node.get("idNumber").asText()).isEqualTo("[REDACTED]");
        assertThat(node.get("email").asText()).isEqualTo("[REDACTED]");
        assertThat(node.get("address").asText()).isEqualTo("[REDACTED]");
        assertThat(node.get("safeStatus").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void redactsSensitiveValuesInsideFreeText() {
        String json = sanitizer.sanitizeToJson(Map.of(
                "message", "Authorization Bearer abc.def and id 12345678901 for ada@example.com"));

        assertThat(json).contains("Bearer [REDACTED]");
        assertThat(json).doesNotContain("abc.def");
        assertThat(json).doesNotContain("12345678901");
        assertThat(json).doesNotContain("ada@example.com");
    }
}
