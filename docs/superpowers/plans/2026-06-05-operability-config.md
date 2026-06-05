# Slice C — Production Operability Config — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the single Spring Boot binary production-operable — a thin `application-prod.yml`, a tuned single shared HikariCP pool, Prometheus-scrapeable metrics, native ECS structured logs carrying the tenant — and fix the `MultiTenantConnectionProvider` `search_path` so pgcrypto PII encryption resolves for real (non-`public`) tenants.

**Architecture:** Profile-merge — base `application.yml` stays the single source of shared structure; `application-prod.yml` holds only prod deltas + `${ENV_VAR}` placeholders. Metrics via `micrometer-registry-prometheus` + a `MeterRegistryCustomizer` common tag. Structured logs via Spring Boot 3.4+ native `logging.structured.format.console=ecs` (no dependency, no `logback-spring.xml`) plus a 2-line MDC enrichment in the existing `TenantContextFilter`. The pgcrypto fix promotes the `TenantSchemas` validator down to `cia-common` and switches the connection provider from `setSchema(tenant)` to `SET search_path TO "<tenant>", public`.

**Tech Stack:** Java 21, Spring Boot 3.5.14, HikariCP, Micrometer/Prometheus, Hibernate multi-tenant (SCHEMA), PostgreSQL pgcrypto, Testcontainers, JUnit 5 + AssertJ.

**Spec:** `docs/superpowers/specs/2026-06-05-operability-config-design.md`

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `cia-common/src/main/java/com/nubeero/cia/common/tenant/TenantSchemas.java` | Schema-name validator (security boundary) — single canonical copy, reachable by both cia-api and the cia-common connection provider | **create** (promote) |
| `cia-common/src/test/java/com/nubeero/cia/common/tenant/TenantSchemasTest.java` | Unit test for the validator (valid + injection-y inputs) | **create** |
| `cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemas.java` | (old location) | **delete** |
| `cia-api/.../tenant/TenantSeeder.java`, `TenantSchemaMigrator.java` | Add `import com.nubeero.cia.common.tenant.TenantSchemas;` | **modify** |
| `cia-common/src/main/java/com/nubeero/cia/common/tenant/MultiTenantConnectionProvider.java` | `getConnection` sets `search_path` to tenant + public (guarded); `releaseConnection` resets to public | **modify** |
| `cia-api/src/test/java/com/nubeero/cia/api/tenant/MultiTenantConnectionProviderSearchPathIT.java` | Load-bearing IT: pgcrypto round-trip resolves under a non-public tenant | **create** |
| `cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java` | Put/remove `tenant` in MDC alongside `TenantContext` | **modify** |
| `cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterTest.java` | Add MDC set/clear assertion | **modify** |
| `cia-api/pom.xml` | Add `micrometer-registry-prometheus` | **modify** |
| `cia-api/src/main/java/com/nubeero/cia/api/config/MetricsConfig.java` | `MeterRegistryCustomizer` common tag `application=cia-api` | **create** |
| `cia-api/src/test/java/com/nubeero/cia/api/config/MetricsConfigTest.java` | Unit test for the common tag | **create** |
| `cia-api/src/main/resources/application-prod.yml` | Thin prod override (Hikari, logging, actuator) | **create** |
| `cia-api/src/test/java/com/nubeero/cia/api/config/ProdProfileConfigTest.java` | Binding test: Hikari values, ECS logging, actuator exposure | **create** |
| `CLAUDE.md`, `cia-log.md` | §7 single-pool correction, §6 pgcrypto-closed, env table, backlog reconciliation | **modify** |

---

## Task 1: Promote `TenantSchemas` validator to `cia-common`

Pure refactor — move the validator so the cia-common connection provider can reach it (cia-common cannot depend on cia-api). The existing two cia-api callers keep working via a new import.

