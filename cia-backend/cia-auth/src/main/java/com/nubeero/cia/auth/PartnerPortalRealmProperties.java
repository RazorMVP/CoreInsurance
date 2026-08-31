package com.nubeero.cia.auth;

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
 *
 * <p><b>No {@code redirectUris} field here (fix round 2).</b> A field of that name existed
 * through Task 5 fix round 1 but had zero call sites — nothing in {@code cia-partner-portal-bff}
 * ever read it. The value that actually registers the {@code cia-partner-portal} Keycloak
 * client's redirect URI is {@code cia-api}'s {@code PartnerPortalBootstrapProperties
 * .redirectUris}, consumed by {@code PartnerPortalBootstrapRunner}. Round 1 corrected the dead
 * field here (from the SPA origin to the BFF callback URL) while leaving the live one in
 * cia-api still wrong — see the Task 5 SDD report's "Fix round 2" section. Removed rather than
 * fixed-in-place to eliminate the two-copies-of-one-value confusion; if a future need arises for
 * cia-auth itself to know the registered redirect URI, re-add it pointed at
 * {@code cia-api}'s copy (e.g. via a shared constant), not as an independently-defaulted field
 * of the same name.
 */
@Getter
@Setter
@ConfigurationProperties("cia.partner-portal")
public class PartnerPortalRealmProperties {

    private String realm = "partner";

    private String clientId = "cia-partner-portal";

    /**
     * Base URL of the Partner Portal SPA. Used by the BFF token-handler flow (Task 5,
     * {@code cia-partner-portal-bff}) as the post-login and post-logout redirect target — distinct
     * from the Keycloak-client-registered OAuth {@code redirect_uri} (which is the BFF's own
     * {@code /portal/auth/callback} endpoint, not the SPA — see {@code
     * PartnerPortalBootstrapProperties.redirectUris} in cia-api, the value that actually drives
     * client registration).
     */
    private String appUrl = "http://localhost:5174";
}
