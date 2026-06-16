# NDPR Slice B — Scheduled PII Retention Purge Workflow — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `CustomerPiiPurgeWorkflow` — a single global hourly Temporal cron that sweeps `public.tenants WHERE active`, and per tenant anonymizes the master PII of customers past their (opt-in, per-tenant configurable) retention window, while leaving NAICOM/NIID-mandated policy/claim snapshots intact.

**Architecture:** Extends the existing `cia-compliance` module (Slice A shipped the `data_retention_policy` config + DSAR export). A thin deterministic workflow loops over active tenant schemas; all DB/clock/IO work lives in two activities (`listActiveTenants`, `purgeTenant`). Tenant context is set per-activity and auto-cleared by the existing `TenantAwareWorkerInterceptor`. Mirrors the `PdfDownloadLogRetentionWorkflow` cron pattern + the `RetroactiveJournalBackfill` multi-tenant precedent.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Temporal (cron workflow + `TestWorkflowEnvironment`), PostgreSQL + pgcrypto (Flyway V70), Testcontainers.

**Source design:** `docs/superpowers/specs/2026-06-15-ndpr-dsar-retention-design.md` §6–§8 (authoritative — follow it, not any contradictory convenience).

**Branch:** `feature/ndpr-retention-purge` (off `main`; already created).

---

## Design fidelity guardrails (read before starting)

- **Sentinel = `customers.pii_purged_at`** (a new column, V70). NOT a separate purge-log table.
- **Audit = the existing `audit_log`** via `AuditService.logWithReason(...)`, metadata-only. NOT a new table.
- **Eligibility (§6.3) = exactly three conditions:** `pii_purged_at IS NULL` AND no `ACTIVE` policy AND `last_activity < cutoff`. Do NOT add a `deleted_at`-based shortcut (a normally soft-deleted customer still bears PII and is purged on the same clock).
- **`last_activity` (§6.3) = `GREATEST(MAX(policies.policy_end_date), MAX(claims.reported_date))`, falling back to `customers.created_at`** when the customer has no policies/claims.
- **`cia-compliance` must NOT depend on `cia-api`.** The sweep activity queries `public.tenants` itself via a native query (it runs with no tenant context → resolver returns `public`), not via `TenantRegistry`.
- **Opt-in:** nothing is purged unless that tenant's `purge_enabled = true`. This is the single most important safety rail — every test must respect it.

---

## File Structure

New files (all under `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/` unless noted):

- `PurgeWindow.java` — pure static helper: window-match + debounce decision (unit-testable, no DB/clock injection beyond a passed-in `Instant`).
- `CustomerPurgeRepository.java` — native eligibility + anonymize SQL (uses `EntityManager`, mirrors `DsarGatherService`).
- `CustomerPiiPurgeService.java` — per-customer anonymize orchestration (read blob paths → delete blobs → anonymize rows → delete directors → audit), `@Transactional` per customer + `REQUIRES_NEW` audit.
- `CompliancePurgeActivities.java` — `@ActivityInterface`: `List<String> listActiveTenants()` + `PurgeTenantResult purgeTenant(String schema)`.
- `CompliancePurgeActivitiesImpl.java` — sets `TenantContext`, window/debounce gate, claims window, calls service per eligible customer, per-customer/per-tenant failure isolation.
- `PurgeTenantResult.java` — record `{schema, ran, customersPurged, skippedReason}`.
- `CustomerPiiPurgeWorkflow.java` — `@WorkflowInterface`, `@WorkflowMethod void purge();`.
- `CustomerPiiPurgeWorkflowImpl.java` — thin deterministic sweep loop.
- `ComplianceWorkerConfig.java` — `@PostConstruct` worker registration on `COMPLIANCE_QUEUE` + hourly cron scheduling (mirrors `NotificationsWorkerConfig`).

Modified:
- `cia-backend/cia-compliance/pom.xml` — add `cia-workflow` + `cia-storage` deps.
- `cia-backend/cia-workflow/src/main/java/com/nubeero/cia/workflow/TemporalQueues.java` — add `COMPLIANCE_QUEUE`.
- `cia-backend/cia-api/src/main/resources/db/migration/V70__customer_pii_purged_at.sql` — new migration.
- `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/ComplianceItSupport.java` — bump flyway target `69` → `70`.

