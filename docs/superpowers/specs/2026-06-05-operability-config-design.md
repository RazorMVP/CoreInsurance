# Slice C — Production Operability Config — Design

> **Status:** Approved (brainstorm complete 2026-06-05). Next: writing-plans.
> **Milestone:** First deployable milestone = P0-1 (tenant provisioning, Slice A ✓) + P0-2 + the P1 operability bundle. This is **Slice C** of the A → C → B order.

## Goal (explicitly broadened — see §0)

Make the single Spring Boot binary **production-operable**: a thin `prod` Spring profile holding all prod deltas, a tuned single shared HikariCP pool, Prometheus-scrapeable metrics, structured (JSON) logs carrying the tenant, **and** the connection-layer `search_path` fix that gates the first real tenant's PII writes.

## 0. Scope note — named broaden

The slice's original goal was "operability config" (`application-prod.yml` + Hikari tuning + observability). During brainstorming we **explicitly broadened** it to also land the `runtime-pgcrypto-search-path` P1 fix, because:

- it is a hard **deploy-gate** for the first non-`public` tenant (NDPR PII writes via `pgp_sym_*` would throw — the function lives in `public`, unreachable from a tenant-only `search_path`), and
- it lives in the **same datasource/connection layer** this slice already tunes.

This is a named broaden (permitted by the slice-discipline rule), not silent growth. The backlog row `runtime-pgcrypto-search-path` is drained by this slice.

## 1. Decisions (from brainstorming Q&A)

