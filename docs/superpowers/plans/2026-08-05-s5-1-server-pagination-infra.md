# S5.1 — Server-Pagination Shared Infra + 3 Pager Refactors — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the reusable server-pagination infrastructure (URL-backed `useServerPagination` hook, `<ServerPaginationFooter>`, an opt-in `serverPagination` mode on `DataTable`) and refactor the 3 existing hand-rolled pagers (finance Receipts, finance Payments, closures Journal Entries) onto it — proving the infra against known-correct lists with no change to which rows appear per page.

**Architecture:** Pure frontend. Spec: `docs/superpowers/specs/2026-08-04-s5-server-pagination-design.md`. The infra has two independently-usable halves: (1) `useServerPagination` (URL-backed `{page,size,sort,filters}`) + `<ServerPaginationFooter>` — usable by any list, incl. raw-`<table>` pagers; (2) a `serverPagination` prop on `DataTable` that, when present, flips `manualPagination` on, drops the client pagination row model, and renders `<ServerPaginationFooter>` instead of the client pager (client sort/filter of the current page is preserved). **Server sort (`manualSorting`) is deliberately NOT in S5.1** — it's an additive extension for S5.2's large lists.

**Tech Stack:** React + TanStack Table + react-router-dom v6.28 (`useSearchParams`) + Vitest.

## Global Constraints

- **FE-only.** The 3 target endpoints already server-paginate; no backend change.
- **Zero behaviour change to *which rows appear*.** The one new behaviour is **URL-sync** of list state (spec §2) — additive, applied to all 3 refactors for consistency.
- **DataTable blast radius:** the `serverPagination` prop is **opt-in**. Absent ⇒ `DataTable` behaves byte-identically to today (client pagination/filter/sort). A parity test enforces this.
- **`replace` (not `push`)** for all URL-state writes (list stays one history entry); **defaults omitted** from the URL; any filter/sort/size change **resets page to 0**.
- `check-api-wiring` + `check-dto-drift` (unaffected) + `pnpm --filter @cia/back-office build` + `pnpm --filter @cia/ui build` + full back-office Vitest must pass.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## File Structure

- **Create** `packages/ui/src/components/data-table/use-server-pagination.ts` — the URL-backed hook.
- **Create** `packages/ui/src/components/data-table/use-server-pagination.test.ts` — hook unit test.
- **Create** `packages/ui/src/components/data-table/server-pagination-footer.tsx` — presentational footer.
- **Modify** `packages/ui/src/components/data-table/data-table.tsx` — add opt-in `serverPagination` prop (manual pagination only).
- **Create** `packages/ui/src/components/data-table/data-table-server.test.tsx` — server-mode + parity component test.
- **Modify** `packages/ui/src/index.ts` — export `useServerPagination`, `ServerPaginationFooter`, their types.
- **Modify** `apps/back-office/src/modules/finance/pages/receivables/ReceiptsListSection.tsx` — adopt shared infra.
- **Modify** `apps/back-office/src/modules/finance/pages/payables/PaymentsListSection.tsx` — adopt shared infra.
- **Modify** `apps/back-office/src/modules/closures/pages/JournalEntryBrowserPage.tsx` — adopt shared infra (raw table).

---

### Task 1: `useServerPagination` hook (URL-backed)

**Files:** Create `packages/ui/src/components/data-table/use-server-pagination.ts` + `.test.ts`.

**Interfaces:**
- Consumes: react-router `useSearchParams`.
- Produces: `useServerPagination(config?) → { page, size, sort, filters, setPage, setSize, setSort, setFilter, resetFilters, toQueryString, pageSizeOptions }`. Consumed by Tasks 3–5.

- [ ] **Step 1: Write the hook.** Reserved keys `page`/`size`/`sort`; everything else in the URL is a "filter". Defaults omitted; `replace` writes; page resets on size/sort/filter change.

