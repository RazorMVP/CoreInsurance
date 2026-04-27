---
name: cia
description: Core Insurance Application — General Business (CIAGB) domain expert. Use when building, designing, debugging, or discussing any part of the CIAGB system. Covers all 11 modules (Setup & Admin, Customer Onboarding, Quotation, Policy, Endorsements, Claims, Reinsurance, Finance, Partner Open API, Audit & Compliance, Reports & Analytics), the agreed tech stack, multi-tenant SaaS architecture, Nigerian regulatory integrations (NAICOM, NIID, NDPR), cross-cutting business rules, and the Insurtech Open API platform. Activate for any task involving insurance domain logic, data models, module flows, compliance requirements, or partner API integration.
---

# Core Insurance Application — General Business (CIAGB)

## Project Identity

- **Product:** Core Insurance Application (General Business)
- **Acronym:** CIAGB
- **Type:** Multi-tenant SaaS for general (P&C) insurance companies
- **Geography:** Nigeria-first (NAICOM, NIID, NDPR compliance required)
- **PRD:** https://akinwalenubeero.atlassian.net/wiki/spaces/CIAGB/overview

---

## Tech Stack (Locked)

| Layer | Choice | Notes |
|---|---|---|
| Frontend | React + Vite + TypeScript + Tailwind + shadcn/ui | SPA calling Spring Boot REST API |
| Backend | Java 21 + Spring Boot 3 | REST API, multi-module Maven/Gradle |
| Database | PostgreSQL — schema-per-tenant | One schema per insurance company |
| Auth | Keycloak | RBAC, multi-tenant orgs, SSO, MFA, audit logs |
| Jobs / Workflows | Temporal | Durable, retryable workflows for claims, settlements, notifications |
| Storage | S3-compatible interface via MinIO adapter | Swap to AWS S3 / GCS / Azure Blob via config; on-prem = MinIO |
| AI | Claude API (Anthropic SDK) | Optional, feature-flagged per tenant |
| Deployment | Cloud-agnostic / on-prem capable | Vercel for frontend; Docker/K8s for backend |
| Testing | Vitest (frontend) + JUnit 5 + Testcontainers (backend) + Playwright (E2E) | |

---

## Multi-Tenancy Model

- **Schema-per-tenant** in PostgreSQL: each insurance company gets its own isolated schema.
- Tenant resolution via subdomain or HTTP header at the API gateway layer.
- Keycloak realm per tenant for auth isolation.
- All data queries must be tenant-scoped — never cross schemas.
- Configuration (products, approval groups, policy number formats) is per-tenant.

---

## Module Inventory (178 features across 11 modules)

### Module 1 — Setup & Administration (35 features)
Company setup, password policy, bank/currency setup, access groups, user management, password reset, signature/profile uploads, approval group setup (single-level and multi-level), class of business, product setup (single-risk and multi-risk), commission setup, policy specifications, policy number naming convention, required document setup for claims, claim notification timelines, nature/cause of loss, SBU/branch/broker/relationship manager/surveyor/insurance company/reinsurance company setup, vehicle makes/models/types, pre-loss and loss inspection survey thresholds, claim reserve setup, partner app management (create/revoke Insurtech OAuth2 clients, configure scopes, rate limits, webhook secrets, usage dashboard).

### Module 2 — Quotation (5 features)
Single-risk quote generation, multi-risk quote generation, bulk upload (individual quotes or single quote with multiple items), quote modification with version history, quote approval workflow.

### Module 3 — Policy (23 features)
Convert quote to policy (Direct, Direct with Coinsurance, Inward Coinsurance), create policy without quote, risk details (manual and bulk upload), policy specifications, payment details, commission, coinsurance share percentages, policy document generation (clause bank, editable template), send for approval, policy approval (NAICOM/NIID upload on approval), send policy document, document acknowledgement, debit note generation, pre-loss survey process (internal/external), survey override, survey approval, policy details page, policy update, renewal notice automation.

### Module 4 — Endorsements (10 features)
Renewal, extension of period, cancellation, reversal, reduction in period, change in period, increase/decrease sum insured, addition/deletion of insured items, endorsement approval, debit note analysis report.

### Module 5 — Claims (23 features)
Claim notification, claim registration, bulk claim registration (individual and single claim), claim dashboard, edit/cancel claim, missing document tracking, claim processing (reserves, expenses, allocation, comments, recovery), loss inspection (internal/external), loss inspection approval, policy claim history, risk details on claim, claim reserves, claim expenses, claim allocation (treaty-based), claim comments, send for approval, approve/reject claim, DV generation (own damage/third party/ex-gratia), execute DV (online portal), process executed DV, close claim.

### Module 6 — Reinsurance (17 features)
Peril group setup, treaty setup (Surplus/Quota Share/XOL), automatic RI allocation, RI confirmation for policies exceeding gross capacity, RI confirmation approval, treaty allocation approval (individual and batch), outward facultative offer slip generation, outward facultative credit note generation, inward facultative policy generation, batch treaty reallocation, renewal/extension endorsements for FAC inward, renewal notice for FAC inward, monthly/quarterly reinsurance returns, claims and premium bordereaux, reinsurance recoveries, debit note analysis for FAC inward.

### Module 7 — Customer Onboarding (10 features)
Individual customer onboarding (with KYC), corporate customer onboarding (with KYC), broker-enabled corporate, broker-enabled individual, KYC update, customer summary page, policy history, claim history, loss ratio report, active customers report.

