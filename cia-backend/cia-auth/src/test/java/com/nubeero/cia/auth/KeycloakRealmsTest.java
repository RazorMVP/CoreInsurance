package com.nubeero.cia.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KeycloakRealmsTest {

    @Test
    @DisplayName("realmOf extracts the realm segment from a Keycloak issuer URL")
    void extractsRealm() {
        assertThat(KeycloakRealms.realmOf("http://localhost:8280/realms/cia")).isEqualTo("cia");
        assertThat(KeycloakRealms.realmOf("https://kc.cia.app/realms/acme-insurance"))
            .isEqualTo("acme-insurance");
    }

    @Test
    @DisplayName("realmOf tolerates a trailing slash and extra path after the realm")
    void toleratesTrailingAndExtra() {
        assertThat(KeycloakRealms.realmOf("http://localhost:8280/realms/cia/")).isEqualTo("cia");
        assertThat(KeycloakRealms.realmOf(
            "http://localhost:8280/realms/cia/protocol/openid-connect")).isEqualTo("cia");
    }

    @ParameterizedTest
    @DisplayName("realmOf returns null for issuers with no realm segment")
    @ValueSource(strings = {
        "http://localhost:8280",
        "http://localhost:8280/realms/",
        "http://localhost:8280/auth/cia",
        "not-a-url"
    })
    void nullWhenNoRealm(String issuer) {
        assertThat(KeycloakRealms.realmOf(issuer)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("realmOf returns null for null/blank")
    void nullWhenBlank(String issuer) {
        assertThat(KeycloakRealms.realmOf(issuer)).isNull();
    }
}
