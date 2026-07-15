# Inward Facultative Reinsurance (v1) — Design

**Module:** 6 — Reinsurance (`cia-reinsurance`) + `cia-finance` + `cia-documents` + `cia-frontend`
**Status:** ✅ IMPLEMENTED (shipped 2026-07-15 on `feat/inward-fac-reinsurance`, 12-task SDD build — see `cia-log.md` 2026-07-15 entry). Plan: `docs/superpowers/plans/2026-07-07-inward-fac-reinsurance.md`. Design approved via brainstorm 2026-07-07.
**Context:** The FAC → Inward tab was removed from a fabricated mock + 404-ing forms and replaced with a "coming soon" placeholder (PR #42, commit `48b85f3`). This spec builds the real backend + rebuilds the frontend against it. Backlog row `inward-fac-backend-build` (P1) tracks it.

---

## Goal

Deliver the **complete inward-facultative-reinsurance lifecycle end-to-end**: an underwriter accepts a share of another insurer's risk, the system generates a guaranty document, records the accepted premium as a real receivable in Finance (with a balanced GL posting), and supports renew / extend / cancel — all through a rebuilt, fully-wired frontend (no mocks).

**Inward FAC, defined:** we (the insurer) accept a proportional share of a risk that *another primary insurer* (the ceding company) underwrote. They cede premium to us; we allow them a commission; the net premium is a receivable we are owed. This is the mirror of outward FAC (`RiFacCover`), where we cede *our* policy's risk to a reinsurer and owe *them* a net premium (payable).

---

## Scope

**In v1:**
- New `ri_fac_inwards` aggregate (table + entity + service + controller + DTOs).
- Lifecycle: **create / renew / extend / cancel**.
- Server-side **guaranty document** generation (PDFBox, via `cia-documents`).
- **Full Finance integration**: accepted premium → `DebitNote` receivable + balanced GL posting.
- Rebuilt frontend forms + live inward tab.
- Testcontainers ITs + frontend guards/tests.

**Deferred (tracked follow-ups, per brainstorm Q4):**
- `inward-fac-renewal-notices` — Temporal scheduled reminder sequence (needs covers to exist first).
- `inward-fac-debit-note-analysis` — reports-module report (needs the inward DebitNotes to exist first).
- Full IFRS-17 PAA/LRC measurement of inward FAC (see §3 accounting decision) — aligned with a future Module 12 workstream; outward FAC is likewise not PAA-integrated today.

---

## Architectural decisions (from brainstorm)

| # | Decision | Rationale |
|---|---|---|
| Q1 | **New `ri_fac_inwards` table + `RiFacInward` entity** (not a `direction` flag on `RiFacCover`) | Inward differs from outward on four axes — anchoring entity (external risk vs our policy → no `policy_id`), counterparty (ceding insurer vs reinsurer), status lifecycle, and financial direction (receivable vs payable). A shared table would force `policy_id` nullable, overload the counterparty column, and branch every outward method/IT on direction. |
| Q2 | **Full Finance parity** — event → `DebitNote` receivable + GL posting | Makes the feature genuinely functional (accepted premium lands in Receivables + the ledger), and reuses the outward event→finance blueprint almost line-for-line. |
| Q3 | **Ceding company = Insurers master data**, stored as `ceding_company_id` + `ceding_company_name` snapshot | Matches the deleted `AddInwardFACSheet` (which fetched `/api/v1/setup/insurance-companies`); domain-correct (a primary insurer cedes to us); keeps referential integrity for the deferred bordereaux/debit-note reports. |
| Q4 | **Focused v1** — create/renew/extend/cancel + guaranty doc + finance + FE; defer renewal-notices + debit-note-analysis | The two extras are independent add-ons that layer cleanly on top once covers + notes exist. |
| §4 | **Real server-side guaranty document** (not a stub) | Honest delivery of the stated scope; consistent with policy/endorsement/claim-DV PDF generation. (Outward's offer-slip/credit-note PDFs are a known backend stub gap — nothing to copy, so this is new work.) |

---

## §1 — Data model (`ri_fac_inwards`, migration V75)

New table. Column *style* mirrors `ri_fac_covers`; semantics are inward. All money `NUMERIC(18,2)`, rates `NUMERIC(10,6)`/`NUMERIC(7,4)` matching the outward precision.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | `BaseEntity` |
| `created_at`, `created_by`, `updated_at`, `deleted_at` | | `BaseEntity` (soft delete) |
| `fac_inward_reference` | VARCHAR(50) UNIQUE NOT NULL | `FAC-IN-{YYYY}-{NNNN}` via `RiFacInwardCounter` + `RiNumberService` |
| `ceding_company_id` | UUID NOT NULL | insurers master data |
| `ceding_company_name` | VARCHAR(200) NOT NULL | snapshot |
| `class_of_business_id` | UUID NOT NULL | the accepted risk's class |
| `class_of_business_name` | VARCHAR(200) NOT NULL | snapshot |
| `risk_description` | TEXT | what we're accepting a share of |
| `sum_insured` | NUMERIC(18,2) NOT NULL | total SI of the underlying risk |
| `our_share_pct` | NUMERIC(7,4) NOT NULL | % share accepted |
| `accepted_sum_insured` | NUMERIC(18,2) NOT NULL | `sum_insured × our_share_pct / 100` |
| `premium_rate` | NUMERIC(10,6) NOT NULL | |
| `gross_premium` | NUMERIC(18,2) NOT NULL | our inward premium before commission |
| `commission_rate` | NUMERIC(7,4) NOT NULL DEFAULT 0 | commission we allow the ceding company |
| `commission_amount` | NUMERIC(18,2) NOT NULL DEFAULT 0 | `gross_premium × commission_rate / 100` |
| `net_premium` | NUMERIC(18,2) NOT NULL | `gross_premium − commission_amount` — the receivable |
| `currency_code` | CHAR(3) NOT NULL DEFAULT 'NGN' | |
| `cover_from` | DATE NOT NULL | |
| `cover_to` | DATE NOT NULL | |
| `status` | VARCHAR(30) NOT NULL | `RiFacInwardStatus { ACTIVE, RENEWED, EXPIRED, CANCELLED }` |
| `renewed_from_id` | UUID NULL (self-FK) | renewal chain |
| `guaranty_document_path` | VARCHAR NULL | MinIO path once generated |
| `cancellation_reason` | TEXT NULL | |

**Indexes:** `ceding_company_id`, `class_of_business_id`, `status`, `cover_from`, and the unique `fac_inward_reference`.

**V75 also adds two COA rows** (the inward income/expense side is absent from V32 — see §3):
- `4330 Inward reinsurance premium income` (INCOME, parent `4300`).
- `5240 Inward reinsurance commission expense` (EXPENSE, parent `5200`).
(Exact codes may shift to the next free code in each block; the `1330` inward receivable and `2210/2220` inward LRC/LIC accounts already exist and are unchanged.)

**Premium math** (computed in the service, validated by unit test):
```
accepted_sum_insured = sum_insured × our_share_pct / 100
gross_premium        = accepted_sum_insured × premium_rate / 100
commission_amount    = gross_premium × commission_rate / 100      (HALF_UP, scale 2)
net_premium          = gross_premium − commission_amount
```

---

## §2 — Backend components (`cia-reinsurance`)

Mirrors the outward file layout so the module stays internally consistent:

- `RiFacInward` (entity, `extends BaseEntity implements LockableByPeriod`; `getLockDate()` returns `cover_from` — period-lock parity with outward's booking-date anchor).
- `RiFacInwardStatus` (enum).
- `RiFacInwardRepository` (`JpaSpecificationExecutor<RiFacInward>` + a `RiFacInwardSpecs` factory for the list filters: status, cedingCompany, class, date range).
- `RiFacInwardCounter` + `RiFacInwardCounterRepository` (mirror `RiFacCounter`); reference minted via the existing `RiNumberService` (add an `nextInwardFacReference()`).
- `RiFacInwardService` (all business logic; publishes events; calls `DocumentGenerationService`).
- `RiFacInwardController` at **`/api/v1/ri/fac-inwards`**.
- DTOs: `CreateFacInwardRequest`, `RenewFacInwardRequest`, `ExtendFacInwardRequest`, `CancelFacInwardRequest`, `FacInwardResponse`.

**Module dependency change:** add `cia-documents` to `cia-reinsurance`'s pom (currently absent) for guaranty-doc generation — consistent with cia-policy/endorsement/claims depending on cia-documents.

### Lifecycle & endpoints

Inward covers are created **live as ACTIVE** (matching the deleted FE, which had no PENDING/offer step — an inward acceptance is our decision, not an offer we wait on).

| Method | Endpoint | Authority | Behaviour |
|---|---|---|---|
| `GET` | `/api/v1/ri/fac-inwards` | `reinsurance:view` | Filterable list (`ApiResponse<List<FacInwardResponse>>` + `ApiMeta`, `@PageableDefault(size=2000)` per the internal-list convention). |
| `GET` | `/api/v1/ri/fac-inwards/{id}` | `reinsurance:view` | Single. |
| `POST` | `/api/v1/ri/fac-inwards` | `reinsurance:create` | Create → status **ACTIVE**, compute premiums, mint reference, generate guaranty doc, publish `RiFacInwardAcceptedEvent`. |
| `POST` | `/api/v1/ri/fac-inwards/{id}/renew` | `reinsurance:create` | New `RiFacInward` for the next term (`renewed_from_id` = source), source → **RENEWED**; new cover fires its own accepted event (new receivable + its own guaranty doc). |
| `POST` | `/api/v1/ri/fac-inwards/{id}/extend` | `reinsurance:update` | Lengthen `cover_to`; **additional pro-rata premium** `net_premium / originalCoverDays × extraDays` (the endorsement pro-rata idiom); publishes an incremental accepted event for the delta. |
| `POST` | `/api/v1/ri/fac-inwards/{id}/cancel` | `reinsurance:update` | Reason **required** → status **CANCELLED**; `cancellation_reason` stored + audited. Any unpaid receivable is voidable via the existing Finance reversal flow (no auto-reversal in v1). |
| `GET` | `/api/v1/ri/fac-inwards/{id}/document` | `reinsurance:view` | Stream the guaranty PDF from MinIO (gated on `guaranty_document_path != null`). |

State transitions: only `ACTIVE` covers may renew/extend/cancel; a cover past `cover_to` is `EXPIRED` (derived on read or by the existing period sweep — v1 derives on read, no scheduler). Guard illegal transitions with a clear 422.

---

## §3 — Finance integration (event → DebitNote + GL)

New `common.event.RiFacInwardAcceptedEvent`:
```java
public record RiFacInwardAcceptedEvent(
    UUID facInwardId, String facInwardReference,
    UUID cedingCompanyId, String cedingCompanyName,
    UUID classOfBusinessId,
    BigDecimal grossPremium, BigDecimal commissionAmount, BigDecimal netPremium,
    String currencyCode) {}
```
Published by `RiFacInwardService` on create / renew / extend (extend carries the delta amounts).

New `RiFacInwardAcceptedEventListener` in `cia-finance` (mirrors `FacPremiumCededEventListener`) → a **new `DebitNoteService.createForInwardFac(...)`** that creates a `DebitNote` with `entityType = REINSURANCE`, `entityId = facInwardId`, debtor = ceding company, amount = `net_premium`, description `"Inward FAC premium — {reference} ({cedingCompanyName})"`. This flows into **Finance → Receivables → Receipt**, identical to a policy premium.

**GL posting — simple style, mirroring outward FAC** (hardcoded compound posting in `SubledgerPostingService`, keyed on a new `EVENT_FAC_PREMIUM_ACCEPTED`; the 1-Dr-1-Cr `posting_rule` shape can't express a 3-line entry, so — exactly like outward's `FAC_PREMIUM_CEDED` — it lives in code, not a seed row):
```
Dr 1330  Premium receivable - Coinsurer (inward)   = net_premium        (existing account)
Dr 5240  Inward reinsurance commission expense     = commission_amount  (new in V75)
Cr 4330  Inward reinsurance premium income         = gross_premium      (new in V75)
```
Balances by construction: `net_premium + commission_amount = gross_premium`. A reconciliation IT asserts `Σdebit = Σcredit` for the JE (mirroring `FacPremiumCededEventContractTest`).

**Accounting-model decision — CONFIRMED 2026-07-07.** v1 uses this **simple income posting**, matching how outward FAC actually posts today. The V32 chart also seeds inward LRC/LIC liability accounts (`2210`/`2220`) for a full **IFRS-17 PAA/LRC** treatment (premium → LRC liability, recognized over coverage), but that path is **out of scope for v1** for two grounded reasons: (a) the existing PAA engine is hard-coupled to direct policies (`ContractGroupingService` listens for `PolicyApprovedEvent`; `PolicyGroupAssignment` is `UNIQUE(policy_id)`; `LrcEngine` reads `FROM policies` and posts to the *direct* accounts `2110`/`4110`), so facultative reinsurance cannot reuse it; and (b) the system's own **outward** FAC is likewise not PAA-integrated, so the simple posting keeps inward *consistent* with its outward twin. The principled fix brings **inward + outward FAC onto PAA together** (never inward-only) — tracked as backlog `fac-ifrs17-paa-workstream` (P2), which records the full cross-module blast radius (Module 12 core, NAICOM disclosure relays, CLOSURES reports, Phase-5 frontend).

---

## §4 — Guaranty document (server-side, `cia-documents`)

Add `generateInwardFacGuaranty(InwardFacGuarantyContext ctx)` to `DocumentGenerationService` + a PDFBox HTML template (`document-templates/inward-fac-guaranty-default.html`), following the exact policy/endorsement/claim-DV pattern (`resolveAndRender` → `HtmlToPdfConverter` → store to MinIO). The doc states: our reference, ceding company, class + risk description, sum insured + our share, gross/commission/net premium, cover period, and an acceptance/guaranty clause. `RiFacInwardService` calls it on create/renew, stores the returned path in `guaranty_document_path`. Generation must not throw — on failure, log WARN and leave the path null (the `GET /document` endpoint gates on non-null), so a doc failure never rolls back the acceptance/receivable. Uses the NotoSans-embedded converter so `₦` renders.

---

## §5 — Frontend rebuild (`cia-frontend`)

- `@cia/api-client`: `FacInwardDto` + zod schema (`FacInwardDtoSchema`) mirroring `FacInwardResponse` (drift-guard covered); request types.
- Rebuild `AddInwardFACSheet` (create form: ceding company [live `/api/v1/setup/insurance-companies`], class, risk description, sum insured, share %, rate, commission %, period; live premium preview) → `validatedPost('/api/v1/ri/fac-inwards', ...)`.
- Rebuild `InwardFACActionSheet` (renew/extend modes) → the real renew/extend endpoints; extend shows the indicative pro-rata delta.
- `FACTab`: replace the inward "coming soon" `EmptyState` with a live `DataTable` (real `useQuery` on `/api/v1/ri/fac-inwards`) + row actions (Renew / Extend / Cancel-with-reason via `ConfirmDeleteDialog`/reason) + a working **Download guaranty** button (gated on `guarantyDocumentPath != null`, blob fetch). Restore the tab count. All mutations real; no mocks.

---

## §6 — Testing

**Backend (Testcontainers ITs, module convention):**
- Create → status ACTIVE + reference minted + guaranty path set + `DebitNote` (REINSURANCE, net_premium) appears in Receivables + GL JE balanced (`Σdr = Σcr`, and lines hit 1330/5240/4330).
- Renew → new cover linked via `renewed_from_id`, source → RENEWED, second receivable created.
- Extend → `cover_to` moved, pro-rata delta DebitNote created.
- Cancel → status CANCELLED + reason persisted + audited; illegal transition (cancel a CANCELLED) → 422.
- Period-lock: a booking into a hard-closed period is rejected 423 (`LockableByPeriod` parity).
- Reference sequence: two creates in the same year → `...-0001`, `...-0002`.
- Premium math unit test (pure): the four formulas incl. HALF_UP rounding.

**Frontend:** `pnpm --filter @cia/back-office build` · `check-api-wiring.sh` · `check-dto-drift.mjs` · vitest for the new create/renew hooks.

---

## File inventory

**Backend — new (`cia-reinsurance`):** `RiFacInward.java`, `RiFacInwardStatus.java`, `RiFacInwardRepository.java`, `RiFacInwardSpecs.java`, `RiFacInwardCounter.java`, `RiFacInwardCounterRepository.java`, `RiFacInwardService.java`, `RiFacInwardController.java`, `dto/{CreateFacInwardRequest,RenewFacInwardRequest,ExtendFacInwardRequest,CancelFacInwardRequest,FacInwardResponse}.java`.
**Backend — new (`cia-common`):** `event/RiFacInwardAcceptedEvent.java`.
**Backend — new (`cia-finance`):** `RiFacInwardAcceptedEventListener.java`.
**Backend — new (`cia-documents`):** `InwardFacGuarantyContext` + `document-templates/inward-fac-guaranty-default.html`.
**Backend — modified:** `RiNumberService` (+`nextInwardFacReference`), `DocumentGenerationService`/`Impl` (+guaranty method), `DebitNoteService` (+`createForInwardFac`), `SubledgerPostingService` (+`EVENT_FAC_PREMIUM_ACCEPTED` compound posting), `cia-reinsurance/pom.xml` (+`cia-documents` dep).
**Migration:** `V75__inward_fac.sql` (table + indexes + 2 COA rows).
**Frontend — modified:** `packages/api-client/src/modules/reinsurance.ts` (+`FacInwardDto`/schema/requests), `apps/back-office/.../fac/FACTab.tsx`, **re-created** `AddInwardFACSheet.tsx` + `InwardFACActionSheet.tsx`.

---

## Out of scope (explicit)

Renewal-notice Temporal workflow · inward debit-note-analysis report · IFRS-17 PAA/LRC measurement of inward FAC · retrocession-from-reinsurer counterparties (insurers only) · a PENDING/approval workflow (created live as ACTIVE, mirroring the outward confirm-less inward model).
