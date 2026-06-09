package com.nubeero.cia.api.keycloak;

import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.keycloak.PlatformRoles;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT — verifies {@link KeycloakTenantProvisioner#provisionPlatformRealm}.
 *
 * <p>Each test uses a fresh realm name (UUID suffix) so assertions about
 * "creates realm / creates role / creates user" are unambiguous and tests
 * don't interfere with each other.
 *
 * <p>No Spring context — the provisioner is constructed manually via
 * {@link KeycloakItSupport} helpers, keeping test startup fast.
 */
class PlatformRealmProvisioningIT extends KeycloakItSupport {

    private static Keycloak ADMIN;

    private String platformRealm;

    @BeforeAll
    static void connect() {
        // adminClient() calls pollUntilAdminReady() which runs
        // disableMasterRealmSsl() first — required on Keycloak 24 start-dev.
        ADMIN = adminClient();
    }

    @BeforeEach
    void freshRealmName() {
        platformRealm = "platform-it-" + UUID.randomUUID();
    }

    @AfterEach
    void deleteRealm() {
        try {
            ADMIN.realm(platformRealm).remove();
        } catch (NotFoundException ignored) { /* test never created it */ }
    }

    private KeycloakTenantProvisioner newProvisioner() {
        return newProvisioner(ADMIN);
    }

    // -----------------------------------------------------------------------
    // Helper spec — fixed accessGroupId for deterministic assertions
    // -----------------------------------------------------------------------

    private FirstAdminSpec superAdminSpec() {
        return new FirstAdminSpec(
                "superadmin",
                "super@cia.local",
                "Super",
                "Admin",
                "Temp-Pass-123!",
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("provisionPlatformRealm — platform realm contains SUPER_ADMIN role")
    void platformRealmContainsSuperAdminRole() {
        newProvisioner().provisionPlatformRealm(
                platformRealm, "cia-platform",
                List.of("http://localhost:5175/*"),
                superAdminSpec());

        List<String> roleNames = ADMIN.realm(platformRealm).roles().list()
                .stream().map(r -> r.getName()).toList();

        assertThat(roleNames).contains(PlatformRoles.SUPER_ADMIN);
    }

    @Test
    @DisplayName("provisionPlatformRealm — platform realm does NOT contain tenant roles")
    void platformRealmLacksTenantRoles() {
        newProvisioner().provisionPlatformRealm(
                platformRealm, "cia-platform",
                List.of("http://localhost:5175/*"),
                superAdminSpec());

        List<String> roleNames = ADMIN.realm(platformRealm).roles().list()
                .stream().map(r -> r.getName()).toList();

        // Neither the Pattern-B PLATFORM_ADMIN nor any Pattern-A tenant module
        // role (e.g. policy_view) should exist in the platform realm.
        assertThat(roleNames).doesNotContain("PLATFORM_ADMIN");
        assertThat(roleNames).doesNotContain("policy_view");
        // Verify no BootstrapRoles at all landed here
        assertThat(roleNames).doesNotContainAnyElementsOf(BootstrapRoles.ALL);
    }

    @Test
    @DisplayName("provisionPlatformRealm — superadmin user exists in platform realm")
    void superAdminUserExists() {
        newProvisioner().provisionPlatformRealm(
                platformRealm, "cia-platform",
                List.of("http://localhost:5175/*"),
                superAdminSpec());

        var users = ADMIN.realm(platformRealm).users().search("superadmin", true);
        assertThat(users).hasSize(1);

        var user = users.get(0);
        assertThat(user.getRequiredActions()).contains("UPDATE_PASSWORD");
        assertThat(user.getAttributes().get("accessGroupId"))
                .containsExactly("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    }

    @Test
    @DisplayName("provisionPlatformRealm — cia-platform client exists with PKCE S256 (no tenant_id mapper)")
    void platformClientExistsWithPkce() {
        newProvisioner().provisionPlatformRealm(
                platformRealm, "cia-platform",
                List.of("http://localhost:5175/*"),
                superAdminSpec());

        List<ClientRepresentation> clients =
                ADMIN.realm(platformRealm).clients().findByClientId("cia-platform");
        assertThat(clients).hasSize(1);

        ClientRepresentation c = clients.get(0);
        assertThat(c.isPublicClient()).isTrue();
        assertThat(c.isStandardFlowEnabled()).isTrue();
        assertThat(c.isDirectAccessGrantsEnabled()).isFalse();
        assertThat(c.getRedirectUris()).contains("http://localhost:5175/*");
        assertThat(c.getAttributes()).containsEntry("pkce.code.challenge.method", "S256");

        // The platform client must NOT carry a tenant_id protocol mapper.
        var mappers = ADMIN.realm(platformRealm)
                .clients().get(c.getId()).getProtocolMappers().getMappers();
        boolean hasTenantIdMapper = mappers != null && mappers.stream()
                .anyMatch(m -> "tenant_id".equals(m.getName()));
        assertThat(hasTenantIdMapper)
                .as("platform client must not carry a tenant_id mapper")
                .isFalse();
    }

    @Test
    @DisplayName("provisionPlatformRealm — superadmin holds SUPER_ADMIN role, not PLATFORM_ADMIN")
    void superAdminRoleMappings() {
        newProvisioner().provisionPlatformRealm(
                platformRealm, "cia-platform",
                List.of("http://localhost:5175/*"),
                superAdminSpec());

        var users = ADMIN.realm(platformRealm).users().search("superadmin", true);
        assertThat(users).hasSize(1);

        List<String> assignedRoles = ADMIN.realm(platformRealm)
                .users().get(users.get(0).getId())
                .roles().realmLevel().listAll()
                .stream().map(r -> r.getName()).toList();

        assertThat(assignedRoles).contains(PlatformRoles.SUPER_ADMIN);
        assertThat(assignedRoles).doesNotContain("PLATFORM_ADMIN");
    }

    @Test
    @DisplayName("provisionPlatformRealm — is idempotent (three runs, no duplicates)")
    void idempotent() {
        KeycloakTenantProvisioner p = newProvisioner();
        p.provisionPlatformRealm(platformRealm, "cia-platform",
                List.of("http://localhost:5175/*"), superAdminSpec());
        p.provisionPlatformRealm(platformRealm, "cia-platform",
                List.of("http://localhost:5175/*"), superAdminSpec());
        p.provisionPlatformRealm(platformRealm, "cia-platform",
                List.of("http://localhost:5175/*"), superAdminSpec());

        // Exactly one user, one client, one SUPER_ADMIN role — no accumulation.
        assertThat(ADMIN.realm(platformRealm).users().search("superadmin", true)).hasSize(1);
        assertThat(ADMIN.realm(platformRealm).clients().findByClientId("cia-platform")).hasSize(1);

        var superAdminRoles = ADMIN.realm(platformRealm).roles().list()
                .stream().filter(r -> PlatformRoles.SUPER_ADMIN.equals(r.getName())).toList();
        assertThat(superAdminRoles).hasSize(1);

        // FIX 1 regression: reconcile re-assertion must keep directAccessGrantsEnabled=false
        // across multiple provision calls (the reconcile branch, not just the create branch).
        ClientRepresentation platformClient =
                ADMIN.realm(platformRealm).clients().findByClientId("cia-platform").get(0);
        assertThat(platformClient.isDirectAccessGrantsEnabled())
                .as("cia-platform client must not allow direct-access (password) grant after reconcile")
                .isNotEqualTo(Boolean.TRUE);
    }
}
