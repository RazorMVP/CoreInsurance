# Core Insurance Application — General Business (CIAGB)

> Multi-tenant SaaS platform for end-to-end general insurance operations.
> Nigeria-first. NAICOM, NIID, NDPR compliant.
> PRD: https://akinwalenubeero.atlassian.net/wiki/spaces/CIAGB/overview

---

## What We Are Building

The CIAGB replaces fragmented, manual insurance workflows with a single system of record covering the full insurance lifecycle:

**Customer Onboarding → Quotation → Policy Issuance → Endorsements → Claims → Reinsurance → Finance Settlement**

It is architected as a SaaS product: one codebase, multiple insurance company tenants, configured not customised.

---

## Tech Stack

| Layer | Technology | Rationale |
|---|---|---|
| Frontend | React 18 + Vite + TypeScript | SPA; clean separation from backend; fast dev builds |
| UI | Tailwind CSS + shadcn/ui | Consistent, accessible, zero runtime cost |
| Backend | Java 21 + Spring Boot 3.5.14 | Enterprise-grade, strong typing, excellent PostgreSQL/Keycloak/Temporal ecosystem |
| Database | PostgreSQL (schema-per-tenant) | ACID, row-level security, strong financial data guarantees; schema isolation for regulatory compliance |
| Auth | Keycloak | RBAC, multi-tenant organisations, SSO, MFA, audit logs; self-hostable; Spring Security native integration |
| Workflows | Temporal | Durable, crash-safe, retryable workflows for approval chains, claim settlements, NAICOM uploads, notification sequences |
| Storage | S3-compatible abstraction (MinIO for on-prem) | Swap to AWS S3 / GCS / Azure Blob via config; cloud-agnostic |
| AI | Claude API via Anthropic SDK | Optional AI features (underwriting assist, claims triage, document extraction); feature-flagged per tenant |
| Testing | Vitest + JUnit 5 + Testcontainers + Playwright | Unit, integration (real DB via Testcontainers), E2E |
| Deployment | Docker + Kubernetes (cloud-agnostic / on-prem) | Frontend also deployable to Vercel |

---

## System Architecture

### 1. System Context

#### User Roles

| Role | Primary Modules |
|---|---|
| System Admin | Setup & Administration, user management, master data |
| Underwriter | Quotation, Policy, Endorsements, Reinsurance |
| Claims Officer | Claims — notification through to DV execution |
| Finance Officer | Finance — receipts, payments, reconciliation |
| Broker | Customer-linked producer; broker-enabled onboarding |

#### External Systems

| System | Purpose | Integration Pattern |
|---|---|---|
| NAICOM | Nigerian insurance regulator — policy registration, UID generation | Async Temporal activity; stub → live via Spring profile |
| NIID | Nigerian insurance database — motor/marine registration | Async Temporal activity; stub → live via Spring profile |
| KYC Provider | Identity verification (individual + corporate) | Sync at onboarding; `KycVerificationService` abstraction |
| Email Provider | Transactional email (approvals, policies, renewals) | `EmailNotificationService` abstraction |
| SMS Provider | SMS notifications | `SmsNotificationService` abstraction |
| Claude API | Optional AI features per tenant (underwriting assist, claims triage) | Feature-flagged per tenant via `features` config table |

---

### 2. Container Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           CIAGB System Boundary                          │
│                                                                          │
│  ┌────────────────┐    HTTPS/REST    ┌────────────────────────────────┐  │
│  │   React SPA    │ ──────────────▶  │      Spring Boot API           │  │
│  │  (Vite / TS)   │                  │      (cia-api  :8080)          │  │
│  │  Vercel / CDN  │ ◀──────────────  │      19 Maven modules          │  │
│  └────────────────┘                  └───────────────┬────────────────┘  │
│                                                      │                   │
│         ┌────────────────────────────────────────────┼──────────────┐    │
│         │                          │                 │              │    │
│  ┌──────▼───────┐   ┌──────────────▼──┐   ┌─────────▼──┐  ┌───────▼─┐  │
│  │   Keycloak   │   │  PostgreSQL 16   │   │  Temporal  │  │  MinIO  │  │
│  │    :8180     │   │  schema/tenant   │   │   :7233    │  │  :9000  │  │
│  │ realm/tenant │   │     :5432        │   │  UI :8088  │  │  :9001  │  │
│  └──────────────┘   └─────────────────┘   └────────────┘  └─────────┘  │
└──────────────────────────────────────────────────────────────────────────┘

External:  NAICOM API  |  NIID API  |  KYC Provider  |  Email/SMS  |  Claude API
```

| Container | Role | Notes |
|---|---|---|
| React SPA | UI for all 8 modules; Keycloak JS adapter | Vercel or self-hosted CDN |
| Spring Boot API | All business logic, REST controllers, Temporal workers | Single executable JAR; modules as Maven dependencies |
| Keycloak | Auth server — one realm per tenant; JWT issuance; RBAC | Self-hostable; SSO, MFA, user federation |
| PostgreSQL | Persistent store — one schema per tenant | Flyway manages per-tenant schema migrations |
| Temporal | Durable workflow orchestration | Workers embedded inside the Spring Boot process |
| MinIO | Object storage — policy documents, claim photos, KYC uploads | Swappable to AWS S3 / GCS / Azure Blob via config |

---

### 3. Backend Module Architecture

#### Module Inventory

```
cia-backend/
├── cia-common/          # TenantContext, audit, ApiResponse<T>, BaseEntity, exceptions
├── cia-auth/            # Keycloak OAuth2 resource server config, JwtAuthConverter
├── cia-storage/         # DocumentStorageService interface + MinIO/S3/GCS/Azure adapters
├── cia-integrations/    # NAICOM, NIID, KYC interfaces + stub implementations
├── cia-notifications/   # NotificationService, Email + SMS implementations
├── cia-workflow/        # Temporal client config, workflow & activity interfaces
├── cia-documents/       # PDF generation (Apache PDFBox), template rendering, clause bank
├── cia-setup/           # Module 1: Setup & Administration (37 features — Adjuster master data V45; RelationshipManager wired into Customer onboarding via V46; Agent master data V48 — NAICOM-licensed agents that represent the insurer, INDIVIDUAL/CORPORATE type; Broker `license_number` added V49 to close the NAICOM-licence consistency gap with every other regulator-licensed counterparty in the module)
├── cia-customer/        # Module 7: Customer Onboarding & KYC (10 features)
├── cia-quotation/       # Module 2: Quotation (5 features)
├── cia-policy/          # Module 3: Policy (23 features)
├── cia-endorsement/     # Module 4: Endorsements (10 features)
├── cia-claims/          # Module 5: Claims (23 features)
├── cia-reinsurance/     # Module 6: Reinsurance (17 features)
├── cia-finance/         # Module 8: Finance (5 features)
├── cia-partner-api/     # Module 9: Partner Open API (Insurtech connectivity, webhooks, docs)
├── cia-audit/           # Module 10: Audit & Compliance (trail, login logs, reports, alerts)
├── cia-reports/         # Module 11: Reports & Analytics (68 pre-built reports incl. CLOSURES category + RM Commission Accrual, custom builder, CSV/PDF export)
└── cia-api/             # Assembly: main app, REST controllers, Flyway, application.yml
```

#### Module Dependency Graph

```
                         ┌──────────────┐
                         │  cia-common  │   (no internal deps)
                         └──────┬───────┘
           ┌──────────┬─────────┴──────────────────────────┐
           │          │               │                     │
    ┌──────▼───┐ ┌────▼────────┐ ┌───▼──────────┐ ┌────────▼───────┐
    │cia-auth  │ │ cia-storage │ │cia-integra-  │ │cia-notific-    │
    └──────────┘ └────┬────────┘ │tions         │ │ations          │
                      │          └──────────────┘ └────────────────┘
               ┌──────▼──────┐   ┌──────────────┐
               │cia-documents│   │ cia-workflow  │
               └─────────────┘   └──────────────┘
                      │                 │
              ┌───────┴─────────────────┘
              │
    ┌─────────▼───────────────────────────────────────────────┐
    │  Business modules (all depend on cia-common):           │
    │  cia-setup  │ cia-customer  │ cia-quotation             │
    │  cia-policy │ cia-endorsement │ cia-claims              │
    │  cia-reinsurance │ cia-finance                          │
    └─────────┬───────────────────────────────────────────────┘
              │                        │
    ┌─────────▼──────┐    ┌────────────▼──────────┐
    │    cia-api     │    │   cia-partner-api      │
    │  (internal —   │    │  (external Insurtech — │
    │  assembly pt)  │    │   OAuth2 CC, webhooks, │
    └────────────────┘    │   OpenAPI docs)        │
                          └────────────────────────┘
```

**`cia-partner-api` depends on:** `cia-common`, `cia-auth` (JWT filter + scope enforcement), `cia-storage` (DocumentStorageService — PDF download streaming), `cia-setup` (ProductService, ClassOfBusinessService), `cia-customer`, `cia-quotation`, `cia-policy`, `cia-claims`, `cia-workflow` (webhook dispatch), `cia-notifications`.

**`cia-audit` depends on:** `cia-common` (AuditLog, AuditLogRepository, AuditService, AuditLogCreatedEvent), `cia-notifications` (alert delivery via NotificationService). No dependency on any business module — business modules publish events; `cia-audit` consumes them through Spring's ApplicationEvent bus.

**`cia-reports` depends on:** `cia-common` (TenantContext, BaseEntity, ApiResponse), `cia-auth` (JWT, access group resolution). No dependency on any business module — `ReportQueryBuilder` uses `EntityManager.createNativeQuery()` directly against the tenant schema. Adding a new pre-built report is a Flyway data migration (V18+), not a code change.

**Cross-module wiring within business modules:**

- `cia-policy` → `cia-workflow` (approval), `cia-documents` (PDF), `cia-integrations` (NAICOM/NIID)
- `cia-endorsement` → `cia-workflow` (approval), `cia-documents` (endorsement PDF)
- `cia-claims` → `cia-workflow` (approval + DV), `cia-documents` (DV PDF)
- `cia-customer` → `cia-integrations` (KYC)
- `cia-quotation` → `cia-workflow` (approval)
- `cia-partner-api` → listens for `PolicyApprovedEvent`, `EndorsementApprovedEvent`, `ClaimApprovedEvent`, `ClaimSettledEvent` via Spring application events → Temporal webhook fanout
- All modules → `cia-notifications` (email/SMS events via Spring application events)

---

### 4. Frontend Architecture

#### Monorepo Structure

The frontend is a **pnpm workspace + Turborepo** monorepo under `cia-frontend/`.

```
cia-frontend/
├── apps/
│   ├── back-office/          # NubSure Back Office — port 5173, light mode
│   │   ├── public/
│   │   │   └── logo.png      # Nubeero circular "n" logo (PNG, 3726×3726 RGBA)
│   │   ├── src/
│   │   │   ├── app/
│   │   │   │   ├── layout/   # AppShell, Sidebar, Topbar
│   │   │   │   ├── router.tsx
│   │   │   │   └── globals.css
│   │   │   ├── modules/      # One directory per business module (lazy-loaded)
│   │   │   │   ├── dashboard/
│   │   │   │   ├── setup/
│   │   │   │   ├── customers/
│   │   │   │   ├── quotation/
│   │   │   │   ├── policy/
│   │   │   │   ├── endorsements/
│   │   │   │   ├── claims/
│   │   │   │   ├── reinsurance/
│   │   │   │   ├── finance/
│   │   │   │   └── audit/
│   │   │   ├── main.tsx
│   │   │   └── App.tsx
│   │   ├── tailwind.config.ts
│   │   └── vite.config.ts
│   └── partner/              # Partner Portal — port 5174, dark mode
├── packages/
│   ├── ui/                   # @cia/ui — design tokens, shadcn components, cn()
│   ├── api-client/           # @cia/api-client — Axios factory, React Query types
│   └── auth/                 # @cia/auth — Keycloak adapter, AuthProvider, DevAuthProvider
├── pnpm-workspace.yaml
├── turbo.json
└── tsconfig.base.json
```

**Turborepo pipeline:** `build` depends on `^build` — `@cia/ui` always builds before apps.

#### Design System

| Token | Value |
|---|---|
| Primary accent | `oklch(0.65 0.13 197)` — Nubeero teal |
| Background (Back Office) | `oklch(0.985 0.003 197)` — warm off-white |
| Background (Partner) | `oklch(0.15 0.012 240)` — dark charcoal |
| Display font | Bricolage Grotesque + `NairaFallback` (unicode-range U+20A6) |
| Body/UI font | Geist + `NairaFallback` (unicode-range U+20A6) |
| Icon library | hugeicons v1.1.6 (`@hugeicons/react` + `@hugeicons/core-free-icons`) |
| Token format | OKLCH (full `oklch(L C H)` values in CSS vars — not channels) |

**Naira sign (₦):** Bricolage Grotesque and Geist do not include U+20A6. A scoped `@font-face { font-family: 'NairaFallback'; src: local('Arial'), ...; unicode-range: U+20A6; }` is declared in `tokens.css` and placed first in both font stacks so the ₦ glyph always resolves to a system font that has it.

#### Layout Shell (Back Office)

```
AppShell
├── <aside> (width: 256px collapsed→64px, transition: 220ms ease-out)
│   └── Sidebar
│       ├── Logo row: [Nubeero logo 28px] [NubSure] [≡ hamburger toggle]
│       ├── Nav groups (OPERATIONS / FINANCE & RI / ADMINISTRATION)
│       │   └── NavLink with hugeicons icon + label (hidden when collapsed)
│       └── User row: avatar + name/email + logout
└── Right panel
    ├── Topbar: [Page title] [Search bar — flex-1] [🔔 notification] [? help]
    └── <main> (lazy Suspense outlet)
```

**Frontend patterns:**

- React Query for all server state — no Redux for remote data.
- Keycloak JS adapter; `onLoad: 'login-required'` in production, `'check-sso'` in dev.
- Token auto-refreshed every 30 seconds; 401 responses dispatch `cia:unauthorized` custom event.
- `X-Tenant-ID` resolved from Keycloak JWT at the `@cia/api-client` Axios interceptor.
- Lazy-loaded module routes — each module chunk loaded on first visit; skeleton fallback via `Suspense`.
- shadcn/ui extended via CVA variants (never patched at source) to maintain upgrade path.
- Sidebar collapses to 64px icon-only mode; toggle button lives in the sidebar logo row.
- `DevAuthProvider` in `@cia/auth` provides mock user context for local dev without Keycloak running. Used via `import.meta.env.DEV` conditional in `main.tsx`.

---

### 5. Key System Flows

#### 5.1 Synchronous API Request Lifecycle

```
Browser
  │  HTTPS + Bearer JWT
  ▼
NGINX / Load Balancer
  │  Subdomain resolved → X-Tenant-ID header injected
  ▼
Spring Boot Filter Chain
  ├── JwtAuthenticationFilter    → validates JWT against Keycloak JWKS endpoint (cached)
  ├── TenantContextFilter        → reads tenant_id claim → sets Hibernate schema ThreadLocal
  └── @PreAuthorize              → role check e.g. hasAuthority("underwriting:create")
  ▼
Service Layer  (business logic, approval rules, premium calculation)
  ▼
JPA Repository
  │  MultiTenantConnectionProvider routes connection to correct PostgreSQL schema
  ▼
PostgreSQL  (tenant schema)
  ▼
ApiResponse<T> { data, meta, errors }  → JSON response
```

#### 5.2 Policy Approval Workflow (Temporal)

```
POST /api/v1/policies/{id}/submit
  ▼
PolicyService.submitForApproval()
  └── temporalClient.newWorkflowStub(PolicyApprovalWorkflow).start(policyId)
        │
        ├── Activity: resolveApprover()       — find approver(s) for the policy amount
        ├── Activity: notifyApprover()        — in-app notification + email
        │
        │   [Signal: approved | rejected | timeout → escalate]
        │
        ├── [Multi-level] move to next approver tier, repeat
        │
        ├── Activity: approvePolicy()
        │     ├── policy.status → ACTIVE
        │     ├── Generate policy PDF        (cia-documents)
        │     └── Create debit note          (→ cia-finance)
        │
        ├── Activity: uploadToNaicom()        — child workflow, non-blocking (see 5.3)
        ├── Activity: uploadToNiid()          — motor / marine only
        └── Activity: sendPolicyDocument()   — email PDF to insured
```

#### 5.3 NAICOM / NIID Post-Approval Upload

```
Policy approved
  └── NaicomUploadWorkflow starts (child workflow, async — does not block approval)
        │
        ├── Certificate immediately generated with naicom_uid = "PENDING"
        │
        └── Retry loop with exponential backoff (5 min → 15 min → 1 hr → indefinite):
              └── NaicomIntegrationService.uploadPolicy(policyId)
                    ├── [dev/test]  StubNaicomService   → returns mock UID instantly
                    └── [prod]      NaicomRestService   → POST to NAICOM REST API
              On success:
                ├── policy.naicom_uid updated
                └── Certificate regenerated with real UID

Manual trigger:
  POST /api/v1/policies/{id}/naicom-upload
    → signals running workflow OR starts new workflow if missing
```

#### 5.4 New Tenant Provisioning (Slice A — `cia-api/tenant/`)

Implemented as a **gated `ApplicationRunner`** (`TenantBootstrapRunner`, `@ConditionalOnProperty(cia.tenants.bootstrap.enabled=true)` — **off by default**, so dev + the IT suite never provision). On boot it drives `TenantProvisioningService.provision(spec)` per configured tenant, then sweeps `public.tenants WHERE active` and re-migrates every schema. **Fail-fast**: any step throwing aborts startup (k8s keeps the prior pod). Requires `cia.keycloak.admin.enabled=true` (the orchestrator fails fast if the admin client is absent).

```
TenantBootstrapRunner (gated, on boot)
  └── per configured tenant → TenantProvisioningService.provision(spec):
        0. generate deterministic Administrators access-group UUID from the schema name
        1. TenantSchemaMigrator.ensureSchema(schema)   — CREATE SCHEMA IF NOT EXISTS
        2. TenantSchemaMigrator.migrate(schema)        — programmatic Flyway-per-schema:
             baselineVersion=2 (skips V1 public.tenants registry + V2 vestigial template_);
             BEFORE_EACH_MIGRATE callback pins search_path to "<schema>", public;
             pgcrypto pre-installed in public; each tenant gets its own flyway_schema_history
        3. TenantSeeder.seed(schema, adminGroupId)     — Administrators access group (+ perms),
             NGN currency, customer_number_format singleton (single pinned connection)
        4. KeycloakTenantProvisioner.provisionTenantAuth(realm, FirstAdminSpec):
             realm + back-office client + unmanaged-attr policy + ALL BootstrapRoles.ALL realm
             roles + first-admin user (temp password → UPDATE_PASSWORD forced reset; accessGroupId
             attribute = the generated UUID; no SMTP dependency)
        5. TenantRegistry.upsert(schema, name, subdomain)  — public.tenants (registry LAST)
  └── then sweep public.tenants WHERE active → migrate each (idempotent)
