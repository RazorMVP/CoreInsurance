# CIAGB Full Build Audit — 25 April 2026

**Auditor:** superpowers:code-reviewer subagent  
**Scope:** Entire CIAGB codebase — commits `11232ad` through `4badace` (93 commits)  
**Triggered by:** Manual `/audit the entire build` in Session 32  
**Outcome:** 2 criticals fixed same session · 4 important + 4 minor deferred (reasons documented below)

---

## Summary Scorecard

| Severity | Count | Status |
|---|---|---|
| Critical | 2 | ✅ Fixed (Session 32) |
| Important | 4 | 🔵 Deferred — see Section 3 |
| Minor | 4 | 🟡 Deferred — see Section 4 |
| **Total** | **10** | |

---

## 1. Strengths Confirmed

The reviewer confirmed the following were correctly implemented:

- **Multi-tenant routing** — `TenantIdentifierResolver` correctly defaults to `"public"` schema when no JWT/tenant context is present. `MultiTenantConnectionProvider.getConnection(tenantId)` calls `setSchema()` correctly. No cross-tenant data leakage path exists through the application layer.
- **DevSecurityConfig isolation** — `@Profile("dev")` + `@Order(1)` ensures the JWT bypass never activates in production. `SecurityConfig` (undecorated, default order) takes over in all non-dev profiles.
- **ReportChart Jackson fix** — `@JsonProperty("xAxis")` and `@JsonProperty("yAxis")` correctly force the JSON field names, defending against Lombok's `getXAxis()` → `Introspector.decapitalize("XAxis")` → `XAxis` property name mismatch.
- **ReportConfigConverter resilience** — `FAIL_ON_UNKNOWN_PROPERTIES=false` configured so future JSONB schema additions in V19+ never crash deserialization.
- **ReportPdfRenderer never-throw contract** — The entire `render()` body is wrapped in a single `try/catch(Exception e)` that returns `null`. The contract is honoured.
- **ReportQueryBuilder no business module imports** — Zero imports from `cia-policy`, `cia-claims`, or any other business module. Uses `EntityManager.createNativeQuery()` directly. Adding a new pre-built report is a data migration, not a code change.
- **SQL injection prevention** — `sanitizeColumnName()` whitelists `[a-zA-Z0-9_.]` for the ORDER BY clause. Parameterised `?N` placeholders used for all filter values.
- **Flyway migrations V1–V18** — No gaps in sequence. V17 columns align exactly with `BaseEntity` (`id`, `created_at`, `updated_at`, `created_by`, `deleted_at`). V18 seeds exactly 55 SYSTEM reports (12 + 13 + 9 + 8 + 5 + 8 = 55).
- **Badge variants** — `@cia/ui` exposes `default | outline | active | pending | rejected | draft | cancelled`. No invalid `secondary` variant used anywhere in the codebase.
- **Breadcrumb** — `Breadcrumb` correctly takes `items: BreadcrumbItem[]` prop in `@cia/ui`. Used correctly in `ReportViewerPage`.
- **Pin icons** — `Pin01Icon` (non-existent in hugeicons v4.1.1) not used anywhere. Correct `Bookmark01Icon` and `BookmarkRemove01Icon` used throughout.
- **Dev stack wiring** — Vite proxy → `localhost:8090`, `.env.local` with `VITE_API_BASE_URL=` (empty), `.gitignore` includes `.env.local`.
- **React Query hooks** — Consistent structure: `useQuery` for reads, `useMutation` for writes, `queryClient.invalidateQueries` in `onSuccess`.
- **Build Queue** — Phase 2 correctly marked 10/10 complete in `CLAUDE.md`. Phase 3 (Partner Portal) correctly shows 0/5.

---

## 2. Critical Issues — Fixed Session 32

### C1 — `ReportQueryBuilder`: Datasource-aware filter aliases

**File:** `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`  
**Commit fixed:** `d4d0b85`

**Problem:**  
`date_from`, `date_to`, and `status` filter clauses unconditionally appended `p.created_at` and `p.status` (POLICIES table alias). Three of six datasources do not have a `p` alias in their base SQL:

| Datasource | Primary alias | `p` present? |
|---|---|---|
| POLICIES | `p` | ✅ yes |
| CLAIMS | `cl` | ❌ no — `p` is joined but refers to policy, not claim |
| FINANCE | `dn` | ❌ no `p` |
| REINSURANCE | `ria` | ❌ no `p` |
| CUSTOMERS | `c` | ❌ no `p` |
| ENDORSEMENTS | `e` | ✅ `p` is joined |

**Impact before fix:**  
Running `Active Customers` (K01) or `KYC Status Report` (K04) with any date filter → PostgreSQL `ERROR: missing FROM-clause entry for table "p"` → HTTP 500 to user. Same structural problem for FINANCE and REINSURANCE datasources.