**Files:**
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/tenant/TenantSchemas.java`
- Create: `cia-backend/cia-common/src/test/java/com/nubeero/cia/common/tenant/TenantSchemasTest.java`
- Delete: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemas.java`
- Modify: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSeeder.java`
- Modify: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemaMigrator.java`

- [ ] **Step 1: Write the failing unit test in cia-common**

Create `cia-backend/cia-common/src/test/java/com/nubeero/cia/common/tenant/TenantSchemasTest.java`:

```java
package com.nubeero.cia.common.tenant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantSchemasTest {

    @Test
    void acceptsValidLowercaseIdentifiers() {
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("tenant_acme"));
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("public"));
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("_x"));
        assertThatNoException().isThrownBy(() -> TenantSchemas.validate("t1"));
    }

    @Test
    void rejectsNullInjectionAndMalformedNames() {
        assertThatThrownBy(() -> TenantSchemas.validate(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("a\"; drop schema x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("has space"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("1leading"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("Upper"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantSchemas.validate("x".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (class not yet in cia-common)**

Run: `cd cia-backend && mvn -q -pl cia-common test -Dtest=TenantSchemasTest`
Expected: COMPILE FAILURE — `cannot find symbol: class TenantSchemas` (the class only exists in cia-api).

- [ ] **Step 3: Create the validator in cia-common**

Create `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/tenant/TenantSchemas.java`:

```java
package com.nubeero.cia.common.tenant;

/** Validation for tenant schema names — a security boundary (names are interpolated into DDL/search_path). */
public final class TenantSchemas {
    private TenantSchemas() {}

    private static final java.util.regex.Pattern VALID =
        java.util.regex.Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /** Throws IllegalArgumentException unless schema is a safe lowercase identifier (Postgres ≤63 chars). */
    public static void validate(String schema) {
        if (schema == null || !VALID.matcher(schema).matches()) {
            throw new IllegalArgumentException("Illegal tenant schema name: " + schema);
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-common test -Dtest=TenantSchemasTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Delete the old cia-api copy and re-point its callers**

Delete `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemas.java`:

```bash
git rm cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemas.java
```

In `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSeeder.java`, add the import (the class is in package `com.nubeero.cia.api.tenant`, so it previously resolved `TenantSchemas` with no import). Add directly after the existing `package com.nubeero.cia.api.tenant;` line's import block — insert:

```java
import com.nubeero.cia.common.tenant.TenantSchemas;
```

In `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemaMigrator.java`, add the same import:

```java
import com.nubeero.cia.common.tenant.TenantSchemas;
```

(Both files keep their existing `TenantSchemas.validate(schema)` call sites unchanged — only the import is added.)

- [ ] **Step 6: Compile cia-api to verify the callers resolve the moved class**

Run: `cd cia-backend && mvn -q -pl cia-api -am test-compile`
Expected: BUILD SUCCESS (no `cannot find symbol: TenantSchemas`).

- [ ] **Step 7: Run the existing Slice A migrator IT to prove the refactor is behaviour-preserving**

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=TenantSchemaMigratorIT -DfailIfNoTests=false`
Expected: PASS (the provisioning IT still creates multiple tenant schemas).

- [ ] **Step 8: Commit**

```bash
git add cia-backend/cia-common/src/main/java/com/nubeero/cia/common/tenant/TenantSchemas.java \
        cia-backend/cia-common/src/test/java/com/nubeero/cia/common/tenant/TenantSchemasTest.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSeeder.java \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemaMigrator.java
git rm cia-backend/cia-api/src/main/java/com/nubeero/cia/api/tenant/TenantSchemas.java
git commit -m "refactor(tenant): promote TenantSchemas validator to cia-common

Connection provider in cia-common needs the schema-name guard for the
search_path fix; cia-common cannot depend on cia-api. Move the canonical
copy down, re-point the two cia-api callers via import. Behaviour-preserving."
```

---

## Task 2: Fix `MultiTenantConnectionProvider` search_path (pgcrypto deploy-gate)

Switch `getConnection` from `setSchema(tenant)` (which makes PG's `search_path` = tenant only) to `SET search_path TO "<tenant>", public`, so pgcrypto functions living in `public` resolve at runtime for every tenant. Guard the interpolated identifier with the now-shared `TenantSchemas.validate`.

**Files:**
- Modify: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/tenant/MultiTenantConnectionProvider.java`
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/MultiTenantConnectionProviderSearchPathIT.java`

- [ ] **Step 1: Write the failing load-bearing IT**

Create `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/MultiTenantConnectionProviderSearchPathIT.java`:

```java
package com.nubeero.cia.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.common.tenant.MultiTenantConnectionProvider;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the runtime-pgcrypto-search-path fix: pgcrypto lives in {@code public}
 * (installed there by the tenant migrator), and a real tenant connection must be
 * able to call {@code pgp_sym_*} even though its primary schema is the tenant's.
 * Before the fix ({@code setSchema(tenant)} → search_path = tenant only) this IT
 * fails with "function pgp_sym_encrypt does not exist".
 */
class MultiTenantConnectionProviderSearchPathIT extends TenantProvisioningItSupport {

    private static final String TENANT = "tenant_pgcrypto_it";

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT);
            st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        }
    }

