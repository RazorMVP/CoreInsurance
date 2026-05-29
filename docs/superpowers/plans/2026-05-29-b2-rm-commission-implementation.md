# B2 — RM Commission via 2520 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pay Relationship Managers a commission on the direct-channel policies they own — auto-derived from the customer at policy creation, accrued on approval as Dr 5130 / Cr 2520 (no CreditNote; paid via external payroll), with a per-RM SYSTEM report.

**Architecture:** RM becomes an operationalised third, mutually-exclusive `commissionSourceType` on `policies` (alongside broker/agent). A V62 migration adds the per-policy RM snapshot columns + a 3-way at-most-one source CHECK. `PolicyService` derives RM-source for direct policies whose customer has an RM and whose product has an effective `CommissionSetup(RELATIONSHIP_MANAGER)` rate. `SubledgerPostingService` gains an RM branch posting through a new `POLICY_COMMISSION_RM` rule (Dr 5130 Insurance acquisition expense / Cr 2520 Staff payables). A new `DataSource.RM_COMMISSION` + report seed delivers the per-RM accrual report.

**Tech Stack:** Java 21 + Spring Boot 3, PostgreSQL + Flyway, JPA/Hibernate, JUnit 5 + Testcontainers; React + TS + zod (frontend DTO + detail label); cia-reports query builder.

---

## File Structure

**Backend:**
- `cia-api/src/main/resources/db/migration/V62__add_relationship_manager_to_policies.sql` — RM snapshot columns + 3-way source CHECK (CREATE)
- `cia-api/src/main/resources/db/migration/V63__seed_policy_commission_rm_posting_rule.sql` — `POLICY_COMMISSION_RM` posting rule (CREATE)
- `cia-api/src/main/resources/db/migration/V64__seed_rm_commission_report.sql` — per-RM `report_definition` SYSTEM report (CREATE)
- `cia-policy/.../Policy.java` — `relationshipManagerId` + `relationshipManagerName` fields (MODIFY)
- `cia-policy/.../PolicyService.java` — RM branch in `resolveCommissionSnapshot` + builder wiring (MODIFY)
- `cia-policy/.../dto/PolicyResponse.java` — expose `relationshipManagerId` + `relationshipManagerName` (MODIFY)
- `cia-finance/.../gl/SubledgerPostingService.java` — RM constants + RM commission branch (MODIFY)
- `cia-reports/.../DataSource.java` — `RM_COMMISSION` enum value (MODIFY)
- `cia-reports/.../ReportQueryBuilder.java` — `RM_COMMISSION` BASE_QUERIES + BASE_QUERY_TAILS entry (MODIFY)

**Backend tests (cia-api ITs):**
- `cia-api/.../policy/PolicyRmCommissionDerivationIT.java` — creation derivation (CREATE)
- `cia-api/.../policy/PolicyRmConstraintIT.java` — the 3-way CHECK (CREATE)
- `cia-api/.../finance/gl/SubledgerPostingRmCommissionIT.java` — Dr 5130 / Cr 2520 posting + no-CreditNote regression (CREATE)
- `cia-api/.../reports/RmCommissionReportIT.java` — per-RM aggregation (CREATE)

**Frontend:**
- `packages/api-client/src/modules/policy.ts` — `PolicyDto` gains `relationshipManagerId` + `relationshipManagerName` (MODIFY)
- `apps/back-office/.../policy/.../<PolicyDetail Financial tab>` — RM source label (MODIFY)

**Docs:**
- `CLAUDE.md`, `cia-log.md` (MODIFY)

---

## Phase 1 — Data model

### Task 1.1: V62 migration — RM snapshot columns + 3-way source CHECK

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V62__add_relationship_manager_to_policies.sql`
- Modify: the Flyway IT-target pin(s) used by the new ITs (see Step 3)

- [ ] **Step 1: Verify the exact existing CHECK + the next free version**

Run:
```bash
grep -rn 'ck_policies_broker_xor_agent\|relationship_managers' cia-backend/cia-api/src/main/resources/db/migration/
ls cia-backend/cia-api/src/main/resources/db/migration/ | sort | tail -6
```
Expected: `ck_policies_broker_xor_agent CHECK (broker_id IS NULL OR agent_id IS NULL)` defined in `V53__add_agent_to_policies.sql`; the `relationship_managers` table exists (created in the V45/V46 area); the highest migration is `V61__add_sms_audit_columns.sql` → **V62 is free**. If the highest is not V61, use the actual next integer and adjust V63/V64 below accordingly.

- [ ] **Step 2: Write the migration**

```sql
-- cia-api/src/main/resources/db/migration/V62__add_relationship_manager_to_policies.sql
-- B2 — Relationship-Manager commission. Per-policy RM snapshot (id + name),
-- mirroring the broker/agent snapshot (V51/V53). RM becomes an exclusive
-- third commission source: at most one of broker / agent / RM per policy.

ALTER TABLE policies ADD COLUMN relationship_manager_id   UUID;
ALTER TABLE policies ADD COLUMN relationship_manager_name VARCHAR(100);

ALTER TABLE policies
  ADD CONSTRAINT fk_policies_relationship_manager
  FOREIGN KEY (relationship_manager_id) REFERENCES relationship_managers (id);

-- Replace the 2-way broker-xor-agent guard with a 3-way at-most-one guard.
ALTER TABLE policies DROP CONSTRAINT ck_policies_broker_xor_agent;

ALTER TABLE policies
  ADD CONSTRAINT ck_policies_commission_source_one
  CHECK (
        (CASE WHEN broker_id               IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN agent_id                IS NOT NULL THEN 1 ELSE 0 END)
      + (CASE WHEN relationship_manager_id IS NOT NULL THEN 1 ELSE 0 END) <= 1
  );

-- When the commission source is RM, the RM snapshot must be present
-- (mirrors the broker/agent invariant via the existing commission_pair CHECK).
ALTER TABLE policies
  ADD CONSTRAINT ck_policies_rm_source_requires_rm
  CHECK (commission_source_type <> 'RELATIONSHIP_MANAGER'
         OR relationship_manager_id IS NOT NULL);
