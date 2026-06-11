package com.nubeero.cia.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Platform-realm awareness for the auth layer. Mirrors {@code cia.platform.realm} —
 * keep the property key in sync with the cia-api {@code PlatformBootstrapProperties}
 * (both bind {@code "cia.platform"}).
 */
@Getter
@Setter
@ConfigurationProperties("cia.platform")
public class PlatformRealmProperties {

    private String realm = "platform";
    private final TenantAllowlist tenantAllowlist = new TenantAllowlist();

    @Getter
    @Setter
    public static class TenantAllowlist {
        private boolean enabled = false;
    }
}
