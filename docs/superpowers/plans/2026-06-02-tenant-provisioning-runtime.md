# Tenant Provisioning Runtime — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provision and migrate isolated per-tenant PostgreSQL schemas at application startup — schema creation, Flyway-per-schema migration, sensible-defaults seed, Keycloak realm roles + first-admin user, and a fail-fast registry-sweep re-migration — driven by config, idempotent, and gated off by default so existing ITs/dev are unaffected.

**Architecture:** A gated `TenantBootstrapRunner` (`ApplicationRunner` in `cia-api`) drives `TenantProvisioningService`, which orchestrates four single-responsibility collaborators — `TenantSchemaMigrator` (CREATE SCHEMA + programmatic Flyway-per-schema with a search_path callback), `TenantSeeder` (idempotent JdbcTemplate seed), `TenantRegistry` (JdbcTemplate over `public.tenants`), and the existing `KeycloakTenantProvisioner` (extended with `ensureRealmRoles` + `ensureFirstAdminUser`). After provisioning the config-declared tenants it sweeps the active registry and re-migrates every schema. Any failure aborts startup.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Flyway (programmatic API), PostgreSQL, Keycloak admin client (`org.keycloak:keycloak-admin-client`), Testcontainers (Postgres + Keycloak), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-06-02-tenant-provisioning-runtime-design.md`

**Branch:** `slice-a-tenant-provisioning` (already created)

---

## File Structure

**cia-setup** (`cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/`):
- Create `BootstrapRoles.java` — the canonical realm-role name set (Pattern A + B) as an immutable `List<String>`.
- Modify `KeycloakTenantProvisioner.java` — add `ensureRealmRoles(Keycloak, String realm)` and `ensureFirstAdminUser(Keycloak, String realm, FirstAdminSpec)`; add public `provisionTenantAuth(String realm, FirstAdminSpec)` that runs the existing realm/client steps + the two new ones.
- Create `FirstAdminSpec.java` — record carrying `username, email, firstName, lastName, tempPassword, accessGroupId`.

**cia-api** (`cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/`):
- Create `TenantSchemaMigrator.java` — `ensureSchema(String)` + `migrate(String)` (programmatic Flyway + search_path callback).
- Create `TenantSeeder.java` — `seed(String schema, UUID adminGroupId)` (idempotent JdbcTemplate inserts).
- Create `TenantRegistry.java` — `upsert(String schema, String name, String subdomain)` + `findActiveSchemas()` (JdbcTemplate over `public.tenants`).
- Create `TenantBootstrapProperties.java` — `@ConfigurationProperties("cia.tenants.bootstrap")`.
- Create `TenantProvisioningService.java` — orchestrator.
- Create `TenantBootstrapRunner.java` — gated `ApplicationRunner`.

**cia-api tests** (`cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/` and `.../keycloak/`):
- `TenantProvisioningItSupport.java` — Testcontainers Postgres base for the data-plane ITs.
- `TenantSchemaMigratorIT.java`, `TenantSeederIT.java`, `TenantRegistryIT.java`, `TenantProvisioningServiceIT.java`.
- `BootstrapRolesDriftTest.java` — greps the reactor for authorities not in `BootstrapRoles.ALL`.
- `keycloak/KeycloakFirstAdminProvisioningIT.java` — extends `KeycloakItSupport`.
- `TenantBootstrapRunnerGatingTest.java` — asserts the runner bean is absent when the flag is off.

**Config / docs:**
- Modify `cia-api/src/main/resources/application.yml` — add the `cia.tenants.bootstrap` block.
- Modify `CLAUDE.md` (§5.4, §6, Environment Variables), `cia-log.md` (session entry + backlog), docs-site.

---

## Task 1: `BootstrapRoles` constant + drift guard

**Files:**
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/BootstrapRoles.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/BootstrapRolesDriftTest.java`

- [ ] **Step 1: Write the failing drift-guard test**

This test is the safety net: it scans every controller in the reactor for `hasRole(...)` / `hasAuthority(...)` and fails if any referenced authority is missing from `BootstrapRoles.ALL`. It reverses the `JwtAuthConverter` mapping (authority `FOO_BAR` ⇐ role `foo_bar` for Pattern A; `FINANCE_VIEW` ⇐ role `FINANCE_VIEW` for Pattern B) by comparing the **uppercased role** to the authority.

```java
package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards BootstrapRoles.ALL against silent drift: every authority referenced by a
 * hasRole(...) / hasAuthority(...) / @PreAuthorize anywhere under cia-backend must be
 * covered by a role in BootstrapRoles.ALL (compared case-insensitively, since
 * JwtAuthConverter uppercases the role to form the ROLE_<X> authority).
 */
class BootstrapRolesDriftTest {

    private static final Pattern AUTHORITY =
            Pattern.compile("has(?:Role|Authority)\\(\\s*'([A-Za-z0-9_:]+)'");

    @Test
    void everyReferencedAuthorityIsCoveredByBootstrapRoles() throws IOException {
        Path backend = Paths.get(System.getProperty("user.dir")).getParent(); // cia-api -> cia-backend
        Set<String> covered = new HashSet<>();
        for (String role : BootstrapRoles.ALL) {
            covered.add(normalise(role));
        }

        Set<String> referenced = new TreeSet<>();
        try (Stream<Path> files = Files.walk(backend)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> p.toString().contains("/src/main/"))
                 .forEach(p -> scan(p, referenced));
        }

        Set<String> missing = new TreeSet<>();
        for (String auth : referenced) {
            if (!covered.contains(normalise(auth))) missing.add(auth);
        }

        assertThat(missing)
            .as("authorities referenced in controllers but missing from BootstrapRoles.ALL — "
                + "add them (and create the Keycloak role) so the bootstrap admin keeps full access")
            .isEmpty();
    }

    private static void scan(Path file, Set<String> out) {
        try {
            String src = Files.readString(file);
            Matcher m = AUTHORITY.matcher(src);
            while (m.find()) out.add(m.group(1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Collapse role/authority spellings: 'setup:view' and 'setup_view' both → 'SETUP_VIEW'. */
    private static String normalise(String s) {
        return s.replace(':', '_').toUpperCase(Locale.ROOT);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=BootstrapRolesDriftTest`
