# Reports & Analytics Module — Design Specification

**Date:** 2026-04-25
**Status:** Approved
**Module:** Reports & Analytics (Module 11)
**Build:** New module — Backend + Frontend

---

## Overview

The Reports & Analytics module provides a centralised, permission-controlled reporting layer across all CIAGB modules. It ships 55 pre-built named reports covering underwriting, claims, finance, reinsurance, customer, and Nigerian regulatory (NAICOM/NIID) requirements, plus a custom report builder that lets authorised users create, save, and visualise ad-hoc reports.

Every report — pre-built and custom — is a `ReportDefinition` stored in the database as a JSON config. A single `ReportRunner` service executes all reports against the tenant's PostgreSQL schema. Access is controlled at two levels: category and per-report, both configured through the existing Access Groups setup.

---

## Architecture Decision: Unified Report Definition Engine

All reports are `ReportDefinition` entities with a JSONB `config` field. The `ReportRunnerService` reads the definition, builds a parameterised query, executes it tenant-scoped, applies computed field formulas, and renders to screen (JSON), CSV, or PDF.

Pre-built reports are seeded as `SYSTEM` type via Flyway migration — they cannot be deleted, only cloned into `CUSTOM` reports for user modification. This means adding a new regulatory report is a data migration, not a code change.

---

## Route & Navigation

| Item | Value |
|---|---|
| Route prefix | `/reports` |
| Nav group | **REPORTS** (new top-level group in sidebar) |
| Nav label | `Reports` |
| Nav icon | `ChartBarIcon` (hugeicons) |

### Child routes

| Path | Component | Description |
|---|---|---|
| `/reports` | `ReportsHomePage` | Pinned cards + recent + quick-access by category |
| `/reports/library` | `ReportLibraryPage` | Full searchable list of all accessible reports |
| `/reports/custom` | `CustomReportBuilderPage` | New custom report (blank builder) |
| `/reports/custom/:id` | `CustomReportBuilderPage` | Edit existing custom report |
| `/reports/run/:id` | `ReportViewerPage` | Run any report — filter form + results + export |
| `/reports/setup` | `ReportAccessSetupPage` | Admin only — grant category + per-report rights |

---

## Page Structure

### ReportsHomePage
```
ReportsHomePage
├── Pinned Reports row     (up to 6 user-pinned report cards with mini chart preview)
├── Recently Run           (last 5 reports this user ran, with timestamps)
├── Quick Access by category
│   ├── Underwriting       (4 most common pre-built reports)
│   ├── Claims
│   ├── Finance
│   ├── Reinsurance
│   ├── Customer
│   └── Regulatory         (all NAICOM/NIID reports listed — high priority)
└── + New Custom Report    (CTA button → /reports/custom)
```

### ReportLibraryPage
Search bar + category filter tabs (All / Underwriting / Claims / Finance / Reinsurance / Customer / Regulatory). Each report shown as a card: name, description, category badge, last run timestamp. Click → `/reports/run/:id`.

### CustomReportBuilderPage
3-step horizontal stepper:
- **Step 1:** Data source selector (Policies / Claims / Finance / Reinsurance / Customers / Endorsements)
- **Step 2:** Fields & Filters — available fields panel (left) + selected fields list with drag-to-reorder (right) + filter picker + group-by / sort-by selects
- **Step 3:** Visualisation — chart type (Table only / Bar / Line / Pie) + x/y axis selects + report name + category + Save & Run

### ReportViewerPage
Dynamic filter form (built from `config.filters`) → Run button → DataTable + chart (Recharts). Export bar: `[ Export CSV ]  [ Export PDF ]  [ Pin / Unpin ]`.

### ReportAccessSetupPage
Access group selector + expandable category/report permission matrix. Each category row has View / Export CSV / Export PDF checkboxes. Individual reports shown on expand — inherit category by default (`—`), can be individually overridden.

---

