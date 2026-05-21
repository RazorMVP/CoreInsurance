---
id: reports-module
title: Reports & Analytics (Module 11)
sidebar_label: Reports & Analytics
---

# Reports & Analytics — Module 11

The Reports & Analytics module provides a centralised, permission-controlled reporting layer across all CIAGB modules. It ships **67 pre-built SYSTEM reports** across 7 categories — underwriting, claims, finance, reinsurance, customer, Nigerian regulatory (NAICOM/NIID), and closures (Module 12 GL + IFRS 17 PAA + IFRS 9 ledger reports) — plus a **custom report builder** that lets authorised users create, save, and visualise ad-hoc reports.

## Key Design Decisions

### Unified Report Definition Engine

Every report — pre-built and custom — is a `ReportDefinition` entity with a JSONB `config` field. The `ReportRunnerService` reads the definition, builds a parameterised native SQL query, executes it tenant-scoped, applies computed field formulas, and renders to screen (JSON), CSV, or PDF.

Adding a new regulatory report is a **Flyway data migration** (`V18+` INSERT into `report_definition`) — not a code change. The `ReportRunnerService` interprets the config at runtime.

### Zero Business Module Dependency

`cia-reports` depends only on `cia-common` and `cia-auth`. It has **no dependency on any business module** (`cia-policy`, `cia-claims`, etc.). `ReportQueryBuilder` uses `EntityManager.createNativeQuery()` directly against the tenant schema. This eliminates circular dependency risks and keeps report logic self-contained.

### SYSTEM vs CUSTOM Reports

| Type | Created by | Editable | Deletable | Seeded by |
|------|-----------|----------|-----------|-----------|
| `SYSTEM` | Platform | ❌ No | ❌ No | Flyway V18 migration |
| `CUSTOM` | User | ✅ Yes | ✅ Yes | User via API |

SYSTEM reports can be **cloned** into CUSTOM reports for user modification.

## Architecture

### Maven Module: `cia-reports`

```
cia-reports/src/main/java/com/nubeero/cia/reports/
├── domain/
│   ├── ReportDefinition.java       # JPA entity — config JSONB via @JdbcTypeCode(SqlTypes.JSON) (native Hibernate 6)
│   ├── ReportConfig.java           # JSONB POJO: fields, filters, groupBy, sortBy, chart
│   # (ReportConfigConverter.java was deleted — replaced by @JdbcTypeCode native binding)
│   ├── ReportField.java            # { key, label, type, computed }
│   ├── ReportFilter.java           # { key, label, type, required }
│   ├── ReportChart.java            # { type, xAxis, yAxis }
│   ├── ReportPin.java              # Per-user pin (unique user_id + report_id)
│   ├── ReportAccessPolicy.java     # Category-level or report-level access per group
│   ├── ReportCategory.java         # UNDERWRITING | CLAIMS | FINANCE | REINSURANCE | CUSTOMER | REGULATORY
│   ├── ReportType.java             # SYSTEM | CUSTOM
│   └── DataSource.java             # POLICIES | CLAIMS | FINANCE | REINSURANCE | CUSTOMERS | ENDORSEMENTS
├── repository/
│   ├── ReportDefinitionRepository.java
│   ├── ReportPinRepository.java
│   └── ReportAccessPolicyRepository.java
├── service/
│   ├── ReportDefinitionService.java   # CRUD + clone; blocks edit/delete of SYSTEM reports
│   ├── ReportRunnerService.java       # Orchestrates run → JSON / CSV / PDF; pin management
│   ├── ReportAccessService.java       # Permission resolution: report-level > category-level > deny
│   ├── ReportQueryBuilder.java        # Native SQL from ReportConfig; computed field post-processing
│   ├── ReportCsvRenderer.java         # RFC 4180 streaming via StreamingResponseBody
│   └── ReportPdfRenderer.java         # PDFBox 3.x branded PDF — never throws
└── controller/
    ├── ReportController.java          # 14 REST endpoints under /api/v1/reports/
    └── dto/                           # ReportDefinitionDto, ReportRunRequest, ReportResultDto, ...
```

