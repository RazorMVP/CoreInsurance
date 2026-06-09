# Platform-Admin Auth + Tenant-Onboarding API (SP1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a cross-tenant platform-admin plane — a `platform` Keycloak realm + `SUPER_ADMIN` identity and a `/api/v1/platform/**` API to onboard and manage tenants at runtime by wrapping `TenantProvisioningService.provision()`.

**Architecture:** A dedicated `platform` Keycloak realm (bootstrapped by a gated runner) holds super-admins with a `SUPER_ADMIN` role. The resolver already trusts it; `TenantContextFilter` scopes platform requests to `public` (not a `"platform"` schema); `/api/v1/platform/**` is gated by `hasRole('SUPER_ADMIN')`. A cached `TenantActivationLookup` rejects suspended tenants on the auth path (platform realm exempt). Onboarding is synchronous; the first-admin temp password is server-generated and returned once. Every platform action is audited to logs + `public.platform_audit_log`.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Security resource server, Keycloak admin client, PostgreSQL + Flyway, Testcontainers (Postgres + Keycloak), JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-09-platform-admin-onboarding-api-design.md`

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `cia-setup/.../keycloak/KeycloakTenantProvisioner.java` | add `provisionPlatformRealm(realm, FirstAdminSpec)` + `ensurePlatformClient`; parameterize `ensureFirstAdminUser` with a role list | modify |
| `cia-setup/.../keycloak/PlatformRoles.java` | `SUPER_ADMIN` constant (the platform realm's role set) | create |
| `cia-api/.../platform/PlatformBootstrapProperties.java` | `@ConfigurationProperties("cia.platform")` (realm, bootstrap toggle, first-admin, client) | create |
| `cia-api/.../platform/PlatformBootstrapRunner.java` | gated `ApplicationRunner` provisioning the platform realm + first super-admin | create |
| `cia-auth/.../PlatformRealmProperties.java` | `cia.platform.realm` (+ `tenant-allowlist.enabled`) reachable in cia-auth | create |
| `cia-auth/.../TenantContextFilter.java` | platform realm → `TenantContext=public`; consult the allowlist gate | modify |
| `cia-auth/.../TenantActivationLookup.java` | SPI `boolean isActive(String realm)` + `void evict(String realm)` | create |
| `cia-api/.../platform/RegistryTenantActivationLookup.java` | JDBC impl over `public.tenants` + Caffeine/TTL cache | create |
| `cia-api/src/main/resources/db/migration/V67__platform_audit_log.sql` | `public.platform_audit_log` table | create |
| `cia-api/.../platform/PlatformAuditService.java` | schema-qualified JDBC writer + reader for `public.platform_audit_log` | create |
| `cia-api/.../platform/PlatformTenantService.java` | uniqueness, temp-pw gen, provision, suspend/activate, audit, cache eviction | create |
| `cia-api/.../platform/dto/*.java` | `OnboardTenantRequest`, `OnboardTenantResponse`, `TenantSummary`, `PlatformAuditEntry` | create |
| `cia-api/.../platform/PlatformTenantController.java` | `/api/v1/platform/tenants` + `/audit`, `hasRole('SUPER_ADMIN')` | create |
| `cia-auth/.../SecurityConfig.java` | (no matcher change needed — `.anyRequest().authenticated()` + method security covers it; confirm `@EnableMethodSecurity` present) | verify/modify |
| `CLAUDE.md`, `cia-log.md` | docs + backlog reconciliation | modify |

**Package:** new code in `com.nubeero.cia.api.platform` (cia-api), `com.nubeero.cia.auth` (cia-auth), `com.nubeero.cia.setup.keycloak` (cia-setup).

---

## Task 1: Keycloak platform-realm provisioning (cia-setup)

Add the ability to provision the `platform` realm with a `SUPER_ADMIN`-only role set (not the tenant `BootstrapRoles.ALL`), a `cia-platform` SPA client, and a first super-admin.

**Files:**
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/PlatformRoles.java`
- Modify: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/PlatformRealmProvisioningIT.java`

- [ ] **Step 1: Create `PlatformRoles`**

```java
package com.nubeero.cia.setup.keycloak;

import java.util.List;

/** The platform realm's realm-role set — the cross-tenant super-admin authority. Deliberately
 *  distinct from {@link BootstrapRoles} (tenant realms), so no tenant user can ever hold it. */
public final class PlatformRoles {
    private PlatformRoles() {}

    /** Realm role → ROLE_SUPER_ADMIN (JwtAuthConverter upper-cases + prefixes). */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    public static final List<String> ALL = List.of(SUPER_ADMIN);
}
```

- [ ] **Step 2: Parameterize `ensureFirstAdminUser` with a role list**

In `KeycloakTenantProvisioner.java`, `ensureFirstAdminUser` currently assigns `BootstrapRoles.ALL` (around line 347). Add a role-list parameter, keeping the existing public signature as a thin delegator (so the 1 existing tenant caller is unchanged). Replace the method header + the role-resolution line:

```java
    // Existing public signature — preserved for the tenant path; delegates with the tenant role set.
    public void ensureFirstAdminUser(Keycloak client, String realmName, FirstAdminSpec spec) {
        ensureFirstAdminUser(client, realmName, spec, BootstrapRoles.ALL);
    }

    /** Role-parameterized variant: assigns exactly {@code roleNames} to the first admin. */
    public void ensureFirstAdminUser(Keycloak client, String realmName, FirstAdminSpec spec,
                                     java.util.List<String> roleNames) {
        // ... existing body up to role resolution unchanged ...
        // Replace the line that did: BootstrapRoles.ALL.stream()...
        List<RoleRepresentation> realmRoles = roleNames.stream()
                .map(r -> client.realm(realmName).roles().get(r).toRepresentation())
                .toList();
        // ... existing role-mapping assignment unchanged ...
    }