## Data Model

### Tables

```sql
-- V17__create_reports_tables.sql

CREATE TABLE report_definition (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name          VARCHAR(200) NOT NULL,
  description   TEXT,
  category      VARCHAR(50) NOT NULL,  -- UNDERWRITING | CLAIMS | FINANCE | REINSURANCE | CUSTOMER | REGULATORY
  type          VARCHAR(20) NOT NULL,  -- SYSTEM | CUSTOM
  data_source   VARCHAR(50) NOT NULL,  -- POLICIES | CLAIMS | FINANCE | REINSURANCE | CUSTOMERS | ENDORSEMENTS
  config        JSONB NOT NULL,
  is_pinnable   BOOLEAN NOT NULL DEFAULT TRUE,
  created_by    UUID,
  is_active     BOOLEAN NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE report_pin (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id        UUID NOT NULL,
  report_id      UUID NOT NULL REFERENCES report_definition(id) ON DELETE CASCADE,
  display_order  INT NOT NULL DEFAULT 0,
  created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, report_id)
);

CREATE TABLE report_access_policy (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  access_group_id  UUID NOT NULL,
  category         VARCHAR(50),        -- NULL = report-level only
  report_id        UUID REFERENCES report_definition(id) ON DELETE CASCADE,  -- NULL = category-level only
  can_view         BOOLEAN NOT NULL DEFAULT FALSE,
  can_export_csv   BOOLEAN NOT NULL DEFAULT FALSE,
  can_export_pdf   BOOLEAN NOT NULL DEFAULT FALSE,
  created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT category_or_report CHECK (category IS NOT NULL OR report_id IS NOT NULL)
);

CREATE INDEX idx_report_definition_category ON report_definition(category);
CREATE INDEX idx_report_definition_type ON report_definition(type);
CREATE INDEX idx_report_pin_user ON report_pin(user_id);
CREATE INDEX idx_report_access_policy_group ON report_access_policy(access_group_id);
```

### ReportConfig JSONB shape

```json
{
  "fields": [
    { "key": "class_of_business", "label": "Class", "type": "STRING" },
    { "key": "gross_written_premium", "label": "GWP (₦)", "type": "MONEY" },
    { "key": "claims_incurred", "label": "Claims Incurred (₦)", "type": "MONEY" },
    { "key": "loss_ratio", "label": "Loss Ratio (%)", "type": "PERCENT", "computed": true }
  ],
  "filters": [
    { "key": "date_from", "label": "Date From", "type": "DATE", "required": true },
    { "key": "date_to",   "label": "Date To",   "type": "DATE", "required": true },
    { "key": "class_of_business_id", "label": "Class", "type": "MULTI_SELECT", "required": false },
    { "key": "product_id", "label": "Product",  "type": "MULTI_SELECT", "required": false }
  ],
  "groupBy": "class_of_business",
  "sortBy":  "loss_ratio",
  "sortDir": "DESC",
  "chart": {
    "type": "BAR",
    "xAxis": "class_of_business",
    "yAxis": "loss_ratio"
  }
}
```

Field types: `STRING | MONEY | PERCENT | DATE | NUMBER | INTEGER`
Filter types: `DATE | DATE_RANGE | SELECT | MULTI_SELECT | TEXT | NUMBER`
Chart types: `BAR | LINE | PIE | TABLE_ONLY`

### Computed Fields (pre-defined, not user-formula)

| Key | Formula |
|---|---|
| `loss_ratio` | `claims_incurred / premium_earned × 100` |
| `combined_ratio` | `(claims_incurred + expenses) / premium_earned × 100` |
| `expense_ratio` | `expenses / premium_earned × 100` |
| `retention_pct` | `retained_si / gross_si × 100` |
| `cession_pct` | `ceded_si / gross_si × 100` |
| `utilisation_pct` | `capacity_used / total_capacity × 100` |
| `conversion_pct` | `bound_quotes / total_quotes × 100` |