```

- [ ] **Step 3: Bump the Flyway IT target(s)**

The new ITs need V62 applied. Find which IT base classes pin the target and which the new ITs will extend:
```bash
grep -rn 'spring.flyway.target' cia-backend/cia-api/src/test/
```
The finance/web IT bases (`FinanceItSupport`, `FinanceWebItSupport`) currently pin `"61"`. Bump whichever base class the new ITs (Tasks 1.3, 2.2, 3.4, 5.3) extend to `"62"`. If a policy-specific IT base exists with its own pin, bump that too. (Mirror how earlier slices bumped targets — see V58/V61 history.)

- [ ] **Step 4: Verify the migration applies**

Run:
```bash
cd cia-backend && mvn install -pl cia-api -am -DskipTests -q && mvn -pl cia-api verify -Dit.test='SubledgerPostingServiceIT' -DskipUnitTests=true
```
Expected: BUILD SUCCESS; V62 applied to the Testcontainers DB without error (this IT doesn't use the new columns yet — it just proves the migration is clean and the bumped target boots).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/db/migration/V62__add_relationship_manager_to_policies.sql cia-backend/cia-api/src/test/
git commit -m "$(cat <<'EOF'
feat(policy): Task 1.1 — V62 RM snapshot columns + 3-way commission-source CHECK

policies gains relationship_manager_id + relationship_manager_name
(snapshot, mirrors broker/agent). Replaces ck_policies_broker_xor_agent
with a 3-way at-most-one guard + an RM-source-requires-RM CHECK. Bumps
the finance IT Flyway target to 62.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.2: Policy entity RM fields

**Files:**
- Modify: `cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/Policy.java`

- [ ] **Step 1: Read the existing broker/agent snapshot fields**

Run: `grep -n 'broker_id\|brokerName\|agent_id\|agentName\|commission_source_type\|commission_rate' cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/Policy.java`
Expected (lines ~71–96): `@Column(name="broker_id") UUID brokerId;`, `@Column(name="broker_name", length=100) String brokerName;`, the agent pair, `@Enumerated(EnumType.STRING) @Column(name="commission_source_type", length=30) CommissionSourceType commissionSourceType;`, `@Column(name="commission_rate", precision=6, scale=4) BigDecimal commissionRate;`.

- [ ] **Step 2: Add the RM snapshot fields**

Add immediately after the `agentName` field (mirror the exact annotation style):
```java
// ── Relationship Manager (V62 — B2; exclusive third commission source) ──
@Column(name = "relationship_manager_id")
private UUID relationshipManagerId;

@Column(name = "relationship_manager_name", length = 100)
private String relationshipManagerName;
```

- [ ] **Step 3: Verify compile**

Run: `cd cia-backend && mvn -pl cia-policy compile -DskipTests -am`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/Policy.java
git commit -m "$(cat <<'EOF'
feat(policy): Task 1.2 — Policy.relationshipManagerId + relationshipManagerName

Snapshot fields for the V62 columns, mirroring the broker/agent snapshot.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 1.3: Constraint IT — the 3-way source guard

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/policy/PolicyRmConstraintIT.java`

- [ ] **Step 1: Find the right IT base + a sibling policy/finance constraint IT to mirror**

Run:
```bash
grep -rln 'extends .*ItSupport\|@DataJpaTest' cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/ | head
grep -rn 'DataIntegrityViolationException\|saveAndFlush' cia-backend/cia-api/src/test/java/com/nubeero/cia/api/ | head
```
Use a `@DataJpaTest`-style base that pins `spring.flyway.target = "62"` (bumped in Task 1.1) + `@Import(CiaCommonAutoConfiguration.class)` for auditing (per the CLAUDE.md testing-requirements note). Insert policy rows via `JdbcTemplate` (the simplest way to exercise raw CHECKs without the full Policy aggregate). Mirror `TenantNotificationTemplateRepositoryIT` (Session 133) for the inline-Testcontainers + `assertThatThrownBy(...).isInstanceOf(DataIntegrityViolationException.class)` shape.

- [ ] **Step 2: Write the IT (3 tests)**

```java
package com.nubeero.cia.api.policy;

// imports: JdbcTemplate, @Testcontainers/@DataJpaTest base, AssertJ, DataIntegrityViolationException, UUID
class PolicyRmConstraintIT extends <DATA_JPA_IT_BASE> {  // pins flyway.target=62

    @Autowired JdbcTemplate jdbc;

    // Helper: insert the minimal policy row with the given source columns.
    // Use a raw INSERT listing only NOT NULL columns + the three source columns;
    // read the policies table's NOT NULL set from V2/V51/V53 to fill required fields
    // (policy_number, customer_id, product_id, status, dates, premium, etc.).
    // Mirror how an existing policy-inserting IT seeds a row.

    @Test
    void rejectsTwoOfThreeSources() {
        assertThatThrownBy(() -> insertPolicy(/* broker_id */ UUID.randomUUID(),
                                              /* agent_id  */ UUID.randomUUID(),
                                              /* rm_id     */ null,
                                              /* source    */ "BROKER"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_policies_commission_source_one");
    }

    @Test
    void rejectsRmSourceWithoutRmId() {
        assertThatThrownBy(() -> insertPolicy(null, null, /* rm_id */ null,
                                              /* source */ "RELATIONSHIP_MANAGER"))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_policies_rm_source_requires_rm");
    }

    @Test
    void acceptsRmSourceWithRmId() {
        UUID rmId = seedRelationshipManager();   // INSERT into relationship_managers, return id
        // Must not throw:
        insertPolicy(null, null, rmId, "RELATIONSHIP_MANAGER");
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM policies WHERE relationship_manager_id = ?", Integer.class, rmId);
        assertThat(n).isEqualTo(1);
    }
}
```
Fill `insertPolicy(...)` + `seedRelationshipManager()` by reading the actual NOT NULL columns of `policies` (and `relationship_managers`) so the INSERTs satisfy every other constraint — the test must fail on the *target* CHECK, not an unrelated NOT NULL. (Read `V2__init_tenant_schema.sql` policies table + `relationship_managers` DDL.)

- [ ] **Step 3: Run — expect green**

Run: `cd cia-backend && mvn -pl cia-api verify -Dit.test='PolicyRmConstraintIT' -DskipUnitTests=true`
Expected: 3/0/0/0. If a test fails because the INSERT hit an unrelated NOT NULL, add the missing column to the helper and re-run.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/policy/PolicyRmConstraintIT.java
git commit -m "$(cat <<'EOF'
test(api): Task 1.3 — PolicyRmConstraintIT (3 tests)

Verifies the V62 3-way at-most-one source CHECK + RM-source-requires-RM
CHECK against a real PostgreSQL.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 2 — Source derivation (policy creation)

### Task 2.1: RM branch in `resolveCommissionSnapshot` + builder wiring

**Files:**
- Modify: `cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/PolicyService.java`

- [ ] **Step 1: Read the current derivation + create() wiring**

