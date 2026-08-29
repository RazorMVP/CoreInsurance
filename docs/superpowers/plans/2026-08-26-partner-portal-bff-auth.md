# Partner Portal — BFF + Partner Auth Foundation (Sub-project A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the token-handler BFF + partner-auth backend foundation so the (future) Partner Portal SPA can drive the existing M2M `/partner/v1/**` API with the browser holding no secret or token.

**Architecture:** A new `cia-partner-portal-bff` module exposes a cookie-authenticated, CORS-enabled `/portal/**` surface. It authenticates partner humans against a dedicated `partner` Keycloak realm, resolves human→app via `public.partner_portal_grant`, mints partner-app-scoped client-credentials tokens by fetching the `client_secret` from Keycloak just-in-time, and proxies to `/partner/v1/**` over real HTTP. Redis (already wired via `JedisPool`) backs sessions + rate-limit + usage rollups.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Security (resource server + a second `/portal/**` filter chain), Keycloak (Auth Code + PKCE for humans; client-credentials for apps), Jedis/Redis, Flyway, Temporal (daily flush cron), JUnit 5 + Testcontainers + MockMvc + AssertJ.

**Spec:** [`docs/superpowers/specs/2026-08-20-partner-portal-bff-auth-design.md`](../specs/2026-08-20-partner-portal-bff-auth-design.md) — the plan argues from it; executors read both.

## Global Constraints

- **Browser holds no access token, no refresh token, no `client_secret` — ever** (the token-handler invariant). The only secret ever returned to the browser is a freshly-rotated app secret, shown once in the rotate response.
- **`/partner/v1/**` is unchanged** in request/response behavior and keeps its deliberate no-CORS posture. The metrics filter + Redis rate-limit are transparent additions.
- **Demo-first / gated:** the `partner` realm + client provisioning and the bootstrap runner are `@ConditionalOnProperty`, **off by default** — dev + the IT suite never provision a live realm. Nothing in this plan requires a live Keycloak realm to build or test.
- **No-defer / build-complete:** every task fully resolves what it touches (Critical/Important/Minor). Backlog `partner-ratelimit-redis-distributed` is *cleared* by Task 10; no new follow-up rows are created (telemetry is built in Task 9, not deferred).
- **Store toggle:** Redis-backed components (session store, rate limiter, usage rollups) sit behind a `cia.partner-portal.store` / existing rate-limit toggle; **in-memory stays the dev/IT default** so ITs need no Redis unless the test opts in.
- **Migrations:** never edit an existing migration. New Flyway files only. Public-registry tables are qualified `public.<table>` with `IF NOT EXISTS` (V67 pattern); tenant tables are unqualified.
- **List endpoints** place the array in `data` and pagination in `meta` (`ApiResponse.success(page.getContent(), ApiMeta…)`), never a serialized `Page`.
- Google Java Style; all strings externalizable; no hardcoded tenant ids / currency / country.

---

### Task 1: Module scaffold + `public.partner_portal_grant` (schema + entity + repository)

