# FAC ↔ IFRS-17 PAA — Design Spec

**Status:** Approved in brainstorm (2026-08-08). **Accounting sign-off REQUIRED before the build slices run** — see §10.
**Backlog row:** `fac-ifrs17-paa-workstream` (P2) — this is its brainstorm→spec; the row stays open until the workstream lands.
**Goal:** Bring **both** inward and outward facultative reinsurance onto the existing IFRS-17 PAA LRC engine so their premium is *earned over coverage* (via an LRC balance released straight-line), instead of the current simple immediate-recognition postings — measuring direct, inward, and outward consistently on one engine.

---

## 1. Background & problem

The IFRS-17 PAA stack (`cia-finance`, Module 12 Phase 2) is hard-coupled to **direct policies**. Facultative reinsurance — inward (`RiFacInward`, V75) and outward (`RiFacCover`) — currently books simple immediate postings and never touches the PAA engine:

- **Inward** (on accept, `RiFacInwardAcceptedEvent` → `SubledgerPostingService.replayFacPremiumAccepted`): `Dr 1330 (net) / Dr 5240 (commission) / Cr 4330 (gross)` — recognises gross premium as income at once.
- **Outward** (on confirm, `FacPremiumCededEvent` → `replayFacPremiumCeded`): `Dr 5210 (ceded premium) / Cr 4300 (commission income) / Cr 2310 (net payable)` — expenses ceded premium at once.

The principled fix earns premium over coverage. The backlog mandates **both directions together** (not inward-only, which would make inward more rigorous than its own outward twin).

### 1.1 Investigation findings (three read-only explorers, 2026-08-08)

**The "PAA = direct policy" coupling is exactly 5 write-seam points (C1–C5):**

| # | Location | Coupling |
|---|---|---|
| C1 | `ContractGroupingService.onPolicyApproved` (`cia-finance/.../paa/ContractGroupingService.java:88`) | Only grouping trigger; listens to `PolicyApprovedEvent` alone. |
| C2 | `PolicyGroupAssignment.policyId` + `V37__create_policy_group_assignment.sql` | `UNIQUE(policy_id)` + hard FK `policy_id → policies(id)`. |
| C3 | `LrcEngine.loadPolicyPricing` (`.../paa/LrcEngine.java:314-325`) | `SELECT policy_start_date, policy_end_date, net_premium, currency_code FROM policies WHERE id = ?`. |
| C4 | `LicEngine.computeRollForward` (`.../paa/LicEngine.java:198-205`) | `JOIN policy_group_assignment pga ON pga.policy_id = c.policy_id`. |
| C5 | `PolicyService.approve()` (`cia-policy/.../PolicyService.java:432`) | Sole publisher of `PolicyApprovedEvent`. |

**Everything downstream is source-agnostic** — `DiscountUnwindEngine`, `OnerousContractTestEngine`, `InsuranceServiceResultService`, `MovementAnalysisService`, the **V38 `paa_movement_analysis` view**, `Ifrs17DisclosureEngine`, the Module-11 CLOSURES reports, and the Phase-5 FE pages all operate at the **group grain** (`group_of_contracts` / `paa_lrc` / `paa_lic`) with no policy reference. Once a facultative contract is grouped and its premium lands in `paa_lrc`, all of it works unchanged — but the groups would be **indistinguishable from direct** without a source dimension (the gap §4 closes).

**The FAC entities are already PAA-ready on data.** Both carry coverage window + premium:

| Concept | `RiFacInward` | `RiFacCover` |
|---|---|---|
| Coverage start / end | `coverFrom` / `coverTo` | `coverFrom` / `coverTo` |
| Gross premium | `grossPremium` | `premiumCeded` |
| Commission | `commissionRate` / `commissionAmount` (flat) | `commissionRate` / `commissionAmount` (flat) |
| Net premium | `netPremium` | `netPremium` |
| Class of business | `classOfBusinessId` (on entity + event) | **none** — resolved from linked `policyId` |
| Currency | `currencyCode` | `currencyCode` |