```

(Keep the rest of `ensureFirstAdminUser` exactly as-is; only the role-list source changes.)

- [ ] **Step 3: Add `ensurePlatformClient` + `provisionPlatformRealm`**

Add to `KeycloakTenantProvisioner.java`. `ensurePlatformClient` mirrors `ensureBackOfficeClient` (`KeycloakTenantProvisioner.java:140`) — a public auth-code+PKCE(S256) SPA client — but with **no `tenant_id` mapper** (the platform realm is not a tenant) and the platform client id + redirect URIs. `provisionPlatformRealm` mirrors `provisionTenantAuth` (line 363) but seeds only `PlatformRoles` and assigns `PlatformRoles.ALL`:

```java
    /** Provisions the platform realm: realm + SUPER_ADMIN role + cia-platform SPA client +
     *  first super-admin (assigned SUPER_ADMIN). Idempotent. Requires the admin client. */
    public void provisionPlatformRealm(String realmName, String platformClientId,
                                       java.util.List<String> redirectUris, FirstAdminSpec spec) {
        Keycloak client = keycloak.getIfAvailable();
        if (client == null) {
            log.warn("Keycloak admin client unavailable — skipping platform realm provisioning for {}", realmName);
            return;
        }
        ensureRealm(client, realmName);
        ensureUnmanagedAttributePolicy(client, realmName);
        ensurePlatformClient(client, realmName, platformClientId, redirectUris);
        ensurePlatformRoles(client, realmName);
        ensureFirstAdminUser(client, realmName, spec, PlatformRoles.ALL);
        log.info("Platform realm '{}' provisioned (SUPER_ADMIN + {} client)", realmName, platformClientId);
    }

    /** Like ensureRealmRoles but for the platform role set. */
    public void ensurePlatformRoles(Keycloak client, String realmName) {
        RealmResource realm = client.realm(realmName);
        for (String role : PlatformRoles.ALL) {
            if (realm.roles().list().stream().noneMatch(r -> r.getName().equals(role))) {
                RoleRepresentation rep = new RoleRepresentation();
                rep.setName(role);
                realm.roles().create(rep);
            }
        }
    }

    private void ensurePlatformClient(Keycloak client, String realmName, String clientId,
                                      java.util.List<String> redirectUris) {
        // Mirror ensureBackOfficeClient (line 140): create-then-reconcile a public, standard-flow,
        // PKCE(S256) client with the given clientId + redirectUris/webOrigins. OMIT the tenant_id
        // protocol mapper (platform realm is not a tenant). Use redirectUris for both redirect URIs
        // and web origins (strip path for origins).
    }
```

(`ensurePlatformClient`'s body follows `ensureBackOfficeClient` line-for-line minus the `ensureTenantIdMapper` call; the implementer copies that method's structure with the platform client id/redirects.)

- [ ] **Step 4: Write the failing IT**

Create `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/PlatformRealmProvisioningIT.java` (extends the Keycloak IT support that starts a Keycloak Testcontainer + builds an admin client — mirror `KeycloakTenantProvisionerIT`):

```java
package com.nubeero.cia.api.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.keycloak.PlatformRoles;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformRealmProvisioningIT extends KeycloakItSupport {

    @Test
    void provisionsPlatformRealmWithSuperAdminOnly() {
        KeycloakTenantProvisioner provisioner = newProvisioner();   // wires admin client (see KeycloakItSupport)
        UUID adminGroup = UUID.randomUUID();

        provisioner.provisionPlatformRealm("platform", "cia-platform",
            List.of("http://localhost:5175/*"),
            new FirstAdminSpec("superadmin", "super@cia.local", "Super", "Admin", "Temp-Pass-123!", adminGroup));

        var realm = adminClient().realm("platform");
        assertThat(realm.roles().list()).extracting("name").contains(PlatformRoles.SUPER_ADMIN);
        assertThat(realm.roles().list()).extracting("name").doesNotContain("policy_view", "PLATFORM_ADMIN");
        var users = realm.users().search("superadmin");
        assertThat(users).hasSize(1);
        assertThat(realm.clients().findByClientId("cia-platform")).hasSize(1);
    }
}
```

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=PlatformRealmProvisioningIT -DfailIfNoTests=false`
Expected: FAIL — `provisionPlatformRealm` doesn't exist yet (compile error), then after Steps 1–3, PASS.

NOTE: confirm `KeycloakItSupport` exposes a way to build a `KeycloakTenantProvisioner` + an `adminClient()` (the existing `KeycloakTenantProvisionerIT` does this). If the helper names differ, adapt the test to the existing harness — do not invent a new harness.

- [ ] **Step 5: Run + commit**

Run the IT (Step 4 command) → PASS.
```bash
git add cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/PlatformRoles.java \
        cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/PlatformRealmProvisioningIT.java
git commit -m "feat(platform): Keycloak platform-realm provisioning (SUPER_ADMIN + cia-platform client)"
```

---

## Task 2: Platform bootstrap runner (cia-api)

A gated `ApplicationRunner` that provisions the platform realm + first super-admin on boot — mirroring Slice A's `TenantBootstrapRunner`.

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformBootstrapProperties.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformBootstrapRunner.java`
- Modify: `cia-backend/cia-api/src/main/resources/application.yml` (add the `cia.platform` block, bootstrap off)
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformBootstrapRunnerIT.java`

- [ ] **Step 1: Create `PlatformBootstrapProperties`**

```java
package com.nubeero.cia.api.platform;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter @Setter
@ConfigurationProperties("cia.platform")
public class PlatformBootstrapProperties {
    /** The platform realm name. Also read in cia-auth (PlatformRealmProperties) — keep in sync. */
    private String realm = "platform";
    private String clientId = "cia-platform";
    private List<String> redirectUris = List.of("http://localhost:5175/*");

    private final Bootstrap bootstrap = new Bootstrap();

    @Getter @Setter
    public static class Bootstrap {
        private boolean enabled = false;            // OFF by default — dev + IT unaffected
        private String adminUsername = "superadmin";
        private String adminEmail = "superadmin@cia.local";
        @ToString.Exclude
        private String adminTempPassword;           // secret — env CIA_PLATFORM_BOOTSTRAP_ADMIN_TEMP_PASSWORD
    }
}
```