Run: `grep -n 'resolveCommissionSnapshot\|CommissionSnapshot\|relationshipManager\|getCustomer\|customerRepository\|relationshipManagerRepository' cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/PolicyService.java`
Confirm the current `resolveCommissionSnapshot(productId, brokerId, agentId, on)` (~lines 880–897) + the `create()` call (~lines 217–235) + how `create()` loads the `Customer` (it must, to set customer fields). Confirm `Customer` exposes `getRelationshipManagerId()` and that a `RelationshipManagerRepository` exists (or how to fetch an RM name by id). If `PolicyService` doesn't already inject something to read the RM name, find the repository: `grep -rn 'interface RelationshipManagerRepository' cia-backend/cia-setup/`.

- [ ] **Step 2: Widen the derivation to take the customer's RM + add the RM branch**

Change the signature + add the third branch. Pass the customer's RM id (already loaded in `create()`) into the method. New shape:

```java
private CommissionSnapshot resolveCommissionSnapshot(UUID productId,
                                                      UUID brokerId,
                                                      UUID agentId,
                                                      UUID customerRelationshipManagerId,
                                                      java.time.LocalDate on) {
    if (brokerId != null) {
        return commissionSetupRepository
                .findActiveForProduct(productId, CommissionSourceType.BROKER, on)
                .map(cs -> new CommissionSnapshot(CommissionSourceType.BROKER, cs.getRate(), null))
                .orElse(CommissionSnapshot.EMPTY);
    }
    if (agentId != null) {
        return commissionSetupRepository
                .findActiveForProduct(productId, CommissionSourceType.AGENT, on)
                .map(cs -> new CommissionSnapshot(CommissionSourceType.AGENT, cs.getRate(), null))
                .orElse(CommissionSnapshot.EMPTY);
    }
    // Direct channel: if the customer has an RM and the product has an effective
    // RM commission rate, the RM is the (exclusive) commission source.
    if (customerRelationshipManagerId != null) {
        return commissionSetupRepository
                .findActiveForProduct(productId, CommissionSourceType.RELATIONSHIP_MANAGER, on)
                .map(cs -> new CommissionSnapshot(
                        CommissionSourceType.RELATIONSHIP_MANAGER, cs.getRate(),
                        customerRelationshipManagerId))
                .orElse(CommissionSnapshot.EMPTY);
    }
    return CommissionSnapshot.EMPTY;
}

private record CommissionSnapshot(CommissionSourceType sourceType, BigDecimal rate,
                                  UUID relationshipManagerId) {
    static final CommissionSnapshot EMPTY = new CommissionSnapshot(null, null, null);
}
```

(The added `relationshipManagerId` record component is `null` for broker/agent — only populated for the RM branch.)

- [ ] **Step 3: Wire the snapshot into the builder in create()**

At the `create()` call site (~lines 217–235), pass the customer's RM id and set the RM snapshot on the builder. Resolve the RM *name* by loading the `RelationshipManager` when the source is RM:

```java
CommissionSnapshot commission = resolveCommissionSnapshot(
        product.getId(), request.getBrokerId(), request.getAgentId(),
        customer.getRelationshipManagerId(), request.getPolicyStartDate());

String relationshipManagerName = null;
if (commission.relationshipManagerId() != null) {
    relationshipManagerName = relationshipManagerRepository
            .findById(commission.relationshipManagerId())
            .map(RelationshipManager::getName)
            .orElse(null);
}

Policy policy = Policy.builder()
        // … existing customer/product/broker/agent fields …
        .commissionSourceType(commission.sourceType())
        .commissionRate(commission.rate())
        .relationshipManagerId(commission.relationshipManagerId())
        .relationshipManagerName(relationshipManagerName)
        // … remainder …
        .build();
```

Inject `RelationshipManagerRepository` into `PolicyService` if not already present (constructor field — match the existing repository-injection style; mirror how `commissionSetupRepository` is injected). `RelationshipManager` lives in `com.nubeero.cia.setup.org` — confirm `cia-policy` already depends on `cia-setup` (it should, given commissionSetup usage); if not, the import resolves through the existing setup dependency.

- [ ] **Step 4: Verify compile**

Run: `cd cia-backend && mvn -pl cia-policy compile -DskipTests -am`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/PolicyService.java
git commit -m "$(cat <<'EOF'
feat(policy): Task 2.1 — derive RM commission source for direct policies

resolveCommissionSnapshot gains a third branch: a direct policy (no
broker/agent) whose customer has an RM and whose product has an effective
CommissionSetup(RELATIONSHIP_MANAGER) rate is stamped source=RM with the
customer's RM id + name + the frozen RM rate snapshotted onto the policy.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2.2: Derivation IT

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/policy/PolicyRmCommissionDerivationIT.java`

- [ ] **Step 1: Find the policy-creation IT pattern**

Run: `grep -rln 'policyService.create\|PolicyService' cia-backend/cia-api/src/test/java/com/nubeero/cia/api/policy/ | head`
Use the full-context (`@SpringBootTest`) policy IT base (the one that wires `PolicyService` + repositories). Read how an existing policy-create IT seeds a customer + product + commission setup. Mirror it.

- [ ] **Step 2: Write the IT (4 tests)**

```java
package com.nubeero.cia.api.policy;

class PolicyRmCommissionDerivationIT extends <POLICY_SPRINGBOOT_IT_BASE> {  // flyway.target=62

    // Seed helpers (mirror the existing policy-create IT): seedCustomer(rmId?),
    // seedProduct(), seedCommissionSetup(productId, source, rate), seedRelationshipManager().

    @Test
    void directPolicy_customerHasRm_andRmSetup_derivesRmSource() {
        UUID rmId = seedRelationshipManager("Ada RM");
        UUID customerId = seedCustomer(rmId);
        UUID productId = seedProduct();
        seedCommissionSetup(productId, CommissionSourceType.RELATIONSHIP_MANAGER, new BigDecimal("2.50"));

        PolicyResponse p = policyService.create(buildDirectРequestNoBrokerNoAgent(customerId, productId));

        assertThat(p.getCommissionSourceType()).isEqualTo(CommissionSourceType.RELATIONSHIP_MANAGER);
        assertThat(p.getCommissionRate()).isEqualByComparingTo("2.50");
        assertThat(p.getRelationshipManagerId()).isEqualTo(rmId);
        assertThat(p.getRelationshipManagerName()).isEqualTo("Ada RM");
    }