### Module 8 — Finance (5 features)
Receipt generation (single debit note), bulk receipt generation (multiple debit notes), receipt approval, payables processing (credit notes), payment approval.

### Module 9 — Partner Open API (15 features)
Insurtech partner app registration and credential management, OAuth2 Client Credentials authentication (Keycloak service accounts), scoped API access (`products:read`, `quotes:create`, `customers:create`, `policies:create`, `policies:read`, `claims:create`, `claims:read`, `webhooks:manage`), product catalog API, quotation API, customer registration API (with KYC), policy binding and retrieval API, policy document download API, claims submission and status API, webhook registration and management (11 event types), webhook event dispatch (HMAC-SHA256 signed payloads via Temporal), rate limiting per partner (bucket4j token bucket, 3 configurable tiers), Springdoc OpenAPI 3.1 spec auto-generation, Postman collection generation per build, sandbox environment per partner.

### Module 10 — Audit & Compliance (15 features)
System-wide audit log viewer (filterable by entity type, entity ID, user, action, date range), event detail view (before/after JSONB snapshots), login and session log viewer (LOGIN, LOGOUT, LOGIN_FAILED, PASSWORD_RESET, ACCOUNT_LOCKED events), CSV export of audit logs with applied filters, 6 pre-built reports (actions by user, actions by module, approval audit trail, data change history, login security report, ranked user activity summary), real-time alert detection (failed logins ≥3, bulk deletes ≥5 in 5 min, off-hours activity, large financial approvals ≥₦50M), alert acknowledgement workflow, alert configuration (System Admin only — thresholds, business hours, retention period), in-app + email alert notifications, 7-year default retention (configurable per tenant), System Auditor role (AUDIT_VIEW — read-only, separate from System Admin).

### Module 11 — Reports & Analytics (20 features)
55 pre-built SYSTEM reports across 6 categories (Underwriting × 12, Claims × 13, Finance × 9, Reinsurance × 8, Customer × 5, Regulatory/NAICOM × 8), custom report builder (3-step: data source → fields/filters → visualisation), report library (searchable, category-filtered), report viewer with dynamic filter form, data table with typed formatting (₦ MONEY, % PERCENT, DATE), Recharts visualisation (BAR/LINE/PIE/TABLE\_ONLY driven by ReportConfig.chart), CSV export (streaming RFC 4180), PDF export (Apache PDFBox branded, server-side), pin management (per-user pinned reports on home page), category-level and per-report access control (View / Export CSV / Export PDF per access group), report cloning (SYSTEM → CUSTOM), SYSTEM reports are immutable (seeded by Flyway, cannot be deleted or edited), custom reports fully CRUD-able by their creator.

---

## Module 11 Architecture — Reports & Analytics

### Backend: `cia-reports`

```
HTTP Request (Bearer JWT)
  └── ReportController  (/api/v1/reports/)
        │
        ├── GET  /definitions[?category=]     → ReportDefinitionService.listAll/ByCategory()
        ├── GET  /definitions/:id             → ReportDefinitionService.get()
        ├── POST /definitions                 → ReportDefinitionService.create()        [reports:create_custom]
        ├── PUT  /definitions/:id             → ReportDefinitionService.update()        [reports:create_custom]
        ├── DEL  /definitions/:id             → ReportDefinitionService.delete()        [reports:create_custom]
        ├── POST /definitions/:id/clone       → ReportDefinitionService.clone()         [reports:create_custom]
        │
        ├── POST /run                         → ReportRunnerService.run()               [reports:view]
        ├── POST /run/csv                     → ReportRunnerService.runCsv()            [reports:export_csv]
        ├── POST /run/pdf                     → ReportRunnerService.runPdf()            [reports:export_pdf]
        │
        ├── GET  /pins                        → ReportRunnerService.listPinned()        [reports:view]
        ├── POST /pins/:id                    → ReportRunnerService.pin()               [reports:view]
        ├── DEL  /pins/:id                    → ReportRunnerService.unpin()             [reports:view]
        │
        ├── GET  /access-policies             → ReportAccessService.listByGroup()       [reports:manage_access]
        └── PUT  /access-policies             → ReportAccessService.upsert()            [reports:manage_access]

ReportRunnerService
  ├── load: ReportDefinitionService.get(reportId)
  ├── execute: ReportQueryBuilder.execute(definition, filterValues)
  │     ├── BASE_QUERIES map (one native SQL per DataSource)
  │     ├── dynamic WHERE clauses from config.filters + user-supplied values
  │     ├── sanitised ORDER BY (whitelist: [a-zA-Z0-9_.] only)
  │     └── post-processing: computed fields (loss_ratio, combined_ratio, etc.)
  └── render:
        ├── JSON  → ReportResultDto { columns, rows, totalRows }
        ├── CSV   → ReportCsvRenderer (StreamingResponseBody, UTF-8 BOM, RFC 4180)
        └── PDF   → ReportPdfRenderer (PDFBox 3.x — header/table/footer; never throws)

ReportAccessService (permission resolution)
  ├── report-level policy  → most specific, wins over category
  └── category-level policy → fallback
  → deny if neither exists

Domain (tenant schema)
  ├── report_definition   (JSONB config via ReportConfigConverter)
  │     └── config shape: { fields[], filters[], groupBy, sortBy, sortDir, chart{type,xAxis,yAxis} }
  ├── report_pin          (user_id + report_id + display_order; UNIQUE per user+report)
  └── report_access_policy (access_group_id + category? + report_id? + can_view + can_export_csv + can_export_pdf)
```

