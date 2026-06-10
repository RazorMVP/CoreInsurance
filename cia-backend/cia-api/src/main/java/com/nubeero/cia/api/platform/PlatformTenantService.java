package com.nubeero.cia.api.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.platform.dto.OnboardTenantRequest;
import com.nubeero.cia.api.platform.dto.OnboardTenantResponse;
import com.nubeero.cia.api.platform.dto.TenantSummary;
import com.nubeero.cia.api.tenant.TenantBootstrapProperties.TenantSpec;
import com.nubeero.cia.api.tenant.TenantProvisioningService;
import com.nubeero.cia.api.tenant.TenantRegistry;
import com.nubeero.cia.auth.TenantActivationLookup;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.tenant.TenantSchemas;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Orchestrates platform-level tenant lifecycle operations:
 * <ul>
 *   <li>Onboard — provision schema + Keycloak realm + first admin + registry row, return credentials once.</li>
 *   <li>List / Get — read-only views of the tenant registry.</li>
 *   <li>Suspend / Activate — flip {@code public.tenants.active} + evict the activation cache immediately.</li>
 * </ul>
 *
 * <p>All mutating operations append a row to {@code public.platform_audit_log} via
 * {@link PlatformAuditService}. The temporary password is generated server-side, returned once
 * in the {@link OnboardTenantResponse}, and NEVER written to any log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformTenantService {

    private final TenantProvisioningService provisioning;
    private final TenantRegistry registry;
    private final TenantActivationLookup activationLookup;
    private final PlatformAuditService audit;
    private final ObjectMapper objectMapper; // Spring's configured bean — for safe detail JSON

    private static final SecureRandom RNG = new SecureRandom();

    /**
     * Onboards a new tenant end-to-end: validates uniqueness, generates a temporary password,
     * delegates to {@link TenantProvisioningService#provision}, then audits and returns the
     * registry summary + first-admin credentials.
     *
     * <p>Onboard is safe to retry after a partial failure: every
     * {@link TenantProvisioningService#provision} step (schema create, Flyway migrate, seed,
     * Keycloak realm/admin, registry upsert) is idempotent, and the registry row is written
     * last — so a retry resumes cleanly and only "succeeds" once the tenant is fully provisioned.
     *
     * @param req        validated request body
     * @param actor      username of the super-admin calling the operation (from JWT)
     * @param actorRealm realm the super-admin authenticated against
     * @param ip         source IP of the HTTP request (for audit)
     * @return onboard result including the one-time temporary password
     * @throws TenantAlreadyExistsException if the schema name or subdomain is already registered
     */
    public OnboardTenantResponse onboard(
            OnboardTenantRequest req, String actor, String actorRealm, String ip) {

        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(actorRealm, "actorRealm must not be null");
        TenantSchemas.validate(req.schema());
        String realm = (req.realm() == null || req.realm().isBlank()) ? req.schema() : req.realm();
        // Realm-per-tenant routing invariant: the realm IS the tenant identifier. TenantContextFilter
        // routes by the validated iss realm, the tenant_id mapper stamps the realm name, and the
        // allowlist gate matches public.tenants.schema_name = realm — all of which assume realm ==
        // schema. A divergent realm would silently produce a tenant whose JWTs route to a nonexistent
        // schema and whose allowlist entry never matches, so reject it rather than provision a broken
        // tenant. (Defaulting realm to schema when blank is the normal path.)
        if (!realm.equals(req.schema())) {
            throw new BusinessRuleException("REALM_SCHEMA_MISMATCH",
                    "realm must equal schema (realm-per-tenant routing invariant); leave realm blank to default it");
        }

        if (registry.exists(req.schema()) || registry.subdomainTaken(req.subdomain())) {
            throw new TenantAlreadyExistsException(req.schema(), req.subdomain());
        }

        // Generate server-side temp password — returned ONCE, never logged.
        String tempPassword = generateTempPassword();

        TenantSpec spec = new TenantSpec();
        spec.setSchema(req.schema());
        spec.setRealm(realm);
        spec.setDisplayName(req.displayName());
        spec.setSubdomain(req.subdomain());
        spec.setAdminUsername(req.adminUsername());
        spec.setAdminEmail(req.adminEmail());
        spec.setAdminTempPassword(tempPassword);

        // Schema + Flyway + seed + Keycloak realm/admin + registry.upsert (last).
        provisioning.provision(spec);

        audit.record("ONBOARD", req.schema(), actor, actorRealm,
                toJson(Map.of("subdomain", req.subdomain(), "realm", realm)), ip);

        TenantSummary summary = registry.find(req.schema()).orElseThrow(() ->
                new IllegalStateException("Registry row missing immediately after provision for schema: "
                        + req.schema()));

        return new OnboardTenantResponse(summary,
                new OnboardTenantResponse.FirstAdmin(req.adminUsername(), req.adminEmail(), tempPassword));
    }

    /** Returns all tenants (active and inactive) from the registry. */
    public List<TenantSummary> list() {
        return registry.findAll();
    }

    /** Returns a single tenant by schema name, or {@link Optional#empty()} if not found. */
    public Optional<TenantSummary> get(String schema) {
        return registry.find(schema);
    }

    /**
     * Suspends a tenant: sets {@code active = false} and evicts the activation cache so the
     * change takes effect immediately without waiting for the TTL.
     *
     * @throws TenantNotFoundException if no tenant with the given schema exists
     */
    public void suspend(String schema, String actor, String actorRealm, String ip) {
        setActive(schema, false, "SUSPEND", actor, actorRealm, ip);
    }

    /**
     * Activates a previously suspended tenant: sets {@code active = true} and evicts the
     * activation cache.
     *
     * @throws TenantNotFoundException if no tenant with the given schema exists
     */
    public void activate(String schema, String actor, String actorRealm, String ip) {
        setActive(schema, true, "ACTIVATE", actor, actorRealm, ip);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void setActive(String schema, boolean active, String action,
                           String actor, String actorRealm, String ip) {
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(actorRealm, "actorRealm must not be null");
        if (!registry.setActive(schema, active)) {
            throw new TenantNotFoundException(schema);
        }
        // Immediate effect: don't wait for the activation-lookup cache TTL to expire.
        activationLookup.evict(schema);
        audit.record(action, schema, actor, actorRealm, null, ip);
    }

    /**
     * Serialises a map to a JSON string safe for insertion as {@code JSONB}.
     * Returns {@code null} on serialisation failure — detail is non-essential;
     * we must never fail an onboard because of audit-detail serialisation.
     */
    private String toJson(Map<String, ?> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("Failed to serialise audit detail; proceeding without detail", e);
            return null;
        }
    }

    /**
     * Generates a cryptographically random temporary password that satisfies common
     * password policies: ≥24 chars, upper, lower, digit, special.
     * The prefix {@code "Aa1!"} ensures all four character classes are always present.
     */
    private static String generateTempPassword() {
        byte[] b = new byte[18];
        RNG.nextBytes(b);
        // Prefix guarantees uppercase + lowercase + digit + special for strict policy checkers.
        return "Aa1!" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
