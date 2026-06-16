# Platform-Admin UI + Backend Extensions (SP2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `apps/platform` SUPER_ADMIN console (dark React SPA) + the backend it needs — consolidated tenant detail, server-side pagination, dashboard stats, super-admin invite/revoke, and a schema-aware V68 that also cures the `platform_audit_log` tenant-schema pollution.

**Architecture:** Four phases — (1) backend extensions to the existing SP1 `/api/v1/platform/**` surface + Keycloak provisioner + Flyway, (2) FE foundation (new `apps/platform` app + auth + `@cia/api-client/platform`), (3) FE screens (Dashboard, Tenants, Onboard, Tenant detail, Audit, Super-admins), (4) Vercel deploy. Backend lands + verifies green before any FE work.

**Tech Stack:** Java 21 / Spring Boot 3.5.14 / Flyway / Testcontainers (backend); React 18 + Vite + TypeScript + Tailwind + `@cia/{ui,auth,api-client}` + React Query + zod + Vitest (frontend); GitHub Actions + Vercel (deploy).

**Spec:** `docs/superpowers/specs/2026-06-11-platform-admin-ui-design.md`

**Reference reading (the engineer should skim these existing files — the plan mirrors their patterns):**
- Backend: `cia-api/.../platform/PlatformTenantController.java`, `PlatformTenantService.java`, `PlatformAuditService.java`, `cia-api/.../tenant/TenantRegistry.java`, `cia-setup/.../keycloak/KeycloakTenantProvisioner.java`, `cia-common/.../exception/{CiaException,BusinessRuleException,GlobalExceptionHandler}.java`.
- Backend tests: `cia-api/.../platform/PlatformTenantControllerIT.java` (extends `FinanceWebItSupport`, uses `jwt()` postprocessor), `cia-api/.../tenant/PlatformTenantServiceIT.java` (extends `TenantProvisioningItSupport`), `cia-api/.../platform/PlatformOnboardingE2EIT.java` (extends `KeycloakItSupport`, real Keycloak Testcontainer).
- Frontend: `apps/back-office/src/main.tsx`, `src/app/layout/{AppShell,Sidebar,Topbar}.tsx`, `packages/api-client/src/modules/finance-closures.ts` (zod house pattern), `packages/api-client/src/validation.ts` (`validatedGet`/`validatedList`/`validatedPost`), `packages/auth/src/{AuthProvider,keycloak}.tsx`, `apps/back-office/vitest.config.ts`.