**Key architectural constraint:** `cia-reports` has **zero dependency on any business module**. `ReportQueryBuilder` uses `EntityManager.createNativeQuery()` directly against the tenant schema. Adding a new pre-built report is a Flyway data migration (`V18+`), not a code change.

**ReportConfig JSONB shape:**
```json
{
  "fields":  [{ "key": "loss_ratio", "label": "Loss Ratio %", "type": "PERCENT", "computed": true }],
  "filters": [{ "key": "date_from",  "label": "Date From",    "type": "DATE",    "required": true }],
  "groupBy": "class_of_business",
  "sortBy":  "loss_ratio",
  "sortDir": "DESC",
  "chart":   { "type": "BAR", "xAxis": "class_of_business", "yAxis": "loss_ratio" }
}
```

**Field types:** `STRING | MONEY | PERCENT | DATE | NUMBER | INTEGER`
**Filter types:** `DATE | DATE_RANGE | SELECT | MULTI_SELECT | TEXT | NUMBER`
**Chart types:** `BAR | LINE | PIE | TABLE_ONLY`

**Computed fields (post-processed in Java, not SQL):**

| Key | Formula |
|---|---|
| `loss_ratio` | `claims_incurred / premium_earned × 100` |
| `combined_ratio` | `(claims_incurred + expenses) / premium_earned × 100` |
| `expense_ratio` | `expenses / premium_earned × 100` |
| `retention_pct` | `retained_si / gross_si × 100` |
| `cession_pct` | `ceded_si / gross_si × 100` |
| `utilisation_pct` | `capacity_used / total_capacity × 100` |
| `conversion_pct` | `bound_quotes / total_quotes × 100` |

**55 SYSTEM report catalogue summary:**

| Category | Count | IDs | Notable |
|---|---|---|---|
| Underwriting | 12 | U01–U12 | GWP, NWP, Policy Register, Renewal Due, Quote-to-Bind |
| Claims | 13 | C01–C13 | Loss Ratio, Claims Ageing, Large Loss, Reserve Movement |
| Finance | 9 | F01–F09 | Receivables Ageing, Collections, Commission Statement, Combined Ratio |
| Reinsurance | 8 | R01–R08 | RI Bordereaux, Treaty Utilisation, Cession Statement |
| Customer | 5 | K01–K05 | Active Customers, Customer Loss Ratio, Broker Performance |
| Regulatory | 8 | N01–N08 | NAICOM Annual Revenue, Prudential Return, Premium/Claims Bordereaux; `is_pinnable=false` |

---

### Frontend: `modules/reports/`

```
/reports                    ReportsHomePage
│   ├── Pinned row          (Bookmark01Icon; max 6; from GET /pins)
│   ├── Quick-access grid   (6 categories × 4 cards; from GET /definitions?category=X)
│   └── Empty pin state     (Browse Library CTA)
│
├── /library                ReportLibraryPage
│   ├── Search bar          (client-side filter on name/description)
│   ├── Category tabs       (All + 6 categories → re-fetches with ?category=)
│   └── Card list           (Run Report → /run/:id  |  Clone & Edit → /custom)
│
├── /run/:id                ReportViewerPage
│   ├── Breadcrumb          (items[] prop — Reports → Category → Report name)
│   ├── ReportFilterForm    (dynamic from config.filters; required* validated by RHF)
│   ├── ReportResultTable   (plain HTML <table>; ₦ MONEY / % PERCENT / DATE formatting)
│   ├── ReportChart         (Recharts wrapper; BAR/LINE/PIE; returns null for TABLE_ONLY)
│   └── ReportExportBar     (Export CSV | Export PDF | Pin/Unpin)
│
├── /custom                 CustomReportBuilderPage (new)
├── /custom/:id             CustomReportBuilderPage (edit)
│   ├── Step 1  DataSource  (6 card selectors)
│   ├── Step 2  Fields      (checkbox field picker + computed badge + date filter toggles)
│   └── Step 3  Visualise   (chart type cards + axis selects + name + category + Save & Run)
│
└── /setup                  ReportAccessSetupPage
    ├── Access group select (mock groups; production: GET /api/v1/setup/access-groups)
    └── Permission matrix   (expandable category rows → per-report overrides)
                            (View / Export CSV / Export PDF checkboxes per row)
```

**React Query hooks:**

| Hook | Endpoint | Notes |
|---|---|---|
| `useReportDefinitions(category?)` | `GET /definitions` | staleTime 5 min |
| `useReportDefinition(id)` | `GET /definitions/:id` | enabled only when id present |
| `useRunReport()` | `POST /run` | mutation |
| `useExportCsv()` | `POST /run/csv` | mutation; triggers browser download |
| `useExportPdf()` | `POST /run/pdf` | mutation; triggers browser download |
| `useReportPins()` | `GET /pins` | |
| `usePinReport()` | `POST /pins/:id` | invalidates pins on success |
| `useUnpinReport()` | `DELETE /pins/:id` | invalidates pins on success |
| `useReportAccessPolicies(groupId)` | `GET /access-policies?accessGroupId=` | enabled only when groupId present |
| `useUpsertAccessPolicy()` | `PUT /access-policies` | invalidates policies on success |

