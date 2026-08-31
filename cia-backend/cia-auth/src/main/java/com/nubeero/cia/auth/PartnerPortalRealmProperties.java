package com.nubeero.cia.auth;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Partner-portal-realm awareness for the auth layer. Mirrors {@link PlatformRealmProperties}
 * — keep the property key in sync with the cia-api {@code PartnerPortalBootstrapProperties}
 * (both bind {@code "cia.partner-portal"}).
 *
 * <p>The {@code partner} Keycloak realm is a third plane alongside tenant realms and the
 * {@code platform} realm: it holds Insurtech developer users (role {@code PARTNER_DEVELOPER}),
 * never a tenant identity and never {@code SUPER_ADMIN}.
 */
@Getter
@Setter
@ConfigurationProperties("cia.partner-portal")
public class PartnerPortalRealmProperties {

    private String realm = "partner";

    private String clientId = "cia-partner-portal";

    /**
     * Keycloak-client-registered OAuth {@code redirect_uri} list. In the token-handler pattern the
     * BFF (not browser JS) is the OAuth client, so this MUST be the BFF's own
     * {@code /portal/auth/callback} endpoint — never the SPA origin. (Fix round 1: the original
     * default here pointed at the SPA port, which would have made {@code
     * PartnerPortalBootstrapRunner} register a redirect URI Keycloak would then reject the real
     * {@code redirect_uri} the BFF sends — {@code cia-partner-portal-bff}'s {@code
     * PortalAuthController#callbackUrl} always computes the BFF's own callback URL, so the
     * registered value here must match it exactly. Default assumes the local dev backend port —
     * see {@code cia-api}'s {@code server.port} default, 8090.) See {@link #appUrl} for the
     * *separate* SPA-origin concern (where the browser lands after the cookie is set).
     */
    private List<String> redirectUris = List.of("http://localhost:8090/portal/auth/callback");

    /**
     * Base URL of the Partner Portal SPA. Used by the BFF token-handler flow (Task 5,
     * {@code cia-partner-portal-bff}) as the post-login and post-logout redirect target — distinct
     * from {@link #redirectUris} (see its javadoc for why the two must not be conflated).
     */
    private String appUrl = "http://localhost:5174";
}
