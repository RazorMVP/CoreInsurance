package com.nubeero.cia.api.partnerportal;

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
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties("cia.partner-portal")
public class PartnerPortalBootstrapProperties {

    /** The partner realm name. Also read in cia-auth (PartnerPortalRealmProperties) — keep in sync. */
    private String realm = "partner";

    private String clientId = "cia-partner-portal";

    private List<String> redirectUris = List.of("http://localhost:5174/*");

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