New tests (under `cia-backend/cia-compliance/src/test/...` for pure units; `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/` for Testcontainers ITs — that's where Flyway is on the classpath):
- `PurgeWindowTest.java` (cia-compliance unit).
- `CustomerPurgeEligibilityIT.java`, `CustomerPiiPurgeServiceIT.java`, `CompliancePurgeActivitiesIT.java`, `CustomerPiiPurgeWorkflowIT.java` (cia-api ITs).

---

## Task 1: Build wiring — deps + queue constant

**Files:**
- Modify: `cia-backend/cia-compliance/pom.xml`
- Modify: `cia-backend/cia-workflow/src/main/java/com/nubeero/cia/workflow/TemporalQueues.java`

- [ ] **Step 1: Add the queue constant**

In `TemporalQueues.java`, alongside the existing constants (`BACKFILL_QUEUE`, `NOTIFICATIONS_QUEUE`, …), add:

```java
public static final String COMPLIANCE_QUEUE = "compliance-queue";
```

- [ ] **Step 2: Add module deps to cia-compliance pom**

In `cia-compliance/pom.xml`, after the existing `cia-documents` dependency, add (versions come from the parent `<dependencyManagement>`):

```xml
<dependency>
  <groupId>com.nubeero.cia</groupId>
  <artifactId>cia-workflow</artifactId>
</dependency>
<dependency>
  <groupId>com.nubeero.cia</groupId>
  <artifactId>cia-storage</artifactId>
</dependency>
```

- [ ] **Step 3: Verify it resolves**

Run: `cd cia-backend && mvn -q -o install -DskipTests -pl cia-workflow,cia-compliance -am`
Expected: BUILD SUCCESS (cia-compliance now has the Temporal + storage APIs on its classpath).

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-compliance/pom.xml cia-backend/cia-workflow/src/main/java/com/nubeero/cia/workflow/TemporalQueues.java
git commit -m "build(compliance): COMPLIANCE_QUEUE + cia-workflow/cia-storage deps for Slice B"
```

---

## Task 2: V70 migration — the `pii_purged_at` sentinel + IT target bump

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V70__customer_pii_purged_at.sql`
- Modify: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/ComplianceItSupport.java`

- [ ] **Step 1: Write the migration**

The migration is unqualified (lands in `public` + every tenant schema via the per-schema sweep), matching V69's style.

```sql
-- V70: NDPR retention-purge idempotency sentinel.
-- Set to now() when a customer's master PII is anonymized by the retention purge
-- (Slice B). NULL = never purged. The purge eligibility query filters on
-- pii_purged_at IS NULL so an anonymized customer is never re-processed.
ALTER TABLE customers ADD COLUMN IF NOT EXISTS pii_purged_at TIMESTAMPTZ;

-- Partial index: the hourly purge sweep repeatedly scans for not-yet-purged
-- customers; index only the rows it cares about.
CREATE INDEX IF NOT EXISTS ix_customers_pii_not_purged
    ON customers (id) WHERE pii_purged_at IS NULL;
```

- [ ] **Step 2: Bump the IT Flyway target**

In `ComplianceItSupport.java`, change the target line:

```java
registry.add("spring.flyway.target", () -> "70");   // was "69"
```

- [ ] **Step 3: Verify migration applies cleanly**

Run: `cd cia-backend && mvn -q -o install -DskipTests -pl cia-compliance && mvn -q -o -pl cia-api test -Dtest='DsarGatherServiceIT'`
Expected: PASS — the existing Slice A gather IT still green against the V70 schema (proves the migration applies and nothing regressed).

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/db/migration/V70__customer_pii_purged_at.sql cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/ComplianceItSupport.java
git commit -m "feat(compliance): V70 customers.pii_purged_at sentinel + IT target 70"
```

---

## Task 3: `PurgeWindow` — pure window-match + debounce decision (TDD)

The window/debounce logic is the only non-trivial pure logic; isolate it so it's unit-testable with a passed-in `Instant` (no clock, no DB, no Temporal).

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/PurgeWindow.java`
- Test: `cia-backend/cia-compliance/src/test/java/com/nubeero/cia/compliance/purge/PurgeWindowTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.nubeero.cia.compliance.purge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class PurgeWindowTest {

    // Sunday 2026-06-14 03:00:00 UTC
    private static final Instant SUNDAY_0300 =
            ZonedDateTime.of(2026, 6, 14, 3, 0, 0, 0, ZoneOffset.UTC).toInstant();

    @Test
    void weekly_matchesOnConfiguredDayAndHour() {
        assertThat(PurgeWindow.matches(SUNDAY_0300, "WEEKLY", 0, 3)).isTrue();   // Sunday=0, 03:00
    }

    @Test
    void weekly_noMatchOnWrongHour() {
        assertThat(PurgeWindow.matches(SUNDAY_0300, "WEEKLY", 0, 4)).isFalse();
    }

    @Test
    void weekly_noMatchOnWrongDay() {
        assertThat(PurgeWindow.matches(SUNDAY_0300, "WEEKLY", 1, 3)).isFalse(); // configured Monday
    }

    @Test
    void monthly_matchesOnlyOnDayOne() {
        Instant firstOfMonth0300 =
                ZonedDateTime.of(2026, 7, 1, 3, 0, 0, 0, ZoneOffset.UTC).toInstant();
        assertThat(PurgeWindow.matches(firstOfMonth0300, "MONTHLY", 5, 3)).isTrue(); // day-of-week ignored
        assertThat(PurgeWindow.matches(SUNDAY_0300, "MONTHLY", 0, 3)).isFalse();      // 14th, not the 1st
    }

    @Test
    void debounce_blocksWhenLastRunInsideWindow() {
        // last run 1h ago → still inside the 23h debounce → blocked
        assertThat(PurgeWindow.debouncePassed(SUNDAY_0300, SUNDAY_0300.minusSeconds(3600))).isFalse();
        // last run never → allowed
        assertThat(PurgeWindow.debouncePassed(SUNDAY_0300, null)).isTrue();
        // last run 24h ago → outside debounce → allowed
        assertThat(PurgeWindow.debouncePassed(SUNDAY_0300, SUNDAY_0300.minusSeconds(24 * 3600))).isTrue();
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `cd cia-backend && mvn -q -o -pl cia-compliance test -Dtest=PurgeWindowTest`
Expected: FAIL — `PurgeWindow` does not exist.

- [ ] **Step 3: Implement `PurgeWindow`**

```java
package com.nubeero.cia.compliance.purge;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Pure decision logic for the hourly retention-purge cron: does a tenant's configured
 * window match the current UTC hour, and has the per-window debounce elapsed.
 * No clock, DB, or Temporal coupling — {@code now} is always supplied by the caller.
 */
public final class PurgeWindow {

    /** A window already fired this run if it ran less than this ago (≈ once per scheduled window). */
    private static final Duration DEBOUNCE = Duration.ofHours(23);

    private PurgeWindow() {}

    /** True iff {@code now} (UTC) falls in the tenant's configured purge window. */
    public static boolean matches(Instant now, String frequency, int dayOfWeek, int hourUtc) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        if (utc.getHour() != hourUtc) {
            return false;
        }
        if ("MONTHLY".equals(frequency)) {
            return utc.getDayOfMonth() == 1;
        }
        // WEEKLY: java DayOfWeek is MON=1..SUN=7; config is SUN=0..SAT=6.
        int configDow = utc.getDayOfWeek().getValue() % 7; // SUN(7)→0, MON(1)→1, … SAT(6)→6
        return configDow == dayOfWeek;
    }

    /** True iff enough time has elapsed since the last run to fire again. */
    public static boolean debouncePassed(Instant now, Instant lastPurgeRunAt) {
        return lastPurgeRunAt == null || lastPurgeRunAt.isBefore(now.minus(DEBOUNCE));
    }
}
```

- [ ] **Step 4: Run to confirm pass**

Run: `cd cia-backend && mvn -q -o -pl cia-compliance test -Dtest=PurgeWindowTest`
Expected: PASS (5/5).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/PurgeWindow.java cia-backend/cia-compliance/src/test/java/com/nubeero/cia/compliance/purge/PurgeWindowTest.java
git commit -m "feat(compliance): PurgeWindow window-match + debounce (pure, unit-tested)"
```

---

## Task 4: Eligibility query (TDD against Testcontainers)

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/CustomerPurgeRepository.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/CustomerPurgeEligibilityIT.java`

- [ ] **Step 1: Write the failing IT**

Seed four customers and assert only the eligible one is returned. (Reuse the `JdbcTemplate` + `customers`/`policies` insert idiom from `DsarGatherServiceIT`.)

```java
package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.compliance.purge.CustomerPurgeRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({CiaCommonAutoConfiguration.class, CustomerPurgeRepository.class})
class CustomerPurgeEligibilityIT extends ComplianceItSupport {

    @Autowired CustomerPurgeRepository repo;

    @Test
    void selectsOnlyTheInactiveExpiredCustomer() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int retentionDays = 2555;

        // (a) ELIGIBLE — inactive, last policy ended 3000 days ago, no ACTIVE policy.
        UUID a = seedCustomer(jdbc, "CUST-A");
        seedPolicy(jdbc, a, "EXPIRED", LocalDate.now().minusDays(3000));

        // (b) INELIGIBLE — has an ACTIVE policy.
        UUID b = seedCustomer(jdbc, "CUST-B");
        seedPolicy(jdbc, b, "ACTIVE", LocalDate.now().minusDays(3000));

        // (c) INELIGIBLE — recently active (policy ended 10 days ago).
        UUID c = seedCustomer(jdbc, "CUST-C");
        seedPolicy(jdbc, c, "EXPIRED", LocalDate.now().minusDays(10));

        // (d) INELIGIBLE — already purged.
        UUID d = seedCustomer(jdbc, "CUST-D");
        seedPolicy(jdbc, d, "EXPIRED", LocalDate.now().minusDays(3000));
        jdbc.update("UPDATE customers SET pii_purged_at = now() WHERE id = ?", d);

        List<UUID> eligible = repo.findEligibleCustomerIds(retentionDays);

        assertThat(eligible).containsExactly(a);
    }

    private UUID seedCustomer(JdbcTemplate jdbc, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, "
                + "first_name, last_name, created_by) VALUES (?,?,?,?,?,?, 'test')",
                id, number, "INDIVIDUAL", "PASSED", "Ada", "Obi");
        return id;
    }

    private void seedPolicy(JdbcTemplate jdbc, UUID customerId, String status, LocalDate endDate) {
        jdbc.update("INSERT INTO policies (id, policy_number, status, customer_id, customer_name, "
                + "product_id, product_name, product_code, product_rate, "
                + "class_of_business_id, class_of_business_name, class_of_business_code, "
                + "business_type, policy_start_date, policy_end_date, "
                + "total_sum_insured, total_premium, net_premium) "
                + "VALUES (?,?,?,?,?, ?,?,?,?, ?,?,?, ?,?,?, ?,?,?)",
                UUID.randomUUID(), "POL-" + UUID.randomUUID(), status, customerId, "Ada Obi",
                UUID.randomUUID(), "Motor", "MOTOR", 5.0,
                UUID.randomUUID(), "Motor", "MOT",
                "DIRECT", endDate.minusYears(1), endDate, 1000000, 50000, 47500);
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd cia-backend && mvn -q -o install -DskipTests -pl cia-compliance && mvn -q -o -pl cia-api test -Dtest=CustomerPurgeEligibilityIT`
Expected: FAIL — `CustomerPurgeRepository` does not exist.

- [ ] **Step 3: Implement the eligibility query**

```java
package com.nubeero.cia.compliance.purge;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Native eligibility + anonymize SQL for the retention purge (runs in the active tenant schema). */
@Repository
@RequiredArgsConstructor
public class CustomerPurgeRepository {

    private final EntityManager em;

    /**
     * Purge-eligible customers (design §6.3): never purged, no ACTIVE policy, and last activity
     * older than the retention cutoff. last_activity = GREATEST(max policy_end_date, max
     * claim reported_date), falling back to customers.created_at.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<UUID> findEligibleCustomerIds(int retentionDays) {
        Query q = em.createNativeQuery(
            "SELECT c.id FROM customers c "
          + "WHERE c.pii_purged_at IS NULL "
          + "  AND NOT EXISTS (SELECT 1 FROM policies p "
          + "                  WHERE p.customer_id = c.id AND p.status = 'ACTIVE') "
          + "  AND COALESCE( "
          + "        GREATEST( "
          + "          (SELECT MAX(p.policy_end_date) FROM policies p WHERE p.customer_id = c.id), "
          + "          (SELECT MAX(cl.reported_date) FROM claims cl WHERE cl.customer_id = c.id) "
          + "        ), c.created_at::date "
          + "      ) < (current_date - CAST(:days AS integer)) "
          + "ORDER BY c.id");
        q.setParameter("days", retentionDays);
        List<?> raw = q.getResultList();
        return raw.stream().map(r -> (UUID) r).toList();
    }
}
```

> Note: native `getResultList()` returns the bare column for a single-column projection (`UUID` via the pgjdbc UUID mapping), not `Object[]`. If the driver yields `String`, map with `UUID.fromString(r.toString())` — verify in the IT and adjust.

- [ ] **Step 4: Run to confirm pass**

Run: `cd cia-backend && mvn -q -o install -DskipTests -pl cia-compliance && mvn -q -o -pl cia-api test -Dtest=CustomerPurgeEligibilityIT`
Expected: PASS — only CUST-A returned.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/CustomerPurgeRepository.java cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/CustomerPurgeEligibilityIT.java
git commit -m "feat(compliance): purge eligibility query (no ACTIVE policy + past retention)"
```

---

## Task 5: Anonymize-in-place + blob deletion + metadata audit (TDD)

**Files:**
- Modify: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/CustomerPurgeRepository.java` (add anonymize/read-paths methods)
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/CustomerPiiPurgeService.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/CustomerPiiPurgeServiceIT.java`

- [ ] **Step 1: Write the failing IT**

Seed an eligible individual customer with 2 directors + 1 document + a related policy; run `purgeCustomer`; assert PII nulled/tombstoned, `pii_purged_at` set, directors hard-deleted, blobs deleted (stub storage records the calls), the policy snapshot untouched, exactly one metadata-only `DELETE` audit row (no PII), and idempotency (second run is a no-op).

```java
package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nubeero.cia.common.audit.AuditLogRepository;
import com.nubeero.cia.common.audit.AuditService;
import com.nubeero.cia.common.config.CiaCommonAutoConfiguration;
import com.nubeero.cia.compliance.purge.CustomerPiiPurgeService;
import com.nubeero.cia.compliance.purge.CustomerPurgeRepository;
import com.nubeero.cia.storage.DocumentStorageService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({CiaCommonAutoConfiguration.class, CustomerPurgeRepository.class,
        CustomerPiiPurgeService.class, CustomerPiiPurgeServiceIT.TestSupportConfig.class})
class CustomerPiiPurgeServiceIT extends ComplianceItSupport {

    @Autowired CustomerPiiPurgeService service;
    @Autowired DocumentStorageService storage;   // mock from TestSupportConfig

    @Test
    void anonymizesCustomer_deletesDirectorsAndBlobs_auditsMetadataOnly_idempotent() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, customer_number, customer_type, kyc_status, "
            + "first_name, last_name, id_number, id_document_url, address, created_by) VALUES (?,?,?,?,?,?, "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'test')",
            id, "CUST-P", "INDIVIDUAL", "PASSED", "Ada", "Obi",
            "NIN-SECRET", "kyc/ada-id.pdf", "12 Marina St");
        jdbc.update("INSERT INTO customer_directors (id, customer_id, first_name, last_name, "
            + "id_number, id_document_url, kyc_status) VALUES (?,?,?,?, "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), "
            + "pgp_sym_encrypt(?, current_setting('app.pii_key')), 'PASSED')",
            UUID.randomUUID(), id, "Bola", "Obi", "NIN-DIR", "kyc/bola-id.pdf");
        jdbc.update("INSERT INTO customer_documents (id, customer_id, document_type, document_name, "
            + "document_path) VALUES (?,?,?,?,?)",
            UUID.randomUUID(), id, "ID_CARD", "nin.pdf", "kyc/nin.pdf");
        UUID policyId = UUID.randomUUID();
        jdbc.update("INSERT INTO policies (id, policy_number, status, customer_id, customer_name, "
            + "product_id, product_name, product_code, product_rate, class_of_business_id, "
            + "class_of_business_name, class_of_business_code, business_type, policy_start_date, "
            + "policy_end_date, total_sum_insured, total_premium, net_premium) "
            + "VALUES (?,?,?,?,?, ?,?,?,?, ?,?,?, ?,?,?, ?,?,?)",
            policyId, "POL-P", "EXPIRED", id, "Ada Obi", UUID.randomUUID(), "Motor", "MOTOR", 5.0,
            UUID.randomUUID(), "Motor", "MOT", "DIRECT",
            java.time.LocalDate.now().minusYears(2), java.time.LocalDate.now().minusYears(1),
            1000000, 50000, 47500);

        int retentionDays = 2555;
        service.purgeCustomer("test-tenant", id, retentionDays);

        // PII gone; stub remains.
        var row = jdbc.queryForMap("SELECT first_name, last_name, id_number, address, email, "
            + "pii_purged_at, customer_number FROM customers WHERE id = ?", id);
        assertThat(row.get("first_name")).isEqualTo("[ERASED]");
        assertThat(row.get("id_number")).isNull();
        assertThat(row.get("address")).isNull();
        assertThat(row.get("pii_purged_at")).isNotNull();
        assertThat(row.get("customer_number")).isEqualTo("CUST-P");   // operational stub kept

        // Directors hard-deleted; documents gone; blobs deleted.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_directors WHERE customer_id = ?",
            Long.class, id)).isZero();
        verify(storage).delete("test-tenant", "kyc/ada-id.pdf");
        verify(storage).delete("test-tenant", "kyc/bola-id.pdf");
        verify(storage).delete("test-tenant", "kyc/nin.pdf");

        // Regulatory snapshot untouched.
        assertThat(jdbc.queryForObject("SELECT customer_name FROM policies WHERE id = ?",
            String.class, policyId)).isEqualTo("Ada Obi");

        // Exactly one metadata-only DELETE audit row; no PII in it.
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'DELETE'",
            Long.class, id.toString())).isEqualTo(1L);
        String audit = jdbc.queryForObject("SELECT COALESCE(new_value::text,'') || COALESCE(old_value::text,'') "
            + "FROM audit_log WHERE entity_id = ? AND action = 'DELETE'", String.class, id.toString());
        assertThat(audit).doesNotContain("NIN-SECRET").doesNotContain("12 Marina St").doesNotContain("Ada");

        // Idempotent: second run is a no-op (already purged → not eligible / guarded).
        service.purgeCustomer("test-tenant", id, retentionDays);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'DELETE'",
            Long.class, id.toString())).isEqualTo(1L);
    }

    @TestConfiguration
    static class TestSupportConfig {
        @Bean @Primary ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
        @Bean AuditService auditService(AuditLogRepository repo, ObjectMapper mapper) {
            return new AuditService(repo, mapper, mock(ApplicationEventPublisher.class));
        }
        @Bean DocumentStorageService documentStorageService() {
            return mock(DocumentStorageService.class);
        }
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd cia-backend && mvn -q -o -pl cia-api test -Dtest=CustomerPiiPurgeServiceIT`
Expected: FAIL — `CustomerPiiPurgeService` does not exist.

- [ ] **Step 3: Add repository methods (read blob paths + anonymize + delete directors)**

Append to `CustomerPurgeRepository`:

```java
    /** Decrypted blob paths to delete from storage before the rows are erased. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<String> blobPathsFor(UUID customerId) {
        Query q = em.createNativeQuery(
            "SELECT pgp_sym_decrypt(id_document_url, current_setting('app.pii_key')) "
          + "  FROM customers WHERE id = CAST(:id AS uuid) AND id_document_url IS NOT NULL "
          + "UNION ALL "
          + "SELECT pgp_sym_decrypt(id_document_url, current_setting('app.pii_key')) "
          + "  FROM customer_directors WHERE customer_id = CAST(:id AS uuid) AND id_document_url IS NOT NULL "
          + "UNION ALL "
          + "SELECT document_path FROM customer_documents "
          + "  WHERE customer_id = CAST(:id AS uuid) AND deleted_at IS NULL");
        q.setParameter("id", customerId.toString());
        List<?> raw = q.getResultList();
        return raw.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    /** Anonymize the master PII in place (design §6.4). Returns rows affected (0 if already purged). */
    @Transactional
    public int anonymizeCustomer(UUID customerId) {
        Query q = em.createNativeQuery(
            "UPDATE customers SET "
          + "  id_number = NULL, id_document_url = NULL, address = NULL, "
          + "  date_of_birth = NULL, email = NULL, phone = NULL, alternate_phone = NULL, "
          + "  other_names = NULL, id_type = NULL, id_expiry_date = NULL, "
          + "  gender = NULL, marital_status = NULL, city = NULL, state = NULL, contact_person = NULL, "
          + "  blacklist_reason = NULL, kyc_provider_ref = NULL, kyc_failure_reason = NULL, "
          + "  first_name = CASE WHEN customer_type = 'INDIVIDUAL' THEN '[ERASED]' ELSE first_name END, "
          + "  last_name  = CASE WHEN customer_type = 'INDIVIDUAL' THEN '[ERASED]' ELSE last_name  END, "
          + "  pii_purged_at = now(), deleted_at = COALESCE(deleted_at, now()), updated_at = now() "
          + "WHERE id = CAST(:id AS uuid) AND pii_purged_at IS NULL");
        q.setParameter("id", customerId.toString());
        return q.executeUpdate();
    }

    @Transactional
    public int deleteDirectors(UUID customerId) {
        Query q = em.createNativeQuery("DELETE FROM customer_directors WHERE customer_id = CAST(:id AS uuid)");
        q.setParameter("id", customerId.toString());
        return q.executeUpdate();
    }

    @Transactional
    public int deleteDocuments(UUID customerId) {
        Query q = em.createNativeQuery("DELETE FROM customer_documents WHERE customer_id = CAST(:id AS uuid)");
        q.setParameter("id", customerId.toString());
        return q.executeUpdate();
    }

    /**
     * Claim the window: stamp last_purge_run_at on the singleton BEFORE the purge loop. MUST live on
     * the repository (not the activity) so it is a cross-bean call and the {@code @Transactional} proxy
     * actually applies — a self-invoked @Transactional from the Temporal-invoked activity is a no-op.
     */
    @Transactional
    public void stampLastPurgeRun(java.time.Instant now) {
        Query q = em.createNativeQuery(
            "UPDATE data_retention_policy SET last_purge_run_at = CAST(:now AS timestamptz) "
          + "WHERE deleted_at IS NULL");
        q.setParameter("now", now.toString());
        q.executeUpdate();
    }
```

> Corporate retention (design §6.4): the `CASE WHEN customer_type='INDIVIDUAL'` keeps `company_name`/`rc_number`/`cac_certificate_url`/`incorporation_date`/`industry` untouched for corporates (those columns are not in the SET list at all). Directors are deleted for both types.

- [ ] **Step 4: Implement the audit writer (SEPARATE bean — required for true `REQUIRES_NEW`)**

The metadata audit must be its own bean. `REQUIRES_NEW` only takes effect on a **cross-bean** call (Spring's transaction proxy never intercepts self-invocation), and the design §6.5 explicitly needs the audit in its own transaction so a failed audit write cannot roll back the completed purge.

```java
package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.common.audit.AuditAction;
import com.nubeero.cia.common.audit.AuditService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes the metadata-only retention-purge audit row in its OWN transaction (design §6.5). */
@Component
@Slf4j
@RequiredArgsConstructor
public class PurgeAuditWriter {

    private final AuditService audit;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(UUID customerId, int retentionDays, int directorsDeleted, int blobsDeleted) {
        // Metadata ONLY — never the erased PII (auditing the values would defeat the erasure).
        audit.logWithReason("Customer", customerId.toString(), AuditAction.DELETE, null,
            Map.of("customerId", customerId.toString(),
                   "retentionDays", retentionDays,
                   "directorsDeleted", directorsDeleted,
                   "blobsDeleted", blobsDeleted,
                   "purgedAt", Instant.now().toString()),
            "NDPR_RETENTION_PURGE");
    }
}
```

- [ ] **Step 5: Implement the service**

```java
package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.storage.DocumentStorageService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Anonymizes one customer's master PII: delete blobs → anonymize rows → delete directors/docs → audit. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CustomerPiiPurgeService {

    private final CustomerPurgeRepository repo;
    private final DocumentStorageService storage;
    private final PurgeAuditWriter auditWriter;

    /** Anonymize one customer. Returns true if a row was anonymized (false = already purged, no-op). */
    @Transactional
    public boolean purgeCustomer(String tenantId, UUID customerId, int retentionDays) {
        List<String> blobPaths = repo.blobPathsFor(customerId);
        int blobsDeleted = 0;
        for (String path : blobPaths) {
            try {
                storage.delete(tenantId, path);
                blobsDeleted++;
            } catch (RuntimeException ex) {
                log.warn("PII purge: blob delete failed for customer {} (continuing): {}",
                        customerId, ex.getMessage());
            }
        }
        int anonymized = repo.anonymizeCustomer(customerId);
        if (anonymized == 0) {
            return false; // already purged between eligibility scan and now — idempotent no-op
        }
        int directorsDeleted = repo.deleteDirectors(customerId);
        repo.deleteDocuments(customerId);
        // Cross-bean call so REQUIRES_NEW applies; swallow audit failure so the purge stands (§6.5).
        try {
            auditWriter.write(customerId, retentionDays, directorsDeleted, blobsDeleted);
        } catch (RuntimeException ex) {
            log.warn("PII purge: audit write failed for customer {} (purge stands): {}",
                    customerId, ex.getMessage());
        }
        return true;
    }
}
```

> The `CustomerPiiPurgeServiceIT` `TestSupportConfig` must also register `PurgeAuditWriter` as a bean (it's a `@Component` — add it to the `@Import` list or declare a `@Bean`).

- [ ] **Step 6: Run to confirm pass**

Run: `cd cia-backend && mvn -q -o install -DskipTests -pl cia-compliance && mvn -q -o -pl cia-api test -Dtest=CustomerPiiPurgeServiceIT`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/ cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/CustomerPiiPurgeServiceIT.java
git commit -m "feat(compliance): anonymize-in-place + blob delete + metadata-only DELETE audit"
```