```

Tenants are declared under `cia.tenants.bootstrap.tenants[]` (schema, realm, display-name, subdomain, admin-username, admin-email, admin-temp-password). Adding a tenant = edit config + restart. A REST admin provisioning API is deferred (needs a platform-admin/super-admin auth story). **Note (`migration-not-edited`):** no existing migration was changed — `baselineVersion=2` is what skips V1/V2 cleanly; the three tables V2 would create (`audit_log`, `partner_apps`, `webhook_registrations`) are re-created unqualified by V12/V13 into each tenant schema.

---

### 6. Multi-Tenancy Model

- **Schema-per-tenant** in PostgreSQL. Each insurance company gets its own isolated schema (e.g., `tenant_acme`, `tenant_leadway`).
- Tenant resolved via subdomain (`acme.cia.app`) or `X-Tenant-ID` header; the value is embedded as a custom claim in the Keycloak JWT.
- Keycloak realm per tenant for complete auth isolation — a token from Tenant A cannot authenticate against Tenant B. **Wired at the resource-server layer (S141):** `TenantIssuerJwtAuthenticationManagerResolver` validates each JWT against *its own realm's* JWKS (resolved from the `iss` claim), not a single fixed issuer — so a second tenant is genuinely isolated by realm.
- All ORM queries are tenant-scoped via Hibernate's `MultiTenantConnectionProvider` and `CurrentTenantIdentifierResolver` — no cross-schema query is possible through the application layer.
- Per-tenant configuration (stored in tenant schema): products, classes of business, approval groups, policy number formats, AI feature flag, KYC provider, notification providers.
- Tenant schemas provisioned + migrated + seeded at boot by the gated `TenantBootstrapRunner` → `TenantProvisioningService` (§5.4, Slice A); all subsequent migrations run against every active tenant schema on API startup via the registry sweep (Flyway-per-schema, baselined past V1/V2). **`public` is the system/registry schema** (holds `public.tenants`); the `template_` schema created by V2 is vestigial and skipped for tenant schemas. **Runtime pgcrypto (closed in Slice C):** `MultiTenantConnectionProvider.getConnection(tenant)` sets `search_path TO "<tenant>", public` (was `setSchema(tenant)` → tenant only), so `pgp_sym_encrypt`/`pgp_sym_decrypt` (in `public`) resolve for every real tenant — NDPR PII encryption works at multi-tenant runtime. The tenant identifier is regex-guarded (`TenantSchemas.validate`, promoted to `cia-common`) before interpolation. Proven by `MultiTenantConnectionProviderSearchPathIT`.
- **Platform-admin plane (SP1) — a realm *above* the tenants.** A dedicated `platform` Keycloak realm (config `cia.platform.realm`, default `platform`) holds the cross-tenant **`SUPER_ADMIN`** realm role (`PlatformRoles.ALL` — deliberately distinct from `BootstrapRoles.ALL`, so `SUPER_ADMIN` is **never** seeded into any tenant realm). Super-admins drive runtime tenant lifecycle via `/api/v1/platform/**` (onboard / list / suspend / activate / audit — §8). `TenantContextFilter` scopes a platform-realm token to the `public` (registry) schema, not a tenant. **Tenant-activation allowlist gate (closes backlog `jwt-resolver-registry-allowlist`):** when `cia.platform.tenant-allowlist.enabled=true`, a non-platform request whose realm is absent from `public.tenants` or has `active=false` is rejected **401 `TENANT_INACTIVE`** (`WWW-Authenticate: Bearer error="inactive_tenant"`); the platform realm is **exempt**, and the gate **fails closed** if enabled with no `TenantActivationLookup` wired. The lookup (`RegistryTenantActivationLookup`) is a TTL-cached `public.tenants` read, evicted immediately on suspend/activate. Off by default (tenant-lifecycle hardening, not a live hole — a lingering realm still fails closed at the schema layer). The provisioning bootstrap is a gated `PlatformBootstrapRunner` (`cia.platform.bootstrap.enabled`, off by default), mirroring §5.4's tenant runner.

---

### 7. Data Architecture

**Schema strategy:** `public` schema holds only shared infrastructure (tenant registry). All business tables live in the tenant-specific schema.

**Connection pooling:** A **single shared HikariCP pool** (not pool-per-tenant). `MultiTenantConnectionProvider` borrows one connection from the shared pool per unit of work and switches `search_path` to `"<tenant>", public` for the borrow (`public` included so shared extensions — pgcrypto — and the registry resolve), resetting to `public` on release. Pool sizing is tuned in `application-prod.yml` (env-overridable `DB_POOL_*`).

**Optional read replica (cia-reports read-scaling, `db-backup-dr` Deliverable B).** When `CIA_DATASOURCE_REPLICA_URL` is set, `ReadReplicaDataSourceConfig` (cia-api, `@ConditionalOnProperty`) takes over the datasource definition and builds **two** Hikari pools (primary + replica) behind a `ReplicaRoutingDataSource` (`AbstractRoutingDataSource`, `@Primary`) — which `MultiTenantConnectionProvider` injects, so the same per-borrow `search_path` SET runs on whichever pool the borrow resolved to (tenant isolation unchanged on both). Routing is **targeted, not readOnly-flag-based**: there are ~167 `@Transactional(readOnly=true)` reads across the app, and routing all of them to a replica would expose every read-after-write flow (e.g. post receipt → view balance) to replication lag — unacceptable for regulated financial data. So only the lag-tolerant `cia-reports` report-run path opts in, by wrapping its query in `ReplicaRoutingContext.onReplica(...)` (a `cia-common` ThreadLocal read by the routing datasource); every write, Flyway migration, and non-report read stays on the **default** primary. The replica pool carries the same `connection-init-sql` (`SET app.pii_key`) so `@ColumnTransformer` PII decrypts on replica reads. **Additive:** with `CIA_DATASOURCE_REPLICA_URL` unset the config is inert (Boot's normal single-pool autoconfig runs — byte-identical to today). Proven by `ReplicaRoutingDataSourceIT` (two Postgres containers: read routes to replica, write/default to primary, search_path works on both) + `ReadReplicaDataSourceConfigTest` (conditional `@Primary` wiring) + `ReplicaRoutingContextTest`. **A replica is read-scaling, not DR** — see [`docs-site/docs/operations/disaster-recovery.md`](docs-site/docs/operations/disaster-recovery.md).

**Flyway migrations** (`cia-api/src/main/resources/db/migration/`):

- `V1__init_public_schema.sql` — tenant registry in `public`.
- `V2__init_tenant_schema.sql` — all business tables; applied per schema on tenant provisioning.
- Subsequent migrations (`V3__...`) applied to all tenant schemas automatically on startup.
- Never edit an existing migration file — always create a new versioned file.

**Table conventions:**

- All entities extend `BaseEntity`: `id` (UUID), `created_at`, `updated_at`, `created_by`, `deleted_at` (soft delete for master data).
- All foreign keys enforced at database level.
- Indexes on all FK columns and high-cardinality filter columns (`status`, `policy_number`, `customer_id`, `class_of_business_id`).
- JSONB for flexible payloads: `risk_details` on policies, `old_value` / `new_value` in audit log.

---

### 8. Security Architecture

**JWT authentication flow:**
```
1. User visits tenant subdomain → React SPA loads
2. Keycloak JS adapter: no session → redirect to Keycloak login (tenant realm)
3. User authenticates → Keycloak issues RS256 JWT
   Claims: sub (user_id), realm_access.roles, tenant_id (custom claim)
4. React attaches JWT as Authorization: Bearer on every API request
5. Spring Security validates JWT signature using the **token's own realm JWKS**, resolved per `iss` claim by `TenantIssuerJwtAuthenticationManagerResolver` (realm-per-tenant — see §8 note); foreign/unknown issuers → 401
6. JwtAuthConverter maps realm_access.roles → Spring GrantedAuthority list
7. TenantContextFilter sets the tenant from the **validated realm in `iss`** (authoritative), falling back to the `tenant_id` claim → CurrentTenantIdentifierResolver ThreadLocal
8. @PreAuthorize on controllers enforces authority requirements per endpoint
9. Hibernate routes all queries to the correct tenant schema for that thread
```

**Tenant realm provisioning (S118 → S119 → S139).** Tenant Keycloak realms must have `UnmanagedAttributePolicy=ENABLED` on the user-profile config — without it, Keycloak 24's default `DISABLED` policy silently drops the `accessGroupId` attribute `UserService.create` writes, breaking the F1e-sync-AccessGroup-fanout. As of S119, this is **automated by `KeycloakTenantBootstrap`** (an `ApplicationRunner` in `cia-setup/keycloak/`) which runs on every application startup when `cia.keycloak.admin.enabled=true`: it ensures the target realm exists and that `UnmanagedAttributePolicy=ENABLED` is set, idempotently. Operations no longer need a separate provisioning step — restart the app and the realm config heals. The Testcontainers IT harness (`KeycloakItSupport.ensureTestRealm`) delegates to the same `KeycloakTenantProvisioner` so tests and production exercise identical code. **S139 extends `KeycloakTenantProvisioner` to also upsert the back-office SPA public client** (`cia.keycloak.admin.back-office-client-id`, default `cia-back-office`) — auth-code + PKCE(S256), realm-scoped redirect URIs / web origins (`back-office-redirect-uris`, default the local Vite origin), and a **hardcoded `tenant_id` claim mapper** whose value is the realm name (realm-per-tenant ⇒ realm name *is* the tenant id, so every user gets the right tenant with no per-user attribute). Without this client the SPA login fails "Client not found"; without the mapper `TenantContextFilter` can't resolve the tenant. The client upsert is idempotent (create-then-reconcile: public/standard-flow/redirects/PKCE drift + single `tenant_id` mapper). **Not** provisioned here: per-partner OAuth2 service-account clients (created on demand by Partner Management). S139 also adds an optional **branded login theme** — `back-office-login-theme` (default blank = leave realm theme untouched, so ITs and un-themed Keycloaks are unaffected); set to `nubsure` to apply the NubSure-branded Keycloak login theme at `docker/keycloak/themes/nubsure/` (mirrors the SPA design tokens — Bricolage Grotesque display + Geist body via Google Fonts, Nubeero teal accent, "NubSure" wordmark; mounted into the Keycloak container via docker-compose).

**Realm-per-tenant JWT validation (S141).** The resource server no longer validates a single fixed issuer. `TenantIssuerJwtAuthenticationManagerResolver` (`cia-auth`) backs `.oauth2ResourceServer(o -> o.authenticationManagerResolver(...))` and resolves the `AuthenticationManager` from each token's `iss` claim, validating against **that realm's own JWKS**. **Trust model = base-URL:** an issuer is trusted iff it exactly equals `{cia.keycloak.server-url}/realms/{realm}` for a non-empty realm (we own the Keycloak, so every realm on it is a tenant); foreign / malformed / realmless issuers → `InvalidBearerTokenException` (HTTP 401, never 500). Per-realm decoders are built **lazily on first token and cached** (`ConcurrentHashMap`), so there is **no OIDC-discovery HTTP call at startup** (this also preserves the IT pattern of `@MockBean JwtDecoder` + `@WithMockUser`, which never exercises the resolver). `TenantContextFilter` then derives the tenant from the **validated realm in `iss`** (authoritative — it's what the signature was checked against), falling back to the S139 `tenant_id` claim. The old `SecurityConfig.jwtDecoder()` bean and the `spring.security.oauth2.resourceserver.jwt.issuer-uri` property were removed (leaving the property would make Boot autoconfig build an eager startup decoder). **Allowlist gate wired in SP1 (closes the former `jwt-resolver-registry-allowlist` backlog):** `TenantContextFilter` now optionally checks the resolved realm against `public.tenants` (active) via `TenantActivationLookup` — see the platform-admin plane note below; off by default, platform-exempt, fails closed. `KeycloakAdminProperties.targetRealm` (user-CRUD admin client) remains single-realm; per-tenant admin resolution is separate.

**Platform-admin plane (SP1) — cross-tenant super-admin + tenant lifecycle API.** A `SUPER_ADMIN` realm role, minted only by the dedicated `platform` Keycloak realm (`PlatformRoles.ALL`, distinct from tenant `BootstrapRoles.ALL` — and from tenant `PLATFORM_ADMIN`, which carries **no** cross-tenant power), gates `PlatformTenantController` at `/api/v1/platform/**` (`@PreAuthorize("hasRole('SUPER_ADMIN')")` + `@EnableMethodSecurity`). **Defense-in-depth:** every endpoint also calls `assertPlatformRealm(jwt)`, which re-checks the *validated* `iss` realm equals `cia.platform.realm` and throws `AccessDeniedException` (→ 403) otherwise — so a stray same-named role in any other realm can never reach these. Surface: `POST /tenants` (onboard → real schema+Flyway+seed + tenant Keycloak realm/first-admin + `public.tenants` row; returns a **server-generated one-time temp password**, never logged), `GET /tenants`, `GET /tenants/{schema}`, `POST /tenants/{schema}/{suspend,activate}` (flip `public.tenants.active` + evict the activation cache + dual audit), `GET /audit`. Every mutation writes a `platform_audit_log` row (V67, in `public`) **and** a structured app-log line (`PlatformAuditService` — audit-write failures propagate, by design). The suspend/activate path is what makes the §6 allowlist gate operational. No hard tenant delete (regulated data — suspend only; backlog `platform-hard-delete-tenant`). The back-office SPA's platform-admin UI (`apps/platform`) is the next sub-project (SP2; backlog `platform-admin-ui-sp2`).

**RBAC mapping:**

| Keycloak Role | Spring Authority | Usage |
|---|---|---|
| `{module}_create` | `{module}:create` | POST endpoints |
| `{module}_view` | `{module}:view` | GET endpoints |
| `{module}_update` | `{module}:update` | PUT / PATCH endpoints |
| `{module}_approve` | `{module}:approve` | Approval actions |

Access groups aggregate roles; users inherit permissions through their access group. Approval groups are separate — they define who can approve transactions within configured amount ranges.

**NDPR compliance:**

- High-risk PII fields encrypted at rest via PostgreSQL `pgcrypto` (V24): `customers.id_number`, `customers.id_document_url`, `customers.address`, `customer_directors.id_number`, `customer_directors.id_document_url`. Implemented via Hibernate `@ColumnTransformer` wrapping `pgp_sym_encrypt` / `pgp_sym_decrypt` and `current_setting('app.pii_key')`. The session var is set per Hikari connection from `cia.security.pii-key` (env `PII_ENCRYPTION_KEY`).
- Search-critical fields (`first_name`, `last_name`, `email`, `phone`, `date_of_birth`) intentionally remain plain — substring search on encrypted bytea isn't possible without companion HMAC-indexed columns. Adding those is a follow-up.
- All data access logged to per-tenant audit table.
- Data retention period enforced per tenant config via scheduled Temporal purge workflow.
- Data export endpoint available to satisfy NDPR data subject access requests.

**CORS (browser SPA cross-origin access).** The internal `/api/**` surface is served to two browser SPAs (back-office + platform console) hosted on a **different origin** than the API (Vercel ⇄ API host), so cross-origin requests need an explicit CORS policy. `CorsConfig` (`cia-auth`) builds a single `CorsConfigurationSource` from `cia.cors.allowed-origins` / `cia.cors.allowed-origin-patterns` (`CiaCorsProperties`) and it is wired into **both** the prod `SecurityConfig` and the dev `DevSecurityConfig` filter chains (CORS lives in the security filter chain, so preflight `OPTIONS` is answered *before* authentication — wiring it into MVC alone would not let preflight past the auth filters). Policy: `allowCredentials(true)` (the SPA sends `Authorization: Bearer`, which **forbids the `*` origin wildcard** — origins are enumerated or pattern-matched), methods `GET/POST/PUT/PATCH/DELETE/OPTIONS`, allowed headers `Authorization,Content-Type,X-Tenant-ID`, and **`Content-Disposition` exposed** so the blob/ZIP download flows (PDF receipts, bulk ZIP) can read the server-supplied filename. The default origins are the local Vite dev ports; **prod must set `CIA_CORS_ALLOWED_ORIGINS`** to the real SPA URLs. The partner API (`/partner/**`) is machine-to-machine (OAuth2 client-credentials, no browser origin) and is deliberately left **without** CORS.

**File-upload validation.** Every multipart upload is validated server-side by `FileUploadValidator` (`cia-common.upload`) **before** streaming to `DocumentStorageService` — applied at all 5 upload sites (the 3 customer KYC/CAC/director endpoints funnel through `CustomerService.uploadKycDocument`; `ClaimDocumentService.upload`; `DocumentTemplateService.upload`). Each site passes a `FileUploadPolicy` (allowed content types + max size): KYC/CAC/director docs `imagesAndPdf`/5 MB, claim docs `imagesAndPdf`/10 MB, templates `htmlAndPdf`/5 MB. The validator checks, in order: not-empty → size ≤ policy cap → declared `Content-Type` in the allowlist → **magic-byte signature** (`FileSignatures`: PDF/JPEG/PNG) so a spoofed content-type can't smuggle a different payload (text/html has no signature → content-type only) → a pluggable scan hook. Violations throw `FileValidationException` → **422** (`EMPTY_FILE` / `FILE_TOO_LARGE` / `UNSUPPORTED_FILE_TYPE` / `FILE_CONTENT_MISMATCH`). **Two size limits by design:** the per-policy caps (422) sit below the global servlet hard cap `spring.servlet.multipart.max-file-size=15MB` (`max-request-size=75MB`), which 413s only abusive uploads via a `GlobalExceptionHandler` branch (`PAYLOAD_TOO_LARGE`) — the 15 MB cap also fixed the silent 1 MB default that 500'd 5 MB KYC uploads. **Virus scan is the `FileScanService` SPI** — `NoOpFileScanService` (default, `cia.upload.scan.provider=none`, `matchIfMissing`); a real ClamAV/API impl registers under a different provider value (mirrors the KYC/email/SMS integration pattern). The validator reads the file's first 8 bytes via `getInputStream()` and the call site re-opens it for the upload — proven safe (Spring's buffered `StandardMultipartFile`) by the end-to-end `DocumentTemplateUploadValidationIT`.

---

### 9. Integration Architecture

All external integrations share the same pattern: **interface → stub implementation (dev/test) → live implementation (prod) — swapped via Spring `@Profile`, zero business logic changes required.**

| Integration | Interface | Stub | Live Implementation(s) | Trigger |
|---|---|---|---|---|
| NAICOM | `NaicomIntegrationService` | `StubNaicomService` | `NaicomRestService` | Post-approval Temporal activity |
| NIID | `NiidIntegrationService` | `StubNiidService` | `NiidRestService` | Post-approval Temporal activity (motor/marine) |
| KYC | `KycVerificationService` | `MockKycService` | `DojahKycService` / `PremblyKycService` / `NibssKycService` | Sync at customer onboarding |
| Email | `EmailNotificationService` | `LoggingEmailService` | `SendGridEmailService` / `SmtpEmailService` | Spring application event |
| SMS | `SmsNotificationService` | `LoggingSmsService` | `TermiiSmsService` / `TwilioSmsService` | Spring application event |
| Storage | `DocumentStorageService` | `LocalDocumentStorageService` | `MinioStorageService` / `S3StorageService` / `GCSStorageService` | Document upload / download |
| AI | `AiAssistService` | disabled (no-op) | `ClaudeAiAssistService` | On-demand; gated by per-tenant feature flag |

---

### 10. Deployment Architecture

#### Local development (docker-compose)

```
docker-compose up
  ├── postgres:16        :5434   — PostgreSQL (5432 in container)
  ├── keycloak:24        :8280   — Keycloak (8080 in container; dev realm pre-seeded)
  ├── temporal-auto-setup :7233   — Temporal server
  ├── temporal-ui         :8088   — Temporal workflow browser (8080 in container)
  ├── minio              :9000   — Object storage
  │                       :9001   — MinIO console
  └── redis:7-alpine     :6380   — Redis (6379 in container; partner-API rate-limit cache)

Spring Boot (cia-api)  :8090    — see "Run the backend" below
Vite dev server        :5173    — pnpm --filter @cia/back-office dev
```

**Run the backend.** From `cia-backend/`:

```bash
# First time + after non-trivial changes — install all module SNAPSHOTs to ~/.m2
mvn install -DskipTests -pl cia-api -am

# Start the API with the dev Spring profile active
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run -pl cia-api -Dspring-boot.run.profiles=dev
```

**Why `install` and not just `compile`:** `mvn spring-boot:run` resolves dependencies from `~/.m2/repository`, NOT from the in-reactor `target/classes` directories. After editing a non-cia-api module (e.g. `cia-claims`), running `spring-boot:run` without an `install` first will silently load the previous SNAPSHOT jar from m2 — endpoints added since the last `install` won't appear at runtime. Symptom: Springdoc reports a path count below the static `internal-api.json`. Fix: `mvn install -DskipTests -pl cia-api -am` and restart.

**Why two profile flags:** `SPRING_PROFILES_ACTIVE=dev` activates the dev profile for the JVM Spring loads; `-Dspring-boot.run.profiles=dev` does the same for the spring-boot-maven-plugin's forked process. Setting both is the safe combination.

The Maven `-Pdev` flag is a *Maven* profile, not a Spring profile — there is no `dev` Maven profile defined, so passing it produces a "profile could not be activated" warning and otherwise does nothing.

#### Production (Kubernetes)

```
Ingress (NGINX)
  ├── {tenant}.cia.app  → React SPA (Vercel edge or static pod)
  └── api.cia.app       → cia-api Service (3+ replicas, HPA on CPU)

Deployments:
  ├── cia-api             3+ replicas; Temporal workers embedded — no separate worker pod
  ├── keycloak            2+ replicas; PostgreSQL-backed sessions
  ├── temporal-frontend   1+ replicas
  ├── temporal-history    3+ replicas
  └── temporal-matching   2+ replicas

Managed / self-hosted services:
  ├── PostgreSQL          RDS / Cloud SQL / self-hosted Patroni
  ├── Object Storage      S3 / GCS / Azure Blob / MinIO
  └── Redis               Optional — Keycloak session cache, rate-limit counters
```

**Backend Helm chart (Slice B):** `deploy/helm/cia-backend/` deploys **cia-api only** — Deployment + Service + Ingress (`/api` + `/partner` **only** — `/actuator/**` is never publicly routed) + HPA (CPU 3→10 @70%) + PDB (`minAvailable: 2`) + ConfigMap + ServiceAccount. The 5 backing services (Postgres, Keycloak, Temporal, object storage, Redis) are **external** — endpoints supplied via the ConfigMap env (URLs) + a pre-existing `Secret` referenced by `values.existingSecret` (the chart never templates secret values; see `secret.example.yaml` for the key contract). Deployment is hardened: `runAsNonRoot`, `readOnlyRootFilesystem` + `/tmp` emptyDir, drop-all-caps, `RuntimeDefault` seccomp, startup/liveness/readiness probes on `/actuator/health/*`, `checksum/config` rollout trigger. A real prod deploy MUST set BOTH `SPRING_PROFILES_ACTIVE=prod` AND `CIA_DEPLOYMENT_ENVIRONMENT=production` (neither implies the other — `ProductionSafetyValidator` keys off the marker). Pin `image.tag` to a built commit SHA (the chart `required`-guards an empty tag). Validated in CI by `helm-chart.yml` — `helm lint` + `kubeconform -strict` (default + prod-example values) + a `kind` smoke that boots the real image against ephemeral Postgres (**Temporal intentionally absent** — the app boots Temporal-degraded by design, proven by `TemporalUnreachableBootIT`; the smoke points `TEMPORAL_HOST` at a non-existent host) and asserts `/actuator/health` 200. **Not in this slice (separate go-live step):** a live cluster, DNS / cert-manager issuer, the secret-store wiring, and the managed backing-service endpoints; plus a deploy-to-cluster CD step.

**Frontend deployment:**

- Vercel project linked at `cia-frontend/` (monorepo root — NOT `apps/back-office/`). Vercel must upload the full workspace to resolve workspace packages during install.
- `vercel.json` lives at `cia-frontend/`. Build: `pnpm --filter @cia/back-office build`. Output: `apps/back-office/dist`. SPA rewrite: `/* → /index.html`.
- `.vercel/project.json` at `cia-frontend/`: `projectId: prj_d9m8fgnCZlKe0xTYjeRcnSMAQnHm`, `orgId: team_7FziB9JbVAXmjPfdIdf5aO19`.
- Auto-deploy via `.github/workflows/vercel-deploy.yml` — preview on PR, production on push to `main`, filtered to `cia-frontend/**` changes.
- **Production URL:** `back-office-blush-six.vercel.app`
- GitHub secrets required: `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` (back-office project ID).
- `VITE_API_BASE_URL`, `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, `VITE_KEYCLOAK_CLIENT_ID` set as Vercel environment variables per environment (dev / staging / prod).
- **Platform console (`apps/platform`, SP2):** a **separate** Vercel project building `apps/platform` (dark-mode super-admin console). CI: `.github/workflows/vercel-deploy-platform.yml` (preview on PR, prod on push to `main`, filtered to `cia-frontend/**`; mirrors `vercel-deploy.yml`). `cia-frontend/apps/platform/vercel.json` carries `buildCommand: pnpm --filter @cia/platform build`, `outputDirectory: apps/platform/dist`, SPA rewrite, asset cache headers. Required GitHub secret: **`VERCEL_PLATFORM_PROJECT_ID`** (the platform project id; `VERCEL_TOKEN` / `VERCEL_ORG_ID` are shared with back-office). Env vars per environment: `VITE_API_BASE_URL`, `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM` (= `platform`), `VITE_KEYCLOAK_CLIENT_ID` (= `cia-platform`), and `VITE_DEMO_MODE=true` on the public preview only. **The platform app deliberately reuses the `VITE_KEYCLOAK_*` names** (scoped per-deployment, pointed at the `platform` realm + `cia-platform` client) because the shared `@cia/auth` `initKeycloak` keys `onLoad:'login-required'` off `VITE_KEYCLOAK_URL` — there are no `VITE_PLATFORM_KEYCLOAK_*` vars. **One-time setup (not code):** create the second Vercel project + set its Root Directory to `cia-frontend/` (monorepo root, so workspace packages resolve) + add the env vars/secret; the exact Root-Directory↔`vercel.json` reconciliation for a 2nd project sharing the monorepo root is settled at that dashboard step (back-office's `vercel.json` lives at `cia-frontend/`; the platform one lives at `apps/platform/`). Like back-office, the public URL is a frontend-only demo until real `platform` Keycloak + backend infra are deployed. **Step-by-step one-time-setup runbook: [`cia-frontend/apps/platform/DEPLOY.md`](cia-frontend/apps/platform/DEPLOY.md)** (create project → demo-only env → secret → dedupe git auto-deploy → verify).

---

### 11. Provider-Agnostic Abstractions

#### Storage

```java
interface DocumentStorageService {
    String upload(String tenantId, String path, InputStream content, String mimeType);
    InputStream download(String tenantId, String path);
    void delete(String tenantId, String path);
}
// Active impl: STORAGE_TYPE=minio|s3|gcs|azure|local
```

#### KYC Verification

```java
interface KycVerificationService {
    KycResult verifyIndividual(IndividualKycRequest request);
    // idType: NIN | VOTERS_CARD | DRIVERS_LICENSE | PASSPORT
    KycResult verifyCorporate(CorporateKycRequest request);
    // rcNumber + companyName
    KycResult verifyDirector(DirectorKycRequest request);
}
// Failure: customer created with kyc_status = FAILED; reason stored; resubmit via KYC Update
// Active impl: KYC_PROVIDER=dojah|prembly|nibss|mock
```

#### Notifications

```java
interface NotificationService {
    void sendEmail(EmailMessage message);
    void sendSms(SmsMessage message);
}
// Active impl: NOTIFICATION_EMAIL_PROVIDER=sendgrid|ses|smtp|log
//              NOTIFICATION_SMS_PROVIDER=termii|twilio|log
```

#### Workflow Engine (Temporal)

Temporal governs all multi-step, async, or crash-safe processes:

- Policy / Quote / Claim / Finance approval workflows (single and multi-level escalation)
- NAICOM/NIID upload with retry-until-success (indefinite, exponential backoff)
- Renewal notification sequences (2mo / 1mo / 14d / 7d / 1d / 0d → +1/10/30/60/120d post-expiry)
- Bulk operations (bulk quote upload, bulk claim registration)
- Reinsurance allocation and batch reallocation
- NDPR data retention purge jobs

#### Document Generation

- Per-product templates uploaded during product setup; stored in object storage.
- Templates surfaced in editable rich text editor on the frontend before approval.
- PDF generated server-side via Apache PDFBox on policy/endorsement approval.
- User signature images (uploaded to storage) appended to the final PDF.
- Motor/marine: separate NAICOM certificate per risk item; UID printed on certificate.

---

## Partner Open API Platform

**Status:** Not in PRD — added as a strategic feature. Implemented in `cia-partner-api` module.

**Purpose:** Allow Insurtech companies (aggregators, digital brokers, embedded insurance providers) to connect to an insurance company's products and services programmatically via a versioned, documented, authenticated REST API.

---

### 1. Who Is This For

| Insurtech Type | Use Case |
|---|---|
| Digital broker / aggregator | Display insurance products, get quotes, bind policies on behalf of customers |
| Embedded insurance provider | Offer insurance at point-of-sale (e-commerce, ride-hailing, travel) |
| Claims fintech | Submit and track claims on behalf of policyholders |
| Insurance data platform | Read policy/customer data for analytics (read-only scopes) |

---

### 2. API Design

**Base path:** `/partner/v1/`  (separate from internal `/api/v1/`)

**Versioning:** URI-based (`/partner/v1/`, `/partner/v2/`). Breaking changes always bump the version; both versions run simultaneously during deprecation windows.

**OpenAPI spec:** Auto-generated by Springdoc OpenAPI 3.1 from `@Operation`, `@Schema`, and `@SecurityRequirement` annotations. Served at `/partner/docs` (Swagger UI) and `/partner/v3/api-docs` (raw JSON/YAML).

**Postman collection:** Generated from the OpenAPI spec on every build (`openapi-generator-maven-plugin`) and published as a build artifact. Importable directly into Postman or Bruno.

#### API Surface

| Method | Endpoint | Scope | Description |
|---|---|---|---|
| GET | `/partner/v1/products` | `products:read` | List all active products available for this tenant |
| GET | `/partner/v1/products/{id}` | `products:read` | Product details, rates, and required fields |
| GET | `/partner/v1/products/{id}/classes` | `products:read` | Classes of business under a product |
| POST | `/partner/v1/quotes` | `quotes:create` | Generate a quote (single-risk or multi-risk) |
| GET | `/partner/v1/quotes/{id}` | `quotes:read` | Retrieve quote and premium breakdown |
| POST | `/partner/v1/customers/individual` | `customers:create` | Register an individual customer (triggers KYC) |
| POST | `/partner/v1/customers/corporate` | `customers:create` | Register a corporate customer (triggers KYC) |
| GET | `/partner/v1/customers/{id}` | `customers:read` | Customer details and KYC status |
| POST | `/partner/v1/policies` | `policies:create` | Bind a policy from an approved quote |
| GET | `/partner/v1/policies/{id}` | `policies:read` | Policy details and status |
| GET | `/partner/v1/policies/{id}/document` | `policies:read` | Download policy certificate (PDF) |
| POST | `/partner/v1/policies/{id}/claims` | `claims:create` | Submit a claim notification |
| GET | `/partner/v1/claims/{id}` | `claims:read` | Claim status and details |
| POST | `/partner/v1/webhooks` | `webhooks:manage` | Register a webhook endpoint |
| GET | `/partner/v1/webhooks` | `webhooks:manage` | List registered webhooks |
| DELETE | `/partner/v1/webhooks/{id}` | `webhooks:manage` | Remove a webhook |

---

### 3. Authentication — OAuth2 Client Credentials

Insurtechs authenticate machine-to-machine using OAuth2 Client Credentials grant. No human login; no Keycloak realm login page.

```
Insurtech App
  │
  │  POST /realms/{tenant}/protocol/openid-connect/token
  │  grant_type=client_credentials
  │  client_id=insurtech-app-xyz
  │  client_secret=••••••••
  │
  ▼
Keycloak (tenant realm)
  │  Validates credentials → issues access token
  │  Token claims: client_id, scope, tenant_id, exp
  ▼
Insurtech App
  │  Authorization: Bearer {token}
  │
  ▼
cia-partner-api
  ├── Spring Security: validate JWT (Keycloak JWKS)
  ├── PartnerScopeFilter: check required scope per endpoint
  ├── RateLimitFilter: bucket4j per client_id (configurable per tenant)
  └── TenantContextFilter: set schema from tenant_id claim
```

**Insurtech onboarding (done by insurance company System Admin in Setup module):**

1. System Admin creates a Partner App in Setup → Partner Management.
2. System creates a Keycloak service account (client) in the tenant realm with configured scopes.
3. Credentials (`client_id` + `client_secret`) displayed once and sent to the Insurtech.
4. System Admin configures: allowed scopes, rate limit (requests/minute), allowed IP CIDR (optional), webhook signing secret.

---

### 4. Webhook System

Insurtechs register webhook URLs to receive real-time event notifications instead of polling.

**Registered webhook events:**

| Event | Trigger |
|---|---|
| `quote.created` | Quote generated via partner API |
| `quote.expired` | Quote passed validity window |
| `policy.bound` | Policy successfully issued |
| `policy.endorsed` | Endorsement applied to policy |
| `policy.cancelled` | Policy cancelled |
| `claim.registered` | Claim notification received |
| `claim.approved` | Claim approved and DV generated |
| `claim.settled` | Payment executed |
| `kyc.completed` | KYC verification result returned |
| `renewal.due` | Policy approaching renewal date |

**Webhook dispatch flow:**

```
Business event fires (e.g. policy.bound)
  └── Spring ApplicationEvent → WebhookPublisher
        └── Temporal: WebhookDispatchWorkflow.start(event, tenantId)
              ├── Load all registered webhooks for this tenant + event type
              └── For each webhook:
                    ├── Build payload: { event, data, timestamp, webhook_id }
                    ├── Sign with HMAC-SHA256 (X-CIA-Signature header)
                    ├── POST to Insurtech URL (5s timeout)
                    └── On failure: retry 3× with exponential backoff (30s, 2min, 10min)
                          → After 3 failures: mark webhook as degraded, notify admin
```

**Payload envelope:**

```json
{
  "id": "evt_01HX...",
  "event": "policy.bound",
  "timestamp": "2026-04-20T14:23:00Z",
  "tenant_id": "tenant_acme",
  "data": { ... }
}
```

**Signature verification (Insurtech side):**

```
X-CIA-Signature: sha256=<HMAC-SHA256(secret, raw_body)>
X-CIA-Timestamp: 1745155380
```

Insurtech must verify signature and reject payloads older than 5 minutes (replay attack prevention).

---

### 5. Rate Limiting

Implemented via `bucket4j` (token bucket algorithm) with limits stored in Redis or in-memory per deployment.

| Tier | Requests/minute | Burst | Configured by |
|---|---|---|---|
| Default | 60 | 100 | System default |
| Standard | 300 | 500 | Tenant admin per partner |
| Premium | 1,000 | 2,000 | Tenant admin per partner |

Rate limit headers returned on every response:

```
X-RateLimit-Limit: 300
X-RateLimit-Remaining: 247
X-RateLimit-Reset: 1745155440
```

HTTP 429 returned with `Retry-After` header when limit exceeded.

---

### 6. Documentation Deliverables

| Deliverable | Format | Location | Tooling |
|---|---|---|---|
| OpenAPI Specification | OpenAPI 3.1 JSON/YAML | `/partner/v3/api-docs` (live) | Springdoc OpenAPI |
| Swagger UI | Interactive HTML | `/partner/docs` | Springdoc Swagger UI |
| Postman Collection | JSON v2.1 | `cia-partner-api/docs/postman_collection.json` | openapi-generator-maven-plugin |
| Postman Environment | JSON | `cia-partner-api/docs/postman_environment.json` | Hand-crafted (dev + staging + prod vars) |
| Developer Guide | Markdown | `cia-partner-api/docs/developer-guide.md` | Written; auto-published to `/partner/docs/guide` |

**Postman collection structure:**

```
CIA Partner API
├── 🔐 Auth
│   └── Get Access Token (client_credentials)
├── 📦 Products
│   ├── List Products
│   └── Get Product Details
├── 💬 Quotation
│   ├── Create Quote
│   └── Get Quote
├── 👤 Customers
│   ├── Register Customer
│   └── Get Customer
├── 📋 Policies
│   ├── Bind Policy
│   ├── Get Policy
│   └── Download Policy Document
├── 🏥 Claims
│   ├── Submit Claim
│   └── Get Claim Status
└── 🔔 Webhooks
    ├── Register Webhook
    ├── List Webhooks
    └── Delete Webhook
```

---

### 7. Partner Management (Setup Module Extension)

The insurance company's System Admin manages Insurtech partners through a **Partner Management** section added to the Setup & Administration module:

- Create / revoke partner credentials (triggers Keycloak client creation/deletion)
- Configure allowed scopes per partner
- Configure rate limit tier per partner
- View partner API usage (request counts, error rates)
- View webhook delivery logs (success/failure per event per partner)
- Enable/disable a partner without revoking credentials (soft disable)

---

### 8. Sandbox Environment

Each tenant can optionally enable a **sandbox mode** for Insurtechs to test integration without creating real policies or triggering real NAICOM uploads.

- Sandbox base URL: `/partner/v1/sandbox/`
- Sandbox credentials: separate `client_id`/`client_secret` (sandbox scope only)
- All writes go to a sandboxed schema (no financial records created)
- Responses are realistic but flagged: `"sandbox": true` in all responses
- NAICOM/NIID/KYC calls use stub adapters in sandbox regardless of prod config

---

## Module Summary

| # | Module | Features | Key Outputs |
|---|---|---|---|
| 1 | Setup & Administration | 37 | Products, classes, approval groups, master data (brokers — **`license_number` added V49** / **agents** — V48 / reinsurers / insurers / branches / SBUs / surveyors / **adjusters** — V45 / **relationship managers** — UI-surfaced in Session 79 + customer FK via V46 / **clause bank** — V72, the policy clause master; quotes (V73) + policies (V74) snapshot a clause's `{title,text}` point-in-time into a `selected_clauses` JSONB at selection/bind, so the official quote + policy PDFs render the exact wording they were issued with), partner management, **notification templates** — F7-δ/R7 per-tenant email + SMS template overrides (Setup → Notification Templates). **Commission sources (B2):** RM is now an operationalised, *exclusive* third source on **direct-channel** policies (no broker/agent) — auto-derived from the customer's RM at policy creation (V62 snapshot id/name + frozen rate), accrued on approval **Dr 5130 / Cr 2520, no CreditNote** (RMs paid via external payroll). Resolves Open Q#11. |
| 2 | Quotation | 6 | Quote documents, per-item loadings/discounts, clause selection, PDF download, quote config tab. **V65** adds the missing `quote_risks.gross_premium` column (the per-item premium before loadings/discounts = `sum_insured × rate / 100`; the entity always mapped it but no migration created it, so a clean-schema `QuoteRisk` fetch failed — V65 backfills existing rows then drops the default). |
| 3 | Policy | 23 | Policy documents, debit notes, NAICOM/NIID upload; **B2 — RM commission source**: direct-channel policies snapshot the customer's RM (V62 `relationship_manager_id` + `_name` + 3-way at-most-one-source CHECK + RM-source-requires-RM CHECK), `commissionAmount` = net premium × frozen rate/100, accrued on approval via V63 `POLICY_COMMISSION_RM` posting rule. **3-source posting split** — same Dr **5130** (Insurance acquisition expense) for all; Cr **2320** Brokers / **2330** Agents / **2520** Staff payables (RM). RM differs from broker/agent: **no CreditNote** (paid via external payroll; the commission-CN listener skips RM). `PolicyResponse`/`PolicyDto` expose `relationshipManagerId`/`relationshipManagerName`; Financial tab labels RM commission accrual-only (no payment action). |
| 4 | Endorsements | 10 | Endorsement documents, debit/credit notes |
| 5 | Claims | 23 | Reserves, DVs, claim settlements, credit notes to finance |
| 6 | Reinsurance | 17 | RI allocations, offer slips, credit notes, bordereaux |
| 7 | Customer Onboarding | 10 | Customer records, KYC status, reports |
| 8 | Finance | 5 | Receipts, payments, settled/outstanding tracking; F7 slice α — flat /api/v1/receipts + /api/v1/payments endpoints with status/method/date filters; ReverseTransactionDialog wired into 4 surfaces (Receivables + Payables tabs plus nested-in-DN-and-CN detail dialogs); reversal-audit columns surfaced (timestamp + user + reason); **F7 slice β — auto-generated branded PDFs on every receipt/payment post()** (NotoSans-embedded HtmlToPdfConverter renders ₦ correctly; sanitise() guard removed; QuotePdfService gains ₦ for free); BeneficiaryProfileResolverDispatcher routes by FinanceEntityType → 4 JPA-backed resolvers (CLAIM/COMMISSION/REINSURANCE/ENDORSEMENT); Customer.address auto-decrypts via JPA @ColumnTransformer; V56 pdf_path persisted on receipts + payments; GET /pdf endpoints stream from MinIO under FINANCE_VIEW; Download buttons on all 4 visibility surfaces; voucher header label varies by CreditNote.entityType (CLAIM SETTLEMENT / COMMISSION / FAC PREMIUM / ENDORSEMENT REFUND); **F7 slice γ — Email PDF transmission via Temporal** (new `cia-notifications.email.EmailService` SPI with Logging/SMTP/SendGrid impls gated by `cia.notifications.email.provider`; `BeneficiaryEmailResolver` SPI + dispatcher mirrors β with `<TYPE>-email` bean-name convention; fail-closed semantics — unmapped or blank → 422; `SendReceiptEmailWorkflow` + `SendPaymentVoucherEmailWorkflow` on `EMAIL_QUEUE` with 5min → 1hr exponential retry; activities marked `@Transactional` for lazy-proxy access; `EmailBodyComposer` renders 2 JAR-default Thymeleaf templates with hardcoded subjects; V57 adds `email_sent_at` + `email_sent_to` to receipts + payments populated by activity on successful delivery; `AuditAction.SEND` audit row written exactly once per workflow completion; POST /api/v1/.../{id}/email endpoints under FINANCE_UPDATE return 202 `{ workflowId }` or 422 `{ errorCode, message }`; ListItemResponse projections gain recipientEmail + emailSentAt + emailSentTo; Email PDF action on all 4 visibility surfaces with "Last emailed" badge); **F11 — PDF download UX + bulk operations** (V58 `pdf_download_log` table + `parent_id UUID` column; new `PdfDocumentType { RECEIPT, PAYMENT }` enum in `cia-finance.audit` distinct from `FinanceEntityType`; `PdfDownloadLogService.log()` writes a row from every `downloadPdf` call with `@Transactional(REQUIRES_NEW)` so audit failures can't roll back the download; `GET /api/v1/finance/pdf-downloads?days=N` scoped to JWT user, capped at 50 rows × 30 days; `POST /api/v1/finance/pdfs/bulk-download` streams a ZIP of receipt + payment PDFs via `PdfZipService` + `ZipOutputStream`, 50-item cap (`BULK_DOWNLOAD_TOO_MANY` / `BULK_DOWNLOAD_EMPTY` error codes), filename `cia-pdfs-{yyyy-MM-dd-HHmmss}.zip`; `PdfDownloadLogRetentionWorkflow` on `EMAIL_QUEUE` with Temporal cron `0 2 * * 0` (Sunday 02:00 UTC) — 30-day retention purge survives JVM restarts; **workflow cancel signal** — both `Send{Receipt,PaymentVoucher}EmailWorkflow` gain `@SignalMethod void cancel()` checked before activity dispatch (best-effort — in-flight SMTP completes); `ReceiptService/PaymentService.cancelEmail(UUID)` signal via the slice-γ workflow-id convention + writes `AuditAction.CANCEL` audit row; `POST .../{id}/email/cancel` (FINANCE_UPDATE) returns 202 `{ cancelled }` or 422 `WORKFLOW_NOT_FOUND`; frontend `DownloadIconButton` replaces row-action "Download PDF" with an inline icon next to the reference cell on all 4 surfaces; `RecentDownloadsPanel` Sheet (server-driven via `useRecentDownloads`); `BulkEmailSheet` serial runner with Cancel button that signals in-flight workflow; `BulkDownloadButton` toolbar action with 50-item gate; row-checkbox column with parent-managed `Record<string, boolean>` selection state (shared DataTable doesn't expose selection props); **Vitest infrastructure** added to back-office app — first frontend test setup in the codebase); **F7 slice δ + R7 — SMS transmission** (Send SMS row action on all 4 finance surfaces, a mirror of the F7-γ email action, running on `SendReceiptSmsWorkflow` / `SendPaymentVoucherSmsWorkflow` over `NOTIFICATIONS_QUEUE`; `recipientPhone` projection added to the ListItemResponse rows; V61 adds `sms_sent_at` / `sms_sent_to` persisted via direct JDBC; recipient phone resolved via `BeneficiaryPhoneResolverDispatcher`; no PDF gate; 422 errorCodes RECEIPT_/PAYMENT_RECIPIENT_PHONE_UNRESOLVED) |
| 9 | Partner Open API | 15 | OAuth2 client management, REST partner API, webhooks, OpenAPI docs, Postman collection |
| 10 | Audit & Compliance | 15 | Full audit trail, login logs, 6 reports, CSV export, real-time alerts, System Auditor role |
| 11 | Reports & Analytics | 20 | 68 pre-built reports across 7 categories (incl. CLOSURES — 12 GL/IFRS-17/IFRS-9 ledger reports; **+1 FINANCE — "RM Commission Accrual" (B2 V64, `RM_COMMISSION` data source, per-RM aggregation)**), custom report builder, CSV/PDF export, pin management, access control |
| 12 | Period-End Closures (Phases 1–5 complete + Slice 1.10) | 55 slices | **Phase 1 — GL Foundations (12 slices):** GL foundation (V31), COA seed (V32, 129 rows incl. IFRS-17 + IFRS-9 role tags), posting rules (V33), JournalEntryService gateway, SubledgerPostingService event listeners, FiscalYearService + period generation (12 MONTH + 4 QUARTER + 2 HALF_YEAR + 1 YEAR = 19 rows, all eager at FY-create; `FiscalPeriodType.DAY` enum value reserved at the V31 schema level but not produced by any code path), PeriodLockService + Hibernate Interceptor with 5-business-day grace, V34 policies.currency_code, retroactive backfill workflow (1.8a/b), reconciliation gate harness with 200-event fixture + per-JE evidence + mutation guard (1.9a/b), period-lock entity opt-in across all monetary entities (1.7a/b — Receipt, Payment, ClaimExpense, Endorsement, DebitNote, CreditNote, RiAllocation, RiFacCover), V35 IAS-8 PPA workflow + `tenant_reopen_recipient` (DB-first w/ CSV fallback) + `tenant_holiday` calendar making `addBusinessDays` NAICOM-aware (1.7c). **Phase 2 — IFRS 17 PAA (8 slices, V36–V38):** ContractGroupingService event listener (§22 permanent assignment via full UNIQUE(policy_id)); LrcEngine (straight-line daily premium recognition); LicEngine (claim roll-forward, no JE in v1); PaaPeriodCloseService orchestrator + §83/§84 InsuranceServiceResult; DiscountUnwindEngine (§87-92 P&L vs OCI routing, §88(b) OCI election on paa_config); OnerousContractTestEngine (§47-49 loss component); V38 §103 movement-analysis disclosure view + MovementAnalysisService. **Phase 3 — IFRS 9 (7 slices, V39–V40):** investment_holding + investment_carrying_value + investment_classification_history (Type-2 SCD) + ifrs9_config singleton; InvestmentClassificationService (§4.1 + §B4.1.26 decision matrix, classify-then-register); AmortisedCostEngine (§5.4.1 effective interest method); FairValueEngine (§5.7 FVPL → P&L, FVOCI_DEBT → OCI reserve, FVOCI_EQUITY → OCI reserve; `closing_fair_value IS NULL` idempotency sentinel); InvestmentEclEngine (§5.5 + §5.7.10A — AC reduces asset directly, FVOCI_DEBT routes to OCI reserve while keeping carrying value at fair value); PremiumReceivableEclEngine (§5.5.15 simplified approach — provision matrix embedded in JE narrative as disclosure substrate); V40 §B5.5.39 movement-analysis disclosure view + Ifrs9MovementAnalysisService. **Phase 4 — NAICOM submissions (10 slices, V41):** V41 naicom_submission + naicom_submission_artifact + naicom_submission_event Type-2 SCD schema (4.1); PremiumBordereauxEngine + ClaimsBordereauxEngine (N05/N06, register-style — 4.2); AnnualRevenueAccountEngine (N01) + BalanceSheetEngine (N02, GL-driven via TrialBalanceService — 4.3); PrudentialReturnEngine (N03, 15% solvency-margin baseline — 4.4); RiQuarterlyReturnEngine (N04, ceded premium per treaty + reinsurer — 4.5); Ifrs17DisclosureEngine (relay over V38 — 4.6); Ifrs9DisclosureEngine + InvestmentStatementEngine (N08; relay V40 + direct-source snapshot — 4.7); NiidStatusSnapshotEngine (N07, in-force-at-period_end — 4.8); NaicomSubmissionService orchestrator + REST + state machine (DRAFT → SUBMITTED → ACKNOWLEDGED → ARCHIVED + RETRACTED) + RBAC + NaicomSubmissionEngine dispatch interface — 4.9; SubmissionArtifactService + JSON/CSV/PDF renderers (Apache PDFBox) + DocumentStorageService — 4.10. **Phase 5 — Module 12 back-office frontend (16 slices, F5.1–F5.16):** F5.1 `PeriodLockListPage` + Close/Reopen dialogs + `LockHistorySheet` (Type-2 SCD audit timeline) + `CreateFiscalYearSheet`; F5.3 read-only `ChartOfAccountsPage` (129-row tree with IFRS-17 / IFRS-9 role badges); F5.7 read-only `PostingRulesPage` (V33-seeded rules with COA-resolved Dr/Cr names, FAC carve-out footer) + new `PostingRuleController` + `PostingRuleResponse` enriched DTO; F5.4 `JournalEntryBrowserPage` + `JournalEntryDetailSheet` (idempotency triple, COA-resolved line breakdown); F5.5 `TrialBalanceReportPage`; F5.6 PLATFORM_ADMIN `BackfillAdminPage` with localStorage workflow tracking; F5.8 `PaaPeriodClosePage` (§83/§84 ISR); F5.9/10 (collapsed) `PaaMovementAnalysisPage` (§103 LRC + LIC roll-forward via shared generic `RollforwardTable<T>` component); F5.11 `ContractGroupsPage` (§22 portfolios + onerousness filters); F5.12 `HoldingsListPage` + `HoldingClassificationHistorySheet` (Type-2 SCD reclassification trail); F5.13 `Ifrs9MeasurementPage` (per-engine run buttons); F5.14 `Ifrs9MovementAnalysisPage` (§B5.5.39 disclosure relay); F5.15 `NaicomSubmissionsPage` with state-machine console + `GenerateSubmissionDialog` (8 NAICOM form types); F5.16 `NaicomSubmissionDetailSheet` artifact block (JSON/CSV/PDF render + download via `apiClient.get { responseType: 'blob' }` + per-row `mutation.variables === format` spinner key). All API calls zod-validated through `validatedGet` / `validatedPost` against schemas in `@cia/api-client/finance-closures.ts` (single Enums section at top, DTO sections below; `RollforwardTable<T>` is the only shared component extracted so far). **Closeout fixes (2026-05-21):** `MinioStorageService.@PostConstruct ensureBucketExists()` (`BucketExistsArgs → MakeBucketArgs`, non-fatal on failure — eliminates the "fresh dev MinIO 500s every first-time upload" gap); `FiscalYearService.close()` now cascades hard-close on every non-HARD child period via `PeriodLockService.hardClose` (delegates per-period so the `period_lock` SCD trail records the FY-close cause; idempotent on already-CLOSED FY); deleted dead `FiscalPeriodResolver.resolveDayForBusinessDate` infrastructure (zero production callers — JEs anchor to MONTH per Slice 1.4 D1=A). **Slice 1.10 — GL substrate enrichment (V42–V43):** `class_of_business_id UUID` column + partial index on journal_entry_line (V42); V43 backfill across 5 event-type code paths; PolicyClassResolver + SubledgerPostingService refactor (resolves class per event); 9-arg back-compat constructor on JournalEntryLineRequest preserves all 18 existing positional callers; AnnualRevenueAccountEngine re-implemented over GL with reconciliation assertion against independent JE aggregate. **Status:** 274 cia-api failsafe ITs across the full reactor, 0 failures / 0 errors / 1 intentional benchmark skip, all passing under `mvn verify` (down from 275 with the dead lazy-DAY IT deletion; previously-broken `FiscalYearServiceIT` is now green at 11/11 after the `@Import(CiaCommonAutoConfiguration.class)` auditing fix + `PeriodLockService` mock bean for the cascade dep). Phase 5 ships the Module 12 back-office frontend in full (16/16 slices); cross-tenant platform admin view (Phase 6) is the remaining workstream. |

---

## Key Business Rules

### Premium Formula
`Premium = (Sum Insured × Rate) − Discount`

### Pro-Rata Endorsement Premium
`Endorsement Premium = (Annual Premium / 365) × Days`

### Approval Hierarchy
- **Single-level**: one approver within amount range.
- **Multi-level**: escalates until an approver whose limit ≥ transaction amount.
- Applies uniformly to: Quotes, Policies, Endorsements, Claims, Finance receipts/payments.

### Policy Business Types
| Type | Description |
|---|---|
| Direct | Full risk retained; full policy document generated by us |
| Direct with Coinsurance | Lead insurer; participants listed on document; coinsurance share page included |
| Inward Coinsurance | We accept a share from another lead; guaranty policy document generated |
| Inward Facultative | Managed in Reinsurance module |

### Reinsurance Allocation
- **Surplus**: Retain ≤ Retention Limit; cede to surplus up to Surplus Limit; excess tagged if beyond gross capacity.
- **Quota Share**: Split by fixed insurer/reinsurer percentages (must sum to 100%).
- **XOL**: Retain first layer; cede losses above retention up to XOL limit.
- Treaty year = policy start date year (not policy creation year).
- Only "our share" used for coinsurance policies.
- Endorsements trigger proportional reallocation.

### Financial Flows
```
Policy approved        → Debit Note      → Finance Receivables  → Receipt
Endorsement approved   → Debit/Credit Note → Finance
Claim approved         → Credit Note (DV) → Finance Payables → Payment
Claim expense          → Credit Note     → Finance Payables
Commission             → Credit Note     → Finance Payables
FAC outward            → Credit Note     → Finance Payables
```

### Nigerian Regulatory Requirements
- **NAICOM**: Upload is **post-approval async** — policy approval never blocks on NAICOM. Temporal activity with exponential backoff retry (5min → 15min → 1hr, indefinite). Certificate generated immediately on approval with UID = PENDING; regenerated when UID arrives. "Generate NAICOM ID" button available on policy page for on-demand trigger. **Stub adapter** (`StubNaicomService`) active until live credentials obtained; swap to `NaicomRestService` via Spring profile — zero approval flow changes needed.
- **NIID**: Motor and marine policies + endorsements. Advance motor renewals uploaded on previous policy expiry. Same async Temporal + stub/live adapter pattern as NAICOM.
- **KYC**: Individual — validate name + DOB against ID. Corporate — validate company name against RC Number + validate two director IDs.
- **NDPR**: All PII encrypted at rest; data access logged; data retention policy enforced per tenant config.

---

## Audit & Logging

Every write operation records to a per-tenant audit log:

```sql
audit_log (
  id, tenant_schema, entity_type, entity_id,
  action,         -- CREATE | UPDATE | DELETE | APPROVE | REJECT | SEND
  user_id, user_name,
  timestamp,
  old_value,      -- JSONB snapshot before change
  new_value,      -- JSONB snapshot after change
  ip_address, session_id
)
```

---

## Access Control Model

Keycloak roles map to Spring Security authorities:

| Permission Type | Example |
|---|---|
| `{module}:create` | `underwriting:create` |
| `{module}:view` | `claims:view` |
| `{module}:update` | `finance:update` |
| `{module}:approve` | `underwriting:approve` |

Access groups aggregate permissions. Users inherit access group permissions. Approval groups are a separate concept — they define who can approve transactions within amount ranges.

---

## Development Standards

### General
- Java code style: Google Java Style Guide.
- React code style: Prettier + ESLint (Airbnb config).
- All strings externalised for i18n readiness (even if English-only initially).
- No hardcoded tenant IDs, currency codes, or country codes anywhere.

### Slice discipline

Every commit/slice has **one stated goal**, set before any code is touched. Side-discoveries made during the slice (drift, theatre, bugs in adjacent code, scope-adjacent UX gaps) go to the **canonical backlog table at the top of `cia-log.md`** — they are not pulled into the host slice. This rule exists because nine consecutive cleanup slices (Sessions 84a → 92) kept growing as new bugs were noticed mid-flight, which delayed everything underneath them.

**Hard rules:**

1. **One goal per slice.** State it explicitly in the first message and the commit. If during execution the goal needs to expand, say so explicitly and amend the goal — don't silently broaden.
2. **Side-discoveries are logged, not absorbed.** When you notice a drift / bug / gap adjacent to the current work, add a row to the backlog table at the top of `cia-log.md` with a priority rating (P1 / P2 / P3) and a one-line note — don't fix it in this slice.
3. **Every slice ends by reconciling against the backlog.** The session entry's "Known follow-ups" section must explicitly point to (a) rows removed from the backlog as the slice landed them, (b) rows added because the slice surfaced them, or (c) "no backlog change" if neither. If the backlog only grew, that's a signal the goal was too narrow or the scope wasn't honest.
4. **Backlog is the source of truth for what comes next.** Don't pick the most-recently-noticed item by default — pick from the table by priority. Per-session "Known follow-ups" entries are informational/chronological; the table at the top is canonical.

**When mid-slice growth is legitimate:** if you discover that the stated goal can't ship without also fixing X, the right move is to stop, name the discovery, and either (a) descope the slice + log X to the backlog or (b) explicitly broaden the slice's stated goal with a one-line justification. What's not legitimate is silently expanding scope and letting the commit grow.

This discipline was added in Session 93 after the user observed the original drift items kept getting pushed under newer discoveries. The backlog table at the top of `cia-log.md` is its enforcement mechanism.

### Frontend API wiring rules

The back-office app reads from `/api/v1/...` everywhere and writes via `useMutation` on every form submit. This invariant is **enforced in CI** by `cia-frontend/scripts/check-api-wiring.sh`, which runs on every PR.

**Hard rules (CI fails on violation):**

1. **No `console.log(` in `cia-frontend/apps/back-office/src/modules/**`.** Remove debug logs before committing. Use `console.error`/`console.warn` for genuine error paths or wire to a real logger.
2. **No top-level `const mockX = [...]` or `const MOCK_X = [...]` in module files** unless explicitly opted out. The standard is `useQuery` against the matching `/api/v1/...` endpoint.
3. **No `// TODO: useMutation` / `// TODO: useQuery` / `// TODO: useCreate` / `// TODO: useUpdate` left behind.** If you can write the TODO, you can write the hook.

**Opting out a legitimate fallback:** When a mock genuinely needs to remain (e.g., a detail-page fallback while `useQuery` is in flight, or decorative enrichment that has no list endpoint), add this comment on the line immediately above the declaration:

```ts
// allow-mock: <one-line reason>
const mockFallback: PolicyDto = { /* ... */ };
```

The reason ends up in `git blame` so future readers know why the fallback survives. Examples currently in the codebase:

- `// allow-mock: fallback while useQuery is in flight or for unknown ids` — on detail pages
- `// allow-mock: decorative product/class enrichment for the per-row dialog` — on finance detail dialogs
- `// allow-mock: per-treaty allocation drilldown — no list endpoint exposes this nested view` — on TreatiesTab