**Key conventions this plan relies on (verified against the codebase):**
- A `CiaException` carries its own `HttpStatus`; `GlobalExceptionHandler.handleCiaException` reads it. So 409/404/422 = throw the right `CiaException` subclass. The 503-on-Keycloak-disabled path is a **controller-local** `@ExceptionHandler` (mirrors `UserController`), NOT a `CiaException`.
- Spring authority for the platform role is `ROLE_SUPER_ADMIN`; `@PreAuthorize("hasRole('SUPER_ADMIN')")`. The raw realm role in the JWT's `realm_access.roles` is `SUPER_ADMIN` (what the FE `hasRole('SUPER_ADMIN')` checks).
- List endpoints return `ApiResponse.success(List<T>, ApiMeta.builder().total(..).page(..).size(..).build())` — type `ApiResponse<List<T>>`, never `Page<T>`.
- FE: `@cia/api-client` barrel is flat (`export * from './modules'`); fetch with `validatedGet`/`validatedList`; `DataTable` is client-side-pagination only (no external page props) — server pagination = `validatedList` + local `useState(page)` + a manual footer. Dark theme = `class="dark"` on `<html>` (tokens live in `@cia/ui/tokens.css` `.dark`).
- FE auth: the shared `@cia/auth` `initKeycloak()` keys `onLoad: 'login-required'` off `VITE_KEYCLOAK_URL`. So the platform app **reuses the `VITE_KEYCLOAK_*` env names** (scoped per-deployment), defaulting `realm` → `platform`, `clientId` → `cia-platform`. (Deviation from the spec's `VITE_PLATFORM_KEYCLOAK_*` naming — chosen so `@cia/auth` needs no change; noted here and to be reflected in CLAUDE.md in Task 21.)

**Commands:**
- Backend single IT: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=<ClassName> -DfailIfNoTests=false`
- Backend full reactor: `cd cia-backend && mvn -q verify`
- Backend install (after multi-module changes): `cd cia-backend && mvn -q install -DskipTests -pl cia-api -am`
- FE typecheck: `cd cia-frontend && pnpm --filter @cia/platform typecheck`
- FE build: `cd cia-frontend && pnpm --filter @cia/platform build`
- FE test: `cd cia-frontend && pnpm --filter @cia/platform test`
- FE wiring/DTO guards: `bash cia-frontend/scripts/check-api-wiring.sh && node cia-frontend/scripts/check-dto-drift.mjs`

---

# PHASE 1 — Backend (cia-api + cia-setup + Flyway)

## Task 1: Flyway V68 — schema-aware (relax `target_schema` + drop tenant-copy pollution)

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V68__platform_audit_log_public_only.sql`
- Create (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/PlatformAuditLogMigrationIT.java`

**Context:** `platform_audit_log` is a public-only table, but V67 created it unqualified above the tenant baseline (`baselineVersion=2`), so the per-tenant Flyway sweep cloned a dead copy into every tenant schema. V68 branches on `current_schema()`: in the `public` run it relaxes `target_schema` to NULL (needed for user-targeted super-admin audit rows); in each tenant run it drops the dead copy (explicitly schema-qualified so it can never touch `public`).

- [ ] **Step 1: Write the failing migration IT**

The IT inlines its own Postgres container (isolated from the shared-container ITs), runs the **real** main Flyway against `public` to head (V68 included), then runs `TenantSchemaMigrator` against a tenant schema, and asserts the schema-aware outcome.

Create `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/PlatformAuditLogMigrationIT.java`:

```java
package com.nubeero.cia.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves V68 is schema-aware: it relaxes {@code target_schema} to NULL on the canonical
 * {@code public.platform_audit_log} and drops the dead per-tenant copies that V67's
 * unqualified above-baseline CREATE cloned into every tenant schema.
 *
 * <p>Inlines its own Postgres (isolated from the shared-container ITs) and runs the REAL
 * main Flyway to head + the REAL {@link TenantSchemaMigrator} for a tenant schema.
 */
class PlatformAuditLogMigrationIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciamig")
                    .withUsername("ciamig")
                    .withPassword("ciamig");

    static final HikariDataSource DS;

    static {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        DS = new HikariDataSource(cfg);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class, schema, table);
        return n != null && n > 0;
    }

    private static String columnNullability(JdbcTemplate jdbc, String schema, String table, String column) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                String.class, schema, table, column);
    }

    @Test
    void v68_makesPublicColumnNullable_andDropsTenantCopy() {
        JdbcTemplate jdbc = new JdbcTemplate(DS);

        // Main Flyway runs the full chain (V1..V68) against public — mirrors the app's
        // spring.flyway.schemas=public, baseline-on-migrate=true.
        Flyway.configure()
                .dataSource(DS)
                .schemas("public")
                .baselineOnMigrate(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // Per-tenant sweep runs V3..V68 against the tenant schema.
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(DS);
        migrator.ensureSchema("tenant_mig");
        migrator.migrate("tenant_mig");

        // public.platform_audit_log survives, with target_schema now nullable.
        assertThat(tableExists(jdbc, "public", "platform_audit_log"))
                .as("public copy must survive V68").isTrue();
        assertThat(columnNullability(jdbc, "public", "platform_audit_log", "target_schema"))
                .as("V68 relaxes target_schema to NULL on the public table").isEqualTo("YES");

        // The dead tenant copy is gone.
        assertThat(tableExists(jdbc, "tenant_mig", "platform_audit_log"))
                .as("V68 drops the vestigial per-tenant copy").isFalse();

        // A NULL-target_schema row (a user-targeted super-admin action) now inserts cleanly.
        assertThatCode(() -> jdbc.update(
                "INSERT INTO public.platform_audit_log "
                        + "(action, target_schema, actor_username, actor_realm, detail, source_ip) "
                        + "VALUES ('INVITE_SUPER_ADMIN', NULL, 'sa', 'platform', "
                        + "'{\"username\":\"x\"}'::jsonb, '127.0.0.1')"))
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run the IT to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformAuditLogMigrationIT -DfailIfNoTests=false`
Expected: FAIL — without V68, `target_schema` is `NO` (not nullable) and `tenant_mig.platform_audit_log` still exists.

- [ ] **Step 3: Write the V68 migration**

Create `cia-backend/cia-api/src/main/resources/db/migration/V68__platform_audit_log_public_only.sql`:

```sql
-- V68: platform_audit_log is a public-only table. V67 introduced it as an unqualified
-- CREATE TABLE above the tenant baseline (baselineVersion=2), so the per-tenant Flyway
-- sweep cloned a dead copy into every tenant schema. Branch on current_schema():
--   * public run  -> relax target_schema to NULL (super-admin invite/revoke audit rows
--                    have no tenant schema)
--   * tenant run  -> drop the dead copy (explicitly schema-qualified so it can NEVER
--                    fall through search_path to public.platform_audit_log)
DO $$
BEGIN
  IF current_schema() = 'public' THEN
    ALTER TABLE public.platform_audit_log ALTER COLUMN target_schema DROP NOT NULL;
  ELSE
    EXECUTE format('DROP TABLE IF EXISTS %I.platform_audit_log', current_schema());
  END IF;
END
$$;

COMMENT ON COLUMN public.platform_audit_log.action IS
  'ONBOARD | SUSPEND | ACTIVATE | INVITE_SUPER_ADMIN | REVOKE_SUPER_ADMIN';
```

- [ ] **Step 4: Run the IT to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformAuditLogMigrationIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/db/migration/V68__platform_audit_log_public_only.sql \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/PlatformAuditLogMigrationIT.java
git commit -m "feat(platform): V68 schema-aware platform_audit_log cleanup + nullable target_schema"
```

---

## Task 2: `PlatformAuditService` — paged reads, target-schema filter, recentForSchema

**Files:**
- Modify: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformAuditService.java`
- Modify (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformAuditServiceIT.java`

**Context:** Add `recentForSchema(schema, limit)` (for the consolidated tenant detail), `recent(page, size, targetSchemaOrNull)` (paginated audit trail, optional per-tenant filter), and `count(targetSchemaOrNull)` (for `ApiMeta.total`). The existing `record(...)` already accepts a (now-nullable) `targetSchema`, so super-admin audit rows need no new write method.

- [ ] **Step 1: Write the failing test additions**

Open `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformAuditServiceIT.java`. In `@BeforeEach`, after the `CREATE TABLE IF NOT EXISTS public.platform_audit_log (...)` statement, add an idempotent relax so NULL-schema rows insert on the shared container (V68 isn't run by this IT):

```java
        jdbc.execute("ALTER TABLE public.platform_audit_log ALTER COLUMN target_schema DROP NOT NULL");
```

Then add these tests (the class already has a `jdbc` field + a `PlatformAuditService audit` under test — mirror the existing setup; if the field names differ, adapt):

```java
    @Test
    @DisplayName("recentForSchema — returns only rows for the given target schema, newest first")
    void recentForSchema_filtersBySchema() {
        audit.record("ONBOARD",  "tenant_a", "sa", "platform", null, "1.1.1.1");
        audit.record("SUSPEND",  "tenant_b", "sa", "platform", null, "1.1.1.1");
        audit.record("ACTIVATE", "tenant_a", "sa", "platform", null, "1.1.1.1");

        var rows = audit.recentForSchema("tenant_a", 10);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.targetSchema()).isEqualTo("tenant_a"));
        assertThat(rows.get(0).action()).isEqualTo("ACTIVATE"); // newest first
    }

    @Test
    @DisplayName("recent(page,size,filter) — paginates and honours the optional target-schema filter")
    void recent_paged_andFiltered() {
        for (int i = 0; i < 5; i++) audit.record("ONBOARD", "tenant_p", "sa", "platform", null, "1.1.1.1");
        audit.record("ONBOARD", "tenant_q", "sa", "platform", null, "1.1.1.1");

        assertThat(audit.recent(0, 2, null)).hasSize(2);            // first page, unfiltered
        assertThat(audit.recent(0, 50, "tenant_p")).hasSize(5);     // filtered
        assertThat(audit.count(null)).isGreaterThanOrEqualTo(6L);
        assertThat(audit.count("tenant_p")).isEqualTo(5L);
    }

    @Test
    @DisplayName("recent — super-admin rows have NULL target_schema and are excluded by recentForSchema")
    void recent_nullSchemaRows_excludedFromPerSchema() {
        audit.record("INVITE_SUPER_ADMIN", null, "sa", "platform", "{\"username\":\"x\"}", "1.1.1.1");

        assertThat(audit.recent(0, 50, null))
                .anySatisfy(r -> assertThat(r.action()).isEqualTo("INVITE_SUPER_ADMIN"));
        assertThat(audit.recentForSchema("anything", 50))
                .noneSatisfy(r -> assertThat(r.action()).isEqualTo("INVITE_SUPER_ADMIN"));
    }
```

Add imports if missing: `import org.junit.jupiter.api.DisplayName;` and `import static org.assertj.core.api.Assertions.assertThat;`.

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformAuditServiceIT -DfailIfNoTests=false`
Expected: FAIL — `recentForSchema`, `recent(int,int,String)`, `count(String)` don't exist (compile error).

- [ ] **Step 3: Implement the new methods**

In `PlatformAuditService.java`, add (keep the existing `record(...)` and `recent(int limit)` untouched):

```java
    /** Newest-first audit rows for a single target schema (backs the consolidated tenant detail). */
    public List<PlatformAuditEntry> recentForSchema(String targetSchema, int limit) {
        return jdbc.query("SELECT id,action,target_schema,actor_username,actor_realm,detail,source_ip,at"
            + " FROM public.platform_audit_log WHERE target_schema = ? ORDER BY at DESC LIMIT ?",
            ROW_MAPPER, targetSchema, limit);
    }

    /**
     * Paged audit trail, newest first. When {@code targetSchema} is non-null, filters to that
     * tenant; when null, returns all actions (including user-targeted super-admin rows whose
     * {@code target_schema} is NULL).
     */
    public List<PlatformAuditEntry> recent(int page, int size, String targetSchema) {
        int offset = Math.max(0, page) * size;
        if (targetSchema == null) {
            return jdbc.query("SELECT id,action,target_schema,actor_username,actor_realm,detail,source_ip,at"
                + " FROM public.platform_audit_log ORDER BY at DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, size, offset);
        }
        return jdbc.query("SELECT id,action,target_schema,actor_username,actor_realm,detail,source_ip,at"
            + " FROM public.platform_audit_log WHERE target_schema = ? ORDER BY at DESC LIMIT ? OFFSET ?",
            ROW_MAPPER, targetSchema, size, offset);
    }

    /** Total audit-row count, optionally filtered to one target schema (for ApiMeta.total). */
    public long count(String targetSchema) {
        Long n = (targetSchema == null)
            ? jdbc.queryForObject("SELECT COUNT(*) FROM public.platform_audit_log", Long.class)
            : jdbc.queryForObject("SELECT COUNT(*) FROM public.platform_audit_log WHERE target_schema = ?",
                Long.class, targetSchema);
        return n == null ? 0L : n;
    }
```

Extract the existing inline row-mapper used by `recent(int)` into a shared constant so all three queries reuse it. Add this field near the top of the class:

```java
    private static final org.springframework.jdbc.core.RowMapper<PlatformAuditEntry> ROW_MAPPER =
        (rs, i) -> new PlatformAuditEntry(
            rs.getObject("id", java.util.UUID.class),
            rs.getString("action"),
            rs.getString("target_schema"),
            rs.getString("actor_username"),
            rs.getString("actor_realm"),
            rs.getString("detail"),
            rs.getString("source_ip"),
            rs.getTimestamp("at").toInstant());
```

And refactor the existing `recent(int limit)` body to `return jdbc.query("SELECT ... ORDER BY at DESC LIMIT ?", ROW_MAPPER, limit);`.

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformAuditServiceIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformAuditService.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformAuditServiceIT.java
git commit -m "feat(platform): PlatformAuditService paged reads + target-schema filter + recentForSchema"
```

---

## Task 3: `TenantRegistry` — paged findAll + counts

**Files:**
- Modify: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantRegistry.java`
- Modify (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/PlatformTenantServiceIT.java` (add a registry-paging test in the same class, which already wires a real `TenantRegistry`)

**Context:** Add `findAll(int page, int size)`, `countAll()`, `countActive()`. Keep the existing no-arg `findAll()` and `findActiveSchemas()`.

- [ ] **Step 1: Write the failing test**

In `PlatformTenantServiceIT.java`, add (it already has `registry` + the cleanup that deletes `tenant_plat`/`tenant_acme`/`tenant_beta`):

```java
    @Test
    @DisplayName("registry — paged findAll + counts reflect active/suspended split")
    void registry_pagedAndCounts() {
        registry.upsert("tenant_acme", "Acme", "acme");
        registry.upsert("tenant_beta", "Beta", "beta");
        registry.setActive("tenant_beta", false);

        long total = registry.countAll();
        long active = registry.countActive();
        assertThat(total).isGreaterThanOrEqualTo(2);
        assertThat(active).isLessThan(total); // beta is suspended

        var firstPage = registry.findAll(0, 1);
        assertThat(firstPage).hasSize(1);
        var secondPage = registry.findAll(1, 1);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage.get(0).schema()).isNotEqualTo(secondPage.get(0).schema());
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformTenantServiceIT -DfailIfNoTests=false`
Expected: FAIL — `findAll(int,int)`, `countAll()`, `countActive()` don't exist.

- [ ] **Step 3: Implement**

In `TenantRegistry.java`, add:

```java
    /** Page of tenants (active and inactive), ordered by creation time. */
    public List<TenantSummary> findAll(int page, int size) {
        int offset = Math.max(0, page) * size;
        return jdbc.query(
            "SELECT schema_name, name, subdomain, active, created_at FROM public.tenants"
                + " ORDER BY created_at LIMIT ? OFFSET ?",
            (rs, i) -> new TenantSummary(
                rs.getString("schema_name"),
                rs.getString("name"),
                rs.getString("subdomain"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()),
            size, offset);
    }

    /** Total number of registered tenants. */
    public long countAll() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM public.tenants", Long.class);
        return n == null ? 0L : n;
    }

    /** Number of active (non-suspended) tenants. */
    public long countActive() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM public.tenants WHERE active = TRUE", Long.class);
        return n == null ? 0L : n;
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformTenantServiceIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantRegistry.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/PlatformTenantServiceIT.java
git commit -m "feat(platform): TenantRegistry paged findAll + countAll/countActive"
```

---

## Task 4: DTOs (`TenantDetailResponse`, `PagedResult`, `TenantStats`) + `PlatformTenantService` detail/paged-list/stats

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/TenantDetailResponse.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/PagedResult.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/TenantStats.java`
- Modify: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantService.java`
- Modify (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/PlatformTenantServiceIT.java`

- [ ] **Step 1: Write the failing test**

In `PlatformTenantServiceIT.java`, add:

```java
    @Test
    @DisplayName("detail — returns tenant summary + its recent audit rows")
    void detail_returnsTenantAndRecentAudit() {
        service.onboard(
                new OnboardTenantRequest(SCHEMA, null, "Plat Corp", SUBDOMAIN, "admin", "a@plat.test"),
                "superadmin", "platform", "10.0.0.1");
        service.suspend(SCHEMA, "superadmin", "platform", "10.0.0.1");

        var detail = service.detail(SCHEMA).orElseThrow();
        assertThat(detail.tenant().schema()).isEqualTo(SCHEMA);
        assertThat(detail.recentAudit()).extracting("action").contains("ONBOARD", "SUSPEND");

        assertThat(service.detail("no_such_schema")).isEmpty();
    }

    @Test
    @DisplayName("list(page,size) + stats — paginates and reports total/active/suspended")
    void pagedListAndStats() {
        service.onboard(
                new OnboardTenantRequest("tenant_acme", null, "Acme", "acme", "admin", "a@acme.test"),
                "superadmin", "platform", "10.0.0.1");
        service.onboard(
                new OnboardTenantRequest("tenant_beta", null, "Beta", "beta", "admin", "a@beta.test"),
                "superadmin", "platform", "10.0.0.1");
        service.suspend("tenant_beta", "superadmin", "platform", "10.0.0.1");

        var paged = service.list(0, 1);
        assertThat(paged.items()).hasSize(1);
        assertThat(paged.total()).isGreaterThanOrEqualTo(2);
        assertThat(paged.page()).isZero();
        assertThat(paged.size()).isEqualTo(1);

        var stats = service.stats();
        assertThat(stats.total()).isEqualTo(stats.active() + stats.suspended());
        assertThat(stats.suspended()).isGreaterThanOrEqualTo(1);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformTenantServiceIT -DfailIfNoTests=false`
Expected: FAIL — `detail`, `list(int,int)`, `stats`, and the three DTOs don't exist.

- [ ] **Step 3: Create the DTOs**

`dto/TenantDetailResponse.java`:

```java
package com.nubeero.cia.api.platform.dto;

import com.nubeero.cia.api.platform.PlatformAuditService.PlatformAuditEntry;
import java.util.List;

/** Consolidated tenant view: the registry summary plus its most-recent audit trail. */
public record TenantDetailResponse(TenantSummary tenant, List<PlatformAuditEntry> recentAudit) {}
```

`dto/PagedResult.java`:

```java
package com.nubeero.cia.api.platform.dto;

import java.util.List;

/** A page of results plus the metadata the controller maps into {@code ApiMeta}. */
public record PagedResult<T>(List<T> items, long total, int page, int size) {}
```

`dto/TenantStats.java`:

```java
package com.nubeero.cia.api.platform.dto;

/** Dashboard counters: total tenants, active, and suspended (= total − active). */
public record TenantStats(long total, long active, long suspended) {}
```

- [ ] **Step 4: Add the service methods**

In `PlatformTenantService.java`, add imports `import com.nubeero.cia.api.platform.dto.PagedResult;`, `import com.nubeero.cia.api.platform.dto.TenantDetailResponse;`, `import com.nubeero.cia.api.platform.dto.TenantStats;` and these methods:

```java
    /** Consolidated detail: registry summary + the 20 most-recent audit rows for the schema. */
    public Optional<TenantDetailResponse> detail(String schema) {
        return registry.find(schema)
                .map(summary -> new TenantDetailResponse(summary, audit.recentForSchema(schema, 20)));
    }

    /** A page of tenants for the list view. */
    public PagedResult<TenantSummary> list(int page, int size) {
        return new PagedResult<>(registry.findAll(page, size), registry.countAll(), page, size);
    }

    /** Dashboard counters. */
    public TenantStats stats() {
        long total = registry.countAll();
        long active = registry.countActive();
        return new TenantStats(total, active, total - active);
    }
```

(Keep the existing no-arg `list()` — the controller migrates off it in Task 5, but leaving it avoids breaking any other caller in this task.)

- [ ] **Step 5: Run to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformTenantServiceIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/TenantDetailResponse.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/PagedResult.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/TenantStats.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantService.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/PlatformTenantServiceIT.java
git commit -m "feat(platform): TenantDetailResponse/PagedResult/TenantStats + service detail/paged-list/stats"
```

---

## Task 5: `PlatformTenantController` — paginate list/audit, consolidated detail, /stats

**Files:**
- Modify: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantController.java`
- Modify (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformTenantControllerIT.java`

**Context:** `GET /tenants` → `ApiResponse<List<TenantSummary>>` + `ApiMeta`; `GET /tenants/{schema}` → `TenantDetailResponse`; `GET /audit` → paged + optional `targetSchema`; new `GET /stats`. The old `?limit=` audit param and the bare-`TenantSummary` detail shape are removed (the SP2 UI is the only consumer).

- [ ] **Step 1: Update the controller IT (failing)**

In `PlatformTenantControllerIT.java`: the `audit_returnsRecentEntries` test currently stubs `audit.recent(anyInt())` and hits `/audit?limit=5`. Replace it, and add detail/list/stats tests. Replace the `audit_returnsRecentEntries` method body and add the new tests:

```java
    @Test
    void audit_returnsPagedEntries() throws Exception {
        when(audit.recent(org.mockito.ArgumentMatchers.anyInt(),
                          org.mockito.ArgumentMatchers.anyInt(),
                          org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(new PlatformAuditService.PlatformAuditEntry(
                        java.util.UUID.randomUUID(), "ONBOARD", "tenant_acme",
                        "superadmin", "platform", null, "127.0.0.1", Instant.now())));
        when(audit.count(org.mockito.ArgumentMatchers.isNull())).thenReturn(1L);

        mvc.perform(get("/api/v1/platform/audit?page=0&size=50")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action", is("ONBOARD")))
                .andExpect(jsonPath("$.meta.total", is(1)));
    }

    @Test
    void list_returnsPagedTenantsWithMeta() throws Exception {
        var summary = new TenantSummary("tenant_acme", "Acme", "acme", true, Instant.parse("2026-06-10T00:00:00Z"));
        when(service.list(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.nubeero.cia.api.platform.dto.PagedResult<>(List.of(summary), 1L, 0, 50));

        mvc.perform(get("/api/v1/platform/tenants?page=0&size=50")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].schema", is("tenant_acme")))
                .andExpect(jsonPath("$.meta.total", is(1)))
                .andExpect(jsonPath("$.meta.size", is(50)));
    }

    @Test
    void detail_returnsTenantPlusRecentAudit() throws Exception {
        var summary = new TenantSummary("tenant_acme", "Acme", "acme", true, Instant.parse("2026-06-10T00:00:00Z"));
        var entry = new PlatformAuditService.PlatformAuditEntry(
                java.util.UUID.randomUUID(), "ONBOARD", "tenant_acme",
                "superadmin", "platform", null, "127.0.0.1", Instant.now());
        when(service.detail("tenant_acme"))
                .thenReturn(java.util.Optional.of(
                        new com.nubeero.cia.api.platform.dto.TenantDetailResponse(summary, List.of(entry))));

        mvc.perform(get("/api/v1/platform/tenants/tenant_acme")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant.schema", is("tenant_acme")))
                .andExpect(jsonPath("$.data.recentAudit[0].action", is("ONBOARD")));
    }

    @Test
    void detail_unknownSchema_returns404() throws Exception {
        when(service.detail("tenant_ghost")).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/v1/platform/tenants/tenant_ghost")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("TENANT_NOT_FOUND")));
    }

    @Test
    void stats_returnsCounters() throws Exception {
        when(service.stats()).thenReturn(new com.nubeero.cia.api.platform.dto.TenantStats(12, 10, 2));

        mvc.perform(get("/api/v1/platform/stats")
                        .with(jwt()
                                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "superadmin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(12)))
                .andExpect(jsonPath("$.data.suspended", is(2)));
    }
```

Delete the now-obsolete `get_unknownSchema_returns404` test (replaced by `detail_unknownSchema_returns404`, since `get` is gone). Keep `onboard_*` tests unchanged.

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformTenantControllerIT -DfailIfNoTests=false`
Expected: FAIL (compile errors — `service.list(int,int)`/`detail`/`stats` and paged `audit.recent` not wired in the controller).

- [ ] **Step 3: Update the controller**

In `PlatformTenantController.java`: add imports `import com.nubeero.cia.common.api.ApiMeta;`, `import com.nubeero.cia.api.platform.dto.PagedResult;`, `import com.nubeero.cia.api.platform.dto.TenantDetailResponse;`, `import com.nubeero.cia.api.platform.dto.TenantStats;`. Replace the constants and the `list`/`get`/`auditTrail` methods, and add `stats`:

```java
    /** Default page size for paginated platform list endpoints. */
    private static final String DEFAULT_PAGE = "0";
    private static final String DEFAULT_SIZE = "50";
    /** Hard cap on page size, regardless of the requested size. */
    private static final int MAX_PAGE_SIZE = 500;

    @GetMapping("/tenants")
    public ApiResponse<List<TenantSummary>> list(
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size,
            @AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        PagedResult<TenantSummary> result = service.list(page, Math.min(size, MAX_PAGE_SIZE));
        return ApiResponse.success(result.items(), ApiMeta.builder()
                .total(result.total()).page(result.page()).size(result.size()).build());
    }

    @GetMapping("/tenants/{schema}")
    public ApiResponse<TenantDetailResponse> detail(@PathVariable String schema, @AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        return ApiResponse.success(service.detail(schema).orElseThrow(() -> new TenantNotFoundException(schema)));
    }

    @GetMapping("/stats")
    public ApiResponse<TenantStats> stats(@AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        return ApiResponse.success(service.stats());
    }

    @GetMapping("/audit")
    public ApiResponse<List<PlatformAuditService.PlatformAuditEntry>> auditTrail(
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_SIZE) int size,
            @RequestParam(required = false) String targetSchema,
            @AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        int capped = Math.min(size, MAX_PAGE_SIZE);
        var rows = audit.recent(page, capped, targetSchema);
        long total = audit.count(targetSchema);
        return ApiResponse.success(rows, ApiMeta.builder().total(total).page(page).size(capped).build());
    }
```

Remove the old `DEFAULT_AUDIT_LIMIT`/`MAX_AUDIT_LIMIT` constants and the old `get(...)` method (superseded by `detail`).

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformTenantControllerIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantController.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformTenantControllerIT.java
git commit -m "feat(platform): paginate tenants/audit, consolidated detail endpoint, /stats"
```

---

## Task 6: `KeycloakTenantProvisioner` — super-admin create/list/remove/count

**Files:**
- Modify: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java`
- Create (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/PlatformSuperAdminProvisioningIT.java`

**Context:** Add the Keycloak admin-client operations the super-admin lifecycle needs, keeping all Keycloak types encapsulated inside the provisioner (the module's hard rule). A small public record `SuperAdminView(username, email, enabled)` is the read shape. `createSuperAdmin` throws `SuperAdminExistsInRealm` (a static nested `RuntimeException`) when the username is taken; the service maps that to 409.

- [ ] **Step 1: Write the failing real-Keycloak IT**

Create `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/PlatformSuperAdminProvisioningIT.java` (mirrors `PlatformRealmProvisioningIT` — extends `KeycloakItSupport`, builds a real provisioner via `newProvisioner(ADMIN)` against a Testcontainers Keycloak):

```java
package com.nubeero.cia.api.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

/**
 * Real-Keycloak IT for the super-admin lifecycle on the platform realm:
 * create → list → count → remove-role. Mirrors {@code PlatformRealmProvisioningIT}.
 */
class PlatformSuperAdminProvisioningIT extends KeycloakItSupport {

    private static final String REALM = "platform_sa_it";
    private static Keycloak ADMIN;
    private static KeycloakTenantProvisioner provisioner;

    @BeforeAll
    static void provisionRealm() {
        ADMIN = adminClient();
        provisioner = newProvisioner(ADMIN);
        // Stand up the platform realm + SUPER_ADMIN role + client (one super-admin seeded).
        provisioner.provisionPlatformRealm(
                REALM, "cia-platform", List.of("http://localhost:5175/*"),
                new com.nubeero.cia.setup.keycloak.FirstAdminSpec(
                        "rootadmin", "root@platform.test", "Root", "Admin", "Aa1!rootpass",
                        java.util.UUID.randomUUID()));
    }

    @Test
    void create_list_count_remove() {
        provisioner.createSuperAdmin(ADMIN, REALM, "sa2", "sa2@platform.test", "Aa1!sa2pass");

        List<KeycloakTenantProvisioner.SuperAdminView> admins = provisioner.listSuperAdmins(ADMIN, REALM);
        assertThat(admins).extracting(KeycloakTenantProvisioner.SuperAdminView::username)
                .contains("rootadmin", "sa2");
        assertThat(provisioner.superAdminCount(ADMIN, REALM)).isGreaterThanOrEqualTo(2);

        // Duplicate create rejected.
        assertThatThrownBy(() -> provisioner.createSuperAdmin(ADMIN, REALM, "sa2", "x@y.test", "Aa1!x"))
                .isInstanceOf(KeycloakTenantProvisioner.SuperAdminExistsInRealm.class);

        // Remove the role → sa2 no longer a super-admin.
        provisioner.removeSuperAdminRole(ADMIN, REALM, "sa2");
        assertThat(provisioner.listSuperAdmins(ADMIN, REALM))
                .extracting(KeycloakTenantProvisioner.SuperAdminView::username)
                .doesNotContain("sa2");
    }
}
```

> Note: verify `KeycloakItSupport` exposes `newProvisioner(Keycloak)` and `adminClient()` (it does — `PlatformOnboardingE2EIT`/`PlatformRealmProvisioningIT` use them). If `newProvisioner` isn't present, construct via `new KeycloakTenantProvisioner(() -> ADMIN, props)` with a `KeycloakAdminProperties` whose `backOfficeClientId`/redirects are set; copy the exact construction from `PlatformRealmProvisioningIT`.

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformSuperAdminProvisioningIT -DfailIfNoTests=false`
Expected: FAIL — `createSuperAdmin`/`listSuperAdmins`/`superAdminCount`/`removeSuperAdminRole`/`SuperAdminView`/`SuperAdminExistsInRealm` don't exist.

- [ ] **Step 3: Implement the provisioner methods**

In `KeycloakTenantProvisioner.java`, add to the platform-realm section. Needed imports are already present (`UserRepresentation`, `RoleRepresentation`, `CredentialRepresentation`, `Response`, `NotFoundException`, `List`, `Map`):

```java
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
            userId = location.substring(location.lastIndexOf('/') + 1);
        }
        List<RoleRepresentation> roles = PlatformRoles.ALL.stream()
                .map(r -> realm.roles().get(r).toRepresentation())
                .toList();
        realm.users().get(userId).roles().realmLevel().add(roles);
        log.info("Platform realm '{}' — created super-admin '{}'", realmName, username);
    }

    /** Lists every user holding the SUPER_ADMIN realm role. */
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
     * account). Idempotent-ish: throws {@link NotFoundException} if the user doesn't exist.
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
```

- [ ] **Step 4: Install cia-setup + run the IT**

Run: `cd cia-backend && mvn -q install -DskipTests -pl cia-setup -am && mvn -q -pl cia-api verify -Dit.test=PlatformSuperAdminProvisioningIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/PlatformSuperAdminProvisioningIT.java
git commit -m "feat(platform): KeycloakTenantProvisioner super-admin create/list/count/remove"
```

---

## Task 7: `PlatformSuperAdminService` + DTOs + exceptions + `PlatformPasswords`

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformSuperAdminService.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformPasswords.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/SuperAdminSummary.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/InviteSuperAdminRequest.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/InviteSuperAdminResponse.java`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/SuperAdminExceptions.java`
- Modify: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantService.java` (use `PlatformPasswords`)
- Create (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformSuperAdminServiceTest.java`

**Context:** The service drives the platform realm via the provisioner, enforces the self-lockout + last-admin + duplicate + not-found guards, audits `INVITE_SUPER_ADMIN`/`REVOKE_SUPER_ADMIN`, and surfaces a 503 when the Keycloak admin client is unavailable. Tested as a pure Mockito unit (provisioner + audit mocked).

- [ ] **Step 1: Create the password util + extract from PlatformTenantService**

`PlatformPasswords.java`:

```java
package com.nubeero.cia.api.platform;

import java.security.SecureRandom;
import java.util.Base64;

/** Server-side one-time temporary-password generator shared by tenant onboard + super-admin invite. */
final class PlatformPasswords {
    private PlatformPasswords() {}
    private static final SecureRandom RNG = new SecureRandom();

    /** ≥24 chars with upper+lower+digit+special guaranteed by the {@code "Aa1!"} prefix. */
    static String generateTempPassword() {
        byte[] b = new byte[18];
        RNG.nextBytes(b);
        return "Aa1!" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
```

In `PlatformTenantService.java`, replace the private `generateTempPassword()` method and the `RNG` field with a call to `PlatformPasswords.generateTempPassword()` (delete the now-dead `SecureRandom RNG`, `import java.security.SecureRandom;`, `import java.util.Base64;`). At the call site: `String tempPassword = PlatformPasswords.generateTempPassword();`.

- [ ] **Step 2: Create the DTOs + exceptions**

`dto/SuperAdminSummary.java`:

```java
package com.nubeero.cia.api.platform.dto;

/** Read shape for a platform super-admin in the list view. */
public record SuperAdminSummary(String username, String email, boolean enabled) {}
```

`dto/InviteSuperAdminRequest.java`:

```java
package com.nubeero.cia.api.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body to invite a new platform super-admin. */
public record InviteSuperAdminRequest(
        @NotBlank String username,
        @NotBlank @Email String email) {}
```

`dto/InviteSuperAdminResponse.java`:

```java
package com.nubeero.cia.api.platform.dto;

/** Invite result — the temporary password is returned ONCE and never stored. */
public record InviteSuperAdminResponse(String username, String email, String temporaryPassword) {}
```

`SuperAdminExceptions.java` (one file, several small `CiaException` subclasses + the 503 marker):

```java
package com.nubeero.cia.api.platform;

import com.nubeero.cia.common.exception.CiaException;
import org.springframework.http.HttpStatus;

/** Platform super-admin lifecycle exceptions. */
public final class SuperAdminExceptions {
    private SuperAdminExceptions() {}

    /** 409 — username already exists in the platform realm. */
    public static class AlreadyExists extends CiaException {
        public AlreadyExists(String username) {
            super("SUPER_ADMIN_ALREADY_EXISTS",
                  "A super-admin named '" + username + "' already exists.", HttpStatus.CONFLICT);
        }
    }

    /** 404 — no such super-admin. */
    public static class NotFound extends CiaException {
        public NotFound(String username) {
            super("SUPER_ADMIN_NOT_FOUND",
                  "No super-admin named '" + username + "'.", HttpStatus.NOT_FOUND);
        }
    }

    /** 409 — a super-admin may not revoke their own access (self-lockout guard). */
    public static class CannotRevokeSelf extends CiaException {
        public CannotRevokeSelf() {
            super("CANNOT_REVOKE_SELF",
                  "You cannot revoke your own super-admin access.", HttpStatus.CONFLICT);
        }
    }

    /** 409 — refusing to remove the last remaining super-admin (zero-super-admin guard). */
    public static class CannotRevokeLast extends CiaException {
        public CannotRevokeLast() {
            super("CANNOT_REVOKE_LAST_SUPER_ADMIN",
                  "Cannot revoke the last remaining super-admin.", HttpStatus.CONFLICT);
        }
    }

    /** 503 — the Keycloak admin client is disabled (dev without Keycloak). Not a CiaException
     *  (mirrors UserController's controller-local 503 path). */
    public static class KeycloakAdminDisabled extends RuntimeException {
        public KeycloakAdminDisabled() {
            super("Keycloak admin client is disabled — super-admin management is unavailable.");
        }
    }
}
```

- [ ] **Step 3: Write the failing unit test**

`PlatformSuperAdminServiceTest.java`:

```java
package com.nubeero.cia.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nubeero.cia.api.platform.dto.InviteSuperAdminRequest;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner.SuperAdminView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.ObjectProvider;

class PlatformSuperAdminServiceTest {

    private Keycloak keycloak;
    private ObjectProvider<Keycloak> keycloakProvider;
    private KeycloakTenantProvisioner provisioner;
    private PlatformAuditService audit;
    private PlatformSuperAdminService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        keycloak = mock(Keycloak.class);
        keycloakProvider = mock(ObjectProvider.class);
        when(keycloakProvider.getIfAvailable()).thenReturn(keycloak);
        provisioner = mock(KeycloakTenantProvisioner.class);
        audit = mock(PlatformAuditService.class);
        var props = new PlatformRealmProperties(); // realm defaults to "platform"
        service = new PlatformSuperAdminService(keycloakProvider, provisioner, audit, props);
    }

    @Test
    void invite_createsAndAudits() {
        var resp = service.invite(new InviteSuperAdminRequest("sa2", "sa2@x.test"),
                "rootadmin", "platform", "1.1.1.1");

        assertThat(resp.username()).isEqualTo("sa2");
        assertThat(resp.temporaryPassword()).startsWith("Aa1!");
        verify(provisioner).createSuperAdmin(eq(keycloak), eq("platform"), eq("sa2"), eq("sa2@x.test"), anyString());
        verify(audit).record(eq("INVITE_SUPER_ADMIN"), eq(null), eq("rootadmin"), eq("platform"), anyString(), eq("1.1.1.1"));
    }

    @Test
    void invite_duplicate_mapsTo409() {
        org.mockito.Mockito.doThrow(new KeycloakTenantProvisioner.SuperAdminExistsInRealm("sa2"))
                .when(provisioner).createSuperAdmin(any(), anyString(), eq("sa2"), anyString(), anyString());

        assertThatThrownBy(() -> service.invite(new InviteSuperAdminRequest("sa2", "x@y.test"),
                "rootadmin", "platform", "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.AlreadyExists.class);
        verify(audit, never()).record(anyString(), any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void revoke_self_blocked() {
        assertThatThrownBy(() -> service.revoke("rootadmin", "rootadmin", "platform", "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.CannotRevokeSelf.class);
        verify(provisioner, never()).removeSuperAdminRole(any(), anyString(), anyString());
    }

    @Test
    void revoke_lastSuperAdmin_blocked() {
        when(provisioner.listSuperAdmins(keycloak, "platform"))
                .thenReturn(List.of(new SuperAdminView("victim", "v@x.test", true)));
        when(provisioner.superAdminCount(keycloak, "platform")).thenReturn(1L);

        assertThatThrownBy(() -> service.revoke("victim", "rootadmin", "platform", "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.CannotRevokeLast.class);
        verify(provisioner, never()).removeSuperAdminRole(any(), anyString(), anyString());
    }

    @Test
    void revoke_notFound_mapsTo404() {
        when(provisioner.listSuperAdmins(keycloak, "platform"))
                .thenReturn(List.of(new SuperAdminView("someoneelse", "s@x.test", true),
                                    new SuperAdminView("rootadmin", "r@x.test", true)));
        when(provisioner.superAdminCount(keycloak, "platform")).thenReturn(2L);

        assertThatThrownBy(() -> service.revoke("ghost", "rootadmin", "platform", "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.NotFound.class);
    }

    @Test
    void revoke_happyPath_removesAndAudits() {
        when(provisioner.listSuperAdmins(keycloak, "platform"))
                .thenReturn(List.of(new SuperAdminView("victim", "v@x.test", true),
                                    new SuperAdminView("rootadmin", "r@x.test", true)));
        when(provisioner.superAdminCount(keycloak, "platform")).thenReturn(2L);

        service.revoke("victim", "rootadmin", "platform", "1.1.1.1");

        verify(provisioner).removeSuperAdminRole(keycloak, "platform", "victim");
        verify(audit).record(eq("REVOKE_SUPER_ADMIN"), eq(null), eq("rootadmin"), eq("platform"), anyString(), eq("1.1.1.1"));
    }

    @Test
    void adminDisabled_throws503Marker() {
        when(keycloakProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.list())
                .isInstanceOf(SuperAdminExceptions.KeycloakAdminDisabled.class);
    }
}
```

- [ ] **Step 4: Run to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=PlatformSuperAdminServiceTest`
Expected: FAIL — `PlatformSuperAdminService` doesn't exist.

- [ ] **Step 5: Implement the service**

`PlatformSuperAdminService.java`:

```java
package com.nubeero.cia.api.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminRequest;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminResponse;
import com.nubeero.cia.api.platform.dto.SuperAdminSummary;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner.SuperAdminView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Platform super-admin lifecycle: list / invite / revoke against the platform Keycloak realm.
 *
 * <p>Drives the realm through {@link KeycloakTenantProvisioner}; enforces the self-lockout and
 * last-super-admin guards; audits INVITE/REVOKE to {@code public.platform_audit_log} (with a NULL
 * target_schema — these are user-targeted actions). When the Keycloak admin client is unavailable
 * (dev without Keycloak), every method throws {@link SuperAdminExceptions.KeycloakAdminDisabled},
 * which the controller maps to HTTP 503.
 */
@Slf4j
@Service
public class PlatformSuperAdminService {

    private final ObjectProvider<Keycloak> keycloak;
    private final KeycloakTenantProvisioner provisioner;
    private final PlatformAuditService audit;
    private final PlatformRealmProperties platformProps;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlatformSuperAdminService(ObjectProvider<Keycloak> keycloak,
                                     KeycloakTenantProvisioner provisioner,
                                     PlatformAuditService audit,
                                     PlatformRealmProperties platformProps) {
        this.keycloak = keycloak;
        this.provisioner = provisioner;
        this.audit = audit;
        this.platformProps = platformProps;
    }

    /** Lists current super-admins. */
    public List<SuperAdminSummary> list() {
        Keycloak client = client();
        return provisioner.listSuperAdmins(client, realm()).stream()
                .map(v -> new SuperAdminSummary(v.username(), v.email(), v.enabled()))
                .toList();
    }

    /** Invites a new super-admin; returns the one-time temp password. */
    public InviteSuperAdminResponse invite(InviteSuperAdminRequest req,
                                           String actor, String actorRealm, String ip) {
        Objects.requireNonNull(actor, "actor must not be null");
        Keycloak client = client();
        String tempPassword = PlatformPasswords.generateTempPassword();
        try {
            provisioner.createSuperAdmin(client, realm(), req.username(), req.email(), tempPassword);
        } catch (KeycloakTenantProvisioner.SuperAdminExistsInRealm e) {
            throw new SuperAdminExceptions.AlreadyExists(req.username());
        }
        audit.record("INVITE_SUPER_ADMIN", null, actor, actorRealm,
                toJson(Map.of("username", req.username(), "email", req.email())), ip);
        return new InviteSuperAdminResponse(req.username(), req.email(), tempPassword);
    }

    /** Revokes a super-admin's role. Guards: cannot revoke self; cannot revoke the last one. */
    public void revoke(String username, String actor, String actorRealm, String ip) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (username.equals(actor)) {
            throw new SuperAdminExceptions.CannotRevokeSelf();
        }
        Keycloak client = client();
        List<SuperAdminView> admins = provisioner.listSuperAdmins(client, realm());
        if (provisioner.superAdminCount(client, realm()) <= 1) {
            throw new SuperAdminExceptions.CannotRevokeLast();
        }
        boolean exists = admins.stream().anyMatch(a -> a.username().equals(username));
        if (!exists) {
            throw new SuperAdminExceptions.NotFound(username);
        }
        provisioner.removeSuperAdminRole(client, realm(), username);
        audit.record("REVOKE_SUPER_ADMIN", null, actor, actorRealm,
                toJson(Map.of("username", username)), ip);
    }

    private Keycloak client() {
        Keycloak c = keycloak.getIfAvailable();
        if (c == null) {
            throw new SuperAdminExceptions.KeycloakAdminDisabled();
        }
        return c;
    }

    private String realm() {
        return platformProps.getRealm();
    }

    private String toJson(Map<String, ?> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            log.warn("Failed to serialise super-admin audit detail; proceeding without detail", e);
            return null;
        }
    }
}
```

- [ ] **Step 6: Run to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=PlatformSuperAdminServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformSuperAdminService.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformPasswords.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/SuperAdminExceptions.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/SuperAdminSummary.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/InviteSuperAdminRequest.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/dto/InviteSuperAdminResponse.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformTenantService.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformSuperAdminServiceTest.java
git commit -m "feat(platform): PlatformSuperAdminService (invite/list/revoke + guards) + shared PlatformPasswords"
```

---

## Task 8: `PlatformSuperAdminController` (+ 503 handler) + web-layer IT

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformSuperAdminController.java`
- Create (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformSuperAdminControllerIT.java`

**Context:** Separate controller (identity lifecycle ≠ tenant lifecycle), same base path + same `SUPER_ADMIN` + `assertPlatformRealm` gating. 503 on `KeycloakAdminDisabled` via a controller-local `@ExceptionHandler` (mirrors `UserController`).

- [ ] **Step 1: Write the failing web IT**

`PlatformSuperAdminControllerIT.java` (mirrors `PlatformTenantControllerIT` — extends `FinanceWebItSupport`, mocks the service):

```java
package com.nubeero.cia.api.platform;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubeero.cia.api.finance.FinanceWebItSupport;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminResponse;
import com.nubeero.cia.api.platform.dto.SuperAdminSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

class PlatformSuperAdminControllerIT extends FinanceWebItSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean PlatformSuperAdminService service;

    private static final String PLATFORM_ISS = "https://kc.test/realms/platform";
    private static final String TENANT_ISS   = "https://kc.test/realms/tenant_acme";

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withSuperAdmin(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
        return b.with(jwt()
                .jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "rootadmin"))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));
    }

    @Test
    void list_returnsSuperAdmins() throws Exception {
        when(service.list()).thenReturn(List.of(new SuperAdminSummary("rootadmin", "r@x.test", true)));
        mvc.perform(withSuperAdmin(get("/api/v1/platform/super-admins")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username", is("rootadmin")));
    }

    @Test
    void invite_returns201WithTempPassword() throws Exception {
        when(service.invite(any(), anyString(), anyString(), anyString()))
                .thenReturn(new InviteSuperAdminResponse("sa2", "sa2@x.test", "Aa1!secret"));
        String body = objectMapper.writeValueAsString(Map.of("username", "sa2", "email", "sa2@x.test"));
        mvc.perform(withSuperAdmin(post("/api/v1/platform/super-admins"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.temporaryPassword", is("Aa1!secret")));
        verify(service).invite(any(), org.mockito.ArgumentMatchers.eq("rootadmin"), anyString(), anyString());
    }

    @Test
    void invite_nonPlatformRealm_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "sa2", "email", "sa2@x.test"));
        mvc.perform(post("/api/v1/platform/super-admins")
                        .with(jwt().jwt(j -> j.claim("iss", TENANT_ISS).claim("preferred_username", "x"))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void invite_missingRole_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("username", "sa2", "email", "sa2@x.test"));
        mvc.perform(post("/api/v1/platform/super-admins")
                        .with(jwt().jwt(j -> j.claim("iss", PLATFORM_ISS).claim("preferred_username", "x"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void revoke_self_returns409() throws Exception {
        doThrow(new SuperAdminExceptions.CannotRevokeSelf())
                .when(service).revoke(anyString(), anyString(), anyString(), anyString());
        mvc.perform(withSuperAdmin(delete("/api/v1/platform/super-admins/rootadmin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CANNOT_REVOKE_SELF")));
    }

    @Test
    void invite_keycloakDisabled_returns503() throws Exception {
        when(service.invite(any(), anyString(), anyString(), anyString()))
                .thenThrow(new SuperAdminExceptions.KeycloakAdminDisabled());
        String body = objectMapper.writeValueAsString(Map.of("username", "sa2", "email", "sa2@x.test"));
        mvc.perform(withSuperAdmin(post("/api/v1/platform/super-admins"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errors[0].code", is("KEYCLOAK_ADMIN_DISABLED")));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformSuperAdminControllerIT -DfailIfNoTests=false`
Expected: FAIL — `PlatformSuperAdminController` doesn't exist.

- [ ] **Step 3: Implement the controller**

`PlatformSuperAdminController.java`:

```java
package com.nubeero.cia.api.platform;

import com.nubeero.cia.api.platform.dto.InviteSuperAdminRequest;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminResponse;
import com.nubeero.cia.api.platform.dto.SuperAdminSummary;
import com.nubeero.cia.auth.KeycloakRealms;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform super-admin lifecycle REST surface under {@code /api/v1/platform/super-admins}.
 * Same double gate as {@link PlatformTenantController}: {@code hasRole('SUPER_ADMIN')} +
 * {@link #assertPlatformRealm}. A controller-local {@code @ExceptionHandler} maps the
 * Keycloak-disabled marker to HTTP 503 (mirrors {@code UserController}).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform/super-admins")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformSuperAdminController {

    private final PlatformSuperAdminService service;
    private final PlatformRealmProperties platformProps;

    @GetMapping
    public ApiResponse<List<SuperAdminSummary>> list(@AuthenticationPrincipal Jwt jwt) {
        assertPlatformRealm(jwt);
        return ApiResponse.success(service.list());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InviteSuperAdminResponse>> invite(
            @Valid @RequestBody InviteSuperAdminRequest req,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt);
        var resp = service.invite(req, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(resp));
    }

    @DeleteMapping("/{username}")
    public ApiResponse<Void> revoke(@PathVariable String username,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        assertPlatformRealm(jwt);
        service.revoke(username, actor(jwt), realm(jwt), http.getRemoteAddr());
        return ApiResponse.success(null);
    }

    /** Keycloak admin client disabled (dev without Keycloak) → 503. Mirrors UserController. */
    @ExceptionHandler(SuperAdminExceptions.KeycloakAdminDisabled.class)
    public ResponseEntity<ApiResponse<Void>> handleAdminDisabled(SuperAdminExceptions.KeycloakAdminDisabled e) {
        log.warn("Super-admin endpoint invoked with admin client disabled: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("KEYCLOAK_ADMIN_DISABLED", e.getMessage()));
    }

    private void assertPlatformRealm(Jwt jwt) {
        String realm = jwt == null ? null : KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
        if (realm == null || !realm.equals(platformProps.getRealm())) {
            throw new AccessDeniedException("Not a platform-realm token");
        }
    }

    private static String actor(Jwt jwt) {
        return jwt.getClaimAsString("preferred_username");
    }

    private static String realm(Jwt jwt) {
        return KeycloakRealms.realmOf(jwt.getClaimAsString("iss"));
    }
}
```

- [ ] **Step 4: Install + run the IT**

Run: `cd cia-backend && mvn -q install -DskipTests -pl cia-api -am && mvn -q -pl cia-api verify -Dit.test=PlatformSuperAdminControllerIT -DfailIfNoTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/platform/PlatformSuperAdminController.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformSuperAdminControllerIT.java
git commit -m "feat(platform): PlatformSuperAdminController (list/invite/revoke + 503 on admin-disabled)"
```

---

## Task 9: `PlatformSuperAdminE2EIT` — real Keycloak invite → list → revoke

**Files:**
- Create (test): `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformSuperAdminE2EIT.java`

**Context:** End-to-end against a real Keycloak Testcontainer + the real `PlatformSuperAdminService` over the real provisioner (mirrors `PlatformOnboardingE2EIT`'s hand-wired, no-Spring style). Audit goes to a hand-created `public.platform_audit_log` (nullable `target_schema`, mirroring post-V68).

- [ ] **Step 1: Write the E2E IT**

```java
package com.nubeero.cia.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.api.keycloak.KeycloakItSupport;
import com.nubeero.cia.api.platform.dto.InviteSuperAdminRequest;
import com.nubeero.cia.auth.PlatformRealmProperties;
import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * E2E super-admin lifecycle: a real {@link PlatformSuperAdminService} over a real
 * {@link KeycloakTenantProvisioner} against a Testcontainers Keycloak platform realm.
 * Mirrors {@code PlatformOnboardingE2EIT} (inline Postgres, hand-wired units, no Spring).
 */
class PlatformSuperAdminE2EIT extends KeycloakItSupport {

    private static final String REALM = "platform_sa_e2e";

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciasae2e").withUsername("ciasae2e").withPassword("ciasae2e");
    static final HikariDataSource DS;
    static {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        DS = new HikariDataSource(cfg);
    }

    private static Keycloak ADMIN;
    private static KeycloakTenantProvisioner provisioner;

    @BeforeAll
    static void connect() {
        ADMIN = adminClient();
        provisioner = newProvisioner(ADMIN);
        provisioner.provisionPlatformRealm(
                REALM, "cia-platform", List.of("http://localhost:5175/*"),
                new FirstAdminSpec("rootadmin", "root@platform.test", "Root", "Admin",
                        "Aa1!rootpass", UUID.randomUUID()));
    }

    private PlatformSuperAdminService service;

    @BeforeEach
    void setup() {
        JdbcTemplate jdbc = new JdbcTemplate(DS);
        jdbc.execute("CREATE TABLE IF NOT EXISTS public.platform_audit_log ("
            + " id UUID PRIMARY KEY DEFAULT gen_random_uuid(),"
            + " action VARCHAR(32) NOT NULL, target_schema VARCHAR(63),"
            + " actor_username VARCHAR(255) NOT NULL, actor_realm VARCHAR(63) NOT NULL,"
            + " detail JSONB, source_ip VARCHAR(64), at TIMESTAMPTZ NOT NULL DEFAULT now())");

        ObjectProvider<Keycloak> provider = new ObjectProvider<>() {
            @Override public Keycloak getObject() { return ADMIN; }
            @Override public Keycloak getObject(Object... a) { return ADMIN; }
            @Override public Keycloak getIfAvailable() { return ADMIN; }
            @Override public Keycloak getIfUnique() { return ADMIN; }
        };
        var props = new PlatformRealmProperties();
        props.setRealm(REALM);
        service = new PlatformSuperAdminService(provider, provisioner,
                new PlatformAuditService(jdbc), props);
    }

    @Test
    void invite_list_revoke_andGuards() {
        var resp = service.invite(new InviteSuperAdminRequest("sa_e2e", "sa@e2e.test"),
                "rootadmin", REALM, "1.1.1.1");
        assertThat(resp.temporaryPassword()).startsWith("Aa1!");

        assertThat(service.list()).extracting("username").contains("rootadmin", "sa_e2e");

        // last-admin guard can't fire (2 exist); self guard blocks rootadmin revoking itself.
        assertThatThrownBy(() -> service.revoke("rootadmin", "rootadmin", REALM, "1.1.1.1"))
                .isInstanceOf(SuperAdminExceptions.CannotRevokeSelf.class);

        service.revoke("sa_e2e", "rootadmin", REALM, "1.1.1.1");
        assertThat(service.list()).extracting("username").doesNotContain("sa_e2e");
    }
}
```

- [ ] **Step 2: Run the IT**

Run: `cd cia-backend && mvn -q -pl cia-api verify -Dit.test=PlatformSuperAdminE2EIT -DfailIfNoTests=false`
Expected: PASS.

> If `ObjectProvider` anonymous-class compilation complains about missing default methods in your Spring version, replace it with `org.springframework.beans.factory.ObjectProvider` from a `mock(...)` stubbed via `when(provider.getIfAvailable()).thenReturn(ADMIN)` (as in `PlatformSuperAdminServiceTest`).

- [ ] **Step 3: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/platform/PlatformSuperAdminE2EIT.java
git commit -m "test(platform): PlatformSuperAdminE2EIT — real-Keycloak invite/list/revoke + guards"
```

---

## Task 10: Full reactor verify (backend gate)

**Files:** none (verification only).

- [ ] **Step 1: Run the full reactor**

Run: `cd cia-backend && mvn -q verify`
Expected: BUILD SUCCESS, 0 failures / 0 errors (the new ITs add to the failsafe count; the 1 intentional benchmark skip remains).

- [ ] **Step 2: If anything failed, fix forward**

If a pre-existing IT pins `spring.flyway.target` below 68 and now fails because it asserts a tenant `platform_audit_log` copy exists, update that assertion (none is expected — V68's drop only affects the dead copy). If a flyway-target-pinned IT fails to reach V68, bump its target to 68 (grep: `grep -rn "flyway.target" cia-backend/cia-api/src/test`). Re-run until green.

- [ ] **Step 3: Commit any fixes**

```bash
git add -A && git commit -m "test(platform): reactor-green fixes after SP2 backend extensions"
```

---

# PHASE 2 — Frontend foundation (`apps/platform` + `@cia/api-client`)

## Task 11: Scaffold the `apps/platform` app (dark, port 5175)

**Files (all create):**
- `cia-frontend/apps/platform/package.json`
- `cia-frontend/apps/platform/vite.config.ts`
- `cia-frontend/apps/platform/vitest.config.ts`
- `cia-frontend/apps/platform/tailwind.config.ts`
- `cia-frontend/apps/platform/postcss.config.js`
- `cia-frontend/apps/platform/tsconfig.json`
- `cia-frontend/apps/platform/index.html`
- `cia-frontend/apps/platform/src/app/globals.css`
- `cia-frontend/apps/platform/src/test/setup.ts`

**Context:** Mirror `apps/back-office` (it has routing + auth + Vitest, which the platform app needs) but pick port 5175 and add `class="dark"` to `index.html` (the partner-app dark trick; dark tokens already live in `@cia/ui/tokens.css`).

- [ ] **Step 1: `package.json`**

```json
{
  "name": "@cia/platform",
  "private": true,
  "version": "0.0.1",
  "type": "module",
  "scripts": {
    "dev": "vite --port 5175",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "typecheck": "tsc --noEmit",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "@cia/api-client": "workspace:*",
    "@cia/auth": "workspace:*",
    "@cia/ui": "workspace:*",
    "@hugeicons/core-free-icons": "^4.1.1",
    "@hugeicons/react": "^1.1.6",
    "@tanstack/react-query": "^5.60.5",
    "@tanstack/react-table": "^8.21.3",
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.28.0",
    "zod": "^4.3.6"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.9.1",
    "@testing-library/react": "^16.3.2",
    "@testing-library/user-event": "^14.6.1",
    "@types/node": "^25.6.0",
    "@types/react": "^18.3.12",
    "@types/react-dom": "^18.3.1",
    "@vitejs/plugin-react": "^4.3.3",
    "autoprefixer": "^10.4.20",
    "jsdom": "^29.1.1",
    "postcss": "^8.4.49",
    "tailwindcss": "^3.4.15",
    "typescript": "^5.6.3",
    "vite": "^5.4.11",
    "vitest": "^2.1.9"
  }
}
```

- [ ] **Step 2: `vite.config.ts`** (port 5175, proxy `/api` → 8090, mirroring back-office)

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5175,
    proxy: {
      '/api': { target: 'http://localhost:8090', changeOrigin: true },
    },
  },
  build: { chunkSizeWarningLimit: 600 },
});
```

- [ ] **Step 3: `vitest.config.ts`** (copy of back-office's)

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    include: ['src/**/*.test.{ts,tsx}'],
    server: { deps: { inline: ['@cia/api-client', '@cia/ui', '@cia/auth'] } },
  },
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
});
```

- [ ] **Step 4: `tailwind.config.ts`, `postcss.config.js`, `tsconfig.json`**

`tailwind.config.ts`:

```ts
import type { Config } from 'tailwindcss';
import baseConfig from '../../packages/ui/tailwind.config';

export default {
  ...baseConfig,
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
    '../../packages/ui/src/**/*.{ts,tsx}',
  ],
} satisfies Config;
```

`postcss.config.js`:

```js
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
};
```

`tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "baseUrl": ".",
    "paths": { "@/*": ["./src/*"] }
  },
  "include": ["src"]
}
```

- [ ] **Step 5: `index.html`** (note `class="dark"` — the entire dark-theme trigger)

```html
<!doctype html>
<html lang="en" class="dark">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta name="description" content="NubSure Platform Admin" />
    <title>NubSure Platform</title>
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link
      rel="stylesheet"
      href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,200..800&family=Geist:wght@100..900&display=swap"
    />
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 6: `src/app/globals.css`** and `src/test/setup.ts`

`src/app/globals.css` (copy of back-office's):

```css
@import '@cia/ui/tokens.css';
@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  * { box-sizing: border-box; }
  html { height: 100%; scroll-behavior: smooth; }
  body { height: 100%; overflow: hidden; }
  #root { height: 100%; }
}

@layer utilities {
  .scrollbar-thin {
    scrollbar-width: thin;
    scrollbar-color: var(--border) transparent;
  }
  .page-enter { animation: fade-in 0.18s cubic-bezier(0.16, 1, 0.3, 1); }
}
```

`src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest';
```

- [ ] **Step 7: Install + verify the workspace resolves**

Run: `cd cia-frontend && pnpm install`
Expected: pnpm links `@cia/platform` into the workspace (it appears in `pnpm -r ls --depth -1`). No build yet (no `src/main.tsx`) — that's Task 12.

- [ ] **Step 8: Commit**

```bash
git add cia-frontend/apps/platform cia-frontend/pnpm-lock.yaml
git commit -m "feat(platform-ui): scaffold apps/platform (dark, port 5175, Vitest)"
```

---

## Task 12: Auth bootstrap + `SuperAdminGate` + router shell

**Files (all create):**
- `cia-frontend/apps/platform/src/main.tsx`
- `cia-frontend/apps/platform/src/App.tsx`
- `cia-frontend/apps/platform/src/app/router.tsx`
- `cia-frontend/apps/platform/src/app/SuperAdminGate.tsx`
- `cia-frontend/apps/platform/src/app/NotAuthorized.tsx`
- `cia-frontend/apps/platform/src/modules/_placeholder/PlaceholderPage.tsx` (temporary; deleted as screens land)
- Test: `cia-frontend/apps/platform/src/app/SuperAdminGate.test.tsx`

**Context:** Mirror back-office `main.tsx` but point at the platform realm/client (reusing `VITE_KEYCLOAK_*` names so `@cia/auth` works unchanged) and wrap the routed app in `SuperAdminGate`. In dev/demo the shared `DevAuthProvider`'s `hasRole: () => true` satisfies the gate.

- [ ] **Step 1: `SuperAdminGate` + test (failing)**

`src/app/SuperAdminGate.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import SuperAdminGate from './SuperAdminGate';

vi.mock('@cia/auth', () => ({
  useAuth: vi.fn(),
}));
import { useAuth } from '@cia/auth';

describe('SuperAdminGate', () => {
  it('renders children when the user has SUPER_ADMIN', () => {
    (useAuth as unknown as ReturnType<typeof vi.fn>).mockReturnValue({ hasRole: (r: string) => r === 'SUPER_ADMIN' });
    render(<SuperAdminGate><div>secret console</div></SuperAdminGate>);
    expect(screen.getByText('secret console')).toBeInTheDocument();
  });

  it('renders Not authorized when the user lacks SUPER_ADMIN', () => {
    (useAuth as unknown as ReturnType<typeof vi.fn>).mockReturnValue({ hasRole: () => false });
    render(<SuperAdminGate><div>secret console</div></SuperAdminGate>);
    expect(screen.queryByText('secret console')).not.toBeInTheDocument();
    expect(screen.getByText(/not authorized/i)).toBeInTheDocument();
  });
});
```

`src/app/SuperAdminGate.tsx`:

```tsx
import type { ReactNode } from 'react';
import { useAuth } from '@cia/auth';
import NotAuthorized from './NotAuthorized';

/**
 * Defense-in-depth UX gate: the backend's @PreAuthorize + assertPlatformRealm is the real
 * boundary; this just avoids rendering the console to a non-super-admin token.
 */
export default function SuperAdminGate({ children }: { children: ReactNode }) {
  const { hasRole } = useAuth();
  if (!hasRole('SUPER_ADMIN')) return <NotAuthorized />;
  return <>{children}</>;
}
```

`src/app/NotAuthorized.tsx`:

```tsx
export default function NotAuthorized() {
  return (
    <div className="flex h-full min-h-screen flex-col items-center justify-center gap-3 bg-background p-8 text-center">
      <h1 className="font-display text-2xl font-semibold text-foreground">Not authorized</h1>
      <p className="max-w-md text-sm text-muted-foreground">
        The NubSure Platform console requires a <span className="font-medium text-foreground">SUPER_ADMIN</span> account
        on the platform realm. Your token does not carry that role.
      </p>
    </div>
  );
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd cia-frontend && pnpm --filter @cia/platform test`
Expected: FAIL — module resolution error (`SuperAdminGate`/`NotAuthorized` don't exist yet) → after creating them in Step 1, FAIL would only occur if logic is wrong. (TDD note: create the test first, then the components in the same step — running here confirms green.)

- [ ] **Step 3: `main.tsx`, `App.tsx`, `router.tsx`, placeholder page**

`src/main.tsx`:

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, DevAuthProvider, keycloak, configureKeycloak } from '@cia/auth';
import { initApiClient, setTokenGetter } from '@cia/api-client';
import App from './App';
import './app/globals.css';

initApiClient(import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080');

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1 } },
});

// The platform app authenticates against the `platform` Keycloak realm with the
// `cia-platform` client. We REUSE the VITE_KEYCLOAK_* env names (scoped per-deployment)
// because @cia/auth's initKeycloak keys onLoad:'login-required' off VITE_KEYCLOAK_URL.
const keycloakConfigured = !!import.meta.env.VITE_KEYCLOAK_URL;
const demoMode = import.meta.env.VITE_DEMO_MODE === 'true';

if (keycloakConfigured) {
  configureKeycloak({
    url:      import.meta.env.VITE_KEYCLOAK_URL,
    realm:    import.meta.env.VITE_KEYCLOAK_REALM     ?? 'platform',
    clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'cia-platform',
  });
  setTokenGetter(() => keycloak.token);
} else if (!import.meta.env.DEV && !demoMode) {
  throw new Error(
    'VITE_KEYCLOAK_URL is required for production builds of the platform console. ' +
    'Set the platform realm Keycloak vars, or VITE_DEMO_MODE=true for a mocked demo build.'
  );
}

const AuthWrapper = keycloakConfigured ? AuthProvider : DevAuthProvider;

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthWrapper>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </AuthWrapper>
  </React.StrictMode>
);
```

`src/App.tsx`:

```tsx
import { RouterProvider } from 'react-router-dom';
import { router } from './app/router';

export default function App() {
  return <RouterProvider router={router} />;
}
```

`src/app/router.tsx` (placeholder routes; real pages land in Phase 3):

```tsx
import { lazy, Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import AppShell from './layout/AppShell';
import SuperAdminGate from './SuperAdminGate';

const PlaceholderPage = lazy(() => import('../modules/_placeholder/PlaceholderPage'));

function Deferred({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<div className="p-6 text-sm text-muted-foreground">Loading…</div>}>{children}</Suspense>;
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <SuperAdminGate><AppShell /></SuperAdminGate>,
    children: [
      { index: true,            element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard',      element: <Deferred><PlaceholderPage title="Dashboard" /></Deferred> },
      { path: 'tenants',        element: <Deferred><PlaceholderPage title="Tenants" /></Deferred> },
      { path: 'tenants/:schema',element: <Deferred><PlaceholderPage title="Tenant detail" /></Deferred> },
      { path: 'audit',          element: <Deferred><PlaceholderPage title="Audit log" /></Deferred> },
      { path: 'super-admins',   element: <Deferred><PlaceholderPage title="Super-admins" /></Deferred> },
    ],
  },
]);
```

`src/modules/_placeholder/PlaceholderPage.tsx`:

```tsx
export default function PlaceholderPage({ title }: { title: string }) {
  return (
    <div className="p-6">
      <h1 className="font-display text-xl font-semibold text-foreground">{title}</h1>
      <p className="mt-2 text-sm text-muted-foreground">Coming in Phase 3.</p>
    </div>
  );
}
```

> Note: `router.tsx` imports `./layout/AppShell`, which is created in Task 14. Implement Task 14 before building. To keep this task's test green independently, the `SuperAdminGate.test.tsx` does not import the router.

- [ ] **Step 4: Run the gate test (green)**

Run: `cd cia-frontend && pnpm --filter @cia/platform test`
Expected: PASS (2 tests). The build is not run here (AppShell lands in Task 14).

- [ ] **Step 5: Commit**

```bash
git add cia-frontend/apps/platform/src
git commit -m "feat(platform-ui): auth bootstrap + SuperAdminGate + router shell"
```

---

## Task 13: `@cia/api-client/platform` — zod schemas + React Query hooks

**Files:**
- Create: `cia-frontend/packages/api-client/src/modules/platform.ts`
- Modify: `cia-frontend/packages/api-client/src/modules/index.ts` (add `export * from './platform';`)
- Create (test): `cia-frontend/packages/api-client/src/modules/platform.test.ts`

**Context:** Single source of zod truth for the platform API (house pattern from `finance-closures.ts`: schemas + inferred types). Reads via `validatedGet`/`validatedList`; mutations via `validatedPost` / raw `apiClient`. Hooks live here too (back-office consumes `@cia/api-client` hooks directly). Field names mirror the Java DTOs exactly (verified against Tasks 4 + 7) so the DTO-drift guard stays quiet.

- [ ] **Step 1: Confirm the barrel includes `./modules`**

`packages/api-client/src/index.ts` already does `export * from './modules';`. Verify `packages/api-client/src/modules/index.ts` exists and add the platform re-export:

```ts
export * from './platform';
```

(If `modules/index.ts` doesn't exist, create it with the existing module re-exports plus the line above — `grep -rn "from './" cia-frontend/packages/api-client/src/modules/index.ts`.)

- [ ] **Step 2: Write the schema test (failing)**

`packages/api-client/src/modules/platform.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import {
  TenantSummarySchema,
  PlatformAuditEntrySchema,
  TenantDetailSchema,
  OnboardTenantResponseSchema,
  TenantStatsSchema,
  SuperAdminSummarySchema,
  InviteSuperAdminResponseSchema,
} from './platform';

describe('platform schemas', () => {
  it('parses a tenant summary', () => {
    const t = TenantSummarySchema.parse({
      schema: 'tenant_acme', displayName: 'Acme', subdomain: 'acme',
      active: true, createdAt: '2026-06-10T00:00:00Z',
    });
    expect(t.schema).toBe('tenant_acme');
  });

  it('accepts a NULL target_schema audit row (super-admin action)', () => {
    const e = PlatformAuditEntrySchema.parse({
      id: 'x', action: 'INVITE_SUPER_ADMIN', targetSchema: null,
      actorUsername: 'root', actorRealm: 'platform', detail: '{"username":"x"}',
      sourceIp: '1.1.1.1', at: '2026-06-10T00:00:00Z',
    });
    expect(e.targetSchema).toBeNull();
  });

  it('parses detail, onboard response, stats, super-admin, invite response', () => {
    const tenant = { schema: 't', displayName: 'd', subdomain: 's', active: true, createdAt: '2026-06-10T00:00:00Z' };
    expect(TenantDetailSchema.parse({ tenant, recentAudit: [] }).recentAudit).toEqual([]);
    expect(OnboardTenantResponseSchema.parse({ tenant, firstAdmin: { username: 'a', email: 'a@x.test', temporaryPassword: 'Aa1!x' } }).firstAdmin.temporaryPassword).toBe('Aa1!x');
    expect(TenantStatsSchema.parse({ total: 12, active: 10, suspended: 2 }).suspended).toBe(2);
    expect(SuperAdminSummarySchema.parse({ username: 'r', email: 'r@x.test', enabled: true }).enabled).toBe(true);
    expect(InviteSuperAdminResponseSchema.parse({ username: 's', email: 's@x.test', temporaryPassword: 'Aa1!y' }).username).toBe('s');
  });
});
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd cia-frontend && pnpm --filter @cia/api-client exec vitest run src/modules/platform.test.ts` (if `@cia/api-client` has no vitest, run from the platform app: `pnpm --filter @cia/platform exec vitest run ../../packages/api-client/src/modules/platform.test.ts` — or simply rely on the platform app's test run picking it up after Step 4). Expected: FAIL — `platform.ts` doesn't exist.

- [ ] **Step 4: Create `platform.ts`**

```ts
// ── Platform Admin (SP2) ──────────────────────────────────────────────────
//
// Wire shapes + React Query hooks for the cross-tenant platform-admin API at
// /api/v1/platform/**. Field names mirror the Java DTOs in cia-api/.../platform/
// (TenantSummary, TenantDetailResponse, PlatformAuditEntry, OnboardTenant*,
// TenantStats, SuperAdminSummary, InviteSuperAdmin*). Schemas are the source of
// truth; fetch with validatedGet/validatedList so backend drift fails loudly.

import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../client';
import { validatedGet, validatedList, validatedPost } from '../validation';

// ── Schemas ───────────────────────────────────────────────────────────────

export const TenantSummarySchema = z.object({
  schema:      z.string(),
  displayName: z.string(),
  subdomain:   z.string(),
  active:      z.boolean(),
  createdAt:   z.string(),
});

export const PlatformAuditEntrySchema = z.object({
  id:            z.string(),
  action:        z.string(),
  targetSchema:  z.string().nullable(),
  actorUsername: z.string(),
  actorRealm:    z.string(),
  detail:        z.string().nullable(),
  sourceIp:      z.string().nullable(),
  at:            z.string(),
});

export const TenantDetailSchema = z.object({
  tenant:      TenantSummarySchema,
  recentAudit: z.array(PlatformAuditEntrySchema),
});

export const FirstAdminSchema = z.object({
  username:          z.string(),
  email:             z.string(),
  temporaryPassword: z.string(),
});

export const OnboardTenantResponseSchema = z.object({
  tenant:     TenantSummarySchema,
  firstAdmin: FirstAdminSchema,
});

export const TenantStatsSchema = z.object({
  total:     z.number(),
  active:    z.number(),
  suspended: z.number(),
});

export const SuperAdminSummarySchema = z.object({
  username: z.string(),
  email:    z.string(),
  enabled:  z.boolean(),
});

export const InviteSuperAdminResponseSchema = z.object({
  username:          z.string(),
  email:             z.string(),
  temporaryPassword: z.string(),
});

export type TenantSummary           = z.infer<typeof TenantSummarySchema>;
export type PlatformAuditEntry      = z.infer<typeof PlatformAuditEntrySchema>;
export type TenantDetail            = z.infer<typeof TenantDetailSchema>;
export type OnboardTenantResponse   = z.infer<typeof OnboardTenantResponseSchema>;
export type TenantStats             = z.infer<typeof TenantStatsSchema>;
export type SuperAdminSummary       = z.infer<typeof SuperAdminSummarySchema>;
export type InviteSuperAdminResponse= z.infer<typeof InviteSuperAdminResponseSchema>;

export interface OnboardTenantRequest {
  schema: string;
  realm?: string;
  displayName: string;
  subdomain: string;
  adminUsername: string;
  adminEmail: string;
}
export interface InviteSuperAdminRequest { username: string; email: string; }

// ── Error helper ──────────────────────────────────────────────────────────

/** Pull the structured backend errorCode (e.g. CANNOT_REVOKE_SELF) off an axios error. */
export function platformErrorCode(err: unknown): string | undefined {
  const e = err as { response?: { data?: { errors?: { code?: string }[] } } };
  return e?.response?.data?.errors?.[0]?.code;
}

// ── Query hooks ───────────────────────────────────────────────────────────

const PLATFORM = '/api/v1/platform';

export function useTenants(page: number, size = 50) {
  return useQuery({
    queryKey: ['platform', 'tenants', page, size],
    queryFn: () => validatedList(`${PLATFORM}/tenants`, TenantSummarySchema, { params: { page, size } }),
    staleTime: 30_000,
  });
}

export function useTenantDetail(schema: string | undefined) {
  return useQuery({
    queryKey: ['platform', 'tenant', schema],
    queryFn: () => validatedGet(`${PLATFORM}/tenants/${schema}`, TenantDetailSchema),
    enabled: !!schema,
  });
}

export function usePlatformStats() {
  return useQuery({
    queryKey: ['platform', 'stats'],
    queryFn: () => validatedGet(`${PLATFORM}/stats`, TenantStatsSchema),
    staleTime: 30_000,
  });
}

export function usePlatformAudit(page: number, size = 50, targetSchema?: string) {
  return useQuery({
    queryKey: ['platform', 'audit', page, size, targetSchema ?? null],
    queryFn: () => validatedList(`${PLATFORM}/audit`, PlatformAuditEntrySchema, {
      params: { page, size, ...(targetSchema ? { targetSchema } : {}) },
    }),
    staleTime: 15_000,
  });
}

export function useSuperAdmins() {
  return useQuery({
    queryKey: ['platform', 'super-admins'],
    queryFn: () => validatedGet(`${PLATFORM}/super-admins`, z.array(SuperAdminSummarySchema)),
  });
}

// ── Mutation hooks ────────────────────────────────────────────────────────

export function useOnboardTenant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: OnboardTenantRequest) =>
      validatedPost(`${PLATFORM}/tenants`, body, OnboardTenantResponseSchema),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['platform', 'tenants'] });
      qc.invalidateQueries({ queryKey: ['platform', 'stats'] });
    },
  });
}

export function useSuspendTenant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (schema: string) => apiClient.post(`${PLATFORM}/tenants/${schema}/suspend`),
    onSuccess: (_d, schema) => {
      qc.invalidateQueries({ queryKey: ['platform', 'tenants'] });
      qc.invalidateQueries({ queryKey: ['platform', 'tenant', schema] });
      qc.invalidateQueries({ queryKey: ['platform', 'stats'] });
    },
  });
}

export function useActivateTenant() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (schema: string) => apiClient.post(`${PLATFORM}/tenants/${schema}/activate`),
    onSuccess: (_d, schema) => {
      qc.invalidateQueries({ queryKey: ['platform', 'tenants'] });
      qc.invalidateQueries({ queryKey: ['platform', 'tenant', schema] });
      qc.invalidateQueries({ queryKey: ['platform', 'stats'] });
    },
  });
}

export function useInviteSuperAdmin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: InviteSuperAdminRequest) =>
      validatedPost(`${PLATFORM}/super-admins`, body, InviteSuperAdminResponseSchema),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['platform', 'super-admins'] }),
  });
}