---

## Task 6: Activities — tenant sweep, window/debounce gate, per-tenant purge (TDD)

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/PurgeTenantResult.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/CompliancePurgeActivities.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/CompliancePurgeActivitiesImpl.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/CompliancePurgeActivitiesIT.java`

- [ ] **Step 1: Write the failing IT**

Drive the activity directly (no Temporal). With `app.pii_key` + the single test schema, the activity's `purgeTenant` reads the `data_retention_policy`, applies the window gate (inject a fixed `Instant` via a test seam), and purges. Cover: opt-in off → no-op; window mismatch → no-op; matched window + eligible customer → purged + `last_purge_run_at` stamped; debounce → second call in-window is a no-op.

```java
package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// (imports mirror CustomerPiiPurgeServiceIT: AuditService/ObjectMapper/DocumentStorageService beans,
//  CustomerPurgeRepository, CustomerPiiPurgeService, RetentionPolicyService, CompliancePurgeActivitiesImpl)

@Import({/* CiaCommonAutoConfiguration + the purge beans + RetentionPolicyService + TestSupportConfig */})
class CompliancePurgeActivitiesIT extends ComplianceItSupport {

    @Autowired CompliancePurgeActivitiesImpl activities;

    // Sunday 2026-06-14 03:00 UTC — matches the default policy window (WEEKLY, day 0, hour 3).
    private static final java.time.Instant SUNDAY_0300 =
            java.time.ZonedDateTime.of(2026, 6, 14, 3, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant();

    @Test
    void optInOff_noPurge() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID elig = seedEligibleCustomer(jdbc);
        // default policy row is created by RetentionPolicyService.getOrCreate with purge_enabled=false
        var result = activities.purgeTenantAt("test-tenant", SUNDAY_0300);
        assertThat(result.ran()).isFalse();
        assertThat(jdbc.queryForObject("SELECT pii_purged_at FROM customers WHERE id = ?",
            Object.class, elig)).isNull();
    }