    @Test
    void pgcryptoResolvesForNonPublicTenant() throws Exception {
        MultiTenantConnectionProvider provider = new MultiTenantConnectionProvider(dataSource());
        Connection conn = provider.getConnection(TENANT);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT pgp_sym_decrypt(pgp_sym_encrypt('secret','k'),'k')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("secret");
        } finally {
            provider.releaseConnection(TENANT, conn);
        }
    }

    @Test
    void searchPathIsTenantThenPublic() throws Exception {
        MultiTenantConnectionProvider provider = new MultiTenantConnectionProvider(dataSource());
        Connection conn = provider.getConnection(TENANT);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SHOW search_path")) {
            assertThat(rs.next()).isTrue();
            String searchPath = rs.getString(1);
            assertThat(searchPath).contains(TENANT).contains("public");
        } finally {
            provider.releaseConnection(TENANT, conn);
        }
    }
}
```

- [ ] **Step 2: Run the IT to verify it fails (current provider uses setSchema-only)**

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=MultiTenantConnectionProviderSearchPathIT -DfailIfNoTests=false`
Expected: FAIL — `pgcryptoResolvesForNonPublicTenant` errors with `function pgp_sym_encrypt(...) does not exist` and `searchPathIsTenantThenPublic` shows the path lacks `public`.

- [ ] **Step 3: Apply the search_path fix**

Replace the whole body of `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/tenant/MultiTenantConnectionProvider.java` with:

```java
package com.nubeero.cia.common.tenant;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class MultiTenantConnectionProvider
        implements org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    public MultiTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String tenantIdentifier) throws SQLException {
        // Security boundary: the identifier is interpolated into the SET statement
        // (schema names cannot be bound as JDBC parameters). It originates from the
        // validated JWT realm, but we validate again for defence-in-depth.
        TenantSchemas.validate(tenantIdentifier);
        Connection connection = getAnyConnection();
        try (Statement st = connection.createStatement()) {
            // Tenant schema first so its tables resolve; public last so shared
            // extensions (pgcrypto: pgp_sym_encrypt/decrypt) and the registry
            // resolve for every tenant. setSchema(tenant) alone would set the
            // search_path to the tenant only, breaking NDPR PII encryption.
            st.execute("SET search_path TO \"" + tenantIdentifier + "\", public");
        }
        return connection;
    }

    @Override
    public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("SET search_path TO public");
        }
        releaseAnyConnection(connection);
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> unwrapType) {
        throw new UnsupportedOperationException("Cannot unwrap as " + unwrapType.getName());
    }
}
```