---

## Pre-built Report Catalogue (55 reports)

All seeded via `V18__seed_system_report_definitions.sql` as `type = SYSTEM`.

### UNDERWRITING (12 reports)

| ID | Name | Key Fields | Key Filters |
|---|---|---|---|
| U01 | Gross Written Premium | Period, Class, Product, Branch, GWP | Date range, Class, Product, Branch |
| U02 | Net Written Premium | GWP, RI Ceded, NWP | Date range, Class, Product |
| U03 | Premium Earned vs Unearned | Earned, Unearned, Policy count | Date range, Class |
| U04 | Policy Register | Policy No, Customer, Class, Product, SI, Premium, Status, Expiry | Date range, Class, Product, Status |
| U05 | Renewal Due Report | Policy No, Customer, Expiry, Premium, Renewal Status | Days to expiry, Class, Product |
| U06 | Quote-to-Bind Conversion | Quotes raised, Converted, Lapsed, Conversion % | Date range, Class, Product, Underwriter |
| U07 | New Business vs Renewal | New GWP, Renewal GWP, Retention rate | Date range, Class, Product |
| U08 | Policy Lapse Report | Lapsed policies, Premium at risk, Lapse rate | Date range, Class, Product, Broker |
| U09 | Debit Note Analysis | DN No, Policy, Customer, Amount, Status, Due Date | Date range, Status, Class |
| U10 | Sum Insured Exposure | Class, Product, Total SI, Policy count | Date range, Class, Product |
| U11 | Coinsurance Report | Policy No, Type, Share %, Co-insurer | Date range, Class |
| U12 | Premium Instalment Schedule | Policy No, Customer, Instalment amounts, Due dates | Date range, Status |

### CLAIMS (13 reports)

| ID | Name | Key Fields | Key Filters |
|---|---|---|---|
| C01 | Claims Register | Claim No, Policy, Customer, Class, Status, Reserve, Paid, Incurred | Date range, Status, Class |
| C02 | Loss Ratio Report | Class, Product, Premium Earned, Claims Incurred, Loss Ratio % | Date range, Class, Product |
| C03 | Claims Incurred Report | Opened, Reserves, Expenses, Total Incurred | Date range, Class |
| C04 | Outstanding Claims | Claim No, Age (days), Reserve, Class, Status | Date range, Class, Ageing bucket |
| C05 | Claims Settled | Claim No, Settlement amount, DV No, Settlement date | Date range, Class |
| C06 | Claims Ageing Report | 0–30, 31–60, 61–90, 90+ day buckets, count & reserve per bucket | Date range, Class |
| C07 | Reserve Movement | Claim No, Opening reserve, Additions, Releases, Closing reserve | Date range, Class |
| C08 | Claims Expense Report | Claim No, Expense type, Amount, Approved by | Date range, Expense type |
| C09 | Large Loss Report | Claim No, Policy, Incurred amount, Status | Date range, Minimum amount threshold |
| C10 | Claims by Nature / Cause | Nature of loss, Cause, Frequency, Total incurred | Date range, Class |
| C11 | Recovery Report | Claim No, Recovery type, Amount, Date | Date range, Class |
| C12 | Survey / Inspection Backlog | Claim No, Survey assigned, Days outstanding, Surveyor | Date range, Surveyor |
| C13 | Missing Documents Report | Claim No, Missing doc names, Days outstanding | Date range, Class |

### FINANCE (9 reports)