    @Test
    void matchedWindow_optInOn_purgesAndStampsAndDebounces() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID elig = seedEligibleCustomer(jdbc);
        enablePurge(jdbc);  // UPDATE data_retention_policy SET purge_enabled = true

        var first = activities.purgeTenantAt("test-tenant", SUNDAY_0300);
        assertThat(first.ran()).isTrue();
        assertThat(first.customersPurged()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pii_purged_at FROM customers WHERE id = ?",
            Object.class, elig)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT last_purge_run_at FROM data_retention_policy",
            Object.class)).isNotNull();

        // Debounce: a second fire one hour later (still inside the 23h window) is a no-op.
        var second = activities.purgeTenantAt("test-tenant", SUNDAY_0300.plusSeconds(3600));
        assertThat(second.ran()).isFalse();
    }

    @Test
    void wrongHour_noPurge() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedEligibleCustomer(jdbc);
        enablePurge(jdbc);
        var result = activities.purgeTenantAt("test-tenant", SUNDAY_0300.plusSeconds(3600)); // 04:00
        assertThat(result.ran()).isFalse();
    }
    // helpers: seedEligibleCustomer (inactive+expired, as in Task 4), enablePurge(jdbc)
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `cd cia-backend && mvn -q -o -pl cia-api test -Dtest=CompliancePurgeActivitiesIT`
Expected: FAIL — activity classes do not exist.