---

## Key Cross-Cutting Business Rules

### Customer Number Format
- `customer_number_format` is a **singleton per tenant** (at most one row). Managed by System Admin via Setup → Customer Number Format.
- Format: `{prefix}/{year}/{type}/{sequence}` — e.g. `CUST/2026/IND/00000001`, `CUST/2026/CORP/00000001`.
- `includeType=true` separates sequences per customer type (`lastSequenceIndividual` / `lastSequenceCorporate`). `includeType=false` uses a shared `lastSequence`.
- Default `sequenceLength=8` supports up to 99,999,999 per type per year.
- `generateNext(customerType)` uses `PESSIMISTIC_WRITE` lock — prevents duplicate numbers under concurrent onboardings.
- If no format is configured, customer creation throws `CUSTOMER_NUMBER_FORMAT_NOT_CONFIGURED`.

### KYC Update Rules
- Updating any KYC field (ID type, ID number, expiry date, or document) on a customer or director **requires a reason** from a predefined dropdown (Document expired / Incorrect details submitted / Name mismatch / Customer request / ID type change / Other). Selecting "Other" makes the additional notes field mandatory.
- KYC field updates automatically trigger re-verification with the KYC provider. Changes are audit-logged twice: a general UPDATE (before/after customer snapshot) and a dedicated `CustomerKyc` or `CustomerDirectorKyc` UPDATE entry containing the reason, notes, and resulting KYC status.
- Old KYC details are preserved in the audit log only — the KYC tab always shows the current record.

### Corporate Director Rules
- A corporate customer must have **at least 2 active directors** at all times. The backend throws `MINIMUM_DIRECTORS_REQUIRED` and the frontend disables the Save button if active directors drop below 2.
- Director deletion is **soft-delete** (`deleted_at`): the record is preserved for audit, shown with a "Removed" badge in the Edit Customer sheet, and restorable before saving.
- Each director's KYC fields can be updated independently, following the same reason-required flow as the customer-level KYC update.
- New directors added via the Edit Customer sheet trigger KYC verification on save.

### Premium Calculations
- `Premium = (Sum Insured × Rate) − Discount`
- Pro-rata endorsements: `(Annual Premium / 365) × Days`
- Multi-risk: section premiums less section discounts, summed, less product-level discounts.

### Approval Hierarchies
- Single-level: one approver within amount range.
- Multi-level: escalates through tiers until approver whose limit ≥ amount.
- Applies to: Quotes, Policies, Endorsements, Claims, Finance receipts/payments.

### Policy Business Types
- **Direct** — full risk retained, full policy document generated.
- **Direct with Coinsurance** — lead insurer; other participants listed; coinsurance share page on document.
- **Inward Coinsurance** — accepting a share from another lead insurer; generate guaranty policy doc.
- **Inward Facultative** — handled in Reinsurance module, not Underwriting.

### Reinsurance Allocation (Auto)
- Surplus: retain up to Retention Limit; cede remainder up to Surplus Limit; tag excess beyond gross capacity.
- Quota Share: split by fixed percentages.
- XOL: retain first layer; cede losses above retention up to XOL limit.
- Allocation based on treaty year matching policy start date — not creation date.
- Only "our share" is used for coinsurance policies.

### Nigerian Regulatory Integrations
- **NAICOM**: All policies must be uploaded on approval. Motor + marine also uploaded to NIID. Retry until NAICOM Unique ID is returned.
- **NIID**: Motor and marine policies/endorsements. Motor renewals in advance flow to NIID on previous policy expiry.
- **KYC**: Validate individual ID (NIN, Voter's Card, Driver's License, International Passport) and corporate RC Number. Integration with a KYC provider (TBD — likely NIBSS/DOJAH/Prembly).
- **NDPR**: Nigerian Data Protection Regulation compliance required for all PII storage.

### Document Generation
- Policy documents use per-product templates; editable in a rich text editor before approval.
- Clause bank: default clauses per product; add/remove/edit at policy level.
- PDF generated on approval; signatures appended from user profile.
- Motor/marine: separate certificate per risk item; NAICOM Unique ID on certificate.

### Notifications
- Email: quote approval, policy approval, renewal notices (2mo/1mo/14d/7d/1d/0d, then +1/10/30/60/120d post-expiry), claim registration, DV dispatch, document acknowledgement reminders (every 7 days).
- In-app: approval pending, rejection reasons, claim status updates.

### Financial Flows
- Debit notes → Finance receivables (receipt generation).
- Credit notes → Finance payables (payment processing).
- Commission credit notes flow from underwriting to finance.
- Claim expense credit notes flow from claims to finance.
- FAC outward credit notes flow from reinsurance to finance.

---

## Data Model Highlights

### Core entities (per tenant schema)
`customers`, `customer_directors`, `customer_documents`, `customer_number_format`, `policies`, `quotes`, `endorsements`, `claims`, `claim_reserves`, `claim_expenses`, `reinsurance_treaties`, `ri_allocations`, `debit_notes`, `credit_notes`, `receipts`, `payments`, `products`, `classes_of_business`, `brokers`, `users`, `access_groups`, `approval_groups`, `document_templates`, `partner_apps`, `webhook_registrations`, `webhook_delivery_logs`, `audit_log`, `login_audit_log`, `audit_alert`, `audit_alert_config`, `report_definition`, `report_pin`, `report_access_policy`, `policy_number_formats`.

