# S5.2 — Server-Side Pagination: Underwriting lists (Policies / Quotations / Endorsements) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Put the three underwriting list pages on true server-side pagination + server search + server sort, consuming the S5.1 shared infra, with a combinable `q`/`status`(/`endorsementType`) filter Specification per endpoint.

**Architecture:** FE **+** BE. Backend: give each of the 3 repos `JpaSpecificationExecutor`, add a `*Specs` class (mirroring finance `ReceiptSpecs`), and make the existing `GET` list endpoint accept a combinable `q` (+ keep `status`/`customerId`) — the controllers already return `{data, meta}` + take `Pageable`, so `?page/size/sort` are already wired. Frontend: extend the S5.1 `@cia/ui` infra with **server sort** (DataTable `serverPagination.sort/onSortChange` → `manualSorting`) and a **server-search** toolbar mode, then migrate the 3 pages from `validatedGet(z.array(…))` (fetch-all) to `validatedList(…, {params})` (one page + `meta.total`) driven by `useServerPagination`.

**Tech Stack:** Spring Boot 3.5 / Java 21 + Testcontainers/MockMvc · React + TanStack Table + react-router + Vitest.

## Global Constraints

- **Backend list endpoints already return the canonical envelope** (`ApiResponse.success(page.getContent(), ApiMeta…)`) + take `@PageableDefault(size = 2000) Pageable`. Do **not** change that contract — only add the `q` param + combinable Specification + (Endorsement) `endorsementType`.
- **Sort maps to entity property names** (JPQL/derived queries) — the FE sends `?sort=<entityProp>,<dir>` where `<entityProp>` == the DataTable column `accessorKey` == the DTO field. An unknown property → Spring `PropertyReferenceException` (400). **Only enable server sort on columns whose `accessorKey` is a real entity property** — computed columns (e.g. "Intermediary" = broker∥agent) keep sort disabled.
- **`q` is case-insensitive OR-LIKE across the denormalised columns**; blank/absent `q` → no predicate. Per-module column sets are specified below (they match each module's existing `/search`, except Endorsement which is greenfield).
- **Keep the existing `/policies/search` + `/quotes/search` endpoints** (backward-compat) — the unified list now also accepts `q`, but don't delete `/search` in this slice (a `*-search-endpoint-redundant` P3 can be logged instead).
- FE: `check-api-wiring` + `check-dto-drift` + `pnpm --filter @cia/back-office build` + full Vitest. BE: the touched-module ITs + the new list ITs under `mvn verify -pl cia-api`.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

**Backend (per module — Policy shown; Quote/Endorsement mirror with the swaps in their tasks):**
- Modify `cia-policy/.../PolicyRepository.java` — `extends JpaRepository<Policy,UUID>, JpaSpecificationExecutor<Policy>`.
- Create `cia-policy/.../PolicySpecs.java` — static `Specification<Policy>` factories.
- Modify `cia-policy/.../PolicyService.java` — `list(...)` composes the Specs; keep `search(...)`.
- Modify `cia-policy/.../PolicyController.java` — add `@RequestParam(required=false) String q` to `list(...)`.
- (Quote: same 4 files in `cia-quotation`. Endorsement: same 4 in `cia-endorsement`, + `endorsementType` param + greenfield `qLike`.)

**Backend tests:**
- Create `cia-api/src/test/.../underwriting/UnderwritingWebItSupport.java` — shared `@SpringBootTest`+`@AutoConfigureMockMvc` base (mirrors `FinanceWebItSupport`).
- Create `cia-api/src/test/.../policy/PolicyListControllerIT.java`, `.../quotation/QuoteListControllerIT.java`, `.../endorsement/EndorsementListControllerIT.java` — 2-page + filter ITs.

**Frontend:**
- Modify `packages/ui/src/components/data-table/data-table.tsx` — `serverPagination` gains `sort`/`onSortChange` → `manualSorting`.
- Modify `packages/ui/src/components/data-table/data-table-toolbar.tsx` — optional server-search mode (`searchValue`/`onSearchChange`).
- Create `apps/back-office/src/lib/use-debounced-value.ts` — tiny debounce for the search box (if none exists).
- Modify `apps/back-office/src/test/data-table-server.test.tsx` — add a server-sort case.
- Modify `.../policy/pages/PolicyListPage.tsx`, `.../quotation/pages/QuotationListPage.tsx`, `.../endorsements/pages/EndorsementsListPage.tsx`.

---

### Task 1: FE infra — DataTable server sort + toolbar server search

**Files:** Modify `data-table.tsx`, `data-table-toolbar.tsx`; create `use-debounced-value.ts`; extend `data-table-server.test.tsx`.

**Interfaces:** Extends the S5.1 `serverPagination` prop. Consumed by Tasks 5–7.

- [ ] **Step 1: DataTable server sort.** Extend the `serverPagination` prop and wire `manualSorting` + controlled sorting state. In `data-table.tsx`:

Extend the type (it currently is `ServerPaginationFooterProps`):
```tsx
  serverPagination?: ServerPaginationFooterProps & {
    /** e.g. 'policyNumber,desc'. Present ⇒ server sort (manualSorting). */
    sort?: string;
    onSortChange?: (sort: string) => void;
  };
```
Derive the controlled TanStack sorting state from the `sort` string, and translate header-sort changes back to `col,dir`:
```tsx
  const serverSorting: SortingState = React.useMemo(() => {
    if (!serverPagination?.sort) return [];
    const [id, dir] = serverPagination.sort.split(',');
    return id ? [{ id, desc: dir === 'desc' }] : [];
  }, [serverPagination?.sort]);

  const useServerSort = !!serverPagination?.onSortChange;
```
In `useReactTable`, when `useServerSort`, use the controlled sorting + manual flag + emit on change:
```tsx
    state: { sorting: useServerSort ? serverSorting : sorting, columnFilters, columnVisibility, rowSelection },
    manualPagination: !!serverPagination,
    manualSorting:    useServerSort,
    onSortingChange: useServerSort
      ? (updater) => {
          const next = typeof updater === 'function' ? updater(serverSorting) : updater;
          const s = next[0];
          serverPagination!.onSortChange!(s ? `${s.id},${s.desc ? 'desc' : 'asc'}` : '');
        }
      : setSorting,
    ...(useServerSort ? {} : { getSortedRowModel: getSortedRowModel() }),
    getCoreRowModel:     getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    ...(serverPagination ? {} : { getPaginationRowModel: getPaginationRowModel() }),
```
(Keep the existing `sorting` useState for the client path. `getSortedRowModel` is dropped only in server-sort mode.)

- [ ] **Step 2: Toolbar server-search mode.** In `data-table-toolbar.tsx`, add optional controlled search props; when present, the Input is controlled + emits instead of client-filtering:
```tsx
export interface DataTableToolbarProps<TData> {
  table:              Table<TData>;
  searchColumn?:      string;
  searchPlaceholder?: string;
  /** Server-search mode: controlled value + change handler (bypasses client column filter). */
  searchValue?:       string;
  onSearchChange?:    (value: string) => void;
  children?:          React.ReactNode;
}
```
Render logic — prefer server-search when `onSearchChange` is provided:
```tsx
        {(searchColumn || onSearchChange) && (
          <Input
            placeholder={searchPlaceholder}
            value={onSearchChange ? (searchValue ?? '') : ((table.getColumn(searchColumn!)?.getFilterValue() as string) ?? '')}
            onChange={(e) =>
              onSearchChange
                ? onSearchChange(e.target.value)
                : table.getColumn(searchColumn!)?.setFilterValue(e.target.value)}
            className="h-8 w-full max-w-xs"
          />
        )}
```

- [ ] **Step 3: Debounce helper** (`apps/back-office/src/lib/use-debounced-value.ts`) — only if no equivalent exists (grep first: `rg "useDebounce" apps/back-office/src`):
```ts
import { useEffect, useState } from 'react';

/** Returns `value` delayed by `delayMs` (default 300ms). For search-as-you-type. */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(t);
  }, [value, delayMs]);
  return debounced;
}
```

- [ ] **Step 4: Extend the component test** (`data-table-server.test.tsx`) — add a server-sort case: a sortable column, pass `serverPagination.sort`/`onSortChange`, click the header's Asc/Desc, assert `onSortChange` fires `'name,asc'`/`'name,desc'`. (Reuse the existing describe block; add one `it`.)

```tsx
  it('server sort: header sort emits col,dir via onSortChange', () => {
    const onSortChange = vi.fn();
    const sortableCols: ColumnDef<Row>[] = [
      { accessorKey: 'name', header: ({ column }) => <DataTableColumnHeader column={column} title="Name" />, cell: ({ row }) => row.original.name },
    ];
    render(
      <DataTable columns={sortableCols} data={data}
        serverPagination={{ page: 0, size: 20, total: 2, onPageChange: vi.fn(), onSizeChange: vi.fn(), sort: '', onSortChange }} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /name/i }));      // opens the sort dropdown
    fireEvent.click(screen.getByText('Asc'));
    expect(onSortChange).toHaveBeenCalledWith('name,asc');
  });
```
(Import `DataTableColumnHeader` from `@cia/ui`. If the DropdownMenu doesn't open in jsdom, fall back to asserting the controlled arrow renders for a given `sort` prop + that `onSortChange` is wired — keep the test meaningful but not brittle; resolve at execution.)

- [ ] **Step 5:** `pnpm --filter @cia/back-office build` + run `data-table-server` test → pass.
- [ ] **Step 6:** Commit: `feat(ui): DataTable server sort + toolbar server-search mode`.

---

### Task 2: Backend — Policy list `q` + combinable Specification + IT

**Files:** `PolicyRepository.java`, `PolicySpecs.java` (new), `PolicyService.java`, `PolicyController.java`; `UnderwritingWebItSupport.java` (new), `PolicyListControllerIT.java` (new).

**Reference to mirror:** `cia-finance/.../ReceiptSpecs.java` + `ReceiptService.findAll(spec, pageable)` + `cia-api/.../finance/ReceiptListControllerIT.java` + `FinanceWebItSupport.java`. **Read these first** to copy the exact idiom (null-returning factories, `Specification.where(deletedAtIsNull()).and(...)`, the mocked-bean set).

- [ ] **Step 1: Repository** — `PolicyRepository extends JpaRepository<Policy, UUID>, JpaSpecificationExecutor<Policy>` (add the second interface + import). Leave the existing derived + `@Query search` methods.

- [ ] **Step 2: `PolicySpecs`** (new — mirror `ReceiptSpecs`; every factory returns `null` when its arg is null/blank so callers compose unconditionally):
```java
package com.nubeero.cia.policy;

import com.nubeero.cia.policy.model.PolicyStatus; // adjust import to the actual PolicyStatus location
import org.springframework.data.jpa.domain.Specification;
import java.util.UUID;

public final class PolicySpecs {
  private PolicySpecs() {}

  public static Specification<Policy> notDeleted() {
    return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
  }
  public static Specification<Policy> statusEquals(PolicyStatus status) {
    return status == null ? null : (root, q, cb) -> cb.equal(root.get("status"), status);
  }
  public static Specification<Policy> customerIdEquals(UUID customerId) {
    return customerId == null ? null : (root, q, cb) -> cb.equal(root.get("customerId"), customerId);
  }
  public static Specification<Policy> qLike(String qStr) {
    if (qStr == null || qStr.isBlank()) return null;
    final String pat = "%" + qStr.toLowerCase() + "%";
    return (root, q, cb) -> cb.or(
      cb.like(cb.lower(root.get("policyNumber")),        pat),
      cb.like(cb.lower(root.get("customerName")),        pat),
      cb.like(cb.lower(root.get("productName")),         pat),
      cb.like(cb.lower(root.get("classOfBusinessName")), pat),
      cb.like(cb.lower(root.get("brokerName")),          pat),
      cb.like(cb.lower(root.get("quoteNumber")),         pat));
  }
}
```

- [ ] **Step 3: Service** — replace the if/else priority chain in `PolicyService.list(...)` with a combinable Specification, and widen the signature to accept `q`:
```java
public Page<PolicySummaryResponse> list(PolicyStatus status, UUID customerId, String q, Pageable pageable) {
  Specification<Policy> spec = Specification.where(PolicySpecs.notDeleted())
      .and(PolicySpecs.statusEquals(status))
      .and(PolicySpecs.customerIdEquals(customerId))
      .and(PolicySpecs.qLike(q));
  return repository.findAll(spec, pageable).map(this::toSummary);
}
```
Keep `search(...)` as-is (backward-compat).

- [ ] **Step 4: Controller** — add the `q` param + pass it through:
```java
public ApiResponse<List<PolicySummaryResponse>> list(
        @RequestParam(required = false) PolicyStatus status,
        @RequestParam(required = false) UUID customerId,
        @RequestParam(required = false) String q,
        @PageableDefault(size = 2000) Pageable pageable) {
    var page = service.list(status, customerId, q, pageable);
    return ApiResponse.success(page.getContent(),
        ApiMeta.builder().total(page.getTotalElements()).page(page.getNumber()).size(page.getSize()).build());
}
```

- [ ] **Step 5: Shared IT support** (`cia-api/src/test/.../underwriting/UnderwritingWebItSupport.java`) — mirror `FinanceWebItSupport`: `@SpringBootTest(webEnvironment = MOCK)` + `@AutoConfigureMockMvc` + the shared Testcontainers Postgres, with `@MockBean` for `JwtDecoder`, Temporal `WorkflowServiceStubs`/`WorkflowClient`/`WorkerFactory`, and `DocumentStorageService`. **Copy the exact `@MockBean` set from `FinanceWebItSupport` during execution** (it's the verified list of beans that keep Temporal/MinIO/Keycloak un-contacted).

- [ ] **Step 6: `PolicyListControllerIT`** (mirror `ReceiptListControllerIT`) — class-level `@WithMockUser(authorities = "ROLE_UNDERWRITING_VIEW")`; `JdbcTemplate` seeds `policies` rows (denormalised columns as in `PolicySelectedClausesIT`). Tests:
  - `paginationMetaIsPopulated`: seed 25 policies, `GET /api/v1/policies?page=0&size=10` → `$.data.length()==10`, `$.meta.total>=25`; `page=1` disjoint from `page=0`.
  - `qNarrowsResults`: seed 2 with distinct customerName, `?q=<substr>` → only the matching row; `$.meta.total==1`.
  - `statusAndQCombine`: `?status=ACTIVE&q=<substr>` narrows on both (proves combinability vs the old priority chain).
  - `sortByPolicyNumberDesc`: `?sort=policyNumber,desc` → first row is the lexicographically-largest.
  - (Optional) `forbiddenWithoutRole`: method-level wrong-role `@WithMockUser` → 403.

- [ ] **Step 7:** `mvn -q -pl cia-policy install -DskipTests` then `mvn -q -pl cia-api -am verify -Dit.test=PolicyListControllerIT -Dtest=none -DfailIfNoTests=false` (adjust to the repo's failsafe invocation) → green.

- [ ] **Step 8:** Commit: `feat(policy): combinable q/status list filter + server sort + list IT`.

---

### Task 3: Backend — Quote list `q` + Specification + IT

Mirror Task 2 in `cia-quotation` with these swaps:
- `QuoteRepository extends … JpaSpecificationExecutor<Quote>`.
- `QuoteSpecs` — same factories; **`qLike` columns = `quoteNumber, customerName, productName, brokerName, agentName`** (NO `classOfBusinessName` — matches the existing quote `/search`).
- `QuoteService.list(QuoteStatus status, UUID customerId, String q, Pageable)` → Specification compose; keep `search(...)`.
- `QuoteController.list(...)` add `@RequestParam(required=false) String q`; role `QUOTATION_VIEW`.
- `QuoteListControllerIT` (mirror `PolicyListControllerIT`, `ROLE_QUOTATION_VIEW`, seed `quotes`): pagination-meta, q-narrows, status+q-combine, `sort=quoteNumber,desc`.

- [ ] Steps 1–8 as Task 2 (repo → Specs → service → controller → IT). Commit: `feat(quotation): combinable q/status list filter + server sort + list IT`.

---

### Task 4: Backend — Endorsement list `q` + `endorsementType` + Specification + IT (greenfield search)

Mirror Task 2 in `cia-endorsement`, with the Endorsement specifics:
- `EndorsementRepository extends … JpaSpecificationExecutor<Endorsement>`.
- `EndorsementSpecs` — `notDeleted()`, `statusEquals(EndorsementStatus)`, `policyIdEquals(UUID)`, `customerIdEquals(UUID)`, **`endorsementTypeEquals(EndorsementType)`** (the unique second filter axis), and **greenfield `qLike` over `endorsementNumber, policyNumber, customerName`**.
- `EndorsementService.list(UUID policyId, EndorsementStatus status, EndorsementType endorsementType, UUID customerId, String q, Pageable)` → Specification compose (replaces the policyId→status→customerId priority chain). Returns `Page<Endorsement>` (the controller still maps via its inline `toResponse`).
- `EndorsementController.list(...)` add `@RequestParam(required=false) EndorsementType endorsementType` + `@RequestParam(required=false) String q`; keep the inline `toResponse` mapping + `ApiMeta`. Role `UNDERWRITING_VIEW`.
- `EndorsementListControllerIT` (`ROLE_UNDERWRITING_VIEW`, seed `endorsements`): pagination-meta, q-narrows, `endorsementType`+`status` combine, `sort=endorsementNumber,desc`.

- [ ] Steps 1–8 as Task 2. Commit: `feat(endorsement): combinable q/type/status list filter + server sort + list IT`.

---

### Task 5: Frontend — PolicyListPage → server pagination

**Files:** `policy/pages/PolicyListPage.tsx`.

- [ ] **Step 1:** Replace the fetch-all query with `validatedList` driven by `useServerPagination`. Imports: add `useServerPagination` (`@/lib/use-server-pagination`), `useDebouncedValue` (`@/lib/use-debounced-value`), `validatedList` (`@cia/api-client`), `useState` if needed for the raw search input. Replace:
```tsx
  const policiesQuery = useQuery<PolicySummaryDto[]>({
    queryKey: ['policies'],
    queryFn: () => validatedGet('/api/v1/policies', z.array(PolicySummaryDtoSchema)),
  });
  const policies = policiesQuery.data ?? [];
```
with:
```tsx
  const { page, size, sort, filters, setPage, setSize, setSort, setFilter } =
    useServerPagination({ defaultSize: 20, defaultSort: 'createdAt,desc' });
  const status = filters.status ?? '';
  const [searchInput, setSearchInput] = useState(filters.q ?? '');
  const debouncedSearch = useDebouncedValue(searchInput, 300);
  useEffect(() => { setFilter('q', debouncedSearch); /* eslint-disable-next-line */ }, [debouncedSearch]);

  const policiesQuery = useQuery({
    queryKey: ['policies', page, size, sort, status, filters.q ?? ''],
    queryFn: () => validatedList('/api/v1/policies', PolicySummaryDtoSchema, {
      params: {
        page, size, sort,
        ...(status ? { status } : {}),
        ...(filters.q ? { q: filters.q } : {}),
      },
    }),
  });
  const policies = policiesQuery.data?.data ?? [];
  const total    = policiesQuery.data?.meta.total ?? 0;
```

- [ ] **Step 2:** Wire the DataTable to server mode — `serverPagination` (with sort) + server-search toolbar; add a status filter control in the header/toolbar. Replace the `<DataTable … />` render:
```tsx
        <DataTable
          columns={columns}
          data={policies}
          toolbar={{ searchPlaceholder: 'Search policies…', searchValue: searchInput, onSearchChange: setSearchInput }}
          serverPagination={{
            page, size, total, onPageChange: setPage, onSizeChange: setSize,
            sort, onSortChange: setSort,
          }}
        />
```
Add a status `<Select>` (values ALL/DRAFT/PENDING/ACTIVE/… from `PolicySummaryDto['status']`) in the `PageHeader`/`PageSection` actions, `onValueChange={(v) => setFilter('status', v === 'ALL' ? '' : v)}`, `value={status || 'ALL'}`.

- [ ] **Step 3:** Ensure sortable columns use real entity property `accessorKey`s (policyNumber, customerName, productName, status, policyEndDate…) so `?sort=` maps; **disable sort on the computed "Intermediary" column** (`enableSorting: false`).

- [ ] **Step 4:** `pnpm --filter @cia/back-office build` + `check-api-wiring` + `check-dto-drift` → pass. (Note: `validatedList` returns `{data,meta}` — the list-page-binds-summary rule still holds; `PolicySummaryDtoSchema` unchanged.)

- [ ] **Step 5:** Commit: `feat(policy): PolicyListPage server pagination + search + sort`.

---

### Task 6: Frontend — QuotationListPage → server pagination

Mirror Task 5 in `quotation/pages/QuotationListPage.tsx` (`/api/v1/quotes`, `QuoteSummaryDtoSchema`, `['quotes', …]` queryKey, status values from `QuoteSummaryDto['status']`). The page already has `toolbar={{ searchColumn: 'customerName', … }}` — replace with the server-search toolbar (`searchValue`/`onSearchChange`). Keep the existing convert-to-policy mutation (`apiClient.post`) untouched.

- [ ] Steps 1–5 as Task 5. Commit: `feat(quotation): QuotationListPage server pagination + search + sort`.

---

### Task 7: Frontend — EndorsementsListPage → server pagination (+ type filter)

Mirror Task 5 in `endorsements/pages/EndorsementsListPage.tsx` (`/api/v1/endorsements`, `EndorsementDtoSchema`, `['endorsements', …]`). Two extras:
- The page binds the **full** `EndorsementDto` (there's no lean summary) — that's unchanged; `validatedList('/api/v1/endorsements', EndorsementDtoSchema, …)`.
- Add a **second filter control** for `endorsementType` (values from `ENDORSEMENT_TYPE_LABELS`) alongside status → `setFilter('endorsementType', …)`, threaded into the `params`.
- Replace `toolbar={{ searchColumn: 'policyNumber', … }}` with server-search.

- [ ] Steps 1–5 as Task 5, plus the `endorsementType` control + param. Commit: `feat(endorsement): EndorsementsListPage server pagination + search + type/status filters + sort`.

---

## Self-Review notes

- **Spec coverage:** §4 per-list contract (Policies/Quotes/Endorsements — `q` columns, filters, sort) = Tasks 2/3/4 (BE) + 5/6/7 (FE); §5 backend Specification/sort = Tasks 2–4; §3.3 server-sort + §4 toolbar-driven-server-side = Task 1; §7 per-endpoint 2-page + filter ITs = the ITs in Tasks 2–4. ✓
- **Type consistency:** FE `serverPagination.sort/onSortChange` extension (Task 1) consumed identically by Tasks 5–7; the `col,dir` string uses the DataTable column `accessorKey` == entity property == `?sort=` — one identifier end to end.
- **No placeholders:** FE infra (Task 1) + Policy backend exemplar (Task 2, full `PolicySpecs`/service/controller) + FE page exemplar (Task 5) are complete; Tasks 3/4/6/7 are explicit mirror-with-swaps of the exemplars (per the no-"similar-to" rule, the swaps are enumerated, not hand-waved) — during execution each is written out in full.
- **Execution reads (not placeholders — templates to copy exactly):** `ReceiptSpecs`, `ReceiptService.findAll`, `ReceiptListControllerIT`, `FinanceWebItSupport` (the verified `@MockBean` set) — read them at Task-2 start and mirror precisely; and confirm the exact `PolicyStatus`/`EndorsementType` import packages.
- **Scope discipline:** `/policies/search` + `/quotes/search` are **kept** (backward-compat); if they're now redundant, log a `*-search-endpoint-redundant` P3 rather than deleting in-slice. Config/master-data lists remain out of scope (client-side).
- **Backlog:** S5.2 removes no row; `list-endpoints-true-pagination` closes only when **S5.3** (Claims/Customers/Audit) lands. Add rows only for genuine side-discoveries (e.g. the redundant `/search` endpoints).
- **Sizing note:** this is a large slice (3 BE modules + FE infra + 3 FE pages + 3 ITs). It can ship as one PR or be committed/reviewed in module groups (Policy end-to-end → Quote → Endorsement) behind the shared Task-1 infra — offer the split at review.