- [ ] **Step 3: Implement the result record + activity interface**

```java
// PurgeTenantResult.java
package com.nubeero.cia.compliance.purge;
public record PurgeTenantResult(String schema, boolean ran, int customersPurged, String skippedReason) {
    public static PurgeTenantResult skipped(String schema, String reason) {
        return new PurgeTenantResult(schema, false, 0, reason);
    }
}
```

```java
// CompliancePurgeActivities.java
package com.nubeero.cia.compliance.purge;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;

@ActivityInterface
public interface CompliancePurgeActivities {
    /** Active tenant schemas from public.tenants (runs with no tenant context → resolver = public). */
    @ActivityMethod
    List<String> listActiveTenants();

    /** Window-gate + (if matched) purge one tenant. */
    @ActivityMethod
    PurgeTenantResult purgeTenant(String schema);
}
```

- [ ] **Step 4: Implement the activity**

`purgeTenant(schema)` delegates to a package-visible `purgeTenantAt(schema, now)` so the IT can inject a fixed `Instant` (the public activity method passes `Instant.now()`). `RetentionPolicyService.getOrCreate()` (Slice A) provides the per-tenant policy row.

```java
package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.common.tenant.TenantContext;
import com.nubeero.cia.compliance.retention.DataRetentionPolicy;
import com.nubeero.cia.compliance.retention.RetentionPolicyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class CompliancePurgeActivitiesImpl implements CompliancePurgeActivities {

    private final EntityManager em;
    private final RetentionPolicyService policyService;
    private final CustomerPurgeRepository purgeRepo;
    private final CustomerPiiPurgeService purgeService;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<String> listActiveTenants() {
        // No tenant context here ⇒ resolver returns "public" ⇒ the registry lives there.
        Query q = em.createNativeQuery(
            "SELECT schema_name FROM public.tenants WHERE active = TRUE ORDER BY schema_name");
        return ((List<?>) q.getResultList()).stream().map(Object::toString).toList();
    }

    @Override
    public PurgeTenantResult purgeTenant(String schema) {
        return purgeTenantAt(schema, Instant.now());
    }

    /**
     * Public test seam — same logic with an injectable clock. PUBLIC (not package-visible) because the
     * IT lives in a different package ({@code com.nubeero.cia.api.compliance}). All DB writes it triggers
     * go through OTHER beans ({@code purgeRepo}, {@code purgeService}) so their @Transactional proxies apply.
     */
    public PurgeTenantResult purgeTenantAt(String schema, Instant now) {
        TenantContext.setTenantId(schema);   // interceptor clears it in finally
        DataRetentionPolicy policy = policyService.getOrCreate();
        if (!policy.isPurgeEnabled()) {
            return PurgeTenantResult.skipped(schema, "purge_disabled");
        }
        if (!PurgeWindow.matches(now, policy.getPurgeFrequency(),
                policy.getPurgeDayOfWeek(), policy.getPurgeHourUtc())) {
            return PurgeTenantResult.skipped(schema, "window_no_match");
        }
        if (!PurgeWindow.debouncePassed(now, policy.getLastPurgeRunAt())) {
            return PurgeTenantResult.skipped(schema, "debounced");
        }
        // Stamp last_purge_run_at BEFORE purging (claim the window). Cross-bean call so the repo's
        // @Transactional applies — a self-invoked @Transactional on this Temporal-invoked bean is a no-op.
        purgeRepo.stampLastPurgeRun(now);

        int purged = 0;
        for (UUID customerId : purgeRepo.findEligibleCustomerIds(policy.getCustomerPiiRetentionDays())) {
            try {
                if (purgeService.purgeCustomer(schema, customerId, policy.getCustomerPiiRetentionDays())) {
                    purged++;
                }
            } catch (RuntimeException ex) {
                log.warn("PII purge: customer {} in tenant {} failed (skipping): {}",
                        customerId, schema, ex.getMessage());
            }
        }
        return new PurgeTenantResult(schema, true, purged, null);
    }
}
```