| ID | Name | Key Fields | Key Filters |
|---|---|---|---|
| F01 | Receivables Ageing | DN No, Customer, Amount, Due date, Age bucket | Date range, Ageing bucket |
| F02 | Collections Report | Receipt No, DN No, Customer, Amount, Method, Date | Date range, Method |
| F03 | Outstanding Premium | DN No, Policy, Customer, Amount, Days overdue | Date range, Days overdue |
| F04 | Payables Report | CN No, Beneficiary, Type, Amount, Status | Date range, Type, Status |
| F05 | Payments Report | Payment No, CN No, Beneficiary, Amount, Method, Date | Date range, Method |
| F06 | Commission Statement | Broker, Policy count, GWP, Commission rate, Commission amount | Date range, Broker |
| F07 | Cash Flow Report | Period, Expected inflows, Outflows, Net | Date range |
| F08 | Combined Ratio Report | Period, Loss Ratio, Expense Ratio, Combined Ratio | Date range, Class |
| F09 | Bank Reconciliation Summary | Period, Receipts posted, Payments made, System balance | Date range |

### REINSURANCE (8 reports)

| ID | Name | Key Fields | Key Filters |
|---|---|---|---|
| R01 | RI Premium Bordereaux | Policy No, Treaty, Reinsurer, Ceded premium, Period | Date range, Treaty, Reinsurer |
| R02 | RI Claims Bordereaux | Claim No, Treaty, Reinsurer, Ceded loss, Period | Date range, Treaty, Reinsurer |
| R03 | Treaty Utilisation | Treaty, Type, Capacity, Used, Remaining, Utilisation % | Date range, Treaty type |
| R04 | Facultative Register | FAC No, Policy, Reinsurer, Type, Share %, Status | Date range, Type, Status |
| R05 | RI Recovery Report | Claim No, Treaty, Reinsurer, Recovery amount, Date | Date range, Treaty, Reinsurer |
| R06 | Cession Statement | Reinsurer, Ceded premium, Ceded claims, Balance | Date range, Reinsurer |
| R07 | Retention vs Cession Summary | Class, Gross SI, Retained SI, Ceded SI, Retention % | Date range, Class |
| R08 | Excess Capacity Report | Policy No, Gross SI, Treaty capacity, Excess, FAC status | Date range |

### CUSTOMER (5 reports)

| ID | Name | Key Fields | Key Filters |
|---|---|---|---|
| K01 | Active Customers | Customer, Type, Channel, Policy count, GWP | Date range, Type, Channel |
| K02 | Customer Loss Ratio | Customer, Premium, Claims, Loss Ratio % | Date range, Class |
| K03 | Customer Policy History | Policy No, Class, Product, Premium, Status, Period | Customer, Date range |
| K04 | KYC Status Report | Customer, Type, KYC status, Date | KYC status |
| K05 | Broker Performance | Broker, Policies placed, GWP, Claims, Loss ratio, Commission | Date range |

### REGULATORY — NAICOM / NIID (8 reports)

| ID | Name | Frequency | Key Content |
|---|---|---|---|
| N01 | Annual Revenue Account | Annually | Premium earned, Claims incurred, Expenses, Profit — per class |
| N02 | Annual Balance Sheet | Annually | Assets, liabilities, shareholders' funds |
| N03 | Quarterly Prudential Return | Quarterly | Solvency margin, Premium reserves, Investment positions |
| N04 | RI Quarterly Returns | Quarterly | Ceded premium and claims by treaty and reinsurer |
| N05 | Premium Bordereaux (NAICOM) | Annually | Policy-level premium register in NAICOM prescribed format |
| N06 | Claims Bordereaux (NAICOM) | Annually | Claim-level loss register in NAICOM prescribed format |
| N07 | NIID Upload Status Report | On demand | Motor/marine policies — upload status, NIID IDs, failures |
| N08 | Investment Statement | Annually | All investments — type, value, yield, % of total assets |

> Regulatory reports (N01–N08) generate PDFs matching NAICOM's prescribed format. `is_pinnable = false`. Cannot be cloned into custom reports — structure is fixed by regulation.

---

## Access Control

### Module-level permissions (Keycloak roles → Spring authorities)