- [ ] **Step 2: Create `PlatformBootstrapRunner`**

```java
package com.nubeero.cia.api.platform;

import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Gated platform-plane bootstrap. Mirrors TenantBootstrapRunner: off by default; requires the
 *  Keycloak admin client; fail-fast on error so a misconfigured platform plane aborts startup. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cia.platform.bootstrap.enabled", havingValue = "true")
public class PlatformBootstrapRunner implements ApplicationRunner {

    private final PlatformBootstrapProperties props;
    private final KeycloakTenantProvisioner provisioner;

    @Override
    public void run(ApplicationArguments args) {
        var b = props.getBootstrap();
        log.info("Bootstrapping platform realm '{}'", props.getRealm());
        UUID adminGroup = UUID.nameUUIDFromBytes(("platform-admin::" + props.getRealm()).getBytes(StandardCharsets.UTF_8));
        provisioner.provisionPlatformRealm(props.getRealm(), props.getClientId(), props.getRedirectUris(),
            new FirstAdminSpec(b.getAdminUsername(), b.getAdminEmail(), "Platform", "Administrator",
                b.getAdminTempPassword(), adminGroup));
        log.info("Platform realm '{}' bootstrap complete", props.getRealm());
    }
}
```

(Ensure `PlatformBootstrapProperties` is registered — add `PlatformBootstrapProperties.class` to the existing `@ConfigurationPropertiesScan`/`@EnableConfigurationProperties` in the cia-api app config, matching how `TenantBootstrapProperties` is registered.)

- [ ] **Step 3: Add the `cia.platform` block to `application.yml`**

Under the existing `cia:` tree:
```yaml
  platform:
    realm: ${CIA_PLATFORM_REALM:platform}
    client-id: ${CIA_PLATFORM_CLIENT_ID:cia-platform}
    redirect-uris: ${CIA_PLATFORM_REDIRECT_URIS:http://localhost:5175/*}
    bootstrap:
      enabled: ${CIA_PLATFORM_BOOTSTRAP_ENABLED:false}
      admin-username: ${CIA_PLATFORM_BOOTSTRAP_ADMIN_USERNAME:superadmin}
      admin-email: ${CIA_PLATFORM_BOOTSTRAP_ADMIN_EMAIL:superadmin@cia.local}
      admin-temp-password: ${CIA_PLATFORM_BOOTSTRAP_ADMIN_TEMP_PASSWORD:}
    tenant-allowlist:
      enabled: ${CIA_PLATFORM_TENANT_ALLOWLIST_ENABLED:false}
```

- [ ] **Step 4: Write the IT**

Create `PlatformBootstrapRunnerIT.java` — a Keycloak-Testcontainer IT with `cia.platform.bootstrap.enabled=true` (+ admin client enabled) asserting the platform realm + SUPER_ADMIN + superadmin user exist after the runner runs. Mirror the Slice A `TenantBootstrapRunner` IT shape (`@SpringBootTest` with the bootstrap props set, Keycloak container wired). Key assertion: `adminClient().realm("platform").roles().list()` contains `SUPER_ADMIN`; user `superadmin` exists.

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=PlatformBootstrapRunnerIT -DfailIfNoTests=false`
Expected: FAIL before Steps 1–3, PASS after.

- [ ] **Step 5: Commit**
```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformBootstrapProperties.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformBootstrapRunner.java \
        cia-backend/cia-api/src/main/resources/application.yml \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformBootstrapRunnerIT.java
git commit -m "feat(platform): gated PlatformBootstrapRunner (off by default)"
```

---

## Task 3: TenantContextFilter platform-realm awareness (cia-auth)

Scope platform-realm requests to `public` so they never route to a nonexistent `"platform"` schema.

**Files:**
- Create: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/PlatformRealmProperties.java`
- Modify: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java`
- Test: `cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterTest.java`

- [ ] **Step 1: Create `PlatformRealmProperties` (cia-auth)**

```java
package com.nubeero.cia.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Platform-realm awareness for the auth layer. Mirrors cia.platform.realm (keep in sync with
 *  the cia-api PlatformBootstrapProperties — same property key). */
@Getter @Setter
@ConfigurationProperties("cia.platform")
public class PlatformRealmProperties {
    private String realm = "platform";
    private final TenantAllowlist tenantAllowlist = new TenantAllowlist();

    @Getter @Setter
    public static class TenantAllowlist {
        private boolean enabled = false;
    }
}
```

(Register via the cia-auth auto-config / `@EnableConfigurationProperties`. If cia-auth has no config-props registration yet, add `@EnableConfigurationProperties(PlatformRealmProperties.class)` to its security config class.)

- [ ] **Step 2: Add the failing test**

In `TenantContextFilterTest.java`, the filter is currently `new TenantContextFilter()`. It will gain constructor deps (props + lookup). Add an import `import org.slf4j.MDC;` is already there. Add a test that a platform-realm token sets `TenantContext == "public"`:

```java
    @Test
    @DisplayName("platform realm scopes the request to public, not a 'platform' schema")
    void platformRealmScopesToPublic() throws Exception {
        // filter built with platform realm = "platform" + a lookup that allows everything (see Step 3 ctor)
        String t = capturedTenantDuringChain(jwtWith("http://localhost:8280/realms/platform", null));
        assertThat(t).isEqualTo("public");
    }
```

(Update the test's `filter` construction to the new constructor — see Step 3. The existing 6 tests must still pass; for them, pass a `PlatformRealmProperties` with realm `"platform"` and an allow-all `TenantActivationLookup`.)

- [ ] **Step 3: Modify `TenantContextFilter`**

Give it the platform-realm props + the lookup (the lookup wiring lands in Task 4; for this task add the props + the platform→public branch, and a no-op/allow-all lookup default so the constructor compiles). Final shape of the resolution block:

```java
    private final PlatformRealmProperties platformProps;
    private final TenantActivationLookup activationLookup;   // added in Task 4

    public TenantContextFilter(PlatformRealmProperties platformProps, TenantActivationLookup activationLookup) {
        this.platformProps = platformProps;
        this.activationLookup = activationLookup;
    }

    // inside doFilterInternal, where tenantId is resolved from the iss realm:
    String realm = KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
    String tenantId = realm;
    if (tenantId == null || tenantId.isBlank()) {
        tenantId = jwt.getClaimAsString("tenant_id");
    }
    if (realm != null && realm.equals(platformProps.getRealm())) {
        tenantId = "public";                                 // platform plane operates on the registry schema
    }
    if (tenantId != null && !tenantId.isBlank()) {
        TenantContext.setTenantId(tenantId);
        MDC.put(MDC_TENANT_KEY, tenantId);
    }
