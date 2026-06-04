package com.nubeero.cia.api.tenant;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Bootstrap tenant declarations. {@code enabled=false} by default → runner is a no-op. */
@Data
@Configuration
@ConfigurationProperties(prefix = "cia.tenants.bootstrap")
public class TenantBootstrapProperties {

    private boolean enabled = false;
    private List<TenantSpec> tenants = new ArrayList<>();

    @Data
    public static class TenantSpec {
        private String schema;
        private String realm;
        private String displayName;
        private String subdomain;
        private String adminUsername;
        private String adminEmail;
        @ToString.Exclude
        private String adminTempPassword;
    }
}