| Authority | Description |
|---|---|
| `reports:view` | Can access the Reports module |
| `reports:export_csv` | Can export any accessible report to CSV |
| `reports:export_pdf` | Can export any accessible report to PDF |
| `reports:create_custom` | Can open the custom builder and save custom reports |
| `reports:manage_access` | Can open `/reports/setup` — System Admin only |

### Category + per-report access (report_access_policy)

Resolution rules — most specific wins:
1. User lacks `reports:view` → block entirely
2. Report-level policy exists for user's access group → apply it
3. Category-level policy exists → apply it
4. Neither → deny (report invisible to user)

### Visibility rule
Reports the user cannot view do not appear in the library or home page — no "access denied" state, simply invisible. Export buttons rendered only when the user has the corresponding export permission for that specific report.

---

## Backend Module Structure

### Maven module: `cia-reports`

```
cia-reports/
└── src/main/java/com/nubeero/cia/reports/
    ├── domain/
    │   ├── ReportDefinition.java
    │   ├── ReportConfig.java          (Jackson POJO mapped from JSONB)
    │   ├── ReportField.java
    │   ├── ReportFilter.java
    │   ├── ReportChart.java
    │   ├── ReportPin.java
    │   └── ReportAccessPolicy.java
    ├── repository/
    │   ├── ReportDefinitionRepository.java
    │   ├── ReportPinRepository.java
    │   └── ReportAccessPolicyRepository.java
    ├── service/
    │   ├── ReportDefinitionService.java   (CRUD + clone SYSTEM → CUSTOM)
    │   ├── ReportRunnerService.java       (core: load → build query → execute → render)
    │   ├── ReportAccessService.java       (resolves effective permissions for user + report)
    │   ├── ReportQueryBuilder.java        (builds native SQL from ReportConfig)
    │   ├── ReportCsvRenderer.java         (RFC 4180 CSV stream)
    │   ├── ReportPdfRenderer.java         (PDFBox + embedded JFreeChart PNG)
    │   └── ReportSeedService.java         (seeds 55 SYSTEM definitions on startup — idempotent)
    ├── controller/
    │   └── ReportController.java
    └── dto/
        ├── ReportDefinitionDto.java
        ├── ReportRunRequest.java          ({ reportId, filters: {date_from, ...}, format })
        └── ReportResultDto.java           ({ columns, rows, chart?, totalRows })
```

### Dependencies
- `cia-common` — TenantContext, BaseEntity, ApiResponse
- `cia-auth` — JWT, access group resolution
- **Not** dependent on any business module — `ReportQueryBuilder` uses `EntityManager.createNativeQuery()` against the tenant schema directly

### REST endpoints (`/api/v1/reports/`)

| Method | Path | Authority required |
|---|---|---|
| GET | `/definitions` | `reports:view` |
| GET | `/definitions/:id` | `reports:view` |
| POST | `/definitions` | `reports:create_custom` |
| PUT | `/definitions/:id` | `reports:create_custom` |
| DELETE | `/definitions/:id` | `reports:create_custom` (SYSTEM type blocked) |
| POST | `/definitions/:id/clone` | `reports:create_custom` |
| POST | `/run` | `reports:view` |
| POST | `/run/csv` | `reports:export_csv` |
| POST | `/run/pdf` | `reports:export_pdf` |
| GET | `/pins` | `reports:view` |
| POST | `/pins/:id` | `reports:view` |
| DELETE | `/pins/:id` | `reports:view` |
| GET | `/access-policies` | `reports:manage_access` |
| PUT | `/access-policies` | `reports:manage_access` |

### Report rendering
- **Screen:** `ReportRunnerService` returns `ReportResultDto` (JSON)
- **CSV:** `ReportCsvRenderer` streams RFC 4180 via `StreamingResponseBody`
- **PDF:** `ReportPdfRenderer` uses Apache PDFBox (already in stack) for layout + table; JFreeChart renders chart as PNG embedded in PDF. 100% server-side — no Headless Chrome.

