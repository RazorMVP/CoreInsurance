# S4a-1 — validatedGet sweep: Setup cluster Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Migrate the Setup module's top-level list pages from raw `apiClient.get(...).then(r => r.data.data)` to the envelope-drift-immune `validatedGet(url, z.array(XDtoSchema))`, authoring the zod schemas those DTOs currently lack.

**Architecture:** Pure frontend. For each in-scope Setup DTO, add a zod `XDtoSchema` in `@cia/api-client/modules/setup.ts` and convert the plain `export interface XDto {…}` to `export type XDto = z.infer<typeof XDtoSchema>` (the established Policy/finance pattern). Then swap each list page's raw `useQuery` fetch to `validatedGet`. This is the first of three S4 sub-slices (S4a-1 Setup, S4a-2 core txn lists, S4b local-type report pages).

**Tech Stack:** React + TanStack Query + zod + `@cia/api-client` (`validatedGet`).

## Global Constraints

- **Backend unchanged.** All endpoints already return `ApiResponse<List<T>>` (flat array in `data`).
- **Drift guard stays green:** `check-dto-drift.mjs` handles Pattern 3 (`export type XDto = z.infer<typeof XDtoSchema>`) by parsing the `z.object({…})` body — so converting an interface to a schema-derived type keeps drift coverage. Do NOT delete the `XDto` name; convert it.
- **Field → zod mapping (mirror each interface exactly):**
  - `foo: string` → `foo: z.string()`
  - `foo?: string | null` → `foo: z.string().nullable().optional()`  (matches `PolicySummaryDtoSchema`)
  - `foo: number` → `foo: z.number()`  · `foo?: number | null` → `z.number().nullable().optional()`
  - `foo: boolean` → `foo: z.boolean()`
  - string-literal union `'A' | 'B'` → `z.enum(['A', 'B'])`
  - nested `foo: BarDto[]` → `foo: z.array(BarDtoSchema)` · `foo?: BarDto[] | null` → `z.array(BarDtoSchema).nullable().optional()` (author `BarDtoSchema` first, above the parent)
- **Page swap idiom:** replace
  ```ts
  queryFn: async () => (await apiClient.get<{ data: XDto[] }>('/api/v1/…')).data.data,
  ```
  with
  ```ts
  queryFn: () => validatedGet('/api/v1/…', z.array(XDtoSchema)),
  ```
  Add `import { validatedGet, XDtoSchema } from '@cia/api-client'` + `import { z } from 'zod'`; drop the now-unused `apiClient` import only if no other call in the file uses it (Organisations/Vehicle/Claims pages still use `apiClient` for mutations via their sheets — but those live in the *sheet* files, so the *page* file may lose the `apiClient` import; verify per file).
- **Verify per task:** `pnpm --filter @cia/back-office build` + `node cia-frontend/scripts/check-dto-drift.mjs` + `bash cia-frontend/scripts/check-api-wiring.sh` all green.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

## Reference exemplars (author all 24 schemas to this shape)

Simple `{name,code}` (SbuDto, `setup.ts:200`):
```ts
export const SbuDtoSchema = z.object({
  id:         z.string(),
  name:       z.string(),
  code:       z.string(),
  createdAt:  z.string(),
  updatedAt:  z.string().nullable().optional(),
});
export type SbuDto = z.infer<typeof SbuDtoSchema>;
```

Nested + optional + enum (ProductDto, `setup.ts:121`, with `ProductSectionDto` `setup.ts:107` authored first):
```ts
export const ProductSectionDtoSchema = z.object({
  /* mirror ProductSectionDto fields exactly */
});
export type ProductSectionDto = z.infer<typeof ProductSectionDtoSchema>;

export const ProductDtoSchema = z.object({
  id:                  z.string(),
  name:                z.string(),
  code:                z.string(),
  classOfBusinessId:   z.string(),
  classOfBusinessName: z.string(),
  type:                z.enum(['SINGLE_RISK', 'MULTI_RISK']),
  rate:                z.number(),
  minPremium:          z.number(),
  active:              z.boolean(),
  sections:            z.array(ProductSectionDtoSchema).nullable().optional(),
  createdAt:           z.string(),
  updatedAt:           z.string().nullable().optional(),
});
export type ProductDto = z.infer<typeof ProductDtoSchema>;
```

`setup.ts` already imports `z` (existing NotificationTemplate schemas), so no new import there.

---

### Task 1: Organisations schemas + page (9 DTOs / 9 gets)

**Files:** Modify `packages/api-client/src/modules/setup.ts` (convert 9 interfaces); modify `apps/back-office/src/modules/setup/pages/organisations/OrganisationsPage.tsx` (9 `useQuery` swaps).