    @Test
    void brokerPolicy_ignoresRm_evenWhenCustomerHasRm() {
        UUID rmId = seedRelationshipManager("Ada RM");
        UUID customerId = seedCustomer(rmId);
        UUID productId = seedProduct();
        seedCommissionSetup(productId, CommissionSourceType.BROKER, new BigDecimal("5.00"));
        UUID brokerId = seedBroker();

        PolicyResponse p = policyService.create(buildRequestWithBroker(customerId, productId, brokerId));

        assertThat(p.getCommissionSourceType()).isEqualTo(CommissionSourceType.BROKER);
        assertThat(p.getRelationshipManagerId()).isNull();
    }

    @Test
    void directPolicy_customerHasNoRm_noCommission() {
        UUID customerId = seedCustomer(null);     // no RM assigned
        UUID productId = seedProduct();
        seedCommissionSetup(productId, CommissionSourceType.RELATIONSHIP_MANAGER, new BigDecimal("2.50"));

        PolicyResponse p = policyService.create(buildDirectРequestNoBrokerNoAgent(customerId, productId));
        assertThat(p.getCommissionSourceType()).isNull();
        assertThat(p.getRelationshipManagerId()).isNull();
    }

    @Test
    void directPolicy_noRmSetup_noCommission() {
        UUID rmId = seedRelationshipManager("Ada RM");
        UUID customerId = seedCustomer(rmId);
        UUID productId = seedProduct();   // no RM CommissionSetup seeded

        PolicyResponse p = policyService.create(buildDirectРequestNoBrokerNoAgent(customerId, productId));
        assertThat(p.getCommissionSourceType()).isNull();
        assertThat(p.getRelationshipManagerId()).isNull();
    }
}
```
(Rename the ASCII helper `buildDirectRequestNoBrokerNoAgent` — no Cyrillic; that's a transcription artifact above.) Use whatever the policy-create request builder + `PolicyResponse` getters actually are (read them). The assertions on `PolicyResponse.getRelationshipManagerId/Name()` depend on Task 6.1 exposing them on the response — if Task 6.1 hasn't run yet, assert on the persisted policy row via `JdbcTemplate` (`SELECT commission_source_type, relationship_manager_id, relationship_manager_name FROM policies WHERE id = ?`) instead. **Prefer the JDBC assertion** so this task doesn't depend on Task 6.1 ordering.

- [ ] **Step 3: Run — expect green**

Run: `cd cia-backend && mvn -pl cia-api verify -Dit.test='PolicyRmCommissionDerivationIT' -DskipUnitTests=true`
Expected: 4/0/0/0.

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/policy/PolicyRmCommissionDerivationIT.java
git commit -m "$(cat <<'EOF'
test(api): Task 2.2 — PolicyRmCommissionDerivationIT (4 tests)

RM-source derivation: picked for direct+RM+setup; ignored when a broker
is present, when the customer has no RM, and when no RM CommissionSetup
exists. Asserts the persisted snapshot via JDBC.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 3 — GL posting

### Task 3.1: V63 — `POLICY_COMMISSION_RM` posting rule

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V63__seed_policy_commission_rm_posting_rule.sql`

- [ ] **Step 1: Confirm the account codes + the rule-seed shape**

Run: `grep -n "'5130'\|'2520'" cia-backend/cia-api/src/main/resources/db/migration/V32__seed_chart_of_accounts.sql`
Expected: `('5130', 'Insurance acquisition expense', 'EXPENSE', …)` and `('2520', 'Staff payables', 'LIABILITY', …)` both present. Read `V52`/`V54` for the `posting_rule` INSERT column list.

- [ ] **Step 2: Write the seed**

```sql
-- cia-api/src/main/resources/db/migration/V63__seed_policy_commission_rm_posting_rule.sql
-- B2 — RM commission posting rule. Same Dr as broker/agent (5130 — a
-- commission is an acquisition cost regardless of payee); Cr 2520 Staff
-- payables because the RM is internal staff (vs 2320/2330 for external
-- broker/agent commission).
INSERT INTO posting_rule (
    source_event_type,
    debit_account_code,
    credit_account_code,
    narrative_template,
    is_active,
    created_by
) VALUES
    ('POLICY_COMMISSION_RM',
     '5130', '2520',
     'RM commission payable on policy %s',
     TRUE, 'system-seed')
ON CONFLICT (source_event_type) DO NOTHING;
```
(Narrative has a single `%s` = policy number — no payee name, so no `PolicyApprovedEvent` change is needed; see Task 3.2.)

- [ ] **Step 3: Verify it applies**

Run: `cd cia-backend && mvn install -pl cia-api -am -DskipTests -q && mvn -pl cia-api verify -Dit.test='SubledgerPostingServiceIT' -DskipUnitTests=true`
Expected: BUILD SUCCESS (the FK to chart_of_account resolves; the rule inserts).

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/db/migration/V63__seed_policy_commission_rm_posting_rule.sql
git commit -m "$(cat <<'EOF'
feat(finance): Task 3.1 — V63 POLICY_COMMISSION_RM posting rule (Dr 5130 / Cr 2520)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3.2: RM commission branch in `SubledgerPostingService`

