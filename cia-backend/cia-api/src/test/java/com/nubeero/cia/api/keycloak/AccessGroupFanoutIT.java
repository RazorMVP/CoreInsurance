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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT — drains {@code F1e-sync-AccessGroup-fanout} by exercising
 * {@link KeycloakRealmRoleSyncer#syncForAllInGroup(AccessGroup)} against a
 * real Keycloak 24 container. Mirrors what {@code AccessGroupService.update()}
 * does when an access group's permissions change.
 *
 * <p>The IT calls the syncer's fanout method directly rather than going
 * through {@code AccessGroupService} because the service requires a DB
 * context this slice-style IT deliberately doesn't bring (the goal here is
 * to pin the Keycloak-side behaviour; the service-side decision of "when to
 * call fanout" is covered by code review of the diff snapshot in
 * {@code AccessGroupService.update()}). Two users assigned to the same
 * access-group attribute are created via the admin client; the syncer is
 * then invoked and both users' realm-role assignments are asserted.
 */
class AccessGroupFanoutIT extends KeycloakItSupport {

    private static Keycloak ADMIN;
    private static KeycloakRealmRoleSyncer SYNCER;

    private String userA;
    private String userB;
    private UUID   groupId;

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
    void createUsersInGroup() {
        groupId = UUID.randomUUID();
        userA = createUserWithAccessGroup(groupId);
        userB = createUserWithAccessGroup(groupId);
    }

    @AfterEach
    void cleanup() {
        RealmResource realm = ADMIN.realm(TEST_REALM);
        for (String id : List.of(userA, userB)) {
            try { realm.users().delete(id).close(); } catch (NotFoundException ignored) { }
        }
        // Drop CIA-managed roles so the next test starts clean.
        for (RoleRepresentation r : realm.roles().list()) {
            if (r.getDescription() != null && r.getDescription().startsWith("CIA-managed: ")) {
                try { realm.roles().deleteRole(r.getName()); } catch (NotFoundException ignored) { }
            }
        }
    }

    @Test
    @DisplayName("syncForAllInGroup — applies the group's permissions to every assigned user")
    void fanoutAppliesToAllUsersInGroup() {
        AccessGroup group = newGroup(groupId, "setup:view", "claims:create");

        SYNCER.syncForAllInGroup(group);

        assertUserRolesContain(userA, "setup_view", "claims_create");
        assertUserRolesContain(userB, "setup_view", "claims_create");
    }

    @Test
    @DisplayName("syncForAllInGroup — re-syncs both users when the group's permissions change")
    void fanoutReconcilesAfterPermissionChange() {
        AccessGroup before = newGroup(groupId, "setup:view", "claims:create");
        SYNCER.syncForAllInGroup(before);

        // Group rotates — claims:create dropped, finance:view added.
        AccessGroup after = newGroup(groupId, "setup:view", "finance:view");
        SYNCER.syncForAllInGroup(after);

        assertUserRolesContain(userA, "setup_view", "finance_view");
        assertUserRolesDoNotContain(userA, "claims_create");
        assertUserRolesContain(userB, "setup_view", "finance_view");
        assertUserRolesDoNotContain(userB, "claims_create");
    }

    @Test
    @DisplayName("syncForAllInGroup — ignores users with a different accessGroupId")
    void fanoutScopedToMatchingAttribute() {
        // Create a third user attached to a DIFFERENT access group; the
        // fanout should leave them untouched.
        UUID otherGroupId = UUID.randomUUID();
        String userC = createUserWithAccessGroup(otherGroupId);
        try {
            AccessGroup group = newGroup(groupId, "setup:view");
            SYNCER.syncForAllInGroup(group);

            assertUserRolesContain(userA, "setup_view");
            // userC's realm-role list shouldn't have setup_view (the role
            // would be created by the syncer but never assigned to a user
            // outside the matching attribute).
            assertUserRolesDoNotContain(userC, "setup_view");
        } finally {
            try { ADMIN.realm(TEST_REALM).users().delete(userC).close(); } catch (NotFoundException ignored) { }
        }
    }

    private String createUserWithAccessGroup(UUID accessGroupId) {
        UserRepresentation u = new UserRepresentation();
        u.setUsername("fanout-it-" + UUID.randomUUID());
        u.setEnabled(true);
        // The user attribute that the fanout query looks up. Same shape
        // UserService writes when creating real users (key
        // "accessGroupId", value a single-element list).
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("accessGroupId", List.of(accessGroupId.toString()));
        u.setAttributes(attrs);

        try (Response r = ADMIN.realm(TEST_REALM).users().create(u)) {
            assertThat(r.getStatus()).as("user create").isEqualTo(201);
            String loc = r.getHeaderString("Location");
            return loc.substring(loc.lastIndexOf('/') + 1);
        }
    }

    private void assertUserRolesContain(String userId, String... expected) {
        Set<String> assigned = ADMIN.realm(TEST_REALM).users().get(userId)
                .roles().realmLevel().listAll()
                .stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        assertThat(assigned).contains(expected);
    }

    private void assertUserRolesDoNotContain(String userId, String... unexpected) {
        Set<String> assigned = ADMIN.realm(TEST_REALM).users().get(userId)
                .roles().realmLevel().listAll()
                .stream().map(RoleRepresentation::getName).collect(Collectors.toSet());
        assertThat(assigned).doesNotContain(unexpected);
    }

    /** Build a transient {@link AccessGroup} with an explicit id (matches the
     *  Keycloak user attribute) and the given permission strings. */
    private static AccessGroup newGroup(UUID id, String... perms) {
        AccessGroup g = AccessGroup.builder().name("test-grp-" + id).build();
        g.setId(id);
        List<AccessGroupPermission> list = new ArrayList<>();
        for (String p : perms) {
            list.add(AccessGroupPermission.builder().accessGroup(g).permission(p).build());
        }
        g.setPermissions(list);
        return g;
    }
}
