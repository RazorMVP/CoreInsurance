package com.nubeero.cia.api.keycloak;

import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;

import jakarta.ws.rs.NotFoundException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT — drains {@code F1e-tenant-provisioning} for {@link
 * KeycloakTenantProvisioner}. Each test runs against a fresh realm name
 * (per-test UUID) so the assertions about "creates realm" / "heals
 * policy" are unambiguous and tests don't interfere with each other or
 * with the long-lived {@code cia-test} realm.
 *
 * <p>The provisioner is constructed manually (no Spring context) for the
 * same reason as the other Keycloak ITs — avoids the {@code @SpringBootTest}
 * boot tax. The startup {@link com.nubeero.cia.setup.keycloak.KeycloakTenantBootstrap}
 * wiring is one-liner trivial; covering the wiring itself with an IT would
 * add little over reading the diff.
 */
class KeycloakTenantProvisionerIT extends KeycloakItSupport {

    private static Keycloak ADMIN;
    private static KeycloakAdminProperties BASE_PROPS;

    private String testRealmName;

    @BeforeAll
    static void buildBase() {
        // Don't call ensureTestRealm() here — we don't want to share the
        // long-lived cia-test realm. Each @Test uses its own realm name.
        ADMIN = KEYCLOAK.getKeycloakAdminClient();
        BASE_PROPS = new KeycloakAdminProperties();
        BASE_PROPS.setEnabled(true);
        BASE_PROPS.setServerUrl(KEYCLOAK.getAuthServerUrl());
        BASE_PROPS.setAdminRealm("master");
        BASE_PROPS.setClientId("admin-cli");
        BASE_PROPS.setUsername(KEYCLOAK.getAdminUsername());
        BASE_PROPS.setPassword(KEYCLOAK.getAdminPassword());
    }

    @BeforeEach
    void freshRealmName() {
        testRealmName = "provisioner-it-" + UUID.randomUUID();
    }

    @AfterEach
    void deleteRealm() {
        try {
            ADMIN.realm(testRealmName).remove();
        } catch (NotFoundException ignored) { /* test never created it */ }
    }

    private KeycloakTenantProvisioner newProvisioner() {
        KeycloakAdminProperties p = new KeycloakAdminProperties();
        p.setEnabled(true);
        p.setServerUrl(BASE_PROPS.getServerUrl());
        p.setAdminRealm(BASE_PROPS.getAdminRealm());
        p.setClientId(BASE_PROPS.getClientId());
        p.setUsername(BASE_PROPS.getUsername());
        p.setPassword(BASE_PROPS.getPassword());
        p.setTargetRealm(testRealmName);
        return new KeycloakTenantProvisioner(new StaticObjectProvider<>(ADMIN), p);
    }

    @Test
    @DisplayName("provisionTenantRealm — creates the realm if missing AND sets the policy")
    void createsMissingRealm() {
        // Sanity: realm doesn't exist yet
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> ADMIN.realm(testRealmName).toRepresentation()
        ).isInstanceOf(NotFoundException.class);

        newProvisioner().provisionTenantRealm(testRealmName);

        RealmRepresentation rep = ADMIN.realm(testRealmName).toRepresentation();
        assertThat(rep.isEnabled()).isTrue();
        assertThat(rep.getRealm()).isEqualTo(testRealmName);