### Flyway migrations
- `V17__create_reports_tables.sql` — creates `report_definition`, `report_pin`, `report_access_policy`
- `V18__seed_system_report_definitions.sql` — inserts all 55 SYSTEM report definitions as JSON

### Assembly (`cia-api`)
- Add `cia-reports` as Maven dependency
- `ReportSeedService` runs on `ApplicationReadyEvent` — idempotent (checks `type = SYSTEM` before inserting)
- Flyway picks up V17 and V18 automatically

---

## Frontend Module Structure

```
src/modules/reports/
├── index.tsx
├── layout/
│   └── ReportsLayout.tsx
├── pages/
│   ├── home/
│   │   └── ReportsHomePage.tsx
│   ├── library/
│   │   └── ReportLibraryPage.tsx
│   ├── builder/
│   │   ├── CustomReportBuilderPage.tsx
│   │   ├── steps/
│   │   │   ├── Step1DataSource.tsx
│   │   │   ├── Step2FieldsFilters.tsx
│   │   │   └── Step3Visualisation.tsx
│   │   └── components/
│   │       ├── FieldPickerPanel.tsx
│   │       ├── SelectedFieldsList.tsx
│   │       └── ComputedFieldBadge.tsx
│   ├── viewer/
│   │   ├── ReportViewerPage.tsx
│   │   ├── ReportFilterForm.tsx      (dynamic form built from config.filters)
│   │   ├── ReportResultTable.tsx     (DataTable with typed columns: money, percent, date)
│   │   ├── ReportChart.tsx           (Recharts: BarChart | LineChart | PieChart | null)
│   │   └── ReportExportBar.tsx       (Export CSV | Export PDF | Pin/Unpin)
│   └── setup/
│       └── ReportAccessSetupPage.tsx
├── hooks/
│   ├── useReportDefinitions.ts
│   ├── useRunReport.ts
│   ├── useReportPins.ts
│   └── useReportAccessPolicies.ts
└── types/
    └── report.types.ts
```

### Chart library
**Recharts** — lightweight, React-native, composable. Three chart types driven entirely by `config.chart`:

```tsx
switch (config.chart.type) {
  case 'BAR':  return <BarChart  data={rows} xKey={xAxis} yKey={yAxis} />
  case 'LINE': return <LineChart data={rows} xKey={xAxis} yKey={yAxis} />
  case 'PIE':  return <PieChart  data={rows} nameKey={xAxis} valueKey={yAxis} />
  default:     return null  // TABLE_ONLY
}
```

### ReportViewerPage flow
1. Load report definition → render `ReportFilterForm` (dynamic from `config.filters`)
2. User fills filters → clicks "Run Report"
3. `POST /run` → spinner → `ReportResultTable` + `ReportChart`
4. `ReportExportBar`: CSV trigger `POST /run/csv`, PDF trigger `POST /run/pdf`, pin toggle `POST|DELETE /pins/:id`

---

## Files to Create

### Backend
| File | Purpose |
|---|---|
| `cia-reports/` | New Maven module |
| `cia-reports/src/.../domain/ReportDefinition.java` | JPA entity |
| `cia-reports/src/.../domain/ReportConfig.java` | JSONB POJO |
| `cia-reports/src/.../domain/ReportPin.java` | JPA entity |
| `cia-reports/src/.../domain/ReportAccessPolicy.java` | JPA entity |
| `cia-reports/src/.../service/ReportRunnerService.java` | Core runner |
| `cia-reports/src/.../service/ReportQueryBuilder.java` | Native SQL builder |
| `cia-reports/src/.../service/ReportCsvRenderer.java` | CSV output |
| `cia-reports/src/.../service/ReportPdfRenderer.java` | PDF output (PDFBox + JFreeChart) |
| `cia-reports/src/.../service/ReportSeedService.java` | Seeds 55 definitions |
| `cia-reports/src/.../controller/ReportController.java` | 14 REST endpoints |
| `db/migration/V17__create_reports_tables.sql` | Schema |
| `db/migration/V18__seed_system_report_definitions.sql` | 55 SYSTEM definitions |

