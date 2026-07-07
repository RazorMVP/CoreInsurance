# Inward Facultative Reinsurance (v1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the inward-facultative-reinsurance lifecycle (create/renew/extend/cancel) end-to-end — a new `ri_fac_inwards` aggregate that records accepted premium as a real Finance receivable + balanced GL posting, generates a guaranty document, and surfaces through a rebuilt, fully-wired frontend.

**Architecture:** A new aggregate in `cia-reinsurance` that mirrors the outward `RiFacCover` file layout but with inward semantics (no `policy_id`; counterparty is a ceding insurer; receivable not payable). It crosses module boundaries via a Spring `ApplicationEvent` (`RiFacInwardAcceptedEvent`): `cia-finance` listens → creates a `DebitNote` receivable + a hardcoded 3-line GL posting; `cia-documents` generates the guaranty PDF. Simple income posting (immediate recognition) — **not** IFRS-17 PAA (deferred to backlog `fac-ifrs17-paa-workstream`, P2).

**Tech Stack:** Java 21 · Spring Boot 3.5.14 · Hibernate/JPA · Flyway (V75) · PostgreSQL · Apache PDFBox (via `cia-documents`) · Testcontainers (ITs) · React 18 + TS + zod + React Query + TanStack Table (frontend).

**Spec:** `docs/superpowers/specs/2026-07-07-inward-fac-reinsurance-design.md`.

## Global Constraints

- **Flyway:** never edit an existing migration; new file is `V75__inward_fac.sql`. One migration file.
- **List endpoints:** return `ApiResponse<List<T>>` with the array directly in `data` + `ApiMeta` (total/page/size) in `meta`; `@PageableDefault(size = 2000)`. Never serialise Spring `Page<T>` into `data`.
- **Money:** `BigDecimal`, scale 2, `RoundingMode.HALF_UP`. Rates `NUMERIC(10,6)` (premium_rate) / `NUMERIC(7,4)` (pct/commission).
- **All entities** extend `BaseEntity` (id UUID, created_at/by, updated_at, deleted_at soft delete). `@EnableJpaAuditing` fills the audit columns.
- **RBAC roles:** `REINSURANCE_VIEW` / `REINSURANCE_CREATE` / `REINSURANCE_UPDATE` (Spring authority `hasRole('REINSURANCE_*')`).
- **Doc generation must never throw** — log WARN, return null; the host flow tolerates a null `guaranty_document_path`.
- **GL postings** go through `JournalEntryService.post(...)` (the gateway); it re-checks `Σdebit == Σcredit` and enforces period-lock.
- **Frontend:** no mocks; use `validatedGet`/`validatedPost` (zod). `check-api-wiring.sh` + `check-dto-drift.mjs` + `pnpm --filter @cia/back-office build` must pass.
- **Build to run backend locally:** `cd cia-backend && mvn install -DskipTests -pl cia-api -am`, then run ITs with `mvn -pl <module> -am verify` (Testcontainers needs Docker).
- **Commit message trailer:** end every commit with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

**cia-reinsurance (new):** `RiFacInward.java` (entity), `RiFacInwardStatus.java` (enum), `RiFacInwardRepository.java`, `RiFacInwardCounter.java` + `RiFacInwardCounterRepository.java`, `RiFacInwardService.java`, `RiFacInwardController.java`, `dto/{CreateFacInwardRequest,RenewFacInwardRequest,ExtendFacInwardRequest,CancelFacInwardRequest,FacInwardResponse}.java`. **Modified:** `RiNumberService.java` (+`nextInwardFacReference`), `pom.xml` (+`cia-documents` dep).
**cia-common (new):** `event/RiFacInwardAcceptedEvent.java`.
**cia-finance (new):** `RiFacInwardAcceptedEventListener.java`. **Modified:** `DebitNoteService.java` (+`createForInwardFac`), `gl/SubledgerPostingService.java` (+inward COA constants + `EVENT_FAC_PREMIUM_ACCEPTED` + `replayFacPremiumAccepted`).
**cia-documents (new):** `InwardFacGuarantyContext.java`, `resources/document-templates/inward-fac-guaranty-default.html`. **Modified:** `DocumentGenerationService.java` (+method) + `DocumentGenerationServiceImpl.java` (+impl).
**cia-api:** `resources/db/migration/V75__inward_fac.sql`.
**Frontend:** `packages/api-client/src/modules/reinsurance.ts` (+DTO/schema/requests), `apps/back-office/.../fac/FACTab.tsx`, re-created `AddInwardFACSheet.tsx` + `InwardFACActionSheet.tsx`.

---

## Task 1: V75 migration — `ri_fac_inwards` + counter + indexes + 2 COA rows

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V75__inward_fac.sql`
- Read first: `cia-backend/cia-api/src/main/resources/db/migration/V32__seed_chart_of_accounts.sql` (to copy the exact `chart_of_account` INSERT column list) and `V10__create_reinsurance_tables.sql` (to copy the `ri_fac_covers` / `ri_fac_counters` column idioms — timestamp types, `deleted_at`, PK).

**Interfaces:**
- Produces: tables `ri_fac_inwards`, `ri_fac_inward_counters`; COA rows `4330` (income) + `5240` (expense).

- [ ] **Step 1: Read V32's `chart_of_account` INSERT and V10's table idioms**

Run: `grep -n "INSERT INTO chart_of_account" cia-backend/cia-api/src/main/resources/db/migration/V32__seed_chart_of_accounts.sql | head` and read ~10 lines after it to capture the exact column list (e.g. `(code, name, account_type, parent_code, ifrs17_role, ifrs9_role)` — plus whether `id` is supplied or defaulted). Read `V10__create_reinsurance_tables.sql`'s `ri_fac_covers` + `ri_fac_counters` DDL to match column types (`TIMESTAMPTZ` vs `timestamp`, `NUMERIC` precision, PK default).

- [ ] **Step 2: Write V75 (adjust the COA INSERT column list to match V32 exactly)**

```sql
-- V75: Inward Facultative Reinsurance (Module 6)
-- New aggregate mirroring ri_fac_covers (outward) but inward semantics:
-- no policy_id (external risk), ceding-insurer counterparty, receivable direction.

CREATE TABLE ri_fac_inwards (
    id                     UUID PRIMARY KEY,
    created_at             TIMESTAMPTZ NOT NULL,
    created_by             VARCHAR(100),
    updated_at             TIMESTAMPTZ,
    deleted_at             TIMESTAMPTZ,
    fac_inward_reference   VARCHAR(50)  NOT NULL UNIQUE,
    ceding_company_id      UUID         NOT NULL,
    ceding_company_name    VARCHAR(200) NOT NULL,
    class_of_business_id   UUID         NOT NULL,
    class_of_business_name VARCHAR(200) NOT NULL,
    risk_description       TEXT,
    sum_insured            NUMERIC(18,2) NOT NULL,
    our_share_pct          NUMERIC(7,4)  NOT NULL,
    accepted_sum_insured   NUMERIC(18,2) NOT NULL,
    premium_rate           NUMERIC(10,6) NOT NULL,
    gross_premium          NUMERIC(18,2) NOT NULL,
    commission_rate        NUMERIC(7,4)  NOT NULL DEFAULT 0,
    commission_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    net_premium            NUMERIC(18,2) NOT NULL,
    currency_code          CHAR(3)       NOT NULL DEFAULT 'NGN',
    cover_from             DATE NOT NULL,
    cover_to               DATE NOT NULL,
    status                 VARCHAR(30) NOT NULL,
    renewed_from_id        UUID REFERENCES ri_fac_inwards(id),
    guaranty_document_path VARCHAR(500),
    cancelled_by           VARCHAR(100),
    cancelled_at           TIMESTAMPTZ,
    cancellation_reason    TEXT
);

CREATE INDEX idx_ri_fac_inwards_ceding_company ON ri_fac_inwards(ceding_company_id);
CREATE INDEX idx_ri_fac_inwards_class          ON ri_fac_inwards(class_of_business_id);
CREATE INDEX idx_ri_fac_inwards_status         ON ri_fac_inwards(status);
CREATE INDEX idx_ri_fac_inwards_cover_from     ON ri_fac_inwards(cover_from);

CREATE TABLE ri_fac_inward_counters (
    year          INT PRIMARY KEY,
    last_sequence BIGINT NOT NULL
);

-- Inward FAC income/expense COA accounts (the inward receivable 1330 and the
-- inward LRC/LIC liabilities 2210/2220 already exist from V32; only the
-- income + expense side is missing). MATCH V32's chart_of_account column list.
INSERT INTO chart_of_account (code, name, account_type, parent_code, ifrs17_role, ifrs9_role) VALUES
    ('4330', 'Inward reinsurance premium income',     'INCOME',  '4300', NULL, NULL),
    ('5240', 'Inward reinsurance commission expense',  'EXPENSE', '5200', NULL, NULL);
