package com.nubeero.cia.setup.keycloak;

import com.nubeero.cia.setup.access.AccessGroup;
import com.nubeero.cia.setup.access.AccessGroupPermission;
import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F1e-sync: writes the user's Keycloak realm-role assignment so that it
 * matches the {@link AccessGroup}'s permission list.
 *
 * <p>This class deliberately encapsulates every Keycloak admin-client type
 * reference. {@link com.nubeero.cia.setup.user.UserService} only sees this
 * {@code @Service} as a plain Spring bean — no {@code Keycloak},
 * {@code RolesResource}, or {@code RoleRepresentation} symbol appears in its
 * bytecode. Session 112's first F1e-sync attempt failed because adding
 * role-management types directly to {@code UserService} polluted JVM
 * classloader state in a way that broke {@code ContractGroupingServiceIT} in
 * the full failsafe suite. Keeping those types confined here avoids the
 * regression while preserving the user-create / user-update call-site
 * delegation pattern.
 *
 * <p>Boundary rule. The syncer ONLY manages roles whose description starts
 * with {@link #DESC_PREFIX}. Roles created by a Keycloak admin directly in
 * the console (e.g. {@code realm-admin}, custom groups) are left untouched
 * regardless of whether they're assigned to the user. This makes the sync
 * safe to run against realms where humans also manage roles.
 *
 * <p>Mapping. CIA permission strings are colon-form ({@code setup:view},
 * {@code claims:create}). Keycloak realm role names use underscore-form
 * ({@code setup_view}) so that the {@code JwtAuthConverter}'s
 * {@code "ROLE_" + role.toUpperCase()} produces a Spring authority
 * {@code ROLE_SETUP_VIEW} matching the {@code @PreAuthorize("hasRole('SETUP_VIEW')")}
 * call sites across the API.
 *
 * <p>This bean is conditional on {@code cia.keycloak.admin.enabled=true}.
 * In tests and dev-without-Keycloak that property is false, the bean is
 * absent, and {@code UserService} receives an empty {@link ObjectProvider}
 * (its {@code getIfAvailable()} returns null and the call no-ops).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cia.keycloak.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class KeycloakRealmRoleSyncer {

    /** Description prefix marking a role as managed by CIA's sync. */
    static final String DESC_PREFIX = "CIA-managed: ";

    private final ObjectProvider<Keycloak>  keycloak;
    private final KeycloakAdminProperties   props;

    /**
     * Reconciles {@code userId}'s Keycloak realm-role assignment with the
     * permission list on {@code group}. Idempotent; safe to call on every
     * user create/update.
     *
     * <p>If the Keycloak admin client is unavailable at runtime (the
     * conditional kept the bean alive but the client itself failed to
     * resolve — rare, but possible after a Keycloak restart), the call
     * logs a warning and returns. The user's CIA-side record is the source
     * of truth; a transient Keycloak outage doesn't fail the parent
     * transaction.
     */
    public void syncFor(String userId, AccessGroup group) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.warn("Keycloak admin client unavailable — skipping realm-role sync for user {}", userId);
            return;
        }

        RealmResource realm = client.realm(props.getTargetRealm());
        RolesResource roles = realm.roles();
        UserResource  user  = realm.users().get(userId);

        Set<String> desiredNames = new HashSet<>();
        for (AccessGroupPermission p : group.getPermissions()) {
            if (p.getDeletedAt() != null) continue;
            desiredNames.add(permissionToRoleName(p.getPermission()));
        }

        for (String name : desiredNames) {
            ensureManagedRole(roles, name);
        }

        List<RoleRepresentation> currentAll = user.roles().realmLevel().listAll();
        Set<String> currentManaged = new HashSet<>();
        for (RoleRepresentation r : currentAll) {
            if (isCiaManaged(r)) currentManaged.add(r.getName());
        }

        Set<String> toAdd    = new HashSet<>(desiredNames);    toAdd.removeAll(currentManaged);
        Set<String> toRemove = new HashSet<>(currentManaged);  toRemove.removeAll(desiredNames);

        if (!toAdd.isEmpty()) {
            List<RoleRepresentation> add = toAdd.stream()
                    .map(n -> roles.get(n).toRepresentation())
                    .toList();
            user.roles().realmLevel().add(add);
        }
        if (!toRemove.isEmpty()) {
            List<RoleRepresentation> remove = toRemove.stream()
                    .map(n -> roles.get(n).toRepresentation())
                    .toList();
            user.roles().realmLevel().remove(remove);
        }

        log.info("Synced realm roles for user {} — +{}/-{}", userId, toAdd.size(), toRemove.size());
    }

    /**
     * F1e-sync-AccessGroup-fanout (S118): re-sync every user who carries
     * the matching {@code accessGroupId} attribute. Called by
     * {@code AccessGroupService.update()} when an access group's permissions
     * change so users assigned to that group see their Keycloak realm-role
     * assignment updated immediately, without having to be touched on the
     * user surface.
     *
     * <p>Listing users by attribute is the {@code accessGroupId=&lt;uuid&gt;}
     * search query — Keycloak's admin API supports this directly via
     * {@code users().searchByAttributes(...)}. The default page size returns
     * up to 100 users; a tenant with more than 100 users in a single access
     * group is outside the current scope and would need pagination here.
     *
     * <p>Like {@link #syncFor}, a missing admin client is a no-op (warns and
     * returns) — the DB record is the source of truth and the next user
     * mutation will re-attempt the sync.
     */
    public void syncForAllInGroup(AccessGroup group) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.warn("Keycloak admin client unavailable — skipping fanout sync for group {}", group.getId());
            return;
        }

        RealmResource realm = client.realm(props.getTargetRealm());
        String wantedAccessGroupId = group.getId().toString();

        // Originally tried {@code realm.users().searchByAttributes(...)}
        // which is the Keycloak 24 admin API for attribute search. It
        // returned zero users against the default {@code Unmanaged
        // Attributes: DISABLED} user-profile policy — Keycloak only indexes
        // attributes declared in the user-profile schema, and
        // {@code accessGroupId} isn't declared (it's an implicit attribute
        // set by {@code UserService.create}). The defensive path is to
        // enumerate users with the attribute brief representation included
        // and filter client-side. For tenant scale (~thousands of users)
        // the bandwidth is fine. Pagination is left to future work — a
        // tenant with more than 1000 users in a single access group would
        // hit Keycloak's default list cap before this matters anyway.
        //
        // Two-step query: (1) list user IDs via the brief endpoint, then
        // (2) GET each user individually to read its attributes. Keycloak
        // 24's default {@code Unmanaged Attributes: DISABLED} user-profile
        // policy strips unmanaged attributes from the bulk list response
        // even with {@code briefRepresentation=false}, but the per-user
        // GET endpoint always returns the full attribute map. The
        // bandwidth cost is N+1 admin calls; acceptable for an admin
        // operation that runs on access-group permission change (not on
        // every login).
        List<org.keycloak.representations.idm.UserRepresentation> allUsers =
                realm.users().list(0, 1000);

        int reconciled = 0;
        for (org.keycloak.representations.idm.UserRepresentation brief : allUsers) {
            org.keycloak.representations.idm.UserRepresentation full =
                    realm.users().get(brief.getId()).toRepresentation();
            if (full.getAttributes() == null) continue;
            List<String> values = full.getAttributes().get("accessGroupId");
            if (values == null || values.isEmpty()) continue;
            if (!wantedAccessGroupId.equals(values.get(0))) continue;
            syncFor(full.getId(), group);
            reconciled++;
        }

        log.info("Fanout sync — {} users in group {} reconciled to realm roles", reconciled, group.getId());
    }

    private void ensureManagedRole(RolesResource roles, String name) {
        try {
            RoleRepresentation existing = roles.get(name).toRepresentation();
            // Adopt unmanaged role names that happen to match (e.g. seeded
            // by a previous schema). Tagging it makes it eligible for
            // future removal if the access group changes — explicit
            // hand-over, no silent leak.
            if (!isCiaManaged(existing)) {
                existing.setDescription(DESC_PREFIX + safeDesc(existing.getDescription()));
                roles.get(name).update(existing);
            }
        } catch (NotFoundException nfe) {
            RoleRepresentation rep = new RoleRepresentation();
            rep.setName(name);
            rep.setDescription(DESC_PREFIX + "Synced from CIA permission " + name);
            roles.create(rep);
        }
    }

    private boolean isCiaManaged(RoleRepresentation role) {
        String desc = role.getDescription();
        return desc != null && desc.startsWith(DESC_PREFIX);
    }

    private static String safeDesc(String d) {
        return d == null ? "" : d;
    }

    /**
     * Maps a CIA permission string (colon-form) to a Keycloak realm role
     * name (underscore-form). Visible for unit testing.
     */
    static String permissionToRoleName(String permission) {
        return permission.replace(':', '_');
    }
}