**Standard wiring patterns:**

```ts
// List page
const customersQuery = useQuery<CustomerDto[]>({
  queryKey: ['customers'],
  queryFn: async () => {
    const res = await apiClient.get<{ data: CustomerDto[] }>('/api/v1/customers');
    return res.data.data;
  },
});
const customers = customersQuery.data ?? [];
// ... show <Skeleton /> while customersQuery.isLoading

// Form submit
const create = useMutation({
  mutationFn: async (values: FormValues) => {
    const res = await apiClient.post<{ data: { id: string } }>('/api/v1/customers/individual', values);
    return res.data.data;
  },
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['customers'] });
    onSuccess();
    form.reset();
  },
});
```

**Run locally before pushing:**

```bash
bash cia-frontend/scripts/check-api-wiring.sh
```

### DTO drift guard

In addition to the API-wiring guard, CI enforces that every `*Dto` interface in `cia-frontend/packages/api-client/src/modules/` matches the field set of its counterpart `*Response.java` (or record) in `cia-backend/`. The check exists because three silent-drift incidents (Session 78 BrokerDto, Slice 84a ProductDto, Session 91 QuoteDto.brokerName) showed that frontend types diverging from backend responses goes unnoticed for months — Jackson silently drops unknown fields, and `useQuery` consumers never notice missing fields they don't render.