```

> If V32 supplies `id` explicitly (e.g. `gen_random_uuid()`) or omits `ifrs9_role`, adjust the INSERT column list + values to match byte-for-byte. `pgcrypto` is pre-installed in `public`.

- [ ] **Step 3: Add a migration-applies IT**

**File:** `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/migration/V75InwardFacMigrationIT.java`

```java
package com.nubeero.cia.api.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
// ...use the repo's existing Testcontainers base for migration ITs (find one
// under cia-api/src/test that boots Flyway against a Testcontainers Postgres;
// mirror its class annotations).

import static org.assertj.core.api.Assertions.assertThat;

class V75InwardFacMigrationIT /* extends <ExistingFlywayItBase> */ {

    @Autowired JdbcTemplate jdbc;

    @Test
    void tablesAndCoaRowsExist() {
        Integer t = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'ri_fac_inwards'", Integer.class);
        assertThat(t).isEqualTo(1);
        Integer coa = jdbc.queryForObject(
            "SELECT count(*) FROM chart_of_account WHERE code IN ('4330','5240') AND deleted_at IS NULL", Integer.class);
        assertThat(coa).isEqualTo(2);
    }
}
```

- [ ] **Step 4: Run — expect pass**

Run: `cd cia-backend && mvn -pl cia-api -am verify -Dit.test=V75InwardFacMigrationIT -Dtest=none -DfailIfNoTests=false`
Expected: BUILD SUCCESS, `ri_fac_inwards` + the two COA rows exist.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/db/migration/V75__inward_fac.sql cia-backend/cia-api/src/test/java/com/nubeero/cia/api/migration/V75InwardFacMigrationIT.java
git commit -m "feat(reinsurance): V75 ri_fac_inwards table + inward COA accounts (4330/5240)"
```

---

## Task 2: Entity, status enum, counter, repository, reference minting

**Files:**
- Create: `cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/{RiFacInward,RiFacInwardStatus,RiFacInwardRepository,RiFacInwardCounter,RiFacInwardCounterRepository}.java`
- Modify: `cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/RiNumberService.java`
- Test: `cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/RiFacInwardReferenceIT.java`

**Interfaces:**
- Produces: `RiFacInward` (builder), `RiFacInwardStatus{ACTIVE,RENEWED,EXPIRED,CANCELLED}`, `RiFacInwardRepository.findAll(cedingCompanyId, classId, status, Pageable)` + `findByIdAndDeletedAtIsNull(UUID)`, `RiNumberService.nextInwardFacReference()` → `"FAC-IN-{yyyy}-{000000}"`.

- [ ] **Step 1: Status enum**

`RiFacInwardStatus.java`:
```java
package com.nubeero.cia.reinsurance;

public enum RiFacInwardStatus {
    ACTIVE,
    RENEWED,
    EXPIRED,
    CANCELLED
}
```

- [ ] **Step 2: Entity** (mirror `RiFacCover`: `@Builder`, `implements LockableByPeriod`)

`RiFacInward.java`:
```java
package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.entity.BaseEntity;
import com.nubeero.cia.common.entity.LockableByPeriod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "ri_fac_inwards")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class RiFacInward extends BaseEntity implements LockableByPeriod {

    @Column(name = "fac_inward_reference", unique = true, nullable = false, length = 50)
    private String facInwardReference;

    @Column(name = "ceding_company_id", nullable = false)
    private UUID cedingCompanyId;

    @Column(name = "ceding_company_name", nullable = false, length = 200)
    private String cedingCompanyName;

    @Column(name = "class_of_business_id", nullable = false)
    private UUID classOfBusinessId;

    @Column(name = "class_of_business_name", nullable = false, length = 200)
    private String classOfBusinessName;

    @Column(name = "risk_description", columnDefinition = "TEXT")
    private String riskDescription;

    @Column(name = "sum_insured", nullable = false, precision = 18, scale = 2)
    private BigDecimal sumInsured;

    @Column(name = "our_share_pct", nullable = false, precision = 7, scale = 4)
    private BigDecimal ourSharePct;

    @Column(name = "accepted_sum_insured", nullable = false, precision = 18, scale = 2)
    private BigDecimal acceptedSumInsured;

    @Column(name = "premium_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal premiumRate;

    @Column(name = "gross_premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossPremium;

    @Column(name = "commission_rate", nullable = false, precision = 7, scale = 4)
    @Builder.Default
    private BigDecimal commissionRate = BigDecimal.ZERO;

    @Column(name = "commission_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal commissionAmount = BigDecimal.ZERO;

    @Column(name = "net_premium", nullable = false, precision = 18, scale = 2)
    private BigDecimal netPremium;

    @Column(name = "currency_code", nullable = false, length = 3)
    @Builder.Default
    private String currencyCode = "NGN";

    @Column(name = "cover_from", nullable = false)
    private LocalDate coverFrom;

    @Column(name = "cover_to", nullable = false)
    private LocalDate coverTo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private RiFacInwardStatus status = RiFacInwardStatus.ACTIVE;

    @Column(name = "renewed_from_id")
    private UUID renewedFromId;

    @Column(name = "guaranty_document_path", length = 500)
    private String guarantyDocumentPath;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    /** Period-lock anchor = booking date = cover_from (parity with outward's booked-date anchor). */
    @Override
    public LocalDate getLockDate() {
        return coverFrom;
    }
}
```

> Verify `LockableByPeriod`'s method name by reading `cia-common/.../entity/LockableByPeriod.java` (it may be `getLockDate()` returning `LocalDate`; the `isReversal()` default is `false` and inward FAC is not a reversal, so don't override it).

- [ ] **Step 3: Counter + repository** (mirror `RiFacCounter` / `RiFacCounterRepository`)

`RiFacInwardCounter.java`:
```java
package com.nubeero.cia.reinsurance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ri_fac_inward_counters")
@Getter @Setter
public class RiFacInwardCounter {
    @Id
    @Column(name = "year", nullable = false)
    private int year;

    @Column(name = "last_sequence", nullable = false)
    private long lastSequence;
}
```

`RiFacInwardCounterRepository.java`:
```java
package com.nubeero.cia.reinsurance;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RiFacInwardCounterRepository extends JpaRepository<RiFacInwardCounter, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM RiFacInwardCounter c WHERE c.year = :year")
    Optional<RiFacInwardCounter> findByYearForUpdate(@Param("year") int year);
}
```

- [ ] **Step 4: Aggregate repository**

`RiFacInwardRepository.java`:
```java
package com.nubeero.cia.reinsurance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RiFacInwardRepository extends JpaRepository<RiFacInward, UUID> {

    @Query("""
            SELECT f FROM RiFacInward f
            WHERE f.deletedAt IS NULL
              AND (:cedingCompanyId IS NULL OR f.cedingCompanyId = :cedingCompanyId)
              AND (:classId IS NULL OR f.classOfBusinessId = :classId)
              AND (:status IS NULL OR f.status = :status)
            """)
    Page<RiFacInward> findAll(
            @Param("cedingCompanyId") UUID cedingCompanyId,
            @Param("classId") UUID classOfBusinessId,
            @Param("status") RiFacInwardStatus status,
            Pageable pageable);

    Optional<RiFacInward> findByIdAndDeletedAtIsNull(UUID id);
}
```

- [ ] **Step 5: Add `nextInwardFacReference()` to `RiNumberService`**

Inject `RiFacInwardCounterRepository riFacInwardCounterRepository;` (add to the constructor via the existing `@RequiredArgsConstructor` — just add the field) and add:
```java
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String nextInwardFacReference() {
        int year = Year.now().getValue();
        RiFacInwardCounter counter = riFacInwardCounterRepository.findByYearForUpdate(year)
                .orElseGet(() -> {
                    RiFacInwardCounter c = new RiFacInwardCounter();
                    c.setYear(year);
                    c.setLastSequence(0L);
                    return c;
                });
        counter.setLastSequence(counter.getLastSequence() + 1);
        riFacInwardCounterRepository.save(counter);
        return String.format("FAC-IN-%d-%06d", year, counter.getLastSequence());
    }
```

- [ ] **Step 6: Write the reference-sequence IT** (mirror an existing `cia-reinsurance` `@DataJpaTest` or `@SpringBootTest` IT; import `CiaCommonAutoConfiguration` if `@DataJpaTest` so auditing fires)

