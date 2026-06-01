# Realm-per-Tenant JWT Resolver — Design Spec

**Backlog row:** `prod-realm-per-tenant-jwt-resolver` (P1)
**Date:** 2026-06-01 · **Session:** 141 · **Branch:** `main`

## Problem

The resource server validates a **single fixed issuer**. `SecurityConfig.jwtDecoder()`
calls `JwtDecoders.fromIssuerLocation("${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM:cia}")`,
and `.oauth2ResourceServer(o -> o.jwt(...))` binds that one decoder. So:

- Only tokens from the **one** realm `cia` are ever validated.
- The S139 hardcoded `tenant_id` mapper emits `tenant_id = <realm name>`, which is correct
  *only* under realm-per-tenant — but with one fixed issuer every authenticated user is in
  the same realm, so a **second tenant cannot be isolated by realm**.
- CLAUDE.md §6 promises realm-per-tenant ("a token from Tenant A cannot authenticate against
  Tenant B"); the resource-server layer does not deliver it.

## Goal

Validate JWTs from **any Keycloak realm hosted on our Keycloak server**, each against *that
realm's own* JWKS, and scope the request to the tenant = realm name. Tokens whose issuer is
not a realm on our server are rejected (401). One realm (`cia`) keeps working exactly as today.

## Trust model: **A — base-URL trust** (decided with user)

An issuer is trusted iff it matches `{KEYCLOAK_URL}/realms/{realm}` for our configured
`KEYCLOAK_URL` and a non-empty `{realm}` segment. We own the Keycloak, so every realm on it
is a legitimate tenant. We do **not** consult `public.tenants` in the auth path (that
allowlist hardening is logged as a separate P2 follow-up, `jwt-resolver-registry-allowlist`).

**Safety of the "realm without a schema" case:** if a token authenticates for a realm that
has no matching tenant schema yet, requests fail closed — Hibernate's
`MultiTenantConnectionProvider.getConnection()` does `connection.setSchema(realm)` against a
nonexistent schema → SQL error, **not** a cross-tenant read. No leak.

## Approach

Replace the single-decoder `.jwt(...)` wiring with Spring Security's
`JwtIssuerAuthenticationManagerResolver`, backed by a **trusted, lazy, cached** per-issuer
`AuthenticationManager` factory.

### New component: `TenantIssuerJwtAuthenticationManagerResolver` (cia-auth)

Implements `AuthenticationManagerResolver<HttpServletRequest>` by delegating to a
`JwtIssuerAuthenticationManagerResolver` constructed with a **custom trust + build function**
`String issuer -> AuthenticationManager`:

```
authManager(issuer):
  1. if issuer is null/blank                 -> throw InvalidBearerTokenException (401)
  2. if !isTrusted(issuer)                    -> throw InvalidBearerTokenException (401)
       isTrusted: issuer.equals(baseUrl + "/realms/" + realmSegment(issuer))
                  && realmSegment(issuer) is non-blank
                  && baseUrl prefix matches configured KEYCLOAK_URL (trailing slash normalised)
  3. cache.computeIfAbsent(issuer, i -> buildManager(i))   // lazy, no startup OIDC call
buildManager(issuer):
  JwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);   // fetches that realm's JWKS
  JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
  provider.setJwtAuthenticationConverter(jwtAuthConverter);      // reuse existing role mapping
  return provider::authenticate;
```

- **Lazy:** decoders are built on first token *per issuer*, not at startup → no OIDC discovery
  HTTP call at boot (preserves the existing IT `@MockBean JwtDecoder` behaviour and fast boot).
- **Cached:** `ConcurrentHashMap<String, AuthenticationManager>` so each realm's JWKS is fetched
  once. (`JwtIssuerAuthenticationManagerResolver`'s own internal map already caches; we add an
  explicit trust gate in front of its resolver function.)
- **Untrusted issuer → 401** via `InvalidBearerTokenException` (Spring maps to 401 with
  `WWW-Authenticate`), never a 500.

### `KeycloakProperties` (cia-auth, new `@ConfigurationProperties("cia.keycloak")`)

- `serverUrl` ← `${KEYCLOAK_URL:http://localhost:8280}` (the trust base; trailing slash trimmed).
- Keeps a single source of truth for the base URL the resolver trusts.

### `SecurityConfig` change

```java
.oauth2ResourceServer(oauth2 -> oauth2
    .authenticationManagerResolver(tenantIssuerResolver))   // was: .jwt(j -> j.jwtAuthenticationConverter(...))
```

- Delete the `@Value issuer-uri` field + `jwtDecoder()` `@Bean` (the resolver owns decoders now).
- `JwtAuthConverter` is unchanged and injected into the resolver (role mapping identical).

### `TenantContextFilter` — make realm the authoritative tenant source

Today it reads only the `tenant_id` claim. Change to prefer the **validated realm** from the
token's `iss` (authoritative — it's what the signature was validated against), falling back to
the `tenant_id` claim, then to nothing:

