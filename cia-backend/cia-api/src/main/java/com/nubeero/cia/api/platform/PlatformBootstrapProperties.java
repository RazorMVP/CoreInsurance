package com.nubeero.cia.api.platform;

import java.util.List;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the platform plane.
 *
 * <p>The {@code cia.platform.bootstrap.*} sub-tree gates
 * {@link PlatformBootstrapRunner}: setting
 * {@code cia.platform.bootstrap.enabled=true} (env
 * {@code CIA_PLATFORM_BOOTSTRAP_ENABLED=true}) makes the runner fire on
 * startup and provision the platform Keycloak realm + first super-admin.
 *
 * <p>The {@code cia.platform.realm} and {@code cia.platform.client-id} values
 * are also read by {@code cia-auth}'s {@code PlatformRealmProperties} — keep
 * the two in sync.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties("cia.platform")
public class PlatformBootstrapProperties {

    /** The platform realm name. Also read in cia-auth (PlatformRealmProperties) — keep in sync. */
    private String realm = "platform";

    private String clientId = "cia-platform";

    private List<String> redirectUris = List.of("http://localhost:5175/*");

    private final Bootstrap bootstrap = new Bootstrap();

    /** Nested bootstrap-gate settings. */
    @Data
    public static class Bootstrap {

        /** Master switch — OFF by default so dev + the IT suite never bootstrap the platform. */
        private boolean enabled = false;

        private String adminUsername = "superadmin";

        private String adminEmail = "superadmin@cia.local";

        /** Sensitive — supply via env {@code CIA_PLATFORM_BOOTSTRAP_ADMIN_TEMP_PASSWORD}. */
        @ToString.Exclude
        private String adminTempPassword;
    }
}
