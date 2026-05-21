---
id: modules
title: Module Reference
sidebar_label: Modules
---

# Module Reference

The backend is a Maven multi-module project under `cia-backend/`. Each module compiles to a JAR and is assembled by `cia-api`.

## Module Inventory

```
cia-backend/
├── cia-common/          # TenantContext, audit, ApiResponse<T>, BaseEntity, exceptions
├── cia-auth/            # Keycloak OAuth2 resource server config, JwtAuthConverter
├── cia-storage/         # DocumentStorageService + MinIO/S3/GCS/Azure adapters
├── cia-integrations/    # NAICOM, NIID, KYC interfaces + stub implementations
├── cia-notifications/   # NotificationService, Email + SMS implementations
├── cia-workflow/        # Temporal client config, workflow & activity interfaces
├── cia-documents/       # PDF generation (Apache PDFBox), template rendering
├── cia-setup/           # Module 1: Setup & Administration
├── cia-customer/        # Module 7: Customer Onboarding & KYC
├── cia-quotation/       # Module 2: Quotation
├── cia-policy/          # Module 3: Policy
├── cia-endorsement/     # Module 4: Endorsements
├── cia-claims/          # Module 5: Claims
├── cia-reinsurance/     # Module 6: Reinsurance
├── cia-finance/         # Module 8: Finance — AND Module 12: Period-End Closures (subpackages)
├── cia-partner-api/     # Module 9: Partner Open API
├── cia-audit/           # Module 10: Audit & Compliance
├── cia-reports/         # Module 11: Reports & Analytics (55 pre-built reports, custom builder, CSV/PDF export)
└── cia-api/             # Assembly: main app, REST controllers, Flyway, config + Dashboard API
```

### Module 12 Lives Inside `cia-finance`

Module 12 (Period-End Closures) co-locates with Module 8 (Finance) because every measurement engine posts journal entries that the GL gateway (also in `cia-finance`) immediately consumes — splitting them out would create a circular dependency. The subpackage layout:

```
cia-finance/
├── (Module 8) — receipts, payments, reconciliation
├── gl/        # Phase 1 — GL Foundation
│   ├── ChartOfAccountService, JournalEntryService (gateway), TrialBalanceService
│   ├── FiscalYearService, PeriodLockService (5-business-day grace, 423 LOCKED)
│   ├── SubledgerPostingService (@EventListener fanout → JEs)
│   └── PolicyClassResolver (Slice 1.10 — class_of_business per event)
├── paa/       # Phase 2 — IFRS 17 PAA measurement
│   ├── ContractGroupingService, LrcEngine, LicEngine
│   ├── DiscountUnwindEngine (§87-92), OnerousContractTestEngine (§47-49)
│   ├── PaaPeriodCloseService (orchestrator + §83/§84)
│   └── MovementAnalysisService (§103 relay over V38 view)
├── ifrs9/     # Phase 3 — IFRS 9 measurement
│   ├── InvestmentClassificationService (§4.1 + §B4.1.26)
│   ├── AmortisedCostEngine, FairValueEngine, InvestmentEclEngine
│   ├── PremiumReceivableEclEngine (§5.5.15)
│   └── Ifrs9MovementAnalysisService (§B5.5.39 relay over V40 view)
├── naicom/    # Phase 4 — NAICOM monthly recap submissions
│   ├── 10 engines (N01–N08), all implementing NaicomSubmissionEngine
│   ├── NaicomSubmissionService (DRAFT → SUBMITTED → ACKNOWLEDGED → ARCHIVED + RETRACTED)
│   └── SubmissionArtifactService (JSON / CSV / PDF → DocumentStorageService)
└── backfill/  # Slice 1.8 — retroactive JE backfill (Temporal workflow)
```

The Module 12 back-office frontend lives at `cia-frontend/apps/back-office/src/modules/closures/` — 13 tabs mounting `PeriodLockListPage`, `ChartOfAccountsPage`, `PostingRulesPage`, `JournalEntryBrowserPage`, `TrialBalanceReportPage`, `BackfillAdminPage`, `PaaPeriodClosePage`, `PaaMovementAnalysisPage`, `ContractGroupsPage`, `HoldingsListPage`, `Ifrs9MeasurementPage`, `Ifrs9MovementAnalysisPage`, `NaicomSubmissionsPage`. All API calls go through `validatedGet` / `validatedPost` against zod schemas in `@cia/api-client/finance-closures.ts`.

See [`period-end-closures-implementation-plan.md`](./period-end-closures-implementation-plan.md) for the per-slice shipping history (V31–V43 migrations, 274 cia-api failsafe ITs, 16 Phase 5 frontend slices F5.1–F5.16).

## Dependency Rules

- All business modules depend on `cia-common`. Never the reverse.
- `cia-auth`, `cia-storage`, `cia-integrations`, `cia-notifications`, `cia-workflow`, `cia-documents` are infrastructure modules — business modules depend on them, not each other (except explicit cross-module relationships listed below).
- `cia-partner-api` is a **pure facade** — it only maps internal service results to partner-safe DTOs. No business logic lives in this module.

### Cross-Module Dependencies

| Module | Depends On |
|--------|-----------|
| `cia-policy` | `cia-workflow`, `cia-documents`, `cia-integrations` |
| `cia-endorsement` | `cia-workflow`, `cia-documents`, `cia-policy` |
| `cia-claims` | `cia-workflow`, `cia-documents`, `cia-policy` |
| `cia-customer` | `cia-integrations` (KYC) |
| `cia-quotation` | `cia-workflow` |
| `cia-partner-api` | `cia-common`, `cia-auth`, `cia-storage`, `cia-setup`, `cia-customer`, `cia-quotation`, `cia-policy`, `cia-claims`, `cia-workflow`, `cia-notifications` |
| `cia-audit` | `cia-common`, `cia-notifications` |
| `cia-reports` | `cia-common`, `cia-auth` — **no business module dependency** (uses `EntityManager.createNativeQuery()` directly) |
| `cia-finance` (Module 12 subpackages) | `cia-common`, `cia-auth`, `cia-storage` (NAICOM artifacts), `cia-workflow` (Slice 1.8 backfill + NAICOM dispatch), `cia-notifications` (CFO reopen alerts). Business modules feed Module 12 via Spring `@EventListener` — never direct calls. |
| `cia-api` | All modules — also owns the Dashboard API (`/api/v1/dashboard/*`) |

## Package Conventions

Each business module follows this package layout:

```
com.nubeero.cia.<module>/
├── <Entity>.java              # JPA entity
├── <Entity>Repository.java    # Spring Data JPA repository
├── <Entity>Service.java       # Business logic
├── <Entity>Controller.java    # REST controller
├── dto/                       # Request/Response DTOs
└── <Entity>Status.java        # Status enum (if applicable)
```