**Fix applied:**  
Added three helper methods:
- `createdAtCol(DataSource)` — maps each datasource to its primary table's `created_at` alias
- `statusCol(DataSource)` — maps each datasource to its `status` column alias
- `hasCobJoin(DataSource)` — returns true only for datasources that JOIN `class_of_business` (POLICIES, CLAIMS, ENDORSEMENTS)

`class_of_business_id` and `product_id` filters are now guarded to skip silently when the datasource does not include the relevant JOIN, rather than appending a WHERE clause that references a non-existent alias.

---

### C2 — `ReportQueryBuilder`: Missing `utilisation_pct` computed field

**File:** `cia-backend/cia-reports/src/main/java/com/nubeero/cia/reports/service/ReportQueryBuilder.java`  
**Commit fixed:** `d4d0b85`

**Problem:**  
The `applyComputedFields()` switch handled: `loss_ratio`, `combined_ratio`, `expense_ratio`, `retention_pct`, `cession_pct`, `conversion_pct` — but not `utilisation_pct`. The `Treaty Utilisation` (R03) SYSTEM report in V18 declares `utilisation_pct` as a computed field. The default branch logged `"Unknown computed field"` and left the value null. Every row of the Treaty Utilisation report showed null for its primary analytical column.

**Impact before fix:**  
`Treaty Utilisation` (R03) — one of eight REINSURANCE SYSTEM reports — silently returned null for `utilisation_pct`. No runtime error, but the report was functionally broken on delivery.

**Fix applied:**  
Added `case "utilisation_pct" -> map.put("utilisation_pct", computeRatio(map, "ceded_amount", "retained_amount"))` to the computed field switch.

---

## 3. Important Issues — Deferred

### I1 — No row limit on JSON endpoint (`ReportQueryBuilder`)

**File:** `ReportQueryBuilder.java`, line 127 (`query.getResultList()` with no `setMaxResults`)  
**Risk:** On a large tenant with 50,000+ policies, `Policy Register` (U04) with no filters attempts to load all rows into memory, serialize to JSON, and send to the browser. `ReportResultTable` renders all rows with no client-side pagination.

**Why deferred:**  
Requires a product decision on what the acceptable limit should be (5,000? 10,000?) and whether users should see a truncation warning or be required to apply filters. The CSV streaming path should remain unlimited. Implementing cleanly requires a `@Value("${reports.max-json-rows:5000}")` config value, a truncation response flag in `ReportResultDto`, and a UI warning component in `ReportResultTable`. This is a focused 1-hour task but needs the product decision made first.

**Suggested fix when ready:**
```java
// ReportQueryBuilder
query.setMaxResults(maxJsonRows); // inject @Value("${reports.max-json-rows:5000}")

// ReportResultDto — add field:
boolean truncated;

// ReportResultTable — show warning when truncated = true
```

---

### I2 — `Clone & Edit` navigates to blank builder instead of cloning

**File:** `cia-frontend/apps/back-office/src/modules/reports/pages/library/ReportLibraryPage.tsx`, line 52  
**Current behaviour:** Clicking "Clone & Edit" on a SYSTEM report navigates to `/reports/custom` (blank 3-step builder). The user sees an empty form with no pre-populated data from the source report.

**Why deferred:**  
A correct implementation requires two changes:
1. Call `POST /api/v1/reports/definitions/{id}/clone` when "Clone & Edit" is clicked — the backend endpoint already exists and works correctly.
2. In `CustomReportBuilderPage`, when `id` is present in `useParams`, fetch the existing definition and pre-populate all three steps. Currently `id` is read from `useParams` but no fetch call is made.

Both changes are needed together (the clone creates an ID, the builder needs to load from it). This is a ~45-minute task and should be done as a focused frontend session.

---

### I3 — `ReportAccessSetupPage` uses hardcoded mock access groups

**File:** `cia-frontend/apps/back-office/src/modules/reports/pages/setup/ReportAccessSetupPage.tsx`, lines 10–15  
**Current behaviour:** The "Select Access Group" dropdown shows four hardcoded groups (`Underwriters`, `Claims Officers`, `Finance Officers`, `Management`) with fabricated UUIDs. These will never match real access groups in the system.

**Why deferred:**  
Requires wiring to `/api/v1/setup/access-groups` — a GET endpoint that already exists in `cia-setup`. Needs a `useAccessGroups` hook in `@cia/api-client` (or inline in the page). The fix is straightforward but requires confirming the response shape from `cia-setup` before writing the hook to ensure correct typing. Estimated 30 minutes.

**Suggested fix:**
```typescript
// Replace MOCK_GROUPS with:
const { data: groups = [] } = useQuery({
  queryKey: ['setup', 'access-groups'],
  queryFn: () => apiClient.get('/api/v1/setup/access-groups').then(r => r.data.data),
});
```

---

### I4 — No unit tests in `cia-reports` module

**Location:** `cia-backend/cia-reports/src/test/java/` (directory exists, zero test files)  
**CLAUDE.md requirement:** 80% line coverage on backend business logic.