> Notes for the implementer: (1) `RetentionPolicyService.getOrCreate()` (Slice A) reads the singleton in the current tenant schema. Confirm its name/visibility; if it returns a DTO rather than the entity, add a small entity-returning accessor or read the row directly. (2) `em` is now only used by `listActiveTenants()`; keep the field. (3) Because the activity is invoked by Temporal on the raw bean, NONE of its own methods are transactional — every DB write is delegated to `purgeRepo`/`purgeService`, which is the point.

- [ ] **Step 5: Run to confirm pass**

Run: `cd cia-backend && mvn -q -o install -DskipTests -pl cia-compliance && mvn -q -o -pl cia-api test -Dtest=CompliancePurgeActivitiesIT`
Expected: PASS (opt-in off, matched-window purge + stamp + debounce, wrong-hour no-op).

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/ cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/CompliancePurgeActivitiesIT.java
git commit -m "feat(compliance): purge activities — tenant sweep, window/debounce gate, claim+purge"
```

---

## Task 7: Workflow + worker config + hourly cron (TDD with TestWorkflowEnvironment)

**Files:**
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/CustomerPiiPurgeWorkflow.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/CustomerPiiPurgeWorkflowImpl.java`
- Create: `cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/ComplianceWorkerConfig.java`
- Test: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/CustomerPiiPurgeWorkflowIT.java`

- [ ] **Step 1: Write the failing workflow IT**

Mirror `SendReceiptEmailWorkflowIT`: a `TestWorkflowEnvironment`, register `CustomerPiiPurgeWorkflowImpl` + the real activities (wired to the Testcontainers DB), seed `public.tenants` with a single active schema pointing at the test schema + one eligible customer with `purge_enabled=true`, run `purge()`, assert the customer is anonymized.

```java
package com.nubeero.cia.api.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.nubeero.cia.compliance.purge.CompliancePurgeActivitiesImpl;
import com.nubeero.cia.compliance.purge.CustomerPiiPurgeWorkflow;
import com.nubeero.cia.compliance.purge.CustomerPiiPurgeWorkflowImpl;
import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({/* CiaCommonAutoConfiguration + all purge beans + RetentionPolicyService + TestSupportConfig */})
class CustomerPiiPurgeWorkflowIT extends ComplianceItSupport {