```ts
import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';

export interface ServerPaginationConfig {
  defaultSize?:     number;                    // default 20
  pageSizeOptions?: number[];                  // default [10, 20, 50, 100]
  defaultSort?:     string;                    // e.g. 'createdAt,desc'; omitted from URL when equal
  filterDefaults?:  Record<string, string>;    // filter key → default; value === default is omitted from URL
}

export interface ServerPaginationState {
  page:    number;
  size:    number;
  sort:    string | undefined;
  filters: Record<string, string>;
  setPage:      (p: number) => void;
  setSize:      (s: number) => void;
  setSort:      (s: string) => void;
  setFilter:    (key: string, value: string) => void;
  resetFilters: () => void;
  toQueryString: () => string;
  pageSizeOptions: number[];
}

const RESERVED = new Set(['page', 'size', 'sort']);

export function useServerPagination(config: ServerPaginationConfig = {}): ServerPaginationState {
  const { defaultSize = 20, pageSizeOptions = [10, 20, 50, 100], defaultSort, filterDefaults = {} } = config;
  const [params, setParams] = useSearchParams();

  const page = Number(params.get('page') ?? '0') || 0;
  const size = Number(params.get('size') ?? String(defaultSize)) || defaultSize;
  const sort = params.get('sort') ?? defaultSort;

  const filters = useMemo(() => {
    const out: Record<string, string> = {};
    params.forEach((v, k) => { if (!RESERVED.has(k)) out[k] = v; });
    return out;
  }, [params]);

  // Rewrite the URL from a plain next-state, omitting defaults, replacing history.
  const write = (next: { page: number; size: number; sort: string | undefined; filters: Record<string, string> }) => {
    const sp = new URLSearchParams();
    if (next.page !== 0) sp.set('page', String(next.page));
    if (next.size !== defaultSize) sp.set('size', String(next.size));
    if (next.sort && next.sort !== defaultSort) sp.set('sort', next.sort);
    for (const [k, val] of Object.entries(next.filters)) {
      if (val !== '' && val !== (filterDefaults[k] ?? '')) sp.set(k, val);
    }
    setParams(sp, { replace: true });
  };

  const setPage = (p: number)  => write({ page: p, size, sort, filters });
  const setSize = (s: number)  => write({ page: 0, size: s, sort, filters });          // reset page
  const setSort = (s: string)  => write({ page: 0, size, sort: s, filters });          // reset page
  const setFilter = (key: string, value: string) =>
    write({ page: 0, size, sort, filters: { ...filters, [key]: value } });             // reset page
  const resetFilters = () => write({ page: 0, size, sort, filters: {} });

  const toQueryString = () => {
    const sp = new URLSearchParams();
    sp.set('page', String(page));
    sp.set('size', String(size));
    if (sort) sp.set('sort', sort);
    for (const [k, val] of Object.entries(filters)) if (val !== '') sp.set(k, val);
    return sp.toString();
  };

  return { page, size, sort, filters, setPage, setSize, setSort, setFilter, resetFilters, toQueryString, pageSizeOptions };
}
```

- [ ] **Step 2: Write the test** (`use-server-pagination.test.ts`) — render the hook inside a `MemoryRouter`, drive it, assert URL + returned state:

