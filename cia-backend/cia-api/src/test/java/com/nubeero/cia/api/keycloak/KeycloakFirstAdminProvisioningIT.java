package com.nubeero.cia.api.keycloak;

import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakFirstAdminProvisioningIT extends KeycloakItSupport {

    @BeforeAll
    static void provision() {
        ensureTestRealm(); // creates realm + back-office client via the production provisioner
    }

    @Test
    void ensureRealmRolesCreatesEveryCanonicalRole() {
        Keycloak admin = adminClient();
        newProvisioner(admin).ensureRealmRoles(admin, TEST_REALM);

        var roleNames = admin.realm(TEST_REALM).roles().list().stream()
            .map(r -> r.getName()).toList();
        assertThat(roleNames).containsAll(BootstrapRoles.ALL);
    }
}
