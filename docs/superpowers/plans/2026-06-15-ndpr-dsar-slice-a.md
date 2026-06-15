# NDPR DSAR — Slice A: `cia-compliance` module + retention config + DSAR export — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a new `cia-compliance` Maven module with (1) a per-tenant `data_retention_policy` config (CRUD under a new `DATA_PROTECTION` role) and (2) a DSAR export endpoint that gathers a customer's full footprint (decrypting PII inline) and returns a JSON+PDF ZIP.

**Architecture:** A standalone module mirroring `cia-reports` — native SQL queries against the tenant schema, **zero business-module dependencies** (depends only on `cia-common`, `cia-auth`, `cia-documents`). PII is decrypted in SQL via `pgp_sym_decrypt(col, current_setting('app.pii_key'))`. The DSAR export runs in an HTTP request (tenant context present). Heavy DB integration tests live in `cia-api/src/test` (where Flyway migrations are on the classpath); pure unit tests live in `cia-compliance/src/test`.

**Tech Stack:** Java 21, Spring Boot, JPA/`EntityManager` native queries, Jackson (JSON), `HtmlToPdfConverter` (PDFBox, NotoSans — for ₦), `ZipOutputStream`, Testcontainers + pgcrypto.

**Spec:** `docs/superpowers/specs/2026-06-15-ndpr-dsar-retention-design.md` (this is Slice A; Slice B = the purge workflow, separate plan).

---

## File Structure

| File | Responsibility |
|---|---|
| `cia-backend/cia-compliance/pom.xml` (create) | New module pom |
| `cia-backend/pom.xml` (modify) | Add `<module>cia-compliance</module>` |
| `cia-backend/cia-api/pom.xml` (modify) | Add `cia-compliance` dependency |
| `cia-backend/cia-setup/.../keycloak/BootstrapRoles.java` (modify) | Add `DATA_PROTECTION` realm role |
| `cia-backend/cia-api/src/main/resources/db/migration/V69__data_retention_policy.sql` (create) | The config table (per-tenant) |
| `cia-compliance/.../retention/DataRetentionPolicy.java` (create) | JPA entity (singleton per tenant) |
| `cia-compliance/.../retention/DataRetentionPolicyRepository.java` (create) | Repository |
| `cia-compliance/.../retention/RetentionPolicyService.java` (create) | get-or-create + validated update |
| `cia-compliance/.../retention/RetentionPolicyRequest.java` + `RetentionPolicyResponse.java` (create) | DTOs |
| `cia-compliance/.../retention/RetentionPolicyController.java` (create) | `GET`/`PUT /api/v1/compliance/retention-policy` |
| `cia-compliance/.../dsar/DsarExport.java` (create) | The gathered-footprint model (records) |
| `cia-compliance/.../dsar/DsarGatherService.java` (create) | Native-query gather (decrypts PII) |
| `cia-compliance/.../dsar/DsarJsonRenderer.java` (create) | `DsarExport` → JSON bytes |
| `cia-compliance/.../dsar/DsarPdfRenderer.java` (create) | `DsarExport` → PDF bytes (HTML→PDF) |
| `cia-compliance/.../dsar/DsarExportService.java` (create) | Gather + render + ZIP + audit |
| `cia-compliance/.../dsar/DsarExportController.java` (create) | `GET /api/v1/customers/{id}/dsar-export` |
| Tests | unit (cia-compliance) + ITs (cia-api/src/test/.../compliance) |

**Base package:** `com.nubeero.cia.compliance` (Spring Boot auto-scans `com.nubeero.cia.*`).