**Why deferred:**  
Writing meaningful tests for `ReportQueryBuilder` requires a Testcontainers PostgreSQL setup (same pattern used in other modules). The test suite should cover:
- Each `DataSource` with date filters — verifying no SQL alias crash
- `SYSTEM` report protection in `ReportDefinitionService` (update/delete blocked)
- `ReportCsvRenderer` — RFC 4180 output and UTF-8 BOM
- `ReportAccessService` permission resolution order (report-level > category-level > deny)

This is a dedicated 2–3 hour test-writing session. Not suitable for mid-audit addition.

---

## 4. Minor Issues — Deferred

### M1 — `recentlyRun` hardcoded to empty array

**File:** `ReportsHomePage.tsx`, line 68 (`const recentlyRun: ReportDefinition[] = []`)  
**Impact:** The "Recently Run" section never renders. Dead code path.

**Why deferred:**  
Needs a design decision: track in `localStorage` (simple, per-device) or a new `user_report_history` backend table (accurate, cross-device). Cannot implement without that decision. Not user-visible since the section only renders when `recentlyRun.length > 0`.

---

### M2 — V18 idempotency comment is misleading

**File:** `V18__seed_system_report_definitions.sql`, line 2  
**Current comment:** `"Safe to run multiple times — DELETE+INSERT on name+type ensures idempotency"`  
**Actual behaviour:** Re-running V18 cascades `DELETE FROM report_definition WHERE type = 'SYSTEM'` to `report_pin` and `report_access_policy` via `ON DELETE CASCADE` — destroying all user pins and access policies for SYSTEM reports.

**Why deferred:**  
Comment-only fix with zero functional impact. Flyway's checksum enforcement prevents re-runs in production. Will be corrected in the next migration-related session.

**Correct comment:** `"NOT safe to re-run in production — Flyway checksum prevents re-execution. Cascade DELETE would destroy report_pin and report_access_policy rows."`

---

### M3 — `MULTI_SELECT` filter type renders as plain text input

**File:** `ReportFilterForm.tsx`, line 55  
**Impact:** Reports with `class_of_business_id` or `product_id` filters (e.g. `Gross Written Premium` U01) show a text box where the user must type a UUID directly. Not functional for non-technical users.

**Why deferred:**  
Proper fix needs:
1. `<Select>` component populated from `/api/v1/setup/classes-of-business` and `/api/v1/setup/products`
2. Backend `ReportQueryBuilder` extended to support IN clause for multi-value selection

This is a meaningful feature improvement, not a quick fix. Scheduled for Phase 3 / reporting polish session.

---

### M4 — JPA positional parameter syntax (`?1`, `?2`)

**File:** `ReportQueryBuilder.java`, SQL parameter binding  
**Issue:** Appending the index integer after `?` character (`"?".append(paramIdx++)`) is non-standard in some JPA tutorials but is correct JPA native query positional parameter syntax. Works with Hibernate. May not be compatible with all JPA providers.

**Why deferred:**  
Valid, working, and Hibernate-specific compatibility is acceptable given the stack. No action needed unless the project ever migrates away from Hibernate.

---

## 5. Build Queue Completeness at Audit Date

| Phase | Status |
|---|---|
| Phase 1 — Infrastructure (5/5) | ✅ Complete |
| Phase 2 — Back Office Modules (10/10) | ✅ Complete (including Module 11) |
| Phase 3 — Partner Portal (0/5) | 🔵 Not started |
| **Total** | **15/20 (75%)** |

**Build 11 sub-page accuracy (per reviewer):**
- Reports Home: ✅ present and functional
- Report Library: ⚠️ present — Clone & Edit broken (I2 above)
- Report Viewer: ✅ present and wired
- Custom Report Builder: ⚠️ present — edit mode (`/reports/custom/:id`) does not pre-populate form (linked to I2)
- Report Access Setup: ⚠️ present — access groups mocked (I3 above)
- Backend `cia-reports` module: ✅ complete (post C1 + C2 fixes)
- Flyway V17/V18: ✅ present and correct

---

## 6. Follow-up Actions (Prioritised)

| Priority | Action | Estimated Effort |
|---|---|---|
| High | Fix `Clone & Edit` + pre-populate edit mode in builder (I2) | 45 min |
| High | Wire real access groups in `ReportAccessSetupPage` (I3) | 30 min |
| Medium | Add row limit to JSON report endpoint (I1) | 30 min + product decision |
| Medium | Write `cia-reports` unit tests: QueryBuilder, DefinitionService, CsvRenderer, AccessService (I4) | 2–3 hours |
| Low | Replace `recentlyRun` empty array with real tracking (M1) | Design decision first |
| Low | Fix V18 comment (M2) | 5 min |
| Low | Upgrade `MULTI_SELECT` filter to `<Select>` component (M3) | 1–2 hours |