```

(The allowlist-gate rejection is added in Task 4. Here we only add the platform→public scoping + the constructor.)

- [ ] **Step 4: Run the test**
Run: `cd cia-backend && mvn -q -pl cia-auth test -Dtest=TenantContextFilterTest`
Expected: 7 tests PASS (6 existing + the new platform one).

- [ ] **Step 5: Commit**
```bash
git add cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/PlatformRealmProperties.java \
        cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java \
        cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterTest.java
git commit -m "feat(platform): TenantContextFilter scopes platform realm to public schema"
```

---

## Task 4: TenantActivationLookup SPI + allowlist gate (cia-auth)

The auth-path gate that rejects suspended/unknown tenants. SPI + filter enforcement; the JDBC impl is Task 5.

**Files:**
- Create: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantActivationLookup.java`
- Modify: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java`
- Test: `cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterGateTest.java`

- [ ] **Step 1: Create the SPI**

```java
package com.nubeero.cia.auth;

/** Looks up whether a tenant realm is active in the registry. Impl (RegistryTenantActivationLookup,
 *  cia-api) reads public.tenants and caches; the platform realm is never passed here (exempt). */
public interface TenantActivationLookup {
    boolean isActive(String realm);
    void evict(String realm);
}
```

- [ ] **Step 2: Write the failing gate test**

```java
package com.nubeero.cia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nubeero.cia.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantContextFilterGateTest {

    @AfterEach void clear() { SecurityContextHolder.clearContext(); TenantContext.clear(); }

    private Jwt jwt(String iss) {
        return Jwt.withTokenValue("t").header("alg","RS256").subject("u").claim("iss", iss).build();
    }
    private PlatformRealmProperties props(boolean gateOn) {
        PlatformRealmProperties p = new PlatformRealmProperties();
        p.setRealm("platform");
        p.getTenantAllowlist().setEnabled(gateOn);
        return p;
    }

    @Test
    void suspendedTenantRealmIsRejected401WhenGateOn() throws Exception {
        TenantActivationLookup lookup = mock(TenantActivationLookup.class);
        when(lookup.isActive("acme")).thenReturn(false);
        var filter = new TenantContextFilter(props(true), lookup);
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt("http://localhost:8280/realms/acme")));
        var res = new MockHttpServletResponse();
        boolean[] chainRan = {false};
        FilterChain chain = (rq, rs) -> chainRan[0] = true;
        filter.doFilter(mock(HttpServletRequest.class), res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chainRan[0]).isFalse();
    }

    @Test
    void platformRealmExemptFromGate() throws Exception {
        TenantActivationLookup lookup = mock(TenantActivationLookup.class);  // never consulted
        var filter = new TenantContextFilter(props(true), lookup);
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt("http://localhost:8280/realms/platform")));
        var res = new MockHttpServletResponse();
        boolean[] chainRan = {false};
        filter.doFilter(mock(HttpServletRequest.class), res, (rq, rs) -> chainRan[0] = true);
        assertThat(chainRan[0]).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void gateOffPassesThrough() throws Exception {
        TenantActivationLookup lookup = mock(TenantActivationLookup.class);
        var filter = new TenantContextFilter(props(false), lookup);
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt("http://localhost:8280/realms/acme")));
        var res = new MockHttpServletResponse();
        boolean[] chainRan = {false};
        filter.doFilter(mock(HttpServletRequest.class), res, (rq, rs) -> chainRan[0] = true);
        assertThat(chainRan[0]).isTrue();
    }
}
```

- [ ] **Step 3: Add the gate to `TenantContextFilter`**

After computing `realm`/`tenantId`, before `filterChain.doFilter`, insert the gate. The platform realm is exempt; the gate runs only when enabled and only for tenant realms:

```java
            if (realm != null && !realm.equals(platformProps.getRealm())
                    && platformProps.getTenantAllowlist().isEnabled()
                    && !activationLookup.isActive(realm)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"errors\":[{\"code\":\"TENANT_INACTIVE\"," +
                        "\"message\":\"Tenant is suspended or unknown\"}]}");
                response.setContentType("application/json");
                return;   // short-circuit — finally still clears TenantContext + MDC
            }
            filterChain.doFilter(request, response);
```

(Keep this inside the existing `try { ... } finally { TenantContext.clear(); MDC.remove(...); }` so the short-circuit still cleans up.)

- [ ] **Step 4: Run both filter tests**
Run: `cd cia-backend && mvn -q -pl cia-auth test -Dtest=TenantContextFilterTest,TenantContextFilterGateTest`
Expected: all PASS.

- [ ] **Step 5: Commit**
```bash
git add cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantActivationLookup.java \
        cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java \
        cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterGateTest.java
git commit -m "feat(platform): tenant-activation allowlist gate (401 on suspended; platform exempt)"
```

---

## Task 5: RegistryTenantActivationLookup — JDBC impl + cache (cia-api)

The `public.tenants`-backed impl with caching + eviction.

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/RegistryTenantActivationLookup.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/RegistryTenantActivationLookupIT.java`

- [ ] **Step 1: Write the failing IT**

Reuse the Slice A shared-container harness (`TenantProvisioningItSupport` exposes `dataSource()`). Seed `public.tenants` rows directly, then assert lookup + eviction:

```java
package com.nubeero.cia.api.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.nubeero.cia.api.tenant.TenantProvisioningItSupport;

class RegistryTenantActivationLookupIT extends TenantProvisioningItSupport {

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS public.tenants (id uuid primary key default gen_random_uuid(),"
                + " schema_name varchar(63) unique not null, name varchar(255), subdomain varchar(63) unique,"
                + " active boolean not null default true, created_at timestamptz default now())");
            st.execute("DELETE FROM public.tenants");
            st.execute("INSERT INTO public.tenants(schema_name,name,subdomain,active) VALUES"
                + " ('tenant_live','Live','live',true),('tenant_susp','Susp','susp',false)");
        }
    }

    @Test
    void readsActiveFlagAndEvicts() {
        var lookup = new RegistryTenantActivationLookup(new JdbcTemplate(dataSource()), 60);
        assertThat(lookup.isActive("tenant_live")).isTrue();
        assertThat(lookup.isActive("tenant_susp")).isFalse();
        assertThat(lookup.isActive("tenant_missing")).isFalse();   // unknown → inactive

        // flip + evict → fresh read
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE public.tenants SET active=false WHERE schema_name='tenant_live'");
        } catch (Exception e) { throw new RuntimeException(e); }
        assertThat(lookup.isActive("tenant_live")).isTrue();       // still cached
        lookup.evict("tenant_live");
        assertThat(lookup.isActive("tenant_live")).isFalse();      // re-read after eviction
    }
}
```

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=RegistryTenantActivationLookupIT -DfailIfNoTests=false`
Expected: FAIL (class missing) → PASS after Step 2.

- [ ] **Step 2: Implement**

```java
package com.nubeero.cia.api.platform;

import com.nubeero.cia.auth.TenantActivationLookup;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** public.tenants-backed activation lookup with a short-TTL cache + explicit eviction
 *  (PlatformTenantService evicts on suspend/activate so the change is immediate). Schema-qualified
 *  read so it's independent of TenantContext. */
@Component
public class RegistryTenantActivationLookup implements TenantActivationLookup {

    private record Entry(boolean active, Instant at) {}
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final Duration ttl;

    public RegistryTenantActivationLookup(JdbcTemplate jdbc,
            @Value("${cia.platform.tenant-allowlist.cache-ttl-seconds:60}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    @Override
    public boolean isActive(String realm) {
        Entry e = cache.get(realm);
        if (e != null && Duration.between(e.at(), Instant.now()).compareTo(ttl) < 0) {
            return e.active();
        }
        Boolean active = jdbc.query(
            "SELECT active FROM public.tenants WHERE schema_name = ?",
            rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, realm);
        boolean result = Boolean.TRUE.equals(active);
        cache.put(realm, new Entry(result, Instant.now()));
        return result;
    }

    @Override
    public void evict(String realm) { cache.remove(realm); }
}
```

- [ ] **Step 3: Run + commit**
Run the IT → PASS.
```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/RegistryTenantActivationLookup.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/RegistryTenantActivationLookupIT.java
git commit -m "feat(platform): registry-backed TenantActivationLookup (cached + evictable)"
```

---

## Task 6: platform_audit_log migration + PlatformAuditService (cia-api)

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V67__platform_audit_log.sql`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformAuditService.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformAuditServiceIT.java`

- [ ] **Step 1: Create the migration**

`V67__platform_audit_log.sql`:
```sql
CREATE TABLE IF NOT EXISTS platform_audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action          VARCHAR(32)  NOT NULL,           -- ONBOARD | SUSPEND | ACTIVATE
    target_schema   VARCHAR(63)  NOT NULL,
    actor_username  VARCHAR(255) NOT NULL,
    actor_realm     VARCHAR(63)  NOT NULL,
    detail          JSONB,
    source_ip       VARCHAR(64),
    at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_platform_audit_at ON platform_audit_log (at DESC);
```

(Standard migration: lands in `public` via the main flyway and in tenant schemas via the per-schema migrator — consistent with every existing table. We write only to `public.platform_audit_log`.)

- [ ] **Step 2: Write the failing IT**

```java
package com.nubeero.cia.api.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.nubeero.cia.api.tenant.TenantProvisioningItSupport;

class PlatformAuditServiceIT extends TenantProvisioningItSupport {

    @Test
    void writesAndReadsAuditRows() {
        var jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.platform_audit_log (id uuid primary key default gen_random_uuid(),"
            + " action varchar(32), target_schema varchar(63), actor_username varchar(255), actor_realm varchar(63),"
            + " detail jsonb, source_ip varchar(64), at timestamptz default now())");
        jdbc.update("DELETE FROM public.platform_audit_log");
        var svc = new PlatformAuditService(jdbc);
        svc.record("ONBOARD", "tenant_acme", "superadmin", "platform", "{\"subdomain\":\"acme\"}", "10.0.0.1");
        var rows = svc.recent(10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).action()).isEqualTo("ONBOARD");
        assertThat(rows.get(0).targetSchema()).isEqualTo("tenant_acme");
    }
}
```

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=PlatformAuditServiceIT -DfailIfNoTests=false` → FAIL then PASS.

- [ ] **Step 3: Implement `PlatformAuditService` + `PlatformAuditEntry`**

```java
package com.nubeero.cia.api.platform;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PlatformAuditService {

    public record PlatformAuditEntry(UUID id, String action, String targetSchema,
                                     String actorUsername, String actorRealm, String detail,
                                     String sourceIp, Instant at) {}

    private final JdbcTemplate jdbc;
    public PlatformAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Dual audit: structured log + schema-qualified insert into public.platform_audit_log. */
    public void record(String action, String targetSchema, String actor, String actorRealm,
                       String detailJson, String sourceIp) {
        log.info("platform-audit action={} target={} actor={} realm={} ip={}",
                action, targetSchema, actor, actorRealm, sourceIp);
        jdbc.update("INSERT INTO public.platform_audit_log"
            + " (action,target_schema,actor_username,actor_realm,detail,source_ip)"
            + " VALUES (?,?,?,?,?::jsonb,?)",
            action, targetSchema, actor, actorRealm, detailJson, sourceIp);
    }

    public List<PlatformAuditEntry> recent(int limit) {
        return jdbc.query("SELECT id,action,target_schema,actor_username,actor_realm,detail,source_ip,at"
            + " FROM public.platform_audit_log ORDER BY at DESC LIMIT ?",
            (rs, i) -> new PlatformAuditEntry(rs.getObject("id", UUID.class), rs.getString("action"),
                rs.getString("target_schema"), rs.getString("actor_username"), rs.getString("actor_realm"),
                rs.getString("detail"), rs.getString("source_ip"), rs.getTimestamp("at").toInstant()),
            limit);
    }
}
```

- [ ] **Step 4: Run + commit**
```bash
git add cia-backend/cia-api/src/main/resources/db/migration/V67__platform_audit_log.sql \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformAuditService.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformAuditServiceIT.java
git commit -m "feat(platform): V67 platform_audit_log + PlatformAuditService (dual audit)"
```

---

## Task 7: PlatformTenantService + DTOs (cia-api)

The orchestration layer: uniqueness, temp-password gen, provision, suspend/activate (registry + cache evict), audit.

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/{OnboardTenantRequest,OnboardTenantResponse,TenantSummary}.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantService.java`
- Modify: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantRegistry.java` (add `findAll()`, `find(schema)`, `setActive(schema, bool)` if not present)
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformTenantServiceIT.java`

- [ ] **Step 1: DTOs**

```java
// OnboardTenantRequest.java
package com.nubeero.cia.api.platform.dto;
import jakarta.validation.constraints.*;
public record OnboardTenantRequest(
    @NotBlank @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}") String schema,
    String realm,                          // defaults to schema when null/blank
    @NotBlank String displayName,
    @NotBlank @Pattern(regexp = "[a-z0-9-]{1,63}") String subdomain,
    @NotBlank String adminUsername,
    @NotBlank @Email String adminEmail) {}