`RiFacInwardReferenceIT.java`:
```java
package com.nubeero.cia.reinsurance;

// mirror the annotations of an existing cia-reinsurance IT that touches the DB
// (e.g. an @SpringBootTest with Testcontainers, or @DataJpaTest + @Import(CiaCommonAutoConfiguration.class))
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class RiFacInwardReferenceIT /* extends <existing base> */ {

    @Autowired RiNumberService numberService;

    @Test
    void referencesAreSequentialWithinYear() {
        String a = numberService.nextInwardFacReference();
        String b = numberService.nextInwardFacReference();
        assertThat(a).matches("FAC-IN-\\d{4}-\\d{6}");
        int seqA = Integer.parseInt(a.substring(a.length() - 6));
        int seqB = Integer.parseInt(b.substring(b.length() - 6));
        assertThat(seqB).isEqualTo(seqA + 1);
    }
}
```

- [ ] **Step 7: Run — expect pass**

Run: `cd cia-backend && mvn -pl cia-reinsurance -am verify -Dit.test=RiFacInwardReferenceIT -Dtest=none -DfailIfNoTests=false`
Expected: BUILD SUCCESS; two sequential `FAC-IN-YYYY-000001`/`000002` references.

- [ ] **Step 8: Commit**

```bash
git add cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/RiFacInward*.java cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/RiNumberService.java cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/RiFacInwardReferenceIT.java
git commit -m "feat(reinsurance): RiFacInward entity, counter, repository, reference minting"
```

---

## Task 3: `RiFacInwardAcceptedEvent` (cia-common) + reinsurance DTOs

**Files:**
- Create: `cia-backend/cia-common/src/main/java/com/nubeero/cia/common/event/RiFacInwardAcceptedEvent.java`
- Create: `cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/dto/{CreateFacInwardRequest,RenewFacInwardRequest,ExtendFacInwardRequest,CancelFacInwardRequest,FacInwardResponse}.java`

**Interfaces:**
- Produces: `RiFacInwardAcceptedEvent(facInwardId, facInwardReference, cedingCompanyId, cedingCompanyName, classOfBusinessId, grossPremium, commissionAmount, netPremium, currencyCode)`; the five DTO records (consumed by the service + controller in Tasks 5 & 7).

- [ ] **Step 1: Event** (mirror `common/event/FacPremiumCededEvent.java`)

```java
package com.nubeero.cia.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/** Published when an inward FAC cover is accepted (create/renew) or extended
 *  (delta amounts). cia-finance listens → DebitNote receivable + GL posting. */
public record RiFacInwardAcceptedEvent(
        UUID facInwardId,
        String facInwardReference,
        UUID cedingCompanyId,
        String cedingCompanyName,
        UUID classOfBusinessId,
        BigDecimal grossPremium,
        BigDecimal commissionAmount,
        BigDecimal netPremium,
        String currencyCode
) {}
```

- [ ] **Step 2: Request DTOs**

`CreateFacInwardRequest.java`:
```java
package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateFacInwardRequest(
        @NotNull UUID cedingCompanyId,
        @NotNull UUID classOfBusinessId,
        String riskDescription,
        @NotNull @DecimalMin("0.01") BigDecimal sumInsured,
        @NotNull @DecimalMin("0.0001") @DecimalMax("100.0000") BigDecimal ourSharePct,
        @NotNull @DecimalMin("0.000001") BigDecimal premiumRate,
        BigDecimal commissionRate,
        String currencyCode,
        @NotNull LocalDate coverFrom,
        @NotNull LocalDate coverTo
) {}
```

`RenewFacInwardRequest.java`:
```java
package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Renew from a source cover: new term dates; premium terms carry over from
 *  the source unless overridden (v1 carries over — no overrides). */
public record RenewFacInwardRequest(
        @NotNull LocalDate coverFrom,
        @NotNull LocalDate coverTo
) {}
```

`ExtendFacInwardRequest.java`:
```java
package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Extend the current cover_to to newCoverTo (must be after the current cover_to). */
public record ExtendFacInwardRequest(
        @NotNull LocalDate newCoverTo
) {}
```

`CancelFacInwardRequest.java`:
```java
package com.nubeero.cia.reinsurance.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelFacInwardRequest(@NotBlank String reason) {}
```

`FacInwardResponse.java`:
```java
package com.nubeero.cia.reinsurance.dto;

import com.nubeero.cia.reinsurance.RiFacInwardStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FacInwardResponse(
        UUID id,
        String facInwardReference,
        UUID cedingCompanyId,
        String cedingCompanyName,
        UUID classOfBusinessId,
        String classOfBusinessName,
        String riskDescription,
        BigDecimal sumInsured,
        BigDecimal ourSharePct,
        BigDecimal acceptedSumInsured,
        BigDecimal premiumRate,
        BigDecimal grossPremium,
        BigDecimal commissionRate,
        BigDecimal commissionAmount,
        BigDecimal netPremium,
        String currencyCode,
        LocalDate coverFrom,
        LocalDate coverTo,
        RiFacInwardStatus status,
        UUID renewedFromId,
        String guarantyDocumentPath,
        String cancelledBy,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt
) {}
```

- [ ] **Step 3: Compile check**

Run: `cd cia-backend && mvn -q -pl cia-common,cia-reinsurance -am compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-common/src/main/java/com/nubeero/cia/common/event/RiFacInwardAcceptedEvent.java cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/dto/
git commit -m "feat(reinsurance): RiFacInwardAcceptedEvent + inward FAC DTOs"
```

---

## Task 4: Guaranty document (cia-documents) + cia-reinsurance dependency

**Files:**
- Create: `cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/InwardFacGuarantyContext.java`
- Create: `cia-backend/cia-documents/src/main/resources/document-templates/inward-fac-guaranty-default.html`
- Modify: `cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/DocumentGenerationService.java` (+method)
- Modify: `cia-backend/cia-documents/src/main/java/com/nubeero/cia/documents/DocumentGenerationServiceImpl.java` (+impl)
- Modify: `cia-backend/cia-reinsurance/pom.xml` (+`cia-documents` dependency)

**Interfaces:**
- Produces: `DocumentGenerationService.generateInwardFacGuaranty(InwardFacGuarantyContext ctx) -> String path|null`; `InwardFacGuarantyContext` record.

- [ ] **Step 1: Context record** (mirror `EndorsementDocumentContext`)

`InwardFacGuarantyContext.java`:
```java
package com.nubeero.cia.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InwardFacGuarantyContext(
        UUID facInwardId,
        String facInwardReference,
        UUID classOfBusinessId,
        String cedingCompanyName,
        String classOfBusinessName,
        String riskDescription,
        BigDecimal sumInsured,
        BigDecimal ourSharePct,
        BigDecimal acceptedSumInsured,
        BigDecimal grossPremium,
        BigDecimal commissionAmount,
        BigDecimal netPremium,
        String currencyCode,
        LocalDate coverFrom,
        LocalDate coverTo
) {}
```

- [ ] **Step 2: Interface method**

Add to `DocumentGenerationService`:
```java
    String generateInwardFacGuaranty(InwardFacGuarantyContext ctx);
```

