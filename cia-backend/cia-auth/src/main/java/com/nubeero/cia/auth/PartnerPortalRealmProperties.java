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

    private List<String> redirectUris = List.of("http://localhost:5174/*");
}