export function useRevokeSuperAdmin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (username: string) => apiClient.delete(`${PLATFORM}/super-admins/${username}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['platform', 'super-admins'] }),
  });
}
```

- [ ] **Step 5: Run the schema test + typecheck**

Run: `cd cia-frontend && pnpm --filter @cia/platform exec vitest run ../../packages/api-client/src/modules/platform.test.ts && pnpm --filter @cia/api-client typecheck`
Expected: PASS + clean typecheck. (The platform app's vitest `include` is `src/**`, so place the test where it's picked up, or run it explicitly as shown.)

- [ ] **Step 6: Commit**

```bash
git add cia-frontend/packages/api-client/src/modules/platform.ts \
        cia-frontend/packages/api-client/src/modules/index.ts \
        cia-frontend/packages/api-client/src/modules/platform.test.ts
git commit -m "feat(platform-ui): @cia/api-client platform module (schemas + hooks)"
```

---

## Task 14: `AppShell` (dark sidebar + topbar)

**Files (all create):**
- `cia-frontend/apps/platform/src/app/layout/AppShell.tsx`
- `cia-frontend/apps/platform/src/app/layout/Sidebar.tsx`
- `cia-frontend/apps/platform/src/app/layout/Topbar.tsx`

**Context:** A minimal 4-item shell (Dashboard / Tenants / Audit / Super-admins). No collapse machinery, no search, no notifications — the platform console is small. Dark tokens apply automatically (`class="dark"` on `<html>`).

- [ ] **Step 1: `Sidebar.tsx`**

```tsx
import { NavLink } from 'react-router-dom';
import { HugeiconsIcon } from '@hugeicons/react';
import {
  DashboardSquare01Icon, Building06Icon, Audit01Icon, UserShield01Icon, Logout01Icon,
} from '@hugeicons/core-free-icons';
import { cn } from '@cia/ui';
import { useAuth } from '@cia/auth';
import type React from 'react';

type HugeIcon = React.ComponentProps<typeof HugeiconsIcon>['icon'];
interface NavItem { label: string; path: string; icon: HugeIcon; }

const NAV: NavItem[] = [
  { label: 'Dashboard',    path: '/dashboard',    icon: DashboardSquare01Icon },
  { label: 'Tenants',      path: '/tenants',      icon: Building06Icon },
  { label: 'Audit log',    path: '/audit',        icon: Audit01Icon },
  { label: 'Super-admins', path: '/super-admins', icon: UserShield01Icon },
];

export default function Sidebar() {
  const { user, logout } = useAuth();
  return (
    <aside className="flex h-full w-full flex-col bg-card" style={{ boxShadow: '1px 0 0 var(--border)' }}>
      <div className="flex h-[var(--topbar-height,56px)] shrink-0 items-center gap-2.5 px-4"
           style={{ boxShadow: '0 1px 0 var(--border)' }}>
        <span className="font-display text-[17px] font-semibold tracking-tight text-foreground">◈ NubSure Platform</span>
      </div>
      <nav className="flex-1 overflow-y-auto px-3 py-4">
        <ul className="space-y-0.5">
          {NAV.map((item) => (
            <li key={item.path}>
              <NavLink
                to={item.path}
                className={({ isActive }) => cn(
                  'flex items-center gap-2.5 rounded-md px-2.5 py-2 text-[15px] font-medium transition-colors',
                  isActive ? 'bg-secondary text-primary' : 'text-muted-foreground hover:bg-secondary hover:text-foreground',
                )}
              >
                {({ isActive }) => (
                  <>
                    <HugeiconsIcon icon={item.icon} size={18} color="currentColor" strokeWidth={isActive ? 2 : 1.75} />
                    <span>{item.label}</span>
                  </>
                )}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <div className="shrink-0 px-3 py-3" style={{ boxShadow: '0 -1px 0 var(--border)' }}>
        <div className="flex items-center gap-3 px-2.5 py-2">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
            {user?.name?.charAt(0).toUpperCase() ?? 'S'}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-foreground">{user?.name ?? 'Super-admin'}</p>
            <p className="truncate text-xs text-muted-foreground">{user?.email ?? ''}</p>
          </div>
          <button onClick={logout} className="shrink-0 text-muted-foreground hover:text-foreground transition-colors" aria-label="Sign out">
            <HugeiconsIcon icon={Logout01Icon} size={16} color="currentColor" strokeWidth={1.75} />
          </button>
        </div>
      </div>
    </aside>
  );
}
```

> If any hugeicons name (`Building06Icon`, `UserShield01Icon`) isn't exported by `@hugeicons/core-free-icons@4.1.1`, substitute a present one (`grep -o 'Building[A-Za-z0-9]*Icon' node_modules/@hugeicons/core-free-icons/dist/esm/index.js | head`); `Building06Icon`/`UserGroupIcon`/`Audit01Icon`/`DashboardSquare01Icon` are used elsewhere in back-office and are known-present.

- [ ] **Step 2: `Topbar.tsx`**

```tsx
import { useLocation } from 'react-router-dom';

const LABELS: Record<string, string> = {
  dashboard: 'Dashboard', tenants: 'Tenants', audit: 'Audit log', 'super-admins': 'Super-admins',
};

export default function Topbar() {
  const seg = useLocation().pathname.split('/').filter(Boolean)[0] ?? 'dashboard';
  return (
    <header className="flex h-[var(--topbar-height,56px)] shrink-0 items-center gap-3 bg-card px-4"
            style={{ boxShadow: '0 1px 0 var(--border)' }}>
      <h1 className="font-display text-[15px] font-semibold tracking-tight text-foreground">{LABELS[seg] ?? seg}</h1>
    </header>
  );
}
```

- [ ] **Step 3: `AppShell.tsx`**

```tsx
import { Outlet } from 'react-router-dom';
import { Toaster } from '@cia/ui';
import Sidebar from './Sidebar';
import Topbar from './Topbar';

const isDemo = import.meta.env.VITE_DEMO_MODE === 'true';

export default function AppShell() {
  return (
    <div className="flex h-full flex-col overflow-hidden bg-background">
      {isDemo && (
        <div className="flex items-center justify-center gap-2 border-b border-amber-700/40 bg-amber-900/30 px-4 py-1.5 text-xs font-medium text-amber-200">
          <span className="rounded-sm bg-amber-700/40 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide">Demo</span>
          <span>Stakeholder preview — auth is mocked, data is illustrative. Not a live platform.</span>
        </div>
      )}
      <div className="flex flex-1 overflow-hidden">
        <aside style={{ width: 256, flexShrink: 0 }}>
          <Sidebar />
        </aside>
        <div className="flex flex-1 flex-col overflow-hidden">
          <Topbar />
          <main className="flex-1 overflow-y-auto scrollbar-thin">
            <div className="page-enter"><Outlet /></div>
          </main>
        </div>
      </div>
      <Toaster />
    </div>
  );
}
```

- [ ] **Step 4: Build the app**

Run: `cd cia-frontend && pnpm --filter @cia/platform build`
Expected: SUCCESS — the placeholder routes + shell compile and bundle. Then `pnpm --filter @cia/platform dev` and visit `http://localhost:5175` to eyeball the dark shell (dev uses `DevAuthProvider`; `hasRole`→true so the gate passes).

- [ ] **Step 5: Commit**

```bash
git add cia-frontend/apps/platform/src/app/layout
git commit -m "feat(platform-ui): dark AppShell (sidebar + topbar)"
```

---

# PHASE 3 — Frontend screens (`apps/platform/src/{components,modules}`)

## Task 15: Shared components — `CredentialReveal`, `ServerPaginationFooter`, `ConfirmActionDialog`

**Files (all create):**
- `cia-frontend/apps/platform/src/components/CredentialReveal.tsx`
- `cia-frontend/apps/platform/src/components/CredentialReveal.test.tsx`
- `cia-frontend/apps/platform/src/components/ServerPaginationFooter.tsx`
- `cia-frontend/apps/platform/src/components/ServerPaginationFooter.test.tsx`
- `cia-frontend/apps/platform/src/components/ConfirmActionDialog.tsx`

**Context:** Three reusable pieces. `CredentialReveal` is the one-time-password panel (Copy gates "Done", value never persisted) used by both Onboard and Invite. `ServerPaginationFooter` is the local server-pagination footer (the shared `DataTable` doesn't drive server pages). `ConfirmActionDialog` is a generic confirm (suspend/activate/revoke).

- [ ] **Step 1: `CredentialReveal` test (failing)**

`src/components/CredentialReveal.test.tsx`:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CredentialReveal from './CredentialReveal';

describe('CredentialReveal', () => {
  beforeEach(() => {
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('renders the secret and gates Done behind Copy', async () => {
    const onDone = vi.fn();
    render(
      <CredentialReveal
        title="Tenant onboarded"
        subtitle="Acme · tenant_acme"
        identityLabel="Admin"
        identityValue="admin · admin@acme.test"
        secret="Aa1!x9Kc2pQ7mZ"
        onDone={onDone}
      />,
    );
    expect(screen.getByText('Aa1!x9Kc2pQ7mZ')).toBeInTheDocument();

    const done = screen.getByRole('button', { name: /done/i });
    expect(done).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: /copy/i }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('Aa1!x9Kc2pQ7mZ');
    expect(done).toBeEnabled();

    await userEvent.click(done);
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('never writes the secret to localStorage', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    render(
      <CredentialReveal title="t" subtitle="s" identityLabel="l" identityValue="v"
        secret="Aa1!secret" onDone={() => {}} />,
    );
    expect(setItem).not.toHaveBeenCalledWith(expect.anything(), expect.stringContaining('Aa1!secret'));
  });
});
```

- [ ] **Step 2: `CredentialReveal.tsx`**

```tsx
import { useState } from 'react';
import { Button } from '@cia/ui';

interface CredentialRevealProps {
  title: string;
  subtitle: string;
  identityLabel: string;
  identityValue: string;
  /** The one-time secret. Held only in this component's render scope — never persisted. */
  secret: string;
  onDone: () => void;
}

export default function CredentialReveal({
  title, subtitle, identityLabel, identityValue, secret, onDone,
}: CredentialRevealProps) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(secret);
    } catch {
      /* clipboard denied — still unlock Done; the value is visible to copy manually */
    }
    setCopied(true);
  }

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-semibold text-primary">✓ {title}</p>
        <p className="text-xs text-muted-foreground">{subtitle}</p>
      </div>

      <div className="rounded-md border border-amber-700/50 bg-amber-900/20 p-3">
        <p className="text-sm font-semibold text-amber-300">⚠ Copy the temporary password now</p>
        <p className="mt-0.5 text-xs text-amber-200/80">
          Shown once. It is never stored or retrievable. The account resets it on first login.
        </p>
      </div>

      <div>
        <p className="text-xs text-muted-foreground">{identityLabel}</p>
        <p className="mt-1 rounded-md border bg-secondary/40 px-3 py-2 text-sm text-foreground">{identityValue}</p>
      </div>

      <div>
        <p className="text-xs text-muted-foreground">Temporary password</p>
        <div className="mt-1 flex gap-2">
          <code className="flex-1 truncate rounded-md border border-primary/60 bg-secondary/40 px-3 py-2 font-mono text-sm tracking-wide text-foreground">
            {secret}
          </code>
          <Button type="button" onClick={copy} variant={copied ? 'outline' : 'default'}>
            {copied ? '✓ Copied' : '⧉ Copy'}
          </Button>
        </div>
      </div>

      <div className="flex justify-end pt-2">
        <Button type="button" onClick={onDone} disabled={!copied}>Done</Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 3: `ServerPaginationFooter` test + component**

`src/components/ServerPaginationFooter.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ServerPaginationFooter from './ServerPaginationFooter';

describe('ServerPaginationFooter', () => {
  it('disables Previous on the first page and Next on the last', () => {
    const onPage = vi.fn();
    render(<ServerPaginationFooter page={0} size={50} total={120} onPageChange={onPage} noun="tenants" />);
    expect(screen.getByText(/showing 1–50 of 120/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /next/i })).toBeEnabled();
  });

  it('disables Next on the last page', () => {
    render(<ServerPaginationFooter page={2} size={50} total={120} onPageChange={() => {}} noun="rows" />);
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled();
  });

  it('advances the page', async () => {
    const onPage = vi.fn();
    render(<ServerPaginationFooter page={0} size={50} total={120} onPageChange={onPage} noun="rows" />);
    await userEvent.click(screen.getByRole('button', { name: /next/i }));
    expect(onPage).toHaveBeenCalledWith(1);
  });
});
```

`src/components/ServerPaginationFooter.tsx`:

```tsx
import { Button } from '@cia/ui';

interface Props {
  page: number;          // zero-based
  size: number;
  total: number;
  onPageChange: (page: number) => void;
  noun: string;          // e.g. "tenants"
}

export default function ServerPaginationFooter({ page, size, total, onPageChange, noun }: Props) {
  const first = total === 0 ? 0 : page * size + 1;
  const last = Math.min(total, (page + 1) * size);
  const isLast = (page + 1) * size >= total;
  return (
    <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
      <span>Showing {first}–{last} of {total} {noun}</span>
      <div className="flex gap-2">
        <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPageChange(Math.max(0, page - 1))}>
          Previous
        </Button>
        <Button variant="outline" size="sm" disabled={isLast} onClick={() => onPageChange(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: `ConfirmActionDialog.tsx`** (generic; reused by suspend/activate/revoke)

```tsx
import { Button, Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@cia/ui';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  destructive?: boolean;
  busy?: boolean;
  onConfirm: () => void;
}

export default function ConfirmActionDialog({
  open, onOpenChange, title, description, confirmLabel, destructive, busy, onConfirm,
}: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
          <Button variant={destructive ? 'destructive' : 'default'} onClick={onConfirm} disabled={busy}>
            {busy ? 'Working…' : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
```

- [ ] **Step 5: Run the tests**

Run: `cd cia-frontend && pnpm --filter @cia/platform test`
Expected: PASS — `CredentialReveal` (2) + `ServerPaginationFooter` (3) + `SuperAdminGate` (2) = 7 tests green.

- [ ] **Step 6: Commit**

```bash
git add cia-frontend/apps/platform/src/components
git commit -m "feat(platform-ui): CredentialReveal + ServerPaginationFooter + ConfirmActionDialog"
```

---

## Task 16: Tenants list + Onboard sheet

**Files (all create):**
- `cia-frontend/apps/platform/src/modules/tenants/TenantsListPage.tsx`
- `cia-frontend/apps/platform/src/modules/tenants/OnboardTenantSheet.tsx`
- `cia-frontend/apps/platform/src/modules/tenants/StatusBadge.tsx`
- `cia-frontend/apps/platform/src/modules/tenants/OnboardTenantSheet.test.tsx`
- Modify: `cia-frontend/apps/platform/src/app/router.tsx` (wire the real page)

**Context:** Paginated tenants table (StatCards from `usePlatformStats`, rows from `useTenants(page)`, `ServerPaginationFooter`), row actions View / Suspend / Activate (status-conditional, `ConfirmActionDialog`), and an Onboard sheet that swaps to `CredentialReveal` on success.

- [ ] **Step 1: `StatusBadge.tsx`**

```tsx
import { Badge } from '@cia/ui';

export default function StatusBadge({ active }: { active: boolean }) {
  return active
    ? <Badge className="bg-primary/15 text-primary">● Active</Badge>
    : <Badge className="bg-amber-500/15 text-amber-400">● Suspended</Badge>;
}
```

- [ ] **Step 2: `OnboardTenantSheet` test (failing)**

`src/modules/tenants/OnboardTenantSheet.test.tsx` — asserts the form→reveal swap and that the temp password is shown once:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import OnboardTenantSheet from './OnboardTenantSheet';

const onboardMock = vi.fn();
vi.mock('@cia/api-client', () => ({
  useOnboardTenant: () => ({ mutateAsync: onboardMock, isPending: false }),
}));

function wrap(ui: React.ReactNode) {
  const qc = new QueryClient();
  return render(<QueryClientProvider client={qc}>{ui}</QueryClientProvider>);
}

describe('OnboardTenantSheet', () => {
  beforeEach(() => {
    onboardMock.mockReset();
    Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
  });

  it('submits the form then reveals the one-time temp password', async () => {
    onboardMock.mockResolvedValue({
      tenant: { schema: 'tenant_acme', displayName: 'Acme', subdomain: 'acme', active: true, createdAt: '2026-06-10T00:00:00Z' },
      firstAdmin: { username: 'admin', email: 'a@acme.test', temporaryPassword: 'Aa1!revealed' },
    });

    wrap(<OnboardTenantSheet open onOpenChange={() => {}} />);

    await userEvent.type(screen.getByLabelText(/^schema/i), 'tenant_acme');
    await userEvent.type(screen.getByLabelText(/display name/i), 'Acme');
    await userEvent.type(screen.getByLabelText(/subdomain/i), 'acme');
    await userEvent.type(screen.getByLabelText(/admin username/i), 'admin');
    await userEvent.type(screen.getByLabelText(/admin email/i), 'a@acme.test');
    await userEvent.click(screen.getByRole('button', { name: /onboard/i }));

    await waitFor(() => expect(screen.getByText('Aa1!revealed')).toBeInTheDocument());
    expect(onboardMock).toHaveBeenCalledWith(expect.objectContaining({ schema: 'tenant_acme', adminEmail: 'a@acme.test' }));
  });
});
```

- [ ] **Step 3: `OnboardTenantSheet.tsx`**

```tsx
import { useState } from 'react';
import {
  Button, Input, Label, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, toast,
} from '@cia/ui';
import { useOnboardTenant, platformErrorCode, type OnboardTenantResponse } from '@cia/api-client';
import CredentialReveal from '../../components/CredentialReveal';

interface Props { open: boolean; onOpenChange: (open: boolean) => void; }

const ERR: Record<string, string> = {
  TENANT_ALREADY_EXISTS: 'A tenant with that schema or subdomain already exists.',
  REALM_SCHEMA_MISMATCH: 'Realm must equal schema — leave realm blank to default it.',
  VALIDATION_ERROR: 'Check the field formats (schema/subdomain are lowercase identifiers).',
};

export default function OnboardTenantSheet({ open, onOpenChange }: Props) {
  const onboard = useOnboardTenant();
  const [result, setResult] = useState<OnboardTenantResponse | null>(null);
  const [form, setForm] = useState({ schema: '', displayName: '', subdomain: '', adminUsername: '', adminEmail: '' });

  function set<K extends keyof typeof form>(k: K, v: string) { setForm((f) => ({ ...f, [k]: v })); }

  function close() {
    setResult(null);
    setForm({ schema: '', displayName: '', subdomain: '', adminUsername: '', adminEmail: '' });
    onOpenChange(false);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    try {
      const resp = await onboard.mutateAsync(form);
      setResult(resp);
    } catch (err) {
      const code = platformErrorCode(err);
      toast({ variant: 'destructive', title: 'Onboard failed', description: (code && ERR[code]) || 'Unexpected error.' });
    }
  }

  return (
    <Sheet open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())}>
      <SheetContent className="w-full sm:max-w-md">
        {!result ? (
          <>
            <SheetHeader>
              <SheetTitle>Onboard tenant</SheetTitle>
              <SheetDescription>Provisions schema + Keycloak realm + first admin.</SheetDescription>
            </SheetHeader>
            <form onSubmit={submit} className="mt-4 space-y-3">
              <div className="space-y-1">
                <Label htmlFor="schema">Schema <span className="text-muted-foreground">(realm = schema)</span></Label>
                <Input id="schema" value={form.schema} onChange={(e) => set('schema', e.target.value)} placeholder="tenant_acme" required />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <Label htmlFor="displayName">Display name</Label>
                  <Input id="displayName" value={form.displayName} onChange={(e) => set('displayName', e.target.value)} placeholder="Acme Insurance" required />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="subdomain">Subdomain</Label>
                  <Input id="subdomain" value={form.subdomain} onChange={(e) => set('subdomain', e.target.value)} placeholder="acme" required />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div className="space-y-1">
                  <Label htmlFor="adminUsername">Admin username</Label>
                  <Input id="adminUsername" value={form.adminUsername} onChange={(e) => set('adminUsername', e.target.value)} placeholder="admin" required />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="adminEmail">Admin email</Label>
                  <Input id="adminEmail" type="email" value={form.adminEmail} onChange={(e) => set('adminEmail', e.target.value)} placeholder="admin@acme.test" required />
                </div>
              </div>
              <div className="flex justify-end pt-3">
                <Button type="submit" disabled={onboard.isPending}>{onboard.isPending ? 'Onboarding…' : 'Onboard →'}</Button>
              </div>
            </form>
          </>
        ) : (
          <>
            <SheetHeader>
              <SheetTitle>Tenant onboarded</SheetTitle>
              <SheetDescription>{result.tenant.displayName} · {result.tenant.schema} · {result.tenant.subdomain}</SheetDescription>
            </SheetHeader>
            <div className="mt-4">
              <CredentialReveal
                title="Tenant onboarded"
                subtitle={`${result.tenant.displayName} · ${result.tenant.schema}`}
                identityLabel="First admin"
                identityValue={`${result.firstAdmin.username} · ${result.firstAdmin.email}`}
                secret={result.firstAdmin.temporaryPassword}
                onDone={close}
              />
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 4: `TenantsListPage.tsx`**

```tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { ColumnDef } from '@tanstack/react-table';
import {
  Button, DataTable, DataTableColumnHeader, DataTableRowActions, PageHeader, PageSection,
  StatCard, Skeleton, toast, type RowAction,
} from '@cia/ui';
import {
  useTenants, usePlatformStats, useSuspendTenant, useActivateTenant,
  platformErrorCode, type TenantSummary,
} from '@cia/api-client';
import ServerPaginationFooter from '../../components/ServerPaginationFooter';
import ConfirmActionDialog from '../../components/ConfirmActionDialog';
import StatusBadge from './StatusBadge';
import OnboardTenantSheet from './OnboardTenantSheet';

export default function TenantsListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const size = 50;
  const tenantsQuery = useTenants(page, size);
  const statsQuery = usePlatformStats();
  const suspend = useSuspendTenant();
  const activate = useActivateTenant();

  const [onboardOpen, setOnboardOpen] = useState(false);
  const [pending, setPending] = useState<{ tenant: TenantSummary; action: 'suspend' | 'activate' } | null>(null);

  const rows = tenantsQuery.data?.data ?? [];
  const meta = tenantsQuery.data?.meta;
  const stats = statsQuery.data;

  async function runAction() {
    if (!pending) return;
    const { tenant, action } = pending;
    try {
      if (action === 'suspend') await suspend.mutateAsync(tenant.schema);
      else await activate.mutateAsync(tenant.schema);
      toast({ title: action === 'suspend' ? 'Tenant suspended' : 'Tenant activated', description: tenant.displayName });
      setPending(null);
    } catch (err) {
      toast({ variant: 'destructive', title: 'Action failed', description: platformErrorCode(err) ?? 'Unexpected error.' });
    }
  }

  const columns: ColumnDef<TenantSummary>[] = [
    { accessorKey: 'schema', header: ({ column }) => <DataTableColumnHeader column={column} title="Schema" />,
      cell: ({ row }) => <span className="font-mono text-xs">{row.original.schema}</span> },
    { accessorKey: 'displayName', header: ({ column }) => <DataTableColumnHeader column={column} title="Display name" /> },
    { accessorKey: 'subdomain', header: 'Subdomain' },
    { accessorKey: 'active', header: 'Status', cell: ({ row }) => <StatusBadge active={row.original.active} /> },
    { accessorKey: 'createdAt', header: 'Created',
      cell: ({ row }) => new Date(row.original.createdAt).toLocaleDateString() },
    {
      id: 'actions',
      cell: ({ row }) => {
        const t = row.original;
        const actions: RowAction[] = [
          { label: 'View detail', onClick: () => navigate(`/tenants/${t.schema}`) },
          t.active
            ? { label: 'Suspend', onClick: () => setPending({ tenant: t, action: 'suspend' }), destructive: true }
            : { label: 'Activate', onClick: () => setPending({ tenant: t, action: 'activate' }) },
        ];
        return <DataTableRowActions actions={actions} />;
      },
    },
  ];

  return (
    <div className="p-6">
      <PageHeader title="Tenants" description="Cross-tenant lifecycle — onboard, suspend, activate.">
        <Button onClick={() => setOnboardOpen(true)}>+ Onboard tenant</Button>
      </PageHeader>

      <div className="mt-4 grid grid-cols-3 gap-3">
        <StatCard label="Total" value={stats ? String(stats.total) : '—'} />
        <StatCard label="Active" value={stats ? String(stats.active) : '—'} />
        <StatCard label="Suspended" value={stats ? String(stats.suspended) : '—'} />
      </div>

      <PageSection className="mt-4">
        {tenantsQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <>
            <DataTable columns={columns} data={rows} />
            {meta && <ServerPaginationFooter page={page} size={size} total={meta.total} onPageChange={setPage} noun="tenants" />}
          </>
        )}
      </PageSection>

      <OnboardTenantSheet open={onboardOpen} onOpenChange={setOnboardOpen} />
      <ConfirmActionDialog
        open={!!pending}
        onOpenChange={(o) => !o && setPending(null)}
        title={pending?.action === 'suspend' ? 'Suspend tenant?' : 'Activate tenant?'}
        description={pending?.action === 'suspend'
          ? `Suspend ${pending?.tenant.displayName}? Its users are locked out at the gate immediately. Regulated data is retained — this is reversible.`
          : `Re-activate ${pending?.tenant.displayName}? Its users can sign in again.`}
        confirmLabel={pending?.action === 'suspend' ? 'Suspend' : 'Activate'}
        destructive={pending?.action === 'suspend'}
        busy={suspend.isPending || activate.isPending}
        onConfirm={runAction}
      />
    </div>
  );
}
```

> Note: this uses `DataTableRowActions` with a `RowAction[]` (`label`, `onClick`, optional `destructive`) — verify that shape against `packages/ui/.../data-table-row-actions.tsx`. If its prop is named differently (e.g. takes a `row` + `actions` render), adapt the call; the type `RowAction` is exported from `@cia/ui`. `StatCard` props: confirm whether it's `label`/`value` or `title`/`value` (`grep -n "export function StatCard" -A8 packages/ui/src/components/layout/stat-card.tsx`) and adjust.

- [ ] **Step 5: Wire the route**

In `src/app/router.tsx`, replace the tenants placeholder line:

```tsx
const TenantsListPage = lazy(() => import('../modules/tenants/TenantsListPage'));
// ...
{ path: 'tenants', element: <Deferred><TenantsListPage /></Deferred> },
```

- [ ] **Step 6: Run tests + build**

Run: `cd cia-frontend && pnpm --filter @cia/platform test && pnpm --filter @cia/platform build`
Expected: PASS (Onboard sheet test green) + clean build.

- [ ] **Step 7: Commit**

```bash
git add cia-frontend/apps/platform/src/modules/tenants cia-frontend/apps/platform/src/app/router.tsx
git commit -m "feat(platform-ui): TenantsListPage + OnboardTenantSheet (paginated + one-time reveal)"
```

---

## Task 17: Tenant detail route + shared `AuditTable`

**Files (all create):**
- `cia-frontend/apps/platform/src/modules/audit/AuditTable.tsx` (shared by detail + audit page)
- `cia-frontend/apps/platform/src/modules/tenants/TenantDetailPage.tsx`
- Modify: `cia-frontend/apps/platform/src/app/router.tsx`

**Context:** A full route `/tenants/:schema` fed by the consolidated `useTenantDetail` (`{ tenant, recentAudit }`). Header + status + suspend/activate action + a recent-activity table. The audit table is extracted because the Audit page (Task 18) renders the same rows.

- [ ] **Step 1: `AuditTable.tsx`** (presentational; takes rows)

```tsx
import { Badge } from '@cia/ui';
import type { PlatformAuditEntry } from '@cia/api-client';

const ACTION_TONE: Record<string, string> = {
  ONBOARD: 'bg-primary/15 text-primary',
  ACTIVATE: 'bg-primary/15 text-primary',
  SUSPEND: 'bg-amber-500/15 text-amber-400',
  INVITE_SUPER_ADMIN: 'bg-sky-500/15 text-sky-400',
  REVOKE_SUPER_ADMIN: 'bg-rose-500/15 text-rose-400',
};

export default function AuditTable({ rows }: { rows: PlatformAuditEntry[] }) {
  if (rows.length === 0) {
    return <p className="px-1 py-6 text-center text-sm text-muted-foreground">No activity yet.</p>;
  }
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-secondary/40 text-xs text-muted-foreground">
          <tr>
            <th className="px-3 py-2 text-left">Action</th>
            <th className="px-3 py-2 text-left">Target</th>
            <th className="px-3 py-2 text-left">Actor</th>
            <th className="px-3 py-2 text-left">When</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {rows.map((r) => (
            <tr key={r.id}>
              <td className="px-3 py-2"><Badge className={ACTION_TONE[r.action] ?? 'bg-secondary text-foreground'}>{r.action}</Badge></td>
              <td className="px-3 py-2 font-mono text-xs">{r.targetSchema ?? detailName(r.detail)}</td>
              <td className="px-3 py-2">{r.actorUsername} <span className="text-muted-foreground">@ {r.actorRealm}</span></td>
              <td className="px-3 py-2 text-muted-foreground">{new Date(r.at).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** For user-targeted rows (null targetSchema), show the username from the detail JSON. */
function detailName(detail: string | null): string {
  if (!detail) return '—';
  try { return (JSON.parse(detail) as { username?: string }).username ?? '—'; } catch { return '—'; }
}
```

- [ ] **Step 2: `TenantDetailPage.tsx`**

```tsx
import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Button, PageHeader, PageSection, Skeleton, EmptyState, toast } from '@cia/ui';
import {
  useTenantDetail, useSuspendTenant, useActivateTenant, platformErrorCode,
} from '@cia/api-client';
import StatusBadge from './StatusBadge';
import AuditTable from '../audit/AuditTable';
import ConfirmActionDialog from '../../components/ConfirmActionDialog';

export default function TenantDetailPage() {
  const { schema } = useParams<{ schema: string }>();
  const navigate = useNavigate();
  const detailQuery = useTenantDetail(schema);
  const suspend = useSuspendTenant();
  const activate = useActivateTenant();
  const [confirm, setConfirm] = useState(false);

  if (detailQuery.isLoading) return <div className="p-6"><Skeleton className="h-64 w-full" /></div>;
  if (detailQuery.isError || !detailQuery.data) {
    return (
      <div className="p-6">
        <EmptyState title="Tenant not found" description={`No tenant with schema "${schema}".`}>
          <Button variant="outline" onClick={() => navigate('/tenants')}>Back to tenants</Button>
        </EmptyState>
      </div>
    );
  }

  const { tenant, recentAudit } = detailQuery.data;
  const action = tenant.active ? 'suspend' : 'activate';

  async function run() {
    try {
      if (action === 'suspend') await suspend.mutateAsync(tenant.schema);
      else await activate.mutateAsync(tenant.schema);
      toast({ title: action === 'suspend' ? 'Tenant suspended' : 'Tenant activated', description: tenant.displayName });
      setConfirm(false);
    } catch (err) {
      toast({ variant: 'destructive', title: 'Action failed', description: platformErrorCode(err) ?? 'Unexpected error.' });
    }
  }

  return (
    <div className="p-6">
      <PageHeader
        title={tenant.displayName}
        description={`${tenant.schema} · ${tenant.subdomain}`}
      >
        <Button variant={tenant.active ? 'destructive' : 'default'} onClick={() => setConfirm(true)}>
          {tenant.active ? 'Suspend' : 'Activate'}
        </Button>
      </PageHeader>

      <div className="mt-3 flex items-center gap-3 text-sm">
        <StatusBadge active={tenant.active} />
        <span className="text-muted-foreground">Created {new Date(tenant.createdAt).toLocaleDateString()}</span>
      </div>

      <PageSection title="Recent activity" className="mt-6">
        <AuditTable rows={recentAudit} />
      </PageSection>

      <ConfirmActionDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={tenant.active ? 'Suspend tenant?' : 'Activate tenant?'}
        description={tenant.active
          ? `Suspend ${tenant.displayName}? Its users are locked out at the gate immediately. Regulated data is retained — reversible.`
          : `Re-activate ${tenant.displayName}? Its users can sign in again.`}
        confirmLabel={tenant.active ? 'Suspend' : 'Activate'}
        destructive={tenant.active}
        busy={suspend.isPending || activate.isPending}
        onConfirm={run}
      />
    </div>
  );
}
```

> Note: `EmptyState` / `PageSection` / `PageHeader` prop names — confirm against `@cia/ui` (`grep -n "export function PageSection" -A6 packages/ui/src/components/layout/page-section.tsx`). If `PageSection` takes `title` as a prop it's used as above; if not, render an `<h2>` above `AuditTable`.

- [ ] **Step 3: Wire the route**

In `src/app/router.tsx`:

```tsx
const TenantDetailPage = lazy(() => import('../modules/tenants/TenantDetailPage'));
// ...
{ path: 'tenants/:schema', element: <Deferred><TenantDetailPage /></Deferred> },
```

- [ ] **Step 4: Build**

Run: `cd cia-frontend && pnpm --filter @cia/platform build`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add cia-frontend/apps/platform/src/modules/audit/AuditTable.tsx \
        cia-frontend/apps/platform/src/modules/tenants/TenantDetailPage.tsx \
        cia-frontend/apps/platform/src/app/router.tsx
git commit -m "feat(platform-ui): tenant detail route + shared AuditTable"
```

---

## Task 18: Audit log page (paginated + per-tenant filter)

**Files:**
- Create: `cia-frontend/apps/platform/src/modules/audit/AuditLogPage.tsx`
- Modify: `cia-frontend/apps/platform/src/app/router.tsx`

**Context:** Full paginated audit trail (`usePlatformAudit(page, size, targetSchema)`) with a `targetSchema` filter input (server-side) + `ServerPaginationFooter`.

- [ ] **Step 1: `AuditLogPage.tsx`**

```tsx
import { useState } from 'react';
import { Button, Input, PageHeader, PageSection, Skeleton } from '@cia/ui';
import { usePlatformAudit } from '@cia/api-client';
import AuditTable from './AuditTable';
import ServerPaginationFooter from '../../components/ServerPaginationFooter';

export default function AuditLogPage() {
  const [page, setPage] = useState(0);
  const [filterInput, setFilterInput] = useState('');
  const [targetSchema, setTargetSchema] = useState<string | undefined>(undefined);
  const size = 50;
  const auditQuery = usePlatformAudit(page, size, targetSchema);

  const rows = auditQuery.data?.data ?? [];
  const meta = auditQuery.data?.meta;

  function applyFilter(e: React.FormEvent) {
    e.preventDefault();
    setPage(0);
    setTargetSchema(filterInput.trim() || undefined);
  }

  return (
    <div className="p-6">
      <PageHeader title="Audit log" description="Every platform mutation — onboarding, suspend/activate, super-admin changes." />

      <form onSubmit={applyFilter} className="mt-4 flex gap-2">
        <Input
          value={filterInput}
          onChange={(e) => setFilterInput(e.target.value)}
          placeholder="Filter by tenant schema (e.g. tenant_acme)…"
          className="max-w-xs"
        />
        <Button type="submit" variant="outline">Filter</Button>
        {targetSchema && (
          <Button type="button" variant="ghost" onClick={() => { setFilterInput(''); setTargetSchema(undefined); setPage(0); }}>
            Clear
          </Button>
        )}
      </form>

      <PageSection className="mt-4">
        {auditQuery.isLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : (
          <>
            <AuditTable rows={rows} />
            {meta && <ServerPaginationFooter page={page} size={size} total={meta.total} onPageChange={setPage} noun="events" />}
          </>
        )}
      </PageSection>
    </div>
  );
}
```

> Note: `Button` `variant="ghost"` — confirm it exists in `buttonVariants` (`grep -n "ghost" packages/ui/src/components/button.tsx`); back-office uses `outline`/`destructive`/`default`. If `ghost` is absent, use `variant="outline"`.

- [ ] **Step 2: Wire the route**

In `src/app/router.tsx`:

```tsx
const AuditLogPage = lazy(() => import('../modules/audit/AuditLogPage'));
// ...
{ path: 'audit', element: <Deferred><AuditLogPage /></Deferred> },
```

- [ ] **Step 3: Build**

Run: `cd cia-frontend && pnpm --filter @cia/platform build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/apps/platform/src/modules/audit/AuditLogPage.tsx cia-frontend/apps/platform/src/app/router.tsx
git commit -m "feat(platform-ui): AuditLogPage (paginated + per-tenant filter)"
```

---

## Task 19: Dashboard landing page

**Files:**
- Create: `cia-frontend/apps/platform/src/modules/dashboard/DashboardPage.tsx`
- Modify: `cia-frontend/apps/platform/src/app/router.tsx`

**Context:** Landing view: 3 StatCards (`usePlatformStats`), a recent-activity feed (first audit page via `usePlatformAudit(0, 8)`, rendered with the shared `AuditTable`), and two quick-action buttons (Onboard tenant → opens the sheet inline; Invite super-admin → navigates to `/super-admins?invite=1`).

- [ ] **Step 1: `DashboardPage.tsx`**

```tsx
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, PageHeader, PageSection, StatCard, Skeleton } from '@cia/ui';
import { usePlatformStats, usePlatformAudit } from '@cia/api-client';
import AuditTable from '../audit/AuditTable';
import OnboardTenantSheet from '../tenants/OnboardTenantSheet';

export default function DashboardPage() {
  const navigate = useNavigate();
  const stats = usePlatformStats().data;
  const auditQuery = usePlatformAudit(0, 8);
  const [onboardOpen, setOnboardOpen] = useState(false);

  return (
    <div className="p-6">
      <PageHeader title="Platform overview" description="Cross-tenant health and recent activity.">
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => navigate('/super-admins?invite=1')}>Invite super-admin</Button>
          <Button onClick={() => setOnboardOpen(true)}>+ Onboard tenant</Button>
        </div>
      </PageHeader>

      <div className="mt-4 grid grid-cols-3 gap-3">
        <StatCard label="Total tenants" value={stats ? String(stats.total) : '—'} />
        <StatCard label="Active" value={stats ? String(stats.active) : '—'} />
        <StatCard label="Suspended" value={stats ? String(stats.suspended) : '—'} />
      </div>

      <PageSection title="Recent activity" className="mt-6">
        {auditQuery.isLoading
          ? <Skeleton className="h-48 w-full" />
          : <AuditTable rows={auditQuery.data?.data ?? []} />}
      </PageSection>

      <OnboardTenantSheet open={onboardOpen} onOpenChange={setOnboardOpen} />
    </div>
  );
}
```

- [ ] **Step 2: Wire the route**

In `src/app/router.tsx`:

```tsx
const DashboardPage = lazy(() => import('../modules/dashboard/DashboardPage'));
// ...
{ path: 'dashboard', element: <Deferred><DashboardPage /></Deferred> },
```

Once Dashboard/Tenants/Audit/Super-admins are all wired, delete the placeholder import + file:

```bash
rm cia-frontend/apps/platform/src/modules/_placeholder/PlaceholderPage.tsx
```
and remove its `lazy(...)` import line from `router.tsx`.

- [ ] **Step 3: Build**

Run: `cd cia-frontend && pnpm --filter @cia/platform build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/apps/platform/src/modules/dashboard cia-frontend/apps/platform/src/app/router.tsx
git commit -m "feat(platform-ui): Dashboard landing (stats + recent activity + quick actions)"
```

---

## Task 20: Super-admins page (list + invite + revoke)

**Files (all create):**
- `cia-frontend/apps/platform/src/modules/super-admins/SuperAdminsPage.tsx`
- `cia-frontend/apps/platform/src/modules/super-admins/InviteSuperAdminSheet.tsx`
- `cia-frontend/apps/platform/src/modules/super-admins/SuperAdminsPage.test.tsx`
- Modify: `cia-frontend/apps/platform/src/app/router.tsx`

**Context:** Lists current super-admins; "+ Invite" opens a sheet (username + email → `CredentialReveal`); per-row "Revoke" → `ConfirmActionDialog` → `useRevokeSuperAdmin`. The current user's own row and (when only one remains) the last row have Revoke disabled (UI hint); the backend `CANNOT_REVOKE_SELF`/`CANNOT_REVOKE_LAST_SUPER_ADMIN` are surfaced as a destructive toast if attempted anyway. A 503 `KEYCLOAK_ADMIN_DISABLED` renders an informational empty state. Auto-opens the invite sheet when navigated with `?invite=1` (from the Dashboard quick action).

- [ ] **Step 1: `InviteSuperAdminSheet.tsx`**

```tsx
import { useState } from 'react';
import {
  Button, Input, Label, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, toast,
} from '@cia/ui';
import { useInviteSuperAdmin, platformErrorCode, type InviteSuperAdminResponse } from '@cia/api-client';
import CredentialReveal from '../../components/CredentialReveal';

interface Props { open: boolean; onOpenChange: (open: boolean) => void; }

const ERR: Record<string, string> = {
  SUPER_ADMIN_ALREADY_EXISTS: 'A super-admin with that username already exists.',
  KEYCLOAK_ADMIN_DISABLED: 'Super-admin management needs the Keycloak admin client enabled.',
  VALIDATION_ERROR: 'Enter a username and a valid email.',
};

export default function InviteSuperAdminSheet({ open, onOpenChange }: Props) {
  const invite = useInviteSuperAdmin();
  const [result, setResult] = useState<InviteSuperAdminResponse | null>(null);
  const [form, setForm] = useState({ username: '', email: '' });

  function close() {
    setResult(null);
    setForm({ username: '', email: '' });
    onOpenChange(false);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    try {
      setResult(await invite.mutateAsync(form));
    } catch (err) {
      const code = platformErrorCode(err);
      toast({ variant: 'destructive', title: 'Invite failed', description: (code && ERR[code]) || 'Unexpected error.' });
    }
  }

  return (
    <Sheet open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())}>
      <SheetContent className="w-full sm:max-w-md">
        {!result ? (
          <>
            <SheetHeader>
              <SheetTitle>Invite super-admin</SheetTitle>
              <SheetDescription>Creates a platform-realm account with the SUPER_ADMIN role.</SheetDescription>
            </SheetHeader>
            <form onSubmit={submit} className="mt-4 space-y-3">
              <div className="space-y-1">
                <Label htmlFor="sa-username">Username</Label>
                <Input id="sa-username" value={form.username} onChange={(e) => setForm((f) => ({ ...f, username: e.target.value }))} required />
              </div>
              <div className="space-y-1">
                <Label htmlFor="sa-email">Email</Label>
                <Input id="sa-email" type="email" value={form.email} onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))} required />
              </div>
              <div className="flex justify-end pt-3">
                <Button type="submit" disabled={invite.isPending}>{invite.isPending ? 'Inviting…' : 'Invite →'}</Button>
              </div>
            </form>
          </>
        ) : (
          <>
            <SheetHeader>
              <SheetTitle>Super-admin invited</SheetTitle>
              <SheetDescription>{result.username} · {result.email}</SheetDescription>
            </SheetHeader>
            <div className="mt-4">
              <CredentialReveal
                title="Super-admin invited"
                subtitle={`${result.username} · ${result.email}`}
                identityLabel="Account"
                identityValue={`${result.username} · ${result.email}`}
                secret={result.temporaryPassword}
                onDone={close}
              />
            </div>
          </>
        )}
      </SheetContent>
    </Sheet>
  );
}
```

- [ ] **Step 2: `SuperAdminsPage` test (failing)**

`src/modules/super-admins/SuperAdminsPage.test.tsx`:

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import SuperAdminsPage from './SuperAdminsPage';

vi.mock('@cia/auth', () => ({ useAuth: () => ({ user: { name: 'Root', email: 'root@x.test' } }) }));
vi.mock('@cia/api-client', () => ({
  useSuperAdmins: () => ({
    data: [
      { username: 'root@x.test', email: 'root@x.test', enabled: true },
      { username: 'sa2', email: 'sa2@x.test', enabled: true },
    ],
    isLoading: false, isError: false,
  }),
  useRevokeSuperAdmin: () => ({ mutateAsync: vi.fn(), isPending: false }),
  platformErrorCode: () => undefined,
}));

function wrap(ui: React.ReactNode) { return render(<MemoryRouter>{ui}</MemoryRouter>); }

describe('SuperAdminsPage', () => {
  it('lists super-admins and disables Revoke on the current user row', () => {
    wrap(<SuperAdminsPage />);
    expect(screen.getByText('sa2')).toBeInTheDocument();
    // The current user (matched by email) cannot revoke self → that row's button is disabled.
    const selfRow = screen.getByText('root@x.test').closest('tr')!;
    const selfRevoke = within(selfRow).getByRole('button', { name: /revoke/i });
    expect(selfRevoke).toBeDisabled();
  });
});

import { within } from '@testing-library/react';
```

- [ ] **Step 3: `SuperAdminsPage.tsx`**

```tsx
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, PageHeader, PageSection, Skeleton, EmptyState, Badge, toast } from '@cia/ui';
import { useAuth } from '@cia/auth';
import { useSuperAdmins, useRevokeSuperAdmin, platformErrorCode, type SuperAdminSummary } from '@cia/api-client';
import InviteSuperAdminSheet from './InviteSuperAdminSheet';
import ConfirmActionDialog from '../../components/ConfirmActionDialog';

const REVOKE_ERR: Record<string, string> = {
  CANNOT_REVOKE_SELF: 'You cannot revoke your own super-admin access.',
  CANNOT_REVOKE_LAST_SUPER_ADMIN: 'Cannot revoke the last remaining super-admin.',
  SUPER_ADMIN_NOT_FOUND: 'That super-admin no longer exists.',
  KEYCLOAK_ADMIN_DISABLED: 'Super-admin management needs the Keycloak admin client enabled.',
};

export default function SuperAdminsPage() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const query = useSuperAdmins();
  const revoke = useRevokeSuperAdmin();
  const [inviteOpen, setInviteOpen] = useState(false);
  const [pending, setPending] = useState<SuperAdminSummary | null>(null);

  // Dashboard "Invite super-admin" deep-link → auto-open the sheet once.
  useEffect(() => {
    if (params.get('invite') === '1') {
      setInviteOpen(true);
      params.delete('invite');
      setParams(params, { replace: true });
    }
  }, [params, setParams]);

  const admins = query.data ?? [];
  const onlyOne = admins.length <= 1;
  const isSelf = (a: SuperAdminSummary) => a.username === user?.email || a.username === user?.name;

  async function run() {
    if (!pending) return;
    try {
      await revoke.mutateAsync(pending.username);
      toast({ title: 'Super-admin revoked', description: pending.username });
      setPending(null);
    } catch (err) {
      const code = platformErrorCode(err);
      toast({ variant: 'destructive', title: 'Revoke failed', description: (code && REVOKE_ERR[code]) || 'Unexpected error.' });
    }
  }

  // 503: the list query errors with KEYCLOAK_ADMIN_DISABLED → informational empty state.
  if (query.isError) {
    return (
      <div className="p-6">
        <PageHeader title="Super-admins" />
        <EmptyState
          title="Super-admin management unavailable"
          description="This needs the Keycloak admin client enabled (cia.keycloak.admin.enabled=true)."
        />
      </div>
    );
  }

  return (
    <div className="p-6">
      <PageHeader title="Super-admins" description="Platform-realm accounts with cross-tenant SUPER_ADMIN.">
        <Button onClick={() => setInviteOpen(true)}>+ Invite super-admin</Button>
      </PageHeader>

      <PageSection className="mt-4">
        {query.isLoading ? (
          <Skeleton className="h-48 w-full" />
        ) : (
          <div className="overflow-hidden rounded-lg border">
            <table className="w-full text-sm">
              <thead className="bg-secondary/40 text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 text-left">Username</th>
                  <th className="px-3 py-2 text-left">Email</th>
                  <th className="px-3 py-2 text-left">Status</th>
                  <th className="px-3 py-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {admins.map((a) => {
                  const self = isSelf(a);
                  const disabled = self || onlyOne;
                  return (
                    <tr key={a.username}>
                      <td className="px-3 py-2 font-medium text-foreground">{a.username}{self && <span className="ml-2 text-xs text-muted-foreground">(you)</span>}</td>
                      <td className="px-3 py-2">{a.email}</td>
                      <td className="px-3 py-2">
                        {a.enabled ? <Badge className="bg-primary/15 text-primary">Enabled</Badge> : <Badge className="bg-muted text-muted-foreground">Disabled</Badge>}
                      </td>
                      <td className="px-3 py-2 text-right">
                        <Button
                          variant="destructive" size="sm"
                          disabled={disabled}
                          title={self ? 'You cannot revoke your own access' : onlyOne ? 'Cannot revoke the last super-admin' : undefined}
                          onClick={() => setPending(a)}
                        >
                          Revoke
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </PageSection>

      <InviteSuperAdminSheet open={inviteOpen} onOpenChange={setInviteOpen} />
      <ConfirmActionDialog
        open={!!pending}
        onOpenChange={(o) => !o && setPending(null)}
        title="Revoke super-admin?"
        description={`Remove SUPER_ADMIN from ${pending?.username}? Their account stays but loses all platform access.`}
        confirmLabel="Revoke"
        destructive
        busy={revoke.isPending}
        onConfirm={run}
      />
    </div>
  );
}
```

- [ ] **Step 4: Wire the route + run tests + build**

In `src/app/router.tsx`:

```tsx
const SuperAdminsPage = lazy(() => import('../modules/super-admins/SuperAdminsPage'));
// ...
{ path: 'super-admins', element: <Deferred><SuperAdminsPage /></Deferred> },
```

Run: `cd cia-frontend && pnpm --filter @cia/platform test && pnpm --filter @cia/platform build`
Expected: PASS (SuperAdminsPage self-revoke-disabled test) + clean build.

- [ ] **Step 5: Frontend guards**

Run: `bash cia-frontend/scripts/check-api-wiring.sh && node cia-frontend/scripts/check-dto-drift.mjs`
Expected: PASS. (No module-level mocks, no console.log, no TODO hooks. The DTO-drift guard scans `packages/api-client/src/modules/` — the new `*Schema` exports aren't `*Dto` interfaces, so the default mapping skips them; if it flags `platform.ts`, add a `manualMap` skip entry with a reason in `dto-drift.config.json`.)

- [ ] **Step 6: Commit**

```bash
git add cia-frontend/apps/platform/src/modules/super-admins cia-frontend/apps/platform/src/app/router.tsx
git commit -m "feat(platform-ui): SuperAdminsPage (list + invite + guarded revoke)"
```

---

# PHASE 4 — Deploy

## Task 21: Vercel project wiring + CI workflow + docs

**Files:**
- Create: `cia-frontend/apps/platform/vercel.json`
- Create: `.github/workflows/vercel-deploy-platform.yml`
- Modify: `CLAUDE.md` (env vars + frontend deployment notes)
- Modify: `cia-log.md` (session entry + backlog reconciliation)

**Context:** A second Vercel project building `apps/platform` (demo mode public URL, mirroring back-office). The Vercel **project creation + secret/env setup is a one-time dashboard/CLI step** (documented here, not code); the workflow + `vercel.json` are the code artifacts. Mirrors `.github/workflows/vercel-deploy.yml`.

- [ ] **Step 1: `apps/platform/vercel.json`**

```json
{
  "buildCommand": "pnpm --filter @cia/platform build",
  "outputDirectory": "apps/platform/dist",
  "installCommand": "pnpm install --frozen-lockfile",
  "framework": "vite",
  "rewrites": [
    { "source": "/(.*)", "destination": "/index.html" }
  ],
  "headers": [
    { "source": "/assets/(.*)", "headers": [{ "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }] },
    { "source": "/index.html", "headers": [{ "key": "Cache-Control", "value": "public, max-age=0, must-revalidate" }] }
  ]
}
```

> Like back-office, the Vercel project's **Root Directory must be `cia-frontend/`** (the monorepo root) so workspace packages resolve during install; the build/output paths above are relative to that root.

- [ ] **Step 2: `.github/workflows/vercel-deploy-platform.yml`**

```yaml
name: Vercel Deploy — NubSure Platform

on:
  push:
    branches: [main]
    paths:
      - 'cia-frontend/**'
  pull_request:
    branches: [main]
    paths:
      - 'cia-frontend/**'

jobs:
  deploy:
    name: Deploy Platform to Vercel
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Install pnpm
        uses: pnpm/action-setup@v4
        with:
          version: 9
          run_install: false

      - name: Set up Node.js 20
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Install Vercel CLI
        run: npm install --global vercel@latest

      - name: Pull Vercel environment (production)
        if: github.ref == 'refs/heads/main'
        run: vercel pull --yes --environment=production --token=$VERCEL_TOKEN
        working-directory: cia-frontend/apps/platform
        env:
          VERCEL_TOKEN: ${{ secrets.VERCEL_TOKEN }}
          VERCEL_ORG_ID: ${{ secrets.VERCEL_ORG_ID }}
          VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PLATFORM_PROJECT_ID }}

      - name: Pull Vercel environment (preview)
        if: github.ref != 'refs/heads/main'
        run: vercel pull --yes --environment=preview --token=$VERCEL_TOKEN
        working-directory: cia-frontend/apps/platform
        env:
          VERCEL_TOKEN: ${{ secrets.VERCEL_TOKEN }}
          VERCEL_ORG_ID: ${{ secrets.VERCEL_ORG_ID }}
          VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PLATFORM_PROJECT_ID }}

      - name: Build (production)
        if: github.ref == 'refs/heads/main'
        run: vercel build --prod --token=$VERCEL_TOKEN
        working-directory: cia-frontend/apps/platform
        env:
          VERCEL_TOKEN: ${{ secrets.VERCEL_TOKEN }}
          VERCEL_ORG_ID: ${{ secrets.VERCEL_ORG_ID }}
          VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PLATFORM_PROJECT_ID }}

      - name: Build (preview)
        if: github.ref != 'refs/heads/main'
        run: vercel build --token=$VERCEL_TOKEN
        working-directory: cia-frontend/apps/platform
        env:
          VERCEL_TOKEN: ${{ secrets.VERCEL_TOKEN }}
          VERCEL_ORG_ID: ${{ secrets.VERCEL_ORG_ID }}
          VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PLATFORM_PROJECT_ID }}

      - name: Deploy to production
        if: github.ref == 'refs/heads/main'
        run: vercel deploy --prebuilt --prod --token=$VERCEL_TOKEN
        working-directory: cia-frontend/apps/platform
        env:
          VERCEL_TOKEN: ${{ secrets.VERCEL_TOKEN }}
          VERCEL_ORG_ID: ${{ secrets.VERCEL_ORG_ID }}
          VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PLATFORM_PROJECT_ID }}

      - name: Deploy preview
        if: github.ref != 'refs/heads/main'
        run: vercel deploy --prebuilt --token=$VERCEL_TOKEN
        working-directory: cia-frontend/apps/platform
        env:
          VERCEL_TOKEN: ${{ secrets.VERCEL_TOKEN }}
          VERCEL_ORG_ID: ${{ secrets.VERCEL_ORG_ID }}
          VERCEL_PROJECT_ID: ${{ secrets.VERCEL_PLATFORM_PROJECT_ID }}