### Key relationships
- `policies` → `customers` (many-to-one)
- `policies` → `products` → `classes_of_business`
- `claims` → `policies`
- `endorsements` → `policies`
- `ri_allocations` → `policies` → `reinsurance_treaties`
- `debit_notes` → `policies` (generated on approval)
- `credit_notes` → `endorsements` | `claims` | `commissions`
- `receipts` → `debit_notes`
- `payments` → `credit_notes`
- `webhook_registrations` → `partner_apps` (many-to-one)
- `webhook_deliveries` → `webhook_registrations` (many-to-one; tracks every dispatch attempt)
- `report_pin` → `report_definition` (many-to-one; UNIQUE per user_id+report_id)
- `report_access_policy` → `report_definition` (nullable many-to-one; NULL = category-level policy)

---

## Development Conventions

- All REST endpoints are tenant-scoped via `X-Tenant-ID` header (resolved from Keycloak JWT).
- All write operations log to an audit table: `entity_type`, `entity_id`, `action`, `user_id`, `timestamp`, `old_value`, `new_value`.
- Approval workflows are modelled as Temporal workflows — not ad-hoc state machines.
- Bulk operations (upload, batch registration) use async Temporal workflows with progress tracking.
- Document storage uses an abstraction interface; inject the correct adapter (MinIO/S3/GCS/Azure) via Spring config.
- AI features are feature-flagged per tenant via a `features` config table; disabled = skip silently.
- NAICOM/NIID uploads are Temporal activities with retry until success.
- KYC, Email, SMS, and Document Storage are all provider-agnostic abstractions injected via Spring config — same pattern for each: interface + multiple implementations + per-tenant or per-deployment config.
  - `KycVerificationService` → DojahKycService | PremblyKycService | NibssKycService | MockKycService
  - `NotificationService` → email impl (SendGrid/SES/Mailgun/SMTP) + SMS impl (Termii/Twilio/etc.), both configurable per deployment
  - `DocumentStorageService` → MinIO | S3 | GCS | Azure Blob
- Partner API (`cia-partner-api`) uses OAuth2 Client Credentials (machine-to-machine) — never Keycloak human login. Base path `/partner/v1/`, separate from internal `/api/v1/`.
- Every `@RestController` method in `cia-partner-api` requires Springdoc annotations: `@Operation(summary, description, tags)`, `@ApiResponse` for 200/400/401/429, `@SecurityRequirement(name = "bearer-key")`.
- Every response DTO exposed through the partner API requires `@Schema(description = "...")` on the class and `@Schema(description = "...", example = "...")` on every field. These DTOs live in `cia-partner-api/controller/dto/` as `Partner*Response` types — never put `@Schema` on business module DTOs (`cia-policy`, `cia-quotation`, etc.), which must not depend on swagger-annotations.
- Partner controllers never return business module entities or internal DTOs directly. Every partner endpoint maps its result through a `Partner*Response.from(businessDto)` static factory, isolating the external API contract from internal model changes.
- Webhook dispatch is a Temporal activity: HMAC-SHA256 signed (`X-CIA-Signature` header), 5-second HTTP timeout, max 3 retries with exponential backoff (30s / 2min / 10min).
- Rate limiting via bucket4j per `client_id`; limits stored in Redis (prod) or in-memory (dev); 3 tiers: 60/300/1000 rpm.
- `DocumentGenerationService` implementations must **never throw** — catch all exceptions, log, and return `null`. Calling approval flows check for null and skip storage; they are never blocked by PDF failures.
- PDF generation uses Apache PDFBox 3.x (`Standard14Fonts.FontName.HELVETICA`) + Thymeleaf `StringTemplateResolver` (separate `@Bean("documentTemplateEngine")` — do not reuse the main web engine). Templates stored in MinIO; classpath defaults used as fallback.
- All approval flows (policy, endorsement, claim) generate and persist a PDF document on approval and store its path on the entity. Document generation is the last step after the status change and event publish.
- Temporal `WorkerFactory` is started by a single `ApplicationReadyEvent` listener in `cia-api` (`TemporalWorkerStarter`). Each module registers its workers via `@PostConstruct` beans before `factory.start()` is called. Never call `factory.start()` inside a module — always delegate to the assembly module.
- `WebhookEventListener` runs **synchronously** (no `@Async`) so `TenantContext` ThreadLocal is still populated on the request thread. Actual HTTP delivery happens asynchronously inside Temporal workflows — the listener only starts a Temporal workflow per matching registration.
- `ClaimSettledEvent` is published by `ClaimService.markSettled()` — consumed by cia-partner-api for webhook fanout.
- `AuditService.log()` publishes `AuditLogCreatedEvent` after every save. `AlertDetectionService` listens with `@Async @EventListener` so alert detection never blocks the calling request thread.
- `cia-audit` depends only on `cia-common` and `cia-notifications`. It does not depend on any business module — business modules publish events; cia-audit consumes them through Spring's event bus.
- `cia-reports` depends only on `cia-common` and `cia-auth`. It does not depend on any business module — `ReportQueryBuilder` uses `EntityManager.createNativeQuery()` directly against the tenant schema. Adding a new pre-built report is a Flyway data migration (`V18+`), not a code change. SYSTEM reports are seeded by Flyway and cannot be deleted or edited; they can only be cloned into CUSTOM reports.
- Report permission resolution in `ReportAccessService`: report-level policy beats category-level policy; if neither exists the user cannot see the report (invisible, not "access denied"). Never show an access-denied state in the UI for reports — absent policies mean the report should not appear in the library or home page at all.
- `audit_alert_config` is a singleton-per-tenant table (one row only, seeded by V16 migration). `AuditAlertConfigService.loadConfig()` always calls `findFirstByOrderByCreatedAtAsc()`.
- Login events (`LoginAuditLog`) are separate from general audit events (`AuditLog`). Login tracking uses `LoginEventType` (LOGIN, LOGOUT, LOGIN_FAILED, PASSWORD_RESET, ACCOUNT_LOCKED); off-hours login detection is triggered directly from `LoginAuditController.loginFailed()` rather than through `AuditLogCreatedEvent`.
- `/api/v1/auth/login/failed` is a **public endpoint** (no JWT required) because it records authentication failures before a valid token exists.
- Lombok must be at `1.18.46` or later. Earlier versions (≤1.18.36) fail with `TypeTag :: UNKNOWN` on JDK 24+.

