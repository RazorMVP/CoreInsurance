package com.nubeero.cia.api.partnerportal;

import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.keycloak.PartnerPortalClientSpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gated partner-portal-plane bootstrap. Mirrors {@code PlatformBootstrapRunner}: off by
 * default; requires the Keycloak admin client (via its constructor dependency on
 * {@link KeycloakTenantProvisioner}, itself {@code @ConditionalOnProperty(cia.keycloak.admin.enabled=true)}
 * — so enabling this runner while the admin client is disabled fails Spring context
 * startup fast, the same fail-fast mechanism the platform runner relies on);
 * fail-fast on error so a misconfigured partner-portal plane aborts startup.
 *
 * <p>Activated only when {@code cia.partner-portal.bootstrap.enabled=true} (env
 * {@code CIA_PARTNER_PORTAL_BOOTSTRAP_ENABLED=true}). Dev, local, and the normal IT
 * suite never set this flag, so this runner is a no-op in those environments.
 *
 * <p>The admin-group UUID is derived deterministically from the realm name via
 * {@link UUID#nameUUIDFromBytes} so provisioning is idempotent across restarts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cia.partner-portal.bootstrap.enabled", havingValue = "true")
public class PartnerPortalBootstrapRunner implements ApplicationRunner {

    private final PartnerPortalBootstrapProperties props;
    private final KeycloakTenantProvisioner provisioner;

    @Override
    public void run(ApplicationArguments args) {
        PartnerPortalBootstrapProperties.Bootstrap b = props.getBootstrap();
        if (b.getAdminTempPassword() == null || b.getAdminTempPassword().isBlank()) {
            throw new IllegalStateException(
                "CIA_PARTNER_PORTAL_BOOTSTRAP_ADMIN_TEMP_PASSWORD must not be blank when "
                    + "cia.partner-portal.bootstrap.enabled=true");
        }
        log.info("Bootstrapping partner-portal realm '{}'", props.getRealm());
        UUID adminGroup = UUID.nameUUIDFromBytes(
                ("partner-portal-admin::" + props.getRealm()).getBytes(StandardCharsets.UTF_8));
        provisioner.provisionPartnerPortalRealm(
                props.getRealm(),
                new PartnerPortalClientSpec(props.getClientId(), props.getRedirectUris()),
                new FirstAdminSpec(
                        b.getAdminUsername(),
                        b.getAdminEmail(),
                        "Partner",
                        "Developer",
                        b.getAdminTempPassword(),
                        adminGroup));
        log.info("Partner-portal realm '{}' bootstrap complete", props.getRealm());
    }
}