    @Autowired CompliancePurgeActivitiesImpl activities;
    private TestWorkflowEnvironment env;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(TemporalQueues.COMPLIANCE_QUEUE);
        worker.registerWorkflowImplementationTypes(CustomerPiiPurgeWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() { env.close(); }

    @Test
    void sweep_purgesEligibleCustomerInActiveTenant() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // The activity's listActiveTenants() reads public.tenants; in the single-schema test
        // harness "public" IS the schema holding the seeded data, so register it as active.
        jdbc.update("INSERT INTO public.tenants (id, schema_name, name, subdomain, active) "
            + "VALUES (?, 'public', 'Test', 'test', TRUE) ON CONFLICT DO NOTHING", UUID.randomUUID());
        UUID elig = seedEligibleCustomer(jdbc);     // inactive + expired (Task 4 helper)
        enablePurge(jdbc);

        CustomerPiiPurgeWorkflow wf = client.newWorkflowStub(CustomerPiiPurgeWorkflow.class,
            WorkflowOptions.newBuilder().setTaskQueue(TemporalQueues.COMPLIANCE_QUEUE)
                .setWorkflowId("purge-it-" + elig).build());
        wf.purge();   // runs to completion in the test env

        assertThat(jdbc.queryForObject("SELECT pii_purged_at FROM customers WHERE id = ?",
            Object.class, elig)).isNotNull();
    }
}
```

> The window gate uses real `Instant.now()` in `purgeTenant`. To keep this IT deterministic regardless of wall-clock, either (a) set the seeded policy's `purge_hour_utc`/`purge_day_of_week`/`purge_frequency` to match "now" before running, or (b) temporarily set `purge_frequency` to a value that matches the current hour. Prefer (a): compute the current UTC hour + day in the test and `UPDATE data_retention_policy` to match, so the window always matches during the run. Document this in the test.

- [ ] **Step 2: Run to confirm it fails**

Run: `cd cia-backend && mvn -q -o -pl cia-api test -Dtest=CustomerPiiPurgeWorkflowIT`
Expected: FAIL — workflow classes do not exist.

- [ ] **Step 3: Implement the workflow**

```java
// CustomerPiiPurgeWorkflow.java
package com.nubeero.cia.compliance.purge;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CustomerPiiPurgeWorkflow {
    @WorkflowMethod
    void purge();
}
```

```java
// CustomerPiiPurgeWorkflowImpl.java
package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;

/** Thin deterministic sweep: list active tenants → purge each. All IO + clock is in the activities. */
public class CustomerPiiPurgeWorkflowImpl implements CustomerPiiPurgeWorkflow {

    private static final Logger log = Workflow.getLogger(CustomerPiiPurgeWorkflowImpl.class);

    private final CompliancePurgeActivities activities = Workflow.newActivityStub(
        CompliancePurgeActivities.class,
        ActivityOptions.newBuilder()
            .setTaskQueue(TemporalQueues.COMPLIANCE_QUEUE)
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());

