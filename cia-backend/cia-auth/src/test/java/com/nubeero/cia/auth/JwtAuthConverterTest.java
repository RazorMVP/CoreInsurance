package com.nubeero.cia.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthConverterTest {

    private final JwtAuthConverter converter = new JwtAuthConverter();

    @Test
    void mapsRealmRolesToRawAndSpringRoleAuthorities() {
        var authentication = converter.convert(jwt(Map.of(
                "realm_access", Map.of("roles", List.of("finance_view", "SETUP_UPDATE"))
        )));

        assertThat(authorities(authentication.getAuthorities()))
                .contains(
                        "finance_view", "ROLE_FINANCE_VIEW", "finance:view",
                        "SETUP_UPDATE", "ROLE_SETUP_UPDATE", "setup:update"
                );
    }

    @Test
    void bridgesPermissionStyleRolesToSpringRoleAuthorities() {
        var authentication = converter.convert(jwt(Map.of(
                "realm_access", Map.of("roles", List.of("reports:view", "setup:update"))
        )));

        assertThat(authorities(authentication.getAuthorities()))
                .contains("reports:view", "ROLE_REPORTS_VIEW", "setup:update", "ROLE_SETUP_UPDATE");
    }

    @Test
    void mapsSpaceDelimitedScopesToRawAndScopeAuthorities() {
        var authentication = converter.convert(jwt(Map.of(
                "scope", "products:read quotes:create"
        )));

        assertThat(authorities(authentication.getAuthorities()))
                .contains("products:read", "SCOPE_products:read", "quotes:create", "SCOPE_quotes:create");
    }

    @Test
    void mapsListScopesFromScopeAndScpClaims() {
        var authentication = converter.convert(jwt(Map.of(
                "scope", List.of("reports:view"),
                "scp", List.of("reports:export_csv")
        )));

        assertThat(authorities(authentication.getAuthorities()))
                .contains("reports:view", "SCOPE_reports:view", "reports:export_csv", "SCOPE_reports:export_csv");
    }

    @Test
    void ignoresMalformedRoleAndScopeClaims() {
        var authentication = converter.convert(jwt(Map.of(
                "realm_access", Map.of("roles", List.of("SETUP_VIEW", 1, "")),
                "scope", List.of("products:read", 2, " ")
        )));

        assertThat(authorities(authentication.getAuthorities()))
                .contains("SETUP_VIEW", "ROLE_SETUP_VIEW", "products:read", "SCOPE_products:read")
                .doesNotContain("", " ");
    }

    private Jwt jwt(Map<String, Object> claims) {
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                claims
        );
    }

    private List<String> authorities(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }
}