        UPConfig upc = ADMIN.realm(testRealmName).users().userProfile().getConfiguration();
        assertThat(upc.getUnmanagedAttributePolicy())
                .isEqualTo(UPConfig.UnmanagedAttributePolicy.ENABLED);
    }

    @Test
    @DisplayName("provisionTenantRealm — heals an existing realm with the policy disabled")
    void healsExistingRealmPolicy() {
        // Pre-create the realm WITHOUT setting the policy — simulates a
        // tenant realm that was manually created in the Keycloak console.
        RealmRepresentation seed = new RealmRepresentation();
        seed.setRealm(testRealmName);
        seed.setEnabled(true);
        ADMIN.realms().create(seed);

        // Verify the policy is the Keycloak 24 default (anything other
        // than ENABLED — typically DISABLED).
        UPConfig before = ADMIN.realm(testRealmName).users().userProfile().getConfiguration();
        assertThat(before.getUnmanagedAttributePolicy())
                .isNotEqualTo(UPConfig.UnmanagedAttributePolicy.ENABLED);

        newProvisioner().provisionTenantRealm(testRealmName);

        UPConfig after = ADMIN.realm(testRealmName).users().userProfile().getConfiguration();
        assertThat(after.getUnmanagedAttributePolicy())
                .isEqualTo(UPConfig.UnmanagedAttributePolicy.ENABLED);
    }

    @Test
    @DisplayName("provisionTenantRealm — is idempotent (re-running is a no-op)")
    void idempotentReRun() {
        KeycloakTenantProvisioner p = newProvisioner();
        p.provisionTenantRealm(testRealmName);
        p.provisionTenantRealm(testRealmName);
        p.provisionTenantRealm(testRealmName);

        // After three runs, exactly one realm exists and the policy is
        // ENABLED. Multiple invocations don't accumulate side effects.
        RealmRepresentation rep = ADMIN.realm(testRealmName).toRepresentation();
        assertThat(rep.isEnabled()).isTrue();

        UPConfig upc = ADMIN.realm(testRealmName).users().userProfile().getConfiguration();
        assertThat(upc.getUnmanagedAttributePolicy())
                .isEqualTo(UPConfig.UnmanagedAttributePolicy.ENABLED);
    }

    @Test
    @DisplayName("provisionTenantRealm — creates the back-office client with PKCE S256 + tenant_id mapper")
    void createsBackOfficeClient() {
        newProvisioner().provisionTenantRealm(testRealmName);

        List<ClientRepresentation> clients =
                ADMIN.realm(testRealmName).clients().findByClientId("cia-back-office");
        assertThat(clients).hasSize(1);

        ClientRepresentation c = clients.get(0);
        assertThat(c.isPublicClient()).isTrue();
        assertThat(c.isStandardFlowEnabled()).isTrue();
        assertThat(c.isDirectAccessGrantsEnabled()).isFalse();
        assertThat(c.getRedirectUris()).contains("http://localhost:5173/*");
        assertThat(c.getAttributes()).containsEntry("pkce.code.challenge.method", "S256");

        // The hardcoded tenant_id mapper emits the realm name as the claim.
        List<ProtocolMapperRepresentation> mappers = ADMIN.realm(testRealmName)
                .clients().get(c.getId()).getProtocolMappers().getMappers();
        ProtocolMapperRepresentation tenantId = mappers.stream()
                .filter(m -> "tenant_id".equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tenant_id mapper missing"));
        assertThat(tenantId.getProtocolMapper()).isEqualTo("oidc-hardcoded-claim-mapper");
        assertThat(tenantId.getConfig()).containsEntry("claim.name", "tenant_id");
        assertThat(tenantId.getConfig()).containsEntry("claim.value", testRealmName);
        assertThat(tenantId.getConfig()).containsEntry("access.token.claim", "true");
    }

    @Test
    @DisplayName("provisionTenantRealm — back-office client creation is idempotent (one client, one mapper)")
    void backOfficeClientIdempotent() {
        KeycloakTenantProvisioner p = newProvisioner();
        p.provisionTenantRealm(testRealmName);
        p.provisionTenantRealm(testRealmName);
        p.provisionTenantRealm(testRealmName);

        // Exactly one client, exactly one tenant_id mapper — no duplicates
        // accumulate across re-runs.
        List<ClientRepresentation> clients =
                ADMIN.realm(testRealmName).clients().findByClientId("cia-back-office");
        assertThat(clients).hasSize(1);

        List<ProtocolMapperRepresentation> tenantIdMappers = ADMIN.realm(testRealmName)
                .clients().get(clients.get(0).getId()).getProtocolMappers().getMappers()
                .stream().filter(m -> "tenant_id".equals(m.getName())).toList();
        assertThat(tenantIdMappers).hasSize(1);
    }
}
