package com.nubeero.cia.api.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

/**
 * Real-Keycloak IT for the super-admin lifecycle on the platform realm:
 * create -> list -> count -> remove-role. Mirrors {@code PlatformRealmProvisioningIT}.
 */
class PlatformSuperAdminProvisioningIT extends KeycloakItSupport {

    private static final String REALM = "platform_sa_it";
    private static Keycloak ADMIN;
    private static KeycloakTenantProvisioner provisioner;

    @BeforeAll
    static void provisionRealm() {
        ADMIN = adminClient();
        provisioner = newProvisioner(ADMIN);
        // Stand up the platform realm + SUPER_ADMIN role + client (one super-admin seeded).
        provisioner.provisionPlatformRealm(
                REALM, "cia-platform", List.of("http://localhost:5175/*"),
                new com.nubeero.cia.setup.keycloak.FirstAdminSpec(
                        "rootadmin", "root@platform.test", "Root", "Admin", "Aa1!rootpass",
                        java.util.UUID.randomUUID()));
    }

    @Test
    void create_list_count_remove() {
        provisioner.createSuperAdmin(ADMIN, REALM, "sa2", "sa2@platform.test", "Aa1!sa2pass");

        List<KeycloakTenantProvisioner.SuperAdminView> admins = provisioner.listSuperAdmins(ADMIN, REALM);
        assertThat(admins).extracting(KeycloakTenantProvisioner.SuperAdminView::username)
                .contains("rootadmin", "sa2");
        assertThat(provisioner.superAdminCount(ADMIN, REALM)).isGreaterThanOrEqualTo(2);

        // Duplicate create rejected.
        assertThatThrownBy(() -> provisioner.createSuperAdmin(ADMIN, REALM, "sa2", "x@y.test", "Aa1!x"))
                .isInstanceOf(KeycloakTenantProvisioner.SuperAdminExistsInRealm.class);

        // Remove the role -> sa2 no longer a super-admin.
        provisioner.removeSuperAdminRole(ADMIN, REALM, "sa2");
        assertThat(provisioner.listSuperAdmins(ADMIN, REALM))
                .extracting(KeycloakTenantProvisioner.SuperAdminView::username)
                .doesNotContain("sa2");
    }
}