**Script:** `cia-frontend/scripts/check-dto-drift.mjs` — parses Lombok `@Data` classes + Java records; parses TS `export interface XYZDto { ... }` blocks. Default mapping: strip `Dto`, append `Response`. Both directions of drift are reported:

- **`frontendOnly`** — fields the frontend declares but the backend doesn't have. Almost always a real bug (Jackson silent-drop).
- **`backendOnly`** — fields the backend ships but the frontend doesn't declare. Missed-surface gap; future UI work.

**Opting out an intentional asymmetry:** edit `cia-frontend/scripts/dto-drift.config.json`. Each `allowList` entry takes `frontendOnly`, `backendOnly`, and a `reason` field that ends up in git blame. The baseline was set in Session 92 — adding new entries needs strong justification; the goal is to drive the list down to zero slice-by-slice.

**Manual mapping** (`manualMap`) handles Dtos whose default Dto→Response swap doesn't match (e.g. `QuoteRiskDto` → `QuoteRiskResponse` works by default, but pure-frontend types like `IndividualCustomerDto` set the value to `""` so they're skipped).

**Run locally before pushing:**

```bash
node cia-frontend/scripts/check-dto-drift.mjs
```

### API Design
- RESTful JSON APIs.
- All endpoints prefixed `/api/v1/`.
- Tenant context always resolved from JWT, never from request body.
- Standard response envelope: `{ "data": ..., "meta": ..., "errors": [...] }`.
- Pagination: cursor-based for large lists. **List endpoints must place the array directly in `data` and the pagination metadata (`total`, `page`, `size`, `nextCursor`, `prevCursor`) in `meta`.** Never serialise Spring's full `Page<T>` object into `data` — the frontend's `useQuery` hooks unwrap `res.data.data` as an array, and a Page object there crashes the consumer with `(query.data ?? []).map is not a function`. The canonical controller idiom is `ApiResponse.success(page.getContent(), ApiMeta.builder().total(page.getTotalElements()).page(page.getNumber()).size(page.getSize()).build())` — return type `ResponseEntity<ApiResponse<List<T>>>`, never `ResponseEntity<ApiResponse<Page<T>>>`. **All internal `Page`-backed list endpoints populate `ApiMeta`** (Session 137 Option-B sweep brought the remaining 16 controllers / 20 endpoints into line); they default to `@PageableDefault(size = 2000)` (Spring's `max-page-size` ceiling) so the FE's single unbounded fetch returns the full list until true server-side pagination UI lands (tracked: `list-endpoints-true-pagination`). For `.map(toResponse)` endpoints, read `total/page/size` off the **source `Page`** (`var page = service.x(...); page.map(...).getContent()` for data, `page.get*()` for meta) — never off the mapped list. The lone exception is `ClaimController.reserves`, an in-memory `claim.getReserves().stream()` feed with no `Page` to read meta from.
- **Flat list endpoints for child aggregates** — when a child aggregate (e.g. Receipt → DebitNote, Payment → CreditNote) needs a cross-parent list view, create a separate `*ListController` rather than adding the flat endpoint to the existing nested parent-scoped controller. Keeps responsibility lines clean; the nested controller stays narrowly about the parent context. F7 slice α introduced `ReceiptListController` (`/api/v1/receipts`) and `PaymentListController` (`/api/v1/payments`) alongside the existing `ReceiptController` and `PaymentController`. Filtering via `JpaSpecificationExecutor<T>` + a static `*Specs` factory class; projection DTOs (`*ListItemResponse`) carry parent + grandparent context to avoid N+1 on row rendering.
- **PDF generation in cia-finance** — receipt + payment-voucher PDFs render via Thymeleaf templates at `cia-documents/src/main/resources/templates/pdf/` + `HtmlToPdfConverter` (NotoSans-embedded PDFBox, post-F7-β). Generators MUST NOT throw — catch `Exception`, log WARN, return `null`. The host `*.post()` flow tolerates null (leaves `pdf_path` unset) so PDF failures never roll back the receipt/payment commit. Storage path convention: `receipts/{yyyy}/{MM}/{id}.pdf` and `payments/{yyyy}/{MM}/{id}.pdf` via `DocumentStorageService.upload()`. Frontend gates the Download button on `pdfPath !== null`. **`BeneficiaryProfileResolverDispatcher`** routes credit notes by `entityType` to one of 4 JPA-backed resolvers (CLAIM / COMMISSION / REINSURANCE / ENDORSEMENT); unmapped types fall back to denormalised `beneficiaryName`. `Customer.address` auto-decrypts via JPA `@ColumnTransformer` on read — cia-finance gained module deps on cia-customer + cia-claims + cia-endorsement + cia-policy specifically to enable this. **`HtmlToPdfConverter`** uses `PDType0Font` + NotoSans TTFs (OFL 1.1, ~1.6 MB) — replaces the prior `PDType1Font.HELVETICA` + WinAnsi `sanitise()` guard which mapped non-WinAnsi glyphs (including ₦ U+20A6) to `?`.

- **Email transmission via Temporal (F7 slice γ)** — receipt + payment-voucher PDF email delivery runs on a dedicated `EMAIL_QUEUE` Temporal worker. The `EmailService` SPI (`cia-notifications.email`) has three impls (`LoggingEmailService` / `SmtpEmailService` / `SendGridEmailService`) gated by `@ConditionalOnProperty(name="cia.notifications.email.provider")` — SMTP is the `matchIfMissing=true` default. **Activity contract:** `deliver(tenantId, entityId, requestedBy)` MUST be `@Transactional` so lazy JPA proxies (`receipt.getDebitNote()`, `payment.getCreditNote()`) resolve inside an active Hibernate session — without this the activity throws `LazyInitializationException` wrapped in `ApplicationFailure(nonRetryable=false)` and retries indefinitely. **Audit-after-success idempotency:** the SEND audit row is written ONLY after `emailService.sendEmail(...)` returns successfully, so 3 SMTP failures + 1 success = exactly 1 `audit_log` row. **Retry policy:** `setInitialInterval(5min)` → `setMaximumInterval(1hr)`, `setBackoffCoefficient(2.0)`, **no `setMaximumAttempts`** (retries indefinitely on transient SMTP errors; matches the NAICOM upload policy). **Preflight at service:** `requestEmail(UUID)` validates `pdfPath != null` AND recipient resolvable (via direct `customers.email` JDBC SELECT for receipts; via `BeneficiaryEmailResolverDispatcher.resolve(creditNote)` for payments) BEFORE starting the workflow. Failures throw `EmailPreflightException` → HTTP 422 with structured `{ errorCode, message }` envelope via the existing `GlobalExceptionHandler.handleCiaException` branch (no separate handler needed since `EmailPreflightException extends CiaException`). **`BeneficiaryEmailResolver`** SPI mirrors slice β's `BeneficiaryProfileResolver` with `<TYPE>-email` bean-name convention; **fail-closed** — unmapped entity types (POLICY, CLAIM_EXPENSE) or blank emails return `Optional.empty()` (no `nameOnly` fallback, unlike β). **`EmailBodyComposer`** renders Thymeleaf JAR-default templates at `cia-documents/src/main/resources/templates/email/<type-kebab-case>.html`; subjects hardcoded per `EmailTemplateType` enum (`RECEIPT_EMAIL` / `PAYMENT_VOUCHER_EMAIL`); slice δ adds per-tenant overrides. **`TemporalQueues.EMAIL_QUEUE = "email-queue"`** (kebab-case to match existing `approval-queue` / `backfill-queue` convention). **First Temporal-test-framework pattern in the codebase** — `TestWorkflowEnvironment` with simulated clock (`env.sleep(40min)` advances retry timers in zero real time) for the workflow ITs.

- **Notification template framework (cia-documents + cia-setup + cia-finance) (F7 slice δ + R7)** — tenant-editable email + SMS templates render through a logic-less **Mustache** engine (`MustacheTemplateRenderer` in cia-documents) — logic-less by design so there is no SSTI surface. **Variable allowlist is enforced twice**: at save-time (`NotificationTemplateService` rejects unknown tokens) and at render-time, both against `NotificationVariables.allowlistFor(type, channel)`; **partials are disabled** — any `{{>...}}` include resolves to `UNKNOWN_TEMPLATE_VARIABLE`. **`NotificationComposer`** (cia-finance) is the runtime fallback chain, evaluated **per field independently** (subject vs body): DB override (`tenant_notification_template`, V60 — soft-delete-aware lookup so a reset override falls through) → JAR default (`DefaultTemplateLoader` reading `templates/notifications/{channel}/{type-kebab}.{subject|html|txt}`). **Type renames vs F7-γ:** `EmailTemplateType` → `NotificationTemplateType` + a new `NotificationChannel` enum (both in `cia-common.notification`); `EmailBodyComposer` **deleted** (subsumed by `NotificationComposer`); `EmailPreflightException` → `NotificationPreflightException`; `TemporalQueues.EMAIL_QUEUE` → `NOTIFICATIONS_QUEUE` and `EmailWorkerConfig` → `NotificationsWorkerConfig`. **SMS path (R7):** `SmsService` SPI (`cia-notifications`) with `LoggingSmsService` (default, `matchIfMissing=true`) + Termii/Twilio impls gated by `cia.notifications.sms.provider`; `BeneficiaryPhoneResolver` SPI + dispatcher mirror the email resolver with `<TYPE>-phone` bean-name convention; `SendReceiptSmsWorkflow` / `SendPaymentVoucherSmsWorkflow` run on `NOTIFICATIONS_QUEUE` mirroring the email workflows exactly (cancel signal, audit-after-success idempotency, same 5min→1hr indefinite retry). SMS persists `sms_sent_at` / `sms_sent_to` (V61) via **direct JDBC UPDATE, NOT `repo.save()`** — saving the managed entity re-flushes the Hibernate shared collection and double-flushes the receipt/payment graph. **No PDF gate for SMS** (text only — preflight checks recipient phone resolvable, not `pdfPath`); failures throw `NotificationPreflightException` with `RECEIPT_RECIPIENT_PHONE_UNRESOLVED` / `PAYMENT_RECIPIENT_PHONE_UNRESOLVED`. **Editor UI:** Setup → Notification Templates; the email body preview renders in a **sandboxed iframe** (`sandbox=""` + `srcDoc`) — tenant-authored HTML is never injected via React's raw-HTML escape hatch, so it cannot execute scripts or escape the preview.

- **PDF download server-side audit pattern (F11)** — the `pdf_download_log` table (V58) is **deliberately separate from `audit_log`** to keep compliance-oriented auditing clean. Every successful `ReceiptController.downloadPdf` and `PaymentController.downloadPdf` calls `PdfDownloadLogService.log(type, entityId, reference, parentId, parentRef, recipientName)`. The service method uses `@Transactional(propagation = Propagation.REQUIRES_NEW) + try/catch` — any write failure logs WARN and is swallowed, so an audit-row write failure NEVER blocks the actual download response. The `parent_id UUID` column carries the DN/CN id (in addition to the denormalised `parent_ref` string) so the frontend Re-download in `RecentDownloadsPanel` can call the existing download endpoints. **30-day retention** is enforced by `PdfDownloadLogRetentionWorkflow` — a Temporal cron `0 2 * * 0` (Sunday 02:00 UTC) registered on `EMAIL_QUEUE` via `EmailWorkerConfig.schedulePdfDownloadLogRetention()`; survives JVM restarts (Temporal persists the schedule). Idempotent on re-registration via the fixed workflow id `pdf-download-log-retention-cron`. **`PdfDocumentType { RECEIPT, PAYMENT }`** lives in `cia-finance.audit` — small enum distinct from `FinanceEntityType` (which discriminates CN/DN source-entity semantics).

- **Workflow cancellation signal pattern (F11)** — both `SendReceiptEmailWorkflow` and `SendPaymentVoucherEmailWorkflow` ship a `@SignalMethod void cancel()`. Impls maintain a `private boolean cancelled = false` field; `send()` checks `if (cancelled) return;` **before** dispatching to `activities.deliver(...)`. Cancellation is **best-effort by design** — an activity already in flight (and its Temporal-managed retries) completes normally; we don't try to interrupt an in-progress SMTP send. This shape is sufficient for the bulk-email UI: cancel mid-run means "don't send the remaining queued ones", and each queued workflow gets a clean pre-dispatch check. **`ReceiptService.cancelEmail(UUID)` / `PaymentService.cancelEmail(UUID)`** look up the workflow by the slice-γ id convention (`send-{receipt|payment-voucher}-email-<id>`) and signal it; `EmailPreflightException("WORKFLOW_NOT_FOUND", ...)` surfaces when Temporal can't find the workflow (already finished or never started) — routed by `GlobalExceptionHandler.handleCiaException` to HTTP 422 with `{errorCode, message}`. Signal success writes an `AuditAction.CANCEL` audit row with `{workflowId, cancelledBy}`.

- **Bulk PDF download — backend ZIP endpoint (F11)** — `PdfZipService.buildZip(tenantId, BulkDownloadRequest)` walks the items list, looks up each Receipt/Payment by id, calls `storage.download(tenantId, pdfPath)` per item, streams into a `ZipOutputStream` backed by a `ByteArrayOutputStream`, returns bytes. Items with null `pdfPath` are silently skipped (server-side WARN). **Each resolved item also writes a `pdf_download_log` row** — so a 30-receipt bulk download appears as 30 entries in the operator's RecentDownloadsPanel. `POST /api/v1/finance/pdfs/bulk-download` (FINANCE_VIEW) — 50-item cap enforced both via `@Size(max=50)` bean validation (returns 400 + `VALIDATION_ERROR`) AND via an explicit controller check (returns 400 + `BULK_DOWNLOAD_TOO_MANY` for bypass cases — e.g. malformed JSON). Empty list → 400 + `BULK_DOWNLOAD_EMPTY`. Response: `application/zip` with `Content-Disposition: attachment; filename="cia-pdfs-{yyyy-MM-dd-HHmmss}.zip"`.

- **Frontend Vitest infrastructure (F11)** — first Vitest setup in `cia-frontend`. Lives in `apps/back-office/vitest.config.ts` (jsdom env, react plugin, `src/test/setup.ts` import of `@testing-library/jest-dom/vitest`). Scripts: `pnpm --filter @cia/back-office test` (one-shot) + `test:watch` (live). `turbo.json` gains a `test` task depending on `^build`. Vitest pinned at `^2.1.9` — Vitest 4 requires Vite 6+ and the project is still on Vite 5 (upgrade path noted for the next Vite bump). Pattern: `vi.mock('@cia/api-client', ...)` for fetcher mocks; `QueryClientProvider` wrapper for hooks; `vi.mock('../hooks/...', ...)` for hook mocks in component tests.

### Partner API Design (cia-partner-api specific)

- Partner controllers never expose business module entities or internal DTOs directly. All partner responses use `Partner*Response` types defined in `cia-partner-api/controller/dto/`.
- Each `Partner*Response` carries a static `from(BusinessDto)` factory that maps from the business module DTO to the partner contract. This isolates the external API surface from internal model changes.
- `@Schema` annotations (class-level and field-level with `example`) belong **exclusively** on `cia-partner-api` DTOs. Business modules (`cia-policy`, `cia-quotation`, `cia-customer`, `cia-setup`, etc.) must not import or use swagger-annotations — they are a presentation concern.
- Every `@RestController` method in `cia-partner-api` must have `@ApiResponses` covering all applicable codes: 200/201, 400, 401, 403, 404 (where applicable), 429.

### Reports API Design (cia-reports specific)

- `cia-reports` has **zero dependency on any business module**. `ReportQueryBuilder` uses `EntityManager.createNativeQuery()` directly against the tenant schema. Never add a business module dependency to `cia-reports`.
- Adding a new pre-built report is a **Flyway data migration** (`V18+` INSERT into `report_definition`) — it is not a code change. The `ReportRunnerService` interprets the JSONB config at runtime.
- `ReportConfig` is stored as JSONB via Hibernate 6's native `@JdbcTypeCode(SqlTypes.JSON)` on the `config` column of `report_definition` (Jackson is auto-discovered from the classpath). Do **not** use the third-party `hibernate-types` library (Vlad Mihalcea) — `@JdbcTypeCode(SqlTypes.JSON)` is core Hibernate 6 and ships with the framework. The previous `AttributeConverter<ReportConfig, String>` approach (`ReportConfigConverter`) was deleted because it serialised the config as VARCHAR and PostgreSQL rejected the INSERT against the `jsonb` column ("column is of type jsonb but expression is of type character varying").
- `SYSTEM` reports (seeded by Flyway) **cannot be deleted or edited**. `ReportDefinitionService` throws `IllegalStateException` on any mutating operation against a SYSTEM report. They can only be cloned into `CUSTOM` reports.
- Computed fields (`loss_ratio`, `combined_ratio`, `retention_pct`, etc.) are post-processed in Java inside `ReportQueryBuilder.applyComputedFields()` — they are not computed in SQL. This keeps the base queries simple and avoids aggregation conflicts.
- `ORDER BY` in `ReportQueryBuilder` uses a whitelist sanitizer (`sanitizeColumnName` → `replaceAll("[^a-zA-Z0-9_.]", "")`) to prevent SQL injection on the sort column. Never interpolate raw strings into the ORDER BY clause. The same sanitizer guards the dynamic-projection `AS <key>` alias (a CUSTOM report's field key is persisted unvalidated, so the alias would otherwise be an injection vector).
- `ReportQueryBuilder` uses a **dual model**. The 6 business sources (POLICIES, CLAIMS, FINANCE, REINSURANCE, CUSTOMERS, ENDORSEMENTS) build their SELECT **dynamically** from each report's declared non-computed field keys via `SOURCE_FROM` (FROM/JOIN/WHERE skeleton) + `SOURCE_COLUMNS` (`fieldKey → SQL expr`, mostly single-table over denormalised columns like `policies.customer_name`/`.class_of_business_name`/`.product_name`; REINSURANCE has one LEFT JOIN for the treaty label). All other sources (TRIAL_BALANCE, GENERAL_LEDGER, the IFRS/PAA views, RM_COMMISSION) keep a fixed `BASE_QUERIES` SELECT. Emitting the dynamic SELECT in declared-field order makes the positional `applyComputedFields()` correct-by-construction (any sort-column injected to satisfy an `ORDER BY` is appended **after** the declared fields, so it never shifts a mapped slot). **Adding a new field key to a business-source report requires a matching `SOURCE_COLUMNS` entry** — an unmapped key projects `NULL` (e.g. `customers.channel`, which has no backing column). This replaced the original fixed-SELECT-for-all model, whose 6 business-source queries referenced phantom singular tables (`policy`, `customer`, …) + nonexistent columns and broke all 55 V18 + 4 V44 (GENERAL_LEDGER ×3 / PAA_GROUPS ×1) SYSTEM reports at runtime. Guarded by `SystemReportSmokeIT` (runs every SYSTEM report against a real DB) + `BusinessReportValueIT` (per-source value assertions). A business source whose report declares a non-blank `groupBy` aggregates in SQL: non-computed MONEY/NUMBER/INTEGER fields are `SUM`-ed and non-computed STRING/DATE fields form the `GROUP BY` (`buildBusinessGroupBy`, emitted at the same tail position as `BASE_QUERY_TAILS`; the SELECT/GROUP BY dimension split is the single `!isComputed && !isMeasure` predicate so they can't disagree; `PERCENT` is deliberately a dimension, not a SUM-ed measure); computed fields stay Java-post-processed. The 49 business reports with no `groupBy` stay per-row. **Adding a measure to a `groupBy` report requires its field `type` to be MONEY/NUMBER/INTEGER** (that's what marks it for `SUM`). The business-vs-fixed partition is the single `isBusinessSource(ds)` predicate (`SOURCE_COLUMNS` and `BASE_QUERIES` are exact complements). A **fixed-aggregate cross-entity source** `UNDERWRITING_PERFORMANCE` (V66, Session 136) is a UNION-ALL event stream over `policies` (gross written premium = `total_premium`), `claims` (`reserve_amount` = claims incurred), and APPROVED `claim_expenses` — each unioned row carries a single `ev.event_date` (policy `created_at` / claim `reported_date` / expense `created_at`) so `date_from`/`date_to` filter at the top level exactly like TRIAL_BALANCE/RM_COMMISSION; `GROUP BY ev.cob` (BASE_QUERY_TAILS) feeds the positional `applyComputedFields()` with `premium_earned`/`claims_incurred`/`expenses`, so the `loss_ratio`/`combined_ratio` columns on the 3 ratio reports (Loss Ratio, Combined Ratio, Annual Revenue Account) finally compute non-zero. Reports on this source MUST declare their non-computed fields as a prefix of `[class_of_business, premium_earned, claims_incurred, expenses]` in that order. Premium is written-not-earned (documented proxy); acquisition/management expenses are out of scope by design (GL/Module-12 domain). Deliberately NOT in the custom-report-builder picker (fixed-shape substrate).
- Report access resolution in `ReportAccessService`: report-level policy beats category-level policy; if neither exists the user cannot see the report. The frontend must **never show an "access denied" state** for reports — absent policy means the report is invisible, not blocked.
- `report_access_policy` has a DB constraint: `category IS NOT NULL OR report_id IS NOT NULL` — exactly one must be present per row (category-level XOR report-level).
- The `report_pin` table has a `UNIQUE(user_id, report_id)` constraint. `ReportRunnerService.pin()` checks `existsByUserIdAndReportId` before inserting to avoid duplicate key exceptions.
- Regulatory reports (`REGULATORY` category, N01–N08) have `is_pinnable = FALSE`. `ReportViewerPage` must not render the Pin button for these reports.
- Chart types: `BAR | LINE | PIE | TABLE_ONLY`. When `config.chart.type = TABLE_ONLY`, `ReportChart` returns `null` — no chart space is reserved. Both backend seed SQL and frontend `ReportChart` must handle this case.

### Period-Lock Design (Module 12, Slice 1.7 — cia-finance)

- **Lock enforcement is opt-in via `LockableByPeriod`** (in `cia-common.entity`). Entities that move money implement the interface; entities that don't (Customer, Broker, AccessGroup, ChartOfAccount) stay out. The Hibernate `PeriodLockInterceptor` checks `instanceof LockableByPeriod` per save — the marker interface IS the opt-in mechanism, not a config table.
- **`getLockDate()` returns the booking date, not the business-effective date.** For Endorsement that means `bookedDate`, NOT `effectiveDate`. The IFRS 17 measurement engine (Phase 2) reads effective dates separately and never flows through this interceptor. Mixing the two anchors silently breaks the lock semantics.
- **Reversal carve-out** — `LockableByPeriod.isReversal()` defaults to `false`. Entities with a reversal model (`JournalEntry.reversalOf != null`) override to return `true`. The interceptor short-circuits reversal rows before the period lookup so corrections to a closed period are always possible. Without this carve-out, post-close corrections become impossible and finance teams disable the interceptor "just this once."
- **`period_lock` is a Type-2 SCD** (V31): `released_at IS NULL` row is the active lock; the sequence of soft/hard/release rows IS the audit history. There is no separate `period_lock_history` table.
- **Two distinct override roles**: `FINANCE_OVERRIDE_LOCK` (soft-close grace bypass; senior accountant) and `FINANCE_REOPEN_PERIOD` (HARD lock release; CFO / FD). Bundling them is a segregation-of-duties audit finding.
- **`PeriodLockedException` is HTTP 423 LOCKED**, distinct from 422 / 403, so the frontend toast can switch on status code alone. The structured `meta.{periodId, periodLabel, status, graceEndsAt, overrideRoles}` payload is rendered by `PeriodLockExceptionHandler`, not by `GlobalExceptionHandler`.
- **`FiscalPeriodLookupCache` is a scope-aware singleton** — when an HTTP request is bound, the cache map is stored as a `SCOPE_REQUEST` attribute (auto-cleaned by Spring at request end, matching the original Slice 1.7 `@RequestScope` semantics). When no request is bound (Temporal worker, scheduled job, batch import), it falls back to a per-thread `HashMap`. The cache key is `(tenantId, lockDate)` — under the ThreadLocal path, including tenantId reduces a tenant-A-to-tenant-B cache hit on pooled worker threads from a correctness bug to a cache miss. Non-HTTP callers MUST invoke `clearThreadCache()` at activity boundaries to prevent ThreadLocal growth on pooled threads — Slice 1.8's Temporal `WorkerInterceptor` owns that lifecycle. Tests that bind a `MockHttpServletRequest` via `RequestContextHolder` exercise the request-attribute path (production HTTP traffic); tests that don't exercise the ThreadLocal fallback (Temporal-activity tests).
- **Grace window is 5 business days (Mon–Fri, no holiday calendar in v1)** — `PeriodLockService.addBusinessDays`. Nigerian holiday-calendar awareness is a Slice 1.7c follow-up.
- **`hardClose` on OPEN auto-soft-closes first** to honour V31's `ck_fiscal_period_close_chronology` invariant (`hard_closed_at >= soft_closed_at`). Service callers see a coherent API; the DB sees two `period_lock` rows.
- **`PeriodReopenedEvent` notification recipients** read from `cia.finance.period-reopen-recipients` (Spring property, CSV). Per-tenant CFO config table is Slice 1.7c.

### Testing Requirements
- Backend: unit tests for all business logic; integration tests for all repository methods (Testcontainers); API tests for all controllers.
- Frontend: unit tests for all utility functions and hooks; component tests for critical flows.
- E2E: golden paths for each module (Playwright).
- Minimum coverage: 80% line coverage on backend business logic.
- **Testcontainers stack pins:** `testcontainers.version=1.21.4` + explicit `docker-java.version=3.5.3` override (in the parent pom's `<dependencyManagement>`, declared **before** the Testcontainers BOM). Docker Engine 29.x reports `MinAPIVersion=1.40` and rejects v1.30 probes with HTTP 400; docker-java 3.4.x (the version Testcontainers 1.21.4 still bundles) hard-pins v1.30 for initial negotiation, so every IT fails with "Could not find a valid Docker environment". Keep the override until Testcontainers upgrades its bundled docker-java past 3.5.x.
- **Backend dependency / CVE pins (S144 — `backend-image.yml` Trivy gate is enforcing):** `spring-boot-starter-parent` is **3.5.14** (the minimum clearing the two original image CRITICALs — `spring-security-web` CVE-2026-22732 needs Security 6.5.9; `spring-boot` CVE-2026-40973 fix is 3.5.14). On top of the 3.5.14 BOM, the parent pom pins several CVE-driven overrides: `temporal.version=1.35.0` (fixed grpc/protobuf); four BOM-property overrides — `tomcat.version=10.1.55`, `netty.version=4.1.135.Final`, `postgresql.version=42.7.11`, `jackson-bom.version=2.21.4` (the last clears CVE-2026-54512 PolymorphicTypeValidator-bypass + CVE-2026-54513 array-subtype-allowlist-bypass, both jackson-databind HIGH, newly disclosed 2026-06 against the BOM-pinned 2.21.2; these four are real BOM property names, so a `<properties>` value overrides them); plus `bcprov-jdk18on=1.84` and `okhttp=4.12.0` which are **minio 8.6.0 transitives NOT managed by the Boot BOM** — a `<properties>` value alone is a silent no-op for those, so they require an explicit `<dependencyManagement>` entry. Net result: backend image **0 CRITICAL/HIGH** (was 45 at Boot 3.3.5). The CVE gate now blocks any new CRITICAL/HIGH on every backend image build. When bumping any of these, re-run the `backend-image.yml` Trivy scan — a regression fails the build.
- **@DataJpaTest ITs that exercise `BaseEntity` writes must `@Import(CiaCommonAutoConfiguration.class)`.** Slice's auto-config carries `@EnableJpaAuditing`, which `@DataJpaTest` does not autodiscover — without it `@CreatedDate` never fires, `created_at` stays null, and every insert hits the NOT NULL constraint. Spring lets you import the config class directly; no `@AutoConfigureDataJpa` slicing change needed.
- **@DataJpaTest ITs that exercise `@Transactional` services (e.g. `SubledgerPostingService`) need an explicit `em.flush()` after each business-call boundary.** `@DataJpaTest` wraps the test in a transaction; service-level `@Transactional` with REQUIRED propagation joins that outer transaction instead of committing per-call. Hibernate auto-flushes only when subsequent JPA queries demand it — JdbcTemplate counts will silently undercount unless the test forces a flush. Production callers (e.g. Temporal workers) commit per-call because they have no outer transaction; the test wiring is what drifts, not the service contract.

### Database
- Migrations via Flyway. One migration file per change. Never edit existing migrations.
- All foreign keys enforced at DB level.
- Soft deletes (`deleted_at`) for all master data entities (brokers, products, etc.). **Reasoned deletes:** master-data DELETE endpoints accept `?reason=` (optional at the API for IT compatibility, required at the UI via the shared `ConfirmDeleteDialog` + `useDeleteWithReason` hook). The reason persists to `audit_log.reason` (V47) alongside the usual user / timestamp. Auditors can extract any soft-deleted row + its deletion reason via the audit log endpoint or directly from the table.
- Indexes on all foreign keys and common filter columns.
- **Schema management:** `spring.jpa.hibernate.ddl-auto: none` (see `application.yml`). Flyway is the source of truth for DDL; Hibernate never creates or validates schema at startup. The `validate` mode is incompatible with the V24 NDPR PII pattern (`@ColumnTransformer` + `columnDefinition = "bytea"`) — Hibernate's schema validator doesn't honour `columnDefinition` and reports a spurious varchar/bytea mismatch. Drift between entities and migrations is caught by integration tests (Testcontainers).

### Security
- All traffic TLS in production.
- Passwords hashed with bcrypt (min cost 12).
- JWT validation on every request — no session state in the API.
- File uploads: validate MIME type server-side; max file size configured per tenant; virus scan on upload (configurable).
- SQL: all queries via JPA/JPQL or parameterised — no string concatenation.

---

## Environment Variables

| Variable | Purpose | Where |
|---|---|---|
| `ANTHROPIC_API_KEY` | Claude API for optional AI features | `.claude/settings.local.json` (dev) / vault (prod) |
| `DB_URL` | PostgreSQL connection | env / K8s secret |
| `KEYCLOAK_URL` | Keycloak server URL | env |
| `TEMPORAL_HOST` | Temporal server address | env |
| `STORAGE_TYPE` | `minio` / `s3` / `gcs` / `azure` | env |
| `STORAGE_ENDPOINT` | Storage endpoint URL | env |
| `NAICOM_API_URL` | NAICOM integration endpoint | env / vault |
| `NIID_API_URL` | NIID integration endpoint | env / vault |
| `KYC_PROVIDER_URL` | KYC provider API endpoint | env / vault |
| `SMTP_HOST` | Email server | env |
| `CIA_NOTIFICATIONS_EMAIL_PROVIDER` | Active `EmailService` impl: `logging` / `smtp` / `sendgrid`. Default `smtp` (matchIfMissing=true). Switch between providers without code changes — only one bean activates per JVM. | env |
| `CIA_NOTIFICATIONS_EMAIL_FROM` | Sender address used by `SendGridEmailService`. Default `noreply@cia.local`. | env |
| `SENDGRID_API_KEY` | SendGrid API key, consumed by `SendGridEmailService` via `cia.notifications.email.sendgrid.api-key`. Required ONLY when `cia.notifications.email.provider=sendgrid`. | env / vault |
| `SMS_PROVIDER_URL` | SMS gateway | env / vault |
| `CIA_NOTIFICATIONS_SMS_PROVIDER` | Active `SmsService` impl: `logging` / `termii` / `twilio`. Default `logging` (matchIfMissing=true). Switch between providers without code changes — only one bean activates per JVM. | env |
| `CIA_NOTIFICATIONS_SMS_FROM` | SMS sender ID used by the live SMS impls. Max 11 chars (GSM7 alphanumeric sender-ID limit). | env |
| `PARTNER_API_RATE_LIMIT_STORE` | `redis` / `in-memory` for bucket4j | env |
| `REDIS_URL` | Redis connection (partner rate limiting) | env / vault |
| `WEBHOOK_SIGNING_SECRET` | Default HMAC-SHA256 key for webhook payloads | env / vault |
| `PII_ENCRYPTION_KEY` | pgcrypto symmetric key for NDPR PII encryption (`id_number`, `id_document_url`, `address` on customers + directors). Loss = unrecoverable customer PII. Recommended: 32+ random bytes, base64-encoded. | env / vault |
| `KEYCLOAK_ADMIN_ENABLED` | Master switch for the Module 1 UserController Keycloak admin proxy. `false` in dev when no Keycloak is running — UserController returns 503 with a clear message. Set `true` in prod. | env |
| `KEYCLOAK_ADMIN_CLIENT_ID` | Client id used by the admin proxy. Defaults to `admin-cli` (Keycloak's built-in public client — paired with USERNAME/PASSWORD for dev). For prod, set to a confidential service-account client id with `realm-management` composite role. | env |
| `KEYCLOAK_ADMIN_CLIENT_SECRET` | Service-account client secret (prod client-credentials grant). When set, the admin client uses CLIENT_CREDENTIALS grant; when unset, it falls back to PASSWORD grant against USERNAME/PASSWORD. | env / vault |
| `KEYCLOAK_ADMIN_USERNAME` | Admin username (dev password grant). Matches docker-compose's `KEYCLOAK_ADMIN`. Defaults to unset; if set, the admin client uses PASSWORD grant. | env |
| `KEYCLOAK_ADMIN_PASSWORD` | Admin password (dev password grant). Pair with `KEYCLOAK_ADMIN_USERNAME`. | env / vault |
| `KEYCLOAK_ADMIN_REALM` | Realm the admin client authenticates against. Defaults to `master` — that's where service accounts typically live. | env |
| `CIA_TENANT_BOOTSTRAP_ENABLED` | Master switch for the Slice A `TenantBootstrapRunner` (§5.4). `false` by default — dev + the IT suite never provision tenants. Set `true` in a real deployment to provision the configured tenants on boot + sweep-migrate all active tenant schemas. **Requires `KEYCLOAK_ADMIN_ENABLED=true`** (the orchestrator fails fast otherwise). | env |
| `CIA_TENANTS_BOOTSTRAP_TENANTS_<n>_*` | Per-tenant bootstrap declaration list (`cia.tenants.bootstrap.tenants[n].{schema,realm,display-name,subdomain,admin-username,admin-email,admin-temp-password}`). Supplied via the deployment manifest / secret store; the admin temp-password is a secret (forces `UPDATE_PASSWORD` on first login). | env / vault |
| `CIA_PLATFORM_REALM` | Name of the platform-admin Keycloak realm holding the `SUPER_ADMIN` role (`cia.platform.realm`, default `platform`). Bound identically by `PlatformBootstrapProperties` (cia-api) and `PlatformRealmProperties` (cia-auth) — keep the two in sync. The auth layer scopes a token from this realm to the `public` schema and exempts it from the tenant allowlist gate. | env |
| `CIA_PLATFORM_CLIENT_ID` | Public SPA client id provisioned into the platform realm for the SP2 platform-admin UI (`cia.platform.client-id`, default `cia-platform`). | env |
| `CIA_PLATFORM_REDIRECT_URIS` | Allowed redirect URIs / web origins for the platform SPA client (`cia.platform.redirect-uris`, CSV; default the local platform Vite origin). | env |
| `CIA_PLATFORM_TENANT_ALLOWLIST_ENABLED` | Arms the tenant-activation allowlist gate in `TenantContextFilter` (`cia.platform.tenant-allowlist.enabled`). `false` by default. When `true`, a non-platform request whose realm is missing from `public.tenants` or `active=false` → 401 `TENANT_INACTIVE` (platform realm exempt; fails closed if no `TenantActivationLookup` bean is wired). `cia.platform.tenant-allowlist.cache-ttl-seconds` (default 60) tunes the cached registry read. | env |
| `CIA_PLATFORM_BOOTSTRAP_ENABLED` | Master switch for the gated `PlatformBootstrapRunner` (provisions the platform realm + first super-admin on boot). `false` by default — dev + the IT suite never provision. **Requires `KEYCLOAK_ADMIN_ENABLED=true`.** Mirrors `CIA_TENANT_BOOTSTRAP_ENABLED`. | env |
| `CIA_PLATFORM_BOOTSTRAP_ADMIN_USERNAME` / `CIA_PLATFORM_BOOTSTRAP_ADMIN_EMAIL` / `CIA_PLATFORM_BOOTSTRAP_ADMIN_TEMP_PASSWORD` | First platform super-admin's credentials, consumed by `PlatformBootstrapRunner` when bootstrap is enabled (`cia.platform.bootstrap.admin-{username,email,temp-password}`). The temp-password is a secret (forces `UPDATE_PASSWORD` on first login; `@ToString.Exclude`d, never logged); the runner fails fast if it is blank while bootstrap is enabled. | env / vault |
| `SPRING_PROFILES_ACTIVE` | Set to `prod` in production — loads `application-prod.yml` (Hikari tuning, ECS structured logging, Prometheus exposure); `dev`-only beans like `DevSecurityConfig` stay inactive as long as `dev` is not also listed. **Must be paired with `CIA_DEPLOYMENT_ENVIRONMENT=production`** (neither implies the other — the safety guard keys off the marker, not the profile). | env |
| `CIA_DEPLOYMENT_ENVIRONMENT` | `local` (default) / `staging` / `production`. Arms `ProductionSafetyValidator` (fail-fast on active `dev` profile or any surviving weak-default secret). | env |
| `CIA_CORS_ALLOWED_ORIGINS` | CSV of exact browser origins allowed to call the internal `/api/**` cross-origin (`cia.cors.allowed-origins`). Default `http://localhost:5173,http://localhost:5175` (dev). **Prod MUST set this** to the SPA URLs (e.g. `https://back-office-blush-six.vercel.app,https://<platform>.vercel.app`) or the browser SPAs cannot reach the API. `allowCredentials(true)` (Bearer auth) ⇒ no `*` wildcard. Wired into `SecurityConfig` + `DevSecurityConfig` (`CorsConfig` bean). Partner `/partner/**` is M2M and intentionally NOT CORS-enabled. | env |
| `CIA_CORS_ALLOWED_ORIGIN_PATTERNS` | CSV of wildcard origin patterns (`cia.cors.allowed-origin-patterns`), e.g. `https://*.vercel.app` for per-branch preview deploys. Default empty (exact origins only). | env |
| `CIA_UPLOAD_SCAN_PROVIDER` | Active `FileScanService` (virus/malware scan on upload) — `cia.upload.scan.provider`. Default `none` → `NoOpFileScanService` (uploads pass with no scanner). A real ClamAV/API impl registers under a different value. Uploads are always size/MIME/magic-byte validated regardless of this. | env |
| `DB_POOL_MAX` / `DB_POOL_MIN` | Hikari `maximum-pool-size` / `minimum-idle` for the single shared pool (prod profile). Default `10`/`10`. Keep `replicas × DB_POOL_MAX` under PostgreSQL `max_connections`. | env |
| `DB_POOL_MAX_LIFETIME_MS` / `DB_POOL_KEEPALIVE_MS` / `DB_POOL_CONNECTION_TIMEOUT_MS` / `DB_POOL_LEAK_DETECTION_MS` | Hikari lifetime / keepalive / connection-timeout / leak-detection (prod profile). Defaults `1740000` / `300000` / `30000` / `60000`. | env |
| `CIA_DATASOURCE_REPLICA_URL` | **Optional** read-replica JDBC URL (`cia.datasource.replica.url`). UNSET (default) ⇒ single pool, runtime byte-identical to today. When set, `ReadReplicaDataSourceConfig` builds a second Hikari pool + a `ReplicaRoutingDataSource` (`@Primary`) and **only the `cia-reports` report-run queries** route to the replica (opt-in via `ReplicaRoutingContext`, NOT the broad `@Transactional(readOnly=true)` flag — see §7). The replica pool inherits the same `connection-init-sql` (`SET app.pii_key`) so encrypted PII decrypts on replica reads. **Omit to disable — do not set blank** (a blank value still activates routing). | env |
| `CIA_DATASOURCE_REPLICA_USERNAME` / `CIA_DATASOURCE_REPLICA_PASSWORD` | **Optional** replica credentials (`cia.datasource.replica.{username,password}`). Default to the primary's `DB_USERNAME`/`DB_PASSWORD` when blank (a read replica usually shares creds). Only needed if the replica authenticates differently. | env / vault |

**Frontend environment variables (Vite — prefix `VITE_`):**

| Variable | Purpose | Default (dev) |
|---|---|---|
| `VITE_API_BASE_URL` | Spring Boot API base URL | `http://localhost:8080` |
| `VITE_KEYCLOAK_URL` | Keycloak server URL | `http://localhost:8180` |
| `VITE_KEYCLOAK_REALM` | Keycloak realm name | `cia-dev` |
| `VITE_KEYCLOAK_CLIENT_ID` | Keycloak client for back office | `cia-back-office` |
| `VITE_DEMO_MODE` | When `'true'`, allow production builds to use `DevAuthProvider` (mocked auth) instead of throwing on absent Keycloak config. Set on the public stakeholder-preview Vercel URL only. Renders an amber "Demo" banner above the AppShell. NEVER enable for tenant environments. | unset |

**Local dev note:** When `import.meta.env.DEV` is true, `main.tsx` uses `DevAuthProvider` (mock user, no Keycloak) instead of `AuthProvider`. All `VITE_KEYCLOAK_*` vars are ignored in dev mode.

**Production preview note:** The public Vercel URL (`back-office-blush-six.vercel.app`) runs in demo mode (`VITE_DEMO_MODE=true`). Until real Keycloak + backend infrastructure is deployed, this URL is a frontend-only demo; mutations succeed locally but data does not persist to a real tenant DB.

**Platform console (`apps/platform`, SP2) note:** The platform super-admin console **reuses the same `VITE_KEYCLOAK_*` names** (scoped per-deployment, pointed at the `platform` realm + `cia-platform` client — `@cia/auth`'s `initKeycloak` keys `onLoad` off `VITE_KEYCLOAK_URL`), so there are no separate `VITE_PLATFORM_KEYCLOAK_*` vars. It is a separate Vercel project (secret `VERCEL_PLATFORM_PROJECT_ID`, workflow `vercel-deploy-platform.yml`) — see §10 Frontend deployment.

---

## Frontend Build Queue

**Purpose:** Authoritative ordered build list for `cia-frontend/`. Update status as each build is completed. Use this section for ongoing audit of frontend progress.

**Status legend:** `[ ]` not started · `[~]` in progress · `[x]` complete

---

### Phase 1 — Shared Infrastructure (`packages/ui` + `packages/api-client`)

> Must be completed before any module UI. All 11 modules depend on these.

| Status | Build | Deliverables |
|---|---|---|
| `[x]` | **1a. shadcn component library** | Input, Label, Textarea, Select, Checkbox, Switch, Tabs, Dialog, Sheet, Toast+Toaster, DropdownMenu, Avatar, Card, Skeleton, Tooltip, Separator, ScrollArea |
| `[x]` | **1b. Data table** | DataTable (TanStack), sortable column headers, filter toolbar, pagination with page-size selector, row actions menu |
| `[x]` | **1c. Page layout components** | PageHeader, PageSection, EmptyState, StatCard, Breadcrumb |
| `[x]` | **1d. Form infrastructure** | Form + FormField + FormItem + FormLabel + FormControl + FormMessage (RHF+Zod); FormSection, FormRow helpers |
| `[x]` | **1e. API types + React Query hooks** | DTOs for Setup, Customer, Quotation, Policy, Claims, Finance; `useGet` `useList` `useCreate` `useUpdate` `useRemove` base hooks |

---

### Phase 2 — Back Office Module Builds (in recommended order)

#### Build 2 — Module 1: Setup & Administration (35 features) 🔴 Highest priority — unlocks all other modules

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Company Settings | Company profile, password policy — form with Card sections |
| `[x]` | User Management | User list (DataTable) + UserSheet (create/edit); access group select |
| `[x]` | Access Groups | Group list + AccessGroupSheet; per-module permission checkboxes |
| `[x]` | Approval Groups | Card-based multi-level display + ApprovalGroupSheet with useFieldArray |
| `[x]` | Classes of Business | DataTable list + ClassSheet (create/edit) |
| `[x]` | Products | DataTable list + ProductSheet (single/multi-risk, commission rate, 14 seed classes); inline `+ New Class of Business` via Dialog inside Sheet |
| `[x]` | Policy Specifications | Clause bank DataTable (search, product/type filter, mandatory/optional, CRUD); template manager (per-product, type-coloured badges, upload/replace/archive/delete); **Quotes tab** — Discount Types CRUD, Loading Types CRUD, Quote Validity Period, Premium Calculation Sequence (LOADING_FIRST/DISCOUNT_FIRST) |
| `[x]` | Claims Setup | Tabbed: Reserve Categories, Notification Timelines, Documents, Loss Types (skeleton tabs ready) |
| `[x]` | Organisations | 7 working tabs (Session 78): Brokers, Reinsurers, Insurers, Branches, SBUs, Surveyors, **Adjusters** (new). Each tab has list + add/edit sheet wired to its `/api/v1/setup/*` endpoint. BrokerDto realigned to the backend shape (dropped legacy `status` + `contactPerson`, added `rcNumber` + `address`); the previous frontend↔backend drift was silent because Jackson dropped unknown fields. New `cia-setup/.../org/Adjuster*` module + V45 migration (`adjusters` table, INTERNAL/EXTERNAL type). |
| `[x]` | Vehicle Registry | Tabbed: Makes, Models, Types (skeleton tabs ready) |
| `[x]` | Partner App Management | EmptyState with Register App action (skeleton) |
| `[x]` | Customer Number Format | CustomerNumberFormatPage — prefix, includeYear, includeType (IND/CORP segments with separate sequences), sequenceLength (default 8 → 99M/type/year); live format preview via useMemo; amber warning when unconfigured |

---

#### Build 3 — Module 7: Customer Onboarding (10 features) 🟠

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Individual Onboarding | IndividualOnboardingSheet — ID type select (NIN/Voter/DL/Passport), DOB, address, occupation, broker-enabled checkbox; KYC document upload (JPG/PNG 5MB); conditional expiry date for DL/Passport |
| `[x]` | Corporate Onboarding | CorporateOnboardingSheet — RC number, CAC certificate upload, useFieldArray directors (name + ID + per-director document upload); broker-enabled |
| `[x]` | Broker-enabled flows | Checkbox toggle reveals broker Select in both individual and corporate sheets; Channel column in list shows "Direct" badge for non-broker customers |
| `[x]` | KYC Update | EditCustomerSheet — contact fields (email, phone, address, contactPerson for corporate, channel); KYC section for individual (ID type/number/expiry/document); KYC reason block (dropdown + notes, notes required if Other) shown only when KYC fields change; re-submits to KYC provider; directors section for corporate with add/edit/delete/restore + per-director KYC reason; min 2 active directors enforced; all changes dual-audit-logged |
| `[x]` | Customer Summary | CustomerDetailPage — customer number in header + summary tab; individual/corporate conditional fields; Channel row (Direct or broker name); policy/claim rows clickable → navigate to detail pages |
| `[x]` | Customer History | CustomerDetailPage Policies + Claims tabs — clickable rows navigate to /policies/:id and /claims/:id |
| `[x]` | Reports | LossRatioReportPage (by class, premium vs claims, rating badge); ActiveCustomersReportPage (by channel, individual vs corporate count) |

---

#### Build 4 — Module 2: Quotation (6 features) 🟡

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Create Quote | SingleRiskQuoteSheet — customer, product, dates, SI, rate; per-item loadings (type+format+value, multiple); per-item discounts (same); clause selection with search bar; live premium preview (Gross → +Loadings → −Discounts → Net) |
| `[x]` | Multi-risk Quote | MultiRiskQuoteSheet — per-item loadings/discounts; quote-level loadings/discounts; grand total (Σ item nets + quote adjustments); clause selection with search bar |
| `[x]` | Bulk Upload | BulkUploadPage — drag-and-drop CSV, validation results, error row detail, template download |
| `[x]` | Quote Detail | QuoteDetailPage — useParams fix (correct quote per click); risk items card with per-item loading/discount breakdown; clauses card; inputter/approver in details; Download PDF button (APPROVED/CONVERTED only); version history timeline |
| `[x]` | Quote Approval | Submit for Approval / Convert to Policy / Edit conditioned on status; status badge throughout; PDF row action on list page for APPROVED/CONVERTED quotes |
| `[x]` | Quote PDF | QuotePdfPreview — Blob URL popup with self-contained HTML + embedded CSS; auto-triggers window.print(); General Subjectivity section (3 lines); inputter + approver signature blocks; validity period from Quote Config |

---

#### Build 5 — Module 3: Policy (23 features) 🟡

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Convert Quote to Policy | CreatePolicySheet "From Approved Quote" tab — quote select, business type, payment terms |
| `[x]` | Create Policy Without Quote | CreatePolicySheet "Direct Entry" tab — customer, product, dates, SI, rate, live premium |
| `[x]` | Risk Details | Risk description field in CreatePolicySheet; PolicyDetailPage Risk Schedule card with RisksEditorDialog (Sheet) — PUT/POST/DELETE reconciliation, sum-insured aggregation, motor reg-no column gates on motor classes (B5.3d + B9) |
| `[x]` | Policy Specifications | PolicyDetailPage Document tab — clause bank (add/edit/remove), template editor button, document status |
| `[x]` | Payment + Commission | PolicyDetailPage Financial tab — debit note, commission, payment status, Post Receipt button |
| `[x]` | Coinsurance | Business type select includes Direct with Coinsurance + Inward Coinsurance options; PolicyDetailPage Coinsurance Participants card with CoinsuranceEditorDialog (Sheet) — insurer picker + per-row share %, must sum to 100% (B5.3c) |
| `[x]` | Policy Approval Flow | Submit / Approve / Reject buttons conditional on status; status badge throughout |
| `[x]` | Policy Document | Document tab: Send to Insured + Acknowledge Receipt buttons; PDF download |
| `[x]` | Debit Note | Financial tab shows debit note number, amount, commission, payment status |
| `[x]` | Survey Process | Survey tab: threshold-conditional display, AssignSurveyorDialog + SubmitSurveyReportDialog (B5.3a/b) wired to /survey/{assign,report,approve,override}; full lifecycle CTA gating (ASSIGNED → REPORT_SUBMITTED → APPROVED / OVERRIDDEN) |
| `[x]` | Policy Details Page | PolicyDetailPage 5-tab layout with full policy info, breadcrumb, action buttons |
| `[x]` | NAICOM / NIID Upload | NAICOM tab: UID display (or PENDING badge), upload log, manual trigger button; NIID for motor/marine |

---

#### Build 6 — Module 8: Finance (5 features) 🟢

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Receipts | ReceivablesTab — debit note number clickable → DebitNoteDetailDialog (policy + debit note details); "View policy" + "Post Receipt" both open detail dialog → PostReceiptSheet; "Reverse" on approved receipts → ReverseTransactionDialog with cannot-undo warning. F7 slice α: Receivables now has a sibling Receipts sub-tab (flat /api/v1/receipts list with status + method + date filters, 20-row pagination, inline reversal audit) and the DN detail dialog shows nested receipts with per-row Reverse. **F7 slice β:** Download PDF row action on the Receipts sub-tab + Download button on each nested receipt in the DN detail dialog; gated on `pdfPath !== null`; consumes `useDownloadReceiptPdf()` (blob fetch via `apiClient.get { responseType: 'blob' }` + createObjectURL/anchor-click). **F7 slice γ:** Email PDF row action ahead of Download (Receipts sub-tab) + Email button alongside Download (nested in DN detail dialog); gated on `pdfPath !== null && recipientEmail !== null`; opens shared `EmailConfirmDialog` (confirms recipient, then fires `useEmailReceipt()` mutation against POST /api/v1/debit-notes/{dnId}/receipts/{id}/email); success toast "Email queued"; 422 errorCode (RECEIPT_PDF_UNAVAILABLE / RECEIPT_RECIPIENT_UNRESOLVED) surfaces in a destructive toast with specific copy. Each row that has `emailSentAt` shows a small "Last emailed {ts} to {recipient}" badge under the status. **F11:** Row-checkbox column (parent-managed `rowSelection` state) + inline `<DownloadIconButton>` replacing the row-action "Download PDF"; bulk toolbar visible when ≥1 row selected → `<BulkEmailSheet>` (serial runner with Cancel that signals in-flight workflow) + `<BulkDownloadButton>` (POSTs item list to `/pdfs/bulk-download`, single browser save with `cia-pdfs-{ts}.zip`); `<RecentDownloadsPanel />` trigger in `PageSection` actions slot (server-driven via `useRecentDownloads(1)`, 30s staleTime, Re-download per row via `entry.parentId`). |
| `[x]` | Bulk Receipts | PostReceiptSheet opens in bulk mode with all outstanding DNs selected; shows total with per-note breakdown |
| `[x]` | Receipt Approval | Receipts DataTable with approve/reject row actions on PENDING_APPROVAL rows |
| `[x]` | Payables | PayablesTab — credit note number clickable → CreditNoteDetailDialog (source type, reference, description, beneficiary, amount); "Process Payment" + "View source" both open detail dialog → ProcessPaymentSheet (amount, method, bank, ref); "Reverse" → ReverseTransactionDialog. F7 slice α: Payables now has a sibling Payments sub-tab (flat /api/v1/payments list with same filter + pagination + reversal audit shape) and the CN detail dialog shows nested payments with per-row Reverse. **F7 slice β:** Download PDF row action on the Payments sub-tab + Download button on each nested payment in the CN detail dialog; gated on `pdfPath !== null`; consumes `useDownloadPaymentPdf()`. Voucher header label varies by `CreditNote.entityType` (CLAIM → "CLAIM SETTLEMENT VOUCHER", COMMISSION → "COMMISSION VOUCHER", REINSURANCE → "FAC PREMIUM VOUCHER", ENDORSEMENT → "ENDORSEMENT REFUND VOUCHER"). **F7 slice γ:** Email PDF row action ahead of Download (Payments sub-tab) + Email button alongside Download (nested in CN detail dialog); gated on `pdfPath !== null && recipientEmail !== null`; `recipientEmail` resolved server-side per row via `BeneficiaryEmailResolverDispatcher` (CLAIM → Customer.email / COMMISSION → Broker → Agent fallback / REINSURANCE → ReinsuranceCompany / ENDORSEMENT → Customer). Shared `EmailConfirmDialog` → `useEmailPayment()` mutation against POST /api/v1/credit-notes/{cnId}/payments/{id}/email; success toast "Email queued"; 422 errorCode (PAYMENT_PDF_UNAVAILABLE / PAYMENT_RECIPIENT_UNRESOLVED) surfaces in destructive toast. Each row that has `emailSentAt` shows a "Last emailed {ts} to {recipient}" badge. **F11:** Same shape as ReceiptsListSection (checkbox column + inline `<DownloadIconButton type="PAYMENT">` + bulk toolbar with `<BulkEmailSheet type="PAYMENT">` + `<BulkDownloadButton>` + `<RecentDownloadsPanel />`); selection-derived `selectedDownloadable` filtered to `pdfPath !== null` items only. |
| `[x]` | Payment Approval | Payments DataTable with Approve/Reject actions on PENDING rows |

---

#### Build 7 — Module 4: Endorsements (10 features) 🔵

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Create Endorsement | CreateEndorsementSheet — type-driven form: period fields for Renewal/Extension/Reduction/Change, SI field for Increase/Decrease with live pro-rata preview, item field for Add/Delete, cancel/reversal info banners |
| `[x]` | Increase/Decrease Sum Insured | newSumInsured field + indicative pro-rata = (SI × rate / 365 × days) shown as debit or credit |
| `[x]` | Add/Delete Insured Items | itemDescription field conditional on ADD_ITEMS / DELETE_ITEMS type |
| `[x]` | Endorsement Approval | Submit/Approve/Reject buttons conditional on status; approval timeline with step indicators |
| `[x]` | Debit Note Analysis Report | DebitNoteAnalysisPage — by period (monthly) and by endorsement type; StatCards + two tables |

---

#### Build 8 — Module 5: Claims (23 features) 🔵

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Claim Notification | RegisterClaimSheet — incident date, notification date, nature/cause of loss, location, description, estimated loss, contact |
| `[x]` | Claim Registration | Full form with validation; status flow: REGISTERED → PROCESSING → PENDING_APPROVAL → APPROVED → SETTLED |
| `[x]` | Bulk Claim Registration | BulkClaimPage — CSV drag-and-drop, validation results with error row detail, template download |
| `[x]` | Claim Dashboard | ClaimsListPage — StatCard row: Open Claims, Total Reserve (₦), Total Paid YTD |
| `[x]` | Claim Detail | ClaimDetailPage 5-tab layout (Summary, Processing, Documents, Inspection, DV); detail rendered from real backend ClaimDto (B7) — natureOfLoss / causeOfLoss / contactName / contactPhone / DV state all from entity columns, not MockClaim |
| `[x]` | Claim Processing | Processing tab: Reserves table (add/total), Expenses table (approve/reject), Comments card backed by ClaimComment aggregate (B11) — append-only feed via AddCommentDialog, GET /comments paged read |
| `[x]` | Loss Inspection | Inspection tab driven by live ClaimInspection record (B6); AssignInspectorDialog + SubmitInspectionReportDialog (B8) + Approve/Decline/Override + zip bundle download; full lifecycle CTA gating |
| `[x]` | Claim Approval | Submit/Approve/Reject buttons conditional on status; status badge in header; "N doc(s) missing" pending-mandatory badge driven by RequiredDocsService (B12) |
| `[x]` | DV Generation | DV tab: Own Damage / Third Party / Ex-gratia type selection cards; DV amount input (defaults to approvedAmount); wired to POST /api/v1/claims/{id}/dv/generate (B7) |
| `[x]` | DV Execution | Execute DV button wired to POST /api/v1/claims/{id}/dv/execute (B7); status row shows executed-on date; Download DV gated on dvDocumentPath |

---

#### Build 9 — Module 6: Reinsurance (17 features) 🟣

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Peril Group Setup | Peril group config managed within TreatySheet (class of business select drives peril scope) |
| `[x]` | Treaty Setup | TreatiesTab DataTable (Surplus/QS/XOL type chips) + TreatySheet (edit); "Batch reallocation" row action → BatchReallocationSheet scoped to that treaty's allocations; "Deactivate/Activate" → confirmation Dialog |
| `[x]` | RI Allocation | AllocationsTab DataTable — retention/ceding amounts, treaty name + reinsurers, 4 status variants; policy number clickable → PolicyAllocationSheet (RI split bar, Confirm/Approve/Decline); "Batch Reallocation" button → BatchReallocationSheet |
| `[x]` | RI Confirmation | "Confirm All" banner → Dialog listing all AUTO_ALLOCATED policies with ceding amounts; Excess Capacity banner "Create FAC" → CreateFACOfferSheet; row-level Confirm/Approve/Decline via PolicyAllocationSheet |
| `[x]` | Outward FAC | FACTab Outward sub-tab; "Generate credit note" → FACCreditNoteDialog (gross/commission/net, Submit to Finance + Download PDF); "Download offer slip" → FACOfferSlipDialog; "Cancel FAC" → confirmation Dialog |
| `[x]` | Inward FAC | FACTab Inward sub-tab; "Renew" → InwardFACActionSheet mode=RENEW (new period + amendable share%/rate, live financial preview); "Extend period" → same sheet mode=EXTEND; "Cancel" → confirmation Dialog |
| `[x]` | Batch Reallocation | BatchReallocationSheet — checkbox multi-select of non-APPROVED allocations, new treaty select, effective date, reason; opened from both AllocationsTab and TreatiesTab |
| `[x]` | Returns & Bordereaux | ReportsTab Bordereaux sub-tab: premium + claims tables with Export; Returns sub-tab: quarterly period list with Generate/Download |
| `[x]` | RI Recoveries | ReportsTab Recoveries sub-tab: claim/treaty/gross paid/RI share/recovery amount/status |

---

#### Build 10 — Module 10: Audit & Compliance (15 features) ✅

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Audit Log Viewer | AuditLogTab — filter bar (entity type, action, user, entity ref, date range); DataTable with 15 mock entries; entity ref cells clickable → AuditEventDetailSheet (before/after JSON panels side-by-side); client-side CSV export |
| `[x]` | Login & Session Log | LoginLogTab — filter by event type, user, date; DataTable with 12 entries; LOGIN/LOGOUT/LOGIN_FAILED/PASSWORD_RESET/ACCOUNT_LOCKED event type badges; CSV export |
| `[x]` | CSV Export | Integrated into AuditLogTab and LoginLogTab — `exportCSV()` uses Blob + createObjectURL; filename includes today's date; exports filtered rows only |
| `[x]` | 6 Pre-built Reports | ReportsTab — 6 sub-tabs: Actions by User (ranked), Actions by Module, Approval Audit Trail, Data Change History, Login Security Report (with risk badge), User Activity Summary (activity score); Export CSV button on each |
| `[x]` | Real-time Alerts | AlertsTab — alert list DataTable (OPEN/ACKNOWLEDGED) with severity badges; Acknowledge confirmation Dialog; alert threshold summary cards; AlertConfigDialog (failed login threshold, bulk delete threshold, large approval ₦ threshold, business hours, retention years, email alert toggle + recipients) |

---

#### Build 11 — Module 11: Reports & Analytics (20 features) ✅

| Status | Sub-page | Key features |
|---|---|---|
| `[x]` | Reports Home | ReportsHomePage — pinned reports row (Bookmark01Icon), quick-access grid by category (7 categories × 4 cards), recently run section, empty pin state with Browse Library CTA |
| `[x]` | Report Library | ReportLibraryPage — search bar, category filter tabs (All + 7 categories), card list with Run / Clone & Edit actions |
| `[x]` | Report Viewer | ReportViewerPage — dynamic filter form (ReportFilterForm), results table (ReportResultTable with ₦ / % / date formatting), Recharts chart (BAR/LINE/PIE/TABLE_ONLY), export bar (CSV + PDF + Pin/Unpin) |
| `[x]` | Custom Report Builder | CustomReportBuilderPage — 3-step stepper: Step1DataSource (15 picker data sources = 6 business + 9 closures; the 2 backend aggregate sources `RM_COMMISSION` (V64) + `UNDERWRITING_PERFORMANCE` (V66) are deliberately excluded from the picker — fixed-shape substrates), Step2FieldsFilters (field picker + computed field badges + date filter toggles), Step3Visualisation (chart type cards + axis selectors + name + category); Save & Run navigates to viewer |
| `[x]` | Report Access Setup | ReportAccessSetupPage — access group selector, expandable category/report permission matrix (View / Export CSV / Export PDF checkboxes), category-level and report-level override |
| `[x]` | Backend: cia-reports module | Maven module: domain entities (ReportDefinition, ReportPin, ReportAccessPolicy) + JSONB config (ReportConfig via `@JdbcTypeCode(SqlTypes.JSON)` — native Hibernate 6) + repositories + services (ReportRunnerService, ReportQueryBuilder, ReportCsvRenderer, ReportPdfRenderer) + ReportController (14 endpoints). `ReportQueryBuilder.BASE_QUERIES` switched from `Map.of` → `Map.ofEntries` (16 entries incl. B2's `RM_COMMISSION`) + `BASE_QUERY_TAILS` map for aggregation suffixes (TRIAL_BALANCE + RM_COMMISSION GROUP-BY) + 3 new filter keys (`account_code`, `source_module`, `classification`). `ReportFilter` carries an optional `defaultValue` string (Builder Step 2 sets it for DATE filters; the Viewer pre-fills the picker). |
| `[x]` | Flyway V17 + V18 + V44 | V17 creates report_definition, report_pin, report_access_policy tables; V18 seeds 55 SYSTEM reports (12 Underwriting + 13 Claims + 9 Finance + 8 Reinsurance + 5 Customer + 8 Regulatory); V44 adds 12 SYSTEM CLOSURES reports (4 GL + 4 IFRS 17 PAA + 4 IFRS 9); V64 adds 1 FINANCE report ("RM Commission Accrual", `RM_COMMISSION` data source — B2). Total 68 SYSTEM reports across 7 categories. Adding a new pre-built closures report is a Flyway data migration (V45+) plus a possible `DataSource` enum + `BASE_QUERIES` entry if a new substrate is involved. |

---

#### Build 12 — Module 12: Period-End Closures (Phase 5 frontend, 16 slices) ✅

All 16 slices live under `cia-frontend/apps/back-office/src/modules/closures/` with the `/closures` route mounting a 13-tab navigation. Backend Phases 1–4 + Slice 1.10 ship the GL gateway, IFRS 17 PAA engines, IFRS 9 measurement, and NAICOM submission state machine; Phase 5 is the read/admin surface for all of it.

| Status | Slice | Key features |
|---|---|---|
| `[x]` | F5.1 — Periods | PeriodLockListPage with FY + granularity selectors, 4 StatCards (Open / Soft-closed / Hard-closed / Reopened), DataTable of periods with row-action gating (Soft-close / Hard-close / Reopen / History); ClosePeriodDialog (soft/hard mode), ReopenPeriodDialog (HARD only, CFO role), LockHistorySheet rendering the Type-2 SCD `period_lock` history as a vertical timeline, CreateFiscalYearSheet for FY 2025+ |
| `[x]` | F5.3 — Chart of Accounts | Read-only ChartOfAccountsPage — full 129-row 3-level tree with expand/collapse, account-type filter, search highlight, IFRS-17 + IFRS-9 role badges per node |
| `[x]` | F5.7 — Posting Rules | Flat-table PostingRulesPage — 6 V33-seeded rules with Dr/Cr code + COA-resolved name + monospaced narrative template + ACTIVE badge. Backend gap closed: new `PostingRuleController` (`GET /api/v1/finance/posting-rules`, `hasRole('FINANCE_VIEW')`) + `PostingRuleService.findAll()` + `PostingRuleResponse` enriched with `ChartOfAccountService::findByCode`. Footer block explains the FAC_PREMIUM_CEDED compound-posting carve-out |
| `[x]` | F5.4 — Journal Entries | JournalEntryBrowserPage (status / source-module / account / business-date filters, cursor pagination, 3 StatCards), JournalEntryDetailSheet (status badge, idempotency triple, line table with COA-resolved names + class-of-business chip) |
| `[x]` | F5.5 — Trial Balance | TrialBalanceReportPage — cumulative-since-inception balance at chosen business date; account-type sub-totals, Σdr = Σcr footer with JE-line backing count |
| `[x]` | F5.6 — Backfill | BackfillAdminPage (PLATFORM_ADMIN gated) — start dry-run / live, parameters form, localStorage workflow tracking with polling status, Temporal workflow ID + activity log per run |
| `[x]` | F5.8 — PAA Period Close | PaaPeriodClosePage — FY + Period selectors, Run PAA close button (orchestrator), §83/§84 InsuranceServiceResult card with per-engine breakdown (LRC release / LIC impact / discount unwind / onerous test) |
| `[x]` | F5.9/10 — §103 Movement Analysis | PaaMovementAnalysisPage (collapsed from two planned slices when the single endpoint already returned both halves) — LRC + LIC roll-forward tables via shared generic `RollforwardTable<T>` component; per-group breakdown rows |
| `[x]` | F5.11 — Contract Groups | ContractGroupsPage — portfolio + cohort + onerousness + status filters; §22 permanent-assignment empty state pointing at `ContractGroupingService` event-driven creation |
| `[x]` | F5.12 — Holdings | HoldingsListPage (asset-type + classification + status filters; 4 StatCards) + HoldingClassificationHistorySheet (Type-2 SCD reclassification trail per §B4.1.26) |
| `[x]` | F5.13 — IFRS 9 Measurement | Ifrs9MeasurementPage — per-engine run buttons (AmortisedCost / FairValue / InvestmentECL / PremiumReceivableECL), per-engine result cards, FINANCE_APPROVE gated |
| `[x]` | F5.14 — IFRS 9 §B5.5.39 | Ifrs9MovementAnalysisPage — combined investment roll-forward + premium-receivable ECL section via shared `RollforwardTable<T>`; relays V40 view via `Ifrs9MovementAnalysisService` |
| `[x]` | F5.15 — NAICOM Submissions | NaicomSubmissionsPage — FY + Period + State filter row, 4 StatCards, submissions table with N01–N08 type codes, `enabled: canList` query gate (mirrors backend "at least one filter" guard), GenerateSubmissionDialog with 8 NAICOM types, NaicomSubmissionDetailSheet state-machine console (Submit / Acknowledge / Retract / Archive depending on state) + Type-2 SCD event timeline + collapsible payload JSON preview |
| `[x]` | F5.16 — NAICOM Artifacts | "Rendered artifacts" block inside NaicomSubmissionDetailSheet — JSON / CSV / PDF rows (XML excluded; no backend renderer); render mutation keyed by `ArtifactFormat` doubles as per-row spinner state via `mutation.variables === format`; download via `apiClient.get { responseType: 'blob' }` + synthesized filename; Re-render gated on FINANCE_APPROVE |
| `[x]` | Closeout fixes | `MinioStorageService.@PostConstruct ensureBucketExists()` (eliminates fresh-MinIO 500s on first upload, non-fatal); `FiscalYearService.close()` cascades hard-close on non-HARD child periods via `PeriodLockService.hardClose`; deleted unused `FiscalPeriodResolver.resolveDayForBusinessDate` infrastructure (zero callers — JEs anchor to MONTH per Slice 1.4 D1=A) |

`@cia/api-client/finance-closures.ts` is the single source of zod truth for every Module 12 frontend page. Header convention: enums hoisted to a single "Enums" section at the top; DTOs may reference any enum + any earlier DTO; recursive shapes use `z.lazy()` with explicit `z.ZodType<...>`. The only extracted shared UI component to date is `RollforwardTable<T extends Record<string, number>>` (PaaMovementAnalysisPage + Ifrs9MovementAnalysisPage). Two flagged-not-extracted patterns (rule-of-three pending) live in cia-log.md under the F5.15 entry: state-conditional transition controls (F5.1 + F5.15) and the `enabled: canList` filter-shape-validity gate (single occurrence at F5.15).

---

### Phase 3 — Partner Portal (`apps/partner`)

> Start after Phase 2 Builds 2–6 are complete (core insurance workflow live).

| Status | Build | Deliverables |
|---|---|---|
| `[ ]` | **P1. Authentication** | OAuth2 client credentials display, token test flow, scope overview |
| `[ ]` | **P2. API Explorer** | Interactive API documentation, request builder, response viewer |
| `[ ]` | **P3. Webhook Management** | Register/list/delete webhooks, delivery log, retry status, signing secret display |
| `[ ]` | **P4. Sandbox** | Sandbox mode toggle, sandbox data indicator, test credential management |
| `[ ]` | **P5. Usage Dashboard** | Request counts, error rates, rate limit tier display |

---

### Build Progress Summary

| Phase | Builds | Complete | Status |
|---|---|---|---|
| Phase 1 — Infrastructure | 5 | 5 | `[x]` Complete |
| Phase 2 — Back Office Modules | 11 | 11 | `[x]` Complete (Modules 1–11 + Module 12 closures frontend) |
| Phase 3 — Partner Portal | 5 | 0 | `[ ]` Not started |
| **Total** | **21** | **16** | **76% complete** |

> Update the status column and progress summary as builds complete. Each completed build should also be reflected in cia-log.md under the session that finished it.

---

## Open Questions (Resolve Before Building Affected Modules)

1. ~~**KYC Provider**~~ — **Resolved: provider-agnostic abstraction** (`KycVerificationService` interface; implementations per provider injected via config).
2. ~~**Phase 1 Scope**~~ — **Resolved:** Build order confirmed: Setup & Admin → Customer Onboarding → Quotation → Policy → Finance → Endorsements → Claims → Reinsurance.
3. ~~**Email/SMS Provider**~~ — **Resolved: provider-agnostic abstraction** (`NotificationService` interface; email and SMS implementations injected via config).
4. **NAICOM/NIID API** — Do we have sandbox credentials and API documentation?
5. **Currency** — Is NGN the only currency at launch, or do we need multi-currency from day one?
6. **Reporting** — PRD mentions several reports. Is there a BI tool requirement, or are all reports in-app exports only?
7. ~~**Per-policy agent / RM commission attribution** (PRD Q#11)~~ — **Resolved:** snapshot on the policy. Broker + agent shipped in Session 84d (mutually-exclusive intermediary, Cr 2320 / 2330); **RM shipped in B2** as the exclusive third source on direct-channel policies (V62 snapshot, Cr 2520, accrual-only no CreditNote — paid via external payroll).