**Files:**
- Create: `cia-backend/cia-partner-portal-bff/pom.xml` (artifactId `cia-partner-portal-bff`; deps: `cia-common`, `cia-auth`, `cia-partner-api`, `cia-setup`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-security`, test: `cia-common` test-jar pattern + testcontainers)
- Modify: `cia-backend/pom.xml` (add `<module>cia-partner-portal-bff</module>`)
- Modify: `cia-backend/cia-api/pom.xml` (add dependency on `cia-partner-portal-bff` so it assembles into the app)
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V80__partner_portal_grant.sql`
- Create: `cia-backend/cia-partner-portal-bff/src/main/java/com/nubeero/cia/portal/grant/PartnerPortalGrant.java`
- Create: `.../portal/grant/PartnerPortalGrantRepository.java`
- Create: `.../portal/grant/GrantRole.java` (enum `MANAGER`, `VIEWER`)
- Test: `cia-backend/cia-partner-portal-bff/src/test/java/com/nubeero/cia/portal/grant/PartnerPortalGrantRepositoryIT.java`

**Interfaces:**
- Consumes: nothing (first task).
- Produces:
  - `PartnerPortalGrant` (entity, `@Table(schema = "public", name = "partner_portal_grant")`) fields: `UUID id`, `UUID partnerUserId`, `String partnerUserEmail`, `String tenantSchema`, `UUID partnerAppId`, `GrantRole role`, `Instant createdAt`, `String createdBy`, `Instant deletedAt`.
  - `PartnerPortalGrantRepository extends JpaRepository<PartnerPortalGrant, UUID>`:
    - `List<PartnerPortalGrant> findByPartnerUserIdAndDeletedAtIsNull(UUID partnerUserId)`
    - `Optional<PartnerPortalGrant> findByPartnerUserIdAndPartnerAppIdAndDeletedAtIsNull(UUID partnerUserId, UUID partnerAppId)`
    - `List<PartnerPortalGrant> findByPartnerAppIdAndDeletedAtIsNull(UUID partnerAppId)`
  - `GrantRole { MANAGER, VIEWER }`.

- [ ] **Step 1: Write the migration**

`V80__partner_portal_grant.sql`:
```sql
-- Cross-tenant registry (public schema) mapping a partner human developer to the
-- Partner App(s) they may manage. partner_app_id is a soft cross-schema reference
-- (the app row lives in a tenant schema) — no DB FK, registry-style like public.tenants.
CREATE TABLE IF NOT EXISTS public.partner_portal_grant (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_user_id    UUID         NOT NULL,
    partner_user_email VARCHAR(255) NOT NULL,
    tenant_schema      VARCHAR(63)  NOT NULL,
    partner_app_id     UUID         NOT NULL,
    role               VARCHAR(16)  NOT NULL,          -- MANAGER | VIEWER
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(255),
    deleted_at         TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ppg_user_app
    ON public.partner_portal_grant (partner_user_id, tenant_schema, partner_app_id)
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ppg_user ON public.partner_portal_grant (partner_user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_ppg_app  ON public.partner_portal_grant (partner_app_id)  WHERE deleted_at IS NULL;
```

- [ ] **Step 2: Write the failing repository IT**

Model on an existing Testcontainers repo IT in the reactor (e.g. a `*RepositoryIT` in cia-setup/cia-finance). Assert:
```java
@Test
void findByUser_returnsActiveGrantsOnly() {
    UUID user = UUID.randomUUID();
    repo.save(grant(user, "tenant_acme", appA, GrantRole.MANAGER, null));
    repo.save(grant(user, "tenant_acme", appB, GrantRole.VIEWER, Instant.now())); // soft-deleted
    assertThat(repo.findByPartnerUserIdAndDeletedAtIsNull(user)).extracting(PartnerPortalGrant::getPartnerAppId)
        .containsExactly(appA);
}
@Test
void uniquePartial_blocksDuplicateActiveGrant() { /* second active (user,tenant,app) insert throws */ }
```

- [ ] **Step 3: Create the module pom + reactor + cia-api wiring**

Mirror an existing business module pom (e.g. `cia-reinsurance/pom.xml`) for parent + dependency shape.

- [ ] **Step 4: Implement entity + repository + enum**

`PartnerPortalGrant` extends the project's audited base only if `BaseEntity` fits (it has `id/created_at/updated_at/created_by/deleted_at`); if the registry table intentionally omits `updated_at`, use a plain `@Entity` with explicit columns (mirror how `public.tenants` / platform entities are mapped). Use `@Enumerated(EnumType.STRING)` for `role`.

- [ ] **Step 5: Run the IT (Testcontainers) — verify pass**

Run: `mvn -pl cia-partner-portal-bff -am verify -Dit.test=PartnerPortalGrantRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Dmaven.compiler.fork=true`

- [ ] **Step 6: Commit** — `feat(portal-bff): module scaffold + public.partner_portal_grant registry`

---

### Task 2: Partner realm config + roles + gated Keycloak client provisioning

**Files:**
- Create: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/partner/PartnerPortalRealmProperties.java` (mirror `PlatformRealmProperties`)
- Create: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/partner/PartnerPortalRoles.java` (constant list; `PARTNER_DEVELOPER`)
- Modify: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java` — add `ensurePartnerPortalClient(realm, spec)` mirroring the back-office/platform public-client upsert (public + PKCE S256 + redirect URIs + direct-grants disabled + a `tenant_id`-style mapper only if needed; partner realm needs no tenant mapper)
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/partnerportal/PartnerPortalBootstrapRunner.java` (`@ConditionalOnProperty("cia.partner-portal.bootstrap.enabled")`, off by default — mirror `PlatformBootstrapRunner`)
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/partnerportal/PartnerPortalBootstrapProperties.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/partnerportal/PartnerPortalBootstrapGatingTest.java`

**Interfaces:**
- Consumes: Task 1 module.
- Produces:
  - `PartnerPortalRealmProperties` — `String realm` (default `partner`), `String clientId` (default `cia-partner-portal`), `List<String> redirectUris`, bound `cia.partner-portal.*`.
  - `PartnerPortalRoles.ALL` = `List.of("PARTNER_DEVELOPER")`.
  - `KeycloakTenantProvisioner.ensurePartnerPortalClient(String realm, PartnerPortalClientSpec spec)` — idempotent create-then-reconcile.

- [ ] **Step 1: Write the failing gating test** — context loads with bootstrap disabled → no `PartnerPortalBootstrapRunner` bean; with `cia.partner-portal.bootstrap.enabled=true` + `cia.keycloak.admin.enabled=false` the runner fails fast (mirror the platform runner's admin-required guard). Use `ApplicationContextRunner`.

- [ ] **Step 2: Implement properties + roles** (mirror `PlatformRealmProperties` / `PlatformRoles` verbatim in shape).

- [ ] **Step 3: Implement `ensurePartnerPortalClient`** in `KeycloakTenantProvisioner` — copy the back-office public-client upsert path (S139); no `tenant_id` mapper (the `partner` realm is not a tenant). Re-assert `directAccessGrantsEnabled=false`, PKCE `S256`, standard flow on.

- [ ] **Step 4: Implement `PartnerPortalBootstrapRunner`** — gated `ApplicationRunner`: ensure realm exists + `UnmanagedAttributePolicy=ENABLED` + `ensurePartnerPortalClient` + `PartnerPortalRoles` + first `PARTNER_DEVELOPER` admin (temp password, forced reset) — all mirroring `PlatformBootstrapRunner`. Fails fast if admin client absent.

- [ ] **Step 5: Run the gating test — verify pass.** Run: `mvn -pl cia-api -am test -Dtest=PartnerPortalBootstrapGatingTest -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.fork=true`

- [ ] **Step 6: Commit** — `feat(portal-bff): partner realm + cia-partner-portal client provisioning (gated, off)`

---

### Task 3: Internal "invite developer" endpoint

**Files:**
- Modify: `cia-backend/cia-partner-api/src/main/java/com/nubeero/cia/partner/controller/PartnerAppController.java` — add `POST /api/v1/partner-apps/{id}/developers` (System-Admin gated `@PreAuthorize`, `partner:manage` or the existing partner-app authority) + `GET /api/v1/partner-apps/{id}/developers` (list grants) + `DELETE /api/v1/partner-apps/{id}/developers/{grantId}`
- Create: `.../partner/app/dto/InviteDeveloperRequest.java` (`@Email String email`, `GrantRole role`)
- Create: `.../partner/app/PartnerDeveloperService.java` (writes `PartnerPortalGrant`; when the partner realm is live + admin enabled, ensures the `partner`-realm user via Keycloak admin and captures its `partner_user_id`; demo-first: if realm not provisioned, store the email + a deterministic placeholder resolved on first login)
- Modify: `docs-site/static/internal-api.json` (regenerate via `InternalApiSnapshotIT` write mode)
- Test: `.../partner/app/PartnerDeveloperControllerIT.java`

**Interfaces:**
- Consumes: Task 1 (`PartnerPortalGrantRepository`, `GrantRole`).
- Produces: `PartnerDeveloperService.invite(UUID partnerAppId, InviteDeveloperRequest req, String actor)` → grant DTO; `.list(UUID partnerAppId)`; `.revoke(UUID partnerAppId, UUID grantId, String actor)`.

- [ ] **Step 1: Failing MockMvc IT** — POST creates a grant row (200 + body); duplicate active (user,app) is idempotent/409; list returns it; delete soft-deletes. Assert the endpoint requires the admin authority (403 without).

- [ ] **Step 2: Implement service + controller + DTO.** `PartnerDeveloperService` resolves the tenant schema from the current tenant context (the app lives in the caller's tenant). Grant row: `partner_user_id` = the Keycloak `partner`-realm user id when resolvable, else a stable UUID derived from the lowercased email (demo-first) reconciled at first login.

- [ ] **Step 3: Run IT — verify pass.**

- [ ] **Step 4: Regenerate the internal OpenAPI snapshot** — `mvn -pl cia-api failsafe:integration-test -Dit.test=InternalApiSnapshotIT -Dcia.openapi.snapshot.write=true …`, normalize with `python3 -m json.tool --indent 2`. (Guard: `InternalApiSnapshotIT` per memory `openapi-snapshot-guard`.)

- [ ] **Step 5: Commit** — `feat(partner-api): invite/list/revoke partner developers on a partner app`

---

### Task 4: Portal session store (Redis + in-memory) + session model

**Files:**
- Create: `.../portal/session/PortalSession.java` (record: `String id`, `UUID partnerUserId`, `String email`, `String displayName`, `String accessToken`, `String refreshToken`, `Instant absoluteExpiry`, `Instant idleExpiry`, `String csrfToken`)
- Create: `.../portal/session/PortalSessionStore.java` (SPI: `String create(PortalSession)`, `Optional<PortalSession> get(String id)`, `void touch(String id)`, `void delete(String id)`)
- Create: `.../portal/session/RedisPortalSessionStore.java` (`@ConditionalOnProperty(name="cia.partner-portal.store", havingValue="redis")`, uses the existing `JedisPool`; JSON-serialized value; idle + absolute TTL via Redis `EXPIRE`)
- Create: `.../portal/session/InMemoryPortalSessionStore.java` (`@ConditionalOnProperty(... havingValue="in-memory", matchIfMissing=true)`, `ConcurrentHashMap` + manual expiry check)
- Test: `.../portal/session/PortalSessionStoreTest.java` (in-memory) + `.../portal/session/RedisPortalSessionStoreIT.java` (Testcontainers Redis)

**Interfaces:**
- Produces: `PortalSessionStore` SPI + two impls; sessions keyed by opaque `id` (UUID, 128-bit).

- [ ] **Step 1: Failing unit test (in-memory)** — create→get roundtrips; `get` after absolute expiry returns empty; `touch` extends idle; `delete` removes.
- [ ] **Step 2: Implement the SPI + in-memory store + model.**
- [ ] **Step 3: Verify unit test passes.**
- [ ] **Step 4: Failing Testcontainers Redis IT** — same contract against `RedisPortalSessionStore` (Testcontainers `redis:7-alpine`, point `spring.data.redis.*` at it).
- [ ] **Step 5: Implement `RedisPortalSessionStore` (Jedis) — verify IT passes.**
- [ ] **Step 6: Commit** — `feat(portal-bff): portal session store (redis + in-memory) with idle/absolute TTL`

---

### Task 5: BFF auth flow — `/portal/auth/**` (Auth Code + PKCE server-side, cookie, CSRF) + security chain

**Files:**
- Create: `.../portal/auth/PortalAuthController.java` (`GET /portal/auth/login`, `GET /portal/auth/callback`, `GET /portal/auth/me`, `POST /portal/auth/logout`)
- Create: `.../portal/auth/PortalOAuthClient.java` (server-side Auth Code + PKCE: build authorize URL w/ `code_challenge`; exchange code→tokens at the `partner` realm token endpoint; RP-logout URL)
- Create: `.../portal/auth/PkceGenerator.java` (verifier + S256 challenge)
- Create: `.../portal/auth/PortalSecurityConfig.java` (a **second** `SecurityFilterChain` `@Order` matched to `/portal/**`: permit `/portal/auth/login|callback`, require a valid session cookie elsewhere via a `PortalSessionFilter`; CORS to the portal origin; CSRF double-submit on state-changing methods)
- Create: `.../portal/auth/PortalSessionFilter.java` (reads the cookie → loads `PortalSession` → sets a request-scoped principal; 401 if absent/expired)
- Test: `.../portal/auth/PortalAuthFlowIT.java`

**Interfaces:**
- Consumes: Task 4 (`PortalSessionStore`), Task 2 (`PartnerPortalRealmProperties`), Task 1 (`PartnerPortalGrantRepository` for `/me`'s app list).
- Produces: `PortalSessionFilter` sets `PortalPrincipal(partnerUserId, email, csrfToken)` for downstream `/portal/**` controllers; cookie name `cia_portal_session`.

- [ ] **Step 1: Failing IT** — with a stubbed token endpoint (WireMock or a test `PortalOAuthClient` bean): `GET /portal/auth/login` → 302 to the authorize URL with `code_challenge` + stored state; `GET /portal/auth/callback?code=…&state=…` → sets `Set-Cookie: cia_portal_session=…; HttpOnly; SameSite=Strict; Secure` and 302 to the SPA; `GET /portal/auth/me` with the cookie → 200 with profile + granted apps and **no token in the body**; `POST /portal/auth/logout` clears the session. Assert `Set-Cookie` never contains a JWT and `/me` body has no `accessToken`.
- [ ] **Step 2: Implement PKCE + OAuth client + controller + session filter + security chain.** The BFF is the OAuth client; tokens live only in the session store. CSRF: issue a `csrfToken` at login, return it in `/me`, require it as an `X-CSRF-Token` header on mutating `/portal/**` (double-submit vs the `SameSite=Strict` cookie).
- [ ] **Step 3: Verify IT passes.**
- [ ] **Step 4: Commit** — `feat(portal-bff): token-handler auth flow (/portal/auth) with HttpOnly cookie + CSRF`

---

### Task 6: Partner-app token minting (Keycloak-admin JIT secret + client-credentials + per-app cache)

**Files:**
- Create: `.../portal/token/PartnerAppTokenService.java`
- Create: `.../portal/token/MintedToken.java` (record: `String accessToken`, `Instant expiry`)
- Test: `.../portal/token/PartnerAppTokenServiceTest.java`

**Interfaces:**
- Consumes: `cia-setup` Keycloak admin infrastructure (`KeycloakAdminConfig`/`KeycloakAdminProperties` — the admin client used for user CRUD) to fetch a client's secret; the tenant realm token endpoint.
- Produces: `PartnerAppTokenService.tokenFor(String tenantRealm, String clientId)` → `MintedToken` — fetches the `client_secret` from Keycloak admin **just-in-time**, performs the client-credentials grant against `{server-url}/realms/{tenantRealm}/protocol/openid-connect/token`, caches per `(tenantRealm, clientId)` with a margin under `exp`.

- [ ] **Step 1: Failing unit test** — mock the admin secret-fetch + token endpoint: `tokenFor` returns a token; a second call within the cache window does **not** re-fetch the secret or re-hit the token endpoint (verify call counts); the secret never appears in `MintedToken` or any logged output.
- [ ] **Step 2: Implement the service + `ConcurrentHashMap` cache** (mirror the lazy per-realm decoder cache in `TenantIssuerJwtAuthenticationManagerResolver`).
- [ ] **Step 3: Verify unit test passes.**
- [ ] **Step 4: Commit** — `feat(portal-bff): JIT partner-app token minting via Keycloak-admin secret + client-credentials`

---

### Task 7: Grant authorization + `GET /portal/apps` (app-context)

**Files:**
- Create: `.../portal/apps/GrantAuthorizationService.java` (`assertGrant(UUID partnerUserId, UUID partnerAppId)`, `assertManager(...)` — throws 403 `PortalAccessDeniedException`)
- Create: `.../portal/apps/PortalAppsController.java` (`GET /portal/apps`)
- Create: `.../portal/apps/PortalAppSummary.java` (DTO: `partnerAppId`, `clientId`, `tenantSchema`, `tenantLabel`, `scopes`, `rateTier`, `status`, `role`)
- Test: `.../portal/apps/PortalAppsIT.java`

**Interfaces:**
- Consumes: Task 5 (`PortalPrincipal`), Task 1 (`PartnerPortalGrantRepository`), `cia-partner-api` PartnerApp read + Keycloak admin (client scopes/status).
- Produces: `GrantAuthorizationService` used by Tasks 8 + 9; `GET /portal/apps` returns the caller's apps.

- [ ] **Step 1: Failing IT** — a session whose user has a MANAGER grant for appA sees appA in `GET /portal/apps`; a user with no grant sees `[]`; `assertGrant` for an un-granted app throws 403; `assertManager` throws 403 for a VIEWER grant.
- [ ] **Step 2: Implement service + controller + DTO** (enrich each grant with PartnerApp + Keycloak client metadata; `ApiResponse.success(list, meta)`).
- [ ] **Step 3: Verify IT passes.**
- [ ] **Step 4: Commit** — `feat(portal-bff): grant authorization + GET /portal/apps app-context`

---

### Task 8: Management + try-it proxy (real HTTP to `/partner/v1/**`)

**Files:**
- Create: `.../portal/proxy/PortalProxyController.java` — webhooks CRUD (`/portal/apps/{id}/webhooks[...]`), `GET /portal/apps/{id}/credentials`, `POST /portal/apps/{id}/credentials/rotate`, and try-it `* /portal/apps/{id}/try/{path:.*}`
- Create: `.../portal/proxy/PartnerApiProxyClient.java` (server-side HTTP client to `/partner/v1/**`, attaching the minted Bearer token; relays status + body verbatim)
- Test: `.../portal/proxy/PortalProxyIT.java`

**Interfaces:**
- Consumes: Task 6 (`PartnerAppTokenService`), Task 7 (`GrantAuthorizationService`), Task 2 (realm props for the app's tenant realm), `cia-setup` Keycloak admin (secret rotate).
- Produces: the full management + try-it surface; `credentials/rotate` returns the new secret **once** (never stored).

- [ ] **Step 1: Failing IT** — `GET /portal/apps/{id}/try/products` proxies to `/partner/v1/products` and relays 200 + array body; a scope-denied try-it relays the upstream **403** verbatim; webhook create/list/delete round-trip; `credentials/rotate` returns a new secret and the old one stops working. All gated by `assertGrant`/`assertManager`. Prove the minted token is attached server-side (upstream sees a Bearer, the `/portal/**` request carried only a cookie).
- [ ] **Step 2: Implement the proxy client + controller.** Proxy makes a **real HTTP call** to the app's own `/partner/v1/**` (loopback), so `PartnerScopeFilter` + rate-limit behave identically to a live integration. Rotate uses Keycloak admin `regenerateSecret`.
- [ ] **Step 3: Verify IT passes.**
- [ ] **Step 4: Commit** — `feat(portal-bff): management + try-it proxy over real HTTP to /partner/v1`

---

### Task 9: Partner request telemetry — filter + `partner_request_daily` + Redis rollups + daily flush + usage endpoint

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V81__partner_request_daily.sql` (tenant schema; unqualified)
- Create: `.../partner/usage/PartnerRequestDaily.java` + `.../partner/usage/PartnerRequestDailyRepository.java` (in `cia-partner-api`)
- Create: `.../partner/config/PartnerRequestMetricsFilter.java` (`OncePerRequestFilter` on `/partner/v1/**`, ordered after auth)
- Create: `.../partner/usage/PartnerUsageRollupStore.java` (SPI + Redis + in-memory impls, `@ConditionalOnProperty` — mirror the session store toggle) — atomic per-day counters
- Create: `.../partner/usage/PartnerUsageFlushWorkflow.java` (+ impl + activity) — Temporal cron `0 3 * * *` (daily 03:00 UTC), mirroring `PdfDownloadLogRetentionWorkflow` registration
- Create: `.../portal/usage/PortalUsageController.java` (`GET /portal/apps/{id}/usage`) + `PortalUsageService` (compose live Redis rollup + `partner_request_daily` history + `webhook_delivery_logs`)
- Test: `.../partner/usage/PartnerRequestMetricsIT.java` + `.../portal/usage/PortalUsageIT.java`

**Interfaces:**
- Consumes: Task 7/8 auth; existing `WebhookDeliveryLogRepository`.
- Produces: `PartnerUsageRollupStore.increment(tenant, clientId, LocalDate, StatusClass)`; `GET /portal/apps/{id}/usage` → `{ today: {total,success,clientError,serverError}, history: [daily…], webhookDeliveries: {…}, errorRate }`.

`V81`:
```sql
CREATE TABLE IF NOT EXISTS partner_request_daily (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    partner_app_id UUID NOT NULL,
    usage_date     DATE NOT NULL,
    total          BIGINT NOT NULL DEFAULT 0,
    success        BIGINT NOT NULL DEFAULT 0,
    client_error   BIGINT NOT NULL DEFAULT 0,
    server_error   BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_prd_app_date UNIQUE (partner_app_id, usage_date)
);
CREATE INDEX IF NOT EXISTS idx_prd_app ON partner_request_daily (partner_app_id, usage_date DESC);
```

- [ ] **Step 1: Failing filter IT** — drive N requests (2xx/4xx/5xx) through `/partner/v1/**` in a MockMvc/Testcontainers harness; assert the rollup store shows the right per-app counters (in-memory store in the test).
- [ ] **Step 2: Implement the filter + rollup store (in-memory + Redis) + entity/repo + V81.**
- [ ] **Step 3: Failing usage-compose IT** — seed rollups + `webhook_delivery_logs`; `GET /portal/apps/{id}/usage` returns real counts + error rate + delivery summary, grant-gated.
- [ ] **Step 4: Implement `PortalUsageService` + controller + the daily flush workflow.** Flush upserts each day's Redis rollup into `partner_request_daily` (mirror the pdf-retention cron registration).
- [ ] **Step 5: Verify both ITs pass.**
- [ ] **Step 6: Commit** — `feat(partner-api): request telemetry (filter + rollups + daily flush) powering /portal usage`

---

### Task 10: Redis-backed distributed rate limiter (clears `partner-ratelimit-redis-distributed`)

**Files:**
- Modify: `cia-backend/cia-partner-api/pom.xml` (add `bucket4j-redis` / `bucket4j-jedis` matching the project's bucket4j version)
- Modify: `.../partner/config/PartnerRateLimitService.java` — behind `@ConditionalOnProperty(name="cia.partner.rate-limit.store", havingValue="redis")` use a Redis-backed proxy manager (Jedis) so buckets are shared across replicas; in-memory `ConcurrentHashMap` path stays `matchIfMissing=true`
- Test: `.../partner/config/PartnerRateLimitRedisIT.java` (Testcontainers Redis — two service instances sharing a client_id share the budget)

**Interfaces:**
- Consumes: the existing `JedisPool` bean.
- Produces: unchanged `PartnerRateLimitService` public API; storage swapped by the toggle.

- [ ] **Step 1: Failing Testcontainers Redis IT** — two `PartnerRateLimitService` instances (simulating replicas) pointed at the same Redis: the client's budget depletes across both (proves distribution). The in-memory default still passes the existing unit tests.
- [ ] **Step 2: Implement the Redis proxy-manager path** behind the toggle; keep in-memory default.
- [ ] **Step 3: Verify IT + existing rate-limit tests pass.**
- [ ] **Step 4: Commit** — `feat(partner-api): Redis-backed distributed rate limiting (toggle; in-memory default)` — closes backlog `partner-ratelimit-redis-distributed`.

---

### Task 11: CORS for the portal origin + config surface + full reactor verify

**Files:**
- Modify: `.../portal/auth/PortalSecurityConfig.java` (or a dedicated `CorsConfigurationSource`) — CORS for `/portal/**` from `cia.partner-portal.allowed-origins` (default the local partner Vite origin `http://localhost:5174`), `allowCredentials(true)` (cookie), methods + `X-CSRF-Token` header, exposes nothing sensitive; `/partner/**` stays CORS-free
- Modify: `cia-backend/cia-api/src/main/resources/application.yml` — add the `cia.partner-portal.*` block (realm, client-id, redirect-uris, allowed-origins, store, bootstrap.enabled=false)
- Modify: `CLAUDE.md` env table + `docs-site` — document the new `CIA_PARTNER_PORTAL_*` env vars (realm, client id, redirect URIs, allowed origins, store, bootstrap)
- Test: `.../portal/PortalCorsIT.java` (preflight `OPTIONS /portal/apps` from the portal origin → allowed; from a random origin → not allowed; `/partner/v1/**` preflight → still no CORS)

- [ ] **Step 1: Failing preflight IT** as above.
- [ ] **Step 2: Implement the `/portal/**` CORS policy + config + docs.**
- [ ] **Step 3: Verify IT passes.**
- [ ] **Step 4: Full reactor verify** — `mvn verify -Dmaven.compiler.fork=true` green (all new ITs + no regression).
- [ ] **Step 5: Commit** — `feat(portal-bff): /portal CORS policy + config surface + docs`

---

## Self-review notes (author)

- **Spec coverage:** A1 identity→Tasks 1,2,3; A2 auth flow→Tasks 4,5; A3 token minting→Task 6; A4 portal surface→Tasks 7,8; A5 module→Task 1; A6 demo-first→gating in Task 2 + toggles throughout; A7 telemetry→Task 9; Redis rate-limit fold-in→Task 10; CORS→Task 11. Security properties (no token/secret in browser, CSRF, least-privilege) asserted in the Task 5/6/7/8 ITs.
- **Type consistency:** `PartnerPortalGrant`/`GrantRole` (Task 1) consumed unchanged by Tasks 3,7; `PortalSessionStore` (4) by 5; `PortalPrincipal` (5) by 7,8,9; `PartnerAppTokenService.tokenFor` (6) by 8; `GrantAuthorizationService` (7) by 8,9; `PartnerUsageRollupStore` (9) shared filter+usage.
- **Demo-first honored:** no task requires a live `partner` realm; every Redis/Keycloak-live path is behind a toggle or gated runner with an in-memory/stub default so `mvn verify` is self-contained.
- **Backlog:** Task 10 removes `partner-ratelimit-redis-distributed`; no follow-up rows created (telemetry built in Task 9).