### Frontend Conventions (`cia-frontend/`)

- The frontend is a **pnpm workspace + Turborepo** monorepo. Run from `cia-frontend/`. Two apps: `@cia/back-office` (port 5173, light mode) and `@cia/partner` (port 5174, dark mode). Three shared packages: `@cia/ui`, `@cia/api-client`, `@cia/auth`.
- The Back Office app is branded **NubSure** — this is the product name shown to end users. The codebase remains CIAGB internally.
- Design tokens are OKLCH (full `oklch(L C H)` values in CSS vars, not channels). Primary accent: `oklch(0.65 0.13 197)` (Nubeero teal). Token file: `packages/ui/src/tokens.css`.
- Fonts: **Bricolage Grotesque** (headings/display) + **Geist** (body/UI). Both loaded via Google Fonts. A scoped `@font-face { family: 'NairaFallback'; src: local('Arial'),...; unicode-range: U+20A6 }` is declared first in each font stack so the ₦ Naira sign always resolves to a system font that has the glyph — Bricolage Grotesque and Geist both lack U+20A6.
- Icons: **hugeicons** v1.1.6. Use `HugeiconsIcon` from `@hugeicons/react` with icon data from `@hugeicons/core-free-icons`. Pattern: `<HugeiconsIcon icon={DashboardSquare01Icon} size={18} color="currentColor" strokeWidth={1.75} />`.
- Module icon mapping: Dashboard→`DashboardSquare01Icon`, Customers→`UserGroupIcon`, Quotation→`NoteEditIcon`, Policies→`Shield01Icon`, Endorsements→`FileEditIcon`, Claims→`AlertCircleIcon`, Finance→`Money01Icon`, Reinsurance→`RepeatIcon`, Setup→`Setting06Icon`, Audit→`Audit01Icon`, Reports→`BarChartIcon`.
- Keycloak: `onLoad: 'login-required'` in production. In dev (`import.meta.env.DEV`), `main.tsx` uses `DevAuthProvider` from `@cia/auth` — a mock context that provides a fake user without any Keycloak round-trip. This lets the UI render locally without the auth stack running.
- `DevAuthProvider` is exported from `@cia/auth` and uses the same `AuthContext` as `AuthProvider`, so all `useAuth()` calls work identically in both modes.
- The Tailwind config in each app imports the shared base via a **relative path** (`../../packages/ui/tailwind.config`) — never via the package name (`@cia/ui/tailwind.config`). Tailwind's PostCSS loader uses CJS `require()` which does not honour the `exports` field.
- `layoutSizingVertical = 'HUG'` in Figma Plugin API must be set **after** all children are appended to a frame. Setting `primaryAxisSizingMode = 'AUTO'` at frame creation time has no effect until children exist.
- Figma BackOffice design file: `Zaiu2K7NvEJ7Cjj6z1xt2D`. Designs are pushed to this file as each module is built using `use_figma` + `upload_assets` (for images). Always invoke the `figma:figma-use` skill before any `use_figma` call.
- To upload PNG assets into Figma, use `mcp__claude_ai_Figma__upload_assets` with a `nodeId` to set the image directly as a fill — this bypasses the unreliable base64 `createImage()` approach.
- **Vercel deployment:** Link and deploy from `cia-frontend/` (monorepo root), never from `apps/back-office/`. Linking from a subdirectory causes Vercel to upload only that directory (~254B), making workspace packages (`@cia/ui`, `@cia/auth`, `@cia/api-client`) unreachable during `pnpm install`. The `vercel.json` and `.vercel/project.json` both live at `cia-frontend/`. Production URL: `back-office-blush-six.vercel.app`.
- **SESSION COMPLETION GATE** is enforced automatically via a Claude Code `Stop` hook in `.claude/settings.json`. It fires at the end of every response in this project — no manual trigger needed.
- **Inline master-data creation pattern:** When a `Select` in a form Sheet references master data (classes of business, brokers, reinsurers, etc.), add a `+ New [Entity]` sentinel item (`value="__create_new__"`) at the bottom separated by `SelectSeparator`. Intercept it in `onValueChange` before calling `field.onChange`. Open a **Dialog** (not a nested Sheet — avoids z-index issues). On save, append the new item to local state and auto-select it via `form.setValue`. This pattern was established in `ProductSheet.tsx` for Classes of Business.