Events `RiFacInwardAcceptedEvent` and `FacPremiumCededEvent` already exist in `cia-common` — just not wired to grouping.

**Target COA accounts already seeded, currently unused** (`V32__seed_chart_of_accounts.sql`):

- Inward reinsurance issued (liability): `2210` "Inward reinsurance - LRC" (role `LRC_BEL`), `2220` "Inward reinsurance - LIC" (role `LIC_OCR`).
- Reinsurance held (asset): `1410` "Reinsurance - LRC asset" (role `LRC_REINSURANCE`), `1420` "Reinsurance - LIC asset" (role `LIC_REINSURANCE`).
- Inward income leaf `4330`, inward commission expense `5240` (seeded V75).

**IT blast radius ~20-22 classes** (7 PAA-engine ITs + ~5 GL + ~6 NAICOM-engine + ~3 migration guards). `ChartOfAccountServiceIT` already pins `LRC_BEL → [2110, 2210]`.

---

## 2. Approach (decided: **A — polymorphic unification**)

Generalise the grouping/measurement path to accept any contract source, keeping the **direct branch behaviourally identical**. Rejected alternatives: **B** (snapshot-at-ingestion `paa_contract` table) — cleanest end-state but rewrites the *direct* read path + needs a full policy backfill, worst risk/reward on a completed phase; **C** (parallel sibling FAC engine) — lowest risk but duplicates the grouping service + straight-line math and loses the unification the backlog wants.

---

## 3. Data model

### 3.1 `portfolio.contract_nature` — the source dimension

IFRS-17 requires direct, inward-reinsurance-issued, and reinsurance-held to be **separate portfolios** (§61 — reinsurance held is its own portfolio; inward reinsurance issued is separate from direct). So the source dimension lives at the **portfolio** level, and flows into groups + all downstream views for free.

- New column `portfolio.contract_nature VARCHAR(20) NOT NULL` ∈ `{DIRECT, FAC_INWARD, FAC_OUTWARD}`, default `DIRECT` (existing rows backfill to `DIRECT`).
- `ContractGroupingService.resolveOrCreatePortfolio(cobId)` → `resolveOrCreatePortfolio(cobId, nature)`; portfolio `code` becomes nature-prefixed (e.g. `DIR-<COB>`, `FIN-<COB>`, `FOU-<COB>`) so the existing `UNIQUE(code)` naturally segregates natures.
- Consequence: direct and FAC groups are automatically distinct; `contract_nature` reaches `group_of_contracts` via the portfolio join.

### 3.2 `contract_group_assignment` — polymorphic assignment (replaces `policy_group_assignment`)

| Column | Type | Note |
|---|---|---|
| `id` | UUID | BaseEntity |
| `contract_type` | VARCHAR(20) | `{POLICY, FAC_INWARD, FAC_OUTWARD}` |
| `contract_id` | UUID | the policy / fac-inward / fac-cover id |
| `group_id` | UUID | FK `group_of_contracts(id)` (kept — group is always local) |
| `assigned_at` | TIMESTAMPTZ | |

