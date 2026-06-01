package com.nubeero.cia.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.stereotype.Component;

/**
 * Realm-per-tenant resource-server auth: resolves the {@link AuthenticationManager}
 * for a request from the token's {@code iss} claim, validating against that
 * realm's JWKS.
 *
 * <p>Trust model (base-URL): an issuer is trusted iff it is
 * {@code {KEYCLOAK_URL}/realms/{realm}} with a non-empty realm. Untrusted or
 * malformed issuers are rejected with {@link InvalidBearerTokenException} -> HTTP
 * 401, never a 500. Per-issuer managers are built lazily on first token (no OIDC
 * discovery at startup) and cached.
 */
@Component
public class TenantIssuerJwtAuthenticationManagerResolver
        implements AuthenticationManagerResolver<HttpServletRequest> {

    private final KeycloakProperties props;
    private final JwtAuthConverter jwtAuthConverter;
    private final ConcurrentHashMap<String, AuthenticationManager> cache = new ConcurrentHashMap<>();
    private final JwtIssuerAuthenticationManagerResolver delegate;

    /**
     * Builds the {@link AuthenticationManager} for a trusted issuer. Package-private
     * + overridable so unit tests can avoid the JWKS network fetch.
     */
    Function<String, AuthenticationManager> managerFactory = this::buildManager;

    public TenantIssuerJwtAuthenticationManagerResolver(KeycloakProperties props,
                                                        JwtAuthConverter jwtAuthConverter) {
        this.props = props;
        this.jwtAuthConverter = jwtAuthConverter;
        this.delegate = new JwtIssuerAuthenticationManagerResolver(
                (AuthenticationManagerResolver<String>) this::resolveForIssuer);
    }

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        return delegate.resolve(request);
    }

    /** Trust gate + lazy cache. Visible for testing. */
    AuthenticationManager resolveForIssuer(String issuer) {
        if (!isTrusted(issuer)) {
            throw new InvalidBearerTokenException("Untrusted token issuer");
        }
        return cache.computeIfAbsent(issuer, managerFactory);
    }

    private boolean isTrusted(String issuer) {
        String realm = KeycloakRealms.realmOf(issuer);
        if (realm == null) {
            return false;
        }
        String expected = props.normalisedServerUrl() + "/realms/" + realm;
        return expected.equals(trimTrailingSlash(issuer));
    }

    private AuthenticationManager buildManager(String issuer) {
        JwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(jwtAuthConverter);
        return provider::authenticate;
    }

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}
