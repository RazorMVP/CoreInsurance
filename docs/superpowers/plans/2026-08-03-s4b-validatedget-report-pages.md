# S4b — validatedGet sweep: report pages (local-type row schemas) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close the last of `raw-apiclient-list-validatedget-sweep` — migrate the report/analytics list pages whose rows are **page-local interfaces** (no `@cia/api-client` DTO) from raw `apiClient.get(...).then(r => r.data.data)` onto `validatedGet(url, z.array(Schema))`, authoring the zod schemas co-located with their types.

**Architecture:** Pure frontend. Unlike S4a (which converted shared `@cia/api-client` DTOs), S4b's types are **app-local**: 4 flat report-row interfaces live in their page files; `ReportDefinition`/`ReportAccessPolicy` (+ the `ReportConfig` graph) live in `apps/back-office/src/modules/reports/types/report.types.ts`. Schemas are authored **in those same files** — nothing is hoisted into `@cia/api-client`, so `check-dto-drift` is not involved. Each schema is converted to the single-source-of-truth `export const XSchema = z.object({...})` + `export type X = z.infer<typeof XSchema>` form, enums inlined as `z.enum([...])` (matching the S4a convention).

**Tech Stack:** React + TanStack Query + zod + `validatedGet` (from `@cia/api-client`).

## Global Constraints

- **FE-only. No backend change.** All 6 endpoints already exist and are consumed today.
- **`validatedGet` fails loudly on shape drift** — schemas must faithfully mirror the response the endpoint actually returns. The current pages already assume these fields present + non-null (they do `.toLocaleString()` / `.reduce((s,r)=>s+r.x)` on them), so mirror the interfaces as **required** (no spurious `.nullable()`); only mark a field optional where the source interface has `?`.
- **Enums inlined** as `z.enum([...])` inside the object schema; leave every existing `export type XEnum = '...' | '...'` alias in `report.types.ts` **untouched** (the `z.infer` of an inlined `z.enum` is structurally identical, so consumers of `ReportCategory`/`DataSource`/etc. keep working). This mirrors the S4a Adjuster/Agent handling.
- **Scope = list/array GETs only.** Migrate the three array-returning report hooks + three report pages. Leave the single-object detail GET (`useReportDefinition`) and every mutation (`useCloneReport`, `usePinReport`, `useUnpinReport`, `useUpsertAccessPolicy`) **as raw `apiClient`** — they are out of the list-sweep scope and not the white-screen risk.
- `check-api-wiring` (no `console.log`, no top-level mock) + `check-dto-drift` (unaffected) + `pnpm --filter @cia/back-office build` + the back-office Vitest suite must pass.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

- **Modify** `apps/back-office/src/modules/reports/types/report.types.ts` — convert `ReportField`, `ReportFilter`, `ReportChart`, `ReportConfig`, `ReportDefinition`, `ReportAccessPolicy` from `interface` → `const XSchema = z.object({...})` + `type X = z.infer<...>` (dependency order already correct in-file). Add `import { z } from 'zod'`.
- **Modify** `apps/back-office/src/modules/reports/hooks/useReportDefinitions.ts` — list query → `validatedGet` (leave `useReportDefinition` single + `useCloneReport` mutation raw).
- **Modify** `apps/back-office/src/modules/reports/hooks/useReportPins.ts` — list query → `validatedGet` (leave the two mutation hooks raw).
- **Modify** `apps/back-office/src/modules/reports/hooks/useReportAccessPolicies.ts` — list query → `validatedGet` (leave `useUpsertAccessPolicy` mutation raw).
- **Modify** `apps/back-office/src/modules/customers/pages/reports/ActiveCustomersReportPage.tsx` — local `ActiveCustomersRowSchema` + `validatedGet`.
- **Modify** `apps/back-office/src/modules/customers/pages/reports/LossRatioReportPage.tsx` — local `LossRatioRowSchema` + `validatedGet`.
- **Modify** `apps/back-office/src/modules/endorsements/pages/reports/DebitNoteAnalysisPage.tsx` — local `PeriodRowSchema` + `TypeRowSchema` + two `validatedGet`.
- **Create** `apps/back-office/src/modules/reports/reports-envelope-parse.test.ts` — parse regression guard (mirrors `setup-envelope-parse.test.ts` / `core-lists-envelope-parse.test.ts`).

---

### Task 1: `report.types.ts` — schemas for the ReportDefinition graph

**Files:** Modify `apps/back-office/src/modules/reports/types/report.types.ts`.