**DTOs (convert `interface`→`schema`+`z.infer` at these lines):** `BrokerDto` (173), `BranchDto` (188), `SbuDto` (200), `ReinsuranceCompanyDto` (285), `RelationshipManagerDto` (300), `AdjusterDto` (315), `AgentDto` (334), `SurveyorDto` (391), `InsuranceCompanyDto` (404).

- [ ] **Step 1:** For each of the 9, read its `export interface` body in `setup.ts` and author `XDtoSchema = z.object({…})` (fields mirrored per the mapping rules) immediately above it, then change the interface to `export type XDto = z.infer<typeof XDtoSchema>`.
- [ ] **Step 2:** In `OrganisationsPage.tsx`, swap each of the 9 tab `useQuery` `queryFn`s to `validatedGet('/api/v1/setup/<path>', z.array(XDtoSchema))`. Endpoint paths (from the current file): `brokers`, `reinsurance-companies`, `insurance-companies`, `branches`, `sbus`, `surveyors`, `adjusters`, `agents`, `relationship-managers` (all `/api/v1/setup/…`). Update imports: add `validatedGet` + the 9 `*DtoSchema` from `@cia/api-client` + `z` from `zod`; keep the `*Dto` type imports (still used for `ColumnDef<XDto>` etc.).
- [ ] **Step 3:** `pnpm --filter @cia/back-office build` → pass (tsc proves `z.infer` types still satisfy every `ColumnDef<XDto>`/sheet prop). `node cia-frontend/scripts/check-dto-drift.mjs` → `✓ No DTO drift` (guard now reads the 9 schema bodies). `bash cia-frontend/scripts/check-api-wiring.sh` → pass.
- [ ] **Step 4:** Commit: `refactor(setup): validatedGet + zod schemas for Organisations tabs`.

---

### Task 2: Vehicle Registry + Claims Config schemas + pages (7 DTOs / 7 gets)

**Files:** Modify `setup.ts` (convert 7 interfaces); modify `vehicle-registry/VehicleRegistryPage.tsx` (3 gets) + `claims-config/ClaimsConfigPage.tsx` (4 list gets).

**DTOs:** `VehicleMakeDto` (209), `VehicleTypeDto` (217), `VehicleModelDto` (226), `ClaimReserveCategoryDto` (236), `NatureOfLossDto` (245), `CauseOfLossDto` (254), `ClaimDocumentRequirementDto` (274). (`ClaimNotificationTimelineDto` is a per-product **singleton**, not a list — leave it as raw `apiClient.get`; out of scope.)

- [ ] **Step 1:** Author the 7 schemas in `setup.ts` (convert each interface as in Task 1).
- [ ] **Step 2 — VehicleRegistryPage:** swap the 3 `useQuery`s (MakesTab `vehicle-makes`, TypesTab `vehicle-types`, ModelsTab `vehicle-makes/${makeId}/models`) to `validatedGet(url, z.array(VehicleXDtoSchema))`. The `makeId`-nested URL keeps its template literal. Note the `ModelsTab`'s `makesQuery` (a make **Select** feed) is a dropdown — migrate it too for consistency since it binds `VehicleMakeDto[]` (same schema), but this is optional; if kept raw, leave a one-line note. **Decision: migrate it** (schema already authored, zero extra cost).
- [ ] **Step 3 — ClaimsConfigPage:** swap the 4 list `useQuery`s — `ReservesTab` (`claim-reserve-categories`), `LossTab` natures (`nature-of-loss`) + causes (`cause-of-loss`), `DocumentsTab` (`products/${productId}/claim-document-requirements`). Leave `TimelinesTab`'s timeline GET (singleton) and the `products` **Select** feeds as raw `apiClient.get` (dropdown/singleton — S4b/deferred); `CauseOfLossSheet`/`NatureOfLoss` select feeds live in sheet files, untouched here.
- [ ] **Step 4:** build + dto-drift + api-wiring → pass.
- [ ] **Step 5:** Commit: `refactor(setup): validatedGet + zod schemas for Vehicle Registry + Claims Config`.

---

### Task 3: Products / Users / Access & Approval Groups / Classes / Clause (8 DTOs / 6 gets)

**Files:** Modify `setup.ts` (convert 8 interfaces incl. 2 nested); modify `products/ProductsPage.tsx`, `users/UsersPage.tsx`, `access-groups/AccessGroupsPage.tsx`, `approval-groups/ApprovalGroupsPage.tsx`, `classes/ClassesPage.tsx`, `policy-specs/ClauseBankTab.tsx`.

