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

    @Test
    void ensureFirstAdminUserCreatesUserWithTempPasswordAndAllRoles() {
        Keycloak admin = adminClient();
        var provisioner = newProvisioner(admin);
        provisioner.ensureRealmRoles(admin, TEST_REALM);

        var spec = new com.nubeero.cia.setup.keycloak.FirstAdminSpec(
            "bootadmin", "bootadmin@acme.example", "Boot", "Admin", "Temp!Pass123",
            java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"));
        provisioner.ensureFirstAdminUser(admin, TEST_REALM, spec);
        provisioner.ensureFirstAdminUser(admin, TEST_REALM, spec); // idempotent — no duplicate user

        var users = admin.realm(TEST_REALM).users().search("bootadmin", true);
        assertThat(users).hasSize(1);
        var user = users.get(0);
        assertThat(user.getRequiredActions()).contains("UPDATE_PASSWORD");
        assertThat(user.getAttributes().get("accessGroupId"))
            .containsExactly("22222222-2222-2222-2222-222222222222");

        var assigned = admin.realm(TEST_REALM).users().get(user.getId())
            .roles().realmLevel().listAll().stream().map(r -> r.getName()).toList();
        assertThat(assigned).containsAll(com.nubeero.cia.setup.keycloak.BootstrapRoles.ALL);
    }
}