Expected: FAIL to **compile** — `BootstrapRoles` does not exist yet.

- [ ] **Step 3: Create `BootstrapRoles`**

```java
package com.nubeero.cia.setup.keycloak;

import java.util.List;

/**
 * Canonical Keycloak realm-role set assigned to a freshly provisioned tenant's first admin.
 *
 * <p>Pattern A roles ({@code module_action}) mirror the {@code module:action} permission model
 * synced by {@link KeycloakRealmRoleSyncer}; Pattern B roles (SCREAMING_CASE) are hardcoded
 * authorities used directly in {@code @PreAuthorize} on the finance / platform-admin surfaces and
 * are NOT derived from the permission model.
 *
 * <p>Drift is enforced by {@code BootstrapRolesDriftTest}, which fails the build if any controller
 * references an authority absent from {@link #ALL}.
 */
public final class BootstrapRoles {

    private BootstrapRoles() {}

    /** Pattern A — created with a "CIA-managed:" description to match the role syncer's convention. */
    public static final List<String> PATTERN_A = List.of(
        "setup_view", "setup_create", "setup_update", "setup_delete",
        "claims_view", "claims_create", "claims_update", "claims_approve",
        "customer_view", "customer_create", "customer_update",
        "underwriting_view", "underwriting_create", "underwriting_update", "underwriting_approve",
        "quotation_view", "quotation_create", "quotation_update", "quotation_approve",
        "reinsurance_view", "reinsurance_create", "reinsurance_update", "reinsurance_approve",
        "audit_view",
        "notification_templates_view", "notification_templates_update",
        "reports_view", "reports_create_custom", "reports_export_csv",
        "reports_export_pdf", "reports_manage_access"
    );

    /** Pattern B — hardcoded realm roles, not syncer-managed. */
    public static final List<String> PATTERN_B = List.of(
        "FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE", "FINANCE_APPROVE",
        "FINANCE_APPROVE_PPA", "FINANCE_REOPEN_PERIOD", "FINANCE_OVERRIDE_LOCK",
        "PLATFORM_ADMIN"
    );

    /** Every role the bootstrap admin must hold. */
    public static final List<String> ALL =
        java.util.stream.Stream.concat(PATTERN_A.stream(), PATTERN_B.stream()).toList();

    /** The {@code module:action} permission strings seeded into the Administrators access group. */
    public static final List<String> ADMIN_PERMISSIONS = PATTERN_A.stream()
        .map(r -> r.replaceFirst("_", ":"))   // setup_view -> setup:view (first underscore only)
        .toList();
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=BootstrapRolesDriftTest`
Expected: PASS. If it FAILS listing missing authorities, add each missing role to `PATTERN_A` (if it is `module_action`) or `PATTERN_B` (if SCREAMING_CASE), then re-run. Do **not** weaken the regex — a real missing role means the bootstrap admin would lack access to that endpoint.

> Note on `ADMIN_PERMISSIONS`: `replaceFirst("_", ":")` converts only the first underscore, so `notification_templates_view` → `notification:templates_view`. If the drift test or seed reveals the permission convention differs (e.g. `notification_templates:view`), adjust the mapping for the multi-word module prefixes explicitly. Verify against `AccessGroupPermission` rows the UI writes before finalising — the authoritative source is `KeycloakRealmRoleSyncer.permissionToRoleName()`.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/BootstrapRoles.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/BootstrapRolesDriftTest.java
git commit -m "feat(tenant-provisioning): canonical bootstrap role set + drift guard"
```

---

## Task 2: `TenantSchemaMigrator` — CREATE SCHEMA + Flyway-per-schema

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemaMigrator.java`
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantProvisioningItSupport.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantSchemaMigratorIT.java`

- [ ] **Step 1: Write the Testcontainers support base**

```java
package com.nubeero.cia.api.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Spins up a real PostgreSQL once for the data-plane provisioning ITs and exposes a DataSource.
 * No Spring context — these units take a DataSource directly, so the IT is fast and focused.
 */
abstract class TenantProvisioningItSupport {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ciaprov")
            .withUsername("ciaprov")
            .withPassword("ciaprov");

    static HikariDataSource dataSource;

    @BeforeAll
    static void startDb() {
        POSTGRES.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(cfg);
    }

    @AfterAll
    static void stopDb() {
        if (dataSource != null) dataSource.close();
    }

    DataSource dataSource() {
        return dataSource;
    }
}
```

- [ ] **Step 2: Write the failing migrator IT**

```java
package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSchemaMigratorIT extends TenantProvisioningItSupport {

    @Test
    void migratesFullSchemaIntoTenantSchemaSkippingV1() {
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(dataSource());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());

        migrator.ensureSchema("tenant_alpha");
        migrator.migrate("tenant_alpha");

        // A V31+ table lands in the tenant schema (proves search_path callback beat V2's RESET).
        assertThat(tableExists(jdbc, "tenant_alpha", "journal_entry")).isTrue();
        // V1's shared registry is NOT cloned into the tenant schema (baselineVersion=1 skipped it).
        assertThat(tableExists(jdbc, "tenant_alpha", "tenants")).isFalse();
        // Each tenant has its own Flyway history.
        assertThat(tableExists(jdbc, "tenant_alpha", "flyway_schema_history")).isTrue();

        // Idempotent: a second migrate is a no-op (no exception, no duplicate apply).
        migrator.migrate("tenant_alpha");
        assertThat(tableExists(jdbc, "tenant_alpha", "journal_entry")).isTrue();
    }

    private static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
            Integer.class, schema, table);
        return n != null && n > 0;
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantSchemaMigratorIT`
Expected: FAIL to compile — `TenantSchemaMigrator` does not exist.

- [ ] **Step 4: Implement `TenantSchemaMigrator`**

```java
package com.nubeero.cia.api.tenant;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns per-tenant schema DDL: creating the schema and running Flyway against it.
 *
 * <p>Flyway is configured with baselineVersion=1 so V1 (the shared public.tenants registry) is
 * marked applied and never cloned into a tenant schema; migration begins at V2. A
 * BEFORE_EACH_MIGRATE callback re-pins search_path to the target schema before every migration,
 * neutralising V2's trailing {@code RESET search_path} (which would otherwise drop V3..V66 into
 * public). Postgres DDL is transactional, so a failed migration rolls back to the prior version.
 */