### REST Endpoints

All endpoints require `isAuthenticated()`. Fine-grained access uses `reports:*` authorities.

| Method | Path | Authority |
|--------|------|-----------|
| `GET` | `/api/v1/reports/definitions` | `reports:view` |
| `GET` | `/api/v1/reports/definitions/:id` | `reports:view` |
| `POST` | `/api/v1/reports/definitions` | `reports:create_custom` |
| `PUT` | `/api/v1/reports/definitions/:id` | `reports:create_custom` |
| `DELETE` | `/api/v1/reports/definitions/:id` | `reports:create_custom` |
| `POST` | `/api/v1/reports/definitions/:id/clone` | `reports:create_custom` |
| `POST` | `/api/v1/reports/run` | `reports:view` |
| `POST` | `/api/v1/reports/run/csv` | `reports:export_csv` |
| `POST` | `/api/v1/reports/run/pdf` | `reports:export_pdf` |
| `GET` | `/api/v1/reports/pins` | `reports:view` |
| `POST` | `/api/v1/reports/pins/:id` | `reports:view` |
| `DELETE` | `/api/v1/reports/pins/:id` | `reports:view` |
| `GET` | `/api/v1/reports/access-policies` | `reports:manage_access` |
| `PUT` | `/api/v1/reports/access-policies` | `reports:manage_access` |

### ReportConfig JSONB Shape

```json
{
  "fields": [
    { "key": "loss_ratio", "label": "Loss Ratio %", "type": "PERCENT", "computed": true }
  ],
  "filters": [
    { "key": "date_from", "label": "Date From", "type": "DATE", "required": true }
  ],
  "groupBy": "class_of_business",
  "sortBy": "loss_ratio",
  "sortDir": "DESC",
  "chart": { "type": "BAR", "xAxis": "class_of_business", "yAxis": "loss_ratio" }
}
```

**Field types:** `STRING | MONEY | PERCENT | DATE | NUMBER | INTEGER`  
**Filter types:** `DATE | DATE_RANGE | SELECT | MULTI_SELECT | TEXT | NUMBER`  
**Chart types:** `BAR | LINE | PIE | TABLE_ONLY`

### Computed Fields

Computed fields are **post-processed in Java** after the raw SQL returns — they are not computed in SQL. This keeps base queries simple and avoids aggregation conflicts.

| Key | Formula |
|-----|---------|
| `loss_ratio` | `claims_incurred / premium_earned × 100` |
| `combined_ratio` | `(claims_incurred + expenses) / premium_earned × 100` |
| `expense_ratio` | `expenses / premium_earned × 100` |
| `retention_pct` | `retained_si / gross_si × 100` |
| `cession_pct` | `ceded_si / gross_si × 100` |
| `utilisation_pct` | `ceded_amount / retained_amount × 100` |
| `conversion_pct` | `bound_quotes / total_quotes × 100` |

### Access Control Resolution

Permission resolution in `ReportAccessService` follows this priority:

1. **Report-level policy** (most specific) — wins over category
2. **Category-level policy** — fallback
3. **Deny** (invisible, not "Access Denied") — if neither policy exists

Reports a user cannot view **do not appear** in the library or home page — there is no "Access Denied" state.

## Flyway Migrations

| Migration | Purpose |
|-----------|---------|
| `V17__create_reports_tables.sql` | Creates `report_definition`, `report_pin`, `report_access_policy` + indexes |
| `V18__seed_system_report_definitions.sql` | Seeds the original 55 SYSTEM report definitions (Underwriting + Claims + Finance + Reinsurance + Customer + Regulatory) |
| `V44__seed_closures_report_definitions.sql` | Adds 12 SYSTEM CLOSURES reports (4 GL + 4 IFRS 17 PAA + 4 IFRS 9). Total reaches 67. |

## Pre-Built Report Catalogue (67 reports)