### Backend — Modify
| File | Change |
|---|---|
| `cia-api/pom.xml` | Add `cia-reports` dependency |

### Frontend
| File | Purpose |
|---|---|
| `modules/reports/index.tsx` | Lazy route registration |
| `modules/reports/pages/home/ReportsHomePage.tsx` | Home dashboard |
| `modules/reports/pages/library/ReportLibraryPage.tsx` | Full report list |
| `modules/reports/pages/builder/CustomReportBuilderPage.tsx` | 3-step builder shell |
| `modules/reports/pages/builder/steps/Step1DataSource.tsx` | Data source selector |
| `modules/reports/pages/builder/steps/Step2FieldsFilters.tsx` | Fields + filters |
| `modules/reports/pages/builder/steps/Step3Visualisation.tsx` | Chart + save |
| `modules/reports/pages/viewer/ReportViewerPage.tsx` | Run + display |
| `modules/reports/pages/viewer/ReportFilterForm.tsx` | Dynamic filter form |
| `modules/reports/pages/viewer/ReportResultTable.tsx` | Typed data table |
| `modules/reports/pages/viewer/ReportChart.tsx` | Recharts wrapper |
| `modules/reports/pages/viewer/ReportExportBar.tsx` | Export + pin controls |
| `modules/reports/pages/setup/ReportAccessSetupPage.tsx` | Access matrix |
| `modules/reports/hooks/useReportDefinitions.ts` | React Query hook |
| `modules/reports/hooks/useRunReport.ts` | React Query mutation |
| `modules/reports/hooks/useReportPins.ts` | Pin hooks |
| `modules/reports/hooks/useReportAccessPolicies.ts` | Access policy hook |
| `modules/reports/types/report.types.ts` | TypeScript types |

### Frontend — Modify
| File | Change |
|---|---|
| `app/router.tsx` | Add `/reports` route tree |
| `app/layout/Sidebar.tsx` | Add REPORTS nav group + Reports nav item |
| `CLAUDE.md` | Add Module 11 to module inventory + build queue |
| `apps/back-office/package.json` | Add `recharts` dependency (new — not currently in workspace) |

---

## Acceptance Criteria

- [ ] `/reports` loads without errors; Reports nav item active and visible
- [ ] Home page shows quick-access cards by category; pinned row empty by default
- [ ] Report library lists all reports the user has access to; hidden reports are invisible (not "denied")
- [ ] Running any pre-built report (e.g. U02 Net Written Premium) with valid filters returns correct results
- [ ] CSV export downloads a valid RFC 4180 file matching the on-screen result
- [ ] PDF export downloads a branded PDF (NubSure logo, report name, filters applied, table, chart if applicable)
- [ ] Pinning a report adds it to the home page pinned row; unpinning removes it
- [ ] Custom report builder: Step 1 → 2 → 3 stepper works; Save & Run saves definition and runs it
- [ ] Custom report clone: cloning a SYSTEM report opens the builder pre-filled; saving creates a CUSTOM definition
- [ ] SYSTEM reports cannot be deleted; custom reports can be deleted by their creator
- [ ] Category-level access: granting Finance category to an access group gives all Finance reports
- [ ] Per-report override: revoking a single report within a granted category hides only that report
- [ ] `reports:manage_access` required for `/reports/setup` — other roles see 403
- [ ] Regulatory reports (N01–N08): `is_pinnable = false` — no Pin button shown
- [ ] `pnpm --filter @cia/back-office typecheck` exits 0
- [ ] Backend: `ReportQueryBuilder` always scopes queries to current tenant schema
- [ ] Backend: `ReportSeedService` is idempotent — running twice does not duplicate SYSTEM definitions