| # | Decision | Choice |
|---|---|---|
| Q1 | Observability scope | **Metrics + structured logging.** Distributed tracing deferred (no collector backend until Slice B). |
| Q2 | Hikari sizing posture | **Conservative fixed-size single pool, fully tuned, env-overridable.** Default `max=min=10` (safe at 3 replicas under PG's default `max_connections=100`). |
| Q3 | `application-prod.yml` structure | **Thin override** — only prod deltas + `${ENV_VAR}` placeholders; base `application.yml` stays the single source for shared structure (Spring profile-merge supplies the rest). |
| Q4 | pgcrypto `search_path` fix | **Fold into Slice C** (named broaden, §0). |
| A1 | Structured-logging mechanism | **Native Spring Boot 3.4+ structured logging** (`logging.structured.format.console=ecs`). No dependency, no `logback-spring.xml`. Profile-scoped so dev logs stay human-readable. |

## 2. Ground truth (verified against `main`, 2026-06-05)

- **Spring Boot 3.5.14** → native structured logging (`logging.structured.format.*`, GA since 3.4) is available.
- `cia-api/pom.xml` already has `spring-boot-starter-actuator`; **lacks** `micrometer-registry-prometheus`.
- **No** `logback-spring.xml` anywhere in `cia-backend`.
- **No** `application-prod.yml` yet.
- **The connection model is a SINGLE shared HikariCP pool**, not pool-per-tenant. `MultiTenantConnectionProvider` (in `cia-common`) holds one `DataSource`; `getConnection(tenant)` borrows + `connection.setSchema(tenant)`; `releaseConnection` resets `setSchema("public")` + closes. PostgreSQL's JDBC `setSchema` issues `SET search_path TO "<schema>"` — **no `public`** — which is the bug.
- `TenantContextFilter` (in `cia-auth`) resolves the tenant from the validated JWT realm (`iss`) with `tenant_id` fallback, sets `TenantContext`, clears in `finally`. **This is the MDC enrichment hook.**
- **`ProductionSafetyValidator` already exists** (`cia-common`, an `EnvironmentPostProcessor`). It keys off the marker `cia.deployment.environment` (env `CIA_DEPLOYMENT_ENVIRONMENT`, default `local`), **not** the Spring profile — because a forgotten `SPRING_PROFILES_ACTIVE` defaults to `dev`, and `DevSecurityConfig` (`@Profile("dev")`) permits all requests with no auth. When the marker is `production|prod|staging` it fail-fasts on: dev profile active, or any of 5 known-weak secret defaults still present (`cia.security.pii-key`, `cia.partner.webhook.signing-secret`, `cia.storage.access-key`, `cia.storage.secret-key`, `spring.datasource.password`).
- `TenantSchemas` validator (regex `^[a-z_][a-z0-9_]{0,62}$`) exists in **`cia-api`** (`com.nubeero.cia.api.tenant`). `cia-common` cannot depend on `cia-api`, so the pgcrypto fix requires promoting it down to `cia-common`.

**Prod deploy requires BOTH env settings** (documented for Slice B):
`SPRING_PROFILES_ACTIVE=prod` (loads `application-prod.yml`, deactivates `DevSecurityConfig`) **and** `CIA_DEPLOYMENT_ENVIRONMENT=production` (arms `ProductionSafetyValidator`). Neither implies the other by design.

## 3. Components

### 3.1 `application-prod.yml` (new — `cia-api/src/main/resources/`)

Thin override; loaded only when `prod` profile active. Contents (deltas only):

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: ${DB_POOL_MAX:10}
      minimum-idle: ${DB_POOL_MIN:10}
      connection-timeout: ${DB_POOL_CONNECTION_TIMEOUT_MS:30000}
      max-lifetime: ${DB_POOL_MAX_LIFETIME_MS:1740000}      # 29 min < PG/proxy idle cutoff
      keepalive-time: ${DB_POOL_KEEPALIVE_MS:300000}        # 5 min
      leak-detection-threshold: ${DB_POOL_LEAK_DETECTION_MS:60000}
      # base application.yml's connection-init-sql (pgcrypto pii-key) is inherited

logging:
  level:
    root: WARN
    com.nubeero: INFO
  structured:
    format:
      console: ecs

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true
```

- Every secret/URL that the base file currently supplies with a dev default is **already** `${ENV:default}` in `application.yml`; the prod profile does **not** re-declare them (thin-override). The deploy manifest sets the env vars; `ProductionSafetyValidator` enforces they aren't the weak defaults. The prod profile re-declaring them would be duplication (rejected Q3 Option B).
- Actuator web exposure is **`health,info,prometheus` only** — `metrics`, `env`, `beans`, `configprops` stay off the web surface (no config/secret leakage). `prometheus` *is* the scrape surface.

### 3.2 Metrics — `micrometer-registry-prometheus`

- Add dependency to `cia-api/pom.xml` (version managed by the Boot BOM — no explicit version).
- `/actuator/prometheus` is exposed by §3.1's prod exposure list.
- A `MeterFilter` `@Bean` (in a small `MetricsConfig` under `cia-api`) adds common tag `application=cia-api`. No profile gate needed (harmless in dev; the endpoint exposure is what's prod-gated).

### 3.3 Structured logging + tenant MDC

- **Format:** `logging.structured.format.console=ecs` (§3.1) — prod profile only; dev stays human-readable.
- **MDC enrichment:** `TenantContextFilter.doFilterInternal` — after `TenantContext.setTenantId(tenantId)`, also `MDC.put("tenant", tenantId)`; in the existing `finally`, add `MDC.remove("tenant")` alongside `TenantContext.clear()`. ECS structured output includes MDC key-values automatically, so every prod log line during a tenant-scoped request carries `tenant`.
- No new dependency, no XML.

### 3.4 pgcrypto `search_path` fix (the deploy-gate)

- **Promote** `TenantSchemas` (regex validator) from `cia-api` → `cia-common` (`com.nubeero.cia.common.tenant`). Update the `cia-api` call sites' imports. (The `cia-api` `TenantSchemas` is deleted; one canonical copy in `cia-common`.)
- **`MultiTenantConnectionProvider.getConnection(tenant)`**: replace `connection.setSchema(tenant)` with:
  - guard: `TenantSchemas.validate(tenant)` (reject anything not matching the regex — defense-in-depth; the value is from the validated JWT realm but identifiers can't be parameterized).
  - then issue, via a `Statement`, `SET search_path TO "<tenant>", public` (tenant quoted with double-quotes; regex guard already forbids quotes/spaces). `public` last so tenant tables shadow nothing but `pgp_sym_*` (in `public`) resolves.
- **`releaseConnection`**: reset to a neutral state — `SET search_path TO public` (equivalently keep `setSchema("public")`; pick `SET search_path TO public` for symmetry). Then close.
- **`public` tenant** (the registry/system schema) stays correct: `SET search_path TO "public", public` is harmless/idempotent.

### 3.5 Docs

- **CLAUDE.md §7**: correct "HikariCP with one pool per tenant schema, lazily initialised on first request to that tenant" → "**A single shared HikariCP pool**; `MultiTenantConnectionProvider` borrows a connection and switches `search_path` per borrow (tenant schema + `public`), resetting on release." Also update the §6 `runtime-pgcrypto-search-path` note from "Latent … tracked in the backlog" → "**Closed in Slice C** — `MultiTenantConnectionProvider` now sets `search_path TO "<tenant>", public`."
- **`cia-log.md`**: backlog reconciliation (drain `runtime-pgcrypto-search-path`, `hikari-pool-tuning`, `application-prod-yml`, `prod-observability`; add any newly surfaced rows or state "no backlog growth") + full Slice C session entry.
- CLAUDE.md env-var table: add `DB_POOL_*` overrides; note the `SPRING_PROFILES_ACTIVE=prod` + `CIA_DEPLOYMENT_ENVIRONMENT=production` pairing.

## 4. Testing posture

| Component | Test | Kind |
|---|---|---|
| pgcrypto fix (load-bearing) | Real-Postgres Testcontainers IT: create a non-`public` tenant schema, install pgcrypto in `public`, borrow via `MultiTenantConnectionProvider`, run `pgp_sym_encrypt`/`pgp_sym_decrypt` round-trip — **fails before the fix, passes after**. Also assert `releaseConnection` leaves `search_path` neutral. | IT |
| `TenantSchemas` in `cia-common` | Unit: valid names pass, injection-y names (`a"; drop`, spaces, leading digit, >63 chars) rejected. | Unit |
| prod profile loads | Context-loads test with `spring.profiles.active=prod` and the required `${ENV}` placeholders satisfied (test props); assert app context starts. | IT/slice |
| Hikari binding | Assert `HikariDataSource` reflects the tuned values under the prod profile (or bind `DataSourceProperties`/`HikariConfig` and assert). | Test |
| Metrics endpoint | Under prod exposure, `GET /actuator/prometheus` returns 200 + `text/plain` Prometheus body; `application="cia-api"` tag present. | IT |
| MDC tenant | Filter unit/IT asserts `MDC.get("tenant")` is set within the chain and removed after. | Test |

**Full-reactor gate:** `mvn verify` stays green (274 cia-api failsafe ITs + the new ones). The pgcrypto IT must run against the shared-container Testcontainers harness pattern (mirrors Slice A's `TenantProvisioningItSupport`).

## 5. Out of scope (YAGNI / deferred)

- **Distributed tracing** (micrometer-tracing + OTel/Zipkin exporter) — no collector until Slice B; add later with no rework.
- **k8s/Helm manifests, ConfigMap/Secret wiring, HPA, probes-as-deployment** — that's **Slice B**. Slice C only makes the binary *emit/accept* the right signals/config; Slice B wires the cluster to consume them.
- **PgBouncer / larger pool / true server-side pagination** — premature; revisit when load testing in Slice B says so.
- **Per-tenant metric tags / log-volume controls** — not needed day one.

## 6. File inventory

| File | Action |
|---|---|
| `cia-api/src/main/resources/application-prod.yml` | **create** |
| `cia-api/pom.xml` | modify (add `micrometer-registry-prometheus`) |
| `cia-api/.../config/MetricsConfig.java` (new, `cia-api`) | **create** (MeterFilter common tag) |
| `cia-common/.../tenant/TenantSchemas.java` | **create** (promoted from cia-api) |
| `cia-api/.../tenant/TenantSchemas.java` | **delete** (move to cia-common); fix call-site imports |
| `cia-common/.../tenant/MultiTenantConnectionProvider.java` | modify (search_path + guard) |
| `cia-auth/.../TenantContextFilter.java` | modify (MDC put/remove) |
| `cia-api/src/test/.../tenant/MultiTenantConnectionProviderSearchPathIT` (new) | **create** (load-bearing IT — lives in `cia-api` to reuse Slice A's Testcontainers `TenantProvisioningItSupport` harness; `cia-common` has no Testcontainers infra) |
| `cia-common/src/test/.../tenant/TenantSchemasTest` (new/moved) | **create/move** (pure unit — no DB — so `cia-common` is correct) |
| `cia-api/.../ProdProfileContextIT` + metrics/Hikari assertions | **create** |
| `CLAUDE.md`, `cia-log.md` | modify (corrections + reconciliation) |