---

## SESSION COMPLETION GATE

**Mandatory before ending any working session on this project.**

Before marking any task complete or saying work is done, Claude MUST verify and complete each item below. No exceptions. Skip sections that are not relevant to the current session (e.g. skip frontend checks if only backend was touched).

---

### 1. cia-log.md
- Append a new entry under today's date (`## YYYY-MM-DD`).
- List every file created or modified (path + one-line description of what changed).
- List every decision made, question resolved, or design choice locked in.
- If a **Build Queue item** was completed or progressed this session, note: which Build number, which sub-pages were completed, and which remain.
- List any open questions raised during the session.

---

### 2. CLAUDE.md
Update the relevant section if any of the following changed:

**Backend:**
- New module added or feature count changed → update Module Summary table
- New Maven module added → update Backend Module Inventory and Dependency Graph
- New architectural decision → update System Architecture section
- New environment variable required → update Environment Variables table (backend section)
- New business rule established → update Key Business Rules section

**Frontend:**
- Frontend monorepo structure changed → update Section 4 (Frontend Architecture)
- New VITE_ environment variable → update Environment Variables table (frontend section)
- New shared package added → update monorepo diagram in Section 4
- App name or branding changed → update Section 4 and note in cia-log.md

**Frontend Build Queue (CLAUDE.md → "Frontend Build Queue"):**
- Any Build or sub-page started → mark `[~]` in progress
- Any sub-page completed → mark `[x]` complete
- Any Build fully completed → mark `[x]` on the Build row AND update the **Build Progress Summary** table at the bottom of the section (Complete count + %)
- Any significant change within an already-completed sub-page (e.g. adding inline class creation to ProductSheet) → update the sub-page description in the table to reflect the new capability

---

### 3. Frontend Build Queue Audit (required after every frontend session)

**This gate is mandatory any time frontend code was written, modified, or deleted.**

Open `CLAUDE.md → "Frontend Build Queue"` and verify:

1. **Sub-page status is accurate.** Each sub-page row in Phase 2 Builds must reflect the current state:
   - `[ ]` — not started (no file exists)
   - `[~]` — in progress (file exists but feature is partial)
   - `[x]` — complete (file exists, all listed key features are implemented, tsc passes)

2. **Build row status is accurate.** If ALL sub-pages under a Build are `[x]`, the Build itself must be `[x]`. If any are `[~]` or `[ ]`, the Build is `[~]`.

3. **Progress Summary is current.** Update the bottom table:
   - Count `[x]` Builds in Phase 1 + Phase 2 + Phase 3
   - Recalculate percentage: `completed ÷ 19 × 100`

4. **Sub-page descriptions are up to date.** If a sub-page was enhanced (e.g. inline create, extra tabs, new field), update its description in the table — do not leave stale one-liners.

Example of what to check after a session that completed the Users sub-page and enhanced Products:
```
| [x] | Users | DataTable + UserSheet (create/edit, access group select)   ← was already [x], no change needed
| [x] | Products | DataTable + ProductSheet (14 seed classes; inline + New Class Dialog) ← description updated
```
Then check the Progress Summary and recalculate if any Build flipped to `[x]`.

---

### 4. SKILL.md (this file)
Update if any of the following changed:

**Backend:**
- Module count or feature count changed → update frontmatter description + Module Inventory header
- New module → add module section
- New business rule → add under Key Cross-Cutting Business Rules
- New entity → add to Data Model Highlights
- New backend development convention → add under Development Conventions

**Frontend:**
- New frontend pattern established → add under Development Conventions → Frontend Conventions subsection
- New icon mapping added → update icon mapping list in Frontend Conventions
- New design token decision → update Frontend Conventions (font, colour, spacing)
- Figma BackOffice file key changed → update in Frontend Conventions

---

### 4. Frontend Verification (required when writing frontend code)

Run before declaring any frontend task complete:

```bash
# From cia-frontend/
pnpm --filter @cia/back-office typecheck   # Must exit 0
pnpm --filter @cia/partner typecheck       # Must exit 0
```

Additional checks:
- Tailwind config imports use **relative paths** (`../../packages/ui/tailwind.config`), never the package name.
- All sidebar nav items use `HugeiconsIcon` from `@hugeicons/core-free-icons` — no raw SVG placeholders.
- Currency amounts displayed in the UI use `₦` (U+20A6). The `NairaFallback` @font-face in `tokens.css` handles rendering — do NOT substitute "NGN" or "N" as a workaround.
- `DevAuthProvider` is only used via `import.meta.env.DEV` conditional — never shipped to production.
- The app is branded **NubSure** in all user-facing strings (sidebar, page title, index.html). The codebase directories and package names remain `back-office` / `@cia/back-office`.

---

### 5. Figma Sync (required when adding or changing a screen)

For every new or significantly changed screen in the Back Office app:
- Push the corresponding frame to the BackOffice Figma file (`Zaiu2K7NvEJ7Cjj6z1xt2D`) using `use_figma`.
- Always invoke the `figma:figma-use` skill BEFORE any `use_figma` call.
- To upload PNG/image assets into Figma, use `mcp__claude_ai_Figma__upload_assets` with `nodeId` — never use `figma.createImage()` with base64 (unreliable in API/screenshot context).
- For the ₦ character in Figma text nodes, apply `setRangeFontName(i, i+1, { family: 'Noto Sans', style: 'Regular' })` to each ₦ character — Bricolage Grotesque and Geist lack this glyph.
- Take a `get_screenshot` after major changes to verify the frame renders correctly.
- Note in cia-log.md: which Figma node IDs were created or mutated.