**Files:**
- Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/gl/SubledgerPostingService.java`

- [ ] **Step 1: Read the existing branches + constants**

Run: `grep -n 'SOURCE_BROKER\|SOURCE_AGENT\|EVENT_POLICY_COMMISSION\|replayPolicyApproved\|postTwoLine' cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/gl/SubledgerPostingService.java`
Confirm constants `SOURCE_BROKER`/`SOURCE_AGENT` (the string values matching `CommissionSourceType.name()`), `EVENT_POLICY_COMMISSION_BROKER`/`_AGENT`, and the two `postTwoLine(...)` overloads — one taking a trailing payee-name arg (broker/agent), one without (base premium posting, single `%s` narrative).

- [ ] **Step 2: Add the RM constants**

Alongside the existing `SOURCE_*` / `EVENT_POLICY_COMMISSION_*` constants:
```java
private static final String SOURCE_RM = "RELATIONSHIP_MANAGER";
private static final String EVENT_POLICY_COMMISSION_RM = "POLICY_COMMISSION_RM";
```

- [ ] **Step 3: Add the RM branch in `replayPolicyApproved`**

Inside the `if (!zeroOrNull(event.commissionAmount())) { … }` block, after the BROKER/AGENT `else if`s, add:
```java
} else if (SOURCE_RM.equals(event.commissionSourceType())) {
    // RM commission: Dr 5130 / Cr 2520. No payee name in the narrative
    // (RM is identified by policies.relationship_manager_id + the per-RM
    // report) → use the no-name postTwoLine overload (single %s = policy no.).
    postTwoLine(
        MODULE_POLICY,
        EVENT_POLICY_COMMISSION_RM,
        event.policyId().toString(),
        event.commissionAmount(),
        event.policyStartDate(),
        event.currencyCode(),
        event.classOfBusinessId(),
        event.policyNumber());
}
```
Use the **no-name** `postTwoLine` overload (the one the base premium line uses). If both overloads require the same arg count and only differ by the trailing name, confirm by reading their signatures; the RM call must match the no-name overload exactly.

- [ ] **Step 4: Verify compile**

Run: `cd cia-backend && mvn -pl cia-finance compile -DskipTests -am`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/gl/SubledgerPostingService.java
git commit -m "$(cat <<'EOF'
feat(finance): Task 3.2 — RM commission branch in SubledgerPostingService

When commissionSourceType=RELATIONSHIP_MANAGER, post event.commissionAmount()
through POLICY_COMMISSION_RM (Dr 5130 / Cr 2520) using the no-payee-name
narrative overload — so no PolicyApprovedEvent change is needed (the event
already carries commissionSourceType + commissionAmount from V51/84c).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3.3: Ensure the event carries RM commission on approval

**Files:**
- Modify (only if needed): `cia-backend/cia-policy/.../PolicyService.java` (the `approve()` path that builds `PolicyApprovedEvent`)

- [ ] **Step 1: Verify the event is already populated for RM**

Run: `grep -n 'PolicyApprovedEvent\|commissionAmount\|computeCommissionAmount\|commissionSourceType' cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/PolicyService.java`
Confirm `approve()` computes `commissionAmount` from `policy.getCommissionRate()` + net premium **regardless of source** (it should — `computeCommissionAmount()` is source-agnostic) and sets `commissionSourceType = policy.getCommissionSourceType()` on the event. Since RM policies carry `commissionSourceType = RELATIONSHIP_MANAGER` + a non-null `commissionRate` (Task 2.1), the event will already carry the right `commissionSourceType` + `commissionAmount` with **no change**.

- [ ] **Step 2: If `computeCommissionAmount`/event population is gated to broker/agent only, generalise it**

If (and only if) the approval path conditionally computes commission **just** for broker/agent (e.g. `if (brokerId != null || agentId != null)`), change the guard to fire whenever `commissionSourceType != null && commissionRate != null` (source-agnostic), so RM policies get `commissionAmount` populated. Show the exact edited guard here based on what you read. If it's already source-agnostic, **no change** — note that and skip to Step 4.

- [ ] **Step 3: Verify compile**

Run: `cd cia-backend && mvn -pl cia-policy compile -DskipTests -am`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit (or record no-op)**

If a change was needed:
```bash
git add cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/PolicyService.java
git commit -m "$(cat <<'EOF'
fix(policy): Task 3.3 — populate commissionAmount on approval for RM-sourced policies

Generalise the approval-time commission computation to fire for any
non-null commission source (was broker/agent-gated), so RELATIONSHIP_MANAGER
policies carry commissionAmount on PolicyApprovedEvent.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```
If no change was needed, report Task 3.3 as a verified no-op (the event was already source-agnostic) and proceed.

---

### Task 3.4: Posting IT + no-CreditNote regression

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/gl/SubledgerPostingRmCommissionIT.java`

- [ ] **Step 1: Read the SubledgerPostingServiceIT pattern**

Run: `sed -n '50,140p' cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/gl/SubledgerPostingServiceIT.java`
Note the `@DataJpaTest` + `@Import` base, the `PolicyApprovedEvent` constructor arg order, the `loadJe(module, eventType, ref)` + `assertLine(jeId, accountCode, dr, cr)` helpers, the fiscal-period seeding. Bump its base's `flyway.target` to 62 if not already (Task 1.1).

- [ ] **Step 2: Write the posting IT (2 tests)**

```java
package com.nubeero.cia.api.finance.gl;

class SubledgerPostingRmCommissionIT extends <SAME_BASE_AS_SubledgerPostingServiceIT> {

    @Autowired SubledgerPostingService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    private LocalDate businessDate;  // seed fiscal period like SubledgerPostingServiceIT

    @Test
    @DisplayName("RM-sourced PolicyApproved → commission JE Dr 5130 / Cr 2520")
    void rmCommissionPosts() {
        UUID policyId = UUID.randomUUID();
        // net premium 500000, RM rate 2.5% → commissionAmount 12500.00
        service.onPolicyApproved(new PolicyApprovedEvent(
            policyId, "POL-RM-001", UUID.randomUUID(), "Acme", null, null,
            "Motor", new BigDecimal("500000.00"), "NGN",
            LocalDate.of(2027, 5, 14), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal("10000000.00"), businessDate,
            /* commissionSourceType */ "RELATIONSHIP_MANAGER",
            /* commissionAmount     */ new BigDecimal("12500.00"),
            /* brokerName */ null, /* agentName */ null));
        entityManager.flush();

        Map<String, Object> je = loadJe("policy", "POLICY_COMMISSION_RM", policyId.toString());
        assertThat(je).isNotEmpty();
        assertLine((UUID) je.get("id"), "5130", "12500.00", "0.00");
        assertLine((UUID) je.get("id"), "2520", "0.00", "12500.00");
    }

    @Test
    @DisplayName("RM-sourced PolicyApproved → still books the base premium JE")
    void rmCommissionStillBooksBasePremium() {
        UUID policyId = UUID.randomUUID();
        service.onPolicyApproved(/* same event as above */ ...);
        entityManager.flush();
        // the base premium JE (EVENT_POLICY_APPROVED) is posted as for any policy
        Map<String, Object> premiumJe = loadJe("policy", "POLICY_APPROVED", policyId.toString());
        assertThat(premiumJe).isNotEmpty();
    }
}
```
Match the **actual** `PolicyApprovedEvent` constructor arity/order (read it — the trailing args above assume `(…, commissionSourceType, commissionAmount, brokerName, agentName)`; adjust to reality). The `assertLine` amount format (`"12500.00"`) must match how the IT formats `numeric` (the existing test uses `"500000.00"`).

- [ ] **Step 3: Run — expect green**

Run: `cd cia-backend && mvn -pl cia-api verify -Dit.test='SubledgerPostingRmCommissionIT' -DskipUnitTests=true`
Expected: 2/0/0/0.

- [ ] **Step 4: No-CreditNote regression test**

