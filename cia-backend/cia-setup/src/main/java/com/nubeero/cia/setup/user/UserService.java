package com.nubeero.cia.setup.user;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.setup.access.AccessGroup;
import com.nubeero.cia.setup.access.AccessGroupRepository;
import com.nubeero.cia.setup.keycloak.KeycloakRealmRoleSyncer;
import com.nubeero.cia.setup.user.dto.UserRequest;
import com.nubeero.cia.setup.user.dto.UserResponse;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Proxy over the Keycloak admin client for the Module 1 Users surface.
 * Users live in Keycloak — there is no local {@code users} table. The only
 * frontend-shaped field that doesn't have a direct Keycloak counterpart is
 * {@code accessGroupId}, which is stored as a Keycloak user attribute.
 *
 * <p>The {@code Keycloak} bean is conditional on
 * {@code cia.keycloak.admin.enabled=true}. When absent (dev without a
 * Keycloak instance running) every method here throws a {@link
 * KeycloakAdminUnavailableException}; the controller translates that to
 * HTTP 503.
 *
 * <p>Out of scope (F1e-sync backlog row): translating {@code accessGroupId}
 * into the corresponding set of Keycloak realm roles. Today the attribute
 * is bookkeeping only — actual authorisation still flows through whatever
 * roles a Keycloak admin manually assigned in the console.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    /** Keycloak user attribute key carrying the access-group FK. */
    private static final String ATTR_ACCESS_GROUP_ID = "accessGroupId";

    private final ObjectProvider<Keycloak>                  keycloak;
    private final KeycloakAdminProperties                   props;
    private final AccessGroupRepository                     accessGroupRepository;
    /**
     * F1e-sync delegate. Declared as {@link ObjectProvider} so that when the
     * underlying bean isn't a candidate (e.g. {@code cia.keycloak.admin.enabled=false}
     * in tests), the field value is an empty provider rather than a missing
     * bean injection failure. Type is intentionally the syncer class — not
     * any Keycloak admin-client type — to keep new Keycloak symbols out of
     * {@code UserService}'s bytecode (avoids the Session 112 regression).
     */
    private final ObjectProvider<KeycloakRealmRoleSyncer>   roleSyncer;

    public List<UserResponse> list() {
        List<UserRepresentation> reps = realm().users().list();
        return reps.stream().map(this::toResponse).toList();
    }

    public UserResponse get(String id) {
        return toResponse(findOrThrow(id).toRepresentation());
    }

    public UserResponse create(UserRequest request) {
        AccessGroup group = resolveGroup(request.getAccessGroupId());

        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(request.getEmail());
        rep.setEmail(request.getEmail());
        rep.setFirstName(request.getFirstName());
        rep.setLastName(request.getLastName());
        rep.setEnabled(true);
        rep.setEmailVerified(false);
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put(ATTR_ACCESS_GROUP_ID, List.of(group.getId().toString()));
        rep.setAttributes(attrs);

        try (Response response = realm().users().create(rep)) {
            if (response.getStatus() == 409) {
                throw new BusinessRuleException("USER_EMAIL_TAKEN",
                        "A user with this email already exists in the realm.");
            }
            if (response.getStatus() >= 300) {
                throw new BusinessRuleException("KEYCLOAK_CREATE_FAILED",
                        "Keycloak rejected the user-create request: HTTP " + response.getStatus());
            }

            String createdId = extractIdFromLocation(response);
            // Send Keycloak's own action-required email so the user sets
            // their password + verifies email on first sign-in. Failure is
            // non-fatal — the user can still be administered, just without
            // the welcome email.
            try {
                realm().users().get(createdId)
                        .executeActionsEmail(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"));
            } catch (Exception e) {
                log.warn("Welcome email failed for user {}: {}", createdId, e.getMessage());
            }
            syncRealmRoles(createdId, group);
            return toResponse(realm().users().get(createdId).toRepresentation());
        }
    }

    public UserResponse update(String id, UserRequest request) {
        UserResource resource = findOrThrow(id);
        UserRepresentation rep = resource.toRepresentation();

        rep.setFirstName(request.getFirstName());
        rep.setLastName(request.getLastName());
        // Email is intentionally immutable — Keycloak treats it as the
        // effective username; rotating it would invalidate existing JWTs.

        if (request.getStatus() != null) {
            rep.setEnabled(request.getStatus() == UserStatus.ACTIVE);
        }

        AccessGroup group = resolveGroup(request.getAccessGroupId());
        Map<String, List<String>> attrs = rep.getAttributes() == null
                ? new HashMap<>() : new HashMap<>(rep.getAttributes());
        attrs.put(ATTR_ACCESS_GROUP_ID, List.of(group.getId().toString()));
        rep.setAttributes(attrs);

        resource.update(rep);
        syncRealmRoles(id, group);
        return toResponse(resource.toRepresentation());
    }

    public void resetPassword(String id) {
        // executeActionsEmail with UPDATE_PASSWORD is the canonical "reset
        // password" UX — the user gets an email link rather than us
        // generating a temporary password and shipping it out of band.
        findOrThrow(id).executeActionsEmail(List.of("UPDATE_PASSWORD"));
    }

    public UserResponse deactivate(String id) {
        return setEnabled(id, false);
    }

    public UserResponse activate(String id) {
        return setEnabled(id, true);
    }

    // ─── Internals ────────────────────────────────────────────────────────

    /**
     * F1e-sync delegation hook. No-ops when the Keycloak admin client is
     * disabled (test / dev-without-Keycloak). Failures inside the syncer
     * are swallowed by the syncer itself — the DB record is the source of
     * truth and the next user-mutation will re-attempt the realm sync.
     */
    private void syncRealmRoles(String userId, AccessGroup group) {
        KeycloakRealmRoleSyncer s = roleSyncer.getIfAvailable();
        if (s != null) {
            s.syncFor(userId, group);
        }
    }

    private UserResponse setEnabled(String id, boolean enabled) {
        UserResource resource = findOrThrow(id);
        UserRepresentation rep = resource.toRepresentation();
        rep.setEnabled(enabled);
        resource.update(rep);
        return toResponse(resource.toRepresentation());
    }

    private UserResource findOrThrow(String id) {
        UserResource resource = realm().users().get(id);
        try {
            // .toRepresentation() throws NotFoundException when the user
            // doesn't exist — translate into the project's domain exception.
            resource.toRepresentation();
            return resource;
        } catch (NotFoundException nfe) {
            throw new ResourceNotFoundException("User", id);
        }
    }

    private AccessGroup resolveGroup(UUID id) {
        return accessGroupRepository.findById(id)
                .filter(g -> g.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("AccessGroup", id));
    }

    private UserResponse toResponse(UserRepresentation rep) {
        String groupIdAttr = firstAttr(rep, ATTR_ACCESS_GROUP_ID);
        UUID   groupId     = parseUuid(groupIdAttr);
        String groupName   = groupId == null ? null
                : accessGroupRepository.findById(groupId)
                        .filter(g -> g.getDeletedAt() == null)
                        .map(AccessGroup::getName)
                        .orElse(null);

        return UserResponse.builder()
                .id(rep.getId())
                .email(rep.getEmail())
                .firstName(rep.getFirstName())
                .lastName(rep.getLastName())
                .status(resolveStatus(rep))
                .accessGroupId(groupIdAttr)
                .accessGroupName(groupName)
                .createdAt(rep.getCreatedTimestamp() == null
                        ? null : Instant.ofEpochMilli(rep.getCreatedTimestamp()))
                .build();
    }

    private UserStatus resolveStatus(UserRepresentation rep) {
        if (Boolean.FALSE.equals(rep.isEnabled())) return UserStatus.INACTIVE;
        // Keycloak ships brute-force-locked state on the
        // /attack-detection/brute-force/users/{id} endpoint, NOT on the
        // representation. Implementing that read here is a later
        // refinement; today, LOCKED is reserved for the explicit case.
        return UserStatus.ACTIVE;
    }

    private static String firstAttr(UserRepresentation rep, String key) {
        if (rep.getAttributes() == null) return null;
        List<String> values = rep.getAttributes().get(key);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static String extractIdFromLocation(Response response) {
        String location = response.getHeaderString("Location");
        if (location == null) {
            throw new BusinessRuleException("KEYCLOAK_CREATE_NO_LOCATION",
                    "Keycloak returned a 2xx but no Location header — cannot resolve created user id.");
        }
        int idx = location.lastIndexOf('/');
        return idx < 0 ? location : location.substring(idx + 1);
    }

    /**
     * Returns the realm resource, throwing 503-translatable when the admin
     * client is disabled (dev without Keycloak). The controller catches the
     * unchecked exception and shapes the 503 response.
     */
    private org.keycloak.admin.client.resource.RealmResource realm() {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            throw new KeycloakAdminUnavailableException();
        }
        return client.realm(targetRealm());
    }

    /**
     * Single shared realm today — when realm-per-tenant lands this becomes
     * a {@code TenantContext.getTenantId()}-derived lookup. Centralised
     * here so the migration is a one-line change.
     */
    private String targetRealm() {
        return props.getTargetRealm();
    }

    /**
     * Thrown when the admin client isn't available (dev environment with
     * {@code cia.keycloak.admin.enabled=false}). Caught by
     * {@link UserController} and translated to HTTP 503.
     */
    public static class KeycloakAdminUnavailableException extends RuntimeException {
        public KeycloakAdminUnavailableException() {
            super("Keycloak admin client is disabled — set cia.keycloak.admin.enabled=true and configure service-account creds.");
        }
    }

    // Silence unused warning when collecting roles in future iterations.
    @SuppressWarnings("unused")
    private static <T> List<T> safe(List<T> in) {
        return in == null ? List.of() : in;
    }

    @SuppressWarnings("unused")
    private static <T> List<T> distinctList(List<T> in) {
        return in == null ? List.of() : in.stream().distinct().collect(Collectors.toList());
    }
}