- [ ] **Step 4: Run the IT to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api -am verify -Dit.test=MultiTenantConnectionProviderSearchPathIT -DfailIfNoTests=false`
Expected: PASS (2 tests) — pgcrypto round-trip returns `secret`; search_path contains both the tenant and `public`.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-common/src/main/java/com/nubeero/cia/common/tenant/MultiTenantConnectionProvider.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/tenant/MultiTenantConnectionProviderSearchPathIT.java
git commit -m "fix(tenant): set search_path to tenant + public so pgcrypto resolves

Closes runtime-pgcrypto-search-path (P1). setSchema(tenant) set the
search_path to the tenant schema only; pgcrypto lives in public, so
pgp_sym_encrypt/decrypt (NDPR PII @ColumnTransformer) were unreachable for
any non-public tenant. Now SET search_path TO \"<tenant>\", public, guarded
by TenantSchemas.validate. Testcontainers IT proves the round-trip."
```

---

## Task 3: Tenant MDC enrichment in `TenantContextFilter`

Put the resolved tenant into the SLF4J MDC so the ECS structured logs (Task 5) carry `tenant` on every request-scoped line. Two lines + a constant.

**Files:**
- Modify: `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java`
- Modify: `cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterTest.java`

- [ ] **Step 1: Add the failing MDC test**

In `cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterTest.java`, add the import `import org.slf4j.MDC;` (alongside the existing imports) and append this test method inside the class (before the closing brace):

```java
    @Test
    @DisplayName("puts tenant into MDC during the chain and removes it after")
    void mdcTenantSetDuringChainAndClearedAfter() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            jwtWith("http://localhost:8280/realms/acme", null)));
        String[] mdcDuring = new String[1];
        FilterChain chain = (req, res) -> mdcDuring[0] = MDC.get("tenant");
        filter.doFilter(mock(HttpServletRequest.class), mock(HttpServletResponse.class), chain);
        assertThat(mdcDuring[0]).isEqualTo("acme");
        assertThat(MDC.get("tenant")).isNull();
    }
```

Also extend the existing `@AfterEach clear()` to defensively clear MDC — change its body to:

```java
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-auth test -Dtest=TenantContextFilterTest`
Expected: FAIL — `mdcTenantSetDuringChainAndClearedAfter` expects `"acme"` but MDC `tenant` is `null` (filter does not set MDC yet).

- [ ] **Step 3: Add MDC put/remove to the filter**

In `cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java`:

Add the import after `import lombok.extern.slf4j.Slf4j;`:

```java
import org.slf4j.MDC;
```

Add a constant at the top of the class body (after the class declaration line `public class TenantContextFilter extends OncePerRequestFilter {`):

```java
    static final String MDC_TENANT_KEY = "tenant";
```

In the `if (tenantId != null && !tenantId.isBlank())` block, add the MDC put right after `TenantContext.setTenantId(tenantId);`:

```java
                    TenantContext.setTenantId(tenantId);
                    MDC.put(MDC_TENANT_KEY, tenantId);
```

In the `finally` block, add the MDC remove after `TenantContext.clear();`:

```java
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT_KEY);
        }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-auth test -Dtest=TenantContextFilterTest`
Expected: PASS (5 tests — the 4 existing + the new MDC test).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-auth/src/main/java/com/nubeero/cia/auth/TenantContextFilter.java \
        cia-backend/cia-auth/src/test/java/com/nubeero/cia/auth/TenantContextFilterTest.java
git commit -m "feat(observability): carry tenant in MDC for structured logs