    @Override
    public void purge() {
        List<String> schemas = activities.listActiveTenants();
        for (String schema : schemas) {
            // Per-tenant isolation: one bad tenant must not abort the sweep. The activity itself
            // swallows per-customer errors; a hard activity failure is caught here.
            try {
                PurgeTenantResult result = activities.purgeTenant(schema);
                if (result.ran()) {
                    log.info("PII purge tenant {} → {} customers", schema, result.customersPurged());
                }
            } catch (Exception ex) {
                log.warn("PII purge tenant {} failed (continuing sweep): {}", schema, ex.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Implement the worker config (registration + hourly cron)**

```java
package com.nubeero.cia.compliance.purge;

import com.nubeero.cia.workflow.TemporalQueues;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Registers the compliance worker + schedules the hourly retention-purge cron. Mirrors NotificationsWorkerConfig. */
@Component
@Slf4j
@RequiredArgsConstructor
public class ComplianceWorkerConfig {

    private final WorkerFactory workerFactory;
    private final WorkflowClient workflowClient;
    private final CompliancePurgeActivitiesImpl purgeActivities;

    @PostConstruct
    public void registerComplianceWorker() {
        try {
            Worker worker = workerFactory.newWorker(TemporalQueues.COMPLIANCE_QUEUE);
            worker.registerWorkflowImplementationTypes(CustomerPiiPurgeWorkflowImpl.class);
            worker.registerActivitiesImplementations(purgeActivities);
            log.info("Registered Temporal worker on queue: {}", TemporalQueues.COMPLIANCE_QUEUE);
            scheduleRetentionPurge();
        } catch (Exception e) {
            log.warn("Could not register compliance Temporal worker (Temporal unavailable?): {}",
                    e.getMessage());
        }
    }

    private void scheduleRetentionPurge() {
        try {
            CustomerPiiPurgeWorkflow workflow = workflowClient.newWorkflowStub(
                CustomerPiiPurgeWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(TemporalQueues.COMPLIANCE_QUEUE)
                    .setWorkflowId("customer-pii-retention-purge-cron")
                    .setCronSchedule("0 * * * *")   // hourly; per-tenant window gate decides actual run
                    .build());
            WorkflowClient.start(workflow::purge);
            log.info("Scheduled customer-pii-retention-purge cron (hourly; per-tenant window-gated)");
        } catch (Exception e) {
            log.info("customer-pii-retention-purge cron already scheduled (idempotent): {}",
                    e.getMessage());
        }
    }
}
```

> `WorkerFactory` + `WorkflowClient` are beans from `cia-workflow`'s `TemporalConfig`. The IT suite `@MockBean`s the Temporal beans, so `@PostConstruct` registration must be defensive (the surrounding try/catch already is). The `ComplianceWorkerConfig` only loads in the full `cia-api` context, not the `@DataJpaTest` compliance ITs.

- [ ] **Step 5: Run to confirm pass**

Run: `cd cia-backend && mvn -q -o install -DskipTests -pl cia-compliance && mvn -q -o -pl cia-api test -Dtest=CustomerPiiPurgeWorkflowIT`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-compliance/src/main/java/com/nubeero/cia/compliance/purge/ cia-backend/cia-api/src/test/java/com/nubeero/cia/api/compliance/CustomerPiiPurgeWorkflowIT.java
git commit -m "feat(compliance): CustomerPiiPurgeWorkflow + ComplianceWorkerConfig hourly cron"
```

---

## Task 8: Module + reactor verify, side-discovery backlog, cia-log

**Files:**
- Modify: `cia-log.md` (session entry + backlog row)

- [ ] **Step 1: Full compliance suite green**

Run:
```bash
cd cia-backend && mvn -q -o install -DskipTests -pl cia-workflow,cia-storage,cia-compliance -am \
  && mvn -q -o -pl cia-compliance test \
  && mvn -q -o -pl cia-api test -Dtest='DsarGatherServiceIT,DsarExportServiceIT,RetentionPolicyControllerIT,CustomerPurgeEligibilityIT,CustomerPiiPurgeServiceIT,CompliancePurgeActivitiesIT,CustomerPiiPurgeWorkflowIT'
```
Expected: all green (Slice A 7 ITs + the new Slice B ITs + `PurgeWindowTest`).

- [ ] **Step 2: BootstrapRoles drift still green** (no new role added in Slice B, but confirm nothing regressed)

Run: `cd cia-backend && mvn -q -o -pl cia-api test -Dtest=BootstrapRolesDriftTest`
Expected: PASS.

- [ ] **Step 3: Log the side-discovery to the backlog**

Add to the canonical backlog table at the top of `cia-log.md` (design §6.2 + §9):

```
| pdf-retention-multitenant-gap | P3 | `PdfDownloadLogRetentionActivitiesImpl` purge doesn't sweep tenants | Surfaced building NDPR Slice B. The existing PDF-download-log retention cron does a bare `repository.deleteByDownloadedAtBefore(cutoff)` with no tenant iteration — under no tenant context it resolves to `public` and is a silent no-op in real multi-tenant. Tolerable for a log purge (not regulated PII), but it should adopt the same `public.tenants` sweep + per-tenant context the Slice B `CustomerPiiPurgeWorkflow` uses. Refactor `PdfDownloadLogRetentionWorkflow` to sweep active tenant schemas. |
```

- [ ] **Step 4: Write the cia-log session entry** documenting Slice B (commits, the V70 sentinel, the hourly-cron + per-tenant-window design, opt-in safety rail, the deferred on-demand-erasure + DPO-UI follow-ups, and the `pdf-retention-multitenant-gap` row added).

- [ ] **Step 5: Commit**

```bash
git add cia-log.md
git commit -m "docs(cia-log): NDPR Slice B — scheduled PII retention purge workflow"
```

---

## Self-review checklist (run before final review)

- Eligibility uses exactly §6.3's three conditions (no `deleted_at` shortcut). ✓ verify in Task 4 SQL.
- Sentinel is `customers.pii_purged_at` (V70), audit is metadata-only into `audit_log`. ✓ Tasks 2/5.
- Opt-in (`purge_enabled`) gate is the first check in `purgeTenantAt`. ✓ Task 6.
- Window-match + 23h debounce are pure + unit-tested. ✓ Task 3.
- `last_purge_run_at` is stamped BEFORE the purge loop (claim-the-window). ✓ Task 6.
- Corporate identity (`company_name`/`rc_number`/`cac_*`/`incorporation_date`/`industry`) is retained; directors deleted for both types. ✓ Task 5 SQL.
- Blob paths are read (decrypted) BEFORE the rows are nulled/deleted. ✓ Task 5 ordering.
- Per-customer + per-tenant failure isolation (try/catch at both levels). ✓ Tasks 6/7.
- `cia-compliance` gains NO dependency on `cia-api` (sweep queries `public.tenants` directly). ✓ Task 6.
- Regulatory snapshots (`policies.customer_name` etc.) untouched. ✓ asserted in Task 5 IT.