```
tenant = realmSegment(jwt.getClaimAsString("iss"))   // authoritative
         ?: jwt.getClaimAsString("tenant_id")          // S139 mapper fallback
if tenant non-blank -> TenantContext.setTenantId(tenant)
```

Rationale: a realm that predates / lacks the S139 `tenant_id` mapper is still correctly scoped,
and the tenant can never disagree with the realm the token was validated against. `realmSegment`
extraction is shared with the resolver (a small static helper, e.g. `KeycloakRealms.realmOf(issuer)`).

## Out of scope (explicitly)

- `public.tenants` allowlist enforcement in the auth path → new P2 row `jwt-resolver-registry-allowlist`.
- Per-tenant `KEYCLOAK_REALM` admin-client resolution (`KeycloakAdminProperties.targetRealm` stays
  single-realm; that's a separate user-management concern).
- Subdomain→realm mapping at the gateway (NGINX); we trust the token's `iss`, not the host.
- Changing `DevSecurityConfig` (dev permitAll) — untouched.

## Acceptance criteria

1. A JWT from realm `cia` authenticates and is scoped to tenant `cia` exactly as today
   (no regression).
2. A JWT from a second realm on the same Keycloak (e.g. `acme`) authenticates against `acme`'s
   JWKS and is scoped to tenant `acme`.
3. A JWT whose `iss` is **not** `{KEYCLOAK_URL}/realms/{realm}` (foreign issuer, or
   `{KEYCLOAK_URL}` with no realm) is rejected **401**, not 500.
4. No OIDC discovery HTTP call occurs at application startup (decoders built lazily on first
   token per realm).
5. `TenantContextFilter` scopes to the realm from `iss` even when the `tenant_id` claim is
   absent; when both present and agree, behaviour is unchanged.
6. Existing `@SpringBootTest` + `@WithMockUser` controller ITs and the `@MockBean JwtDecoder`
   pattern continue to pass unchanged (the resolver path is not exercised by `@WithMockUser`,
   which pre-populates the `SecurityContext`).
7. Full reactor compiles; `cia-auth` + a representative full-context boot IT
   (`ReconciliationGateIT`) stay green.

## Test plan

- **Unit (`cia-auth`):**
  - `KeycloakRealmsTest` — `realmOf` extracts realm from valid issuer; null/blank/`/realms/`-less
    issuer → null.
  - `TenantIssuerJwtAuthenticationManagerResolverTest` — trusted issuer builds+caches a manager
    (same instance on 2nd call); untrusted issuer (foreign host, missing realm, null) throws
    `InvalidBearerTokenException`; the decoder-build function is injected/mocked so no network.
  - `TenantContextFilterTest` — `iss`-realm wins; `tenant_id` fallback when no `iss`; clears in
    `finally`.
- **Wiring:** verify the resolver bean is wired into the filter chain (a `@SpringBootTest`
  context-load assertion is enough; the existing IT base already boots the chain).
- **Regression:** `cia-common`/`cia-auth` unit suites + `ReconciliationGateIT` (full-context boot)
  green; reactor `cia-api -am` compiles.
- A live 2-realm Testcontainers IT (validate token from realm B is accepted, token from an
  untrusted issuer is 401) is **desirable but optional** — note it as a follow-up if the
  Keycloak IT harness can't easily mint a second realm + sign tokens; the unit tests + trust-gate
  logic cover the contract.