> **Migration version note:** V67 is the latest on this branch; SP2 (open PR #6, branch `platform-admin-ui`) holds **V68**. This plan uses **V69** to avoid the collision. If SP2 has merged to `main` by the time you rebase, confirm V69 is still the next free sequential version; bump if needed (Flyway rejects out-of-order migrations).

---

### Task 1: Module scaffold + `DATA_PROTECTION` role

**Files:**
- Create: `cia-backend/cia-compliance/pom.xml`
- Modify: `cia-backend/pom.xml` (`<modules>` list)
- Modify: `cia-backend/cia-api/pom.xml` (dependencies)
- Modify: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/BootstrapRoles.java`

- [ ] **Step 1: Create the module pom**

`cia-backend/cia-compliance/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.nubeero.cia</groupId>
    <artifactId>cia-backend</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>cia-compliance</artifactId>
  <name>CIA Compliance</name>
  <description>NDPR data protection: DSAR export + per-tenant retention policy</description>

  <dependencies>
    <dependency>
      <groupId>com.nubeero.cia</groupId>
      <artifactId>cia-common</artifactId>
    </dependency>
    <dependency>
      <groupId>com.nubeero.cia</groupId>
      <artifactId>cia-auth</artifactId>
    </dependency>
    <dependency>
      <groupId>com.nubeero.cia</groupId>
      <artifactId>cia-documents</artifactId>
    </dependency>

    <!-- OpenAPI documentation -->
    <dependency>
      <groupId>org.springdoc</groupId>
      <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    </dependency>

    <!-- Test -->
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>postgresql</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

</project>
```

- [ ] **Step 2: Register the module in the parent pom**

In `cia-backend/pom.xml`, add `<module>cia-compliance</module>` to the `<modules>` list **immediately before** `<module>cia-api</module>` (assembly is always last):
```xml
    <module>cia-reports</module>
    <module>cia-compliance</module>
    <module>cia-api</module>
```

- [ ] **Step 3: Wire it into cia-api**

In `cia-backend/cia-api/pom.xml`, add (in the business-modules dependency block, e.g. after the `cia-reports` dependency):
```xml
    <dependency>
      <groupId>com.nubeero.cia</groupId>
      <artifactId>cia-compliance</artifactId>
    </dependency>
```

- [ ] **Step 4: Add the `DATA_PROTECTION` realm role**

In `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/BootstrapRoles.java`, add `"DATA_PROTECTION"` to the `PATTERN_B` list (the SCREAMING_CASE roles, alongside `PLATFORM_ADMIN`):
```java
    public static final List<String> PATTERN_B = List.of(
        "FINANCE_VIEW", "FINANCE_CREATE", "FINANCE_UPDATE", "FINANCE_APPROVE",
        "FINANCE_APPROVE_PPA", "FINANCE_REOPEN_PERIOD", "FINANCE_OVERRIDE_LOCK",
        "PLATFORM_ADMIN", "DATA_PROTECTION"
    );
```
> Why `PATTERN_B`: it's a standalone cross-module compliance role (like `PLATFORM_ADMIN`), not a `module_action` permission tuple. `JwtAuthConverter` maps the Keycloak role `DATA_PROTECTION` → authority `ROLE_DATA_PROTECTION`, so `@PreAuthorize("hasRole('DATA_PROTECTION')")` matches.

- [ ] **Step 5: Create the base package marker + verify the build**

Create an empty package dir by adding the first source file in Task 2. For now, verify the module compiles and the reactor resolves it:

Run: `cd cia-backend && mvn -q -pl cia-compliance -am compile`
Expected: BUILD SUCCESS (an empty module compiles). If the reactor complains the module has no sources, that's fine — Task 2 adds the first class; re-run there.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-compliance/pom.xml cia-backend/pom.xml cia-backend/cia-api/pom.xml cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/keycloak/BootstrapRoles.java
git commit -m "feat(compliance): scaffold cia-compliance module + DATA_PROTECTION role"
```

---

### Task 2: `data_retention_policy` migration + entity + repository

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V69__data_retention_policy.sql`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/DataRetentionPolicy.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/DataRetentionPolicyRepository.java`

- [ ] **Step 1: The migration (runs in `public` + every tenant schema — unqualified)**

`V69__data_retention_policy.sql`:
```sql
-- NDPR per-tenant data retention policy (singleton per tenant schema).
-- Created unqualified so it lands in public + every tenant schema via the per-schema migration sweep.
CREATE TABLE IF NOT EXISTS data_retention_policy (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_pii_retention_days INT         NOT NULL DEFAULT 2555,   -- ~7 years after last activity
    purge_enabled               BOOLEAN     NOT NULL DEFAULT FALSE,  -- opt-in safety rail
    purge_frequency             VARCHAR(10) NOT NULL DEFAULT 'WEEKLY',
    purge_day_of_week           SMALLINT    NOT NULL DEFAULT 0,      -- 0=Sun..6=Sat (WEEKLY)
    purge_hour_utc              SMALLINT    NOT NULL DEFAULT 3,      -- 0..23
    last_purge_run_at           TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                  VARCHAR(100),
    deleted_at                  TIMESTAMPTZ
);
```

- [ ] **Step 2: The entity**

`DataRetentionPolicy.java`:
```java
package com.nubeero.cia.compliance.retention;

import com.nubeero.cia.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-tenant NDPR retention policy. One row per tenant schema (service-enforced singleton). */
@Entity
@Table(name = "data_retention_policy")
@Getter
@Setter
@NoArgsConstructor
public class DataRetentionPolicy extends BaseEntity {

    @Column(name = "customer_pii_retention_days", nullable = false)
    private int customerPiiRetentionDays = 2555;

    @Column(name = "purge_enabled", nullable = false)
    private boolean purgeEnabled = false;

    @Column(name = "purge_frequency", nullable = false, length = 10)
    private String purgeFrequency = "WEEKLY";   // WEEKLY | MONTHLY

    @Column(name = "purge_day_of_week", nullable = false)
    private int purgeDayOfWeek = 0;             // 0=Sun..6=Sat

    @Column(name = "purge_hour_utc", nullable = false)
    private int purgeHourUtc = 3;               // 0..23

    @Column(name = "last_purge_run_at")
    private Instant lastPurgeRunAt;
}
```
> `BaseEntity` supplies `id`, `createdAt`, `updatedAt`, `createdBy`, `deletedAt` + Spring Data auditing. Confirm the import path of `BaseEntity` (`com.nubeero.cia.common.entity.BaseEntity`) against an existing entity such as `cia-customer/.../Customer.java`.

- [ ] **Step 3: The repository**

`DataRetentionPolicyRepository.java`:
```java
package com.nubeero.cia.compliance.retention;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataRetentionPolicyRepository extends JpaRepository<DataRetentionPolicy, UUID> {
    /** Singleton lookup — the first (and only) non-deleted row in the current tenant schema. */
    Optional<DataRetentionPolicy> findFirstByDeletedAtIsNull();
}
```

- [ ] **Step 4: Compile**

Run: `cd cia-backend && mvn -q -pl cia-compliance -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/db/migration/V69__data_retention_policy.sql cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/DataRetentionPolicy.java cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/DataRetentionPolicyRepository.java
git commit -m "feat(compliance): V69 data_retention_policy table + entity + repository"
```

---

### Task 3: `RetentionPolicyService` (get-or-create + validated update)

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/RetentionPolicyService.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/RetentionPolicyRequest.java`
- Test: `cia-backend/cia-compliance/src/test/java/com/nubeero/cia/compliance/retention/RetentionPolicyValidationTest.java`

- [ ] **Step 1: The request DTO**

`RetentionPolicyRequest.java`:
```java
package com.nubeero.cia.compliance.retention;

/** Mutable fields of the per-tenant retention policy (the schedule + retention period + opt-in flag). */
public record RetentionPolicyRequest(
        int customerPiiRetentionDays,
        boolean purgeEnabled,
        String purgeFrequency,   // WEEKLY | MONTHLY
        int purgeDayOfWeek,      // 0..6
        int purgeHourUtc         // 0..23
) {}
```

- [ ] **Step 2: Write the failing validation test**

`RetentionPolicyValidationTest.java`:
```java
package com.nubeero.cia.compliance.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.common.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

class RetentionPolicyValidationTest {

    private final RetentionPolicyService service = new RetentionPolicyService(null);  // validate() needs no repo

    @Test
    void rejectsNonPositiveRetentionDays() {
        assertThatThrownBy(() -> service.validate(
                new RetentionPolicyRequest(0, false, "WEEKLY", 0, 3)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("retention");
    }

    @Test
    void rejectsUnknownFrequency() {
        assertThatThrownBy(() -> service.validate(
                new RetentionPolicyRequest(2555, true, "DAILY", 0, 3)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("frequency");
    }

    @Test
    void rejectsDayOfWeekOutOfRange() {
        assertThatThrownBy(() -> service.validate(
                new RetentionPolicyRequest(2555, true, "WEEKLY", 7, 3)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("day");
    }

    @Test
    void rejectsHourOutOfRange() {
        assertThatThrownBy(() -> service.validate(
                new RetentionPolicyRequest(2555, true, "WEEKLY", 0, 24)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("hour");
    }

    @Test
    void acceptsValidRequest() {
        // no throw
        service.validate(new RetentionPolicyRequest(365, true, "MONTHLY", 0, 2));
        assertThat(true).isTrue();
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-compliance test -Dtest=RetentionPolicyValidationTest`
Expected: COMPILE FAIL (`RetentionPolicyService` / `validate` not defined).

- [ ] **Step 4: The service**

`RetentionPolicyService.java`:
```java
package com.nubeero.cia.compliance.retention;

import com.nubeero.cia.common.exception.BusinessRuleException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads + updates the per-tenant {@link DataRetentionPolicy} singleton, with schedule validation. */
@Service
@RequiredArgsConstructor
public class RetentionPolicyService {

    private static final Set<String> FREQUENCIES = Set.of("WEEKLY", "MONTHLY");

    private final DataRetentionPolicyRepository repository;

    /** Returns the tenant's policy, lazily creating it with defaults on first access. */
    @Transactional
    public DataRetentionPolicy getOrCreate() {
        return repository.findFirstByDeletedAtIsNull()
                .orElseGet(() -> repository.save(new DataRetentionPolicy()));
    }

    /** Validates + applies an update. Throws {@link BusinessRuleException} (HTTP 400) on bad input. */
    @Transactional
    public DataRetentionPolicy update(RetentionPolicyRequest req) {
        validate(req);
        DataRetentionPolicy p = getOrCreate();
        p.setCustomerPiiRetentionDays(req.customerPiiRetentionDays());
        p.setPurgeEnabled(req.purgeEnabled());
        p.setPurgeFrequency(req.purgeFrequency());
        p.setPurgeDayOfWeek(req.purgeDayOfWeek());
        p.setPurgeHourUtc(req.purgeHourUtc());
        return repository.save(p);
    }

    /** Pure validation — package-visible so it is unit-testable without a repository. */
    void validate(RetentionPolicyRequest req) {
        if (req.customerPiiRetentionDays() <= 0) {
            throw new BusinessRuleException("INVALID_RETENTION_DAYS",
                    "retention days must be > 0");
        }
        if (req.purgeFrequency() == null || !FREQUENCIES.contains(req.purgeFrequency())) {
            throw new BusinessRuleException("INVALID_PURGE_FREQUENCY",
                    "purge frequency must be one of " + FREQUENCIES);
        }
        if (req.purgeDayOfWeek() < 0 || req.purgeDayOfWeek() > 6) {
            throw new BusinessRuleException("INVALID_PURGE_DAY",
                    "purge day of week must be 0..6");
        }
        if (req.purgeHourUtc() < 0 || req.purgeHourUtc() > 23) {
            throw new BusinessRuleException("INVALID_PURGE_HOUR",
                    "purge hour must be 0..23");
        }
    }
}
```
> Confirm `BusinessRuleException`'s constructor is `(String code, String message)` against `cia-common/.../exception/BusinessRuleException.java` (the reinsurance `AllocationService` uses exactly this form: `new BusinessRuleException("TREATY_NOT_ACTIVE", "...")`). Confirm it maps to HTTP 400 in `GlobalExceptionHandler`; if it maps to a different status, that's acceptable for a validation error (it carries a code + message).

- [ ] **Step 5: Run the test, expect green**

Run: `cd cia-backend && mvn -q -pl cia-compliance test -Dtest=RetentionPolicyValidationTest`
Expected: 5 tests pass.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/RetentionPolicyService.java cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/RetentionPolicyRequest.java cia-backend/cia-compliance/src/test/java/com/nubeero/cia/compliance/retention/RetentionPolicyValidationTest.java
git commit -m "feat(compliance): RetentionPolicyService (get-or-create + validated update)"
```

---

### Task 4: `RetentionPolicyController` + IT

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/RetentionPolicyResponse.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/RetentionPolicyController.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/ComplianceItSupport.java` (shared IT base)
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/RetentionPolicyControllerIT.java`

- [ ] **Step 1: The response DTO**

`RetentionPolicyResponse.java`:
```java
package com.nubeero.cia.compliance.retention;

import java.time.Instant;

public record RetentionPolicyResponse(
        int customerPiiRetentionDays,
        boolean purgeEnabled,
        String purgeFrequency,
        int purgeDayOfWeek,
        int purgeHourUtc,
        Instant lastPurgeRunAt
) {
    public static RetentionPolicyResponse from(DataRetentionPolicy p) {
        return new RetentionPolicyResponse(
                p.getCustomerPiiRetentionDays(), p.isPurgeEnabled(), p.getPurgeFrequency(),
                p.getPurgeDayOfWeek(), p.getPurgeHourUtc(), p.getLastPurgeRunAt());
    }
}
```

- [ ] **Step 2: The controller**

`RetentionPolicyController.java`:
```java
package com.nubeero.cia.compliance.retention;

import com.nubeero.cia.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/compliance/retention-policy")
@RequiredArgsConstructor
public class RetentionPolicyController {

    private final RetentionPolicyService service;

    @GetMapping
    @PreAuthorize("hasRole('DATA_PROTECTION')")
    public ApiResponse<RetentionPolicyResponse> get() {
        return ApiResponse.success(RetentionPolicyResponse.from(service.getOrCreate()));
    }

    @PutMapping
    @PreAuthorize("hasRole('DATA_PROTECTION')")
    public ApiResponse<RetentionPolicyResponse> update(@RequestBody RetentionPolicyRequest request) {
        return ApiResponse.success(RetentionPolicyResponse.from(service.update(request)));
    }
}
```
> Confirm `ApiResponse.success(T)` exists (it does — `cia-common/.../api/ApiResponse.java`).

- [ ] **Step 3: The shared IT base (Testcontainers + pgcrypto + pii_key)**

`ComplianceItSupport.java` — mirrors `cia-api/.../finance/FinanceItSupport.java`, plus pgcrypto + the pii key, and `flyway.target=69` so the V69 table exists:
```java
package com.nubeero.cia.api.compliance;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class ComplianceItSupport {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ciatest").withUsername("ciatest").withPassword("ciatest");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.target", () -> "69");
        registry.add("spring.jpa.properties.hibernate.multiTenancy", () -> "NONE");
        registry.add("cia.security.pii-key", () -> "test-pii-key-do-not-use-in-prod");
        // ensure pgp_sym_* can read the key on every connection (mirrors prod connection-init-sql)
        registry.add("spring.datasource.hikari.connection-init-sql",
                () -> "SET app.pii_key = 'test-pii-key-do-not-use-in-prod'");
    }

    @Autowired
    protected DataSource dataSource;

    @BeforeEach
    void installPgcrypto() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        }
    }
}
```
> If `@DataJpaTest` + `@PreAuthorize`/MVC are awkward to combine, the controller IT can instead instantiate the controller + service directly against the wired repository (a "slice" IT). But the simplest path is a `@SpringBootTest` + `MockMvc` web IT — check how `cia-api/.../finance` web ITs (e.g. `FinanceWebItSupport`) combine `MockMvc` + `jwt()` postprocessor + a real or `@MockBean` service, and follow whichever base fits. For this controller, a direct service-level IT (below) is enough; the web-layer auth is covered by the existing `@PreAuthorize` + a single `403-without-role` assertion via `MockMvc` if a web base is available.

- [ ] **Step 4: Write the IT**

`RetentionPolicyControllerIT.java` (service-level IT — the simplest reliable form):
```java
package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.compliance.retention.DataRetentionPolicyRepository;
import com.nubeero.cia.compliance.retention.RetentionPolicyRequest;
import com.nubeero.cia.compliance.retention.RetentionPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import({RetentionPolicyService.class})
class RetentionPolicyControllerIT extends ComplianceItSupport {

    @Autowired DataRetentionPolicyRepository repository;
    @Autowired RetentionPolicyService service;

    @Test
    void getOrCreate_lazilyCreatesWithDefaults() {
        var p = service.getOrCreate();
        assertThat(p.getCustomerPiiRetentionDays()).isEqualTo(2555);
        assertThat(p.isPurgeEnabled()).isFalse();
        assertThat(p.getPurgeFrequency()).isEqualTo("WEEKLY");
        assertThat(p.getPurgeHourUtc()).isEqualTo(3);
        assertThat(repository.findFirstByDeletedAtIsNull()).isPresent();
    }

    @Test
    void getOrCreate_isIdempotentSingleton() {
        var a = service.getOrCreate();
        var b = service.getOrCreate();
        assertThat(b.getId()).isEqualTo(a.getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void update_appliesValidValues() {
        service.update(new RetentionPolicyRequest(365, true, "MONTHLY", 0, 2));
        var p = service.getOrCreate();
        assertThat(p.getCustomerPiiRetentionDays()).isEqualTo(365);
        assertThat(p.isPurgeEnabled()).isTrue();
        assertThat(p.getPurgeFrequency()).isEqualTo("MONTHLY");
        assertThat(p.getPurgeHourUtc()).isEqualTo(2);
    }

    @Test
    void update_rejectsInvalid() {
        assertThatThrownBy(() -> service.update(new RetentionPolicyRequest(2555, true, "DAILY", 0, 3)))
                .isInstanceOf(BusinessRuleException.class);
    }
}
```
> `@DataJpaTest` needs `@Import(CiaCommonAutoConfiguration.class)` for `BaseEntity` auditing (per CLAUDE.md). Add it to the `@Import(...)` list if `created_at` comes back null — confirm the exact class name in `cia-common` (`grep -r "class CiaCommonAutoConfiguration"`). If a `@SpringBootTest` web IT is used instead, add `@WithMockUser(roles = "DATA_PROTECTION")` + a `403` test without the role.

- [ ] **Step 5: Run the IT (Docker required)**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=RetentionPolicyControllerIT`
Expected: 4 tests pass. If `created_at`/auditing NPEs, add `@Import(CiaCommonAutoConfiguration.class)`.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/RetentionPolicyResponse.java cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/retention/RetentionPolicyController.java cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/
git commit -m "feat(compliance): retention-policy GET/PUT endpoint + ITs"
```

---

### Task 5: `DsarExport` model + `DsarGatherService` (native gather, decrypt) + IT

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarExport.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarGatherService.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/DsarGatherServiceIT.java`

- [ ] **Step 1: The gathered-footprint model**

`DsarExport.java` — a structured holder of generic rows (each related-record set is a `List<Map<String,Object>>` keyed by column, so the gather stays schema-driven and the renderers iterate generically):
```java
package com.nubeero.cia.compliance.dsar;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A data subject's full footprint, gathered for a DSAR. PII is already decrypted into these maps. */
public record DsarExport(
        Instant generatedAt,
        String customerId,
        String customerNumber,
        Map<String, Object> customer,                 // the decrypted master record
        List<Map<String, Object>> directors,          // decrypted
        List<Map<String, Object>> documents,          // metadata only
        List<Map<String, Object>> policies,
        List<Map<String, Object>> quotes,
        List<Map<String, Object>> claims,
        List<Map<String, Object>> endorsements,
        List<Map<String, Object>> debitNotes,
        List<Map<String, Object>> receipts,
        List<Map<String, Object>> creditNotes,
        List<Map<String, Object>> payments,
        List<Map<String, Object>> auditHistory
) {}
```

- [ ] **Step 2: Write the failing gather IT**

`DsarGatherServiceIT.java`:
```java
package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.compliance.dsar.DsarExport;
import com.nubeero.cia.compliance.dsar.DsarGatherService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({DsarGatherService.class})
class DsarGatherServiceIT extends ComplianceItSupport {

    @Autowired DsarGatherService gather;
    @Autowired JdbcTemplate jdbc;

    @Test
    void gathers_decryptedCustomer_directors_documents_and_relatedRecords() {
        UUID customerId = UUID.randomUUID();
        // seed a corporate customer with an encrypted address + id_number, a director, a document, a policy.
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, " +
                "first_name, last_name, email, phone, " +
                "id_number, address, created_by) VALUES (?,?,?,?,?,?,?,?, " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'test')",
                customerId, "CUST-DSAR-1", "INDIVIDUAL", "PASSED",
                "Ada", "Obi", "ada@test.local", "08030000000",
                "NIN12345678901", "12 Marina St, Lagos");

        UUID directorId = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_directors (id, customer_id, first_name, last_name, " +
                "id_number, kyc_status) VALUES (?,?,?,?, " +
                "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'PASSED')",
                directorId, customerId, "Bola", "Obi", "NIN99999999999");

        jdbc.update("INSERT INTO customer_documents (id, customer_id, document_type, document_name, " +
                "document_path) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), customerId, "ID_CARD", "nin.pdf", "kyc/2026/nin.pdf");

        jdbc.update("INSERT INTO policies (id, policy_number, status, customer_id, customer_name, " +
                "business_type, total_sum_insured, total_premium, net_premium) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), "POL-DSAR-1", "ACTIVE", customerId, "Ada Obi",
                "DIRECT", 1000000, 50000, 47500);

        DsarExport export = gather.gather(customerId);

        assertThat(export.customerNumber()).isEqualTo("CUST-DSAR-1");
        assertThat(export.customer().get("id_number")).isEqualTo("NIN12345678901");      // decrypted
        assertThat(export.customer().get("address")).isEqualTo("12 Marina St, Lagos");   // decrypted
        assertThat(export.directors()).hasSize(1);
        assertThat(export.directors().get(0).get("id_number")).isEqualTo("NIN99999999999"); // decrypted
        assertThat(export.documents()).hasSize(1);
        assertThat(export.documents().get(0).get("document_path")).isEqualTo("kyc/2026/nin.pdf");
        assertThat(export.policies()).hasSize(1);
        assertThat(export.policies().get(0).get("policy_number")).isEqualTo("POL-DSAR-1");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=DsarGatherServiceIT`
Expected: COMPILE FAIL (`DsarGatherService.gather` not defined).

- [ ] **Step 4: The gather service (native queries; decrypt PII inline)**

`DsarGatherService.java`:
```java
package com.nubeero.cia.compliance.dsar;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gathers a data subject's full footprint via native SQL against the tenant schema, decrypting the
 * pgcrypto-protected PII columns inline (id_number / id_document_url / address) so the export carries
 * the cleartext the subject is entitled to. Zero business-module deps — all column access is by name.
 */
@Service
@RequiredArgsConstructor
public class DsarGatherService {

    @PersistenceContext
    private final EntityManager em;

    @Transactional(readOnly = true)
    public DsarExport gather(UUID customerId) {
        String id = customerId.toString();

        // Customer master — decrypt id_number / id_document_url / address; rest selected as-is.
        List<Map<String, Object>> customers = rows(
                "SELECT id, customer_number, customer_type, customer_status, kyc_status, " +
                "  first_name, last_name, other_names, date_of_birth, gender, marital_status, " +
                "  id_type, pgp_sym_decrypt(id_number, current_setting('app.pii_key')) AS id_number, " +
                "  company_name, rc_number, incorporation_date, industry, contact_person, " +
                "  email, phone, alternate_phone, " +
                "  pgp_sym_decrypt(address, current_setting('app.pii_key')) AS address, " +
                "  city, state, country, created_at, deleted_at " +
                "FROM customers WHERE id = CAST(:id AS uuid)",
                List.of("id", "customer_number", "customer_type", "customer_status", "kyc_status",
                        "first_name", "last_name", "other_names", "date_of_birth", "gender",
                        "marital_status", "id_type", "id_number", "company_name", "rc_number",
                        "incorporation_date", "industry", "contact_person", "email", "phone",
                        "alternate_phone", "address", "city", "state", "country", "created_at",
                        "deleted_at"),
                id);
        if (customers.isEmpty()) {
            throw new com.nubeero.cia.common.exception.ResourceNotFoundException("Customer", customerId);
        }
        Map<String, Object> customer = customers.get(0);

        List<Map<String, Object>> directors = rows(
                "SELECT id, first_name, last_name, date_of_birth, id_type, " +
                "  pgp_sym_decrypt(id_number, current_setting('app.pii_key')) AS id_number, " +
                "  kyc_status FROM customer_directors WHERE customer_id = CAST(:id AS uuid) " +
                "  AND deleted_at IS NULL",
                List.of("id", "first_name", "last_name", "date_of_birth", "id_type", "id_number",
                        "kyc_status"), id);

        List<Map<String, Object>> documents = rows(
                "SELECT id, document_type, document_name, document_path, mime_type, created_at " +
                "FROM customer_documents WHERE customer_id = CAST(:id AS uuid) AND deleted_at IS NULL",
                List.of("id", "document_type", "document_name", "document_path", "mime_type",
                        "created_at"), id);

        List<Map<String, Object>> policies = rows(
                "SELECT policy_number, status, business_type, policy_start_date, policy_end_date, " +
                "  total_sum_insured, total_premium, net_premium, created_at " +
                "FROM policies WHERE customer_id = CAST(:id AS uuid)",
                List.of("policy_number", "status", "business_type", "policy_start_date",
                        "policy_end_date", "total_sum_insured", "total_premium", "net_premium",
                        "created_at"), id);

        List<Map<String, Object>> quotes = rows(
                "SELECT quote_number, status, business_type, policy_start_date, policy_end_date, " +
                "  total_sum_insured, net_premium, created_at " +
                "FROM quotes WHERE customer_id = CAST(:id AS uuid)",
                List.of("quote_number", "status", "business_type", "policy_start_date",
                        "policy_end_date", "total_sum_insured", "net_premium", "created_at"), id);

        List<Map<String, Object>> claims = rows(
                "SELECT claim_number, status, policy_number, incident_date, reported_date, " +
                "  description, estimated_loss, reserve_amount, approved_amount, created_at " +
                "FROM claims WHERE customer_id = CAST(:id AS uuid)",
                List.of("claim_number", "status", "policy_number", "incident_date", "reported_date",
                        "description", "estimated_loss", "reserve_amount", "approved_amount",
                        "created_at"), id);

        List<Map<String, Object>> endorsements = rows(
                "SELECT endorsement_number, status, endorsement_type, policy_number, effective_date, " +
                "  premium_adjustment, description, created_at " +
                "FROM endorsements WHERE customer_id = CAST(:id AS uuid)",
                List.of("endorsement_number", "status", "endorsement_type", "policy_number",
                        "effective_date", "premium_adjustment", "description", "created_at"), id);

        List<Map<String, Object>> debitNotes = rows(
                "SELECT debit_note_number, status, entity_type, entity_reference, description, " +
                "  total_amount, paid_amount, currency_code, due_date, created_at " +
                "FROM debit_notes WHERE customer_id = CAST(:id AS uuid)",
                List.of("debit_note_number", "status", "entity_type", "entity_reference",
                        "description", "total_amount", "paid_amount", "currency_code", "due_date",
                        "created_at"), id);

        List<Map<String, Object>> receipts = rows(
                "SELECT r.receipt_number, r.amount, r.payment_date, r.payment_method, r.status, " +
                "  r.created_at FROM receipts r JOIN debit_notes dn ON dn.id = r.debit_note_id " +
                "WHERE dn.customer_id = CAST(:id AS uuid)",
                List.of("receipt_number", "amount", "payment_date", "payment_method", "status",
                        "created_at"), id);

        // Customer-as-beneficiary payables (e.g. claim settlements paid to the customer).
        List<Map<String, Object>> creditNotes = rows(
                "SELECT credit_note_number, status, entity_type, entity_reference, description, " +
                "  total_amount, paid_amount, currency_code, due_date, created_at " +
                "FROM credit_notes WHERE beneficiary_id = CAST(:id AS uuid)",
                List.of("credit_note_number", "status", "entity_type", "entity_reference",
                        "description", "total_amount", "paid_amount", "currency_code", "due_date",
                        "created_at"), id);

        List<Map<String, Object>> payments = rows(
                "SELECT p.payment_number, p.amount, p.payment_date, p.payment_method, p.status, " +
                "  p.created_at FROM payments p JOIN credit_notes cn ON cn.id = p.credit_note_id " +
                "WHERE cn.beneficiary_id = CAST(:id AS uuid)",
                List.of("payment_number", "amount", "payment_date", "payment_method", "status",
                        "created_at"), id);

        List<Map<String, Object>> auditHistory = rows(
                "SELECT action, user_name, timestamp, reason FROM audit_log " +
                "WHERE entity_id = :id ORDER BY timestamp",
                List.of("action", "user_name", "timestamp", "reason"), id);

        return new DsarExport(Instant.now(), id, str(customer.get("customer_number")),
                customer, directors, documents, policies, quotes, claims, endorsements,
                debitNotes, receipts, creditNotes, payments, auditHistory);
    }

    /** Runs a native query whose SELECT aliases match {@code keys} (in order) and maps each row to a map. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String sql, List<String> keys, String id) {
        Query q = em.createNativeQuery(sql);
        q.setParameter("id", id);
        List<Object[]> raw = (sql.contains("FROM customers WHERE id")) // single-column-safe path
                ? wrapSingle(q.getResultList())
                : q.getResultList();
        return raw.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < keys.size() && i < r.length; i++) m.put(keys.get(i), r[i]);
            return m;
        }).toList();
    }

    /** JPA returns Object (not Object[]) when a native query selects a single column; normalize to Object[]. */
    @SuppressWarnings("unchecked")
    private List<Object[]> wrapSingle(List<?> raw) {
        return (List<Object[]>) raw; // multi-column selects (all of ours) already return Object[]
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
```
> **Three things to confirm at implementation (read the real source — these are the only soft spots):**
> 1. The exact `EntityManager` injection idiom — some codebases inject via `@PersistenceContext private EntityManager em;` (field, no constructor). `ReportQueryBuilder` uses constructor injection of `EntityManager`; **follow `ReportQueryBuilder`** (`@RequiredArgsConstructor` + `private final EntityManager entityManager;`) and drop the `@PersistenceContext` field if that's the house style. The `wrapSingle` helper is defensive — all queries here are multi-column so they return `Object[]`; delete it if it's unused noise.
> 2. `ResourceNotFoundException` constructor shape — confirm `(String, Object)` against `cia-common/.../exception/ResourceNotFoundException.java` (the reinsurance service uses `new ResourceNotFoundException("RiTreaty", treatyId)`).
> 3. `pgp_sym_decrypt` returns `text`; JPA maps it to `String`. Confirm the decrypted columns come back as `String` (the seed inserts cleartext via `pgp_sym_encrypt`, so the round-trip yields the original string).

- [ ] **Step 5: Run the IT, expect green**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=DsarGatherServiceIT`
Expected: 1 test passes (customer/directors decrypted; documents + policies gathered).

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarExport.java cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarGatherService.java cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/DsarGatherServiceIT.java
git commit -m "feat(compliance): DsarGatherService native-query footprint gather (decrypts PII)"
```

---

### Task 6: `DsarJsonRenderer` + unit test

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarJsonRenderer.java`
- Test: `cia-backend/cia-compliance/src/test/java/com/nubeero/cia/compliance/dsar/DsarJsonRendererTest.java`

- [ ] **Step 1: Write the failing test**

`DsarJsonRendererTest.java`:
```java
package com.nubeero.cia.compliance.dsar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DsarJsonRendererTest {

    private final DsarJsonRenderer renderer = new DsarJsonRenderer();

    @Test
    void rendersStructuredJsonWithDecryptedPii() {
        DsarExport export = new DsarExport(Instant.parse("2026-06-15T00:00:00Z"),
                "id-1", "CUST-1",
                Map.of("customer_number", "CUST-1", "id_number", "NIN123", "address", "12 Marina"),
                List.of(Map.of("first_name", "Bola", "id_number", "NIN999")),
                List.of(), List.of(Map.of("policy_number", "POL-1")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        byte[] json = renderer.render(export);
        String s = new String(json);

        assertThat(s).contains("CUST-1").contains("NIN123").contains("12 Marina")
                .contains("POL-1").contains("\"directors\"").contains("\"policies\"");
    }
}
```

- [ ] **Step 2: Run it (compile-fail expected)**

Run: `cd cia-backend && mvn -q -pl cia-compliance test -Dtest=DsarJsonRendererTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement**

`DsarJsonRenderer.java`:
```java
package com.nubeero.cia.compliance.dsar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/** Serializes a {@link DsarExport} to pretty, machine-readable JSON (the NDPR data-portability copy). */
@Component
public class DsarJsonRenderer {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public byte[] render(DsarExport export) {
        try {
            return mapper.writeValueAsBytes(export);
        } catch (Exception e) {
            throw new RuntimeException("Failed to render DSAR JSON", e);
        }
    }
}
```
> Confirm `jackson-datatype-jsr310` is on the classpath (it ships with `spring-boot-starter-json`, transitive via `cia-common`). If `JavaTimeModule` won't resolve, replace the dates in the export with ISO strings, or use `mapper.findAndRegisterModules()`.

- [ ] **Step 4: Run the test, expect green**

Run: `cd cia-backend && mvn -q -pl cia-compliance test -Dtest=DsarJsonRendererTest`
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarJsonRenderer.java cia-backend/cia-compliance/src/test/java/com/nubeero/cia/compliance/dsar/DsarJsonRendererTest.java
git commit -m "feat(compliance): DsarJsonRenderer (structured portability JSON)"
```

---

### Task 7: `DsarPdfRenderer` (HTML → PDF) + unit test

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarPdfRenderer.java`
- Test: `cia-backend/cia-compliance/src/test/java/com/nubeero/cia/compliance/dsar/DsarPdfRendererTest.java`

- [ ] **Step 1: Write the failing test**

`DsarPdfRendererTest.java`:
```java
package com.nubeero.cia.compliance.dsar;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.documents.HtmlToPdfConverter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DsarPdfRendererTest {

    private final DsarPdfRenderer renderer = new DsarPdfRenderer(new HtmlToPdfConverter());

    @Test
    void rendersNonEmptyPdf() {
        DsarExport export = new DsarExport(Instant.parse("2026-06-15T00:00:00Z"),
                "id-1", "CUST-1",
                Map.of("customer_number", "CUST-1", "first_name", "Ada", "last_name", "Obi"),
                List.of(), List.of(), List.of(Map.of("policy_number", "POL-1", "net_premium", "47500")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        byte[] pdf = renderer.render(export);

        assertThat(pdf).isNotNull().isNotEmpty();
        assertThat(new String(pdf, 0, 5)).startsWith("%PDF-");  // PDF magic header
    }
}
```

- [ ] **Step 2: Run it (compile-fail expected)**

Run: `cd cia-backend && mvn -q -pl cia-compliance test -Dtest=DsarPdfRendererTest`
Expected: COMPILE FAIL.

- [ ] **Step 3: Implement (build HTML, delegate to `HtmlToPdfConverter`)**

`DsarPdfRenderer.java`:
```java
package com.nubeero.cia.compliance.dsar;

import com.nubeero.cia.documents.HtmlToPdfConverter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Renders a {@link DsarExport} to a human-readable PDF via the shared NotoSans-embedded HTML→PDF converter. */
@Component
@RequiredArgsConstructor
public class DsarPdfRenderer {

    private final HtmlToPdfConverter converter;

    public byte[] render(DsarExport export) {
        StringBuilder html = new StringBuilder("<html><body>");
        html.append("<h1>Data Subject Access Request</h1>");
        html.append("<p>Customer: ").append(esc(export.customerNumber()))
            .append(" &nbsp; Generated: ").append(esc(String.valueOf(export.generatedAt())))
            .append("</p>");
        section(html, "Customer", List.of(export.customer()));
        section(html, "Directors", export.directors());
        section(html, "Documents", export.documents());
        section(html, "Policies", export.policies());
        section(html, "Quotes", export.quotes());
        section(html, "Claims", export.claims());
        section(html, "Endorsements", export.endorsements());
        section(html, "Debit Notes", export.debitNotes());
        section(html, "Receipts", export.receipts());
        section(html, "Credit Notes", export.creditNotes());
        section(html, "Payments", export.payments());
        section(html, "Audit History", export.auditHistory());
        html.append("</body></html>");
        try {
            return converter.convert(html.toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to render DSAR PDF", e);
        }
    }

    private void section(StringBuilder html, String title, List<Map<String, Object>> rows) {
        html.append("<h2>").append(esc(title)).append("</h2>");
        if (rows == null || rows.isEmpty()) {
            html.append("<p>(none)</p>");
            return;
        }
        for (Map<String, Object> row : rows) {
            html.append("<p>");
            row.forEach((k, v) -> html.append("<b>").append(esc(k)).append(":</b> ")
                    .append(esc(String.valueOf(v))).append(" &nbsp; "));
            html.append("</p>");
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
```
> Confirm `HtmlToPdfConverter` is a `@Component` with a public no-arg constructor (it is) and `convert(String) throws IOException`. The exact HTML tags it supports (`h1`/`h2`/`p`/`b`) — check `renderNode` in `HtmlToPdfConverter`; if a tag is unsupported it's typically rendered as text, which is acceptable. The test only asserts a non-empty `%PDF-` blob.

- [ ] **Step 4: Run the test, expect green**

Run: `cd cia-backend && mvn -q -pl cia-compliance test -Dtest=DsarPdfRendererTest`
Expected: 1 test passes.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarPdfRenderer.java cia-backend/cia-compliance/src/test/java/com/nubeero/cia/compliance/dsar/DsarPdfRendererTest.java
git commit -m "feat(compliance): DsarPdfRenderer (human-readable PDF via HtmlToPdfConverter)"
```

---

### Task 8: `DsarExportService` + `DsarExportController` (ZIP / format + audit) + IT

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarExportService.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarExportController.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/DsarExportServiceIT.java`

- [ ] **Step 1: Write the failing IT**

`DsarExportServiceIT.java`:
```java
package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.compliance.dsar.DsarExportService;
import com.nubeero.cia.compliance.dsar.DsarGatherService;
import com.nubeero.cia.compliance.dsar.DsarJsonRenderer;
import com.nubeero.cia.compliance.dsar.DsarPdfRenderer;
import com.nubeero.cia.documents.HtmlToPdfConverter;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({DsarExportService.class, DsarGatherService.class, DsarJsonRenderer.class,
        DsarPdfRenderer.class, HtmlToPdfConverter.class})
class DsarExportServiceIT extends ComplianceItSupport {

    @Autowired DsarExportService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void zipBundleContainsBothJsonAndPdf() throws Exception {
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, " +
                "first_name, last_name, created_by) VALUES (?,?,?,?,?,?, 'test')",
                customerId, "CUST-ZIP-1", "INDIVIDUAL", "PASSED", "Ada", "Obi");

        byte[] zip = service.exportZip("test-tenant", customerId, "system");

        boolean hasJson = false, hasPdf = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            var e = zin.getNextEntry();
            while (e != null) {
                if (e.getName().endsWith(".json")) hasJson = true;
                if (e.getName().endsWith(".pdf")) hasPdf = true;
                e = zin.getNextEntry();
            }
        }
        assertThat(hasJson).isTrue();
        assertThat(hasPdf).isTrue();

        // a metadata-only audit row was written (no PII payload)
        Long audits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'SEND'",
                Long.class, customerId.toString());
        assertThat(audits).isEqualTo(1L);
    }
}
```

- [ ] **Step 2: Run it (compile-fail expected)**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=DsarExportServiceIT`
Expected: COMPILE FAIL.

- [ ] **Step 3: The service (gather → render → ZIP → audit)**

`DsarExportService.java`:
```java
package com.nubeero.cia.compliance.dsar;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Orchestrates a DSAR export: gather footprint → render JSON+PDF → ZIP → write a metadata-only audit row. */
@Service
@RequiredArgsConstructor
public class DsarExportService {

    private final DsarGatherService gather;
    private final DsarJsonRenderer json;
    private final DsarPdfRenderer pdf;
    private final AuditService audit;

    public byte[] renderJson(UUID customerId) {
        return json.render(gather.gather(customerId));
    }

    public byte[] renderPdf(UUID customerId) {
        return pdf.render(gather.gather(customerId));
    }

    /** Default DSAR download — a ZIP of both files. Writes the audit row exactly once. */
    public byte[] exportZip(String tenantId, UUID customerId, String requestedBy) {
        DsarExport export = gather.gather(customerId);
        byte[] jsonBytes = json.render(export);
        byte[] pdfBytes = pdf.render(export);
        byte[] zip = zip(export.customerNumber(), jsonBytes, pdfBytes);
        recordAudit(customerId, requestedBy);
        return zip;
    }

    private byte[] zip(String customerNumber, byte[] jsonBytes, byte[] pdfBytes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("dsar-" + customerNumber + ".json"));
            zos.write(jsonBytes);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dsar-" + customerNumber + ".pdf"));
            zos.write(pdfBytes);
            zos.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build DSAR ZIP", e);
        }
        return baos.toByteArray();
    }

    /** Metadata-only audit — records THAT a DSAR was produced, never the exported PII. */
    private void recordAudit(UUID customerId, String requestedBy) {
        audit.logWithReason("Customer", customerId.toString(), AuditAction.SEND,
                null, Map.of("dsarExportedBy", requestedBy == null ? "system" : requestedBy),
                "NDPR_DSAR_EXPORT");
    }
}
```
> Confirm `AuditService.logWithReason(String, String, AuditAction, Object, Object, String)` signature (it exists per recon). If `logWithReason` serializes `newValue` to JSON, a small `Map` is fine. `AuditAction.SEND` exists.

- [ ] **Step 4: The controller (format param → single file or ZIP)**

`DsarExportController.java`:
```java
package com.nubeero.cia.compliance.dsar;

import com.nubeero.cia.common.tenant.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/{id}/dsar-export")
@RequiredArgsConstructor
public class DsarExportController {

    private final DsarExportService service;

    @GetMapping
    @PreAuthorize("hasRole('DATA_PROTECTION')")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,
                                         @RequestParam(required = false) String format,
                                         @AuthenticationPrincipal Jwt jwt) {
        String tenantId = TenantContext.getTenantId();
        String actor = jwt != null ? jwt.getClaimAsString("preferred_username") : "system";

        if ("json".equalsIgnoreCase(format)) {
            return file(service.renderJson(id), "dsar-" + id + ".json", MediaType.APPLICATION_JSON);
        }
        if ("pdf".equalsIgnoreCase(format)) {
            return file(service.renderPdf(id), "dsar-" + id + ".pdf", MediaType.APPLICATION_PDF);
        }
        byte[] zip = service.exportZip(tenantId, id, actor);
        return file(zip, "dsar-" + id + ".zip", MediaType.parseMediaType("application/zip"));
    }

    private ResponseEntity<byte[]> file(byte[] body, String filename, MediaType type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(type)
                .body(body);
    }
}
```
> Confirm `TenantContext.getTenantId()` (it exists — `cia-common/.../tenant/TenantContext.java`). Note: the single-file `?format=json|pdf` paths intentionally do NOT write the audit row (only the canonical ZIP export does), so the audit count stays exactly one per fulfilled DSAR; if you want single-file fetches audited too, move `recordAudit` into `renderJson`/`renderPdf` — but keep it **once per request**.

- [ ] **Step 5: Run the IT, expect green**

Run: `cd cia-backend && mvn -q -pl cia-api test -Dtest=DsarExportServiceIT`
Expected: 1 test passes (ZIP has both entries + exactly one SEND audit row).

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarExportService.java cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/dsar/DsarExportController.java cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/DsarExportServiceIT.java
git commit -m "feat(compliance): DSAR export endpoint (JSON+PDF ZIP, format param, audit)"
```

---

### Task 9: Full module + reactor verify

- [ ] **Step 1: Build + test the module and its ITs**

Run: `cd cia-backend && mvn -q -pl cia-compliance,cia-api -am test -Dtest='RetentionPolicy*,Dsar*'`
Expected: all compliance unit tests + the 3 ITs (RetentionPolicyControllerIT, DsarGatherServiceIT, DsarExportServiceIT) pass.

- [ ] **Step 2: Confirm nothing else broke (the new module + role + migration touch shared wiring)**

Run: `cd cia-backend && mvn -q -pl cia-api -am install -DskipTests` then `mvn -q -pl cia-api test -Dtest='*BootstrapRoles*,*Keycloak*'` (whichever tests cover BootstrapRoles / provisioning) — confirm the added `DATA_PROTECTION` role didn't break role-count assertions. If a provisioning IT asserts an exact role count, update it to include `DATA_PROTECTION` (that's a legitimate same-task fix).

Expected: green. Address any role-count assertion that the new role shifts.

- [ ] **Step 3: Commit any follow-on test fixups**

```bash
git add -A
git commit -m "test(compliance): reconcile role-count assertions for DATA_PROTECTION"
```
(Skip if nothing needed fixing.)

---

## Self-Review (completed by plan author)

**1. Spec coverage (Slice A scope):**
- `cia-compliance` module + zero business deps → Task 1 (pom: cia-common/cia-auth/cia-documents only). ✓
- `data_retention_policy` (config + schedule fields + opt-in) → Task 2 (migration + entity), Task 3 (service + validation), Task 4 (endpoint). ✓
- `DATA_PROTECTION` role → Task 1 (BootstrapRoles) + every controller `@PreAuthorize`. ✓
- DSAR export (gather + decrypt + JSON + PDF + ZIP + `?format=` + audit-metadata-only) → Tasks 5–8. ✓
- ITs with pgcrypto + pii_key → `ComplianceItSupport` (Task 4) reused by Tasks 5 & 8. ✓
- **Not in Slice A (Slice B):** the purge workflow, `last_purge_run_at` writes, multi-tenant sweep, `cia-storage`/`cia-workflow` deps. Correctly absent.

**2. Placeholder scan:** No "TBD"/"add error handling". The few "confirm against the real source" notes are concrete single-line verifications (exception constructor shapes, `EntityManager` injection idiom, Jackson module, HTML tag support) — each names the file + the expected shape; they are verifications, not unwritten code.

**3. Type consistency:** `DsarExport` record field names match across `DsarGatherService` (producer), `DsarJsonRenderer` + `DsarPdfRenderer` + `DsarExportService` (consumers). `RetentionPolicyRequest`/`Response` fields match the entity + the service. `DATA_PROTECTION` role string is identical in BootstrapRoles + all four `@PreAuthorize`. Migration column names match the entity `@Column` names. `exportZip(tenantId, customerId, requestedBy)` signature matches the controller call + the IT.

**Known risk to watch during execution:** the `@DataJpaTest`-based `ComplianceItSupport` may need `@Import(CiaCommonAutoConfiguration.class)` for `BaseEntity` auditing and may not exercise `@PreAuthorize` (web-layer auth). If web-layer 403 coverage is wanted, add one `@SpringBootTest`+`MockMvc` test using whatever `FinanceWebItSupport`-style base exists; otherwise the `@PreAuthorize` annotations + the role wiring are the contract and the service ITs cover behavior.
