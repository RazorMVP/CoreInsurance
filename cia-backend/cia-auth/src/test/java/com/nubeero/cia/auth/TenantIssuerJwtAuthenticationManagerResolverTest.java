package com.nubeero.cia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class TenantIssuerJwtAuthenticationManagerResolverTest {

    private KeycloakProperties props;
    private TenantIssuerJwtAuthenticationManagerResolver resolver;
    private AtomicInteger builds;

    @BeforeEach
    void setUp() {
        props = new KeycloakProperties();
        props.setServerUrl("http://localhost:8280/"); // trailing slash on purpose
        resolver = new TenantIssuerJwtAuthenticationManagerResolver(props, new JwtAuthConverter());
        builds = new AtomicInteger();
        resolver.managerFactory = issuer -> {
            builds.incrementAndGet();
            return mock(AuthenticationManager.class);
        };
    }

    @Test
    @DisplayName("trusted issuer on our server builds a manager")
    void trustedIssuerBuilds() {
        AuthenticationManager m = resolver.resolveForIssuer("http://localhost:8280/realms/acme");
        assertThat(m).isNotNull();
        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same issuer is cached — build function runs once")
    void cachesPerIssuer() {
        resolver.resolveForIssuer("http://localhost:8280/realms/acme");
        resolver.resolveForIssuer("http://localhost:8280/realms/acme");
        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("different realms build separate managers")
    void distinctRealms() {
        resolver.resolveForIssuer("http://localhost:8280/realms/acme");
        resolver.resolveForIssuer("http://localhost:8280/realms/leadway");
        assertThat(builds.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("issuer on a foreign host is rejected (401), no build")
    void rejectsForeignHost() {
        assertThatThrownBy(() ->
            resolver.resolveForIssuer("http://evil.example.com/realms/acme"))
            .isInstanceOf(InvalidBearerTokenException.class);
        assertThat(builds.get()).isZero();
    }

    @Test
    @DisplayName("our server but no realm segment is rejected (401)")
    void rejectsNoRealm() {
        assertThatThrownBy(() ->
            resolver.resolveForIssuer("http://localhost:8280/realms/"))
            .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() ->
            resolver.resolveForIssuer("http://localhost:8280"))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("null/blank issuer is rejected (401)")
    void rejectsBlank() {
        assertThatThrownBy(() -> resolver.resolveForIssuer(null))
            .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> resolver.resolveForIssuer("  "))
            .isInstanceOf(InvalidBearerTokenException.class);
    }
}
