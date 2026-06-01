# Realm-per-Tenant JWT Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate JWTs from any Keycloak realm on our server against that realm's own JWKS, scoping each request to tenant = realm name; reject foreign issuers 401; one realm keeps working unchanged.

**Architecture:** Replace `SecurityConfig`'s single `JwtDecoder` + `.jwt(...)` with a trusted, lazy, cached `JwtIssuerAuthenticationManagerResolver` (`.authenticationManagerResolver(...)`). Tenant becomes the realm parsed from the validated `iss` claim (falling back to the `tenant_id` claim). Trust model: base-URL (issuer must match `{KEYCLOAK_URL}/realms/{realm}`).

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Security OAuth2 resource server, JUnit 5 + AssertJ + Mockito. Module: `cia-auth` (+ `application.yml` in `cia-api`).

**Spec:** `docs/superpowers/specs/2026-06-01-realm-per-tenant-jwt-resolver-design.md`

---

## Context for the implementer (read before Task 1)

Current files (all in `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/`):

- `SecurityConfig.java` — has `@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri;`, a `jwtDecoder()` `@Bean` returning `JwtDecoders.fromIssuerLocation(issuerUri)`, and `.oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthConverter)))`. Injects `JwtAuthConverter` and `TenantContextFilter`.
- `JwtAuthConverter.java` — `Converter<Jwt, AbstractAuthenticationToken>`; maps `realm_access.roles` → `ROLE_*`. **Do not change.**
- `TenantContextFilter.java` — `OncePerRequestFilter`; currently reads `jwt.getClaimAsString("tenant_id")` → `TenantContext.setTenantId(...)`, clears in `finally`.
- `DevSecurityConfig.java` — `@Profile("dev")` permitAll. **Do not change.**

`cia-auth` deps: `cia-common`, `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-security-test` (test). The oauth2-resource-server starter already provides `JwtIssuerAuthenticationManagerResolver`, `JwtAuthenticationProvider`, `InvalidBearerTokenException`.

Test infra note: existing controller ITs use `@SpringBootTest` + `@WithMockUser` + `@MockBean JwtDecoder` — they pre-populate the `SecurityContext`, so they never hit the resolver. After this change, `@MockBean JwtDecoder` may no longer match a bean (we delete the `jwtDecoder()` bean). **This is handled in Task 6** — verify and, if any IT fails to load context because its `@MockBean JwtDecoder` no longer overrides anything, that's still fine (a `@MockBean` of an absent type just registers a new mock bean; it does not fail). Confirm empirically in Task 6; do not pre-emptively edit ITs.

Keycloak issuer shape: `http://localhost:8280/realms/cia` (dev). `realmOf("http://host:8280/realms/acme")` → `"acme"`.

---

## Task 1: `KeycloakRealms.realmOf` helper