@Slf4j
@Component
public class TenantSchemaMigrator {

    private final DataSource dataSource;

    public TenantSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Idempotent CREATE SCHEMA. */
    public void ensureSchema(String schema) {
        validate(schema);
        try (var conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
            log.info("Tenant schema '{}' ensured", schema);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create schema " + schema, e);
        }
    }

    /** Run V2..V66 against the tenant schema (V1 baselined out). Throws on failure (fail-fast). */
    public void migrate(String schema) {
        validate(schema);
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .locations("classpath:db/migration")
            .callbacks(new SearchPathCallback(schema))
            .load();
        flyway.migrate();
        log.info("Tenant schema '{}' migrated to current version", schema);
    }

    private static void validate(String schema) {
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Illegal tenant schema name: " + schema);
        }
    }

    /** Re-pins search_path to the tenant schema before each migration (beats V2's RESET). */
    private record SearchPathCallback(String schema) implements Callback {
        @Override public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_EACH_MIGRATE;
        }
        @Override public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }
        @Override public void handle(Event event, Context context) {
            try (Statement st = context.getConnection().createStatement()) {
                st.execute("SET search_path TO \"" + schema + "\"");
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to set search_path to " + schema, e);
            }
        }
        @Override public String getCallbackName() {
            return "tenant-search-path-" + schema;
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantSchemaMigratorIT`
Expected: PASS — `journal_entry` and `flyway_schema_history` exist in `tenant_alpha`, `tenants` does not, idempotent re-run succeeds.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemaMigrator.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantProvisioningItSupport.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantSchemaMigratorIT.java
git commit -m "feat(tenant-provisioning): Flyway-per-schema migrator (baseline past V1, search_path callback)"
```

---

## Task 3: `TenantSeeder` — idempotent sensible-defaults seed

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSeeder.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantSeederIT.java`

- [ ] **Step 1: Write the failing seeder IT**

```java
package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantSeederIT extends TenantProvisioningItSupport {

    @Test
    void seedsSensibleDefaultsIdempotently() {
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(dataSource());
        TenantSeeder seeder = new TenantSeeder(dataSource());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        UUID adminGroupId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        migrator.ensureSchema("tenant_seed");
        migrator.migrate("tenant_seed");

        seeder.seed("tenant_seed", adminGroupId);
        seeder.seed("tenant_seed", adminGroupId); // idempotent — second run must not duplicate

        jdbc.execute("SET search_path TO tenant_seed");
        assertThat(count(jdbc, "access_groups WHERE id = '" + adminGroupId + "'")).isEqualTo(1);
        assertThat(count(jdbc, "access_group_permissions WHERE access_group_id = '" + adminGroupId + "'"))
            .isGreaterThan(0);
        assertThat(count(jdbc, "currencies WHERE code = 'NGN' AND is_default = TRUE")).isEqualTo(1);
        assertThat(count(jdbc, "customer_number_format")).isEqualTo(1);
    }

    private static int count(JdbcTemplate jdbc, String tail) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + tail, Integer.class);
        return n == null ? 0 : n;
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantSeederIT`
Expected: FAIL to compile — `TenantSeeder` does not exist.

- [ ] **Step 3: Implement `TenantSeeder`**

```java
package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Seeds the sensible-defaults baseline into a freshly migrated tenant schema: an Administrators
 * access group (+ permissions), the NGN default currency, and the customer-number-format singleton.
 * Every insert is existence-guarded so re-running is a no-op. No users row is written — users live
 * in Keycloak. No policy-number format is seeded — it is per-product and created during product setup.
 */
@Slf4j
@Component
public class TenantSeeder {

    private final DataSource dataSource;

    public TenantSeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void seed(String schema, UUID adminGroupId) {
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Illegal tenant schema name: " + schema);
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("SET search_path TO \"" + schema + "\"");

        seedAdminGroup(jdbc, adminGroupId);
        seedCurrency(jdbc);
        seedCustomerNumberFormat(jdbc);
        log.info("Tenant schema '{}' seeded (admin group {})", schema, adminGroupId);
    }

    private void seedAdminGroup(JdbcTemplate jdbc, UUID adminGroupId) {
        jdbc.update("""
            INSERT INTO access_groups (id, name, description, created_at, updated_at, created_by)
            VALUES (?, 'Administrators', 'Full system access (bootstrap)', NOW(), NOW(), 'system')
            ON CONFLICT (id) DO NOTHING
            """, adminGroupId);
        for (String permission : BootstrapRoles.ADMIN_PERMISSIONS) {
            jdbc.update("""
                INSERT INTO access_group_permissions
                    (id, access_group_id, permission, created_at, updated_at, created_by)
                VALUES (gen_random_uuid(), ?, ?, NOW(), NOW(), 'system')
                ON CONFLICT (access_group_id, permission) DO NOTHING
                """, adminGroupId, permission);
        }
    }

    private void seedCurrency(JdbcTemplate jdbc) {
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM currencies WHERE code = 'NGN'", Integer.class);
        if (existing == null || existing == 0) {
            jdbc.update("""
                INSERT INTO currencies (id, code, name, symbol, is_default, created_at, updated_at, created_by)
                VALUES (gen_random_uuid(), 'NGN', 'Nigerian Naira', '₦', TRUE, NOW(), NOW(), 'system')
                """);
        }
    }

    private void seedCustomerNumberFormat(JdbcTemplate jdbc) {
        Integer existing = jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_number_format", Integer.class);
        if (existing == null || existing == 0) {
            jdbc.update("""
                INSERT INTO customer_number_format
                    (id, prefix, include_year, include_type, sequence_length,
                     last_sequence, last_sequence_individual, last_sequence_corporate,
                     created_at, updated_at, created_by)
                VALUES (gen_random_uuid(), 'CUST', TRUE, TRUE, 8, 0, 0, 0, NOW(), NOW(), 'system')
                """);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantSeederIT`
Expected: PASS — admin group + permissions + NGN currency + customer-number-format all present exactly once after two seed calls.

> If the test fails on a column mismatch (e.g. `access_group_permissions` unique constraint name, or a NOT NULL column without a default), fix the INSERT to match the actual DDL from the spec §research; do not add columns the table lacks.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSeeder.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantSeederIT.java
git commit -m "feat(tenant-provisioning): idempotent sensible-defaults tenant seeder"
```

---

## Task 4: `TenantRegistry` — public.tenants upsert + active sweep

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantRegistry.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantRegistryIT.java`

- [ ] **Step 1: Write the failing registry IT**

```java
package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRegistryIT extends TenantProvisioningItSupport {

    @BeforeEach
    void ensureRegistryTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("SET search_path TO public");
        // The registry lives in public; create it directly (mirrors V1) for this isolated unit IT.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tenants (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              schema_name VARCHAR(63) NOT NULL UNIQUE,
              name VARCHAR(255) NOT NULL,
              subdomain VARCHAR(63) NOT NULL UNIQUE,
              active BOOLEAN NOT NULL DEFAULT TRUE,
              created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""");
        jdbc.execute("TRUNCATE tenants");
    }

    @Test
    void upsertsIdempotentlyAndListsActiveSchemas() {
        TenantRegistry registry = new TenantRegistry(dataSource());

        registry.upsert("tenant_reg", "Reg Insurance", "reg");
        registry.upsert("tenant_reg", "Reg Insurance", "reg"); // idempotent on schema_name

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("SET search_path TO public");
        Integer rows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenants WHERE schema_name = 'tenant_reg'", Integer.class);
        assertThat(rows).isEqualTo(1);

        List<String> active = registry.findActiveSchemas();
        assertThat(active).contains("tenant_reg");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantRegistryIT`
Expected: FAIL to compile — `TenantRegistry` does not exist.

- [ ] **Step 3: Implement `TenantRegistry`**

```java
package com.nubeero.cia.api.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * JdbcTemplate access to the public.tenants registry (V1). No JPA entity exists for this table;
 * it is shared infrastructure, queried directly against the public schema.
 */
@Slf4j
@Component
public class TenantRegistry {

    private final JdbcTemplate jdbc;

    public TenantRegistry(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Upsert keyed on schema_name; safe to call on every boot. */
    public void upsert(String schema, String displayName, String subdomain) {
        jdbc.execute("SET search_path TO public");
        jdbc.update("""
            INSERT INTO tenants (id, schema_name, name, subdomain, active, created_at, updated_at)
            VALUES (gen_random_uuid(), ?, ?, ?, TRUE, NOW(), NOW())
            ON CONFLICT (schema_name) DO UPDATE
              SET name = EXCLUDED.name, subdomain = EXCLUDED.subdomain, updated_at = NOW()
            """, schema, displayName, subdomain);
        log.info("Tenant registry upserted: schema={} subdomain={}", schema, subdomain);
    }

    /** All active tenant schema names — the set swept and re-migrated on every boot. */
    public List<String> findActiveSchemas() {
        jdbc.execute("SET search_path TO public");
        return jdbc.queryForList(
            "SELECT schema_name FROM tenants WHERE active = TRUE ORDER BY schema_name", String.class);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantRegistryIT`
Expected: PASS — one row after two upserts; `findActiveSchemas` contains `tenant_reg`.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantRegistry.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantRegistryIT.java
git commit -m "feat(tenant-provisioning): public.tenants registry (upsert + active sweep)"
```

---

## Task 5: `FirstAdminSpec` + extend `KeycloakTenantProvisioner` with `ensureRealmRoles`

**Files:**
- Create: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/FirstAdminSpec.java`
- Modify: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/KeycloakFirstAdminProvisioningIT.java`

> Read `KeycloakTenantProvisioner.java` and `KeycloakItSupport.java` in full before editing — match the existing `keycloak.getIfAvailable()` acquisition, the try-get/catch-`NotFoundException`-create idempotency style, and the INFO-on-change / DEBUG-on-no-op logging convention.

- [ ] **Step 1: Create `FirstAdminSpec`**

```java
package com.nubeero.cia.setup.keycloak;

import java.util.UUID;

/** Parameters for the bootstrap first-admin user created in a freshly provisioned tenant realm. */
public record FirstAdminSpec(
        String username,
        String email,
        String firstName,
        String lastName,
        String tempPassword,
        UUID accessGroupId) {
}
```

- [ ] **Step 2: Write the failing Keycloak IT (roles half)**

```java
package com.nubeero.cia.api.keycloak;

import com.nubeero.cia.setup.keycloak.BootstrapRoles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakFirstAdminProvisioningIT extends KeycloakItSupport {

    @BeforeAll
    static void provision() {
        ensureTestRealm(); // creates realm + back-office client via the production provisioner
    }

    @Test
    void ensureRealmRolesCreatesEveryCanonicalRole() {
        Keycloak admin = adminClient();                 // exposed by KeycloakItSupport (see step 3 note)
        newProvisioner(admin).ensureRealmRoles(admin, TEST_REALM);

        var roleNames = admin.realm(TEST_REALM).roles().list().stream()
            .map(r -> r.getName()).toList();
        assertThat(roleNames).containsAll(BootstrapRoles.ALL);
    }
}
```

> `adminClient()` and `newProvisioner(...)` helpers: if `KeycloakItSupport` does not already expose the `Keycloak` admin client and a provisioner factory, add small protected helpers to it in this step (the harness already builds the admin client in `pollUntilAdminReady()` and constructs a `KeycloakTenantProvisioner` in `ensureTestRealm()` — extract them to `protected static Keycloak adminClient()` and `protected static KeycloakTenantProvisioner newProvisioner(Keycloak k)` reusing the existing `KeycloakAdminProperties` setup).

- [ ] **Step 3: Run to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=KeycloakFirstAdminProvisioningIT`
Expected: FAIL to compile — `ensureRealmRoles` (and possibly the harness helpers) do not exist.

- [ ] **Step 4: Implement `ensureRealmRoles` in `KeycloakTenantProvisioner`**

Add this method (and the import for `BootstrapRoles`, `RoleRepresentation`, `NotFoundException`). Follow the existing `ensure*` idempotency style:

```java
    /**
     * Idempotently create every canonical bootstrap realm role (BootstrapRoles.ALL). Pattern-A roles
     * get a "CIA-managed:" description to match KeycloakRealmRoleSyncer's convention; Pattern-B roles
     * (FINANCE_*, PLATFORM_ADMIN) are created as plain hardcoded roles.
     */
    public void ensureRealmRoles(Keycloak client, String realmName) {
        var roles = client.realm(realmName).roles();
        for (String roleName : com.nubeero.cia.setup.keycloak.BootstrapRoles.ALL) {
            try {
                roles.get(roleName).toRepresentation();   // exists -> no-op
                log.debug("Tenant realm '{}' — role '{}' already present", realmName, roleName);
            } catch (jakarta.ws.rs.NotFoundException nfe) {
                org.keycloak.representations.idm.RoleRepresentation rep =
                    new org.keycloak.representations.idm.RoleRepresentation();
                rep.setName(roleName);
                rep.setDescription("CIA-managed: bootstrap role");
                roles.create(rep);
                log.info("Tenant realm '{}' — created role '{}'", realmName, roleName);
            }
        }
    }
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=KeycloakFirstAdminProvisioningIT`
Expected: PASS — every `BootstrapRoles.ALL` role is present in the test realm.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/FirstAdminSpec.java \
        cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/KeycloakItSupport.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/KeycloakFirstAdminProvisioningIT.java
git commit -m "feat(tenant-provisioning): ensureRealmRoles creates canonical bootstrap roles"
```

---

## Task 6: `ensureFirstAdminUser` + `provisionTenantAuth`

**Files:**
- Modify: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/KeycloakFirstAdminProvisioningIT.java` (add a test)

- [ ] **Step 1: Add the failing first-admin test**

Append to `KeycloakFirstAdminProvisioningIT`:

```java
    @Test
    void ensureFirstAdminUserCreatesUserWithTempPasswordAndAllRoles() {
        Keycloak admin = adminClient();
        var provisioner = newProvisioner(admin);
        provisioner.ensureRealmRoles(admin, TEST_REALM);

        var spec = new com.nubeero.cia.setup.keycloak.FirstAdminSpec(
            "bootadmin", "bootadmin@acme.example", "Boot", "Admin", "Temp!Pass123",
            java.util.UUID.fromString("22222222-2222-2222-2222-222222222222"));
        provisioner.ensureFirstAdminUser(admin, TEST_REALM, spec);
        provisioner.ensureFirstAdminUser(admin, TEST_REALM, spec); // idempotent — no duplicate user

        var users = admin.realm(TEST_REALM).users().search("bootadmin", true);
        assertThat(users).hasSize(1);
        var user = users.get(0);
        assertThat(user.getRequiredActions()).contains("UPDATE_PASSWORD");
        assertThat(user.getAttributes().get("accessGroupId"))
            .containsExactly("22222222-2222-2222-2222-222222222222");

        var assigned = admin.realm(TEST_REALM).users().get(user.getId())
            .roles().realmLevel().listEffective().stream().map(r -> r.getName()).toList();
        assertThat(assigned).containsAll(com.nubeero.cia.setup.keycloak.BootstrapRoles.ALL);
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=KeycloakFirstAdminProvisioningIT#ensureFirstAdminUserCreatesUserWithTempPasswordAndAllRoles`
Expected: FAIL to compile — `ensureFirstAdminUser` does not exist.

- [ ] **Step 3: Implement `ensureFirstAdminUser` + `provisionTenantAuth`**

```java
    /**
     * Idempotently create the bootstrap first-admin user: temporary password (forces UPDATE_PASSWORD
     * on first login), accessGroupId attribute, and every canonical realm role assigned directly.
     * Mirrors UserService.create but uses a temp password instead of an email action (no SMTP
     * dependency at first boot).
     */
    public void ensureFirstAdminUser(Keycloak client, String realmName, FirstAdminSpec spec) {
        var realm = client.realm(realmName);
        var existing = realm.users().search(spec.username(), true);
        if (!existing.isEmpty()) {
            log.debug("Tenant realm '{}' — first-admin '{}' already present", realmName, spec.username());
            return;
        }

        var rep = new org.keycloak.representations.idm.UserRepresentation();
        rep.setUsername(spec.username());
        rep.setEmail(spec.email());
        rep.setFirstName(spec.firstName());
        rep.setLastName(spec.lastName());
        rep.setEnabled(true);
        rep.setEmailVerified(false);
        rep.setRequiredActions(java.util.List.of("UPDATE_PASSWORD"));
        rep.setAttributes(java.util.Map.of(
            "accessGroupId", java.util.List.of(spec.accessGroupId().toString())));

        var cred = new org.keycloak.representations.idm.CredentialRepresentation();
        cred.setType(org.keycloak.representations.idm.CredentialRepresentation.PASSWORD);
        cred.setValue(spec.tempPassword());
        cred.setTemporary(true);                       // forces reset on first login
        rep.setCredentials(java.util.List.of(cred));

        String userId;
        try (jakarta.ws.rs.core.Response resp = realm.users().create(rep)) {
            if (resp.getStatus() >= 300) {
                throw new IllegalStateException(
                    "Keycloak first-admin create returned HTTP " + resp.getStatus()
                    + " for realm " + realmName);
            }
            String location = resp.getHeaderString("Location");
            userId = location.substring(location.lastIndexOf('/') + 1);
        }

        var realmRoles = BootstrapRoles.ALL.stream()
            .map(name -> realm.roles().get(name).toRepresentation())
            .toList();
        realm.users().get(userId).roles().realmLevel().add(realmRoles);
        log.info("Tenant realm '{}' — created first-admin '{}' with {} roles",
            realmName, spec.username(), realmRoles.size());
    }

    /** Full auth-plane provisioning for one tenant: realm + client + roles + first admin. */
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
```

> Note: `provisionTenantAuth` **throws** if the admin client is unavailable (fail-fast for the new provisioning path), unlike the existing `provisionTenantRealm` which logs+returns. Keep both — the legacy bootstrap path stays lenient; the new provisioning path is strict.

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=KeycloakFirstAdminProvisioningIT`
Expected: PASS (both tests) — user created once, has `UPDATE_PASSWORD`, `accessGroupId` attribute, and all `BootstrapRoles.ALL` effective.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/KeycloakTenantProvisioner.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/keycloak/KeycloakFirstAdminProvisioningIT.java
git commit -m "feat(tenant-provisioning): ensureFirstAdminUser (temp password + roles) + provisionTenantAuth"
```

---

## Task 7: `TenantBootstrapProperties` — config binding

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantBootstrapProperties.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantBootstrapPropertiesTest.java`

- [ ] **Step 1: Write the failing binding test**

```java
package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBootstrapPropertiesTest {

    @Test
    void bindsEnabledAndTenantList() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("cia.tenants.bootstrap.enabled", "true");
        env.setProperty("cia.tenants.bootstrap.tenants[0].schema", "tenant_acme");
        env.setProperty("cia.tenants.bootstrap.tenants[0].realm", "tenant_acme");
        env.setProperty("cia.tenants.bootstrap.tenants[0].display-name", "Acme");
        env.setProperty("cia.tenants.bootstrap.tenants[0].subdomain", "acme");
        env.setProperty("cia.tenants.bootstrap.tenants[0].admin-username", "admin");
        env.setProperty("cia.tenants.bootstrap.tenants[0].admin-email", "admin@acme.example");
        env.setProperty("cia.tenants.bootstrap.tenants[0].admin-temp-password", "Temp!123");

        TenantBootstrapProperties props = new Binder(
            ConfigurationPropertySources.get(env))
            .bind("cia.tenants.bootstrap", TenantBootstrapProperties.class)
            .get();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getTenants()).hasSize(1);
        assertThat(props.getTenants().get(0).getSchema()).isEqualTo("tenant_acme");
        assertThat(props.getTenants().get(0).getAdminTempPassword()).isEqualTo("Temp!123");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantBootstrapPropertiesTest`
Expected: FAIL to compile — `TenantBootstrapProperties` does not exist.

- [ ] **Step 3: Implement `TenantBootstrapProperties`**

```java
package com.nubeero.cia.api.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Bootstrap tenant declarations. {@code enabled=false} by default → runner is a no-op. */
@Data
@Configuration
@ConfigurationProperties(prefix = "cia.tenants.bootstrap")
public class TenantBootstrapProperties {

    private boolean enabled = false;
    private List<TenantSpec> tenants = new ArrayList<>();

    @Data
    public static class TenantSpec {
        private String schema;
        private String realm;
        private String displayName;
        private String subdomain;
        private String adminUsername;
        private String adminEmail;
        private String adminTempPassword;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantBootstrapPropertiesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantBootstrapProperties.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantBootstrapPropertiesTest.java
git commit -m "feat(tenant-provisioning): TenantBootstrapProperties config binding"
```

---

## Task 8: `TenantProvisioningService` — orchestrator

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantProvisioningService.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantProvisioningServiceIT.java`

- [ ] **Step 1: Write the failing orchestrator IT**

This IT exercises the data-plane end-to-end with a **fake** `KeycloakTenantProvisioner` collaborator (the Keycloak path is covered by Task 6's live IT; here we verify orchestration + the UUID handoff without a Keycloak container).

```java
package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TenantProvisioningServiceIT extends TenantProvisioningItSupport {

    @BeforeEach
    void ensureRegistry() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("SET search_path TO public");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tenants (
              id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              schema_name VARCHAR(63) NOT NULL UNIQUE,
              name VARCHAR(255) NOT NULL,
              subdomain VARCHAR(63) NOT NULL UNIQUE,
              active BOOLEAN NOT NULL DEFAULT TRUE,
              created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
              updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )""");
    }

    @Test
    void provisionRunsAllStepsAndThreadsAdminGroupIdToKeycloak() {
        var migrator = new TenantSchemaMigrator(dataSource());
        var seeder = new TenantSeeder(dataSource());
        var registry = new TenantRegistry(dataSource());
        var keycloak = mock(KeycloakTenantProvisioner.class);
        AtomicReference<UUID> keycloakGroupId = new AtomicReference<>();
        doAnswer(inv -> {
            FirstAdminSpec spec = inv.getArgument(1);
            keycloakGroupId.set(spec.accessGroupId());
            return null;
        }).when(keycloak).provisionTenantAuth(anyString(), any(FirstAdminSpec.class));

        var service = new TenantProvisioningService(migrator, seeder, registry, keycloak);
        var spec = new TenantBootstrapProperties.TenantSpec();
        spec.setSchema("tenant_orch"); spec.setRealm("tenant_orch");
        spec.setDisplayName("Orch"); spec.setSubdomain("orch");
        spec.setAdminUsername("admin"); spec.setAdminEmail("admin@orch.example");
        spec.setAdminTempPassword("Temp!123");

        service.provision(spec);

        // Schema migrated + seeded.
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("SET search_path TO tenant_orch");
        UUID seededGroupId = jdbc.queryForObject(
            "SELECT id FROM access_groups WHERE name = 'Administrators'", UUID.class);
        // Registry row written.
        jdbc.execute("SET search_path TO public");
        Integer regRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM tenants WHERE schema_name = 'tenant_orch'", Integer.class);
        assertThat(regRows).isEqualTo(1);
        // The SAME admin-group UUID flowed to Keycloak.
        verify(keycloak).provisionTenantAuth(eq("tenant_orch"), any(FirstAdminSpec.class));
        assertThat(keycloakGroupId.get()).isEqualTo(seededGroupId);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantProvisioningServiceIT`
Expected: FAIL to compile — `TenantProvisioningService` does not exist.

- [ ] **Step 3: Implement `TenantProvisioningService`**

```java
package com.nubeero.cia.api.tenant;

import com.nubeero.cia.setup.keycloak.FirstAdminSpec;
import com.nubeero.cia.setup.keycloak.KeycloakTenantProvisioner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates per-tenant provisioning: schema → migrate → seed → Keycloak (realm/roles/admin) →
 * registry. Generates the Administrators access-group UUID up front and threads it into both the
 * DB seed and the Keycloak admin attribute. Every step is idempotent; any failure propagates
 * (fail-fast — the caller aborts startup).
 */
@Slf4j
@Service
public class TenantProvisioningService {

    private final TenantSchemaMigrator migrator;
    private final TenantSeeder seeder;
    private final TenantRegistry registry;
    private final KeycloakTenantProvisioner keycloak;

    public TenantProvisioningService(TenantSchemaMigrator migrator, TenantSeeder seeder,
                                     TenantRegistry registry, KeycloakTenantProvisioner keycloak) {
        this.migrator = migrator;
        this.seeder = seeder;
        this.registry = registry;
        this.keycloak = keycloak;
    }

    public void provision(TenantBootstrapProperties.TenantSpec spec) {
        String schema = spec.getSchema();
        String realm = spec.getRealm() != null ? spec.getRealm() : schema;
        UUID adminGroupId = deterministicAdminGroupId(schema);
        log.info("Provisioning tenant: schema={} realm={}", schema, realm);

        migrator.ensureSchema(schema);
        migrator.migrate(schema);
        seeder.seed(schema, adminGroupId);

        keycloak.provisionTenantAuth(realm, new FirstAdminSpec(
            spec.getAdminUsername(), spec.getAdminEmail(),
            "Tenant", "Administrator",
            spec.getAdminTempPassword(), adminGroupId));

        registry.upsert(schema, spec.getDisplayName(), spec.getSubdomain());
        log.info("Tenant '{}' provisioned", schema);
    }

    /** Stable per-schema UUID so re-runs target the same Administrators access group row. */
    private static UUID deterministicAdminGroupId(String schema) {
        return UUID.nameUUIDFromBytes(("admin-group::" + schema).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantProvisioningServiceIT`
Expected: PASS — schema migrated+seeded, registry row written, and the admin-group UUID seeded into the DB equals the one passed to `provisionTenantAuth`.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantProvisioningService.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantProvisioningServiceIT.java
git commit -m "feat(tenant-provisioning): orchestrator service (schema->migrate->seed->keycloak->registry)"
```

---

## Task 9: `TenantBootstrapRunner` — gated ApplicationRunner + registry sweep

**Files:**
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantBootstrapRunner.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantBootstrapRunnerTest.java`

- [ ] **Step 1: Write the failing unit test (runner behaviour, mocked collaborators)**

```java
package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;

import static org.mockito.Mockito.*;

class TenantBootstrapRunnerTest {

    @Test
    void provisionsConfiguredTenantsThenSweepsActiveRegistry() throws Exception {
        var props = new TenantBootstrapProperties();
        props.setEnabled(true);
        var spec = new TenantBootstrapProperties.TenantSpec();
        spec.setSchema("tenant_one"); spec.setRealm("tenant_one");
        spec.setDisplayName("One"); spec.setSubdomain("one");
        spec.setAdminUsername("admin"); spec.setAdminEmail("a@one.example");
        spec.setAdminTempPassword("T!1");
        props.setTenants(List.of(spec));

        var service = mock(TenantProvisioningService.class);
        var migrator = mock(TenantSchemaMigrator.class);
        var registry = mock(TenantRegistry.class);
        when(registry.findActiveSchemas()).thenReturn(List.of("tenant_one", "tenant_two"));

        var runner = new TenantBootstrapRunner(props, service, migrator, registry);
        runner.run(new DefaultApplicationArguments());

        verify(service).provision(spec);                       // config tenant provisioned
        verify(migrator).migrate("tenant_one");                // sweep migrates every active schema
        verify(migrator).migrate("tenant_two");
    }

    @Test
    void failFastPropagatesProvisioningError() {
        var props = new TenantBootstrapProperties();
        props.setEnabled(true);
        var spec = new TenantBootstrapProperties.TenantSpec();
        spec.setSchema("tenant_bad"); spec.setRealm("tenant_bad");
        spec.setAdminUsername("admin"); spec.setAdminEmail("a@bad.example");
        spec.setAdminTempPassword("T!1"); spec.setDisplayName("Bad"); spec.setSubdomain("bad");
        props.setTenants(List.of(spec));

        var service = mock(TenantProvisioningService.class);
        doThrow(new IllegalStateException("boom")).when(service).provision(spec);
        var migrator = mock(TenantSchemaMigrator.class);
        var registry = mock(TenantRegistry.class);

        var runner = new TenantBootstrapRunner(props, service, migrator, registry);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> runner.run(new DefaultApplicationArguments()))
            .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantBootstrapRunnerTest`
Expected: FAIL to compile — `TenantBootstrapRunner` does not exist.

- [ ] **Step 3: Implement `TenantBootstrapRunner`**

```java
package com.nubeero.cia.api.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Ensures the configured bootstrap tenants exist, then sweeps every active tenant in the registry
 * and re-migrates it — making "all migrations run against every schema on startup" true. Gated by
 * cia.tenants.bootstrap.enabled (default false), so existing ITs and local dev are unaffected.
 * Fail-fast: any failure propagates and aborts application startup.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cia.tenants.bootstrap", name = "enabled", havingValue = "true")
public class TenantBootstrapRunner implements ApplicationRunner {

    private final TenantBootstrapProperties props;
    private final TenantProvisioningService provisioningService;
    private final TenantSchemaMigrator migrator;
    private final TenantRegistry registry;

    public TenantBootstrapRunner(TenantBootstrapProperties props,
                                 TenantProvisioningService provisioningService,
                                 TenantSchemaMigrator migrator,
                                 TenantRegistry registry) {
        this.props = props;
        this.provisioningService = provisioningService;
        this.migrator = migrator;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Tenant bootstrap: ensuring {} configured tenant(s)", props.getTenants().size());
        for (TenantBootstrapProperties.TenantSpec spec : props.getTenants()) {
            provisioningService.provision(spec);
        }
        for (String schema : registry.findActiveSchemas()) {
            log.info("Tenant bootstrap: re-migrating registered schema '{}'", schema);
            migrator.migrate(schema);
        }
        log.info("Tenant bootstrap complete");
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantBootstrapRunnerTest`
Expected: PASS — both tests.

- [ ] **Step 5: Add the gating context test**

Create `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantBootstrapRunnerGatingTest.java`:

```java
package com.nubeero.cia.api.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TenantBootstrapRunnerGatingTest {

    @Test
    void runnerBeanAbsentWhenDisabled() {
        new ApplicationContextRunner()
            .withUserConfiguration(TenantBootstrapRunner.class)
            // no cia.tenants.bootstrap.enabled property → default false
            .run(ctx -> assertThat(ctx).doesNotHaveBean(TenantBootstrapRunner.class));
    }
}
```

- [ ] **Step 6: Run the gating test**

Run: `cd cia-backend && ./mvnw -q -pl cia-api test -Dtest=TenantBootstrapRunnerGatingTest`
Expected: PASS — the runner is excluded from the context when the flag is unset (this is what protects the existing IT baseline).

- [ ] **Step 7: Commit**

```bash
git add cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantBootstrapRunner.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantBootstrapRunnerTest.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/TenantBootstrapRunnerGatingTest.java
git commit -m "feat(tenant-provisioning): gated TenantBootstrapRunner + registry sweep + fail-fast"
```

---

## Task 10: Wiring config, full reactor verify, and documentation

**Files:**
- Modify: `cia-backend/cia-api/src/main/resources/application.yml`
- Modify: `CLAUDE.md`, `cia-log.md`
- Modify: docs-site files per the §9 gate.

- [ ] **Step 1: Add the config block to `application.yml`**

Add under the `cia:` root (keep `enabled: false` so the default profile/dev/ITs are unaffected):

```yaml
  tenants:
    bootstrap:
      # Master switch — OFF by default so dev + the IT suite never provision tenants.
      # Set CIA_TENANT_BOOTSTRAP_ENABLED=true in a real deployment.
      enabled: ${CIA_TENANT_BOOTSTRAP_ENABLED:false}
      tenants: []
      # Example (supply via the deployment manifest / secret store):
      # tenants:
      #   - schema: tenant_acme
      #     realm: tenant_acme
      #     display-name: "Acme Insurance"
      #     subdomain: acme
      #     admin-username: admin
      #     admin-email: admin@acme.example
      #     admin-temp-password: ${ACME_ADMIN_TEMP_PASSWORD}
```

- [ ] **Step 2: Run the FULL reactor verify (regression gate)**

Run: `cd cia-backend && ./mvnw verify --batch-mode --no-transfer-progress`
Expected: BUILD SUCCESS. The new ITs pass; the existing IT baseline is unchanged (the runner is gated off, so no test provisions tenants). If any pre-existing IT now fails, investigate before proceeding — the gating flag should make this impossible, so a failure means a bean leaked into the default context.

- [ ] **Step 3: Commit the config**

```bash
git add cia-backend/cia-api/src/main/resources/application.yml
git commit -m "feat(tenant-provisioning): application.yml cia.tenants.bootstrap block (off by default)"
```

- [ ] **Step 4: Update CLAUDE.md**

- §5.4 (New Tenant Provisioning): replace the aspirational description with the real flow — gated `TenantBootstrapRunner` → `TenantProvisioningService` (schema → Flyway-per-schema baselined past V1 → seed → Keycloak realm/roles/first-admin → `public.tenants` upsert) → boot-time active-registry sweep.
- §6 (Multi-Tenancy): correct "migrations run against every schema on startup" to reference the real registry sweep; note `public` is the system/registry schema and `template_` is vestigial.
- Environment Variables table: add `CIA_TENANT_BOOTSTRAP_ENABLED` and the per-tenant `*_ADMIN_TEMP_PASSWORD` secret convention.

- [ ] **Step 5: Update cia-log.md**

- New `## 2026-06-02 — Session NNN` entry: files created/modified, the design/decisions (Q1–Q5), and the V2-RESET search_path callback discovery.
- Backlog reconciliation: **drain/replace** `keycloak-seed-admin-user` (P3) — superseded by the delivered first-admin provisioning; note `prod-deployability-k8s-manifests` advances (runtime-provisioning half done, k8s half remains = Slice B). Add any follow-up surfaced (e.g. REST admin provisioning API; tenant de-provisioning).

- [ ] **Step 6: Update docs-site (§9 gate)**

- `docs-site/docs/guides/tenant-provisioning.md`: rewrite to match the real runner-driven flow (remove the fictional `POST /admin/v1/tenants`).
- `docs-site/docs/guides/environment-variables.md`: add `CIA_TENANT_BOOTSTRAP_ENABLED`.
- `docs-site/docs/architecture/security.md` and/or `database-migrations.md`: document the Flyway-per-schema baseline + search_path callback.

- [ ] **Step 7: Commit docs**

```bash
git add CLAUDE.md cia-log.md docs-site/
git commit -m "docs(tenant-provisioning): correct §5.4/§6, env vars, tenant-provisioning guide; backlog reconcile"
```

- [ ] **Step 8: Final branch verify + push**

```bash
cd cia-backend && ./mvnw verify --batch-mode --no-transfer-progress
# then, from repo root, push the feature branch (do NOT merge to main without review):
git push -u origin slice-a-tenant-provisioning
```

---

## Self-Review (completed by plan author)

**Spec coverage:**
- §4 architecture → Tasks 2–9 (every named component has a task). ✓
- §5 sequence → Task 8 orchestrator (exact order: schema→migrate→seed→keycloak→registry). ✓
- §6 Flyway-per-schema + baselineVersion + search_path callback → Task 2 (+ empirical IT assertion). ✓
- §6a canonical roles + drift guard → Tasks 1, 5, 6. ✓
- §7 seed (access group + perms, NGN, customer-number-format; no users; no policy-number) → Task 3. ✓
- §8 config + gating → Tasks 7, 9, 10. ✓
- §9 fail-fast + idempotency → Tasks 2–4 (idempotent re-runs asserted), Task 9 (`failFastPropagatesProvisioningError`). ✓
- §10 testing strategy → every unit has an IT/test; gating test present. ✓
- §12 doc reconciliation → Task 10. ✓

**Placeholder scan:** No "TBD"/"add validation"/"similar to Task N" — every code step shows full code. The two flagged judgement points (`ADMIN_PERMISSIONS` underscore→colon for multi-word modules in Task 1; harness helper extraction in Task 5) are explicit, actionable notes with the exact resolution path, not placeholders.

**Type consistency:** `FirstAdminSpec(username,email,firstName,lastName,tempPassword,accessGroupId)` is defined in Task 5 and used identically in Tasks 6 and 8. `provisionTenantAuth(String, FirstAdminSpec)` defined in Task 6, called in Task 8, mocked in Task 8's IT. `TenantBootstrapProperties.TenantSpec` getters used consistently in Tasks 7–9. `migrate(String)` / `ensureSchema(String)` / `seed(String,UUID)` / `upsert(String,String,String)` / `findActiveSchemas()` signatures consistent across definition and call sites. ✓