```ts
import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import React from 'react';
import { useServerPagination } from './use-server-pagination';

function wrapper(initial = '/') {
  return ({ children }: { children: React.ReactNode }) =>
    React.createElement(MemoryRouter, { initialEntries: [initial] }, children);
}

// Expose the current URL search alongside the hook for assertions.
function useHarness(config?: Parameters<typeof useServerPagination>[0]) {
  const sp = useServerPagination(config);
  const loc = useLocation();
  return { sp, search: loc.search };
}

describe('useServerPagination', () => {
  it('defaults are omitted from the URL', () => {
    const { result } = renderHook(() => useHarness(), { wrapper: wrapper('/') });
    expect(result.current.sp.page).toBe(0);
    expect(result.current.sp.size).toBe(20);
    act(() => result.current.sp.setPage(0));
    expect(result.current.search).toBe(''); // page 0 + default size → clean URL
  });

  it('writes non-default page/size to the URL', () => {
    const { result } = renderHook(() => useHarness(), { wrapper: wrapper('/') });
    act(() => result.current.sp.setPage(2));
    expect(result.current.search).toContain('page=2');
    act(() => result.current.sp.setSize(50));
    expect(result.current.search).toContain('size=50');
    expect(result.current.search).not.toContain('page='); // size change reset page to 0 (omitted)
  });

  it('setFilter resets page to 0 and reads back as a filter', () => {
    const { result } = renderHook(() => useHarness(), { wrapper: wrapper('/?page=3') });
    act(() => result.current.sp.setFilter('status', 'ACTIVE'));
    expect(result.current.sp.filters.status).toBe('ACTIVE');
    expect(result.current.search).toContain('status=ACTIVE');
    expect(result.current.search).not.toContain('page='); // reset
  });

  it('toQueryString includes page, size, sort, filters', () => {
    const { result } = renderHook(() => useHarness({ defaultSort: 'createdAt,desc' }), { wrapper: wrapper('/?status=POSTED') });
    const qs = result.current.sp.toQueryString();
    expect(qs).toContain('page=0');
    expect(qs).toContain('size=20');
    expect(qs).toContain('sort=createdAt%2Cdesc');
    expect(qs).toContain('status=POSTED');
  });
});
```

- [ ] **Step 3:** Run: `pnpm --filter @cia/ui test -- use-server-pagination` → all pass. (If `@cia/ui` has no vitest yet, run via the back-office binary: `pnpm --filter @cia/back-office exec vitest run packages/ui/src/components/data-table/use-server-pagination.test.ts --root ../../packages/ui` — confirm the ui package's test setup during the slice; if absent, co-locate the test run under back-office's vitest by importing from the built package.)

- [ ] **Step 4:** Commit: `feat(ui): useServerPagination — URL-backed list state`.

---

### Task 2: `<ServerPaginationFooter>` + barrel exports

**Files:** Create `packages/ui/src/components/data-table/server-pagination-footer.tsx`; modify `packages/ui/src/index.ts`.

**Interfaces:** Consumes nothing. Produces `ServerPaginationFooter` + `ServerPaginationFooterProps`. Consumed by Tasks 3–5.

