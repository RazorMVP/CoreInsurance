package com.nubeero.cia.api.partnerportal;

import com.nubeero.cia.portal.auth.PortalAuthController;
import java.util.List;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the partner-portal plane.
 *
 * <p>The {@code cia.partner-portal.bootstrap.*} sub-tree gates
 * {@link PartnerPortalBootstrapRunner}: setting
 * {@code cia.partner-portal.bootstrap.enabled=true} (env
 * {@code CIA_PARTNER_PORTAL_BOOTSTRAP_ENABLED=true}) makes the runner fire on
 * startup and provision the partner Keycloak realm + first partner-developer admin.
 *
 * <p>The {@code cia.partner-portal.realm} and {@code cia.partner-portal.client-id}
 * values are also read by {@code cia-auth}'s {@code PartnerPortalRealmProperties} —
 * keep the two in sync.
 *
 * <p><b>{@link #redirectUris} is the value that actually registers the {@code cia-partner-portal}
 * Keycloak client's redirect URI</b> ({@link PartnerPortalBootstrapRunner#run} passes it straight
 * into {@code PartnerPortalClientSpec} → {@code KeycloakTenantProvisioner
 * .provisionPartnerPortalRealm}) — this is the ONE property that must equal what
 * {@link PortalAuthController} (cia-partner-portal-bff) actually sends Keycloak as
 * {@code redirect_uri} at authorize/token time, since the BFF (not the browser) is the OAuth
 * client in the token-handler pattern. It is deliberately the BFF's own
 * {@code /portal/auth/callback} endpoint, never the SPA origin — see
 * {@code PartnerPortalBootstrapPropertiesRedirectUriTest} for the assertion that ties the two
 * together, and {@code fix round 2} of the Task 5 SDD report for why an earlier fix round edited
 * the wrong (dead) property of the same name in {@code cia-auth}.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties("cia.partner-portal")
public class PartnerPortalBootstrapProperties {

    /** The partner realm name. Also read in cia-auth (PartnerPortalRealmProperties) — keep in sync. */
    private String realm = "partner";

    private String clientId = "cia-partner-portal";

    /**
     * Default assumes the local dev backend port — see {@code cia-api}'s {@code server.port}
     * default, 8090 (application.yml) — combined with {@link PortalAuthController}'s
     * {@code /portal/auth/callback} path, which is exactly what {@code PortalAuthController
     * #callbackUrl} computes from the incoming request at runtime. The two MUST stay equal:
     * Keycloak rejects a token/authorize call whose {@code redirect_uri} doesn't exactly match a
     * registered one.
     */
    private List<String> redirectUris = List.of("http://localhost:8090/portal/auth/callback");

    private final Bootstrap bootstrap = new Bootstrap();

    /** Nested bootstrap-gate settings. */
    @Data
    public static class Bootstrap {

        /** Master switch — OFF by default so dev + the IT suite never bootstrap the partner realm. */
        private boolean enabled = false;

        private String adminUsername = "partnerportaladmin";

        private String adminEmail = "partnerportaladmin@cia.local";

        /** Sensitive — supply via env {@code CIA_PARTNER_PORTAL_BOOTSTRAP_ADMIN_TEMP_PASSWORD}. */
        @ToString.Exclude
        private String adminTempPassword;
    }
}
