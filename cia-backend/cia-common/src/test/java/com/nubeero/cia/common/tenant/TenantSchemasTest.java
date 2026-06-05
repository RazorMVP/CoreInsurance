package com.nubeero.cia.common.tenant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantSchemasTest {

    @Test
    void acceptsValidLowercaseIdentifiers() {
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("tenant_acme"));
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("public"));
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("_x"));
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("t1"));
        // 63 chars is the max valid length (1 lead + {0,62}); pin the upper boundary.
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("x".repeat(63)));
    }

    @Test
    void rejectsNullInjectionAndMalformedNames() {
        assertThatThrownBy(() -> TenantSchemas.validate(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("a\"; drop schema x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("has space"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("1leading"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("Upper"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("x".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