**Interfaces:**
- Consumes: nothing (leaf).
- Produces: `ReportFieldSchema`, `ReportFilterSchema`, `ReportChartSchema`, `ReportConfigSchema`, `ReportDefinitionSchema`, `ReportAccessPolicySchema` (+ the same-named `z.infer` types, structurally identical to today's interfaces). Consumed by Task 2.

- [ ] **Step 1:** Add `import { z } from 'zod';` at the top of the file (before the first `export`).

- [ ] **Step 2:** Replace the `ReportField` / `ReportFilter` / `ReportChart` interfaces with schemas (enums inlined; leave the `FieldType`/`FilterType`/`ChartType` aliases above them untouched):

```ts
export const ReportFieldSchema = z.object({
  key:      z.string(),
  label:    z.string(),
  type:     z.enum(['STRING', 'MONEY', 'PERCENT', 'DATE', 'NUMBER', 'INTEGER']),
  computed: z.boolean(),
});
export type ReportField = z.infer<typeof ReportFieldSchema>;

export const ReportFilterSchema = z.object({
  key:      z.string(),
  label:    z.string(),
  type:     z.enum(['DATE', 'DATE_RANGE', 'SELECT', 'MULTI_SELECT', 'TEXT', 'NUMBER']),
  required: z.boolean(),
  /** Optional default value set in the Builder; pre-fills the Viewer's filter input. */
  defaultValue: z.string().optional(),
});
export type ReportFilter = z.infer<typeof ReportFilterSchema>;

export const ReportChartSchema = z.object({
  type:  z.enum(['BAR', 'LINE', 'PIE', 'TABLE_ONLY']),
  xAxis: z.string().optional(),
  yAxis: z.string().optional(),
});
export type ReportChart = z.infer<typeof ReportChartSchema>;
```

- [ ] **Step 3:** Replace the `ReportConfig` interface with a schema (references the three above; `sortDir` inlined):

```ts
export const ReportConfigSchema = z.object({
  fields:  z.array(ReportFieldSchema),
  filters: z.array(ReportFilterSchema),
  groupBy: z.string().optional(),
  sortBy:  z.string().optional(),
  sortDir: z.enum(['ASC', 'DESC']).optional(),
  chart:   ReportChartSchema.optional(),
});
export type ReportConfig = z.infer<typeof ReportConfigSchema>;
```

- [ ] **Step 4:** Replace the `ReportDefinition` interface with a schema. `category`/`type`/`dataSource` enums inlined — **note:** the 17-value `DataSource` enum is inlined verbatim (a future backend source value will fail parse loudly; that is the intended drift-catch and is already tracked by `reports-frontend-datasource-union-sync`):

```ts
export const ReportDefinitionSchema = z.object({
  id:          z.string(),
  name:        z.string(),
  description: z.string().optional(),
  category:    z.enum(['UNDERWRITING', 'CLAIMS', 'FINANCE', 'REINSURANCE', 'CUSTOMER', 'REGULATORY', 'CLOSURES']),
  type:        z.enum(['SYSTEM', 'CUSTOM']),
  dataSource:  z.enum([
    'POLICIES', 'CLAIMS', 'FINANCE', 'REINSURANCE', 'CUSTOMERS', 'ENDORSEMENTS',
    'TRIAL_BALANCE', 'GENERAL_LEDGER', 'GL_PERIOD_LOCK', 'PAA_LRC', 'PAA_GROUPS',
    'IFRS17_MOVEMENT', 'IFRS9_HOLDINGS', 'IFRS9_CARRYING', 'IFRS9_MOVEMENT',
    'RM_COMMISSION', 'UNDERWRITING_PERFORMANCE',
  ]),
  config:    ReportConfigSchema,
  pinnable:  z.boolean(),
  active:    z.boolean(),
  createdAt: z.string(),
});
export type ReportDefinition = z.infer<typeof ReportDefinitionSchema>;
```

- [ ] **Step 5:** Replace the `ReportAccessPolicy` interface with a schema (nested optional `report`):

```ts
export const ReportAccessPolicySchema = z.object({
  id:            z.string(),
  accessGroupId: z.string(),
  category:      z.enum(['UNDERWRITING', 'CLAIMS', 'FINANCE', 'REINSURANCE', 'CUSTOMER', 'REGULATORY', 'CLOSURES']).optional(),
  report:        ReportDefinitionSchema.optional(),
  canView:       z.boolean(),
  canExportCsv:  z.boolean(),
  canExportPdf:  z.boolean(),
});
export type ReportAccessPolicy = z.infer<typeof ReportAccessPolicySchema>;
```

- [ ] **Step 6:** Build. The ~10 report pages/components that consume these types compile only if the `z.infer` shapes are structurally identical to the old interfaces — the build is the proof:

Run: `pnpm --filter @cia/back-office build`
Expected: PASS (no TS errors).

- [ ] **Step 7:** Commit:

```bash
git add cia-frontend/apps/back-office/src/modules/reports/types/report.types.ts
git commit -m "refactor(reports): zod schemas for ReportDefinition + ReportAccessPolicy graph"
```

---

### Task 2: migrate the 3 report list hooks to `validatedGet`

**Files:** Modify `useReportDefinitions.ts`, `useReportPins.ts`, `useReportAccessPolicies.ts`.

**Interfaces:** Consumes `ReportDefinitionSchema`, `ReportAccessPolicySchema` (Task 1).

- [ ] **Step 1: `useReportDefinitions.ts`** — migrate only the `useReportDefinitions` (list) query. Change the imports + the one queryFn; leave `useReportDefinition` + `useCloneReport` untouched:

Change the import line
```ts
import { apiClient } from '@cia/api-client';
import type { ReportCategory, ReportDefinition } from '../types/report.types';
```
to
```ts
import { z } from 'zod';
import { apiClient, validatedGet } from '@cia/api-client';
import { ReportDefinitionSchema } from '../types/report.types';
import type { ReportCategory, ReportDefinition } from '../types/report.types';
```
(`apiClient` stays — `useReportDefinition` + `useCloneReport` still use it.)

Change the `useReportDefinitions` queryFn
```ts
    queryFn: async () => {
      const params = category ? `?category=${category}` : '';
      const res = await apiClient.get<{ data: ReportDefinition[] }>(
        `/api/v1/reports/definitions${params}`
      );
      return res.data.data;
    },
```
to
```ts
    queryFn: () => {
      const params = category ? `?category=${category}` : '';
      return validatedGet(`/api/v1/reports/definitions${params}`, z.array(ReportDefinitionSchema));
    },
```

- [ ] **Step 2: `useReportPins.ts`** — migrate the `useReportPins` list query; leave `usePinReport`/`useUnpinReport` untouched:

Imports:
```ts
import { z } from 'zod';
import { apiClient, validatedGet } from '@cia/api-client';
import { ReportDefinitionSchema } from '../types/report.types';
import type { ReportDefinition } from '../types/report.types';
```
queryFn:
```ts
    queryFn: () => validatedGet('/api/v1/reports/pins', z.array(ReportDefinitionSchema)),
```

- [ ] **Step 3: `useReportAccessPolicies.ts`** — migrate the list query; leave `useUpsertAccessPolicy` untouched:

Imports:
```ts
import { z } from 'zod';
import { apiClient, validatedGet } from '@cia/api-client';
import { ReportAccessPolicySchema } from '../types/report.types';
import type { ReportAccessPolicy } from '../types/report.types';
```
queryFn:
```ts
    queryFn: () => validatedGet(
      `/api/v1/reports/access-policies?accessGroupId=${accessGroupId}`,
      z.array(ReportAccessPolicySchema),
    ),
```

- [ ] **Step 4:** Build + api-wiring:

Run: `pnpm --filter @cia/back-office build && bash cia-frontend/scripts/check-api-wiring.sh`
Expected: both PASS.

- [ ] **Step 5:** Commit:

```bash
git add cia-frontend/apps/back-office/src/modules/reports/hooks/useReportDefinitions.ts cia-frontend/apps/back-office/src/modules/reports/hooks/useReportPins.ts cia-frontend/apps/back-office/src/modules/reports/hooks/useReportAccessPolicies.ts
git commit -m "refactor(reports): validatedGet for definitions/pins/access-policies list hooks"
```

---

### Task 3: Customer report pages — local row schemas

**Files:** Modify `ActiveCustomersReportPage.tsx`, `LossRatioReportPage.tsx`.

**Interfaces:** self-contained (page-local schemas).

- [ ] **Step 1: `ActiveCustomersReportPage.tsx`** — add zod + validatedGet imports, convert the interface to a schema, swap the fetch. Replace
```ts
interface ActiveCustomersRow { broker: string; individual: number; corporate: number; total: number; }
```
with
```ts
const ActiveCustomersRowSchema = z.object({
  broker:     z.string(),
  individual: z.number(),
  corporate:  z.number(),
  total:      z.number(),
});
type ActiveCustomersRow = z.infer<typeof ActiveCustomersRowSchema>;
```
Add to the import block: `import { z } from 'zod';` and `validatedGet` from `@cia/api-client` (add the import if the file has none). Replace the queryFn
```ts
    queryFn: async () => {
      const res = await apiClient.get<{ data: ActiveCustomersRow[] }>(
        '/api/v1/customers/reports/active-by-channel',
      );
      return res.data.data;
    },
```
with
```ts
    queryFn: () => validatedGet('/api/v1/customers/reports/active-by-channel', z.array(ActiveCustomersRowSchema)),
```
If `apiClient` is now unused in the file, drop it from the import (verify: `rg -c 'apiClient\.' <file>` → 0).

- [ ] **Step 2: `LossRatioReportPage.tsx`** — same shape. Replace
```ts
interface LossRatioRow { class: string; premiums: number; claims: number; lossRatio: number; }
```
with
```ts
const LossRatioRowSchema = z.object({
  class:     z.string(),
  premiums:  z.number(),
  claims:    z.number(),
  lossRatio: z.number(),
});
type LossRatioRow = z.infer<typeof LossRatioRowSchema>;
```
Add `import { z } from 'zod';` + `validatedGet`. Replace the queryFn
```ts
    queryFn: async () => {
      const res = await apiClient.get<{ data: LossRatioRow[] }>(
        '/api/v1/customers/reports/loss-ratio-by-class',
      );
      return res.data.data;
    },
```
with
```ts
    queryFn: () => validatedGet('/api/v1/customers/reports/loss-ratio-by-class', z.array(LossRatioRowSchema)),
```
Drop `apiClient` from the import if unused.

- [ ] **Step 3:** Build + api-wiring → PASS.

Run: `pnpm --filter @cia/back-office build && bash cia-frontend/scripts/check-api-wiring.sh`

- [ ] **Step 4:** Commit:

```bash
git add cia-frontend/apps/back-office/src/modules/customers/pages/reports/ActiveCustomersReportPage.tsx cia-frontend/apps/back-office/src/modules/customers/pages/reports/LossRatioReportPage.tsx
git commit -m "refactor(customers): validatedGet for active-customers + loss-ratio report pages"
```

---

### Task 4: Endorsement DebitNoteAnalysisPage — two local row schemas

**Files:** Modify `endorsements/pages/reports/DebitNoteAnalysisPage.tsx`.

- [ ] **Step 1:** Replace the two interfaces
```ts
interface PeriodRow { period: string; endorsements: number; debits: number; credits: number; netPremium: number; }
interface TypeRow   { type: string; count: number; totalPremium: number; }
```
with schemas
```ts
const PeriodRowSchema = z.object({
  period:       z.string(),
  endorsements: z.number(),
  debits:       z.number(),
  credits:      z.number(),
  netPremium:   z.number(),
});
type PeriodRow = z.infer<typeof PeriodRowSchema>;

const TypeRowSchema = z.object({
  type:         z.string(),
  count:        z.number(),
  totalPremium: z.number(),
});
type TypeRow = z.infer<typeof TypeRowSchema>;
```
Add `import { z } from 'zod';` + `validatedGet` from `@cia/api-client`.

- [ ] **Step 2:** Swap both queryFns. Replace
```ts
      const res = await apiClient.get<{ data: PeriodRow[] }>(
        '/api/v1/endorsements/reports/debit-note-by-period',
      );
      return res.data.data;
```
with
```ts
      return validatedGet('/api/v1/endorsements/reports/debit-note-by-period', z.array(PeriodRowSchema));
```
(remove the now-unneeded `async` wrapper's `const res` lines — the queryFn becomes `queryFn: () => validatedGet(...)`), and likewise the by-type query:
```ts
    queryFn: () => validatedGet('/api/v1/endorsements/reports/debit-note-by-type', z.array(TypeRowSchema)),
```
Drop `apiClient` from the import if now unused (`rg -c 'apiClient\.'` → 0).

- [ ] **Step 3:** Build + api-wiring → PASS.

- [ ] **Step 4:** Commit:

```bash
git add cia-frontend/apps/back-office/src/modules/endorsements/pages/reports/DebitNoteAnalysisPage.tsx
git commit -m "refactor(endorsements): validatedGet for debit-note-analysis report page"
```

---

### Task 5: envelope-parse regression test

**Files:** Create `apps/back-office/src/modules/reports/reports-envelope-parse.test.ts`. Mirror `customers/core-lists-envelope-parse.test.ts` (pure schema-parse test — no rendering, no mocks).

- [ ] **Step 1: Write the test** — assert the ReportDefinition graph parses a valid payload, rejects a Page-shaped envelope, and that the flat report-row schemas behave:

```ts
import { describe, it, expect } from 'vitest';
import { z } from 'zod';
import {
  ReportDefinitionSchema,
  ReportAccessPolicySchema,
} from './types/report.types';

const validDefinition = {
  id: 'r1',
  name: 'Loss Ratio by Class',
  description: 'x',
  category: 'CUSTOMER',
  type: 'SYSTEM',
  dataSource: 'UNDERWRITING_PERFORMANCE',
  config: {
    fields: [{ key: 'class', label: 'Class', type: 'STRING', computed: false }],
    filters: [{ key: 'date_from', label: 'From', type: 'DATE', required: false }],
    chart: { type: 'TABLE_ONLY' },
  },
  pinnable: true,
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
};

describe('reports envelope + shape contract', () => {
  it('validatedGet-shaped array of ReportDefinition parses', () => {
    const parsed = z.array(ReportDefinitionSchema).parse([validDefinition]);
    expect(parsed[0].dataSource).toBe('UNDERWRITING_PERFORMANCE');
    expect(parsed[0].config.fields[0].type).toBe('STRING');
  });

  it('rejects a Spring Page-shaped envelope where a flat array is expected', () => {
    const pageShaped = { content: [validDefinition], totalElements: 1 };
    expect(() => z.array(ReportDefinitionSchema).parse(pageShaped)).toThrow();
  });

  it('rejects an unknown dataSource enum value (drift-catch)', () => {
    expect(() => ReportDefinitionSchema.parse({ ...validDefinition, dataSource: 'NOT_A_SOURCE' })).toThrow();
  });

  it('ReportAccessPolicy parses with an optional nested report + without it', () => {
    const base = { id: 'p1', accessGroupId: 'g1', canView: true, canExportCsv: false, canExportPdf: false };
    expect(ReportAccessPolicySchema.parse(base).report).toBeUndefined();
    expect(ReportAccessPolicySchema.parse({ ...base, category: 'FINANCE', report: validDefinition }).report?.id).toBe('r1');
  });
});
```

- [ ] **Step 2:** Run the test:

Run: `pnpm --filter @cia/back-office test -- reports-envelope-parse`
Expected: all pass.

- [ ] **Step 3:** Run the **full** back-office suite to confirm coverage floors still hold with the new file included:

Run: `pnpm --filter @cia/back-office test`
Expected: all files pass, no coverage-threshold error (exit 0).

- [ ] **Step 4:** Commit:

```bash
git add cia-frontend/apps/back-office/src/modules/reports/reports-envelope-parse.test.ts
git commit -m "test(reports): envelope + ReportDefinition shape regression guard"
```

---

## Self-Review notes

- **Spec coverage:** the 6 S4b targets — 3 report hooks (T2) + 2 customer report pages (T3) + endorsement DN-analysis (T4) — all migrated; the `ReportDefinition`/`ReportAccessPolicy` schema graph they need is T1; T5 is the guard. ✓ Closes `raw-apiclient-list-validatedget-sweep` (all list-page portions across S4a-1/S4a-2/S4b).
- **Type consistency:** `ReportDefinitionSchema`/`ReportAccessPolicySchema` names used identically in T1 (produce) and T2 (consume); the 4 page-local schemas are self-contained.
- **No placeholders:** every schema + every queryFn swap shown in full.
- **Enum fragility (design note):** the inlined 17-value `DataSource` enum makes a *new backend source value* fail the definitions-list parse loudly. That is the intended drift-catch (a new source requires an FE union sync — the standing `reports-frontend-datasource-union-sync` invariant). If a looser contract is ever wanted, relax `dataSource` to `z.string()` — but that also drops the sync enforcement.
- **Scope discipline:** single-object detail GET (`useReportDefinition`) and all mutations stay raw — out of the list-sweep scope, logged as the opportunistic remainder if ever swept.
- **Backlog:** on landing, remove `raw-apiclient-list-validatedget-sweep` (all three sub-slices done). The ~30 dropdown/select raw fetches were always explicitly out of S4 scope (noted in the sequencing spec) — if we want them tracked, add a new P3 row; otherwise the row closes clean.
