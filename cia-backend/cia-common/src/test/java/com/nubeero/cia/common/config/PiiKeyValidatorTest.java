package com.nubeero.cia.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PiiKeyValidatorTest {

    private final PiiKeyValidator validator = new PiiKeyValidator();

    @Test
    @DisplayName("rejects null/empty key")
    void rejectsMissing() {
        MockEnvironment env = new MockEnvironment();
        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("accepts dev default")
    void acceptsDevDefault() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("cia.security.pii-key", "dev-pii-key-do-not-use-in-prod-CHANGE-ME");
        assertDoesNotThrow(() -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("accepts base64 key (32-byte equivalent)")
    void acceptsBase64() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("cia.security.pii-key", "abcDEF123+/=._abcDEF123+/=._abcDEF12345");
        assertDoesNotThrow(() -> validator.postProcessEnvironment(env, null));
    }

    @ParameterizedTest
    @DisplayName("rejects SQL-injection attempts")
    @ValueSource(strings = {
        "key'; DROP TABLE users; --",
        "key';SELECT pg_sleep(10);--",
        "key\\'; DROP",
        "key with spaces",
        "key\nwith\nnewlines",
        "key\twith\ttabs",
        "key\"with\"doublequotes",
        "key;semicolon",
        "key$injection",
        "key`backtick",
        "短",
        ""
    })
    void rejectsInjection(String badKey) {
        MockEnvironment env = new MockEnvironment().withProperty("cia.security.pii-key", badKey);
        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("rejects too-short key")
    void rejectsTooShort() {
        MockEnvironment env = new MockEnvironment().withProperty("cia.security.pii-key", "short-key");
        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }

    @Test
    @DisplayName("rejects too-long key (> 256 chars)")
    void rejectsTooLong() {
        String longKey = "a".repeat(257);
        MockEnvironment env = new MockEnvironment().withProperty("cia.security.pii-key", longKey);
        assertThrows(IllegalStateException.class, () -> validator.postProcessEnvironment(env, null));
    }
}