| Category | Count | ID Range / Coverage | Notable |
|----------|-------|---------------------|---------|
| Underwriting | 12 | U01–U12 | GWP, NWP, Policy Register, Renewal Due, Quote-to-Bind |
| Claims | 13 | C01–C13 | Loss Ratio, Claims Ageing, Large Loss, Reserve Movement |
| Finance | 9 | F01–F09 | Receivables Ageing, Collections, Commission Statement, Combined Ratio |
| Reinsurance | 8 | R01–R08 | RI Bordereaux, Treaty Utilisation, Cession Statement |
| Customer | 5 | K01–K05 | Active Customers, Customer Loss Ratio, Broker Performance |
| Regulatory | 8 | N01–N08 | NAICOM Annual Revenue, Prudential Return, NAICOM Bordereaux; `is_pinnable=false` |
| **Closures (V44)** | **12** | GL × 4 + PAA × 4 + IFRS 9 × 4 | Trial Balance, General Journal Listing, Account Movement Statement, Period Lock Audit Trail, LRC/LIC Roll-forward, Insurance Service Result Summary, Contract Groups Listing, Investment Holdings Schedule, Investment Carrying Value Movement, Premium Receivable ECL Schedule, §B5.5.39 Combined Movement Analysis. All pinnable. Backed by V31 (GL), V36/V38 (PAA), V39/V40 (IFRS 9) substrates. |

Regulatory reports (N01–N08) have `is_pinnable = false` — the Pin button is not shown for these reports in the UI. CLOSURES reports are all pinnable (`is_pinnable = true`) since they are operational ledger queries, not regulator-mandated forms.

### Closures Data Sources

The CLOSURES category requires 9 new `DataSource` enum values mapped to `BASE_QUERIES` in `ReportQueryBuilder`:

| Data Source        | Substrate                                                                              | Notes                                                                                                       |
|--------------------|----------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `TRIAL_BALANCE` | `journal_entry_line × journal_entry × chart_of_account` | Aggregated — uses `BASE_QUERY_TAILS` for `GROUP BY coa.code, coa.name, coa.account_type` |
| `GENERAL_LEDGER` | `journal_entry_line × journal_entry × chart_of_account × class_of_business` | Per-line JE listing; class-of-business join via V42 `journal_entry_line.class_of_business_id` |
| `GL_PERIOD_LOCK` | `period_lock × fiscal_period` | Type-2 SCD timeline of close/release events |
| `PAA_LRC` | `paa_lrc × group_of_contracts × portfolio × fiscal_period` | Raw LRC roll-forward — one row per (group, period) |
| `PAA_GROUPS` | `group_of_contracts × portfolio × class_of_business` | §22 group register |
| `IFRS17_MOVEMENT` | `paa_movement_analysis` (V38 view) | §103 disclosure-shaped LRC + LIC roll-forward |
| `IFRS9_HOLDINGS` | `investment_holding` | Current-state register |
| `IFRS9_CARRYING` | `investment_carrying_value × investment_holding × fiscal_period` | Raw IFRS 9 per-(holding, period) roll-forward |
| `IFRS9_MOVEMENT` | `ifrs9_investment_movement_analysis` (V40 view) | §B5.5.39 disclosure-shaped roll-forward |

Three additional filter keys (`account_code`, `source_module`, `classification`) were added to `ReportQueryBuilder.execute()` to support per-account, per-event-source, and per-IFRS-9-classification scoping on closures reports.

## Development Conventions

- **Never add a business module dependency to `cia-reports`** — use `EntityManager.createNativeQuery()` only.
- **SYSTEM reports are immutable** — `ReportDefinitionService` throws `IllegalStateException` on any mutating operation against a SYSTEM report.
- **`ReportPdfRenderer` must never throw** — all exceptions are caught, logged, and `null` returned.
- **ORDER BY SQL injection prevention** — `ReportQueryBuilder.sanitizeColumnName()` whitelists `[a-zA-Z0-9_.]`.
- **`@JsonProperty` on `ReportChart.xAxis`/`yAxis`** — required to avoid Lombok/Jackson getter naming mismatch with JSONB deserialization.