```
```java
// TenantSummary.java
package com.nubeero.cia.api.platform.dto;
import java.time.Instant;
public record TenantSummary(String schema, String displayName, String subdomain,
                            boolean active, Instant createdAt) {}
```
```java
// OnboardTenantResponse.java
package com.nubeero.cia.api.platform.dto;
public record OnboardTenantResponse(TenantSummary tenant, FirstAdmin firstAdmin) {
    public record FirstAdmin(String username, String email, String temporaryPassword) {}
}
```

- [ ] **Step 2: Ensure `TenantRegistry` read/lifecycle methods exist**

`TenantRegistry` already has `upsert` + `findActiveSchemas`. Add (schema-qualified JDBC against `public.tenants`):
```java
    public java.util.List<com.nubeero.cia.api.platform.dto.TenantSummary> findAll() { /* SELECT * ORDER BY created_at */ }
    public java.util.Optional<com.nubeero.cia.api.platform.dto.TenantSummary> find(String schema) { /* WHERE schema_name=? */ }
    public boolean setActive(String schema, boolean active) { /* UPDATE ... WHERE schema_name=?; return rows>0 */ }
    public boolean exists(String schema) { /* SELECT 1 WHERE schema_name=? */ }
    public boolean subdomainTaken(String subdomain) { /* SELECT 1 WHERE subdomain=? */ }
```
(Implement with the existing `JdbcTemplate`/`NamedParameterJdbcTemplate` the registry already uses; mirror its existing query style.)

- [ ] **Step 3: Write the failing service IT**

`PlatformTenantServiceIT` extends a Keycloak+Postgres IT support (needs both — onboarding provisions a realm + schema). Assert: onboard creates schema + registry row + returns a non-blank temp password; duplicate schema throws a 409-mapped exception; suspend sets active=false + evicts; activate sets true. (Mirror the existing provisioning ITs that wire `TenantProvisioningService` + Keycloak.) Key assertions:
```java
    var resp = service.onboard(new OnboardTenantRequest("tenant_acme", null, "Acme", "acme", "admin", "a@acme.test"),
                               "superadmin", "platform", "10.0.0.1");
    assertThat(resp.firstAdmin().temporaryPassword()).isNotBlank();
    assertThat(registry.exists("tenant_acme")).isTrue();
    assertThatThrownBy(() -> service.onboard(/* same schema */ ...)).isInstanceOf(TenantAlreadyExistsException.class);
    service.suspend("tenant_acme", actor...);   assertThat(registry.find("tenant_acme").get().active()).isFalse();
```

- [ ] **Step 4: Implement `PlatformTenantService`**

```java
package com.nubeero.cia.api.platform;