- `UNIQUE(contract_type, contract_id)` — generalised idempotency key.
- **Trade-off (the Approach-A cost):** the hard `policy_id → policies(id)` FK is dropped (a polymorphic `contract_id` can't carry one DB FK across three tables). Integrity moves to (a) the grouping service validating the contract row exists before assigning, and (b) `CHECK (contract_id IS NOT NULL)`.
- **Migration:** create `contract_group_assignment`, backfill every `policy_group_assignment` row as `(POLICY, policy_id, group_id, assigned_at, …)`, migrate code to the new table/entity (`PolicyGroupAssignment` → `ContractGroupAssignment`), then drop `policy_group_assignment` in the same migration. Direct grouping writes `(POLICY, policyId)` — behaviourally identical.

### 3.3 Ingestion — three listeners, one grouping method

`ContractGroupingService` keeps `onPolicyApproved` and adds `onFacInwardAccepted(RiFacInwardAcceptedEvent)` + `onFacPremiumCeded(FacPremiumCededEvent)`. Each maps to a common `assign(contractType, contractId, cobId, cohortDate, nature)`:

- Cohort year = coverage-start year (`coverFrom` for FAC, `policyStartDate` for direct).
- Outward's missing COB is resolved from its linked `policyId` at grouping time (mirrors how `SubledgerPostingService` resolves it today) — **no new column on `RiFacCover`**.
- Both FAC listeners are `@Transactional` (join the publisher's tx), matching the existing policy listener.

### 3.4 Measurement — pricing dispatch by `contract_type`

`LrcEngine` reads the assignment, then dispatches the premium/coverage read:

- `POLICY → FROM policies` (unchanged `loadPolicyPricing`).
- `FAC_INWARD → FROM ri_fac_inwards` (coverFrom/coverTo, grossPremium, netPremium, currencyCode).
- `FAC_OUTWARD → FROM ri_fac_covers` (coverFrom/coverTo, premiumCeded, netPremium, currencyCode).

The straight-line earning math (`daysActiveInPeriod / totalDays`, inclusive day count, `FRACTION_SCALE=12` → HALF_UP `MONEY_SCALE=2`) is **shared**; only the source query + the posting accounts differ per nature.

---

## 4. The IFRS-17 posting model (**sign-off-critical — §10**)

Premise: set up an LRC balance at recognition, release it straight-line over `coverFrom → coverTo`, mirroring the direct engine. Directions differ in balance-sheet side.

**Reference — direct policy (unchanged):** recognition `Dr 1310 receivable / Cr 2110 LRC`; each period `Dr 2110 LRC / Cr 4110 revenue`. LRC = net premium; broker commission is a separate immediate acquisition cost.

### 4.1 Inward FAC — insurance contract *issued* → liability (mirrors direct)

| Step | Posting | Note |
|---|---|---|
| Accept | `Dr 1330 receivable (net)` · `Dr 5240 commission exp` · `Cr 2210 inward LRC (gross)` | Only change vs today: `Cr 2210` replaces `Cr 4330`. Ceding commission expensed immediately (§59(a), matching `paa_config.acquisition_cashflow_method = EXPENSE_AS_INCURRED`). |
| Each period | `Dr 2210 inward LRC / Cr 4330 inward premium income` | Straight-line earned portion. By `coverTo`, `2210` fully released; total income = gross. |

### 4.2 Outward FAC — reinsurance *held* → asset

| Step | Posting | Note |
|---|---|---|
| Confirm | `Dr 1410 reinsurance-held LRC asset (net) / Cr 2310 RI premium payable (net)` | "net" = the `RiFacCover.netPremium` column (`premiumCeded − commission`). **§65 commission-netting (sign-off item):** ceding commission is netted into the asset (amortised), not booked as immediate `4300`/`4320` income — so the gross `premiumCeded` is never posted gross; only `netPremium` flows. Incidentally retires the `4300`-non-postable-parent path (see `fac-outward-4300-nonpostable-parent` backlog row). |
| Each period | `Dr 5210 outward RI premium exp / Cr 1410 reinsurance-held asset` | Amortise ceded premium to expense straight-line. Opposite sign to direct/inward. |

### 4.3 The three locked decisions

1. **Transition = modified-prospective.** New FAC on PAA from day one. For FAC still in-force at cutover, compute its inception-to-date LRC and book the **unearned remainder** as a one-time recognition in the first **OPEN** period — **closed periods are never reopened** (respects the period-lock invariant; `PeriodLockInterceptor`). Cheap here because the FAC book is only weeks old (`RiFacInward` shipped 2026-07-15). Pure-prospective (new-only) is the acceptable lighter fallback since annual FAC self-liquidates within ~12 months.
2. **Outward ceding commission = §65 net-into-asset.** The IFRS-17 treatment (immediate `4300` income was the pre-IFRS-17 shortcut). Unambiguous for the current system: `RiFacCover` carries only a **flat `commissionRate`/`commissionAmount`** with no profit-/claims-contingent field, so by construction all present commissions are non-contingent (§66 exception cannot arise until a profit-commission field is added).
3. **Scope = LRC (premium-earning) for both directions**, with two **in-scope non-negotiables**: (a) guard the source-agnostic `OnerousContractTestEngine` to **exclude `FAC_OUTWARD` groups** (applying the direct onerous test to a held asset is wrong); (b) the spec + disclosures state the reinsurance-held-asset LRC-only boundary. FAC LIC + §66A loss-recovery is Slice 7 in this same plan (§6).

### 4.4 Lifecycle events

- Inward `cancel` / outward `cancel` mid-coverage → **derecognise the remaining LRC/asset** (release the unearned balance). Today's FAC `cancel` posts no GL reversal at all — this closes that gap. Reversal rows honour `LockableByPeriod.isReversal()` so post-close corrections remain possible.
- Inward `renew` (new row) / `extend` (moves `coverTo`) → new/extended coverage window → new/extended LRC. The existing `extend` already posts a pro-rata delta; the PAA path recomputes the LRC over the extended window.

---

## 5. Downstream `contract_nature` surfacing

Given the source-agnostic downstream, this is mostly additive:

- `contract_nature` on `portfolio` → `group_of_contracts` (portfolio join) → **V38 `paa_movement_analysis` view recreation** carrying `contract_nature` → exposed by `MovementAnalysisService` (`GroupMovementEntry` gains the field) / `Ifrs17DisclosureEngine`.
- Module-11 CLOSURES reports (`Contract Groups Listing` / `PAA_GROUPS`, `LRC Roll-forward` / `LIC Roll-forward` / `ISR` / `IFRS17_MOVEMENT`) gain a `contract_nature` column + filter key in `ReportQueryBuilder`.
- FE `ContractGroupsPage` + `PaaMovementAnalysisPage` gain a nature column + filter; the hardcoded "created event-driven on every PolicyApprovedEvent" copy is corrected to reflect the three sources. (`@cia/api-client` `ContractGroupSummaryDtoSchema` / `MovementAnalysisDtoSchema` gain the field.)
- **Balance-sheet separation is automatic:** outward posts to `1410` (asset), inward to `2210` (liability), so `BalanceSheetEngine` (reads GL by account) presents reinsurance-held separately from issued *by account structure* — no special-casing.

---

## 6. Slice sequence

Each slice is independently shippable + reviewable, one stated goal, and keeps the ~20-22 PAA/GL/NAICOM/migration ITs green (direct path behaviourally identical throughout).

1. **Data model** — `contract_nature` on `portfolio`; `contract_group_assignment` table + backfill from `policy_group_assignment`; entity/repo generalisation (`PolicyGroupAssignment` → `ContractGroupAssignment`); drop the old table. No FAC behaviour yet.
2. **Grouping ingestion** — two new `ContractGroupingService` listeners + nature-aware portfolio resolution; FAC contracts get grouped (no measurement yet).
3. **LRC engine** — pricing dispatch by `contract_type`; inward liability postings (`2210`/`4330`); outward asset postings (`1410`/`5210`) with §65 commission-netting; FAC LRC roll-forward + reconciliation assertion.
4. **Lifecycle + transition** — cancel/renew/extend derecognition; the modified-prospective cutover catch-up (open period only).
5. **Downstream** — `contract_nature` through the V38 view + MovementAnalysis + Ifrs17Disclosure + the three reports.
6. **Frontend** — nature column/filter on the two closures pages + copy fixes.
7. **FAC LIC + §66A loss-recovery** — the reinsurance-recoverable / loss-recovery slice that completes the held-asset picture (closes the §4.3(3) boundary).

---

## 7. Testing

- **Regression floor:** every slice keeps the existing PAA/GL/NAICOM/migration ITs green; the direct path is byte-identical (dispatch only *adds* FAC branches).
- **New ITs:** FAC grouping → correct nature portfolio/group (idempotent on re-fire); FAC LRC roll-forward with reconciliation assertion (`opening + received − earned = closing`) + exact account/amount checks incl. §65 netting (inward `2210`/`4330`, outward `1410`/`5210`); cutover catch-up posts only into the open period; cancel derecognition releases the unearned balance; `OnerousContractTestEngine` excludes `FAC_OUTWARD`; migration seed guards for the new column/table.
- **Extend** `ChartOfAccountServiceIT` for the new account usage; extend the V38-view migration guard for `contract_nature`.
- IT harness pattern: `@SpringBootTest` PAA ITs under `cia-api/src/test/.../finance/paa/`, `em.flush()` at service-call boundaries (per the `@DataJpaTest`/`@Transactional` note in CLAUDE.md).

---

## 8. Migration / back-compat

- **New Flyway files only** (V76+): `contract_nature` column on `portfolio`; `contract_group_assignment` + backfill + drop of `policy_group_assignment`; V38-view recreation carrying `contract_nature`. Existing migrations never edited.
- FAC postings stay **inline-compound** in `SubledgerPostingService` (like today's `replayFacPremiumAccepted`/`replayFacPremiumCeded`), so **no new `posting_rule` rows** — the new inline methods post the LRC set-up + periodic release.
- `journal_entry` dedup unchanged (`UNIQUE(source_module, source_event_type, source_reference)`); FAC LRC JEs use module `"paa"`, event `"LRC_RECOGNITION"`, ref `periodId + ":" + groupId` (same idempotency shape as direct).

---

## 9. Out of scope (explicit)

- **FAC LIC / recoveries beyond Slice 7's §66A loss-recovery** — deeper reinsurance-recoverable modelling tied to the RI-recoveries module.
- **Discounting the LRC** — PAA §56 permits skipping it for ≤1-year coverage; the engine assumes annual FAC (matches the current direct `LrcEngine`, which also does not discount the LRC). Multi-year FAC discounting is out of scope.
- **Deferring inward acquisition commission (§59(b))** — we inherit `EXPENSE_AS_INCURRED` from `paa_config`; the DEFER_AND_AMORTISE path is unsupported and out of scope.
- **Profit-/claims-contingent ceding commission** — the data model has no such field; §66 handling is out of scope until one is added.

---

## 10. Accounting sign-off gate (REQUIRED before build)

> **SIGN-OFF OBTAINED 2026-08-08** — the accounting owner confirmed both gate items below (§65 commission-netting for outward, and the modified-prospective transition stance). Build execution is unblocked.

The build slices do **not** run until the accounting owner confirms the following. The inward liability-mirror (§4.1) is low-controversy; the two items most needing review:

1. **§65 commission-netting (outward, §4.2 / §4.3(2))** — confirm FAC ceding commissions are non-claims-contingent (true for the current flat-commission data model), so netting into the reinsurance-held asset is correct rather than immediate income.
2. **Transition stance (§4.3(1))** — confirm modified-prospective (in-force LRC catch-up into the open period, closed periods untouched) vs. the pure-prospective fallback.

Documented boundaries to acknowledge (not errors): the LRC-only reinsurance-held picture until Slice 7 (§4.3(3)), and inward commission expensed immediately per §59(a) (§4.1).

---

## 11. Backlog reconciliation

- `fac-ifrs17-paa-workstream` (P2) — **stays open**; lands when the workstream ships.
- `fac-outward-4300-nonpostable-parent` (P3) — **subsumed by Slice 3** (outward stops crediting the non-postable `4300` parent; the §65 netting routes commission into `1410` instead). The generic "posting account must be a postable leaf" guard on `JournalEntryService.post` remains a standalone item on that row if not folded into Slice 3.