---

### 6. OpenAPI Annotations (required when writing backend controller code)
Every new or modified endpoint in `cia-partner-api` MUST have all of:
```java
@Operation(summary = "...", description = "...", tags = {"resource-name"})
@ApiResponse(responseCode = "200", description = "...",
    content = @Content(schema = @Schema(implementation = ResponseDto.class)))
@ApiResponse(responseCode = "400", description = "Validation error")
@ApiResponse(responseCode = "401", description = "Unauthorized — invalid or expired token")
@ApiResponse(responseCode = "403", description = "Forbidden — insufficient scope")
@ApiResponse(responseCode = "429", description = "Rate limit exceeded")
@SecurityRequirement(name = "bearer-key")
```
Every DTO used in a partner API response MUST have:
```java
@Schema(description = "...")          // on the class
@Schema(description = "...", example = "...")  // on every field
```

---

### 7. Postman Collection
If any `/partner/v1/` endpoint was added, removed, or its request/response shape changed:
- Note in cia-log.md: "Postman collection regeneration required."
- Regeneration command (run after build): `mvn generate-sources -pl cia-partner-api -Popenapi-gen`
- Output: `cia-partner-api/docs/postman_collection.json`

---

### 8. Backend API Consistency
If any service interface method was added or modified:
- Ensure the method signature, parameter names, and return type clearly express the contract.
- If the change affects a module's feature count in CLAUDE.md or SKILL.md, update those counts.
- If a new internal REST endpoint was added to `cia-api`, note the route and purpose in cia-log.md.

---

### 9. Docs Site (https://cia-docs.vercel.app/) — MANDATORY after EVERY session that touches backend or architecture

**This gate is non-negotiable. Do not mark a session complete if any of the conditions below are true and the corresponding doc update has NOT been made.**

#### 9a. What triggers a docs update

| If this changed this session… | Update this docs file |
|---|---|
| New Maven module added | `docs-site/docs/architecture/modules.md` — inventory tree + dependency table |
| New module architecture | New `docs-site/docs/architecture/<module>.md` + `docs-site/sidebars.ts` |
| Any new `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping` in ANY controller | `docs-site/static/internal-api.json` — add path + schema |
| Any change to an existing endpoint's request body, content-type, or response shape | `docs-site/static/internal-api.json` — update the existing path entry |
| Partner API change (`/partner/v1/`) | `cia-backend/cia-partner-api/docs/openapi.json` (auto-synced on deploy) |
| New env var | `docs-site/docs/guides/environment-variables.md` |
| New Flyway migration | `docs-site/docs/guides/database-migrations.md` |
| Security/auth change | `docs-site/docs/architecture/security.md` |

#### 9b. How to audit `internal-api.json` completeness

Run this check before closing any session that added or changed endpoints:

```bash
python3 -c "
import json, subprocess, re
with open('docs-site/static/internal-api.json') as f:
    spec = json.load(f)
documented = set(spec.get('paths', {}).keys())

# Find all @*Mapping annotations in controllers
result = subprocess.run(
    ['grep', '-rh', r'@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping', '--include=*.java',
     'cia-backend/'],
    capture_output=True, text=True
)
# (manual review — compare annotated paths to documented paths)
print('Documented paths:', len(documented))
for p in sorted(documented): print(' ', p)
"
```

If any `/api/v1/` path from a controller is missing from the documented list, add it before closing.

#### 9c. `internal-api.json` path naming convention

Paths in `internal-api.json` use the **suffix after `/api/v1/`**, NOT the full URL. Examples:
- Controller `@RequestMapping("/api/v1/customers")` → spec path `/customers`
- Controller `@PostMapping("/individual")` → spec path `/customers/individual`
- Endpoint `POST /api/v1/reports/run/csv` → spec path `/reports/run/csv`

#### 9d. Deployment

Committing any file under `docs-site/**` triggers `docs-deploy.yml` → Docusaurus build → `https://cia-docs.vercel.app/`.

**CRITICAL — never change `VERCEL_PROJECT_ID` in `docs-deploy.yml` back to `${{ secrets.VERCEL_PROJECT_ID }}`** — that secret points to the back-office project. The workflow hardcodes `prj_KgaDZ7fSkBNu3r6GEdiV8vAoZyAC` for a reason: the shared secret caused the docs to silently deploy to the wrong Vercel project for 3 days (April 23–26, 2026) before it was caught.

#### 9e. Verification checklist (run before marking gate complete)

- [ ] Ran the audit script in 9b and confirmed all new/changed controller paths are in `internal-api.json`
- [ ] `docs-site/docs/architecture/modules.md` lists all current Maven modules (including `cia-reports` and any new ones)
- [ ] `docs-site/static/openapi.json` matches `cia-backend/cia-partner-api/docs/openapi.json`
- [ ] Committed changes to `docs-site/**` and pushed to `main`
- [ ] GitHub Actions run for `docs-deploy.yml` completed with `✓ success`
- [ ] Log shows `Deploying razormvps-projects/cia-docs` (NOT `back-office`)
- [ ] `cd docs-site && vercel ls` shows a deployment ≤ 5 minutes old