import com.nubeero.cia.api.platform.dto.*;
import com.nubeero.cia.api.tenant.TenantBootstrapProperties.TenantSpec;
import com.nubeero.cia.api.tenant.TenantProvisioningService;
import com.nubeero.cia.api.tenant.TenantRegistry;
import com.nubeero.cia.auth.TenantActivationLookup;
import com.nubeero.cia.common.tenant.TenantSchemas;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformTenantService {

    private final TenantProvisioningService provisioning;
    private final TenantRegistry registry;
    private final TenantActivationLookup activationLookup;
    private final PlatformAuditService audit;
    private static final SecureRandom RNG = new SecureRandom();

    public OnboardTenantResponse onboard(OnboardTenantRequest req, String actor, String actorRealm, String ip) {
        TenantSchemas.validate(req.schema());
        String realm = (req.realm() == null || req.realm().isBlank()) ? req.schema() : req.realm();
        if (registry.exists(req.schema()) || registry.subdomainTaken(req.subdomain())) {
            throw new TenantAlreadyExistsException(req.schema(), req.subdomain());
        }
        String tempPassword = generateTempPassword();
        TenantSpec spec = new TenantSpec();
        spec.setSchema(req.schema()); spec.setRealm(realm); spec.setDisplayName(req.displayName());
        spec.setSubdomain(req.subdomain()); spec.setAdminUsername(req.adminUsername());
        spec.setAdminEmail(req.adminEmail()); spec.setAdminTempPassword(tempPassword);

        provisioning.provision(spec);   // schema + Flyway + seed + Keycloak realm/admin + registry.upsert (last)

        audit.record("ONBOARD", req.schema(), actor, actorRealm,
            "{\"subdomain\":\"" + req.subdomain() + "\",\"realm\":\"" + realm + "\"}", ip);
        var summary = registry.find(req.schema()).orElseThrow();
        return new OnboardTenantResponse(summary,
            new OnboardTenantResponse.FirstAdmin(req.adminUsername(), req.adminEmail(), tempPassword));
    }

    public java.util.List<TenantSummary> list() { return registry.findAll(); }
    public java.util.Optional<TenantSummary> get(String schema) { return registry.find(schema); }

    public void suspend(String schema, String actor, String actorRealm, String ip) {
        setActive(schema, false, "SUSPEND", actor, actorRealm, ip);
    }
    public void activate(String schema, String actor, String actorRealm, String ip) {
        setActive(schema, true, "ACTIVATE", actor, actorRealm, ip);
    }
    private void setActive(String schema, boolean active, String action, String actor, String actorRealm, String ip) {
        if (!registry.setActive(schema, active)) throw new TenantNotFoundException(schema);
        activationLookup.evict(schema);   // immediate effect, not after TTL
        audit.record(action, schema, actor, actorRealm, null, ip);
    }

    private static String generateTempPassword() {
        byte[] b = new byte[18]; RNG.nextBytes(b);
        return "Aa1!" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);  // ≥24 chars, mixed classes
    }
}
```

Add small exceptions `TenantAlreadyExistsException` (→409) and `TenantNotFoundException` (→404) in the same package, each extending the project's `CiaException` (carry an `errorCode` so `GlobalExceptionHandler` maps them — mirror an existing `CiaException` subclass).

- [ ] **Step 5: Run + commit**
```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/ \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantRegistry.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformTenantServiceIT.java
git commit -m "feat(platform): PlatformTenantService (onboard/list/suspend/activate + audit + evict)"
```

---

## Task 8: PlatformTenantController + security (cia-api)

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantController.java`
- Verify: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/SecurityConfig.java` has `@EnableMethodSecurity` (so `@PreAuthorize` works); if absent, add it.
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformTenantControllerIT.java`

- [ ] **Step 1: Controller**

```java
package com.nubeero.cia.api.platform;

import com.nubeero.cia.api.platform.dto.*;
import com.nubeero.cia.common.api.ApiResponse;          // the project envelope
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformTenantController {

    private final PlatformTenantService service;
    private final PlatformAuditService audit;
    private final com.nubeero.cia.auth.PlatformRealmProperties platformProps;

    @PostMapping("/tenants")
    public ResponseEntity<ApiResponse<OnboardTenantResponse>> onboard(
            @Valid @RequestBody OnboardTenantRequest req,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt);
        var resp = service.onboard(req, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(resp));
    }

    @GetMapping("/tenants")
    public ApiResponse<java.util.List<TenantSummary>> list(@AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt); return ApiResponse.success(service.list());
    }

    @GetMapping("/tenants/{schema}")
    public ApiResponse<TenantSummary> get(@PathVariable String schema, @AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        return ApiResponse.success(service.get(schema).orElseThrow(() -> new TenantNotFoundException(schema)));
    }

    @PostMapping("/tenants/{schema}/suspend")
    public ApiResponse<Void> suspend(@PathVariable String schema, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt); service.suspend(schema, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ApiResponse.success(null);
    }

    @PostMapping("/tenants/{schema}/activate")
    public ApiResponse<Void> activate(@PathVariable String schema, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt); service.activate(schema, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ApiResponse.success(null);
    }

    @GetMapping("/audit")
    public ApiResponse<java.util.List<PlatformAuditService.PlatformAuditEntry>> auditTrail(
            @RequestParam(defaultValue = "100") int limit, @AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt); return ApiResponse.success(audit.recent(Math.min(limit, 500)));
    }

    /** Defense-in-depth: SUPER_ADMIN should only ever be minted by the platform realm, but verify
     *  the validated iss realm anyway, so a stray same-named role elsewhere can't reach these. */
    private void assertPlatformRealm(Jwt jwt) {
        String realm = com.nubeero.cia.auth.KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
        if (realm == null || !realm.equals(platformProps.getRealm())) {
            throw new org.springframework.security.access.AccessDeniedException("Not a platform-realm token");
        }
    }
    private static String actor(Jwt jwt) { return jwt.getClaimAsString("preferred_username"); }
    private static String realm(Jwt jwt) { return com.nubeero.cia.auth.KeycloakRealms.realmOf(jwt.getClaimAsString("iss")); }
}
```

(Use the project's actual `ApiResponse` factory + `CiaException`/`GlobalExceptionHandler` conventions — match an existing controller. `KeycloakRealms` is the existing helper used by `TenantContextFilter`; if it's package-private, expose a static accessor or read the realm via the same util.)

- [ ] **Step 2: Verify method security is enabled**

Check `SecurityConfig` (cia-auth) for `@EnableMethodSecurity`. If absent, add it to the config class so class-level `@PreAuthorize("hasRole('SUPER_ADMIN')")` is enforced.

Run: `cd cia-backend && grep -rn "EnableMethodSecurity" cia-backend/cia-auth/src/main` — if no hit, add the annotation.

- [ ] **Step 3: Write the web IT**

