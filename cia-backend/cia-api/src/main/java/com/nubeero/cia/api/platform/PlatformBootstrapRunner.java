package com.nubeero.cia.api.platform;

import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gated platform-plane bootstrap. Mirrors {@code TenantBootstrapRunner}: off by
 * default; requires the Keycloak admin client; fail-fast on error so a
 * misconfigured platform plane aborts startup.
 *
 * <p>Activated only when {@code cia.platform.bootstrap.enabled=true} (env
 * {@code CIA_PLATFORM_BOOTSTRAP_ENABLED=true}). Dev, local, and the normal IT
 * suite never set this flag, so this runner is a no-op in those environments.
 *
 * <p>The admin-group UUID is derived deterministically from the realm name via
 * {@link UUID#nameUUIDFromBytes} so provisioning is idempotent across restarts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cia.platform.bootstrap.enabled", havingValue = "true")
public class PlatformBootstrapRunner implements ApplicationRunner {

    private final PlatformBootstrapProperties props;
    private final KeycloakTenantProvisioner provisioner;

    @Override
    public void run(ApplicationArguments args) {
        PlatformBootstrapProperties.Bootstrap b = props.getBootstrap();
        if (b.getAdminTempPassword() == null || b.getAdminTempPassword().isBlank()) {
            throw new IllegalStateException(
                "CIA_PLATFORM_BOOTSTRAP_ADMIN_TEMP_PASSWORD must not be blank when "
                    + "cia.platform.bootstrap.enabled=true");
        }
        log.info("Bootstrapping platform realm '{}'", props.getRealm());
        UUID adminGroup = UUID.nameUUIDFromBytes(
                ("platform-admin::" + props.getRealm()).getBytes(StandardCharsets.UTF_8));
        provisioner.provisionPlatformRealm(
                props.getRealm(),
                props.getClientId(),
                props.getRedirectUris(),
                new FirstAdminSpec(
                        b.getAdminUsername(),
                        b.getAdminEmail(),
                        "Platform",
                        "Administrator",
                        b.getAdminTempPassword(),
                        adminGroup));
        log.info("Platform realm '{}' bootstrap complete", props.getRealm());
    }
}
