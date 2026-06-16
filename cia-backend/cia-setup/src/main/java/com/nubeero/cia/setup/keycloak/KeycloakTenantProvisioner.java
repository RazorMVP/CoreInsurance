package com.nubeero.cia.setup.keycloak;

import com.nubeero.cia.setup.user.KeycloakAdminProperties;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * F1e-tenant-provisioning (S119) — idempotent Keycloak realm bootstrapper.
 *
 * <p>Ensures the tenant realm exists and carries the realm-level settings
 * the CIA application requires. Called on application startup by
 * {@link KeycloakTenantBootstrap}; can be invoked explicitly from a CLI
 * argument handler to repair an existing realm.
 *
 * <p>Currently enforces:
 * <ol>
 *   <li>Realm exists (created with defaults if missing).</li>
 *   <li>{@code UnmanagedAttributePolicy=ENABLED} on the user-profile config.
 *       Without this, Keycloak 24's default {@code DISABLED} policy silently
 *       drops the implicit {@code accessGroupId} attribute that
 *       {@code UserService.create} writes — breaking the F1e-sync-AccessGroup-fanout
 *       lookup. Surfaced empirically by the S118 Testcontainers IT suite.</li>
 *   <li>The back-office SPA public client exists ({@code cia-back-office} by
 *       default) with auth-code + PKCE(S256), the realm's redirect URIs /
 *       web origins, and a hardcoded {@code tenant_id} claim mapper whose
 *       value is the realm name. Without this client the SPA login fails with
 *       "Client not found"; without the mapper the backend's
 *       {@code TenantContextFilter} can't resolve the tenant from the JWT.
 *       The mapper is <em>hardcoded</em> (claim value = realm name) rather
 *       than a per-user attribute so every user in the realm gets the right
 *       tenant automatically — realm-per-tenant means realm name IS the
 *       tenant id.</li>
 *   <li>The realm login theme is set to {@code backOfficeLoginTheme} when that
 *       property is non-blank (default blank = leave untouched). Used to apply
 *       the {@code nubsure} branded login theme in deployed environments
 *       without affecting ITs or un-themed Keycloaks.</li>
 * </ol>
 *
 * <p><b>Not provisioned here:</b> per-partner OAuth2 service-account clients.
 * Those are created on demand by Partner Management (Setup module) when a
 * System Admin onboards an Insurtech — they're per-partner, not a realm-level
 * invariant — so they don't belong in realm bootstrap.
 *
 * <p>Future realm-level invariants (custom realm attributes, default
 * client scopes, role hierarchy seed, etc.) extend the same idempotent
 * pattern — read current state, mutate only if needed, write back.
 *
 * <p>Encapsulation. Same pattern as {@link KeycloakRealmRoleSyncer} +
 * {@link KeycloakPasswordPolicySyncer}: every Keycloak admin-client type
 * reference lives inside this class. Callers see it as a plain Spring
 * service. Conditional on {@code cia.keycloak.admin.enabled=true} so the
 * bean (and its Keycloak class graph) stays absent in IT runs where admin
 * is disabled.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cia.keycloak.admin", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class KeycloakTenantProvisioner {

    private final ObjectProvider<Keycloak>  keycloak;
    private final KeycloakAdminProperties   props;

    /**
     * Idempotent. Safe to call on every application boot.
     *
     * <p>If the Keycloak admin client is unavailable at runtime (rare:
     * {@code cia.keycloak.admin.enabled=true} but the client failed to
     * resolve at startup), logs a warning and returns — the DB / config
     * is the source of truth, and the next call retries.
     */
    public void provisionTenantRealm(String realmName) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.warn("Keycloak admin client unavailable — skipping tenant realm provisioning for {}", realmName);
            return;
        }
        ensureRealm(client, realmName);
        ensureUnmanagedAttributePolicy(client, realmName);
        ensureBackOfficeClient(client, realmName);
        ensureLoginTheme(client, realmName);
    }

    /**
     * Convenience overload — provisions the realm named by
     * {@link KeycloakAdminProperties#getTargetRealm()}.
     */
    public void provisionTargetRealm() {
        provisionTenantRealm(props.getTargetRealm());
    }

    private void ensureRealm(Keycloak client, String realmName) {
        try {
            client.realm(realmName).toRepresentation();
            log.info("Tenant realm '{}' exists", realmName);
        } catch (NotFoundException nfe) {
            RealmRepresentation rep = new RealmRepresentation();
            rep.setRealm(realmName);
            rep.setEnabled(true);
            client.realms().create(rep);
            log.info("Tenant realm '{}' created", realmName);
        }
    }

    private void ensureUnmanagedAttributePolicy(Keycloak client, String realmName) {
        UPConfig upc = client.realm(realmName).users().userProfile().getConfiguration();
        UPConfig.UnmanagedAttributePolicy current = upc.getUnmanagedAttributePolicy();
        if (current != UPConfig.UnmanagedAttributePolicy.ENABLED) {
            upc.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
            client.realm(realmName).users().userProfile().update(upc);
            log.info("Tenant realm '{}' — set UnmanagedAttributePolicy=ENABLED (was {})", realmName, current);
        } else {
            log.debug("Tenant realm '{}' — UnmanagedAttributePolicy already ENABLED", realmName);
        }
    }

    /**
     * Idempotent upsert of the back-office SPA public client. On first run it
     * creates the client (with the {@code tenant_id} mapper embedded); on
     * later runs it reconciles redirect URIs / web origins / PKCE drift and
     * ensures the mapper is present.
     */
    private void ensureBackOfficeClient(Keycloak client, String realmName) {
        RealmResource realm = client.realm(realmName);
        String clientId = props.getBackOfficeClientId();
        List<ClientRepresentation> found = realm.clients().findByClientId(clientId);

        if (found.isEmpty()) {
            ClientRepresentation rep = desiredBackOfficeClient(realmName);
            rep.setProtocolMappers(List.of(tenantIdMapper(realmName)));
            try (Response resp = realm.clients().create(rep)) {
                if (resp.getStatus() >= 300) {
                    log.warn("Tenant realm '{}' — back-office client '{}' create returned HTTP {}",
                            realmName, clientId, resp.getStatus());
                    return;
                }
            }
            log.info("Tenant realm '{}' — created back-office client '{}' (PKCE S256, redirects {})",
                    realmName, clientId, props.getBackOfficeRedirectUris());
            return;
        }

        // Reconcile the existing client toward the desired shape.
        ClientRepresentation existing = found.get(0);
        ClientRepresentation desired = desiredBackOfficeClient(realmName);
        boolean changed = false;

        if (!Boolean.TRUE.equals(existing.isPublicClient())) { existing.setPublicClient(true); changed = true; }
        if (!Boolean.TRUE.equals(existing.isStandardFlowEnabled())) { existing.setStandardFlowEnabled(true); changed = true; }
        if (!desired.getRedirectUris().equals(existing.getRedirectUris())) {
            existing.setRedirectUris(desired.getRedirectUris()); changed = true;
        }
        if (!desired.getWebOrigins().equals(existing.getWebOrigins())) {
            existing.setWebOrigins(desired.getWebOrigins()); changed = true;
        }
        Map<String, String> attrs = existing.getAttributes() != null
                ? new HashMap<>(existing.getAttributes()) : new HashMap<>();
        if (!"S256".equals(attrs.get("pkce.code.challenge.method"))) {
            attrs.put("pkce.code.challenge.method", "S256");
            existing.setAttributes(attrs);
            changed = true;
        }
        if (changed) {
            realm.clients().get(existing.getId()).update(existing);
            log.info("Tenant realm '{}' — reconciled back-office client '{}'", realmName, clientId);
        } else {
            log.debug("Tenant realm '{}' — back-office client '{}' already conformant", realmName, clientId);
        }
        ensureTenantIdMapper(realm, existing.getId(), realmName);
    }

    /**
     * Sets the realm's login theme to the configured value, idempotently.
     * No-op when {@code backOfficeLoginTheme} is blank (the default) — so ITs
     * and Keycloaks without the theme mounted are never touched.
     */
    private void ensureLoginTheme(Keycloak client, String realmName) {
        String theme = props.getBackOfficeLoginTheme();
        if (theme == null || theme.isBlank()) {
            return;
        }
        RealmRepresentation rep = client.realm(realmName).toRepresentation();
        if (!theme.equals(rep.getLoginTheme())) {
            rep.setLoginTheme(theme);
            client.realm(realmName).update(rep);
            log.info("Tenant realm '{}' — set loginTheme='{}'", realmName, theme);
        } else {
            log.debug("Tenant realm '{}' — loginTheme already '{}'", realmName, theme);
        }
    }

    private ClientRepresentation desiredBackOfficeClient(String realmName) {
        ClientRepresentation rep = new ClientRepresentation();
        rep.setClientId(props.getBackOfficeClientId());
        rep.setName("NubSure Back Office");
        rep.setEnabled(true);
        rep.setPublicClient(true);
        rep.setStandardFlowEnabled(true);          // auth-code flow
        rep.setDirectAccessGrantsEnabled(false);   // no password grant for the SPA
        rep.setRedirectUris(props.getBackOfficeRedirectUris());
        rep.setWebOrigins(List.of("+"));           // "+" = derive from redirect URIs
        Map<String, String> attrs = new HashMap<>();
        attrs.put("pkce.code.challenge.method", "S256");
        attrs.put("post.logout.redirect.uris", "+"); // "+" = same as redirect URIs
        rep.setAttributes(attrs);
        return rep;
    }

    /**
     * Hardcoded {@code tenant_id} claim mapper — emits the realm name as the
     * tenant id on every token. Realm-per-tenant: realm name IS the tenant id,
     * so no per-user attribute is required.
     */
    private ProtocolMapperRepresentation tenantIdMapper(String realmName) {
        ProtocolMapperRepresentation m = new ProtocolMapperRepresentation();
        m.setName("tenant_id");
        m.setProtocol("openid-connect");
        m.setProtocolMapper("oidc-hardcoded-claim-mapper");
        Map<String, String> cfg = new HashMap<>();
        cfg.put("claim.name", "tenant_id");
        cfg.put("claim.value", realmName);
        cfg.put("jsonType.label", "String");
        cfg.put("id.token.claim", "true");
        cfg.put("access.token.claim", "true");
        cfg.put("userinfo.token.claim", "true");
        m.setConfig(cfg);
        return m;
    }

    private void ensureTenantIdMapper(RealmResource realm, String clientUuid, String realmName) {
        List<ProtocolMapperRepresentation> mappers =
                realm.clients().get(clientUuid).getProtocolMappers().getMappers();
        boolean present = mappers != null && mappers.stream()
                .anyMatch(m -> "tenant_id".equals(m.getName()));
        if (!present) {
            try (Response resp = realm.clients().get(clientUuid)
                    .getProtocolMappers().createMapper(tenantIdMapper(realmName))) {
                if (resp.getStatus() >= 300) {
                    log.warn("Tenant realm '{}' — tenant_id mapper create returned HTTP {}",
                            realmName, resp.getStatus());
                    return;
                }
            }
            log.info("Tenant realm '{}' — added tenant_id claim mapper to back-office client", realmName);
        }
    }

    /**
     * Idempotently creates every canonical bootstrap realm role ({@link BootstrapRoles#ALL}).
     * Roles that already exist are left untouched (DEBUG). New roles are created with a
     * {@code "CIA-managed: bootstrap role"} description — the same {@code CIA-managed:} prefix
     * used by {@link KeycloakRealmRoleSyncer} — so the syncer recognises them as managed roles
     * and the two components don't fight over ownership.
     *
     * <p>Called explicitly by provisioning tasks and integration tests. Not wired into
     * {@link #provisionTenantRealm} yet — the next task (ensureFirstAdminUser) adds the
     * call-site there once the user-creation path depends on the roles existing.
     */
    public void ensureRealmRoles(Keycloak client, String realmName) {
        var roles = client.realm(realmName).roles();
        for (String roleName : BootstrapRoles.ALL) {
            try {
                roles.get(roleName).toRepresentation();   // exists → no-op
                log.debug("Tenant realm '{}' — role '{}' already present", realmName, roleName);
            } catch (NotFoundException nfe) {
                RoleRepresentation rep = new RoleRepresentation();
                rep.setName(roleName);
                rep.setDescription("CIA-managed: bootstrap role");
                roles.create(rep);
                log.info("Tenant realm '{}' — created role '{}'", realmName, roleName);
            }
        }
    }

    /**
     * Idempotently create the bootstrap first-admin user: temporary password (forces
     * UPDATE_PASSWORD on first login), accessGroupId attribute, and every canonical
     * realm role assigned directly.
     *
     * <p>Mirrors {@code UserService.create} but uses a temp password instead of an
     * email action — no SMTP dependency at first boot.
     *
     * <p>Requires {@link #ensureUnmanagedAttributePolicy} to have run on the realm
     * first; otherwise Keycloak 24's default DISABLED policy silently drops the
     * {@code accessGroupId} attribute. {@link #provisionTenantAuth} guarantees this
     * ordering; callers that invoke this method directly (e.g. ITs) must ensure the
     * policy is set beforehand (the shared test realm has it set by
     * {@code ensureTestRealm()}).
     *
     * <p>Delegates to {@link #ensureFirstAdminUser(Keycloak, String, FirstAdminSpec, List)}
     * with {@link BootstrapRoles#ALL} — preserves the existing tenant-provisioning contract.
     */
    public void ensureFirstAdminUser(Keycloak client, String realmName, FirstAdminSpec spec) {
        ensureFirstAdminUser(client, realmName, spec, BootstrapRoles.ALL);
    }

    /**
     * Role-list overload — assigns only the supplied {@code roleNames} to the created user.
     * Used by {@link #provisionPlatformRealm} to assign only {@link PlatformRoles#ALL}
     * to the platform super-admin (keeping tenant roles out of the platform realm entirely).
     */
    public void ensureFirstAdminUser(Keycloak client, String realmName, FirstAdminSpec spec,
                                     List<String> roleNames) {
        var realm = client.realm(realmName);
        var existing = realm.users().search(spec.username(), true);
        if (!existing.isEmpty()) {
            log.debug("Tenant realm '{}' — first-admin '{}' already present", realmName, spec.username());
            return;
        }

        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(spec.username());
        rep.setEmail(spec.email());
        rep.setFirstName(spec.firstName());
        rep.setLastName(spec.lastName());
        rep.setEnabled(true);
        rep.setEmailVerified(false);
        rep.setRequiredActions(List.of("UPDATE_PASSWORD"));
        rep.setAttributes(Map.of(
                "accessGroupId", List.of(spec.accessGroupId().toString())));

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(spec.tempPassword());
        cred.setTemporary(true);   // forces reset on first login
        rep.setCredentials(List.of(cred));

        String userId;
        try (Response resp = realm.users().create(rep)) {
            if (resp.getStatus() >= 300) {
                throw new IllegalStateException(
                        "Keycloak first-admin create returned HTTP " + resp.getStatus()
                        + " for realm " + realmName);
            }
            String location = resp.getHeaderString("Location");
            if (location == null) {
                throw new IllegalStateException(
                    "Keycloak created the first-admin user but returned no Location header for realm " + realmName);
            }
            userId = location.substring(location.lastIndexOf('/') + 1);
        }

        List<RoleRepresentation> realmRoles = roleNames.stream()
                .map(r -> realm.roles().get(r).toRepresentation())
                .toList();
        realm.users().get(userId).roles().realmLevel().add(realmRoles);
        log.info("Tenant realm '{}' — created first-admin '{}' with {} roles",
                realmName, spec.username(), realmRoles.size());
    }

    /**
     * Full auth-plane provisioning for one tenant: realm + unmanaged-attribute policy
     * + back-office client + login theme + realm roles + first admin user.
     *
     * <p>This is the single entry point the tenant-provisioning orchestrator calls.
     * Throws immediately if the Keycloak admin client is unavailable (fail-fast),
     * unlike {@link #provisionTenantRealm} which logs-and-returns.
     */
    public void provisionTenantAuth(String realmName, FirstAdminSpec spec) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "Keycloak admin client unavailable — cannot provision tenant auth for " + realmName);
        }
        ensureRealm(client, realmName);
        ensureUnmanagedAttributePolicy(client, realmName);
        ensureBackOfficeClient(client, realmName);
        ensureLoginTheme(client, realmName);
        ensureRealmRoles(client, realmName);
        ensureFirstAdminUser(client, realmName, spec);
    }

    // -------------------------------------------------------------------------
    // Platform realm provisioning
    // -------------------------------------------------------------------------

    /**
     * Provisions the platform realm: realm + unmanaged-attribute policy +
     * SUPER_ADMIN role + cia-platform SPA client + first super-admin (assigned
     * ONLY {@link PlatformRoles#ALL}, never tenant roles). Idempotent.
     *
     * <p>The platform realm is deliberately kept separate from every tenant
     * realm. A token from a tenant realm will never carry SUPER_ADMIN because
     * that role only exists in the platform realm. The platform realm carries
     * no {@code tenant_id} claim. A platform-realm token still passes the
     * base-URL issuer trust check in the tenant resource server, but fails
     * closed at the schema/tenant-context layer (it resolves to no tenant
     * schema); a registry allowlist gate at the auth layer is tracked as
     * backlog item jwt-resolver-registry-allowlist.
     */
    public void provisionPlatformRealm(String realmName, String platformClientId,
                                       List<String> redirectUris, FirstAdminSpec spec) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.warn("Keycloak admin client unavailable — skipping platform realm provisioning for {}", realmName);
            return;
        }
        ensureRealm(client, realmName);
        ensureUnmanagedAttributePolicy(client, realmName);
        ensurePlatformClient(client, realmName, platformClientId, redirectUris);
        ensurePlatformRoles(client, realmName);
        // spec.accessGroupId() has no backing access_group row in the platform realm
        // (the platform realm has no tenant DB) — it is carried only because the shared
        // FirstAdminSpec / ensureFirstAdminUser signature requires it.
        ensureFirstAdminUser(client, realmName, spec, PlatformRoles.ALL);
        log.info("Platform realm '{}' provisioned (SUPER_ADMIN + {} client)", realmName, platformClientId);
    }

    /**
     * Seeds {@link PlatformRoles#ALL} into the given realm. MUST only be called
     * on the platform realm — seeding SUPER_ADMIN into a tenant realm would
     * create a cross-tenant escalation path. Idempotent.
     *
     * <p>Only {@code SUPER_ADMIN} today; the list may grow as new
     * cross-tenant authorities are defined.
     */
    public void ensurePlatformRoles(Keycloak client, String realmName) {
        var roles = client.realm(realmName).roles();
        for (String roleName : PlatformRoles.ALL) {
            try {
                roles.get(roleName).toRepresentation();   // exists → no-op
                log.debug("Platform realm '{}' — role '{}' already present", realmName, roleName);
            } catch (NotFoundException nfe) {
                RoleRepresentation rep = new RoleRepresentation();
                rep.setName(roleName);
                rep.setDescription("CIA-managed: platform role");
                roles.create(rep);
                log.info("Platform realm '{}' — created role '{}'", realmName, roleName);
            }
        }
    }

    /**
     * Idempotent upsert of the platform SPA public client. Mirrors
     * {@link #ensureBackOfficeClient} exactly in structure (create-then-reconcile,
     * auth-code + PKCE S256, web origins derived from redirects via {@code "+"}),
     * with two differences:
     * <ol>
     *   <li>Uses the caller-supplied {@code platformClientId} and {@code redirectUris}.</li>
     *   <li>Does NOT attach a {@code tenant_id} protocol mapper — the platform realm
     *       is not a tenant, so no tenant_id stamp is appropriate.</li>
     * </ol>
     */
    private void ensurePlatformClient(Keycloak client, String realmName,
                                      String platformClientId, List<String> redirectUris) {
        RealmResource realm = client.realm(realmName);
        List<ClientRepresentation> found = realm.clients().findByClientId(platformClientId);

        if (found.isEmpty()) {
            ClientRepresentation rep = desiredPlatformClient(platformClientId, redirectUris);
            try (Response resp = realm.clients().create(rep)) {
                if (resp.getStatus() >= 300) {
                    log.warn("Platform realm '{}' — platform client '{}' create returned HTTP {}",
                            realmName, platformClientId, resp.getStatus());
                    return;
                }
            }
            log.info("Platform realm '{}' — created platform client '{}' (PKCE S256, redirects {})",
                    realmName, platformClientId, redirectUris);
            return;
        }

        // Reconcile the existing client toward the desired shape.
        ClientRepresentation existing = found.get(0);
        ClientRepresentation desired = desiredPlatformClient(platformClientId, redirectUris);
        boolean changed = false;

        if (!Boolean.TRUE.equals(existing.isPublicClient())) { existing.setPublicClient(true); changed = true; }
        if (!Boolean.TRUE.equals(existing.isStandardFlowEnabled())) { existing.setStandardFlowEnabled(true); changed = true; }
        if (Boolean.TRUE.equals(existing.isDirectAccessGrantsEnabled())) {
            existing.setDirectAccessGrantsEnabled(false);
            changed = true;
        }
        if (!desired.getRedirectUris().equals(existing.getRedirectUris())) {
            existing.setRedirectUris(desired.getRedirectUris()); changed = true;
        }
        if (!desired.getWebOrigins().equals(existing.getWebOrigins())) {
            existing.setWebOrigins(desired.getWebOrigins()); changed = true;
        }
        Map<String, String> attrs = existing.getAttributes() != null
                ? new HashMap<>(existing.getAttributes()) : new HashMap<>();
        if (!"S256".equals(attrs.get("pkce.code.challenge.method"))) {
            attrs.put("pkce.code.challenge.method", "S256");
            existing.setAttributes(attrs);
            changed = true;
        }
        if (changed) {
            realm.clients().get(existing.getId()).update(existing);
            log.info("Platform realm '{}' — reconciled platform client '{}'", realmName, platformClientId);
        } else {
            log.debug("Platform realm '{}' — platform client '{}' already conformant", realmName, platformClientId);
        }
        // No tenant_id mapper ensured — platform realm has no tenant identity.
    }

    private ClientRepresentation desiredPlatformClient(String platformClientId,
                                                        List<String> redirectUris) {
        ClientRepresentation rep = new ClientRepresentation();
        rep.setClientId(platformClientId);
        rep.setName("NubSure Platform Admin");
        rep.setEnabled(true);
        rep.setPublicClient(true);
        rep.setStandardFlowEnabled(true);          // auth-code flow
        rep.setDirectAccessGrantsEnabled(false);   // no password grant for the SPA
        rep.setRedirectUris(redirectUris);
        rep.setWebOrigins(List.of("+"));           // "+" = derive from redirect URIs
        Map<String, String> attrs = new HashMap<>();
        attrs.put("pkce.code.challenge.method", "S256");
        attrs.put("post.logout.redirect.uris", "+"); // "+" = same as redirect URIs
        rep.setAttributes(attrs);
        return rep;
    }

    // -------------------------------------------------------------------------
    // Platform super-admin lifecycle (create / list / count / remove-role)
    // -------------------------------------------------------------------------

    /** Read shape for a platform super-admin (a user holding the SUPER_ADMIN realm role). */
    public record SuperAdminView(String username, String email, boolean enabled) {}

    /** Thrown by {@link #createSuperAdmin} when the username already exists in the realm. */
    public static class SuperAdminExistsInRealm extends RuntimeException {
        public SuperAdminExistsInRealm(String username) {
            super("A user named '" + username + "' already exists in the platform realm.");
        }
    }

    /**
     * Creates a new super-admin in the platform realm: enabled user, temp password
     * (forces UPDATE_PASSWORD on first login), assigned ONLY {@link PlatformRoles#ALL}.
     * No accessGroupId attribute — the platform realm has no access groups.
     *
     * @throws SuperAdminExistsInRealm if a user with this username already exists
     */
    public void createSuperAdmin(Keycloak client, String realmName,
                                 String username, String email, String tempPassword) {
        var realm = client.realm(realmName);
        if (!realm.users().search(username, true).isEmpty()) {
            throw new SuperAdminExistsInRealm(username);
        }
        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(username);
        rep.setEmail(email);
        rep.setEnabled(true);
        rep.setEmailVerified(false);
        rep.setRequiredActions(List.of("UPDATE_PASSWORD"));

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(tempPassword);
        cred.setTemporary(true);
        rep.setCredentials(List.of(cred));

        String userId;
        try (Response resp = realm.users().create(rep)) {
            if (resp.getStatus() >= 300) {
                throw new IllegalStateException(
                        "Keycloak super-admin create returned HTTP " + resp.getStatus()
                        + " for realm " + realmName);
            }
            String location = resp.getHeaderString("Location");
            if (location == null) {
                throw new IllegalStateException(
                    "Keycloak created the super-admin user but returned no Location header for realm " + realmName);
            }
            userId = location.substring(location.lastIndexOf('/') + 1);
        }
        List<RoleRepresentation> roles = PlatformRoles.ALL.stream()
                .map(r -> realm.roles().get(r).toRepresentation())
                .toList();
        realm.users().get(userId).roles().realmLevel().add(roles);
        log.info("Platform realm '{}' — created super-admin '{}'", realmName, username);
    }

    /**
     * Lists every user holding the SUPER_ADMIN realm role.
     *
     * <p>{@code getUserMembers()} returns Keycloak's default first page (~100). Super-admins are
     * deliberately few, so this is sufficient; add a paginated variant only if that ever changes.
     */
    public List<SuperAdminView> listSuperAdmins(Keycloak client, String realmName) {
        return client.realm(realmName).roles().get(PlatformRoles.SUPER_ADMIN).getUserMembers().stream()
                .map(u -> new SuperAdminView(u.getUsername(), u.getEmail(),
                        u.isEnabled() != null && u.isEnabled()))
                .toList();
    }

    /** Number of users holding the SUPER_ADMIN realm role (backs the last-admin guard). */
    public long superAdminCount(Keycloak client, String realmName) {
        return client.realm(realmName).roles().get(PlatformRoles.SUPER_ADMIN).getUserMembers().size();
    }

    /**
     * Removes the SUPER_ADMIN realm-role mapping from the named user (does NOT delete the
     * account). Throws {@link NotFoundException} if the user doesn't exist.
     */
    public void removeSuperAdminRole(Keycloak client, String realmName, String username) {
        var realm = client.realm(realmName);
        var matches = realm.users().search(username, true);
        if (matches.isEmpty()) {
            throw new NotFoundException("No user '" + username + "' in realm " + realmName);
        }
        String userId = matches.get(0).getId();
        RoleRepresentation superAdmin = realm.roles().get(PlatformRoles.SUPER_ADMIN).toRepresentation();
        realm.users().get(userId).roles().realmLevel().remove(List.of(superAdmin));
        log.info("Platform realm '{}' — removed SUPER_ADMIN from '{}'", realmName, username);
    }
}
