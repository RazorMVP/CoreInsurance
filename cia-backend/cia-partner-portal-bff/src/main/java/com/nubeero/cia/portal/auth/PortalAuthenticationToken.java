package com.nubeero.cia.portal.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * Wraps a {@link PortalPrincipal} as a Spring Security {@code Authentication} so
 * {@code authorizeHttpRequests().anyRequest().authenticated()} and
 * {@code @AuthenticationPrincipal PortalPrincipal} both work for {@code /portal/**} controllers,
 * exactly as {@code org.springframework.security.oauth2.server.resource.authentication
 * .JwtAuthenticationToken} does for the JWT-based prod chains.
 */
public class PortalAuthenticationToken extends AbstractAuthenticationToken {

    private final PortalPrincipal principal;

    public PortalAuthenticationToken(PortalPrincipal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