- [ ] **Step 3: Template** (mirror the structure/placeholders style of `document-templates/endorsement-default.html`; use `${...}` Thymeleaf vars matching the impl's variable map)

`inward-fac-guaranty-default.html`:
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head><meta charset="UTF-8"/>
<style>
  body { font-family: 'NotoSans', sans-serif; font-size: 11px; color: #111; }
  h1 { font-size: 18px; } h2 { font-size: 13px; margin-top: 16px; }
  table { width: 100%; border-collapse: collapse; margin-top: 8px; }
  td { padding: 4px 6px; border-bottom: 1px solid #ddd; }
  .label { color: #555; width: 40%; }
</style></head>
<body>
  <h1>Facultative Reinsurance — Guaranty of Acceptance</h1>
  <p>Reference: <b th:text="${facInwardReference}">FAC-IN-YYYY-000001</b></p>
  <p>We confirm our acceptance of the facultative share described below, ceded to us by
     <b th:text="${cedingCompanyName}">Ceding Company</b>.</p>
  <h2>Risk</h2>
  <table>
    <tr><td class="label">Class of business</td><td th:text="${classOfBusinessName}">Class</td></tr>
    <tr><td class="label">Description</td><td th:text="${riskDescription}">Risk</td></tr>
    <tr><td class="label">Sum insured</td><td th:text="${currencyCode} + ' ' + ${sumInsured}">NGN 0</td></tr>
    <tr><td class="label">Our share</td><td th:text="${ourSharePct} + ' %'">0 %</td></tr>
    <tr><td class="label">Accepted sum insured</td><td th:text="${currencyCode} + ' ' + ${acceptedSumInsured}">NGN 0</td></tr>
    <tr><td class="label">Cover period</td><td th:text="${coverFrom} + ' to ' + ${coverTo}">from to</td></tr>
  </table>
  <h2>Premium</h2>
  <table>
    <tr><td class="label">Gross premium</td><td th:text="${currencyCode} + ' ' + ${grossPremium}">NGN 0</td></tr>
    <tr><td class="label">Commission</td><td th:text="${currencyCode} + ' ' + ${commissionAmount}">NGN 0</td></tr>
    <tr><td class="label">Net premium receivable</td><td th:text="${currencyCode} + ' ' + ${netPremium}">NGN 0</td></tr>
  </table>
  <p style="margin-top:24px;">This guaranty confirms our participation on the terms above.</p>
</body>
</html>
```

> Read `endorsement-default.html` first and match its Thymeleaf dialect exactly (the impl uses `SpringTemplateEngine` + `StringTemplateResolver` — plain `th:text`/`${}` SpringEL, per the S38 doc-gen fix).

- [ ] **Step 4: Impl method** (mirror `generateEndorsementDocument` in `DocumentGenerationServiceImpl`)

Add:
```java
    @Override
    public String generateInwardFacGuaranty(InwardFacGuarantyContext ctx) {
        try {
            String html = resolveAndRender(
                    DocumentTemplateType.POLICY, null, ctx.classOfBusinessId(),
                    Map.ofEntries(
                            entry("facInwardReference",  ctx.facInwardReference()),
                            entry("cedingCompanyName",   ctx.cedingCompanyName()),
                            entry("classOfBusinessName", ctx.classOfBusinessName()),
                            entry("riskDescription",     ctx.riskDescription() != null ? ctx.riskDescription() : ""),
                            entry("sumInsured",          ctx.sumInsured().toPlainString()),
                            entry("ourSharePct",         ctx.ourSharePct().toPlainString()),
                            entry("acceptedSumInsured",  ctx.acceptedSumInsured().toPlainString()),
                            entry("grossPremium",        ctx.grossPremium().toPlainString()),
                            entry("commissionAmount",    ctx.commissionAmount().toPlainString()),
                            entry("netPremium",          ctx.netPremium().toPlainString()),
                            entry("currencyCode",        ctx.currencyCode()),
                            entry("coverFrom",           fmt(ctx.coverFrom())),
                            entry("coverTo",             fmt(ctx.coverTo()))
                    ));
            byte[] pdf = pdfConverter.convert(html);
            String path = "documents/ri-fac-inwards/" + ctx.facInwardId() + "/guaranty.pdf";
            store(path, pdf);
            return path;
        } catch (Exception ex) {
            log.error("Inward FAC guaranty generation failed for {}: {}", ctx.facInwardReference(), ex.getMessage(), ex);
            return null;
        }
    }
```
> `resolveAndRender(type, productId, classOfBusinessId, vars)` prefers a tenant template then a classpath default. There is no `INWARD_FAC_GUARANTY` value in `DocumentTemplateType`; passing `POLICY` reuses the tenant-template lookup harmlessly, but the classpath default must be the guaranty template. **Cleaner:** add a `DocumentTemplateType.INWARD_FAC_GUARANTY` enum value and a `loadClasspathDefault` case → `"inward-fac-guaranty-default.html"`, then pass that type. Do the enum addition (one line in the enum + one `switch` case in `loadClasspathDefault`).

- [ ] **Step 5: Add cia-documents dep to cia-reinsurance pom**

In `cia-backend/cia-reinsurance/pom.xml` `<dependencies>`, add (mirror how `cia-policy` declares it):
```xml
        <dependency>
            <groupId>com.nubeero.cia</groupId>
            <artifactId>cia-documents</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 6: Compile + a render smoke IT** (mirror an existing cia-documents doc-gen IT, e.g. the one that renders the endorsement/policy default template and asserts non-null bytes)

`InwardFacGuarantyRenderIT.java` (in cia-documents test): call `generateInwardFacGuaranty(<a fully-populated ctx>)` with a stub/local `DocumentStorageService` and assert a non-null path (or, if the existing doc IT asserts on rendered HTML, assert the HTML contains the reference + "Guaranty").

Run: `cd cia-backend && mvn -q -pl cia-documents -am verify -Dit.test=InwardFacGuarantyRenderIT -Dtest=none -DfailIfNoTests=false`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add cia-backend/cia-documents/ cia-backend/cia-reinsurance/pom.xml
git commit -m "feat(documents): inward FAC guaranty document generation"
```

---

## Task 5: `RiFacInwardService` — create / renew / extend / cancel + premium math

**Files:**
- Create: `cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/RiFacInwardService.java`
- Test: `cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/RiFacInwardPremiumMathTest.java` (pure unit) + `RiFacInwardServiceIT.java` (Testcontainers)
- Read: `cia-backend/cia-setup/src/main/java/com/nubeero/cia/setup/org/InsuranceCompanyRepository.java` (confirm/add `findByIdAndDeletedAtIsNull`) + `ClassOfBusinessRepository` (for the class-name snapshot).

**Interfaces:**
- Consumes: `RiFacInwardRepository`, `RiNumberService.nextInwardFacReference()`, `InsuranceCompanyRepository`, `ClassOfBusinessRepository`, `DocumentGenerationService.generateInwardFacGuaranty`, `ApplicationEventPublisher`.
- Produces: `create(CreateFacInwardRequest) -> RiFacInward`, `renew(UUID, RenewFacInwardRequest) -> RiFacInward`, `extend(UUID, ExtendFacInwardRequest) -> RiFacInward`, `cancel(UUID, String) -> RiFacInward`, `findOrThrow(UUID)`, `list(cedingCompanyId, classId, status, Pageable)`. Static package-private `computeAmounts(sumInsured, sharePct, rate, commissionRate)` for the math unit test.

- [ ] **Step 1: Premium-math unit test (pure, no Spring)**

`RiFacInwardPremiumMathTest.java`:
```java
package com.nubeero.cia.reinsurance;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class RiFacInwardPremiumMathTest {

    @Test
    void computesAcceptedSiGrossCommissionNet() {
        // SI 20,000,000 × 30% = 6,000,000 accepted; rate 0.75% → 45,000 gross;
        // commission 10% → 4,500; net 40,500.
        var a = RiFacInwardService.computeAmounts(
            new BigDecimal("20000000"), new BigDecimal("30"),
            new BigDecimal("0.75"), new BigDecimal("10"));
        assertThat(a.acceptedSumInsured()).isEqualByComparingTo("6000000.00");
        assertThat(a.grossPremium()).isEqualByComparingTo("45000.00");
        assertThat(a.commissionAmount()).isEqualByComparingTo("4500.00");
        assertThat(a.netPremium()).isEqualByComparingTo("40500.00");
    }

    @Test
    void nullCommissionRateTreatedAsZero() {
        var a = RiFacInwardService.computeAmounts(
            new BigDecimal("1000000"), new BigDecimal("50"),
            new BigDecimal("1.0"), null);
        assertThat(a.commissionAmount()).isEqualByComparingTo("0.00");
        assertThat(a.netPremium()).isEqualByComparingTo(a.grossPremium());
    }
}
```

- [ ] **Step 2: Run — expect fail** (compile error: `computeAmounts` undefined)

Run: `cd cia-backend && mvn -q -pl cia-reinsurance -am test -Dtest=RiFacInwardPremiumMathTest`
Expected: FAIL — cannot find symbol `computeAmounts`.

- [ ] **Step 3: Service**

`RiFacInwardService.java`:
```java
package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.event.RiFacInwardAcceptedEvent;
import com.nubeero.cia.common.exception.BusinessRuleException;
import com.nubeero.cia.common.exception.ResourceNotFoundException;
import com.nubeero.cia.documents.DocumentGenerationService;
import com.nubeero.cia.documents.InwardFacGuarantyContext;
import com.nubeero.cia.reinsurance.dto.CreateFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.ExtendFacInwardRequest;
import com.nubeero.cia.reinsurance.dto.RenewFacInwardRequest;
import com.nubeero.cia.setup.org.InsuranceCompany;
import com.nubeero.cia.setup.org.InsuranceCompanyRepository;
import com.nubeero.cia.setup.product.ClassOfBusiness;
import com.nubeero.cia.setup.product.ClassOfBusinessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RiFacInwardService {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RiFacInwardRepository repository;
    private final RiNumberService numberService;
    private final InsuranceCompanyRepository insuranceCompanyRepository;
    private final ClassOfBusinessRepository classOfBusinessRepository;
    private final DocumentGenerationService documentGenerationService;
    private final ApplicationEventPublisher eventPublisher;

    /** Pure amount computation — unit-tested independently. */
    record Amounts(BigDecimal acceptedSumInsured, BigDecimal grossPremium,
                   BigDecimal commissionAmount, BigDecimal netPremium) {}

    static Amounts computeAmounts(BigDecimal sumInsured, BigDecimal sharePct,
                                  BigDecimal rate, BigDecimal commissionRate) {
        BigDecimal accepted = sumInsured.multiply(sharePct)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal gross = accepted.multiply(rate)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal cr = commissionRate != null ? commissionRate : BigDecimal.ZERO;
        BigDecimal commission = gross.multiply(cr)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(commission);
        return new Amounts(accepted, gross, commission, net);
    }

    @Transactional
    public RiFacInward create(CreateFacInwardRequest req) {
        InsuranceCompany ceding = insuranceCompanyRepository.findByIdAndDeletedAtIsNull(req.cedingCompanyId())
                .orElseThrow(() -> new ResourceNotFoundException("InsuranceCompany", req.cedingCompanyId()));
        ClassOfBusiness cob = classOfBusinessRepository.findByIdAndDeletedAtIsNull(req.classOfBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("ClassOfBusiness", req.classOfBusinessId()));
        if (!req.coverTo().isAfter(req.coverFrom())) {
            throw new BusinessRuleException("INVALID_COVER_PERIOD", "coverTo must be after coverFrom");
        }

        Amounts a = computeAmounts(req.sumInsured(), req.ourSharePct(), req.premiumRate(), req.commissionRate());
        BigDecimal commissionRate = req.commissionRate() != null ? req.commissionRate() : BigDecimal.ZERO;

        RiFacInward inward = RiFacInward.builder()
                .facInwardReference(numberService.nextInwardFacReference())
                .cedingCompanyId(ceding.getId())
                .cedingCompanyName(ceding.getName())
                .classOfBusinessId(cob.getId())
                .classOfBusinessName(cob.getName())
                .riskDescription(req.riskDescription())
                .sumInsured(req.sumInsured())
                .ourSharePct(req.ourSharePct())
                .acceptedSumInsured(a.acceptedSumInsured())
                .premiumRate(req.premiumRate())
                .grossPremium(a.grossPremium())
                .commissionRate(commissionRate)
                .commissionAmount(a.commissionAmount())
                .netPremium(a.netPremium())
                .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
                .coverFrom(req.coverFrom())
                .coverTo(req.coverTo())
                .status(RiFacInwardStatus.ACTIVE)
                .build();

        RiFacInward saved = repository.save(inward);
        generateGuaranty(saved);
        publishAccepted(saved, saved.getGrossPremium(), saved.getCommissionAmount(), saved.getNetPremium());
        return saved;
    }

    @Transactional
    public RiFacInward renew(UUID sourceId, RenewFacInwardRequest req) {
        RiFacInward source = findOrThrow(sourceId);
        if (source.getStatus() != RiFacInwardStatus.ACTIVE) {
            throw new BusinessRuleException("INVALID_FAC_INWARD_STATUS", "Only ACTIVE covers can be renewed");
        }
        if (!req.coverTo().isAfter(req.coverFrom())) {
            throw new BusinessRuleException("INVALID_COVER_PERIOD", "coverTo must be after coverFrom");
        }

        // Premium terms carry over from source; recompute for the new term.
        Amounts a = computeAmounts(source.getSumInsured(), source.getOurSharePct(),
                source.getPremiumRate(), source.getCommissionRate());

        RiFacInward renewed = RiFacInward.builder()
                .facInwardReference(numberService.nextInwardFacReference())
                .cedingCompanyId(source.getCedingCompanyId())
                .cedingCompanyName(source.getCedingCompanyName())
                .classOfBusinessId(source.getClassOfBusinessId())
                .classOfBusinessName(source.getClassOfBusinessName())
                .riskDescription(source.getRiskDescription())
                .sumInsured(source.getSumInsured())
                .ourSharePct(source.getOurSharePct())
                .acceptedSumInsured(a.acceptedSumInsured())
                .premiumRate(source.getPremiumRate())
                .grossPremium(a.grossPremium())
                .commissionRate(source.getCommissionRate())
                .commissionAmount(a.commissionAmount())
                .netPremium(a.netPremium())
                .currencyCode(source.getCurrencyCode())
                .coverFrom(req.coverFrom())
                .coverTo(req.coverTo())
                .status(RiFacInwardStatus.ACTIVE)
                .renewedFromId(source.getId())
                .build();

        RiFacInward saved = repository.save(renewed);
        source.setStatus(RiFacInwardStatus.RENEWED);
        repository.save(source);

        generateGuaranty(saved);
        publishAccepted(saved, saved.getGrossPremium(), saved.getCommissionAmount(), saved.getNetPremium());
        return saved;
    }

    @Transactional
    public RiFacInward extend(UUID id, ExtendFacInwardRequest req) {
        RiFacInward cover = findOrThrow(id);
        if (cover.getStatus() != RiFacInwardStatus.ACTIVE) {
            throw new BusinessRuleException("INVALID_FAC_INWARD_STATUS", "Only ACTIVE covers can be extended");
        }
        if (!req.newCoverTo().isAfter(cover.getCoverTo())) {
            throw new BusinessRuleException("INVALID_COVER_PERIOD", "newCoverTo must be after the current coverTo");
        }

        // Incremental pro-rata premium for the extra days (endorsement idiom):
        // delta gross = gross_premium / originalDays × extraDays.
        long originalDays = ChronoUnit.DAYS.between(cover.getCoverFrom(), cover.getCoverTo()) + 1L;
        long extraDays = ChronoUnit.DAYS.between(cover.getCoverTo(), req.newCoverTo()); // exclusive of the old end day
        BigDecimal deltaGross = cover.getGrossPremium()
                .multiply(BigDecimal.valueOf(extraDays))
                .divide(BigDecimal.valueOf(originalDays), SCALE, RoundingMode.HALF_UP);
        BigDecimal deltaCommission = deltaGross.multiply(cover.getCommissionRate())
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        BigDecimal deltaNet = deltaGross.subtract(deltaCommission);

        cover.setCoverTo(req.newCoverTo());
        RiFacInward saved = repository.save(cover);

        // The extension is an incremental transaction: a separate receivable for
        // the delta (the original premium fields represent the original term).
        publishAccepted(saved, deltaGross, deltaCommission, deltaNet);
        return saved;
    }

    @Transactional
    public RiFacInward cancel(UUID id, String reason) {
        RiFacInward cover = findOrThrow(id);
        if (cover.getStatus() == RiFacInwardStatus.CANCELLED) {
            throw new BusinessRuleException("INVALID_FAC_INWARD_STATUS", "Inward FAC cover is already cancelled");
        }
        cover.setStatus(RiFacInwardStatus.CANCELLED);
        cover.setCancelledBy(currentUsername());
        cover.setCancelledAt(Instant.now());
        cover.setCancellationReason(reason);
        return repository.save(cover);
    }

    public RiFacInward findOrThrow(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("RiFacInward", id));
    }

    public Page<RiFacInward> list(UUID cedingCompanyId, UUID classId,
                                  RiFacInwardStatus status, Pageable pageable) {
        return repository.findAll(cedingCompanyId, classId, status, pageable);
    }

    private void generateGuaranty(RiFacInward c) {
        String path = documentGenerationService.generateInwardFacGuaranty(new InwardFacGuarantyContext(
                c.getId(), c.getFacInwardReference(), c.getClassOfBusinessId(),
                c.getCedingCompanyName(), c.getClassOfBusinessName(), c.getRiskDescription(),
                c.getSumInsured(), c.getOurSharePct(), c.getAcceptedSumInsured(),
                c.getGrossPremium(), c.getCommissionAmount(), c.getNetPremium(),
                c.getCurrencyCode(), c.getCoverFrom(), c.getCoverTo()));
        if (path != null) {
            c.setGuarantyDocumentPath(path);
            repository.save(c);
        }
    }

    private void publishAccepted(RiFacInward c, BigDecimal gross, BigDecimal commission, BigDecimal net) {
        eventPublisher.publishEvent(new RiFacInwardAcceptedEvent(
                c.getId(), c.getFacInwardReference(),
                c.getCedingCompanyId(), c.getCedingCompanyName(), c.getClassOfBusinessId(),
                gross, commission, net, c.getCurrencyCode()));
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("preferred_username");
        }
        return "system";
    }
}
```
> Confirm `InsuranceCompanyRepository.findByIdAndDeletedAtIsNull(UUID)` and `ClassOfBusinessRepository.findByIdAndDeletedAtIsNull(UUID)` exist; if a repo lacks it, add the derived-query method (one line) mirroring `ReinsuranceCompanyRepository`. Confirm `BusinessRuleException`/`ResourceNotFoundException` constructors by reading `cia-common/.../exception/`.

- [ ] **Step 4: Run the math unit test — expect pass**

Run: `cd cia-backend && mvn -q -pl cia-reinsurance -am test -Dtest=RiFacInwardPremiumMathTest`
Expected: PASS.

- [ ] **Step 5: Service lifecycle IT** (Testcontainers; mirror an existing cia-reinsurance full-context IT — seed an `InsuranceCompany` + `ClassOfBusiness`, mock/stub `DocumentGenerationService` if needed or let the real one run)

`RiFacInwardServiceIT.java` — assert: create → status ACTIVE, reference set, amounts correct, `renewedFromId` null; renew → new cover linked (`renewedFromId == source.id`), source status RENEWED; extend → `coverTo` moved; cancel(reason) → status CANCELLED + reason persisted; cancel again → `BusinessRuleException`.

Run: `cd cia-backend && mvn -pl cia-reinsurance -am verify -Dit.test=RiFacInwardServiceIT -Dtest=none -DfailIfNoTests=false`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/RiFacInwardService.java cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/RiFacInward*Test.java cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/RiFacInwardServiceIT.java
git commit -m "feat(reinsurance): RiFacInwardService create/renew/extend/cancel + premium math"
```

---

## Task 6: Finance — DebitNote receivable + GL posting

**Files:**
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/DebitNoteService.java` (+`createForInwardFac`)
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/gl/SubledgerPostingService.java` (+inward COA constants + `EVENT_FAC_PREMIUM_ACCEPTED` + `replayFacPremiumAccepted` + wire it to the event)
- Create: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/RiFacInwardAcceptedEventListener.java`
- Test: `cia-backend/cia-finance/src/test/java/com/nubeero/cia/finance/RiFacInwardFinanceIT.java`

**Interfaces:**
- Consumes: `RiFacInwardAcceptedEvent`.
- Produces: `DebitNoteService.createForInwardFac(RiFacInwardAcceptedEvent) -> DebitNote`; a GL JE `Dr 1330 / Dr 5240 / Cr 4330`.

- [ ] **Step 1: `DebitNoteService.createForInwardFac`** (mirror `createForEndorsement`; debtor = ceding company held in `customerId`/`customerName`)

Add to `DebitNoteService`:
```java
    @Transactional
    public DebitNote createForInwardFac(RiFacInwardAcceptedEvent event) {
        DebitNote dn = new DebitNote();
        dn.setDebitNoteNumber(numberService.nextDebitNoteNumber());
        dn.setEntityType(FinanceEntityType.REINSURANCE);
        dn.setEntityId(event.facInwardId());
        dn.setEntityReference(event.facInwardReference());
        dn.setCustomerId(event.cedingCompanyId());
        dn.setCustomerName(event.cedingCompanyName());
        dn.setDescription("Inward FAC premium — " + event.facInwardReference()
                + " (" + event.cedingCompanyName() + ")");
        dn.setAmount(event.netPremium());
        dn.setTaxAmount(java.math.BigDecimal.ZERO);
        dn.setTotalAmount(event.netPremium());
        dn.setCurrencyCode(event.currencyCode());
        dn.setStatus(DebitNoteStatus.OUTSTANDING);
        dn.setCreatedBy(currentUser());
        return debitNoteRepository.save(dn);
    }
```
> Add the import `com.nubeero.cia.common.event.RiFacInwardAcceptedEvent`. Match the exact setter/field names + `currentUser()` helper already used by `createForPolicy`/`createForEndorsement`.

- [ ] **Step 2: GL posting** — add inward constants + `replayFacPremiumAccepted` to `SubledgerPostingService`

Add near the outward FAC constants:
```java
    static final String EVENT_FAC_PREMIUM_ACCEPTED = "FAC_PREMIUM_ACCEPTED";

    private static final String COA_INWARD_PREMIUM_RECEIVABLE = "1330"; // Premium receivable - Coinsurer (inward)
    private static final String COA_INWARD_COMMISSION_EXPENSE  = "5240"; // Inward reinsurance commission expense
    private static final String COA_INWARD_PREMIUM_INCOME      = "4330"; // Inward reinsurance premium income
```

Add the compound-posting method (mirror `replayFacPremiumCeded`; no `policyId` → pass `null` classOfBusinessId or the event's `classOfBusinessId`):
```java
    /**
     * 7. Inward FAC accepted → Dr 1330 receivable (net) / Dr 5240 commission
     * expense / Cr 4330 premium income (gross). Invariant:
     * gross == commission + net (JournalEntryService re-checks Σdr==Σcr).
     * Simple income posting (not IFRS-17 PAA — backlog fac-ifrs17-paa-workstream).
     */
    public void replayFacPremiumAccepted(RiFacInwardAcceptedEvent event) {
        replayFacPremiumAccepted(event, today());
    }

    public void replayFacPremiumAccepted(RiFacInwardAcceptedEvent event, LocalDate businessDate) {
        if (zeroOrNull(event.grossPremium())) {
            log.debug("Skipping JE for RiFacInwardAccepted {} — gross premium is zero", event.facInwardReference());
            return;
        }
        String narrative = String.format("Inward FAC %s accepted from %s",
                event.facInwardReference(), event.cedingCompanyName());
        PostJournalEntryRequest request = new PostJournalEntryRequest(
                businessDate,
                MODULE_REINSURANCE,
                EVENT_FAC_PREMIUM_ACCEPTED,
                event.facInwardId().toString(),
                narrative,
                List.of(
                    line(COA_INWARD_PREMIUM_RECEIVABLE, event.netPremium(),      BigDecimal.ZERO,          event.currencyCode(), event.classOfBusinessId()),
                    line(COA_INWARD_COMMISSION_EXPENSE,  event.commissionAmount(),BigDecimal.ZERO,          event.currencyCode(), event.classOfBusinessId()),
                    line(COA_INWARD_PREMIUM_INCOME,      BigDecimal.ZERO,         event.grossPremium(),     event.currencyCode(), event.classOfBusinessId())));
        journalEntryService.post(request);
    }
```
> `extend` fires the event with **delta** amounts, so the idempotency reference `event.facInwardId().toString()` would collide with the create JE. Use a unique reference for the delta: change the reference passed to `PostJournalEntryRequest` to `event.facInwardId() + ":" + businessDate` **only if** the JE idempotency triple would otherwise clash. Simpler and robust: make the listener (Step 3) pass a monotonic reference — see Step 3's note.

- [ ] **Step 3: Event listener** (mirror `FacPremiumCededEventListener`)

`RiFacInwardAcceptedEventListener.java`:
```java
package com.nubeero.cia.finance;

import com.nubeero.cia.common.event.RiFacInwardAcceptedEvent;
import com.nubeero.cia.finance.gl.SubledgerPostingService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RiFacInwardAcceptedEventListener {

    private final DebitNoteService debitNoteService;
    private final SubledgerPostingService subledgerPostingService;

    @EventListener
    @Transactional
    public void onInwardFacAccepted(RiFacInwardAcceptedEvent event) {
        debitNoteService.createForInwardFac(event);
        subledgerPostingService.replayFacPremiumAccepted(event);
    }
}
```
> **Idempotency for extend:** the JE gateway rejects a duplicate `(source_module, source_event_type, source_reference)` triple. `create` and each `extend` both fire `RiFacInwardAcceptedEvent` for the same `facInwardId`. To keep each posting distinct, change `replayFacPremiumAccepted`'s reference slot from `event.facInwardId().toString()` to include the business date, e.g. `event.facInwardId() + ":" + businessDate`. Two extends on the same day would still collide — acceptable for v1 (document it); a fuller fix threads a per-transaction sequence. Note this in the method javadoc.

- [ ] **Step 4: Finance IT** (Testcontainers) — publish a `RiFacInwardAcceptedEvent` (or drive `RiFacInwardService.create` through the full context) and assert: a `DebitNote` exists with `entityType=REINSURANCE`, `entityId=facInwardId`, `amount=netPremium`, `status=OUTSTANDING`; a `journal_entry` exists with 3 lines hitting `1330`/`5240`/`4330` and `Σdebit==Σcredit`.

`RiFacInwardFinanceIT.java` (mirror `FacPremiumCededEventContractTest` / an existing subledger IT — use `em.flush()` after the service call per the `@DataJpaTest` flush note if applicable).

Run: `cd cia-backend && mvn -pl cia-finance -am verify -Dit.test=RiFacInwardFinanceIT -Dtest=none -DfailIfNoTests=false`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/DebitNoteService.java cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/gl/SubledgerPostingService.java cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/RiFacInwardAcceptedEventListener.java cia-backend/cia-finance/src/test/java/com/nubeero/cia/finance/RiFacInwardFinanceIT.java
git commit -m "feat(finance): inward FAC DebitNote receivable + FAC_PREMIUM_ACCEPTED GL posting"
```

---

## Task 7: `RiFacInwardController` + guaranty download

**Files:**
- Create: `cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/RiFacInwardController.java`
- Test: `cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/RiFacInwardControllerIT.java`
- Read: how outward streams a document (there is no outward doc endpoint — mirror how `PolicyController`/`ClaimController` stream a PDF via `DocumentStorageService.download(tenantId, path)` returning `ResponseEntity<byte[]>` or an `InputStreamResource`).

**Interfaces:**
- Consumes: `RiFacInwardService`, `DocumentStorageService` (for the guaranty stream), `TenantContext.getTenantId()`.
- Produces: REST surface at `/api/v1/ri/fac-inwards`.

- [ ] **Step 1: Controller** (mirror `RiFacCoverController`; list returns `ApiResponse<List<FacInwardResponse>>` + `ApiMeta`)

`RiFacInwardController.java`:
```java
package com.nubeero.cia.reinsurance;

import com.nubeero.cia.common.api.ApiMeta;
import com.nubeero.cia.common.api.ApiResponse;
import com.nubeero.cia.reinsurance.dto.*;
import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ri/fac-inwards")
@Tag(name = "Inward Facultative Reinsurance",
     description = "Inward FAC — accept a share of another insurer's risk. Lifecycle: ACTIVE → RENEWED/EXPIRED/CANCELLED. Creation fires RiFacInwardAcceptedEvent → cia-finance cascades a DebitNote receivable + JE (Dr 1330 / Dr 5240 / Cr 4330).")
@RequiredArgsConstructor
public class RiFacInwardController {

    private final RiFacInwardService service;
    private final DocumentStorageService storageService;

    @GetMapping
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "List inward FAC covers (paginated, filterable)")
    public ApiResponse<List<FacInwardResponse>> list(
            @RequestParam(required = false) UUID cedingCompanyId,
            @RequestParam(required = false) UUID classOfBusinessId,
            @RequestParam(required = false) RiFacInwardStatus status,
            @PageableDefault(size = 2000) Pageable pageable) {
        var page = service.list(cedingCompanyId, classOfBusinessId, status, pageable);
        return ApiResponse.success(page.map(this::toResponse).getContent(),
                ApiMeta.builder().total(page.getTotalElements())
                        .page(page.getNumber()).size(page.getSize()).build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "Get inward FAC cover detail")
    public ApiResponse<FacInwardResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(toResponse(service.findOrThrow(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    @Operation(summary = "Create (accept) an inward FAC cover — status ACTIVE")
    public ApiResponse<FacInwardResponse> create(@Valid @RequestBody CreateFacInwardRequest req) {
        return ApiResponse.success(toResponse(service.create(req)));
    }

    @PostMapping("/{id}/renew")
    @PreAuthorize("hasRole('REINSURANCE_CREATE')")
    @Operation(summary = "Renew an ACTIVE inward FAC cover for a new term")
    public ApiResponse<FacInwardResponse> renew(@PathVariable UUID id, @Valid @RequestBody RenewFacInwardRequest req) {
        return ApiResponse.success(toResponse(service.renew(id, req)));
    }

    @PostMapping("/{id}/extend")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Extend an ACTIVE inward FAC cover's period (incremental pro-rata premium)")
    public ApiResponse<FacInwardResponse> extend(@PathVariable UUID id, @Valid @RequestBody ExtendFacInwardRequest req) {
        return ApiResponse.success(toResponse(service.extend(id, req)));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('REINSURANCE_UPDATE')")
    @Operation(summary = "Cancel an inward FAC cover (reason required)")
    public ApiResponse<FacInwardResponse> cancel(@PathVariable UUID id, @Valid @RequestBody CancelFacInwardRequest req) {
        return ApiResponse.success(toResponse(service.cancel(id, req.reason())));
    }

    @GetMapping("/{id}/document")
    @PreAuthorize("hasRole('REINSURANCE_VIEW')")
    @Operation(summary = "Download the guaranty document (PDF)")
    public ResponseEntity<byte[]> document(@PathVariable UUID id) throws Exception {
        RiFacInward cover = service.findOrThrow(id);
        if (cover.getGuarantyDocumentPath() == null) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream is = storageService.download(TenantContext.getTenantId(), cover.getGuarantyDocumentPath())) {
            byte[] bytes = is.readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + cover.getFacInwardReference() + "-guaranty.pdf\"")
                    .body(bytes);
        }
    }

    private FacInwardResponse toResponse(RiFacInward f) {
        return new FacInwardResponse(
                f.getId(), f.getFacInwardReference(),
                f.getCedingCompanyId(), f.getCedingCompanyName(),
                f.getClassOfBusinessId(), f.getClassOfBusinessName(),
                f.getRiskDescription(), f.getSumInsured(), f.getOurSharePct(),
                f.getAcceptedSumInsured(), f.getPremiumRate(), f.getGrossPremium(),
                f.getCommissionRate(), f.getCommissionAmount(), f.getNetPremium(),
                f.getCurrencyCode(), f.getCoverFrom(), f.getCoverTo(),
                f.getStatus(), f.getRenewedFromId(), f.getGuarantyDocumentPath(),
                f.getCancelledBy(), f.getCancelledAt(), f.getCancellationReason(),
                f.getCreatedAt());
    }
}
```
> Confirm `cia-reinsurance` already depends on `cia-storage` (for `DocumentStorageService`); the outward module streams no doc, so if `cia-storage` isn't a dep, add it (mirror `cia-policy`). Confirm `@ApiResponses` conventions used elsewhere in the module and add matching annotations (the outward controller carries full `@ApiResponses` — mirror them for lint/docs parity, though not strictly required for internal `/api/**`).

- [ ] **Step 2: Controller IT** (mirror an existing `cia-reinsurance` controller IT — MockMvc or full-context) — POST create → 201 + status ACTIVE; GET list → 200 with the row; POST renew → source RENEWED; POST extend → coverTo moved; POST cancel (reason) → CANCELLED; GET /document before any doc → 404 (or 200 if the stub storage has it).

Run: `cd cia-backend && mvn -pl cia-reinsurance -am verify -Dit.test=RiFacInwardControllerIT -Dtest=none -DfailIfNoTests=false`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Full reactor verify** (the internal-api path count must include the new endpoints)

Run: `cd cia-backend && mvn install -DskipTests -pl cia-api -am && mvn -pl cia-api -am verify`
Expected: BUILD SUCCESS; all ITs green (the reactor IT count increases).

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-reinsurance/src/main/java/com/nubeero/cia/reinsurance/RiFacInwardController.java cia-backend/cia-reinsurance/src/test/java/com/nubeero/cia/reinsurance/RiFacInwardControllerIT.java
git commit -m "feat(reinsurance): RiFacInwardController + guaranty document download"
```

---

## Task 8: Frontend — api-client DTO + zod schema + request types

**Files:**
- Modify: `cia-frontend/packages/api-client/src/modules/reinsurance.ts`
- Read first: the existing `FacCoverDto` + `FacCoverDtoSchema` in that file to match the module's zod/style conventions.

**Interfaces:**
- Produces: `FacInwardDto` + `FacInwardDtoSchema` (mirrors `FacInwardResponse` field-for-field for `check-dto-drift`) + `RiFacInwardStatus` union + request types.

- [ ] **Step 1: Add the enum + schema + types** (match `FacInwardResponse` fields exactly)

```ts
export const RiFacInwardStatusSchema = z.enum(['ACTIVE', 'RENEWED', 'EXPIRED', 'CANCELLED']);
export type RiFacInwardStatus = z.infer<typeof RiFacInwardStatusSchema>;

export const FacInwardDtoSchema = z.object({
  id:                    z.string(),
  facInwardReference:    z.string(),
  cedingCompanyId:       z.string(),
  cedingCompanyName:     z.string(),
  classOfBusinessId:     z.string(),
  classOfBusinessName:   z.string(),
  riskDescription:       z.string().nullable().optional(),
  sumInsured:            z.number(),
  ourSharePct:           z.number(),
  acceptedSumInsured:    z.number(),
  premiumRate:           z.number(),
  grossPremium:          z.number(),
  commissionRate:        z.number(),
  commissionAmount:      z.number(),
  netPremium:            z.number(),
  currencyCode:          z.string(),
  coverFrom:             z.string(),
  coverTo:               z.string(),
  status:                RiFacInwardStatusSchema,
  renewedFromId:         z.string().nullable().optional(),
  guarantyDocumentPath:  z.string().nullable().optional(),
  cancelledBy:           z.string().nullable().optional(),
  cancelledAt:           z.string().nullable().optional(),
  cancellationReason:    z.string().nullable().optional(),
  createdAt:             z.string(),
});
export type FacInwardDto = z.infer<typeof FacInwardDtoSchema>;

export interface CreateFacInwardRequest {
  cedingCompanyId: string;
  classOfBusinessId: string;
  riskDescription?: string;
  sumInsured: number;
  ourSharePct: number;
  premiumRate: number;
  commissionRate?: number;
  currencyCode?: string;
  coverFrom: string;
  coverTo: string;
}
export interface RenewFacInwardRequest { coverFrom: string; coverTo: string; }
export interface ExtendFacInwardRequest { newCoverTo: string; }
export interface CancelFacInwardRequest { reason: string; }
```
> Ensure these are exported from the package barrel (`packages/api-client/src/index.ts` re-export of `reinsurance.ts`) if the module isn't wildcard-exported.

- [ ] **Step 2: DTO-drift check**

Run: `node cia-frontend/scripts/check-dto-drift.mjs`
Expected: `✓ No DTO drift detected` (FacInwardDto ↔ FacInwardResponse field sets match).

- [ ] **Step 3: Commit**

```bash
git add cia-frontend/packages/api-client/src/modules/reinsurance.ts cia-frontend/packages/api-client/src/index.ts
git commit -m "feat(api-client): FacInwardDto + zod schema + inward FAC request types"
```

---

## Task 9: Frontend — rebuild `AddInwardFACSheet` (create form)

**Files:**
- Create: `cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/AddInwardFACSheet.tsx`
- Read: `git show 48b85f3^:cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/AddInwardFACSheet.tsx` (the deleted form, for layout reference) + `CreateFACOfferSheet.tsx` (the outward sibling, for the current Sheet/RHF/validatedPost idiom).

**Interfaces:**
- Consumes: `CreateFacInwardRequest`, `validatedPost`, `/api/v1/setup/insurance-companies`, `/api/v1/setup/classes-of-business`.
- Produces: `<AddInwardFACSheet open onOpenChange onSuccess />`.

- [ ] **Step 1: Build the form** — RHF + zod resolver; fields: ceding company (Select from `/api/v1/setup/insurance-companies`), class of business (Select), risk description, sum insured, our share %, premium rate, commission %, coverFrom, coverTo; a live premium preview (accepted SI → gross → −commission → net) computed with the same formulas as the backend; submit via `useMutation` → `validatedPost('/api/v1/ri/fac-inwards', values, FacInwardDtoSchema)`; on success invalidate `['ri','fac-inwards']`, toast "Inward FAC cover created", `onSuccess()`. Mirror `CreateFACOfferSheet.tsx`'s structure verbatim, swapping fields + endpoint. **No mocks.**

- [ ] **Step 2: Build check**

Run: `cd cia-frontend && pnpm --filter @cia/back-office build`
Expected: BUILD SUCCESS (tsc + vite).

- [ ] **Step 3: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/AddInwardFACSheet.tsx
git commit -m "feat(ui): rebuild AddInwardFACSheet against real inward FAC create endpoint"
```

---

## Task 10: Frontend — rebuild `InwardFACActionSheet` (renew / extend)

**Files:**
- Create: `cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/InwardFACActionSheet.tsx`
- Read: `git show 48b85f3^:cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/InwardFACActionSheet.tsx` (deleted, for the RENEW/EXTEND mode shape).

**Interfaces:**
- Consumes: `RenewFacInwardRequest`, `ExtendFacInwardRequest`, `validatedPost`, `FacInwardDto` (the target cover).
- Produces: `<InwardFACActionSheet open onOpenChange fac mode onSuccess />` where `type InwardFACMode = 'RENEW' | 'EXTEND'`.

- [ ] **Step 1: Build the sheet** — RENEW mode: coverFrom + coverTo → `validatedPost('/api/v1/ri/fac-inwards/{id}/renew', {coverFrom, coverTo}, FacInwardDtoSchema)`. EXTEND mode: newCoverTo (must be after `fac.coverTo`) + an indicative pro-rata delta preview (`fac.grossPremium / originalDays × extraDays`, then −commission → net) → `validatedPost('/api/v1/ri/fac-inwards/{id}/extend', {newCoverTo}, ...)`. On success invalidate `['ri','fac-inwards']`, toast, `onSuccess()`. Export `InwardFACMode`.

- [ ] **Step 2: Build check**

Run: `cd cia-frontend && pnpm --filter @cia/back-office build`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/InwardFACActionSheet.tsx
git commit -m "feat(ui): rebuild InwardFACActionSheet against real renew/extend endpoints"
```

---

## Task 11: Frontend — live inward tab in `FACTab` + guards + vitest

**Files:**
- Modify: `cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/FACTab.tsx`
- Test: `cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/FACTab.inward.test.tsx`

**Interfaces:**
- Consumes: `FacInwardDto`, `FacInwardDtoSchema`, `validatedGet`, `AddInwardFACSheet`, `InwardFACActionSheet`.

- [ ] **Step 1: Replace the "coming soon" `EmptyState`** — restore the imports for `AddInwardFACSheet` + `InwardFACActionSheet`; add `const inwardQuery = useQuery<FacInwardDto[]>({ queryKey: ['ri','fac-inwards'], queryFn: () => validatedGet('/api/v1/ri/fac-inwards', z.array(FacInwardDtoSchema)) })`; render a `DataTable` (columns: reference, ceding company, class, accepted SI [`formatNaira`], our share %, net premium [`formatNaira`], status badge) with row actions Renew / Extend (ACTIVE only) / Cancel (reason via `ConfirmDeleteDialog` or the outward cancel-with-reason dialog idiom) + a Download-guaranty button gated on `guarantyDocumentPath != null` (blob fetch via `apiClient.get(url, { responseType: 'blob' })`). Restore the tab count `Inward FAC ({inward.length})` and the "Add Inward FAC" action button (opens `AddInwardFACSheet`). Cancel → `validatedPost('/api/v1/ri/fac-inwards/{id}/cancel', {reason}, FacInwardDtoSchema)`, invalidate `['ri','fac-inwards']`.

- [ ] **Step 2: Vitest** — `FACTab.inward.test.tsx`: mock `@cia/api-client` `validatedGet` to return one `FacInwardDto`; render `FACTab`; switch to the inward tab; assert the reference + ceding company render and the Renew/Cancel actions appear for an ACTIVE row. (Mirror the existing `BulkEmailSheet.test.tsx` mocking idiom.)

- [ ] **Step 3: Build + guards + vitest**

Run:
```bash
cd cia-frontend && pnpm --filter @cia/back-office build
bash scripts/check-api-wiring.sh
node scripts/check-dto-drift.mjs
pnpm --filter @cia/back-office test
```
Expected: all pass; api-wiring shows no violations (the "coming soon" placeholder is gone, no mock added).

- [ ] **Step 4: Commit**

```bash
git add cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/FACTab.tsx cia-frontend/apps/back-office/src/modules/reinsurance/pages/fac/FACTab.inward.test.tsx
git commit -m "feat(ui): live inward FAC tab — DataTable + renew/extend/cancel + guaranty download"
```

---

## Task 12: Docs — cia-log + CLAUDE.md + spec status

**Files:**
- Modify: `cia-log.md` (session entry + drain `inward-fac-backend-build` from the backlog), `CLAUDE.md` (Module 6 line + migration high-water V75 if it tracks it), the spec header status → IMPLEMENTED.

- [ ] **Step 1: cia-log session entry** — new dated entry summarising the build (files, the simple-posting decision reference, the V75 migration, the frontend rebuild); in "Known follow-ups" remove `inward-fac-backend-build` (P1) from the backlog table (landed) and confirm the three deferred rows (`inward-fac-renewal-notices`, `inward-fac-debit-note-analysis`, `fac-ifrs17-paa-workstream`) remain.

- [ ] **Step 2: CLAUDE.md** — update the Module 6 summary row + the `cia-setup`/reinsurance description to mention inward FAC is now built (V75), and note the new `/api/v1/ri/fac-inwards` surface. If CLAUDE.md tracks a "latest migration" anywhere, bump to V75.

- [ ] **Step 3: Commit**

```bash
git add cia-log.md CLAUDE.md docs/superpowers/specs/2026-07-07-inward-fac-reinsurance-design.md
git commit -m "docs: inward FAC v1 shipped — log, CLAUDE.md, spec status"
```

---

## Final verification

- [ ] Backend: `cd cia-backend && mvn install -DskipTests -pl cia-api -am && mvn -pl cia-api -am verify` — all ITs green.
- [ ] Frontend: `cd cia-frontend && pnpm --filter @cia/back-office build && bash scripts/check-api-wiring.sh && node scripts/check-dto-drift.mjs && pnpm --filter @cia/back-office test` — all pass.
- [ ] Live smoke (optional, per the `run` skill): start the dev stack, `POST /api/v1/ri/fac-inwards`, confirm the cover is ACTIVE, a `DebitNote` appears in `GET /api/v1/debit-notes?entityId={id}`, and `GET /api/v1/ri/fac-inwards/{id}/document` streams a PDF.
- [ ] Then `superpowers:finishing-a-development-branch` → PR + merge (merge-commit convention; CI green).

## Out of scope (do NOT build)

Renewal-notice Temporal workflow (`inward-fac-renewal-notices`) · inward debit-note-analysis report (`inward-fac-debit-note-analysis`) · IFRS-17 PAA/LRC measurement (`fac-ifrs17-paa-workstream`) · retrocession-from-reinsurer counterparties · a PENDING/approval workflow (created live as ACTIVE).
