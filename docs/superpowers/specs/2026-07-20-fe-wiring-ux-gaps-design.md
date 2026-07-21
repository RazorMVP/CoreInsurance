# Frontend Wiring / UX Gaps (backlog category C) — Sequencing Design

**Status:** Approved (brainstorm 2026-07-20). Executed slice-by-slice.
**Goal:** Close the 7 "frontend wiring / UX gaps" backlog rows as an ordered set of independently-shippable slices, cheapest/highest-value first.
**Context:** Sizing investigation (cia-log 2026-07-20) found **5 of the 7 need no backend build** — the endpoints already exist; the gaps are unwired or mock-gated frontends.

---

## Scope decisions (approved)

- **All 7 in scope**, but **true pagination (S5) goes last** — after the other six close.
- **Setup dead-shells (S3): full CRUD per tab**, mirroring the existing Setup → Organisations tabs (backend supports create/update/soft-delete; read-only would be a half-measure).
- **validatedGet sweep (S4): true top-level list pages first** (~15-20 that render a table over the array — the actual white-screen risk). The ~30 dropdown/select fetches are a lower-risk opportunistic continuation, not this slice.

## Slice sequence

| Slice | Goal | Backlog rows closed | Backend? |
|---|---|---|---|
| **S1** | Quick wins | `audit-list-page-size-cap`, `reports-frontend-datasource-union-sync` | audit controllers only |
| **S2** | Quotation FE wiring | `quote-detail-uses-live-config`, `quote-list-pdf-mock-gated` | none |
| **S3** | Setup dead-shells → full CRUD (6 tabs) | `setup-dead-shells` | none |
| **S4** | validatedGet sweep (list pages) | `raw-apiclient-list-validatedget-sweep` (list-page portion) | none |
| **S5** | True server-side pagination (Option C) | `list-endpoints-true-pagination` | FE + BE |

Each slice is its own PR + backlog reconciliation. S1-S4 close six rows before S5 begins.

### S1 — Quick wins (trivial)
- **audit-list-page-size-cap:** bump `@PageableDefault(size = 20)` → `2000` in the 4 audit controllers (`AuditLogController`, `LoginAuditController`, `AuditAlertController`, `AuditReportController` — ~9 occurrences), matching the internal-list `size=2000` convention so the audit tabs (which filter client-side with no pager) see the full set, not the first 20.
- **reports-frontend-datasource-union-sync:** add `RM_COMMISSION` + `UNDERWRITING_PERFORMANCE` to the FE `DataSource` union (`reports/types/report.types.ts`) to match the backend enum. **Not** added to `DATA_SOURCE_OPTIONS` (both are fixed-shape aggregate substrates deliberately excluded from the custom-report-builder picker).

### S2 — Quotation FE wiring (small, FE-only)
- **quote-detail-uses-live-config:** replace the 3 `MOCK_*` imports in `QuoteDetailPage` with `useQuery` against the live `/api/v1/setup/quote-{config,discount-types,loading-types}` endpoints (they exist).
- **quote-list-pdf-mock-gated:** drop the `mockQuotePdfData[id]` gate on the list-row "Download PDF"; reuse the detail page's existing client-side PDF path (fetch the full quote via the detail endpoint, render `QuotePdfPreview`) so the action works for real quotes.

### S3 — Setup dead-shells → full CRUD (medium, FE-only)
Build real CRUD tabs against the existing backend for the two placeholder pages:
- `ClaimsConfigPage`: reserve categories (`/api/v1/setup/claim-reserve-categories`), notification timelines (`.../products/{id}/claim-notification-timeline`), loss types.
- `VehicleRegistryPage`: makes (`/api/v1/setup/vehicle-makes`), models (`.../vehicle-makes/{id}/models`), types (`/api/v1/setup/vehicle-types`).
Mirror the existing Setup → Organisations tab pattern (DataTable + create/edit Sheet + `ConfirmDeleteDialog` + `useDeleteWithReason`). Splittable into S3a claims-config / S3b vehicle-registry. Gets its own `writing-plans`.

### S4 — validatedGet sweep, list pages (large, FE-only)
Migrate the true top-level list pages from raw `apiClient.get(...).then(r => r.data.data)` to `validatedGet`/`validatedList` (envelope-drift-immune, per the audit-tab precedent). Add a per-module parse regression test where warranted (`audit-envelope-parse.test.ts` is the pattern). Dropdown/select fetches deferred. Gets its own `writing-plans`.

### S5 — True server-side pagination (very large, ~2 wk, FE + BE)
Option C: a shared `useServerPagination` / `ServerPaginationFooter` in `@cia/ui`, wire `{page,size}` through the ~13 top-level list pages (16 call sites incl. the 3 existing hand-rolled pagers), + per-endpoint 2-page backend ITs. The backend meta half is already done (Session 137 Option B). Nested detail-feeds stay on the raised cap. Its own spec + plan when reached.

## Testing

Per slice: FE `check-api-wiring` + `check-dto-drift` + build + Vitest; backend changes (S1 audit, S5) run the relevant module/reactor ITs. S1's audit bump is covered by the existing audit ITs (paging assertions); S4 adds parse regression tests; S5 adds pagination ITs.

## Out of scope

Backend pagination redesign beyond Option C · migrating the ~30 dropdown/select raw fetches (opportunistic) · any new report data sources.
