# S4a-2 — validatedGet sweep: core transaction lists Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Migrate the four core transaction list pages (Policy, Quotation, Endorsements, Customers) off raw `apiClient.get(...).then(r => r.data.data)` onto the envelope-drift-immune `validatedGet(url, z.array(XSchema))`, authoring the zod schemas each needs.

**Architecture:** Pure frontend, mirroring the S4a-1 Setup sweep (merged PR #55). Each page's list fetch becomes `validatedGet` against a zod schema that is the single source of truth for its row DTO (`export const XSchema = z.object({...})` + `export type X = z.infer<typeof XSchema>`). `check-dto-drift` Pattern 3 keeps drift coverage by resolving the `z.infer` alias back to the schema body.

**Tech Stack:** React + TanStack Query + zod + `@cia/api-client` (`validatedGet`).

## Global Constraints

- **No backend changes.** All four list endpoints already exist and return the shapes below (verified against controllers): `/api/v1/policies` → `List<PolicySummaryResponse>`; `/api/v1/quotes` → `List<QuoteSummaryResponse>`; `/api/v1/endorsements` → `List<EndorsementResponse>` (full, incl. `risks[]`); `/api/v1/customers` → `List<CustomerSummaryResponse>`.
- **Established pattern:** `queryFn: () => validatedGet('/api/v1/...', z.array(XSchema))`. `validatedGet` unwraps the `{data,meta}` envelope and validates `data` against the schema.
- **Enum fields** are inlined as `z.enum([...])` (mirroring the existing FE `export type X` alias values verbatim), exactly as S4a-1 did for Adjuster/Agent/Surveyor `type`. The existing `export type XStatus`/`XType` string-union aliases stay untouched (structurally identical to the `z.infer`).
- **`apiClient` import:** drop it from a page **only** if the list get was its sole use. QuotationListPage (convert-to-policy `.post`) and CustomersListPage (blacklist `.post`) keep `apiClient`.
- **Guards (all must pass):** `pnpm --filter @cia/back-office build` · `node cia-frontend/scripts/check-dto-drift.mjs` · `bash cia-frontend/scripts/check-api-wiring.sh`. Plus the back-office Vitest suite for T5.
- **DTO-drift mapping** (default: strip `Dto`, append `Response`): `PolicySummaryDto`→`PolicySummaryResponse`, `QuoteSummaryDto`→`QuoteSummaryResponse`, `EndorsementDto`→`EndorsementResponse`, `EndorsementRiskDto`→`EndorsementRiskResponse`, `CustomerSummaryDto`→`CustomerSummaryResponse` (new). Mirror each backend record's field set exactly.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

- **Modify** `cia-frontend/packages/api-client/src/modules/quotation.ts` — `QuoteSummaryDto` interface → schema + `z.infer`.
- **Modify** `cia-frontend/packages/api-client/src/modules/endorsement.ts` — `EndorsementRiskDto` + `EndorsementDto` interfaces → schemas + `z.infer`.
- **Modify** `cia-frontend/packages/api-client/src/modules/customer.ts` — **add** `CustomerSummaryDto` + `CustomerSummaryDtoSchema` (new; mirrors `CustomerSummaryResponse`).
- **Modify** the four pages: `policy/pages/PolicyListPage.tsx`, `quotation/pages/QuotationListPage.tsx`, `endorsements/pages/EndorsementsListPage.tsx`, `customers/pages/CustomersListPage.tsx`.
- **Create** `cia-frontend/apps/back-office/src/modules/<module>/core-lists-envelope-parse.test.ts` (T5 regression test; final location decided in T5).
- **No change** to `policy.ts` (`PolicySummaryDtoSchema` already exists).

---

### Task 1: Policy — pure swap (schema already exists)

**Files:** Modify `apps/back-office/src/modules/policy/pages/PolicyListPage.tsx`.
**Interfaces:** Consumes existing `PolicySummaryDtoSchema` + `validatedGet` from `@cia/api-client`.

- [ ] **Step 1:** In `PolicyListPage.tsx`, replace the fetch block:

```ts
// before (line ~34-38)
    queryFn: async () => {
      const res = await apiClient.get<{ data: PolicySummaryDto[] }>('/api/v1/policies');
      return res.data.data;
    },
// after
    queryFn: () => validatedGet('/api/v1/policies', z.array(PolicySummaryDtoSchema)),
```

- [ ] **Step 2:** Fix imports. `apiClient` was the only use (verified: 1 `apiClient.` in the file), so drop it; add `validatedGet`, `PolicySummaryDtoSchema`, and `import { z } from 'zod'`:

```ts
import { z } from 'zod';
import { validatedGet, PolicySummaryDtoSchema, type PolicySummaryDto } from '@cia/api-client';
```

(Keep `type PolicySummaryDto` — the `ColumnDef<PolicySummaryDto>` / `useQuery<PolicySummaryDto[]>` generics still reference it.)

- [ ] **Step 3:** `pnpm --filter @cia/back-office build` → pass; `bash cia-frontend/scripts/check-api-wiring.sh` → pass.
- [ ] **Step 4:** Commit: `refactor(policy): PolicyListPage validatedGet (schema already existed)`.

---

### Task 2: Quotation — author `QuoteSummaryDtoSchema`, swap list get

**Files:** Modify `packages/api-client/src/modules/quotation.ts`; `apps/back-office/src/modules/quotation/pages/QuotationListPage.tsx`.
**Interfaces:** Produces `QuoteSummaryDtoSchema` + `QuoteSummaryDto` (`z.infer`).

- [ ] **Step 1:** In `quotation.ts`, convert the `QuoteSummaryDto` interface (currently at ~line 134) to a schema. `QuoteStatus` inlined; `businessType` reuses the exported `BusinessTypeSchema` from `./policy` (add the import if absent):

```ts
import { BusinessTypeSchema } from './policy';

export const QuoteSummaryDtoSchema = z.object({
  id:                  z.string(),
  quoteNumber:         z.string(),
  status:              z.enum(['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CONVERTED', 'EXPIRED']),
  customerId:          z.string(),
  customerName:        z.string(),
  productName:         z.string(),
  classOfBusinessName: z.string(),
  brokerName:          z.string().nullable().optional(),
  agentName:           z.string().nullable().optional(),
  businessType:        BusinessTypeSchema,
  policyStartDate:     z.string(),
  policyEndDate:       z.string(),
  totalSumInsured:     z.number(),
  netPremium:          z.number(),
  expiresAt:           z.string().nullable().optional(),
  createdAt:           z.string(),
});
export type QuoteSummaryDto = z.infer<typeof QuoteSummaryDtoSchema>;
```

(If `z` is not already imported in `quotation.ts`, add `import { z } from 'zod';`. The `export type QuoteStatus` alias stays — it is used elsewhere.)

- [ ] **Step 2:** In `QuotationListPage.tsx`, swap only the **list** fetch (the `.post` convert-to-policy stays on `apiClient`, so keep the `apiClient` import):

```ts
// before (line ~31-34)
    queryFn: async () => {
      const res = await apiClient.get<{ data: QuoteSummaryDto[] }>('/api/v1/quotes');
      return res.data.data;
    },
// after
    queryFn: () => validatedGet('/api/v1/quotes', z.array(QuoteSummaryDtoSchema)),
```

Add to imports: `validatedGet`, `QuoteSummaryDtoSchema`, and `import { z } from 'zod'` (keep `apiClient` + `type QuoteSummaryDto`).

- [ ] **Step 3:** `node cia-frontend/scripts/check-dto-drift.mjs` → `✓ No DTO drift` (QuoteSummaryDto↔QuoteSummaryResponse via Pattern 3). `pnpm --filter @cia/back-office build` + `check-api-wiring` → pass.
- [ ] **Step 4:** Commit: `refactor(quotation): QuoteSummaryDtoSchema + QuotationListPage validatedGet`.

---

### Task 3: Endorsement — author `EndorsementRiskDtoSchema` + `EndorsementDtoSchema`, swap list get

**Files:** Modify `packages/api-client/src/modules/endorsement.ts`; `apps/back-office/src/modules/endorsements/pages/EndorsementsListPage.tsx`.
**Interfaces:** Produces `EndorsementRiskDtoSchema`, `EndorsementDtoSchema` + `z.infer` types. `EndorsementDtoSchema.risks` references `EndorsementRiskDtoSchema` (declare the risk schema first).

- [ ] **Step 1:** In `endorsement.ts`, convert `EndorsementRiskDto` (line ~30) first:

```ts
export const EndorsementRiskDtoSchema = z.object({
  id:               z.string(),
  description:      z.string(),
  sumInsured:       z.number(),
  premium:          z.number(),
  sectionId:        z.string().nullable(),
  sectionName:      z.string().nullable(),
  riskDetails:      z.record(z.string(), z.unknown()).nullable(),
  vehicleRegNumber: z.string().nullable(),
  orderNo:          z.number(),
});
export type EndorsementRiskDto = z.infer<typeof EndorsementRiskDtoSchema>;
```

- [ ] **Step 2:** Convert `EndorsementDto` (line ~46). `EndorsementStatus` + `EndorsementType` inlined (values copied verbatim from the existing aliases); note these interface fields are non-optional `| null`, so use `.nullable()` (NOT `.nullable().optional()`):

```ts
export const EndorsementDtoSchema = z.object({
  id:                  z.string(),
  endorsementNumber:   z.string(),
  status:              z.enum(['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED']),
  endorsementType:     z.enum(['RENEWAL', 'EXTENSION', 'CANCELLATION', 'REVERSAL', 'REDUCTION', 'CHANGE_PERIOD', 'INCREASE_SI', 'DECREASE_SI', 'ADD_ITEMS', 'DELETE_ITEMS']),
  policyId:            z.string(),
  policyNumber:        z.string(),
  customerId:          z.string(),
  customerName:        z.string(),
  productName:         z.string(),
  classOfBusinessName: z.string(),
  brokerId:            z.string().nullable(),
  brokerName:          z.string().nullable(),
  effectiveDate:       z.string(),
  policyEndDate:       z.string(),
  remainingDays:       z.number(),
  oldSumInsured:       z.number(),
  newSumInsured:       z.number(),
  oldNetPremium:       z.number(),
  newNetPremium:       z.number(),
  premiumAdjustment:   z.number(),
  currencyCode:        z.string(),
  description:         z.string().nullable(),
  notes:               z.string().nullable(),
  approvedBy:          z.string().nullable(),
  approvedAt:          z.string().nullable(),
  rejectedBy:          z.string().nullable(),
  rejectedAt:          z.string().nullable(),
  rejectionReason:     z.string().nullable(),
  createdAt:           z.string(),
  risks:               z.array(EndorsementRiskDtoSchema),
});
export type EndorsementDto = z.infer<typeof EndorsementDtoSchema>;
```

> **Verification note (do this in Step 4, before committing):** confirm `EndorsementType`'s alias in `endorsement.ts` has exactly these 10 members (read lines 4–14). If the alias lists more/fewer, match it — the `z.enum` must equal the alias. The build fails loudly if `z.infer` no longer satisfies a consumer, and `check-dto-drift` fails if a field name diverges from `EndorsementResponse`.

- [ ] **Step 3:** In `EndorsementsListPage.tsx`, swap the fetch (verified: 1 `apiClient.` use → drop the import):

```ts
// before (line ~25-28)
    queryFn: async () => {
      const res = await apiClient.get<{ data: EndorsementDto[] }>('/api/v1/endorsements');
      return res.data.data;
    },
// after
    queryFn: () => validatedGet('/api/v1/endorsements', z.array(EndorsementDtoSchema)),
```

Imports: drop `apiClient`; add `validatedGet`, `EndorsementDtoSchema`, `import { z } from 'zod'` (keep `type EndorsementDto`).

- [ ] **Step 4:** `node cia-frontend/scripts/check-dto-drift.mjs` → pass (EndorsementDto↔EndorsementResponse, EndorsementRiskDto↔EndorsementRiskResponse). `pnpm --filter @cia/back-office build` + `check-api-wiring` → pass.
- [ ] **Step 5:** Commit: `refactor(endorsement): Endorsement(+Risk)DtoSchema + list validatedGet`.

---

### Task 4: Customer — new `CustomerSummaryDto`, rebind list page (fixes latent blank-name bug)

**Why this is more than a swap:** `/api/v1/customers` returns `List<CustomerSummaryResponse>` (lean), but `CustomersListPage` currently binds the **full** `CustomerDto` and labels rows via `customerLabel(row)`, which reads `firstName`/`lastName`/`companyName` — fields the summary response does **not** carry. So names render blank today. The correct migration binds a `CustomerSummaryDto` (which has `displayName`) and labels via `displayName`.

**Files:** Modify `packages/api-client/src/modules/customer.ts`; `apps/back-office/src/modules/customers/pages/CustomersListPage.tsx`.
**Interfaces:** Produces `CustomerSummaryDtoSchema` + `CustomerSummaryDto`. Mirrors `CustomerSummaryResponse` exactly: `{id, customerNumber, customerType, customerStatus, kycStatus, displayName, email?, phone?, relationshipManagerId?, relationshipManagerName?, createdAt}`.

- [ ] **Step 1:** In `customer.ts`, add the summary schema. Reuse existing FE enum aliases' values (`CustomerStatus` = `ACTIVE|INACTIVE|BLACKLISTED`; `customerType` mirrors the backend `CustomerType` = `INDIVIDUAL|CORPORATE`; `kycStatus` mirrors `KycStatus`). **Before writing, read `customer.ts` for the exact `CustomerType`/`KycStatus` values** (the `CustomerDto` interface already declares them — copy those unions verbatim into the `z.enum`s). Template (fill the two enums from the interface):

```ts
export const CustomerSummaryDtoSchema = z.object({
  id:                      z.string(),
  customerNumber:          z.string(),
  customerType:            z.enum(['INDIVIDUAL', 'CORPORATE']),
  customerStatus:          z.enum(['ACTIVE', 'INACTIVE', 'BLACKLISTED']),
  kycStatus:               z.enum([/* copy KycStatus alias values from CustomerDto */]),
  displayName:             z.string(),
  email:                   z.string().nullable().optional(),
  phone:                   z.string().nullable().optional(),
  relationshipManagerId:   z.string().nullable().optional(),
  relationshipManagerName: z.string().nullable().optional(),
  createdAt:               z.string(),
});
export type CustomerSummaryDto = z.infer<typeof CustomerSummaryDtoSchema>;
```

> `email`/`phone`/`relationshipManager*` are `.nullable().optional()` because the backend record leaves them nullable (a corporate customer may have no personal phone; RM is an optional FK). If `check-dto-drift` flags a mismatch, align the nullability to `CustomerSummaryResponse` (Instant/String reference types are nullable; primitives are not — but this record has none).

- [ ] **Step 2:** Rebind `CustomersListPage.tsx` to `CustomerSummaryDto`:
  - `const columns: ColumnDef<CustomerSummaryDto>[]` and `useQuery<CustomerSummaryDto[]>`.
  - Fetch → `queryFn: () => validatedGet('/api/v1/customers', z.array(CustomerSummaryDtoSchema))`.
  - Replace both `customerLabel(row.original)` / `customerLabel(row)` (the accessorFn at ~line 53 and the cell at ~line 60) with `row.original.displayName` / `row.displayName` respectively; and the blacklist dialog `entityName={customerLabel(blacklistTarget)}` (~line 138) with `entityName={blacklistTarget.displayName}`.
  - The `getValue() as CustomerDto['kycStatus']` / `CustomerDto['customerStatus']` casts (~lines 73, 78) → `CustomerSummaryDto['kycStatus']` / `CustomerSummaryDto['customerStatus']`.
  - Imports: drop `customerLabel` and `type CustomerDto`; **keep `apiClient`** (blacklist `.post` at ~line 38); add `validatedGet`, `CustomerSummaryDtoSchema`, `type CustomerSummaryDto`, `import { z } from 'zod'`.

- [ ] **Step 3:** `node cia-frontend/scripts/check-dto-drift.mjs` → `✓` (new `CustomerSummaryDto`↔`CustomerSummaryResponse` must match; if it flags `firstName`/etc as backendOnly, that's a *different* pair — ignore, it's `CustomerDto`↔`CustomerResponse`, unchanged). `pnpm --filter @cia/back-office build` + `check-api-wiring` → pass.
- [ ] **Step 4:** Commit: `fix(customers): bind CustomerSummaryDto on list (displayName) + validatedGet`.

  Commit body notes the latent bug fixed: the list showed blank names because `customerLabel` read full-DTO fields absent from the summary response.

---

### Task 5: Envelope-parse regression test

**Files:** Create `apps/back-office/src/modules/customers/core-lists-envelope-parse.test.ts` (co-locate with the highest-risk page; pure schema test, no rendering). Mirror `setup/setup-envelope-parse.test.ts` (no `@cia/ui` mock needed — this imports schemas directly).

- [ ] **Step 1: Write the test** — assert each schema parses a valid flat row and rejects a Page-shaped payload; assert the endorsement nested `risks` parse; assert `CustomerSummaryDtoSchema` requires `displayName`:

```ts
import { describe, it, expect } from 'vitest';
import { z } from 'zod';
import {
  PolicySummaryDtoSchema, QuoteSummaryDtoSchema,
  EndorsementDtoSchema, CustomerSummaryDtoSchema,
} from '@cia/api-client';

describe('core-list schemas — envelope + shape guards', () => {
  it('CustomerSummaryDtoSchema requires displayName and parses a lean row', () => {
    const row = {
      id: 'c1', customerNumber: 'CUS-1', customerType: 'INDIVIDUAL',
      customerStatus: 'ACTIVE', kycStatus: 'PENDING', displayName: 'Ada Lovelace',
      email: null, phone: null, relationshipManagerId: null,
      relationshipManagerName: null, createdAt: '2026-01-01T00:00:00Z',
    };
    expect(CustomerSummaryDtoSchema.parse(row).displayName).toBe('Ada Lovelace');
    const { displayName, ...noName } = row;
    expect(CustomerSummaryDtoSchema.safeParse(noName).success).toBe(false);
  });

  it('EndorsementDtoSchema parses nested risks', () => {
    const row = {
      id: 'e1', endorsementNumber: 'END-1', status: 'DRAFT', endorsementType: 'RENEWAL',
      policyId: 'p1', policyNumber: 'POL-1', customerId: 'c1', customerName: 'Ada',
      productName: 'Motor', classOfBusinessName: 'Motor', brokerId: null, brokerName: null,
      effectiveDate: '2026-01-01', policyEndDate: '2026-12-31', remainingDays: 300,
      oldSumInsured: 1, newSumInsured: 2, oldNetPremium: 1, newNetPremium: 2,
      premiumAdjustment: 1, currencyCode: 'NGN', description: null, notes: null,
      approvedBy: null, approvedAt: null, rejectedBy: null, rejectedAt: null,
      rejectionReason: null, createdAt: '2026-01-01T00:00:00Z',
      risks: [{ id: 'r1', description: 'Car', sumInsured: 1, premium: 1, sectionId: null,
        sectionName: null, riskDetails: null, vehicleRegNumber: null, orderNo: 0 }],
    };
    expect(EndorsementDtoSchema.parse(row).risks).toHaveLength(1);
  });

  it('a Page-shaped payload does not satisfy z.array (drift guard)', () => {
    const page = { content: [], totalElements: 0 };
    expect(z.array(PolicySummaryDtoSchema).safeParse(page).success).toBe(false);
    expect(z.array(QuoteSummaryDtoSchema).safeParse(page).success).toBe(false);
  });
});
```

- [ ] **Step 2:** Run `pnpm --filter @cia/back-office test -- core-lists-envelope-parse` → both/all pass. (If the isolated-file coverage floor trips exit 1, that's the known single-file artifact — confirm with the full suite in the slice wrap.)
- [ ] **Step 3:** Commit: `test(core-lists): schema envelope + shape regression guards`.

---

## Self-Review notes

- **Spec coverage:** Policy (T1, swap), Quotation (T2, new schema), Endorsement (T3, nested schema), Customer (T4, new summary DTO + latent-bug fix), regression test (T5). All 4 inventory pages covered. ✓
- **Type consistency:** each `XDtoSchema` + `type XDto = z.infer<...>` pair; `EndorsementDtoSchema.risks` references the earlier-declared `EndorsementRiskDtoSchema`; page generics rebind to the exact produced type.
- **Nullability discipline:** `EndorsementDto`'s `| null` (non-optional) fields → `.nullable()`; summary optionals (`brokerName?`, `expiresAt?`, customer `email?`) → `.nullable().optional()`. Matches each interface/record.
- **Guard blind-spot:** T4 is the one that fixes a real over-claim (list bound full DTO); the other three already bound the correct summary/response type, so they are true swaps + schema authoring.
- **Backlog:** on completion, this drains the **list-page portion** of `raw-apiclient-list-validatedget-sweep` for the core-txn cluster; the row stays open until **S4b** (report pages with local row types) lands. The Customer blank-name fix is a bug closed in-flight (surfaced by the migration, intrinsic to doing it correctly) — note it in the session entry, no separate backlog row needed.