```

- [ ] **Step 3: Document the one-time Vercel + env setup in `CLAUDE.md`**

Add to the **Environment Variables → Frontend** table a note that the platform app reuses the `VITE_KEYCLOAK_*` names (pointed at the `platform` realm + `cia-platform` client) and add a "Platform console deployment" bullet under §10 Frontend deployment:

```markdown
- **Platform console (`apps/platform`):** separate Vercel project, Root Directory `cia-frontend/`, build `pnpm --filter @cia/platform build`, output `apps/platform/dist`. CI: `.github/workflows/vercel-deploy-platform.yml` (preview on PR, prod on push to main, filtered to `cia-frontend/**`). Required GitHub secret: `VERCEL_PLATFORM_PROJECT_ID` (the platform project id; `VERCEL_TOKEN`/`VERCEL_ORG_ID` are shared with back-office). Env vars per environment: `VITE_API_BASE_URL`, `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM` (= `platform`), `VITE_KEYCLOAK_CLIENT_ID` (= `cia-platform`), and `VITE_DEMO_MODE=true` on the public preview only. The platform app reuses the `VITE_KEYCLOAK_*` names (scoped per-deployment) because `@cia/auth`'s `initKeycloak` keys `onLoad:'login-required'` off `VITE_KEYCLOAK_URL`. Like back-office, the public URL is a frontend-only demo until real platform Keycloak + backend infra are deployed.
```

- [ ] **Step 4: Verify CI lint locally + build**

Run: `cd cia-frontend && pnpm --filter @cia/platform build && bash cia-frontend/scripts/check-api-wiring.sh`
Expected: SUCCESS. (The workflow YAML is validated by GitHub on push; a local `yamllint .github/workflows/vercel-deploy-platform.yml` is optional.)

- [ ] **Step 5: Commit**

```bash
git add cia-frontend/apps/platform/vercel.json .github/workflows/vercel-deploy-platform.yml CLAUDE.md
git commit -m "feat(platform-ui): Vercel project wiring + CI deploy workflow + docs"
```

- [ ] **Step 6: Update `cia-log.md`**

Add a session entry (one goal: "SP2 — platform-admin UI + backend extensions"), list every file created/modified across the 21 tasks, and the **backlog reconciliation**: drains `platform-admin-ui-sp2`, `platform-invite-super-admin`, and `platform-audit-log-tenant-schema-pollution`; notes V68 cured the pollution; flags the rule-of-three watch on the local `ServerPaginationFooter` vs the existing `list-endpoints-true-pagination` item; records the env-var-naming deviation (platform reuses `VITE_KEYCLOAK_*`). Commit:

```bash
git add cia-log.md && git commit -m "docs: cia-log SP2 platform-admin UI session entry + backlog reconciliation"
```

---

# Self-Review (run against the spec)

**Spec coverage** — every spec section maps to a task:
- §3(a) consolidated detail → Tasks 4 (DTO+service) + 5 (controller).
- §3(b) pagination + audit filter + `/stats` → Tasks 2 (audit), 3 (registry), 4 (service+stats), 5 (controller).
- §3(c) super-admin invite/revoke → Tasks 6 (provisioner), 7 (service+DTOs+exceptions), 8 (controller), 9 (E2E).
- §3(d) schema-aware V68 → Task 1.
- §4 FE foundation → Tasks 11 (scaffold), 12 (auth+gate), 13 (api-client), 14 (AppShell).
- §5 screens → Tasks 15 (shared incl. CredentialReveal), 16 (Tenants+Onboard), 17 (Detail), 18 (Audit), 19 (Dashboard), 20 (Super-admins).
- §6 deploy → Task 21.
- §7 error handling + testing → woven through (errorCode→friendly copy in each sheet/page; backend ITs in 1–9; Vitest in 12/15/16/20; reactor gate in 10).

**Type consistency** — backend DTO field names (`TenantSummary{schema,displayName,subdomain,active,createdAt}`, `PlatformAuditEntry{...,targetSchema,actorUsername,actorRealm,detail,sourceIp,at}`, `TenantDetailResponse{tenant,recentAudit}`, `TenantStats{total,active,suspended}`, `SuperAdminSummary{username,email,enabled}`, `InviteSuperAdminResponse{username,email,temporaryPassword}`) exactly match the zod schemas in Task 13 and the screen consumers. The `PlatformAuditService.record(action, targetSchema, actor, actorRealm, detailJson, sourceIp)` signature is reused unchanged for super-admin rows (null `targetSchema`).

**Placeholder scan** — no TBD/TODO. The few "verify the prop name against `@cia/ui`" notes (`DataTableRowActions`/`RowAction`, `StatCard` label vs title, `PageSection.title`, `Button variant="ghost"`, hugeicon names) are real-codebase confirmations with the exact `grep` to run + the fallback, not deferred work.

**Known deviations from spec (intentional, flagged):**
1. **Env var names** — platform reuses `VITE_KEYCLOAK_*` (not the spec's `VITE_PLATFORM_KEYCLOAK_*`) because the shared `@cia/auth` keys `onLoad` off `VITE_KEYCLOAK_URL`; documented in Task 21 + CLAUDE.md.
2. **`PagedResult`** carrier record (not Spring `Page`) for the JDBC-backed list — consistent with the registry being JdbcTemplate, not JPA.

**Scope** — one cohesive sub-project across 21 tasks, phased so the backend lands + verifies green (Task 10) before any FE work, and each FE screen is independently buildable.

