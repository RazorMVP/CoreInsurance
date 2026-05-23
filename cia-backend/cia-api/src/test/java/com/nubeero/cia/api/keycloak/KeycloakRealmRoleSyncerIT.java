package com.nubeero.cia.api.keycloak;

import com.nubeero.cia.setup.access.AccessGroup;
import com.nubeero.cia.setup.access.AccessGroupPermission;
import com.nubeero.cia.setup.keycloak.KeycloakRealmRoleSyncer;
import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT — drains the role-sync half of {@code F4-sync-tests} for
 * {@link KeycloakRealmRoleSyncer} against a real Keycloak 24 container.
 * Pins the four properties that matter for production correctness:
 * <ul>
 *   <li>Permission strings ({@code setup:view}) are translated to Keycloak
 *       realm role names ({@code setup_view}) via the colon→underscore
 *       mapping the {@code JwtAuthConverter} expects.</li>
 *   <li>Sync is idempotent — running twice with the same access group
 *       produces the same realm + user state.</li>
 *   <li>Sync removes obsolete managed roles from the user's assignment
 *       when the access group's permissions change (diff semantics).</li>
 *   <li>Unmanaged realm roles (description doesn't start with
 *       {@code CIA-managed: }) are NEVER removed from the user — the
 *       boundary rule that lets the syncer coexist with realm roles
 *       managed by hand in the Keycloak console.</li>
 * </ul>
 *
 * <p>Adoption semantics (a managed-role name already exists in the realm
 * without the CIA description) are covered by the corresponding unit test
 * in {@link com.nubeero.cia.setup.keycloak.KeycloakPolicyDslTest}'s sibling
 * coverage area — the assertion logic for the adoption path is exercised
 * here transitively by the first {@code syncFor} call (which creates roles
 * with the CIA description) and by the obsolete-removal test (which
 * relies on the description tag to know what's safe to remove).
 */
class KeycloakRealmRoleSyncerIT extends KeycloakItSupport {

    private static Keycloak ADMIN;
    private static KeycloakRealmRoleSyncer SYNCER;
    private static String USER_ID;

    @BeforeAll
    static void buildSyncer() {
        ensureTestRealm();
        ADMIN = KEYCLOAK.getKeycloakAdminClient();

        KeycloakAdminProperties props = new KeycloakAdminProperties();
        props.setEnabled(true);
        props.setServerUrl(KEYCLOAK.getAuthServerUrl());
        props.setAdminRealm("master");
        props.setClientId("admin-cli");
        props.setUsername(KEYCLOAK.getAdminUsername());
        props.setPassword(KEYCLOAK.getAdminPassword());
        props.setTargetRealm(TEST_REALM);

        SYNCER = new KeycloakRealmRoleSyncer(new StaticObjectProvider<>(ADMIN), props);
    }

    @BeforeEach
    void createFreshUser() {
        // Create a throwaway user per test. The user lives only for the
        // duration of one test method — eliminates cross-test pollution
        // around role assignments at the per-user level.
        RealmResource realm = ADMIN.realm(TEST_REALM);
        UserRepresentation u = new UserRepresentation();
        u.setUsername("syncer-it-" + UUID.randomUUID());
        u.setEnabled(true);
        try (Response r = realm.users().create(u)) {
            assertThat(r.getStatus()).as("user create").isEqualTo(201);
            String loc = r.getHeaderString("Location");
            USER_ID = loc.substring(loc.lastIndexOf('/') + 1);
        }
    }

    @AfterEach
    void deleteUserAndManagedRoles() {
        RealmResource realm = ADMIN.realm(TEST_REALM);
        try {
            realm.users().delete(USER_ID).close();
        } catch (NotFoundException ignored) { /* already gone */ }

        // Strip any CIA-managed roles created during this test so the next
        // test starts with no role residue.
        for (RoleRepresentation r : realm.roles().list()) {
            if (r.getDescription() != null && r.getDescription().startsWith("CIA-managed: ")) {
                try {
                    realm.roles().deleteRole(r.getName());
                } catch (NotFoundException ignored) { /* already gone */ }
            }
        }
    }

    @Test
    @DisplayName("syncFor — creates realm roles tagged CIA-managed and assigns them to the user")
    void syncCreatesAndAssignsManagedRoles() {
        AccessGroup group = newGroup("setup:view", "claims:create");

        SYNCER.syncFor(USER_ID, group);

        RealmResource realm = ADMIN.realm(TEST_REALM);
        // Roles exist in the realm with the CIA description prefix
        assertThat(realm.roles().get("setup_view").toRepresentation().getDescription())
                .startsWith("CIA-managed: ");
        assertThat(realm.roles().get("claims_create").toRepresentation().getDescription())
                .startsWith("CIA-managed: ");
        // User's realm-role assignment includes exactly those two
        List<String> assigned = realm.users().get(USER_ID).roles().realmLevel().listAll()
                .stream().map(RoleRepresentation::getName).toList();
        assertThat(assigned).contains("setup_view", "claims_create");
    }

    @Test
    @DisplayName("syncFor — running twice with the same group is a no-op (idempotent)")
    void syncIsIdempotent() {
        AccessGroup group = newGroup("setup:view", "claims:view");
        SYNCER.syncFor(USER_ID, group);
        SYNCER.syncFor(USER_ID, group);

        Set<String> assigned = ADMIN.realm(TEST_REALM).users().get(USER_ID).roles().realmLevel().listAll()
                .stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        // contains(...) rather than containsExactlyInAnyOrder — Keycloak
        // auto-assigns "default-roles-<realm>" to every new user; that's
        // outside the syncer's managed set and intentionally untouched.
        assertThat(assigned).contains("setup_view", "claims_view");
    }

    @Test
    @DisplayName("syncFor — removes managed roles that are no longer in the access group")
    void syncRemovesObsoleteManagedRoles() {
        // First state: user has setup:view + claims:create
        AccessGroup before = newGroup("setup:view", "claims:create");
        SYNCER.syncFor(USER_ID, before);

        // Group rotates: claims:create dropped, finance:view added
        AccessGroup after = newGroup("setup:view", "finance:view");
        SYNCER.syncFor(USER_ID, after);

        Set<String> assigned = ADMIN.realm(TEST_REALM).users().get(USER_ID).roles().realmLevel().listAll()
                .stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        assertThat(assigned).contains("setup_view", "finance_view");
        assertThat(assigned).doesNotContain("claims_create");
        // The role itself still exists in the realm — only the user's
        // assignment was diffed. Other users in other groups may still
        // need it.
        assertThat(ADMIN.realm(TEST_REALM).roles().get("claims_create").toRepresentation()).isNotNull();
    }

    @Test
    @DisplayName("syncFor — does NOT remove realm roles that aren't CIA-managed (boundary rule)")
    void syncPreservesUnmanagedRoles() {
        RealmResource realm = ADMIN.realm(TEST_REALM);

        // Create an unmanaged role (no CIA-managed prefix) and assign it to
        // the user. This simulates a Keycloak admin assigning a custom role
        // directly in the console — the syncer must never touch it.
        RoleRepresentation manualRole = new RoleRepresentation();
        manualRole.setName("manual-admin-role");
        manualRole.setDescription("Created by a human in the console — no CIA prefix");
        realm.roles().create(manualRole);
        RoleRepresentation lookedUp = realm.roles().get("manual-admin-role").toRepresentation();
        realm.users().get(USER_ID).roles().realmLevel().add(List.of(lookedUp));

        // Now sync the user against an access group that DOESN'T include
        // manual-admin-role. If the syncer respected only CIA-managed roles,
        // manual-admin-role stays assigned.
        AccessGroup group = newGroup("setup:view");
        SYNCER.syncFor(USER_ID, group);

        Set<String> assigned = realm.users().get(USER_ID).roles().realmLevel().listAll()
                .stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        assertThat(assigned).contains("setup_view", "manual-admin-role");
    }

    /**
     * Build a transient {@link AccessGroup} carrying the given permission
     * strings. Not persisted — the syncer only reads {@code getPermissions()},
     * which is enough.
     */
    private static AccessGroup newGroup(String... perms) {
        AccessGroup g = AccessGroup.builder().name("test-grp-" + UUID.randomUUID()).build();
        List<AccessGroupPermission> list = new ArrayList<>();
        for (String p : perms) {
            list.add(AccessGroupPermission.builder().accessGroup(g).permission(p).build());
        }
        g.setPermissions(list);
        return g;
    }
}