**DTOs:** `ProductSectionDto` (107) + `ProductDto` (121); `UserDto` (51); `AccessGroupDto` (67); `ApprovalLevelDto` (95) + `ApprovalGroupDto` (82); `ClassOfBusinessDto` (140); `ClauseDto` (352). **Author the nested child schema first** (`ProductSectionDtoSchema` before `ProductDtoSchema`; `ApprovalLevelDtoSchema` before `ApprovalGroupDtoSchema`).

- [ ] **Step 1:** Author the 8 schemas (2 nested pairs + 4 flat) in `setup.ts`, converting each interface.
- [ ] **Step 2:** Swap each page's list `useQuery`:
  - `ProductsPage` → `validatedGet('/api/v1/setup/products', z.array(ProductDtoSchema))`.
  - `UsersPage` → `.../users`, `z.array(UserDtoSchema)`.
  - `AccessGroupsPage` → `.../access-groups`, `z.array(AccessGroupDtoSchema)`.
  - `ApprovalGroupsPage` → `.../approval-groups`, `z.array(ApprovalGroupDtoSchema)`.
  - `ClassesPage` → `.../classes-of-business`, `z.array(ClassOfBusinessDtoSchema)`.
  - `ClauseBankTab` → `.../clauses`, `z.array(ClauseDtoSchema)` (the page maps `ClauseDto` → a local `ClauseRow` for the table; keep that `.map`, only the fetch changes).
- [ ] **Step 3:** build + dto-drift + api-wiring → pass. (tsc confirms `ApprovalGroupDto.levels` / `ProductDto.sections` nested arrays still type-check against every consumer.)
- [ ] **Step 4:** Commit: `refactor(setup): validatedGet + zod schemas for products/users/groups/classes/clauses`.

---

### Task 4: Envelope-parse regression test

**Files:** Create `apps/back-office/src/modules/setup/setup-envelope-parse.test.ts`. Mirror `apps/back-office/src/modules/audit/audit-envelope-parse.test.ts` (read it first for the exact idiom).

- [ ] **Step 1: Write the test** — import one migrated schema (`SbuDtoSchema`) + `apiEnvelope` from `@cia/api-client`; assert (a) a correct `{ data: [ {…valid Sbu…} ] }` envelope parses, and (b) a **Spring `Page`-shaped** `{ data: { content: [...], totalElements: 1 } }` payload **throws** on `apiEnvelope(z.array(SbuDtoSchema)).parse(...)` — proving the exact envelope-drift bug is now caught at runtime. Follow `audit-envelope-parse.test.ts`'s structure (it already exercises `apiEnvelope` + a DTO schema).
- [ ] **Step 2:** Run the full suite `pnpm --filter @cia/back-office test` → all pass, coverage above floors.
- [ ] **Step 3:** Commit: `test(setup): envelope-parse regression for setup list schemas`.

---

## Self-Review notes

- **Spec coverage:** all 9 in-scope Setup list files migrated (Organisations T1, Vehicle+Claims T2, Products/Users/Groups/Classes/Clause T3) + a regression test (T4). ✓
- **24 schemas:** 9 (org) + 3 (vehicle) + 4 (claims) + 2 (product + section) + 1 (user, has a 3-value `status` `z.enum`) + 1 (accessgroup — `permissions: z.array(z.string())`, **no child DTO**) + 2 (approvalgroup + level) + 1 (class) + 1 (clause) = 24. Confirmed against the interface bodies; the only nested child schemas are `ProductSectionDto` and `ApprovalLevelDto` (author each before its parent).
- **Drift safety:** every converted DTO keeps its `XDto` name as `z.infer<…>`; `check-dto-drift` Pattern-3 parsing covers it. Nested child schemas (`ProductSectionDto`, `ApprovalLevelDto`) also gain coverage (they were previously interfaces).
- **No behavior change:** `validatedGet` returns the same array the raw `.data.data` did; TanStack consumers unchanged. tsc is the proof the `z.infer` types are structurally identical to the old interfaces (any field-type typo in a schema fails the build at a consumer).
- **Out of scope (deferred):** `ClaimNotificationTimelineDto` singleton; the `products`/make **Select**-feed dropdowns (except the zero-cost VehicleMake one); page-local `AdjustmentTypeDto`/`QuoteConfigDto` (→ S4b); `CommissionSetupDto` (nested-in-sheet). 
- **Backlog:** on completion, `raw-apiclient-list-validatedget-sweep` is **partially drained** (Setup tier done) — annotate the row "Setup done (S4a-1); core txn lists (S4a-2) + local-type reports (S4b) remain"; the row closes after S4a-2 + S4b.