`PlatformTenantControllerIT` — full `@SpringBootTest` web IT (mirror `FinanceWebItSupport`-style, with `@MockBean JwtDecoder` + `@WithMockUser`/jwt postprocessor). Assert: `POST /api/v1/platform/tenants` with a `SUPER_ADMIN` jwt (iss=platform realm) returns 201 + temp password; missing `SUPER_ADMIN` → 403; duplicate → 409. (Use the existing MockMvc + jwt() request-postprocessor pattern; mock `PlatformTenantService` for the controller-layer test so it doesn't need a real Keycloak — the full real-stack path is Task 9.)

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=PlatformTenantControllerIT -DfailIfNoTests=false` → PASS.

- [ ] **Step 4: Commit**
```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantController.java \
        cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/SecurityConfig.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformTenantControllerIT.java
git commit -m "feat(platform): PlatformTenantController /api/v1/platform/** (SUPER_ADMIN + realm assert)"
```

---

## Task 9: End-to-end ITs (cia-api)

Real Keycloak + Postgres, exercising the whole path including the gate + escalation guards.

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformOnboardingE2EIT.java`

- [ ] **Step 1: Write the E2E IT**

Using the Keycloak+Postgres harness, with the platform realm + a SUPER_ADMIN user provisioned (via Task 1's `provisionPlatformRealm`), obtain a real `SUPER_ADMIN` token and exercise the API. Cover:
1. Onboard via API → tenant schema created + migrated, tenant realm + first-admin provisioned, `public.tenants` row present, response carries the one-time temp password.
2. Onboard duplicate schema/subdomain → 409.
3. Suspend → a token minted in that tenant's realm is rejected 401 by the gate (with `cia.platform.tenant-allowlist.enabled=true`); activate → that token works again.
4. Platform realm exempt from the gate (SUPER_ADMIN keeps working while a tenant is suspended).
5. A non-SUPER_ADMIN token → 403; a token carrying a tenant-realm `PLATFORM_ADMIN` role → 403 on `/api/v1/platform/**` (no cross-tenant escalation).
6. `GET /api/v1/platform/audit` returns the onboard/suspend/activate rows.

(This is the load-bearing security IT. If minting a real second-realm token in the harness is heavy, assert the gate + escalation at the filter/authz layer with crafted JWTs (as in Tasks 4/8) and assert the onboarding/registry/audit effects against the real provisioning — splitting (1)(2)(6) [real provisioning] from (3)(4)(5) [auth-layer crafted-JWT], rather than minting live tokens for every case.)

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=PlatformOnboardingE2EIT -DfailIfNoTests=false` → PASS.

- [ ] **Step 2: Commit**
```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformOnboardingE2EIT.java
git commit -m "test(platform): end-to-end onboarding + gate + escalation ITs"
```

---

## Task 10: Docs + backlog reconciliation + full verify

**Files:**
- Modify: `CLAUDE.md`, `cia-log.md`

- [ ] **Step 1: CLAUDE.md**
- §6 (Multi-Tenancy): note the `platform` realm + `SUPER_ADMIN` (cross-tenant, above tenants) + the allowlist gate (`public.tenants.active`, platform-realm-exempt).
- §8 (Security): the platform-admin plane + `SUPER_ADMIN` authority + `/api/v1/platform/**`.
- Env-var table: `CIA_PLATFORM_REALM`, `CIA_PLATFORM_CLIENT_ID`, `CIA_PLATFORM_REDIRECT_URIS`, `CIA_PLATFORM_BOOTSTRAP_ENABLED`, `CIA_PLATFORM_BOOTSTRAP_ADMIN_{USERNAME,EMAIL,TEMP_PASSWORD}`, `CIA_PLATFORM_TENANT_ALLOWLIST_ENABLED`.
- Module 1/12: note the runtime tenant-onboarding surface (SP1) + that the SP2 platform-admin UI is the next slice.

- [ ] **Step 2: cia-log.md backlog reconciliation**
- **Drain** `jwt-resolver-registry-allowlist` (P2) — implemented as the `TenantActivationLookup` gate.
- **Add**: `platform-hard-delete-tenant` (P3 — NDPR/retention workflow), `platform-invite-super-admin` (P3 — runtime super-admin invite vs config+restart), `platform-admin-ui-sp2` (P2 — the SP2 `apps/platform` UI, the next sub-project).
- Add the session entry.

- [ ] **Step 3: Commit**
```bash
git add CLAUDE.md cia-log.md
git commit -m "docs(platform): CLAUDE.md platform plane + env vars; backlog reconciliation"
```

- [ ] **Step 4: Full-reactor verify**
Run: `cd cia-backend && mvn -q -DskipTests install -pl cia-auth,cia-setup,cia-api -am && mvn verify`
Expected: BUILD SUCCESS, full failsafe IT suite green (prior count + the new platform ITs), 0 failures/0 errors. (Stale-m2 `NoSuchMethodError` → the `install … -am` prefix fixes it.)

---

## Self-Review Notes (plan author)

- **Spec coverage:** §1 auth foundation → Tasks 3,4,5 (+ realm scoping, gate, SPI/impl) + Task 8 (authz + realm assert); §2 bootstrap → Tasks 1,2; §3 API → Tasks 7,8; §4 audit → Task 6; data-flow → exercised by Task 9; testing posture → Tasks 1–9 ITs; out-of-scope (hard delete, invite, SP2 UI) → backlog Task 10. All mapped.
- **Cross-module dependency order:** `TenantActivationLookup` SPI (cia-auth, Task 4) ← impl (cia-api, Task 5); filter ctor gains the SPI in Task 4 with a mock in unit tests, real `@Component` impl wired by Spring in Task 5+. cia-auth must NOT depend on cia-api (SPI stays in cia-auth; impl is a cia-api `@Component` injected by type — verify the cia-api context picks it up).
- **Name consistency:** `SUPER_ADMIN` (role) → `ROLE_SUPER_ADMIN` (JwtAuthConverter) → `hasRole('SUPER_ADMIN')` (controller) — consistent. `cia.platform.realm` used identically by `PlatformBootstrapProperties` (cia-api) and `PlatformRealmProperties` (cia-auth) — two classes, one key; flagged to keep in sync. `TenantActivationLookup.isActive/evict` signatures match across SPI (Task 4), impl (Task 5), and service eviction call (Task 7).
- **Known integration risks to verify during implementation (not guesses to hardcode):** the exact `KeycloakItSupport` helper names (Task 1/2/9); the project `ApiResponse`/`CiaException`/`GlobalExceptionHandler` factory signatures (Task 7/8); whether `KeycloakRealms` is accessible from cia-api (Task 8) — if package-private in cia-auth, add a public accessor. Each is called out in-task.