**Files:**
- Create: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/KeycloakRealms.java`
- Test: `cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/KeycloakRealmsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubeero.cia.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class KeycloakRealmsTest {

    @Test
    @DisplayName("realmOf extracts the realm segment from a Keycloak issuer URL")
    void extractsRealm() {
        assertThat(KeycloakRealms.realmOf("http://localhost:8280/realms/cia")).isEqualTo("cia");
        assertThat(KeycloakRealms.realmOf("https://kc.cia.app/realms/acme-insurance"))
            .isEqualTo("acme-insurance");
    }

    @Test
    @DisplayName("realmOf tolerates a trailing slash and extra path after the realm")
    void toleratesTrailingAndExtra() {
        assertThat(KeycloakRealms.realmOf("http://localhost:8280/realms/cia/")).isEqualTo("cia");
        assertThat(KeycloakRealms.realmOf(
            "http://localhost:8280/realms/cia/protocol/openid-connect")).isEqualTo("cia");
    }

    @ParameterizedTest
    @DisplayName("realmOf returns null for issuers with no realm segment")
    @ValueSource(strings = {
        "http://localhost:8280",
        "http://localhost:8280/realms/",
        "http://localhost:8280/auth/cia",
        "not-a-url"
    })
    void nullWhenNoRealm(String issuer) {
        assertThat(KeycloakRealms.realmOf(issuer)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("realmOf returns null for null/blank")
    void nullWhenBlank(String issuer) {
        assertThat(KeycloakRealms.realmOf(issuer)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cia-auth test -Dtest=KeycloakRealmsTest`
Expected: compile failure / FAIL — `KeycloakRealms` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.nubeero.cia.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing helpers for Keycloak issuer URLs. A Keycloak realm issuer has the
 * shape {@code {server}/realms/{realm}} (optionally with a trailing slash or
 * further {@code /protocol/...} path). The realm segment is the tenant id
 * under the realm-per-tenant model.
 */
public final class KeycloakRealms {

    private KeycloakRealms() {}

    // Capture the segment immediately after "/realms/", up to the next "/" or end.
    private static final Pattern REALM = Pattern.compile(".*/realms/([^/]+)(?:/.*)?$");

    /**
     * Extracts the realm segment from a Keycloak issuer URL, or {@code null}
     * if the string has no non-empty {@code /realms/{realm}} segment.
     */
    public static String realmOf(String issuer) {
        if (issuer == null || issuer.isBlank()) {
            return null;
        }
        Matcher m = REALM.matcher(issuer);
        if (!m.matches()) {
            return null;
        }
        String realm = m.group(1);
        return realm.isBlank() ? null : realm;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cia-auth test -Dtest=KeycloakRealmsTest`
Expected: PASS (all cases).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/KeycloakRealms.java \
        cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/KeycloakRealmsTest.java
git commit -m "feat(auth): add KeycloakRealms.realmOf issuer parser"
```

---

## Task 2: `KeycloakProperties` (trust base URL)

**Files:**
- Create: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/KeycloakProperties.java`
- Modify: `cia-backend/cia-api/src/main/resources/application.yml` (document the existing `KEYCLOAK_URL` binds here too)

- [ ] **Step 1: Write the implementation** (no separate unit test — it's a config holder; exercised in Task 3's tests)

```java
package com.nubeero.cia.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Resource-server trust config. {@link #serverUrl} is the Keycloak base URL
 * whose realms this resource server trusts: an incoming token's {@code iss}
 * must be {@code {serverUrl}/realms/{realm}} for some non-empty realm. Backed
 * by {@code cia.keycloak.*}.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cia.keycloak")
public class KeycloakProperties {

    /** Keycloak base URL, e.g. {@code http://localhost:8280}. Trailing slash trimmed on read. */
    private String serverUrl = "http://localhost:8280";

    /** Normalised base with any trailing slash removed. */
    public String normalisedServerUrl() {
        String s = serverUrl == null ? "" : serverUrl.trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
```

- [ ] **Step 2: Add the property to `application.yml`**

Find the existing `cia.keycloak.admin:` block (around line 120-145). Add a `server-url` sibling
*above* `admin:` under `cia.keycloak:`:

```yaml
  keycloak:
    # Base Keycloak URL whose realms this resource server trusts (realm-per-tenant).
    # An incoming JWT's `iss` must be {server-url}/realms/{realm}. Mirrors the
    # frontend VITE_KEYCLOAK_URL and the partner token-url host.
    server-url: ${KEYCLOAK_URL:http://localhost:8280}
    # Admin-client config for UserService ... (existing block unchanged)
    admin:
      ...
```

- [ ] **Step 3: Verify it compiles**

Run: `mvn -q -pl cia-auth -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/KeycloakProperties.java \
        cia-backend/cia-api/src/main/resources/application.yml
git commit -m "feat(auth): add KeycloakProperties.serverUrl trust base"
```

---

## Task 3: `TenantIssuerJwtAuthenticationManagerResolver`

**Files:**
- Create: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantIssuerJwtAuthenticationManagerResolver.java`
- Test: `cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantIssuerJwtAuthenticationManagerResolverTest.java`

Design: the class wraps a `JwtIssuerAuthenticationManagerResolver` built from a trust+build
function. To keep it unit-testable without network, the decoder-build step is a package-private
`Function<String, AuthenticationManager> managerFactory` field with a production default that
calls `JwtDecoders.fromIssuerLocation`. Tests inject a fake factory.

- [ ] **Step 1: Write the failing test**

```java
package com.nubeero.cia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class TenantIssuerJwtAuthenticationManagerResolverTest {

    private KeycloakProperties props;
    private TenantIssuerJwtAuthenticationManagerResolver resolver;
    private AtomicInteger builds;

    @BeforeEach
    void setUp() {
        props = new KeycloakProperties();
        props.setServerUrl("http://localhost:8280/"); // trailing slash on purpose
        resolver = new TenantIssuerJwtAuthenticationManagerResolver(props, new JwtAuthConverter());
        builds = new AtomicInteger();
        // Inject a fake build function so no JWKS/network call happens.
        resolver.managerFactory = issuer -> {
            builds.incrementAndGet();
            return mock(AuthenticationManager.class);
        };
    }

    @Test
    @DisplayName("trusted issuer on our server builds a manager")
    void trustedIssuerBuilds() {
        AuthenticationManager m = resolver.resolveForIssuer("http://localhost:8280/realms/acme");
        assertThat(m).isNotNull();
        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same issuer is cached — build function runs once")
    void cachesPerIssuer() {
        resolver.resolveForIssuer("http://localhost:8280/realms/acme");
        resolver.resolveForIssuer("http://localhost:8280/realms/acme");
        assertThat(builds.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("different realms build separate managers")
    void distinctRealms() {
        resolver.resolveForIssuer("http://localhost:8280/realms/acme");
        resolver.resolveForIssuer("http://localhost:8280/realms/leadway");
        assertThat(builds.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("issuer on a foreign host is rejected (401), no build")
    void rejectsForeignHost() {
        assertThatThrownBy(() ->
            resolver.resolveForIssuer("http://evil.example.com/realms/acme"))
            .isInstanceOf(InvalidBearerTokenException.class);
        assertThat(builds.get()).isZero();
    }

    @Test
    @DisplayName("our server but no realm segment is rejected (401)")
    void rejectsNoRealm() {
        assertThatThrownBy(() ->
            resolver.resolveForIssuer("http://localhost:8280/realms/"))
            .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() ->
            resolver.resolveForIssuer("http://localhost:8280"))
            .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("null/blank issuer is rejected (401)")
    void rejectsBlank() {
        assertThatThrownBy(() -> resolver.resolveForIssuer(null))
            .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> resolver.resolveForIssuer("  "))
            .isInstanceOf(InvalidBearerTokenException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cia-auth test -Dtest=TenantIssuerJwtAuthenticationManagerResolverTest`
Expected: compile failure — class does not exist.

- [ ] **Step 3: Write the implementation**

```java
package com.nubeero.cia.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.stereotype.Component;

/**
 * Realm-per-tenant resource-server auth: resolves the {@link AuthenticationManager}
 * for a request from the token's {@code iss} claim, validating against that
 * realm's JWKS.
 *
 * <p>Trust model (base-URL): an issuer is trusted iff it is
 * {@code {KEYCLOAK_URL}/realms/{realm}} with a non-empty realm. Untrusted or
 * malformed issuers are rejected with {@link InvalidBearerTokenException} → HTTP
 * 401, never a 500. Per-issuer managers are built lazily on first token (no OIDC
 * discovery at startup) and cached.
 */
@Component
public class TenantIssuerJwtAuthenticationManagerResolver
        implements AuthenticationManagerResolver<HttpServletRequest> {

    private final KeycloakProperties props;
    private final JwtAuthConverter jwtAuthConverter;
    private final ConcurrentHashMap<String, AuthenticationManager> cache = new ConcurrentHashMap<>();

    /** Delegate that maps a trusted issuer to a request-bound resolver. */
    private final JwtIssuerAuthenticationManagerResolver delegate;

    /**
     * Builds the {@link AuthenticationManager} for a trusted issuer. Package-private
     * + overridable so unit tests can avoid the JWKS network fetch. Production default
     * fetches the realm's JWKS via OIDC discovery and reuses {@link JwtAuthConverter}.
     */
    Function<String, AuthenticationManager> managerFactory = this::buildManager;

    public TenantIssuerJwtAuthenticationManagerResolver(KeycloakProperties props,
                                                        JwtAuthConverter jwtAuthConverter) {
        this.props = props;
        this.jwtAuthConverter = jwtAuthConverter;
        // Spring's resolver reads the iss claim and calls our trust+build function.
        this.delegate = new JwtIssuerAuthenticationManagerResolver(
                (AuthenticationManagerResolver<String>) this::resolveForIssuer);
    }

    @Override
    public AuthenticationManager resolveForContext(HttpServletRequest request) {
        return delegate.resolve(request);
    }

    /**
     * Trust gate + lazy cache. Visible for testing. Throws
     * {@link InvalidBearerTokenException} for any untrusted/malformed issuer.
     */
    AuthenticationManager resolveForIssuer(String issuer) {
        if (!isTrusted(issuer)) {
            throw new InvalidBearerTokenException("Untrusted token issuer");
        }
        return cache.computeIfAbsent(issuer, managerFactory);
    }

    private boolean isTrusted(String issuer) {
        String realm = KeycloakRealms.realmOf(issuer);
        if (realm == null) {
            return false;
        }
        // Exact match against our server's canonical issuer for that realm.
        String expected = props.normalisedServerUrl() + "/realms/" + realm;
        return expected.equals(trimTrailingSlash(issuer));
    }

    private AuthenticationManager buildManager(String issuer) {
        JwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(jwtAuthConverter);
        return provider::authenticate;
    }

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}
```

Note on the `JwtIssuerAuthenticationManagerResolver` constructor: it accepts an
`AuthenticationManagerResolver<String>` (issuer → manager). We pass `this::resolveForIssuer`.
Spring extracts the `iss` claim itself and calls our function; our function applies the trust
gate + cache. If the cast/constructor signature differs in 3.3.5, use the
`new JwtIssuerAuthenticationManagerResolver(AuthenticationManagerResolver<String>)` overload
(it exists in Spring Security 6.x). Verify by compiling.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cia-auth test -Dtest=TenantIssuerJwtAuthenticationManagerResolverTest`
Expected: PASS (6 cases).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantIssuerJwtAuthenticationManagerResolver.java \
        cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantIssuerJwtAuthenticationManagerResolverTest.java
git commit -m "feat(auth): trusted lazy per-issuer JWT auth manager resolver"
```

---

## Task 4: Wire the resolver into `SecurityConfig`

**Files:**
- Modify: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/SecurityConfig.java`

- [ ] **Step 1: Edit `SecurityConfig`**

Remove the issuer-uri `@Value` field and the `jwtDecoder()` `@Bean`; inject the new resolver;
swap `.jwt(...)` for `.authenticationManagerResolver(...)`.

Before:
```java
    private final JwtAuthConverter jwtAuthConverter;
    private final TenantContextFilter tenantContextFilter;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;
```
After:
```java
    private final TenantContextFilter tenantContextFilter;
    private final TenantIssuerJwtAuthenticationManagerResolver authenticationManagerResolver;
```
(Drop the `jwtAuthConverter` field here — it now lives inside the resolver. Drop the
`@Value issuerUri` field and the `JwtDecoder`/`JwtDecoders`/`@Value` imports.)

Before:
```java
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter))
                )
```
After:
```java
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(authenticationManagerResolver)
                )
```

Delete the whole `jwtDecoder()` `@Bean` method.

- [ ] **Step 2: Verify it compiles**

Run: `mvn -q -pl cia-auth -am compile`
Expected: BUILD SUCCESS. (If `JwtDecoder` import is now unused, remove it.)

- [ ] **Step 3: Run cia-auth unit tests**

Run: `mvn -q -pl cia-auth test`
Expected: PASS (the two new test classes + any existing).

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/SecurityConfig.java
git commit -m "feat(auth): resolve auth manager per token issuer (realm-per-tenant)"
```

---

## Task 5: `TenantContextFilter` — realm-from-iss authoritative

**Files:**
- Modify: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java`
- Test: `cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterTest.java`

- [ ] **Step 1: Write the failing test**

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private Jwt jwtWith(String iss, String tenantClaim) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u");
        if (iss != null) b.claim("iss", iss);
        if (tenantClaim != null) b.claim("tenant_id", tenantClaim);
        return b.build();
    }

    private String capturedTenantDuringChain(Jwt jwt) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt));
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = TenantContext.getTenantId();
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);
        return seen[0];
    }

    @Test
    @DisplayName("realm from iss is the tenant (authoritative)")
    void realmFromIssWins() throws Exception {
        String t = capturedTenantDuringChain(
            jwtWith("http://localhost:8280/realms/acme", "ignored-claim"));
        assertThat(t).isEqualTo("acme");
    }

    @Test
    @DisplayName("falls back to tenant_id claim when iss has no realm")
    void fallsBackToClaim() throws Exception {
        String t = capturedTenantDuringChain(jwtWith(null, "cia"));
        assertThat(t).isEqualTo("cia");
    }

    @Test
    @DisplayName("clears tenant after the chain")
    void clearsAfter() throws Exception {
        capturedTenantDuringChain(jwtWith("http://localhost:8280/realms/acme", null));
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("non-JWT principal sets no tenant")
    void nonJwtNoTenant() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("u", "p"));
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = TenantContext.getTenantId();
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);
        assertThat(seen[0]).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -pl cia-auth test -Dtest=TenantContextFilterTest`
Expected: FAIL on `realmFromIssWins` (current code reads only `tenant_id`, so tenant would be
`ignored-claim`, not `acme`).

- [ ] **Step 3: Edit `TenantContextFilter`**

Replace the tenant-resolution block inside the `try`:

```java
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                // Realm parsed from the validated `iss` is authoritative (it is
                // what the signature was checked against); fall back to the
                // tenant_id claim for tokens without a parseable realm issuer.
                String tenantId = KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
                if (tenantId == null || tenantId.isBlank()) {
                    tenantId = jwt.getClaimAsString("tenant_id");
                }
                if (tenantId != null && !tenantId.isBlank()) {
                    TenantContext.setTenantId(tenantId);
                }
            }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -pl cia-auth test -Dtest=TenantContextFilterTest`
Expected: PASS (4 cases).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java \
        cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterTest.java
git commit -m "feat(auth): derive tenant from validated realm (iss), fall back to claim"
```

---

## Task 6: Regression — full cia-auth suite + reactor compile + boot IT

**Files:** none (verification + reconciliation only)

- [ ] **Step 1: Full `cia-auth` test suite**

Run: `mvn -q -pl cia-auth test`
Expected: all green (3 new test classes + any existing).

- [ ] **Step 2: Install cia-auth + reactor compile**

Run: `mvn -q -pl cia-auth install -DskipTests && mvn -q -pl cia-api -am test-compile -DskipTests`
Expected: BUILD SUCCESS (cia-api still compiles against the changed SecurityConfig; the deleted
`jwtDecoder()` bean does not break compilation).

- [ ] **Step 3: Full-context boot IT regression**

Run: `mvn -q -pl cia-api failsafe:integration-test failsafe:verify -Dit.test=ReconciliationGateIT`
Expected: 2/2 PASS, no startup failure. This proves the app boots with the new
`.authenticationManagerResolver(...)` wiring and the `@MockBean JwtDecoder` in the IT base does
not break context load.

- [ ] **Step 4: Targeted controller IT (auth path under @WithMockUser)**

Run: `mvn -q -pl cia-api failsafe:integration-test failsafe:verify -Dit.test=PaymentListControllerIT`
Expected: PASS — confirms `@WithMockUser`-authenticated controller ITs are unaffected (they
pre-populate the SecurityContext, bypassing the resolver). If this fails because the IT base's
`@MockBean JwtDecoder` no longer matches a bean type, remove that `@MockBean` field from
`FinanceWebItSupport` (the resolver no longer exposes a `JwtDecoder` bean to mock) and re-run.
Document whichever path was taken.

- [ ] **Step 5: Commit any IT-base adjustment (only if Step 4 required one)**

```bash
git add -A && git commit -m "test(auth): adjust IT base for resolver-based oauth2 wiring"
```

---

## Final review (controller, after all tasks)

- [ ] Dispatch a final code reviewer over the whole change set (Tasks 1–6 commits) against the spec acceptance criteria. Confirm: foreign issuer → 401 (not 500); no startup OIDC call; realm-from-iss scoping; one-realm no regression; existing ITs green.
- [ ] Update `cia-log.md`: Session 141 entry; drain backlog row `prod-realm-per-tenant-jwt-resolver`; add P2 row `jwt-resolver-registry-allowlist` (public.tenants allowlist hardening) + optionally `jwt-resolver-2realm-live-it` (live 2-realm Testcontainers IT) if not done.
- [ ] Update `CLAUDE.md` §6 / §8 security wording: realm-per-tenant now wired at the resource-server layer via `TenantIssuerJwtAuthenticationManagerResolver` (base-URL trust); tenant derived from validated `iss`.