TenantContextFilter now MDC.put(\"tenant\", id) when it resolves the tenant
and MDC.remove in finally, so native ECS structured logs (prod profile)
include the tenant on every request-scoped line across interleaved replicas."
```

---

## Task 4: Prometheus metrics — dependency + common tag

Add the registry so `/actuator/prometheus` can scrape, and tag every meter with `application=cia-api` so a multi-service Prometheus can distinguish series.

**Files:**
- Modify: `cia-backend/cia-api/pom.xml`
- Create: `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/config/MetricsConfig.java`
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/config/MetricsConfigTest.java`

- [ ] **Step 1: Write the failing unit test**

Create `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/config/MetricsConfigTest.java`:

```java
package com.nubeero.cia.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MetricsConfigTest {

    @Test
    void appliesApplicationCommonTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MetricsConfig().commonTags().customize(registry);

        Counter counter = registry.counter("test.counter");
        assertThat(counter.getId().getTag("application")).isEqualTo("cia-api");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=MetricsConfigTest`
Expected: COMPILE FAILURE — `cannot find symbol: class MetricsConfig`.

- [ ] **Step 3: Add the dependency to `cia-api/pom.xml`**

In `cia-backend/cia-api/pom.xml`, immediately after the `spring-boot-starter-actuator` dependency block (the one ending at the `</dependency>` after `<artifactId>spring-boot-starter-actuator</artifactId>`), add:

```xml
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
```

(No `<version>` — it is managed by the Spring Boot BOM.)

- [ ] **Step 4: Create `MetricsConfig`**

Create `cia-backend/cia-api/src/main/java/com/nubeero/cia/api/config/MetricsConfig.java`:

```java
package com.nubeero.cia.api.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tags every meter with {@code application=cia-api} so a shared Prometheus can
 * distinguish this service's series from others. Applies in all profiles
 * (harmless in dev); the {@code /actuator/prometheus} scrape surface itself is
 * gated by the prod profile's actuator exposure list.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config().commonTags("application", "cia-api");
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=MetricsConfigTest`
Expected: PASS (1 test). The `io.micrometer:micrometer-registry-prometheus` dependency transitively pulls `micrometer-core` (already present via actuator), so `SimpleMeterRegistry` resolves.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-api/pom.xml \
        cia-backend/cia-api/src/main/java/com/nubeero/cia/api/config/MetricsConfig.java \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/config/MetricsConfigTest.java
git commit -m "feat(observability): add Prometheus registry + application common tag

micrometer-registry-prometheus enables /actuator/prometheus (exposed in the
prod profile, Task 5). MetricsConfig tags all meters application=cia-api."
```

---

## Task 5: `application-prod.yml` thin override + binding test

The prod profile delta: tuned single-pool Hikari, ECS structured logging, and actuator exposure that adds `prometheus` while dropping `metrics` from the web surface. Verified by a fast binding test (no container) that asserts the YAML parses, the env-default placeholders resolve, and the exposure list is correct.

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/application-prod.yml`
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/config/ProdProfileConfigTest.java`

- [ ] **Step 1: Write the failing binding test**

Create `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/config/ProdProfileConfigTest.java`:

```java
package com.nubeero.cia.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Validates application-prod.yml without booting the app: the YAML parses, the
 * ${ENV:default} placeholders resolve to their intended defaults, and the
 * actuator exposure list adds prometheus while keeping metrics off the web
 * surface. Binder.get(env) wires a placeholder resolver over the loaded source,
 * so ${DB_POOL_MAX:10} binds to 10 when the env var is absent.
 */
class ProdProfileConfigTest {

    private Binder prodBinder() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources =
            loader.load("application-prod", new ClassPathResource("application-prod.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(env.getPropertySources()::addLast);
        return Binder.get(env);
    }

    @Test
    void hikariTuningBindsWithEnvDefaults() throws Exception {
        Binder b = prodBinder();
        assertThat(b.bind("spring.datasource.hikari.maximum-pool-size", Integer.class).get()).isEqualTo(10);
        assertThat(b.bind("spring.datasource.hikari.minimum-idle", Integer.class).get()).isEqualTo(10);
        assertThat(b.bind("spring.datasource.hikari.max-lifetime", Long.class).get()).isEqualTo(1740000L);
        assertThat(b.bind("spring.datasource.hikari.keepalive-time", Long.class).get()).isEqualTo(300000L);
        assertThat(b.bind("spring.datasource.hikari.leak-detection-threshold", Long.class).get()).isEqualTo(60000L);
        assertThat(b.bind("spring.datasource.hikari.connection-timeout", Long.class).get()).isEqualTo(30000L);
    }

    @Test
    void structuredLoggingIsEcs() throws Exception {
        assertThat(prodBinder().bind("logging.structured.format.console", String.class).get())
            .isEqualTo("ecs");
    }

    @Test
    void actuatorExposesPrometheusNotMetrics() throws Exception {
        String include = prodBinder()
            .bind("management.endpoints.web.exposure.include", String.class).get();
        assertThat(include).contains("prometheus").contains("health").contains("info");
        assertThat(include).doesNotContain("metrics");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (no prod yaml yet)**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=ProdProfileConfigTest`
Expected: FAIL — `IllegalStateException`/`FileNotFoundException` for `application-prod.yml` (resource missing), or a `NoSuchElementException` from `.get()` on an empty binding.

- [ ] **Step 3: Create `application-prod.yml`**

Create `cia-backend/cia-api/src/main/resources/application-prod.yml`:

```yaml
# Production profile — THIN OVERRIDE.
# Holds only the deltas that differ from the base application.yml. Spring merges
# this over the base when SPRING_PROFILES_ACTIVE=prod. Secrets/URLs are already
# ${ENV:default} in the base file and are NOT re-declared here; the deploy
# manifest sets the env vars and ProductionSafetyValidator (armed by
# CIA_DEPLOYMENT_ENVIRONMENT=production) fail-fasts if any weak default survives.
#
# A prod deploy MUST set BOTH:
#   SPRING_PROFILES_ACTIVE=prod          -> loads this file, deactivates DevSecurityConfig
#   CIA_DEPLOYMENT_ENVIRONMENT=production -> arms the secret/profile fail-fast guard
spring:
  datasource:
    hikari:
      # Single shared pool for ALL tenants (schema switched per borrow by
      # MultiTenantConnectionProvider). Size so replicas x pool stays under
      # PostgreSQL max_connections (default 100): 10 x 3 replicas = 30.
      maximum-pool-size: ${DB_POOL_MAX:10}
      minimum-idle: ${DB_POOL_MIN:10}
      connection-timeout: ${DB_POOL_CONNECTION_TIMEOUT_MS:30000}
      # 29 min — retire connections before any PG/proxy idle reaper does, so the
      # app never hands out a server-side-dead socket.
      max-lifetime: ${DB_POOL_MAX_LIFETIME_MS:1740000}
      keepalive-time: ${DB_POOL_KEEPALIVE_MS:300000}
      # Log a stack trace if a borrowed connection isn't returned within 60s
      # (catches a missing try-with-resources before it starves the pool).
      leak-detection-threshold: ${DB_POOL_LEAK_DETECTION_MS:60000}
      # base application.yml's connection-init-sql (pgcrypto pii-key) is inherited.

logging:
  level:
    root: WARN
    com.nubeero: INFO
  structured:
    format:
      # Native Spring Boot structured logging (3.4+) — emits one JSON object per
      # line (Elastic Common Schema), aggregatable across replicas. MDC keys
      # (tenant) are included automatically. Dev keeps human-readable logs
      # because this is only on the prod profile.
      console: ecs

management:
  endpoints:
    web:
      exposure:
        # Add the Prometheus scrape surface; keep metrics/env/beans/configprops
        # OFF the web surface (no config/secret leakage). prometheus IS the
        # scrape endpoint at /actuator/prometheus.
        include: health,info,prometheus
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=ProdProfileConfigTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/application-prod.yml \
        cia-backend/cia-api/src/test/java/com/nubeero/cia/api/config/ProdProfileConfigTest.java
git commit -m "feat(ops): add application-prod.yml thin override

Tuned single shared Hikari pool (env-overridable), native ECS structured
logging, actuator web exposure health,info,prometheus (metrics/env off the
web surface). Binding test asserts values + placeholder defaults + exposure."
```

---

## Task 6: Documentation + backlog reconciliation

Correct the two CLAUDE.md inaccuracies this slice exposed/closed, document the new env vars and the dual-env prod requirement, then reconcile the backlog (drain 3 rows) and finalize the cia-log.md entry.

**Files:**
- Modify: `CLAUDE.md`
- Modify: `cia-log.md`

- [ ] **Step 1: Correct CLAUDE.md §7 connection-pooling claim**

In `CLAUDE.md`, under "### 7. Data Architecture", find:

```
**Connection pooling:** HikariCP with one pool per tenant schema, lazily initialised on first request to that tenant.
```

Replace with:

```
**Connection pooling:** A **single shared HikariCP pool** (not pool-per-tenant). `MultiTenantConnectionProvider` borrows one connection from the shared pool per unit of work and switches `search_path` to `"<tenant>", public` for the borrow (`public` included so shared extensions — pgcrypto — and the registry resolve), resetting to `public` on release. Pool sizing is tuned in `application-prod.yml` (env-overridable `DB_POOL_*`).
```

- [ ] **Step 2: Mark the runtime-pgcrypto gap closed in CLAUDE.md §6**

In `CLAUDE.md`, under "### 6. Multi-Tenancy Model", find the sentence beginning `**Runtime gap (`runtime-pgcrypto-search-path`, P1):**` and replace the whole parenthetical/gap note with:

```
**Runtime pgcrypto (closed in Slice C):** `MultiTenantConnectionProvider.getConnection(tenant)` sets `search_path TO "<tenant>", public` (was `setSchema(tenant)` → tenant only), so `pgp_sym_encrypt`/`pgp_sym_decrypt` (in `public`) resolve for every real tenant — NDPR PII encryption works at multi-tenant runtime. Proven by `MultiTenantConnectionProviderSearchPathIT`.
```

- [ ] **Step 3: Add the new env vars to the CLAUDE.md env table**

In `CLAUDE.md`, in the "## Environment Variables" table, add these rows after the `CIA_TENANTS_BOOTSTRAP_TENANTS_<n>_*` row:

```
| `SPRING_PROFILES_ACTIVE` | Set to `prod` in production — loads `application-prod.yml` (Hikari tuning, ECS structured logging, Prometheus exposure) and deactivates `DevSecurityConfig`. **Must be paired with `CIA_DEPLOYMENT_ENVIRONMENT=production`** (neither implies the other — the safety guard keys off the marker, not the profile). | env |
| `CIA_DEPLOYMENT_ENVIRONMENT` | `local` (default) / `staging` / `production`. Arms `ProductionSafetyValidator` (fail-fast on active `dev` profile or any surviving weak-default secret). | env |
| `DB_POOL_MAX` / `DB_POOL_MIN` | Hikari `maximum-pool-size` / `minimum-idle` for the single shared pool (prod profile). Default `10`/`10`. Keep `replicas × DB_POOL_MAX` under PostgreSQL `max_connections`. | env |
| `DB_POOL_MAX_LIFETIME_MS` / `DB_POOL_KEEPALIVE_MS` / `DB_POOL_CONNECTION_TIMEOUT_MS` / `DB_POOL_LEAK_DETECTION_MS` | Hikari lifetime/keepalive/connection-timeout/leak-detection (prod profile). Defaults `1740000`/`300000`/`30000`/`60000`. | env |
```

- [ ] **Step 4: Reconcile the backlog table in cia-log.md (drain 3 rows)**

In `cia-log.md`, under "## Tracked follow-up items", **delete** these three table rows entirely (they are now landed by this slice):
- the `runtime-pgcrypto-search-path` row,
- the `prod-observability` row,
- the `hikari-pool-tuning + application-prod-yml` row.

- [ ] **Step 5: Update the Slice C cia-log.md entry from DESIGN PHASE to COMPLETE**

In `cia-log.md`, in the `## 2026-06-05 — Slice C` entry, change the heading suffix `— DESIGN PHASE (brainstorm + spec committed, no code yet)` to `— COMPLETE`, and replace the "**Known follow-ups / backlog reconciliation:**" paragraph with:

```
**Known follow-ups / backlog reconciliation:** Slice landed. **Drained 3 backlog rows:** `runtime-pgcrypto-search-path` (P1 — fixed in `MultiTenantConnectionProvider`, IT-proven), `prod-observability` (P2 — Prometheus registry + ECS structured logging + tenant MDC), `hikari-pool-tuning + application-prod-yml` (P2 — tuned single shared pool + thin prod profile). **No new rows surfaced.** Distributed tracing remains deliberately out (deferred to a Slice-B collector — documented YAGNI, not a backlog row). The `/actuator/prometheus` HTTP-200 path is covered by dependency + binding + customizer tests; an end-to-end scrape is exercised by the Slice B deploy smoke (no heavy full-boot-prod IT — deliberate scope call).
```

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md cia-log.md
git commit -m "docs(slice-c): correct §7 single-pool + §6 pgcrypto-closed, drain backlog

CLAUDE.md §7 (single shared pool, not pool-per-tenant) + §6 (pgcrypto
search_path closed) + env table (SPRING_PROFILES_ACTIVE / CIA_DEPLOYMENT_
ENVIRONMENT pairing, DB_POOL_*). cia-log.md: drain runtime-pgcrypto-search-
path, prod-observability, hikari-pool-tuning+application-prod-yml; mark
Slice C COMPLETE."
```

---

## Task 7: Full-reactor verification

A final guard that nothing regressed across the reactor — the slice touched `cia-common` (connection provider, used by every module's persistence path) and `cia-auth` (the security filter).

- [ ] **Step 1: Build + install changed modules, then full verify**

Run: `cd cia-backend && mvn -q -DskipTests install -pl cia-common,cia-auth,cia-api -am && mvn verify`
Expected: BUILD SUCCESS — full failsafe IT suite green (≥ the prior 440 ITs from Slice A, plus the 2 new ITs and the new unit tests), 0 failures / 0 errors. If a stale-m2 `NoSuchMethodError` appears, the `install … -am` prefix is the fix (per CLAUDE.md "Run the backend" note).

- [ ] **Step 2: (No commit — verification only.)** If green, the slice is ready for finishing-a-development-branch.

---

## Self-Review Notes (filled in by the plan author)

- **Spec coverage:** §3.1 prod yaml → Task 5; §3.2 metrics → Task 4; §3.3 structured logging+MDC → Task 5 (yaml) + Task 3 (MDC); §3.4 pgcrypto fix + TenantSchemas promotion → Tasks 1–2; §3.5 docs+backlog → Task 6; §4 testing posture → tests embedded per task + Task 7 full verify. All spec sections map to a task.
- **Endpoint-200 scope call:** the spec's "`/actuator/prometheus` returns 200" line is intentionally covered by dependency-presence + binding (exposure) + customizer unit test rather than a full-boot-prod IT (which would need DB+security+the prod profile and be heavy/flaky for low marginal confidence). This is stated explicitly in Task 6 Step 5 (no silent cap).
- **Type/name consistency:** `TenantSchemas.validate` (cia-common) used identically by `TenantSeeder`, `TenantSchemaMigrator`, `MultiTenantConnectionProvider`; `MDC_TENANT_KEY="tenant"` matches the test's `MDC.get("tenant")`; `MetricsConfig.commonTags()` returns `MeterRegistryCustomizer<MeterRegistry>` and the test calls `.customize(registry)`; prod-yaml keys match the binding-test paths exactly.
