# FAC → IFRS-17 PAA (LRC premium-earning) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring inward + outward facultative reinsurance onto the existing IFRS-17 PAA LRC engine so their premium is earned straight-line over coverage, measured consistently with direct policies.

**Architecture:** Approach A — polymorphic contract assignment. `policy_group_assignment` is generalised to `contract_group_assignment(contract_type, contract_id)`; a `contract_nature` dimension on `portfolio` segregates DIRECT / FAC_INWARD / FAC_OUTWARD groups. `ContractGroupingService` gains two FAC listeners; `LrcEngine` dispatches its premium/coverage read by `contract_type`. The **direct path stays behaviourally identical** — the dispatch only *adds* FAC branches. Inward books an LRC *liability* (`2210`→`4330`); outward books a reinsurance-held *asset* (`1410`→`5210`) with §65 commission-netting.

**Tech Stack:** Java 21, Spring Boot 3.5.14, Flyway, Hibernate, PostgreSQL (schema-per-tenant), JUnit 5 + Testcontainers (ITs in `cia-api/src/test`), React + Vitest (Task 7).

**Spec:** `docs/superpowers/specs/2026-08-08-fac-ifrs17-paa-design.md`. **Scope of THIS plan = spec Slices 1–6.** Spec Slice 7 (FAC LIC + §66A loss-recovery) is a separate future brainstorm→spec→plan and is NOT in this plan.

## Global Constraints