Add to the same file (or a focused IT next to the commission-listener tests) a test asserting that an RM-sourced `PolicyApprovedEvent` produces **no** CreditNote. Read `PolicyCommissionCreditNoteListener` + how its existing broker/agent IT asserts a CreditNote *was* created (`SELECT COUNT(*) FROM credit_notes WHERE entity_id = ?`), then assert COUNT = 0 for an RM-sourced event:
```java
@Test
@DisplayName("RM-sourced PolicyApproved → NO CreditNote (paid via payroll, not Payables)")
void rmCommissionCreatesNoCreditNote() {
    // publish/handle an RM-sourced PolicyApprovedEvent through the listener,
    // then:
    Integer cnCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM credit_notes WHERE entity_id = ?", Integer.class, policyId);
    assertThat(cnCount).isZero();
}
```
If the listener test lives in a different module/base (it's `cia-finance`), put this regression in the IT class that already exercises `PolicyCommissionCreditNoteListener` for broker/agent — mirror its harness. Confirm the listener still skips RM (it does today — this locks it).

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/finance/gl/SubledgerPostingRmCommissionIT.java
git commit -m "$(cat <<'EOF'
test(api): Task 3.4 — RM commission posting IT + no-CreditNote regression

Dr 5130 / Cr 2520 = commissionAmount on RM-sourced approval; base premium
JE still books; and NO CreditNote is created for RM (paid via payroll).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 4 — Per-RM commission report

### Task 4.1: `DataSource.RM_COMMISSION` + query-builder entry

**Files:**
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/.../DataSource.java`
- Modify: `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/.../ReportQueryBuilder.java`

- [ ] **Step 1: Read the aggregation mechanism (BASE_QUERIES + BASE_QUERY_TAILS) via TRIAL_BALANCE**

Run: `grep -n 'BASE_QUERIES\|BASE_QUERY_TAILS\|TRIAL_BALANCE\|applyComputedFields\|GROUP BY\|appendFilters\|ORDER BY' cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/*/ReportQueryBuilder.java`
Understand: how `TRIAL_BALANCE` splits its query into a `BASE_QUERIES` head (`SELECT … FROM … WHERE …`) + a `BASE_QUERY_TAILS` suffix (`GROUP BY … `) so the engine can inject `AND <filter>` clauses between them, and how the filter keys map to columns. The per-RM report is a grouped/aggregated query → it needs the same head+tail split.

- [ ] **Step 2: Add the enum value**

In `DataSource.java`, add `RM_COMMISSION` (end of the enum):
```java
    IFRS9_MOVEMENT,
    RM_COMMISSION
```

- [ ] **Step 3: Add the BASE_QUERIES head + BASE_QUERY_TAILS suffix**

In `ReportQueryBuilder`, add to `BASE_QUERIES`:
```java
Map.entry(DataSource.RM_COMMISSION,
    "SELECT rm.name AS relationship_manager_name, " +
    "COUNT(p.id) AS policy_count, " +
    "SUM(p.premium) AS total_premium, " +
    "SUM(p.premium * p.commission_rate / 100) AS total_accrued " +
    "FROM policy p " +
    "JOIN relationship_managers rm ON rm.id = p.relationship_manager_id " +
    "WHERE p.commission_source_type = 'RELATIONSHIP_MANAGER' AND p.deleted_at IS NULL"),
```
And to `BASE_QUERY_TAILS` (mirror the TRIAL_BALANCE tail entry's exact map name/shape):
```java
Map.entry(DataSource.RM_COMMISSION,
    " GROUP BY rm.name ORDER BY total_accrued DESC"),
```
**Reconciliation note:** `total_accrued = SUM(premium × rate / 100)` must equal the posted Cr-2520 accruals. The posting uses `event.commissionAmount() = netPremium × rate / 100`. Confirm the policy column that equals the event's net premium — if `policies` has a distinct `net_premium`, use that column in both `total_premium` and `total_accrued` instead of `p.premium`; if `p.premium` *is* the net premium used at approval, keep `p.premium`. Read `Policy.java` + `computeCommissionAmount()` to confirm, and use the column that reconciles. Also confirm the real table name is `policy` (singular, as in the existing POLICIES base query) vs `policies` (the migration uses `policies`) — **use whatever the existing POLICIES `BASE_QUERIES` entry uses** (the exploration shows `FROM policy p`), and confirm `relationship_managers` is the correct table name.

- [ ] **Step 4: Verify compile**

Run: `cd cia-backend && mvn -pl cia-reports compile -DskipTests -am`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add cia-backend/cia-reports/
git commit -m "$(cat <<'EOF'
feat(reports): Task 4.1 — RM_COMMISSION data source (per-RM accrual aggregation)

New DataSource.RM_COMMISSION + BASE_QUERIES head + BASE_QUERY_TAILS GROUP BY
(mirrors TRIAL_BALANCE's head/tail split) aggregating RM-sourced policies by
RM: name, policy count, total premium, total accrued (Σ premium × rate/100).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.2: V64 — seed the per-RM SYSTEM report

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V64__seed_rm_commission_report.sql`

- [ ] **Step 1: Read a representative report seed**

Run: `sed -n '1,40p' cia-backend/cia-api/src/main/resources/db/migration/V18__seed_system_reports.sql`
Confirm the `report_definition` columns (`name, description, category, type, data_source, config, is_pinnable`) + the `config` JSONB shape (fields/filters/groupBy/sortBy/chart). Confirm the current SYSTEM report count (67) for the CLAUDE.md update.

- [ ] **Step 2: Write the seed**

```sql
-- cia-api/src/main/resources/db/migration/V64__seed_rm_commission_report.sql
-- B2 — per-RM commission accrual SYSTEM report (Module 11). Aggregates
-- RM-sourced policies by RM over a period so payroll knows what each RM
-- is owed. Reads DataSource.RM_COMMISSION (head/tail in ReportQueryBuilder).
INSERT INTO report_definition (name, description, category, type, data_source, config, is_pinnable)
VALUES (
  'RM Commission Accrual',
  'Relationship-Manager commission accrued (Cr 2520) per RM over a period — name, policy count, total premium, total accrued.',
  'FINANCE', 'SYSTEM', 'RM_COMMISSION',
  '{
    "fields": [
      {"key":"relationship_manager_name","label":"Relationship Manager","type":"STRING","computed":false},
      {"key":"policy_count","label":"Policies","type":"NUMBER","computed":false},
      {"key":"total_premium","label":"Total Premium (₦)","type":"MONEY","computed":false},
      {"key":"total_accrued","label":"Total Accrued (₦)","type":"MONEY","computed":false}
    ],
    "filters": [
      {"key":"date_from","label":"Date From","type":"DATE","required":true},
      {"key":"date_to","label":"Date To","type":"DATE","required":true}
    ],
    "sortBy":"total_accrued","sortDir":"DESC",
    "chart":{"type":"BAR","xAxis":"relationship_manager_name","yAxis":"total_accrued"}
  }',
  false
);
```
**Confirm the date-filter mechanics at task time:** the `date_from`/`date_to` filters must inject against the policy's approval date column (read how the GWP report's date filters map to a column — the engine appends `AND <col> BETWEEN ? AND ?` or similar **before** the GROUP BY tail). If the engine maps `date_from`/`date_to` to a fixed column name, ensure the RM_COMMISSION head exposes/qualifies that column (e.g. `p.created_at` or the approval date). Adjust the head's WHERE/column aliasing so the injected filter lands on the policy approval date. Mirror the GWP report's working date-filter wiring exactly.

- [ ] **Step 3: Verify it applies**

Run: `cd cia-backend && mvn install -pl cia-api -am -DskipTests -q && mvn -pl cia-api verify -Dit.test='SubledgerPostingServiceIT' -DskipUnitTests=true`
Expected: BUILD SUCCESS (the JSONB INSERT succeeds; `@JdbcTypeCode(SqlTypes.JSON)` accepts it).

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-api/src/main/resources/db/migration/V64__seed_rm_commission_report.sql
git commit -m "$(cat <<'EOF'
feat(reports): Task 4.2 — V64 seed RM Commission Accrual SYSTEM report (Finance)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4.3: Report IT — per-RM aggregation

**Files:**
- Create: `cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/RmCommissionReportIT.java`

- [ ] **Step 1: Find the report-run IT pattern**

Run: `grep -rln 'ReportRunnerService\|runReport\|report_definition' cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/ | head`
Use the report IT base; read how an existing report IT seeds source rows + runs a report by name/id + asserts the result rows. Mirror it.

- [ ] **Step 2: Write the IT (1 test)**

```java
package com.nubeero.cia.api.reports;

class RmCommissionReportIT extends <REPORT_IT_BASE> {  // flyway.target=62 (sees V64)

    @Test
    void aggregatesAccruedCommissionByRm() {
        // Seed 2 RMs; for RM-A two RM-sourced policies (premium 100000 @2% + 200000 @2%),
        // for RM-B one RM-sourced policy (premium 50000 @3%). Also seed one BROKER-sourced
        // policy that must be EXCLUDED.
        UUID rmA = seedRelationshipManager("RM Alpha");
        UUID rmB = seedRelationshipManager("RM Beta");
        seedRmPolicy(rmA, "100000.00", "2.0000");
        seedRmPolicy(rmA, "200000.00", "2.0000");
        seedRmPolicy(rmB, "50000.00",  "3.0000");
        seedBrokerPolicy("999999.00", "5.0000");  // excluded

        List<Map<String,Object>> rows = runReportByName("RM Commission Accrual", broadDateRange());

        // RM Alpha: (100000+200000) premium, accrued = 100000*2% + 200000*2% = 6000
        // RM Beta:  50000 premium, accrued = 50000*3% = 1500
        var alpha = rowFor(rows, "RM Alpha");
        assertThat(alpha.get("policy_count")).isEqualTo(2L);
        assertThat(new BigDecimal(alpha.get("total_accrued").toString())).isEqualByComparingTo("6000.00");
        var beta = rowFor(rows, "RM Beta");
        assertThat(beta.get("policy_count")).isEqualTo(1L);
        assertThat(new BigDecimal(beta.get("total_accrued").toString())).isEqualByComparingTo("1500.00");
        // broker policy excluded:
        assertThat(rows).noneMatch(r -> "999999.00".equals(String.valueOf(r.get("total_premium"))));
    }
}
```
`seedRmPolicy(rmId, premium, rate)` inserts a `policies` row with `commission_source_type='RELATIONSHIP_MANAGER'`, `relationship_manager_id=rmId`, the given `premium` + `commission_rate`, and the approval date inside `broadDateRange()`. `runReportByName(...)` invokes the report runner for the V64-seeded report. Adjust column key casing + numeric types to match what the runner returns. **The accrued math here defines the reconciliation contract** — it must equal the Cr-2520 posting amounts (Task 3.4 used 2.5% × 500000 = 12500; same formula).

- [ ] **Step 3: Run — expect green**

Run: `cd cia-backend && mvn -pl cia-api verify -Dit.test='RmCommissionReportIT' -DskipUnitTests=true`
Expected: 1/0/0/0. If the date filter excludes the seeded policies, align the seeded approval-date column with the filter column (Task 4.2 Step 2).

- [ ] **Step 4: Commit**

```bash
git add cia-backend/cia-api/src/test/java/com/nubeero/cia/api/reports/RmCommissionReportIT.java
git commit -m "$(cat <<'EOF'
test(api): Task 4.3 — RmCommissionReportIT (per-RM aggregation + broker exclusion)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 5 — Frontend (policy-detail RM source label)

### Task 5.1: PolicyResponse + PolicyDto expose the RM snapshot; detail label

**Files:**
- Modify: `cia-backend/cia-policy/.../dto/PolicyResponse.java`
- Modify: `cia-frontend/packages/api-client/src/modules/policy.ts` (`PolicyDto`)
- Modify: the back-office policy-detail Financial tab component

- [ ] **Step 1: Read PolicyResponse + PolicyDto + the Financial tab**

Run:
```bash
grep -n 'brokerName\|agentName\|commissionSourceType\|relationshipManager' cia-backend/cia-policy/src/main/java/com/nubeero/cia/policy/dto/PolicyResponse.java
grep -n 'brokerName\|agentName\|commissionSourceType\|PolicyDto' cia-frontend/packages/api-client/src/modules/policy.ts
grep -rn 'commissionSourceType\|brokerName\|agentName\|Commission' cia-frontend/apps/back-office/src/modules/policy/
```
Confirm whether `PolicyResponse`/`PolicyDto` already expose `brokerName`/`agentName`/`commissionSourceType` (they should, given the detail page renders commission). Confirm whether the Financial tab already renders the source generically.

- [ ] **Step 2: Add `relationshipManagerId` + `relationshipManagerName` to `PolicyResponse`**

Mirror the `brokerName`/`agentName` fields + their `from(...)`/mapping. (If `PolicyResponse` is a record, add the two components + populate from the entity; if a Lombok class, add the two fields + the mapper line.)

- [ ] **Step 3: Add the matching fields to `PolicyDto` (frontend)**

Mirror the broker/agent fields in `PolicyDto` (+ the zod schema if `policy.ts` uses one): `relationshipManagerId: string | null`, `relationshipManagerName: string | null`. This keeps `check-dto-drift.mjs` clean (PolicyResponse ↔ PolicyDto must match).

- [ ] **Step 4: Render the RM source label in the Financial tab**

Where the tab shows the commission source, add the RM case — e.g. map `commissionSourceType === 'RELATIONSHIP_MANAGER'` → `Relationship Manager — {relationshipManagerName}`. If the tab already renders `commissionSourceType` + a `{broker|agent}Name` generically, extend the same mapping to RM (minimal). Note in the label that RM commission is accrued (paid via payroll), if the tab shows payable/payment status for broker/agent — RM has no CreditNote, so don't render a payment/voucher action for the RM case.

- [ ] **Step 5: Verify gates**

Run:
```bash
cd cia-backend && mvn -pl cia-policy compile -DskipTests -am
cd /Users/razormvp/CoreInsurance && pnpm --filter @cia/back-office typecheck && node cia-frontend/scripts/check-dto-drift.mjs && bash cia-frontend/scripts/check-api-wiring.sh
```
Expected: backend compiles; frontend typecheck clean; DTO drift clean (PolicyResponse ↔ PolicyDto aligned); api-wiring clean.

- [ ] **Step 6: Commit**

```bash
git add cia-backend/cia-policy/ cia-frontend/packages/api-client/src/modules/policy.ts cia-frontend/apps/back-office/src/modules/policy/
git commit -m "$(cat <<'EOF'
feat(policy): Task 5.1 — surface RM as commission source on the policy detail

PolicyResponse + PolicyDto gain relationshipManagerId + relationshipManagerName
(DTO-drift aligned); the Financial tab labels the source "Relationship Manager
— {name}" for RM-sourced policies (no payment action — RM accrues to 2520,
paid via payroll).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Phase 6 — Docs + final verify

### Task 6.1: CLAUDE.md + cia-log.md + final full verify

**Files:**
- Modify: `CLAUDE.md`, `cia-log.md`

- [ ] **Step 1: CLAUDE.md**

- Module 1 / Module 3 commission note: RM is now an operationalised, exclusive third commission source on **direct-channel** policies, accrued on approval as **Dr 5130 Insurance acquisition expense / Cr 2520 Staff payables**, **no CreditNote** (paid via external payroll). Per-policy RM snapshot (V62) auto-derived from the customer's RM.
- Record the 3-source posting split: same Dr 5130 expense; Cr **2320** brokers / **2330** agents / **2520** RM (staff payable).
- Module 11: +1 SYSTEM report ("RM Commission Accrual", Finance) → update the count (67 → 68) and the `DataSource` list (+`RM_COMMISSION`).
- Note Open Question #11 resolved (per-policy RM attribution = snapshot on the policy).

- [ ] **Step 2: cia-log.md — session entry + backlog reconciliation**

Add a session entry (above the most recent) summarising the slice by phase; in "Known follow-ups + backlog reconciliation": **drain `B2`** from the canonical backlog table; note Open Q#11 resolved; no new rows expected (or add any side-discoveries found during execution with a P-rating).

- [ ] **Step 3: Final full verify**

Run:
```bash
cd /Users/razormvp/CoreInsurance/cia-backend && mvn install -pl cia-api -am -DskipTests -q && mvn -pl cia-api verify -DskipUnitTests=true
cd /Users/razormvp/CoreInsurance && pnpm --filter @cia/back-office typecheck && pnpm --filter @cia/back-office test -- --run && node cia-frontend/scripts/check-dto-drift.mjs && bash cia-frontend/scripts/check-api-wiring.sh
```
Expected: cia-api ITs all green (≈ +10 new: 3 constraint + 4 derivation + 2 posting + 1 report, plus the no-CreditNote regression), **0 failures / 0 errors**; frontend typecheck + Vitest + DTO-drift + api-wiring all clean. Report the exact IT total. **No `internal-api.json` change** (this slice adds no REST endpoints — the report uses the existing report-run endpoint; the policy columns add no routes). If the final verify surfaces a failure, fix it (or, if it's a harness gap like a missed Flyway-target bump on another IT base, fix that) before committing.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md cia-log.md
git commit -m "$(cat <<'EOF'
docs(claude,log): Task 6.1 — B2 RM commission docs + backlog drain

CLAUDE.md: RM as exclusive third commission source (direct-channel,
Dr 5130 / Cr 2520, no CreditNote); 3-source posting split; +1 SYSTEM
report (68); DataSource +RM_COMMISSION; Open Q#11 resolved. cia-log.md:
session entry + drains B2 from the canonical backlog.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**1. Spec coverage:**
- §3 IN scope item 1 (V62 columns + 3-way CHECK) → Task 1.1 + 1.2 + 1.3 ✓
- §3 item 2 (auto-derivation) → Task 2.1 + 2.2 ✓
- §3 item 3 (posting rule + RM branch) → Task 3.1 + 3.2 + 3.3 + 3.4 ✓
- §3 item 4 (per-RM report) → Task 4.1 + 4.2 + 4.3 ✓
- §3 item 5 (policy-detail RM display) → Task 5.1 ✓
- §3 item 6 (ITs) → 1.3, 2.2, 3.4, 4.3 ✓
- §6 no-CreditNote → Task 3.4 Step 4 ✓
- §10 docs → Task 6.1 ✓
- §11 backlog drain → Task 6.1 Step 2 ✓

**2. Placeholder scan:** The plan-time *reads* (exact CHECK text, net-premium column, report aggregation mechanism, whether PolicyResponse already exposes broker/agent, whether the approval path is source-agnostic) are framed as explicit "read X then mirror" first-steps with concrete target code — these are codebase-pattern verifications, not deferred decisions. The one genuinely conditional task (3.3) has both branches specified (change-the-guard vs verified-no-op). No "TBD/implement later" without content.

**3. Type consistency:** `CommissionSnapshot` gains a 3rd component `relationshipManagerId` (Task 2.1) used consistently in `create()` (Task 2.1 Step 3). `SOURCE_RM`/`EVENT_POLICY_COMMISSION_RM` defined (3.2 Step 2) + used (3.2 Step 3) + match the V63 `source_event_type` (3.1) + the IT's `loadJe(..., "POLICY_COMMISSION_RM", ...)` (3.4). `DataSource.RM_COMMISSION` (4.1) matches the V64 `data_source` value (4.2) + the report IT (4.3). `relationshipManagerId`/`relationshipManagerName` consistent across Policy entity (1.2), V62 columns (1.1), PolicyResponse + PolicyDto (5.1). Accrual formula `premium × rate/100` consistent between the posting (3.4: 2.5%×500000=12500) and the report (4.3: same formula) — the reconciliation contract.
