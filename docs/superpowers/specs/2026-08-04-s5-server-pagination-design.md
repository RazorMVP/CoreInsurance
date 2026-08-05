# S5 — True Server-Side Pagination (Option C) — Design Spec

**Status:** Approved (brainstorm 2026-08-04). Executed as a phased sub-slice series (S5.1 → S5.3).
**Backlog row closed on completion:** `list-endpoints-true-pagination` (the last category-C item).
**Goal:** Replace the "fetch every row and paginate in the browser" model on the genuinely-large transactional lists with true server-side pagination — page/size/sort/filters go to the API, the UI renders one page at a time — via shared, reusable infrastructure. Small config/master-data lists deliberately stay on the current client-side model.

---

## 1. Background — current state

- **DataTable (`@cia/ui`) is fully client-side.** It wires `getPaginationRowModel` + `getFilteredRowModel` + `getSortedRowModel` (TanStack defaults, no `manual*` modes). Every consuming page therefore fetches **all** rows (the `@PageableDefault(size = 2000)` cap set in Session 137's Option-B sweep) and paginates / searches / sorts in the browser.
- **The envelope is offset-based.** `ApiMeta = { page?, size?, total?, totalPages? }` (no cursor fields, despite CLAUDE.md's mention). Every internal list controller already takes Spring `Pageable` and populates `ApiMeta` (Session 137). `validatedList(url, schema)` already returns `{ data, meta }`.
- **Three lists already hand-roll server pagination:** `finance/pages/receivables/ReceiptsListSection.tsx`, `finance/pages/payables/PaymentsListSection.tsx`, `closures/pages/JournalEntryBrowserPage.tsx`. They each carry bespoke `{page,size}` state + a bespoke footer — three slightly-different implementations of the same idea.
- **All top-level list pages now fetch via `validatedGet`** (S4a-1/S4a-2/S4b, PRs #55/#58/#60) — so the fetch layer is already validated; S5 changes *what* is fetched (a page, not everything) and *how* the result's `meta` drives the UI.

**Why now / why phased:** the practical ceiling is real only for lists that accumulate without bound — Policies, Claims, Customers, and especially the **Audit Log** (every write appends a row). Config/master-data lists (brokers, vehicle makes, classes, users, …) are small by nature and will never approach 2000, so paying for server-side search on them is cost without benefit. S5 targets the transactional lists and leaves the rest client-side.

---

## 2. Scope decisions (approved)

| Decision | Choice |
|---|---|
| **Strategy** | Phased server-side — true pagination only for the large transactional lists; small config lists stay client-side on the existing cap. |
| **Phase-1 set** | **9 lists** = 3 refactors (finance Receipts, finance Payments, closures Journal Entries) + 6 new (Policies, Claims, Customers, Audit Log, Quotations, Endorsements). |
| **Search / filter contract** | Drive each list's **existing** toolbar controls server-side (per-list free-text `q` + the filters that page already exposes). Not a generic `q`-only; not a UX-dropping "pagination only". |
| **Sort** | Free via Spring `Pageable` (`?sort=col,dir` auto-parsed). Per-list sort columns are whitelisted (see §5). |
| **Customers `q`** | Plain columns only — `first_name`, `last_name`, `company_name`, `email`, `phone`. `id_number` / `address` are pgcrypto-encrypted `bytea` (V24) and not substring-searchable. Inherited NDPR constraint, not an S5 choice. |
| **List state** | **URL-synced** via react-router `useSearchParams` — page/size/sort/filters are bookmarkable, refresh-safe, back/forward-navigable. Defaults omitted from the URL; `replace` (not `push`) for **all** list-state mutations so the list stays a single browser-history entry (back-button leaves the list rather than stepping through filter keystrokes / visited pages); any filter/sort change resets to page 0. |
| **Sub-slicing** | S5.1 (infra + 3 refactors) → S5.2 (underwriting: Policies, Quotations, Endorsements) → S5.3 (Claims, Customers, Audit Log). Each its own PR + backlog reconciliation. |
| **Config/master-data lists** | Out of scope — stay client-side on the `size=2000` cap (Organisations, Vehicle Registry, Claims Config, Products, Users, Access/Approval Groups, Classes, Clause Bank, report pages). |

---

## 3. Shared infrastructure (built once in S5.1)

Three units, each with one responsibility. They live in `@cia/ui` (component + hook) so both apps can consume them; the hook is framework-state only (no data fetching — the page still owns its `useQuery`).

### 3.1 `useServerPagination` (hook, `@cia/ui`)

URL-backed list-state controller. **Consumes:** a config `{ defaultSize?, defaultSort?, filterKeys: string[] }`. **Produces:** the current `{ page, size, sort, filters }` + setters + a `toQueryString()` that serializes them for the API call.

- State is **read from and written to the URL** via `useSearchParams` (react-router-dom, already a dependency; all target pages are route components, so a Router context is always present).
- **Defaults omitted** from the URL — a fresh list shows a clean path, not `?page=0&size=20&sort=createdAt,desc`.
- **`replace` for keystroke-level changes** (the `q` text input) so typing doesn't spam browser history; `push`/`replace` is uniform in practice — we use `setSearchParams(next, { replace: true })` for all list-state mutations to keep history clean (one back-press leaves the list, not steps through filter keystrokes).
- **Page resets to 0** whenever `size`, `sort`, or any filter changes.
- Debounce is the page's concern (wire the `q` input through a small `useDebouncedValue`); the hook stays synchronous.

`toQueryString()` output example: `page=2&size=20&sort=createdAt,desc&q=acme&status=ACTIVE`.

### 3.2 `<ServerPaginationFooter>` (component, `@cia/ui`)

Presentational. **Consumes:** `{ page, size, total, onPageChange, onSizeChange, pageSizeOptions? }`. Renders "Showing X–Y of `total`", prev/next (disabled at bounds), current/total page count (derived from `total`/`size`), and a page-size selector. No data logic.

### 3.3 DataTable server mode (opt-in prop, `@cia/ui`)

A new optional prop on the existing `DataTable`:

```ts
serverPagination?: {
  page: number; size: number; total: number;
  onPaginationChange: (next: { page: number; size: number }) => void;
  sort?: string; onSortChange?: (sort: string) => void;
};
```

- **When present:** the table sets `manualPagination / manualFiltering / manualSorting: true`, **drops** the client `getPaginationRowModel` / `getFilteredRowModel` / `getSortedRowModel`, renders `<ServerPaginationFooter>` instead of the client pager, and routes header-sort clicks to `onSortChange`. The toolbar search box (if used) emits its value to the page rather than filtering rows locally.
- **When absent:** the component behaves **byte-identically to today** — client row models, client footer. This is the blast-radius control: every existing consumer (nested detail tables, config lists) is untouched because none pass the new prop.

**Blast-radius rule:** S5 must not change any DataTable rendering for a caller that doesn't opt in. An S5.1 acceptance check greps that no un-migrated DataTable usage changed behaviour (snapshot/visual parity on one nested table).

---

## 4. Per-list contract (the 9 lists)

**3 refactors — zero behaviour change** (they already server-paginate; S5.1 moves them onto the shared infra):

| List | Endpoint | Note |
|---|---|---|
| Finance Receipts | `/api/v1/receipts` | Already filtered server-side (status/method/date) via `JpaSpecificationExecutor` + `*Specs`. Swap bespoke pager → shared hook/footer; keep the existing filter params. |
| Finance Payments | `/api/v1/payments` | Same as Receipts. |
| Journal Entries | `/api/v1/finance/journal-entries` | Already server-paginated (status/source-module/account/business-date filters). Swap bespoke pager → shared infra. |

**6 new — add server pagination + drive the existing toolbar server-side:**

| List | Endpoint | Summary DTO | `q` search columns | Filters to plumb | Default sort |
|---|---|---|---|---|---|
| Policies | `/api/v1/policies` | `PolicySummaryDto` | policyNumber, customerName, productName | status | createdAt,desc |
| Quotations | `/api/v1/quotes` | `QuoteSummaryDto` | quoteNumber, customerName, productName | status | createdAt,desc |
| Endorsements | `/api/v1/endorsements` | `EndorsementDto` | endorsementNumber, policyNumber, customerName | status, endorsementType | createdAt,desc |
| Claims | `/api/v1/claims` | `ClaimDto` | claimNumber, customerName | status | createdAt,desc (or reportedDate,desc) |
| Customers | `/api/v1/customers` | `CustomerSummaryDto` | firstName, lastName, companyName, email, phone (**plain only**) | customerStatus | createdAt,desc |
| Audit Log | `/api/v1/audit/logs` | `AuditLogDto` | entityRef (optional) | entityType, action, userId/userName, dateFrom, dateTo | timestamp,desc |

Audit is the richest — its filters exist as client-side `useState` today (`dateFrom`/`dateTo`/entityType/action/user in `AuditLogTab`); S5.3 plumbs them to the endpoint. **First implementation step for Audit is to check whether `AuditLogController` already accepts these params** (it returns paged data + was built to filter) — if so, the change is mostly FE (send what the backend already takes) rather than new backend filtering.

---

## 5. Backend contract

- **Sort + meta are done** — controllers already take `Pageable` and populate `ApiMeta`. The per-list work is (a) a `q`/filter **Specification** and (b) ensuring the whitelisted **sort columns** map to real fields.
- **`q` Specification per endpoint:** a small `JpaSpecificationExecutor` predicate — `OR`-of-`ILIKE '%q%'` across the list's denormalised summary columns (§4). Finance + Audit already have Specification infra to mirror; the 5 underwriting/customer endpoints get one each (`Policy Specs`, `QuoteSpecs`, …).
- **Sort-column whitelist:** for JPA-`Specification` + `Pageable` reads, `sort` maps to entity fields automatically — but we still **whitelist** the allowed sort keys per list (reject unknown columns) to avoid leaking internal field names / erroring on a bad `?sort=`. If any target endpoint is backed by a **native** query, its sort column must be sanitized explicitly (mirror the `ReportQueryBuilder.sanitizeColumnName` whitelist pattern). Confirm per endpoint during the slice.
- **Contract invariant (unchanged):** list endpoints return the array in `data` and `total/page/size` in `meta` — never a `Page<T>` in `data` (the standing CLAUDE.md rule). The controllers already comply; S5 just relies on it.
- **No new envelope, no cursor.** Offset pagination only.

---

## 6. Sub-slicing (each its own PR + backlog reconciliation)

- **S5.1 — Shared infra + 3 refactors.** Build `useServerPagination` (URL-backed), `<ServerPaginationFooter>`, DataTable server mode; refactor the 3 hand-rolled pagers onto them with **zero behaviour change** (the proving ground for the shared DataTable mode against known-correct lists). No new backend work. FE component/hook tests. Ships the reusable infra; nothing user-visible changes.
- **S5.2 — Underwriting cluster.** Policies + Quotations + Endorsements. Per endpoint: `q` Specification + sort whitelist + 2-page/filter ITs; per page: adopt the shared infra + URL-sync + drive the toolbar server-side.
- **S5.3 — Claims + Customers + Audit Log.** Claims (simple `q`); Customers (PII-limited `q` + `customerStatus`); Audit Log (multi-filter — check existing server params first). Per-endpoint ITs as above.

Each new-list slice = FE adoption (small, mechanical once the infra exists) + one backend Specification + ITs. Slices are independently shippable and independently reviewable.

---

## 7. Testing

- **Backend (per new endpoint):** a **2-page IT** — seed > `size` rows, assert page 0 and page 1 are disjoint, correctly ordered, and `meta.total` is exact; a **filter IT** — assert `q` + each filter narrows the result set and `meta.total` reflects the filtered count. Finance/audit already have list ITs to extend.
- **Frontend:** a `useServerPagination` unit test (URL read/write, defaults omitted, page-reset-on-filter-change, `replace` semantics); a DataTable **server-mode** component test (renders `ServerPaginationFooter`, emits `onPaginationChange`/`onSortChange`, does **not** client-filter); and a **parity** check that a DataTable without `serverPagination` is unchanged.
- **E2E (optional, on the existing harness):** one golden path — load a large list, page forward, apply a filter, assert the URL reflects state and the row set changes.

---

## 8. Out of scope

- The ~30 dropdown/`Select` fetches and all config/master-data list pages (small by nature — stay client-side).
- Cursor-based pagination (offset only; the envelope has no cursor fields).
- Saved filter presets / column-visibility persistence.
- Cross-tenant / partner-API list pagination (internal `/api/v1` only).
- Server-side pagination for nested detail-page feeds (claim comments/docs/expenses, policy debit/credit notes) — they stay on the raised cap per the Session-137 note.

---

## 9. Risks & mitigations

| Risk | Mitigation |
|---|---|
| DataTable server mode regresses un-migrated tables (shared component) | Opt-in prop; absent = byte-identical behaviour; S5.1 parity check on a nested table. |
| Client-side search silently narrowed to the current page (the whole reason for §4) | Search/sort are driven server-side on every migrated list — never client-filter a server-paginated table. Enforced by the DataTable-server-mode test (no client `getFilteredRowModel`). |
| Customers search appears "broken" (can't find by address / ID) | Documented NDPR constraint; the search placeholder names the searchable fields ("name, email, phone"). |
| Bad `?sort=` column errors the query or leaks field names | Per-list sort whitelist (reject unknown); native-query lists sanitize like `ReportQueryBuilder`. |
| URL-sync history spam from typing in `q` | `replace` semantics + debounced `q` input. |
| Audit filters double-implemented (client + new server) | First step checks whether the controller already accepts the params; remove the client-side filtering when server-side lands. |

---

## 10. Open questions (resolve during S5.1/first slice, not blocking)

- Does `AuditLogController` already accept `entityType`/`action`/`user`/`dateFrom`/`dateTo` server-side? (Determines whether S5.3-Audit is FE-only plumbing or needs backend filtering.)
- Are any of the 6 target list endpoints backed by **native** queries (→ explicit sort-column sanitization) vs JPA `Specification` (→ automatic)? Confirm per endpoint at slice start.
- Page-size options — confirm the selector set (e.g. 20 / 50 / 100); default 20.