- **Accounting sign-off gate (spec §10) is REQUIRED before execution.** Do not run these tasks until the accounting owner confirms §65 commission-netting (outward) and the modified-prospective transition stance. The plan is written now; execution is held for sign-off.
- **Direct-policy path must stay byte-identical.** Every task keeps the ~20-22 existing PAA/GL/NAICOM/migration ITs green (`cia-api/src/test/.../finance/{paa,gl,naicom,migration}/`). The `POLICY` branch of every dispatch reproduces today's behaviour.
- **Flyway: new versioned files only** (V76+). Never edit an existing migration. Per-tenant schema; migrations run unqualified into the tenant schema.
- **No `posting_rule` rows for FAC** — FAC postings are inline-compound in `SubledgerPostingService` (like today's `replayFacPremium*`); the LRC release is posted by `LrcEngine`.
- **JE idempotency** unchanged: `UNIQUE(source_module, source_event_type, source_reference)`; FAC LRC JEs reuse module `"paa"`, event `"LRC_RECOGNITION"`, ref `periodId + ":" + groupId`.
- **Money math** mirrors `LrcEngine`: `FRACTION_SCALE=12` intermediate, HALF_UP to `MONEY_SCALE=2`; inclusive day count (`ChronoUnit.DAYS.between(from,to)+1`).
- **IT invariants:** `@SpringBootTest` ITs extend the finance web IT support base; `em.flush()` at each service-call boundary (CLAUDE.md `@DataJpaTest`/`@Transactional` note); role-gated endpoints use `hasRole(...)` → `ROLE_`-prefixed `@WithMockUser`.
- **Build/verify:** `mvn -q install -DskipTests -pl <module> -am` then `mvn -q verify -pl cia-api -Dit.test=<IT> -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`; read results from `cia-api/target/failsafe-reports/*.txt`. **A new/changed endpoint requires regenerating `docs-site/static/internal-api.json`** (`InternalApiSnapshotIT` with `-Dcia.openapi.snapshot.write=true` + `python3 -m json.tool --indent 2`).

---

## File structure

**Task 1 — Data model** (`cia-finance` + `cia-api`):
- Create `db/migration/V76__portfolio_contract_nature.sql` — `contract_nature` column on `portfolio`.
- Create `db/migration/V77__contract_group_assignment.sql` — new table + backfill + drop `policy_group_assignment`.
- Rename `paa/PolicyGroupAssignment.java` → `paa/ContractGroupAssignment.java` (+ `contract_type`, `contract_id`); rename repo → `ContractGroupAssignmentRepository`.
- Modify `paa/Portfolio.java` (+`contractNature`), `paa/ContractGroupingService.java`, `paa/LrcEngine.java`, `paa/LicEngine.java` — reference the new entity/columns; direct behaviour identical.

**Task 2 — FAC grouping ingestion** (`cia-finance`): modify `paa/ContractGroupingService.java` (+2 listeners, nature-aware portfolio, common `assign(...)`), add `paa/ContractNature.java` + `paa/ContractType.java` enums.

**Task 3 — Inward LRC** (`cia-finance`): modify `paa/LrcEngine.java` (dispatch + inward branch), `gl/SubledgerPostingService.java` (accept posting `Cr 2210`).

**Task 4 — Outward LRC + onerous guard** (`cia-finance`): modify `paa/LrcEngine.java` (outward branch), `gl/SubledgerPostingService.java` (confirm posting §65), `paa/OnerousContractTestEngine.java` (exclude FAC_OUTWARD).

**Task 5 — Lifecycle & transition** (`cia-reinsurance` events + `cia-finance`): FAC cancel/renew/extend derecognition + modified-prospective cutover service.

**Task 6 — Downstream surfacing** (`cia-api` migration + `cia-finance` + `cia-reports`): V78 view recreation, `MovementAnalysisService`, `Ifrs17DisclosureEngine`, `ReportQueryBuilder`.

**Task 7 — Frontend** (`cia-frontend`): `ContractGroupsPage`, `PaaMovementAnalysisPage`, `@cia/api-client` schemas.

---

## Task 1: Data model & polymorphic assignment

Generalise the assignment table to `(contract_type, contract_id)` and add `contract_nature` to `portfolio`, keeping the direct path identical.

**Files:**
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V76__portfolio_contract_nature.sql`
- Create: `cia-backend/cia-api/src/main/resources/db/migration/V77__contract_group_assignment.sql`
- Rename/Modify: `cia-backend/cia-finance/src/main/java/com/nubeero/cia/finance/paa/PolicyGroupAssignment.java` → `ContractGroupAssignment.java`
- Rename/Modify: the repo `PolicyGroupAssignmentRepository.java` → `ContractGroupAssignmentRepository.java`
- Modify: `paa/Portfolio.java`, `paa/ContractGroupingService.java`, `paa/LrcEngine.java` (`:178` assignment lookup), `paa/LicEngine.java` (`:198-205` JOIN)
- Test: `cia-api/src/test/java/com/nubeero/cia/api/migration/V77ContractGroupAssignmentMigrationTest.java`; existing `ContractGroupingServiceIT`, `LrcEngineIT`, `LicEngineIT` must stay green.

**Interfaces:**
- Produces: `ContractGroupAssignment` entity with `getContractType():ContractType`, `getContractId():UUID`, `getGroup():GroupOfContracts`; `ContractGroupAssignmentRepository.findByContractTypeAndContractIdAndDeletedAtIsNull(ContractType, UUID)` and `findByGroupIdAndDeletedAtIsNullOrderByAssignedAtAsc(UUID)`. `Portfolio.getContractNature():ContractNature`. Enums `ContractType {POLICY, FAC_INWARD, FAC_OUTWARD}`, `ContractNature {DIRECT, FAC_INWARD, FAC_OUTWARD}` (Task 2 formally adds them; Task 1 may introduce `ContractType` for the entity — define it here in `paa/ContractType.java` and let Task 2 add `ContractNature`).

- [ ] **Step 1: Write the migration-guard failing test**

Create `V77ContractGroupAssignmentMigrationTest.java` mirroring `V37`-era guards (`cia-api/src/test/.../migration/`). Boot Flyway on a Testcontainers Postgres, then assert:

```java
@Test
void contractGroupAssignmentReplacesPolicyGroupAssignment() {
  // table exists with the polymorphic columns
  assertThat(columnExists("contract_group_assignment", "contract_type")).isTrue();
  assertThat(columnExists("contract_group_assignment", "contract_id")).isTrue();
  // old table dropped
  assertThat(tableExists("policy_group_assignment")).isFalse();
  // portfolio has contract_nature defaulting DIRECT
  assertThat(columnExists("portfolio", "contract_nature")).isTrue();
}
```

- [ ] **Step 2: Run it — expect FAIL** (`policy_group_assignment` still exists, no `contract_nature`).

`mvn -q verify -pl cia-api -Dit.test=V77ContractGroupAssignmentMigrationTest -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`

- [ ] **Step 3: Write V76**

```sql
-- V76__portfolio_contract_nature.sql
ALTER TABLE portfolio ADD COLUMN contract_nature VARCHAR(20) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE portfolio ADD CONSTRAINT ck_portfolio_contract_nature
  CHECK (contract_nature IN ('DIRECT','FAC_INWARD','FAC_OUTWARD'));
-- code uniqueness (uq_portfolio_code) already segregates natures via the nature-prefixed code.
```

- [ ] **Step 4: Write V77** (create → backfill → drop, one migration)

```sql
-- V77__contract_group_assignment.sql
CREATE TABLE contract_group_assignment (
  id            UUID PRIMARY KEY,
  contract_type VARCHAR(20) NOT NULL,
  contract_id   UUID        NOT NULL,
  group_id      UUID        NOT NULL,
  assigned_at   TIMESTAMPTZ,
  created_at    TIMESTAMPTZ, updated_at TIMESTAMPTZ, created_by VARCHAR(255), deleted_at TIMESTAMPTZ,
  CONSTRAINT uq_cga_type_contract UNIQUE (contract_type, contract_id),
  CONSTRAINT ck_cga_contract_type CHECK (contract_type IN ('POLICY','FAC_INWARD','FAC_OUTWARD')),
  CONSTRAINT ck_cga_contract_id_present CHECK (contract_id IS NOT NULL),
  CONSTRAINT fk_cga_group FOREIGN KEY (group_id) REFERENCES group_of_contracts (id)
);
CREATE INDEX idx_cga_group ON contract_group_assignment (group_id) WHERE deleted_at IS NULL;

INSERT INTO contract_group_assignment
  (id, contract_type, contract_id, group_id, assigned_at, created_at, updated_at, created_by, deleted_at)
SELECT id, 'POLICY', policy_id, group_id, assigned_at, created_at, updated_at, created_by, deleted_at
FROM policy_group_assignment;

DROP TABLE policy_group_assignment;
```

- [ ] **Step 5: Rename the entity + repo**

Rename `PolicyGroupAssignment` → `ContractGroupAssignment` (`@Table(name = "contract_group_assignment")`). Replace `policyId` with `contractType` (`@Enumerated(STRING) @Column(name="contract_type", updatable=false)`) + `contractId` (`@Column(name="contract_id", updatable=false)`). Keep `@ManyToOne group`, `assignedAt`. Rename repo → `ContractGroupAssignmentRepository` with `findByContractTypeAndContractIdAndDeletedAtIsNull(ContractType, UUID)` (replaces `findByPolicyIdAndDeletedAtIsNull`) and keep `findByGroupIdAndDeletedAtIsNullOrderByAssignedAtAsc(UUID)`. Create `paa/ContractType.java` enum `{POLICY, FAC_INWARD, FAC_OUTWARD}`.

- [ ] **Step 6: Update direct-path callers to preserve behaviour**

In `ContractGroupingService.replayPolicyApproved` (`:100-118`): the idempotency lookup becomes `findByContractTypeAndContractIdAndDeletedAtIsNull(ContractType.POLICY, event.policyId())`; the write sets `setContractType(POLICY); setContractId(event.policyId())`. In `LrcEngine` (`:178`, `:192`): iterate assignments unchanged, but read the id via `a.getContractId()` (the `POLICY` branch still calls `loadPolicyPricing(contractId)` — unchanged behaviour). In `LicEngine.computeRollForward` (`:198-205`): change the JOIN to `JOIN contract_group_assignment pga ON pga.contract_id = c.policy_id AND pga.contract_type = 'POLICY'` (direct claims only — FAC LIC is out of scope this plan).

- [ ] **Step 7: Add `contractNature` to Portfolio**

`Portfolio.java`: add `@Enumerated(STRING) @Column(name="contract_nature", updatable=false) private ContractNature contractNature = ContractNature.DIRECT;`. Define `paa/ContractNature.java` enum `{DIRECT, FAC_INWARD, FAC_OUTWARD}`. `ContractGroupingService.resolveOrCreatePortfolio` sets `DIRECT` on the direct path (unchanged behaviour; Task 2 generalises the signature).

- [ ] **Step 8: Run the migration guard + the 3 existing PAA ITs — expect PASS**

`mvn -q install -DskipTests -pl cia-finance -am && mvn -q verify -pl cia-api -Dit.test=V77ContractGroupAssignmentMigrationTest,ContractGroupingServiceIT,LrcEngineIT,LicEngineIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false`
Expected: all green (direct path identical; new table in place).

- [ ] **Step 9: Update `ChartOfAccountServiceIT` if referenced + commit**

```bash
git add cia-backend/cia-finance cia-backend/cia-api/src/main/resources/db/migration/V76__* cia-backend/cia-api/src/main/resources/db/migration/V77__* cia-backend/cia-api/src/test
git commit -m "feat(paa): polymorphic contract_group_assignment + portfolio contract_nature (direct path identical)"
```

---

## Task 2: FAC grouping ingestion

Wire the two existing FAC events into grouping, creating `FAC_INWARD`/`FAC_OUTWARD` portfolios/groups.

**Files:**
- Modify: `cia-finance/.../paa/ContractGroupingService.java`
- Test: `cia-api/src/test/java/com/nubeero/cia/api/finance/paa/FacContractGroupingIT.java`

**Interfaces:**
- Consumes: `RiFacInwardAcceptedEvent(facInwardId, …, classOfBusinessId, …)` and `FacPremiumCededEvent(facCoverId, …, policyId, …)` from `cia-common.event`; `ContractGroupAssignment`, `ContractType`, `ContractNature` (Task 1); `PolicyClassResolver.findClassByPolicyId(UUID)` (used by `SubledgerPostingService` today for outward COB).
- Produces: `assign(ContractType, UUID contractId, UUID cobId, int cohortYear, ContractNature)` returning `ContractGroupAssignment`.

- [ ] **Step 1: Write the failing IT**

`FacContractGroupingIT` — publish a `RiFacInwardAcceptedEvent` and a `FacPremiumCededEvent`; assert a `contract_group_assignment` row exists for each with the right `contract_type`, and its group's portfolio has the right `contract_nature`:

```java
@Test void inwardFacGroupsIntoFacInwardPortfolio() {
  publisher.publishEvent(inwardAcceptedEvent(cobId, LocalDate.of(2026,1,1)));
  var a = repo.findByContractTypeAndContractIdAndDeletedAtIsNull(ContractType.FAC_INWARD, facInwardId).orElseThrow();
  assertThat(a.getGroup().getPortfolio().getContractNature()).isEqualTo(ContractNature.FAC_INWARD);
  assertThat(a.getGroup().getCohortYear()).isEqualTo(2026);
}
@Test void outwardFacGroupsIntoFacOutwardPortfolio() { /* symmetric; COB resolved from linked policy */ }
@Test void reFireIsIdempotent() { /* publish twice → one assignment row */ }
```

- [ ] **Step 2: Run it — expect FAIL** (no listeners; no assignment written).

- [ ] **Step 3: Generalise `resolveOrCreatePortfolio` + add `assign(...)`**

Change `resolveOrCreatePortfolio(UUID cob)` → `resolveOrCreatePortfolio(UUID cob, ContractNature nature)`; the auto-code becomes nature-prefixed (`DIR-`/`FIN-`/`FOU-` + COB code, truncated to 20) so `uq_portfolio_code` segregates natures; set `portfolio.contractNature = nature`. Extract the common body of `replayPolicyApproved` into `private ContractGroupAssignment assign(ContractType type, UUID contractId, UUID cobId, int cohortYear, ContractNature nature)` — idempotency lookup, portfolio, group, assignment write. `replayPolicyApproved` calls `assign(POLICY, policyId, cobId, policyStartYear, DIRECT)`.

- [ ] **Step 4: Add the two FAC listeners**

```java
@EventListener @Transactional
public void onFacInwardAccepted(RiFacInwardAcceptedEvent e) {
  // idempotent: same (FAC_INWARD, facInwardId) short-circuits inside assign(...)
  assign(ContractType.FAC_INWARD, e.facInwardId(), e.classOfBusinessId(),
         coverStartYear(e.facInwardId()), ContractNature.FAC_INWARD);
}
@EventListener @Transactional
public void onFacPremiumCeded(FacPremiumCededEvent e) {
  UUID cob = policyClassResolver.findClassByPolicyId(e.policyId());   // outward COB from linked policy
  assign(ContractType.FAC_OUTWARD, e.facCoverId(), cob,
         coverStartYear(e.facCoverId(), /*outward*/ true), ContractNature.FAC_OUTWARD);
}
```

Cohort year = coverage-start year. Neither FAC event carries `coverFrom`, so resolve it via a scalar read (native SQL `SELECT cover_from FROM ri_fac_inwards|ri_fac_covers WHERE id=?`) in a small helper — mirror `LrcEngine.loadPolicyPricing`'s native-SQL pattern (`:314-325`) to avoid cross-module entity coupling.

- [ ] **Step 5: Run the IT — expect PASS.** `mvn -q install -DskipTests -pl cia-finance -am && mvn -q verify -pl cia-api -Dit.test=FacContractGroupingIT,ContractGroupingServiceIT ...`

- [ ] **Step 6: Commit** — `feat(paa): group inward + outward FAC contracts by contract_nature`

---

## Task 3: Inward FAC LRC measurement

Dispatch LRC pricing by `contract_type`; inward books the LRC liability and earns to income.

**Files:**
- Modify: `cia-finance/.../paa/LrcEngine.java`, `cia-finance/.../gl/SubledgerPostingService.java` (`replayFacPremiumAccepted` `:399-442`)
- Test: `cia-api/src/test/.../finance/paa/InwardFacLrcIT.java`

**Interfaces:**
- Consumes: `ContractGroupAssignment.getContractType()/getContractId()`; group's `portfolio.contractNature`.
- Produces: `LrcEngine` pricing dispatch: `loadPricing(ContractType, UUID) : PolicyPricing`; per-nature posting-account selection.

- [ ] **Step 1: Write the failing IT**

Seed an inward FAC (coverFrom 2026-01-01, coverTo 2026-12-31, gross 1200, net 1000, commission 200), group it (Task 2), run `lrcEngine.recognise(janPeriod)`. Assert: (a) `paa_lrc` row for the FAC_INWARD group with `premium_earned ≈ 1200 × 31/365`; (b) a JE `Dr 2210 / Cr 4330` for that earned amount; (c) reconciliation `opening + received − earned = closing`.

```java
assertThat(lrcRow.premiumEarned()).isEqualByComparingTo(gross.multiply(bd(31)).divide(bd(365), 2, HALF_UP));
assertThat(jeLine("2210").debit()).isEqualByComparingTo(lrcRow.premiumEarned());
assertThat(jeLine("4330").credit()).isEqualByComparingTo(lrcRow.premiumEarned());
```

Also assert the **accept** posting now credits `2210` (LRC), not `4330`: after `replayFacPremiumAccepted`, `Cr 2210 = gross`, `Dr 1330 = net`, `Dr 5240 = commission`.

- [ ] **Step 2: Run — expect FAIL** (inward not grouped-priced; accept still credits 4330).

- [ ] **Step 3: Dispatch pricing in `LrcEngine`**

Add `LRC/REVENUE` account selection by `portfolio.contractNature`: `DIRECT → (2110, 4110)` (unchanged), `FAC_INWARD → (2210, 4330)`. Replace `loadPolicyPricing(a.getPolicyId())` (`:192`) with `loadPricing(a.getContractType(), a.getContractId())` which dispatches: `POLICY → loadPolicyPricing` (unchanged `FROM policies`), `FAC_INWARD → SELECT cover_from, cover_to, gross_premium, currency_code FROM ri_fac_inwards WHERE id=? AND deleted_at IS NULL` (LRC basis = **gross** for inward). `postJe` (`:287-312`) takes the nature-selected debit/credit accounts instead of the hardcoded `COA_LRC_BEL`/`COA_REVENUE_LRC_RELEASE`.

- [ ] **Step 4: Change the accept posting to set up the LRC**

In `SubledgerPostingService.replayFacPremiumAccepted` (`:399-442`): change the credit leg from `COA_INWARD_PREMIUM_INCOME` (`4330`) to `COA_INWARD_LRC = "2210"` (new constant). Legs become `Dr 1330 net / Dr 5240 commission / Cr 2210 gross`. (Income `4330` now only receives the periodic LRC release from `LrcEngine`.)

- [ ] **Step 5: Run the IT + existing `LrcEngineIT` — expect PASS.**

- [ ] **Step 6: Commit** — `feat(paa): inward FAC LRC liability — earn gross premium to 4330 over coverage`

---

## Task 4: Outward FAC LRC measurement + onerous-test guard

Outward books a reinsurance-held asset amortised to expense, with §65 commission-netting; exclude outward groups from the onerous test.

**Files:**
- Modify: `cia-finance/.../paa/LrcEngine.java` (outward branch), `cia-finance/.../gl/SubledgerPostingService.java` (`replayFacPremiumCeded` `:335-365`), `cia-finance/.../paa/OnerousContractTestEngine.java` (`test` `:105`)
- Test: `cia-api/src/test/.../finance/paa/OutwardFacLrcIT.java`

**Interfaces:**
- Consumes: `LrcEngine.loadPricing` (Task 3); `portfolio.contractNature`.
- Produces: outward nature accounts `(1410 asset, 5210 expense)`; onerous-test group filter excluding `FAC_OUTWARD`.

- [ ] **Step 1: Write the failing IT**

Seed an outward FAC (coverFrom 2026-01-01, coverTo 2026-12-31, premiumCeded 1200, commission 200, netPremium 1000), group + `lrcEngine.recognise(janPeriod)`. Assert: (a) `paa_lrc` for the FAC_OUTWARD group with earned ≈ **net** 1000 × 31/365; (b) JE `Dr 5210 / Cr 1410` for that amount; (c) the **confirm** posting is now `Dr 1410 net / Cr 2310 net` (no `4300` credit); (d) a separate assertion that `onerousContractTestEngine.test(period)` produces **no** loss-component JE for the FAC_OUTWARD group even when cumulative "incurred" would exceed "earned".

- [ ] **Step 2: Run — expect FAIL** (outward not priced; confirm still credits 4300; onerous test includes outward).

- [ ] **Step 3: Add the outward LRC branch**

`LrcEngine` nature accounts: `FAC_OUTWARD → (asset 1410 as the "LRC" debit-side substrate, expense 5210 as the "release")`. **Sign flip:** for outward the period posting is `Dr 5210 (expense) / Cr 1410 (asset)` — i.e. the asset is *credited* as it amortises, expense *debited*. Implement by parameterising `postJe` with `(debitAccount, creditAccount)` = `(5210, 1410)` for outward vs `(2210→ no… )`. Concretely: for DIRECT/FAC_INWARD the earned amount is `Dr LRC / Cr revenue`; for FAC_OUTWARD it is `Dr expense(5210) / Cr asset(1410)`. Encode as a small `record NatureAccounts(String debit, String credit)` resolved from `contractNature`. Outward pricing: `SELECT cover_from, cover_to, net_premium, currency_code FROM ri_fac_covers WHERE id=? AND deleted_at IS NULL` (LRC basis = **net** for outward, per §65 netting).

- [ ] **Step 4: Change the confirm posting (§65 netting)**

In `SubledgerPostingService.replayFacPremiumCeded` (`:335-365`): replace the 3-leg `Dr 5210 / Cr 4300 / Cr 2310` with `Dr 1410 net / Cr 2310 net` (set up the asset; pay the reinsurer net). Add `COA_REINSURANCE_HELD_LRC_ASSET = "1410"`. Commission is netted into the asset (never posted to `4300`/`4320`). (Expense `5210` now only receives the periodic amortisation from `LrcEngine`.)

- [ ] **Step 5: Guard the onerous test**

In `OnerousContractTestEngine.test` (`:105`), when iterating groups, skip any whose `portfolio.contractNature == FAC_OUTWARD` (reinsurance held has no onerous test; §66A loss-recovery is out of scope this plan). Add a one-line skip + a debug log.

- [ ] **Step 6: Run the IT + `OnerousContractTestEngineIT` + `LrcEngineIT` — expect PASS.**

- [ ] **Step 7: Commit** — `feat(paa): outward FAC reinsurance-held asset (§65 netting) + onerous-test excludes held groups`

---

## Task 5: FAC lifecycle & modified-prospective transition

Cancellation releases the unearned LRC/asset; renew/extend recompute; in-force FAC at cutover gets a one-time catch-up into the open period.

**Files:**
- Modify: `cia-reinsurance/.../RiFacInwardService.java` (`cancel` `:182-193`), `cia-reinsurance/.../FacCoverService.java` (`cancel` `:95-107`) — publish a derecognition event; `cia-common/.../event/` add `FacDerecognisedEvent`; `cia-finance/.../paa/` add a derecognition handler + `FacPaaCutoverService`.
- Test: `cia-api/src/test/.../finance/paa/FacLifecycleLrcIT.java`, `FacPaaCutoverIT.java`

**Interfaces:**
- Consumes: `ContractGroupAssignment`, `paa_lrc` closing balances, `PeriodLockService` (must post only into an OPEN period).
- Produces: `FacDerecognisedEvent(contractType, contractId, effectiveDate)`; `FacPaaCutoverService.runCutover(UUID periodId)`.

- [ ] **Step 1: Write the failing lifecycle IT**

Seed + group + recognise an inward FAC for Jan, then `cancel` it mid-Feb. Assert a derecognition JE releases the remaining LRC (`Dr 2210 / Cr 4330` for the unearned balance, or the reversal-shaped entry per the engine's convention) and the group's `paa_lrc` closing goes to zero for remaining coverage. Symmetric outward case: remaining `1410` asset released (`Dr 5210 / Cr 1410`).

- [ ] **Step 2: Run — expect FAIL** (cancel posts no GL today).

- [ ] **Step 3: Publish derecognition on cancel**

`RiFacInwardService.cancel` and `FacCoverService.cancel`: after setting `CANCELLED`, `eventPublisher.publishEvent(new FacDerecognisedEvent(type, id, LocalDate.now(clock)))`. Add a `@Transactional @EventListener` in `cia-finance` that releases the remaining LRC/asset for the contract's group in the current open period (reuse the nature-account resolution from Tasks 3-4; the reversal honours `LockableByPeriod.isReversal()`).

- [ ] **Step 4: Renew/extend — recompute over the new window**

`renew` already writes a new row (new `(FAC_INWARD, newId)` → grouped + priced by Tasks 2-3 automatically — no new code). `extend` moves `coverTo`; add a re-price trigger so the LRC recomputes over the extended window (the LRC engine reads `cover_to` live at each `recognise`, so extend needs no special posting beyond the existing pro-rata delta — assert this in the IT rather than adding code).

- [ ] **Step 5: Write the cutover IT + `FacPaaCutoverService`**

`FacPaaCutoverIT`: seed an in-force FAC whose `coverFrom` predates the cutover open period; run `facPaaCutoverService.runCutover(openPeriodId)`; assert a one-time catch-up recognition posts the inception-to-open-period-start earned portion into the OPEN period only, and that calling it against a CLOSED period throws `PeriodLockedException` (423). Implement `FacPaaCutoverService.runCutover` to enumerate in-force FAC not yet in `contract_group_assignment`, group them, and post the catch-up via the LRC engine bounded to the open period; guard with `PeriodLockService`.

- [ ] **Step 6: Run both ITs — expect PASS.**

- [ ] **Step 7: Commit** — `feat(paa): FAC cancel derecognition + modified-prospective cutover (open period only)`

---

## Task 6: Downstream `contract_nature` surfacing

Carry `contract_nature` through the movement view, disclosure relay, and the three CLOSURES reports.

**Files:**
- Create: `cia-api/src/main/resources/db/migration/V78__movement_view_contract_nature.sql` (recreate the V38 view + column)
- Modify: `cia-finance/.../paa/MovementAnalysisService.java` (+field on `GroupMovementEntry`), `cia-finance/.../naicom/Ifrs17DisclosureEngine.java` (relay), `cia-reports/.../service/ReportQueryBuilder.java` (nature column + filter for `PAA_GROUPS` + `IFRS17_MOVEMENT`)
- Test: `cia-api/src/test/.../finance/paa/MovementAnalysisServiceIT.java` (extend), `V78MovementViewMigrationTest.java`

**Interfaces:**
- Consumes: `portfolio.contract_nature`; the V38 view definition (`V38__create_paa_movement_analysis_view.sql`).
- Produces: `paa_movement_analysis.contract_nature`; `GroupMovementEntry.contractNature`.

- [ ] **Step 1: Write the failing view-migration guard**

`V78MovementViewMigrationTest`: assert `SELECT contract_nature FROM paa_movement_analysis LIMIT 0` succeeds (column present).

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Write V78**

`CREATE OR REPLACE VIEW paa_movement_analysis AS …` — copy the V38 body verbatim and add `p.contract_nature` to the SELECT (the view already joins `portfolio p ON p.id = g.portfolio_id`). No other change.

- [ ] **Step 4: Surface it in `MovementAnalysisService` + disclosure**

Add `contractNature` to the `GroupMovementEntry` record and map it from the view row (`MovementAnalysisService.compute`, `:52-165`). `Ifrs17DisclosureEngine` relays it unchanged (it already passes group entries through).

- [ ] **Step 5: Add nature to the reports**

`ReportQueryBuilder`: add `contract_nature` to the `PAA_GROUPS` projection (`:102-109`, `FROM group_of_contracts g JOIN portfolio`) and expose a `contract_nature` filter key (mirror the `statusCol`/`cobFilterCol` pattern `:532-560`). For `IFRS17_MOVEMENT` (reads the view, `:114-125`) add `contract_nature` as a selectable/filterable column.

- [ ] **Step 6: Extend `MovementAnalysisServiceIT` + run guards — expect PASS.** Assert a FAC_INWARD and a FAC_OUTWARD group surface with the right `contractNature` in the movement analysis.

- [ ] **Step 7: Commit** — `feat(paa,reports): contract_nature through movement view, disclosure, and CLOSURES reports`

---

## Task 7: Frontend — `contract_nature` on the closures pages

Surface the nature dimension and correct the direct-only copy.

**Files:**
- Modify: `cia-frontend/packages/api-client/src/modules/finance-closures.ts` (`ContractGroupSummaryDtoSchema`, `MovementAnalysisDtoSchema` — add `contractNature`)
- Modify: `cia-frontend/apps/back-office/src/modules/closures/pages/ContractGroupsPage.tsx`, `PaaMovementAnalysisPage.tsx`
- Test: `cia-frontend/apps/back-office/src/modules/closures/contract-groups-nature.test.tsx`

**Interfaces:**
- Consumes: the `contractNature` field now returned by `/api/v1/finance/paa/contract-groups` + the movement endpoint (Task 6).
- Produces: a nature column + filter Select; corrected empty-state/header copy.

- [ ] **Step 1: Write the failing Vitest** — mock the fetcher to return a FAC_INWARD + a FAC_OUTWARD + a DIRECT group; assert the table renders a "Nature" column with the three values and that filtering by `FAC_OUTWARD` narrows to one row. (Mirror the `claims-list-server.test.tsx` mock idiom: `vi.mock('@cia/api-client', …)`, `importOriginal('@cia/ui')` replacing `DataTable`.)

- [ ] **Step 2: Run — expect FAIL.** `pnpm --filter @cia/back-office test -- contract-groups-nature`

- [ ] **Step 3: Add `contractNature` to the zod schemas** (`z.enum(['DIRECT','FAC_INWARD','FAC_OUTWARD'])`), a "Nature" column + a nature filter Select on `ContractGroupsPage`, and mirror the nature column on `PaaMovementAnalysisPage`. Correct the hardcoded "created on every PolicyApprovedEvent" copy (`ContractGroupsPage:99,169`) to name direct + FAC sources.

- [ ] **Step 4: Run the test + the full back-office suite + guards — expect PASS.** `pnpm --filter @cia/back-office test && bash cia-frontend/scripts/check-api-wiring.sh && node cia-frontend/scripts/check-dto-drift.mjs`

- [ ] **Step 5: Commit** — `feat(closures-ui): contract_nature column + filter on Contract Groups and Movement Analysis`

---

## After all tasks

- [ ] **Regenerate the OpenAPI snapshot** if any endpoint signature changed (Task 6's report filter keys may): `mvn -q verify -pl cia-api -Dit.test=InternalApiSnapshotIT -Dcia.openapi.snapshot.write=true …` + `python3 -m json.tool --indent 2`.
- [ ] **Full reactor verify:** `mvn -q verify -pl cia-api -am` — 0 failures/errors across the PAA/GL/NAICOM/migration ITs.
- [ ] **cia-log session entry** + backlog reconciliation: land `fac-ifrs17-paa-workstream` (Slices 1-6); note Slice 7 (FAC LIC + §66A) opens as its own row/sub-project; mark `fac-outward-4300-nonpostable-parent` resolved (Task 4 retired the 4300 path) — leaving only its generic "postable-leaf guard on `JournalEntryService.post`" if not folded in.
- [ ] Use superpowers:finishing-a-development-branch.

## Self-review notes

- **Spec coverage:** §3.1→T1(S7)/T2(S3); §3.2→T1; §3.3→T2; §3.4→T3/T4; §4.1→T3; §4.2→T4; §4.3(1)→T5; §4.3(2)→T4; §4.3(3) guard→T4, boundary→(scope-out of Slice 7); §4.4→T5; §5→T6/T7; §6 slices→Tasks; §7→per-task ITs; §8→T1/T6 migrations. §4.3(3) FAC-LIC/§66A (Slice 7) deliberately out of this plan (separate sub-project).
- **Type consistency:** `ContractType {POLICY,FAC_INWARD,FAC_OUTWARD}` (entity discriminator) vs `ContractNature {DIRECT,FAC_INWARD,FAC_OUTWARD}` (portfolio dimension) are **distinct enums by design** — DIRECT policies are `ContractType.POLICY` in a `ContractNature.DIRECT` portfolio. `loadPricing(ContractType,UUID)` and `NatureAccounts(debit,credit)` are used consistently in T3/T4.
- **Non-obvious risk:** Task 4's outward sign-flip (`Dr 5210 / Cr 1410`) vs inward/direct (`Dr LRC / Cr revenue`) — encoded once in `NatureAccounts` so the earned-amount posting direction is data-driven, not branched ad hoc.
