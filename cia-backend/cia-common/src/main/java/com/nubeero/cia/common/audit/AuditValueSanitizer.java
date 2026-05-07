package com.nubeero.cia.common.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AuditValueSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_TEXT_LENGTH = 2048;

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "address", "alternatephone", "authorization", "bvn", "caccertificateurl",
            "dateofbirth", "dob", "email", "firstname", "fullname", "iddocumenturl",
            "idnumber", "lastname", "nin", "othernames", "password", "phone",
            "rcnumber", "secret", "sessionid", "token"
    );

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern LONG_IDENTIFIER = Pattern.compile("\\b\\d{8,}\\b");

    private final ObjectMapper objectMapper;

    public String sanitizeToJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.valueToTree(value);
            JsonNode sanitized = sanitize(node, null);
            return objectMapper.writeValueAsString(sanitized);
        } catch (Exception e) {
            return objectMapper.valueToTree(sanitizeText(String.valueOf(value))).toString();
        }
    }

    private JsonNode sanitize(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (isSensitiveField(fieldName)) {
            return TextNode.valueOf(REDACTED);
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            Iterator<Map.Entry<String, JsonNode>> fields = copy.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                copy.set(field.getKey(), sanitize(field.getValue(), field.getKey()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                copy.add(sanitize(child, fieldName));
            }
            return copy;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.asText()));
        }
        return node;
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String normalized = fieldName
                .replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
        return SENSITIVE_FIELD_NAMES.contains(normalized)
                || normalized.endsWith("address")
                || normalized.endsWith("email")
                || normalized.endsWith("phone")
                || normalized.endsWith("token")
                || normalized.endsWith("secret")
                || normalized.endsWith("password")
                || normalized.endsWith("idnumber")
                || normalized.endsWith("documenturl");
    }

    private String sanitizeText(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = BEARER_TOKEN.matcher(value).replaceAll("Bearer " + REDACTED);
        sanitized = EMAIL.matcher(sanitized).replaceAll(REDACTED);
        sanitized = LONG_IDENTIFIER.matcher(sanitized).replaceAll(REDACTED);
        if (sanitized.length() > MAX_TEXT_LENGTH) {
            return sanitized.substring(0, MAX_TEXT_LENGTH) + "...[truncated]";
        }
        return sanitized;
    }
}