- [ ] **Step 1: Write the component** (mirrors `data-table-pagination.tsx`'s controls, but driven by `{page,size,total}` props, not a TanStack table):

```tsx
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';
import { Button } from '../button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../select';

export interface ServerPaginationFooterProps {
  page:            number;                 // 0-based
  size:            number;
  total:           number;
  onPageChange:    (p: number) => void;
  onSizeChange:    (s: number) => void;
  pageSizeOptions?: number[];              // default [10, 20, 50, 100]
}

export function ServerPaginationFooter({
  page, size, total, onPageChange, onSizeChange, pageSizeOptions = [10, 20, 50, 100],
}: ServerPaginationFooterProps) {
  const pageCount = Math.max(1, Math.ceil(total / size));
  const from = total === 0 ? 0 : page * size + 1;
  const to   = Math.min(total, (page + 1) * size);
  const canPrev = page > 0;
  const canNext = page < pageCount - 1;

  return (
    <div className="flex items-center justify-between px-1">
      <div className="text-xs text-muted-foreground">
        Showing {from}–{to} of {total.toLocaleString()}
      </div>
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          <p className="text-xs font-medium text-muted-foreground">Rows per page</p>
          <Select value={`${size}`} onValueChange={(v) => onSizeChange(Number(v))}>
            <SelectTrigger className="h-8 w-16 text-xs"><SelectValue placeholder={size} /></SelectTrigger>
            <SelectContent side="top">
              {pageSizeOptions.map((sz) => (
                <SelectItem key={sz} value={`${sz}`} className="text-xs">{sz}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <p className="text-xs font-medium text-muted-foreground">Page {page + 1} of {pageCount}</p>
        <div className="flex items-center gap-1">
          <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => onPageChange(0)} disabled={!canPrev}><ChevronsLeft className="h-4 w-4" /></Button>
          <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => onPageChange(page - 1)} disabled={!canPrev}><ChevronLeft className="h-4 w-4" /></Button>
          <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => onPageChange(page + 1)} disabled={!canNext}><ChevronRight className="h-4 w-4" /></Button>
          <Button variant="outline" size="icon" className="h-7 w-7" onClick={() => onPageChange(pageCount - 1)} disabled={!canNext}><ChevronsRight className="h-4 w-4" /></Button>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2:** Add to `packages/ui/src/index.ts` (near the other data-table exports, lines 72–78):

```ts
export { ServerPaginationFooter } from './components/data-table/server-pagination-footer';
export type { ServerPaginationFooterProps } from './components/data-table/server-pagination-footer';
export { useServerPagination } from './components/data-table/use-server-pagination';
export type { ServerPaginationConfig, ServerPaginationState } from './components/data-table/use-server-pagination';
```

- [ ] **Step 3:** `pnpm --filter @cia/ui build` → pass.
- [ ] **Step 4:** Commit: `feat(ui): ServerPaginationFooter + barrel exports`.

---

### Task 3: DataTable opt-in `serverPagination` prop

**Files:** Modify `packages/ui/src/components/data-table/data-table.tsx`; create `data-table-server.test.tsx`.

**Interfaces:** Consumes `ServerPaginationFooter` (Task 2). Produces the extended `DataTable` prop. Consumed by Task 4.

- [ ] **Step 1: Extend `DataTableProps` + wire manual pagination.** Add the import + prop + conditional row model + conditional footer. Only `manualPagination` is flipped (client sort/filter of the current page stay — zero behaviour change for the refactors):

```tsx
// add to imports:
import { ServerPaginationFooter, type ServerPaginationFooterProps } from './server-pagination-footer';

interface DataTableProps<TData, TValue> {
  columns:    ColumnDef<TData, TValue>[];
  data:       TData[];
  toolbar?:   Omit<DataTableToolbarProps<TData>, 'table'>;
  className?: string;
  /** Present ⇒ server-driven pagination: the client pagination row model is
   *  dropped and ServerPaginationFooter replaces the client pager. Client
   *  sort/filter of the current page are preserved (manualSorting is a
   *  future extension). Absent ⇒ fully client-side (unchanged). */
  serverPagination?: Omit<ServerPaginationFooterProps, 'pageSizeOptions'> & { pageSizeOptions?: number[] };
}
```

In the component body, make the pagination row model conditional and swap the footer:

```tsx
  const table = useReactTable({
    data,
    columns,
    state: { sorting, columnFilters, columnVisibility, rowSelection },
    manualPagination: !!serverPagination,
    enableRowSelection:      true,
    onRowSelectionChange:    setRowSelection,
    onSortingChange:         setSorting,
    onColumnFiltersChange:   setColumnFilters,
    onColumnVisibilityChange:setColumnVisibility,
    getCoreRowModel:         getCoreRowModel(),
    getFilteredRowModel:     getFilteredRowModel(),
    getSortedRowModel:       getSortedRowModel(),
    ...(serverPagination ? {} : { getPaginationRowModel: getPaginationRowModel() }),
  });
```

Replace the footer line:

```tsx
      {serverPagination
        ? <ServerPaginationFooter {...serverPagination} />
        : <DataTablePagination table={table} />}
```

(Destructure `serverPagination` in the function params alongside `columns, data, toolbar, className`.)

- [ ] **Step 2: Component test** (`data-table-server.test.tsx`) — server mode renders the server footer + emits `onPageChange`; parity: without the prop the client pager renders. Mock nothing (real `@cia/ui`); minimal columns/data.

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';
import { DataTable } from './data-table';
import type { ColumnDef } from '@tanstack/react-table';

type Row = { id: string; name: string };
const columns: ColumnDef<Row>[] = [{ accessorKey: 'name', header: 'Name', cell: ({ row }) => row.original.name }];
const data: Row[] = [{ id: '1', name: 'Alpha' }, { id: '2', name: 'Beta' }];

describe('DataTable server mode', () => {
  it('renders the server footer + total, and emits onPageChange', () => {
    const onPageChange = vi.fn();
    render(<DataTable columns={columns} data={data}
      serverPagination={{ page: 0, size: 20, total: 57, onPageChange, onSizeChange: vi.fn() }} />);
    expect(screen.getByText(/Showing 1–2 of 57/)).toBeInTheDocument();
    // next-page button (last of the nav buttons is "last page"; first enabled "next")
    const nextBtns = screen.getAllByRole('button');
    fireEvent.click(nextBtns[nextBtns.length - 2]); // ChevronRight (next)
    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it('without serverPagination, renders the client pager (parity)', () => {
    render(<DataTable columns={columns} data={data} />);
    expect(screen.getByText(/row\(s\)/)).toBeInTheDocument(); // client DataTablePagination text
    expect(screen.queryByText(/Showing 1–/)).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 3:** `pnpm --filter @cia/ui build` + run the component test → pass.
- [ ] **Step 4:** Commit: `feat(ui): DataTable opt-in serverPagination (manual pagination + server footer)`.

---

### Task 4: Refactor Receipts + Payments onto the shared infra

**Files:** Modify `ReceiptsListSection.tsx`, `PaymentsListSection.tsx`.

Both have the same shape: `const [page, setPage] = useState(0)` → `useReceiptList({ status, page, size: 20 })` → `<DataTable .../>` + a bespoke `Showing {n} of {meta.total}` + Prev/Next footer. Refactor: source `page`/`size`/`status` from `useServerPagination` (URL-synced), pass `serverPagination` to `DataTable`, delete the bespoke footer (DataTable now renders the single server footer).

- [ ] **Step 1 (Receipts):** Replace the pager state + status state with the hook. Replace
```tsx
  const [page, setPage] = useState(0);
  // ...status state...
  const receiptsQuery = useReceiptList({ status, page, size: 20 });
```
with
```tsx
  const { page, size, filters, setPage, setSize, setFilter } = useServerPagination({ defaultSize: 20 });
  const status = (filters.status as 'POSTED' | 'REVERSED' | undefined) || undefined;
  const receiptsQuery = useReceiptList({ status, page, size });
```
Import `useServerPagination` from `@cia/ui`. Change the status `Select` `onValueChange` from `setStatus(...); setPage(0)` to `setFilter('status', v === 'ALL' ? '' : v)` (the hook resets page).

- [ ] **Step 2 (Receipts):** Pass `serverPagination` to `<DataTable>` and delete the bespoke footer block (`<span>Showing {receipts.length} of {meta.total}…</span>` + the two Prev/Next `<Button>`s):
```tsx
        <DataTable
          columns={columns}
          data={receipts}
          serverPagination={{ page, size, total: meta.total ?? 0, onPageChange: setPage, onSizeChange: setSize }}
        />
```
(Remove the now-dead `<div>…Previous…Next…</div>` footer that followed the DataTable.)

- [ ] **Step 3 (Payments):** Apply the identical transformation to `PaymentsListSection.tsx` (`usePaymentList`, `payments`, same status filter + footer).

- [ ] **Step 4:** `pnpm --filter @cia/back-office build` + `bash cia-frontend/scripts/check-api-wiring.sh` → pass. Manually confirm (reading the diff) that the row set per page is unchanged — only the footer source + URL-sync differ.

- [ ] **Step 5:** Commit: `refactor(finance): Receipts + Payments onto shared server-pagination infra`.

---

### Task 5: Refactor JournalEntryBrowserPage (raw table)

**Files:** Modify `closures/pages/JournalEntryBrowserPage.tsx`.

This page renders a raw `<table>` with 5 filters (`status`, `sourceModule`, `accountCode`, `businessFrom`, `businessTo`), `const [page, setPage] = useState(0)`, `pageSize = 20`, and its own Prev/Next footer; it reads a `Page`-shaped `pageData.content/.totalElements/.totalPages`. Refactor: move `page` + the 5 filters into `useServerPagination`, keep the raw table, replace the bespoke footer with `<ServerPaginationFooter>`. Keep reading `content/totalElements` from the existing query (no fetch-shape change in this slice — only the state source + footer change).

- [ ] **Step 1:** Replace the `useState` filter block + `page` state:
```tsx
  const { page, size, filters, setPage, setSize, setFilter } = useServerPagination({ defaultSize: 20 });
  const status       = (filters.status as StatusFilter) || 'ALL';
  const sourceModule = filters.sourceModule ?? '';
  const accountCode  = filters.accountCode  ?? '';
  const businessFrom = filters.businessFrom ?? '';
  const businessTo   = filters.businessTo   ?? '';
```
Import `useServerPagination` + `ServerPaginationFooter` from `@cia/ui`.

- [ ] **Step 2:** Point each filter control at `setFilter` (which resets page). E.g. `onValueChange={(v) => setFilter('status', v)}`, `onChange={(e) => setFilter('sourceModule', e.target.value)}`, etc. Update the query-param builder (`useMemo`) to read `page`/`size` from the hook (it already builds from these locals). Keep the existing `listQuery` shape.

- [ ] **Step 3:** Replace the bespoke footer (`{totalPages > 1 && (…Previous…Next…)}`) with:
```tsx
      {totalElements > 0 && (
        <ServerPaginationFooter
          page={page} size={size} total={totalElements}
          onPageChange={setPage} onSizeChange={setSize}
        />
      )}
```
(The StatCards reading `page + 1 / totalPages` may stay or be dropped — keep them; they still read the hook's `page`.)

- [ ] **Step 4:** `pnpm --filter @cia/back-office build` + `check-api-wiring` → pass.

- [ ] **Step 5:** Run the full back-office Vitest suite → all pass, coverage floors hold (`pnpm --filter @cia/back-office test`).

- [ ] **Step 6:** Commit: `refactor(closures): Journal Entries onto shared server-pagination infra`.

---

## Self-Review notes

- **Spec coverage:** infra units (§3.1/3.2/3.3) = T1/T2/T3; the 3 refactors (§6 S5.1) = T4 (Receipts+Payments, DataTable server mode) + T5 (Journal Entries, standalone hook+footer). URL-sync (§2) is in the hook (T1) and inherited by every refactor. ✓
- **Type consistency:** `ServerPaginationFooterProps` shape is reused verbatim by the DataTable `serverPagination` prop; `useServerPagination` return type consumed unchanged by T4/T5.
- **No placeholders:** hook, footer, DataTable change, and both tests are complete; refactors give exact before/after for the state + footer swap.
- **Scope discipline:** `manualSorting`/`manualFiltering` server modes + the `q`-search-via-toolbar wiring are **explicitly deferred to S5.2** (noted in Architecture) — S5.1 flips only `manualPagination`, so the finance/journal refactors are behaviour-preserving apart from additive URL-sync.
- **Open item to confirm at execution:** whether `@cia/ui` has a vitest runner (Task 1 Step 3 / `api-client-vitest-infra` P3 backlog row notes packages lack their own vitest); if not, run the two `@cia/ui` tests via the back-office vitest against the source files, or add a minimal `vitest.config.ts` to `@cia/ui` as part of T1.
- **Backlog:** S5.1 removes no row (the `list-endpoints-true-pagination` row closes only when S5.3 lands the last new list). Add no row unless a side-discovery surfaces (e.g. the JournalEntry `Page`-shape-vs-`{data,meta}` inconsistency — if confirmed, log it P3 rather than fixing it in this slice).
