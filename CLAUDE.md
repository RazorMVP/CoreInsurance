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
| Backend | Java 21 + Spring Boot 3 | Enterprise-grade, strong typing, excellent PostgreSQL/Keycloak/Temporal ecosystem |
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
│  │  Vercel / CDN  │ ◀──────────────  │      16 Maven modules          │  │
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
├── cia-setup/           # Module 1: Setup & Administration (35 features)
├── cia-customer/        # Module 7: Customer Onboarding & KYC (10 features)
├── cia-quotation/       # Module 2: Quotation (5 features)
├── cia-policy/          # Module 3: Policy (23 features)
├── cia-endorsement/     # Module 4: Endorsements (10 features)
├── cia-claims/          # Module 5: Claims (23 features)
├── cia-reinsurance/     # Module 6: Reinsurance (17 features)
├── cia-finance/         # Module 8: Finance (5 features)
├── cia-partner-api/     # Module 9: Partner Open API (Insurtech connectivity, webhooks, docs)
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

```
cia-frontend/
├── src/
│   ├── modules/              # One directory per business module
│   │   ├── setup/
│   │   ├── customers/
│   │   ├── quotation/
│   │   ├── policy/
│   │   ├── endorsements/
│   │   ├── claims/
│   │   ├── reinsurance/
│   │   └── finance/
│   ├── shared/
│   │   ├── components/       # shadcn/ui wrappers and domain components
│   │   ├── hooks/            # React Query hooks, auth hooks, pagination
│   │   ├── api/              # Axios instance (JWT attach, X-Tenant-ID), query client
│   │   └── auth/             # Keycloak JS adapter, AuthProvider, useAuth hook
│   └── app/                  # React Router v6, layout shell, providers
```

**Frontend patterns:**

- React Query for all server state — no Redux for remote data.
- Keycloak JS adapter in silent SSO mode; token auto-refreshed before expiry.
- `X-Tenant-ID` header injected by Axios interceptor from Keycloak JWT claim.
- Lazy-loaded module routes — each module chunk loaded on first visit.
- shadcn/ui components extended (never patched at source) to maintain upgrade path.

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

#### 5.4 New Tenant Provisioning

```
Super-admin creates tenant (admin API or super-admin console):
  1. Keycloak   — create realm, create admin user, configure roles and groups
  2. PostgreSQL — CREATE SCHEMA {tenant_id}
  3. Flyway     — run all migrations against {tenant_id} schema
  4. Seed       — insert defaults: currencies, policy number format, approval groups
  5. Config     — set KYC provider, storage type, notification provider, AI flag
  Tenant is live. Subdomain routes to their isolated schema.
```

---

### 6. Multi-Tenancy Model

- **Schema-per-tenant** in PostgreSQL. Each insurance company gets its own isolated schema (e.g., `tenant_acme`, `tenant_leadway`).
- Tenant resolved via subdomain (`acme.cia.app`) or `X-Tenant-ID` header; the value is embedded as a custom claim in the Keycloak JWT.
- Keycloak realm per tenant for complete auth isolation — a token from Tenant A cannot authenticate against Tenant B.
- All ORM queries are tenant-scoped via Hibernate's `MultiTenantConnectionProvider` and `CurrentTenantIdentifierResolver` — no cross-schema query is possible through the application layer.
- Per-tenant configuration (stored in tenant schema): products, classes of business, approval groups, policy number formats, AI feature flag, KYC provider, notification providers.
- Tenant schemas provisioned by Flyway at tenant creation time; all subsequent migrations run against every schema on API startup.

---

### 7. Data Architecture

**Schema strategy:** `public` schema holds only shared infrastructure (tenant registry). All business tables live in the tenant-specific schema.

**Connection pooling:** HikariCP with one pool per tenant schema, lazily initialised on first request to that tenant.

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
5. Spring Security validates JWT signature using Keycloak JWKS (cached, auto-refreshed)
6. JwtAuthConverter maps realm_access.roles → Spring GrantedAuthority list
7. TenantContextFilter reads tenant_id claim → sets CurrentTenantIdentifierResolver ThreadLocal
8. @PreAuthorize on controllers enforces authority requirements per endpoint
9. Hibernate routes all queries to the correct tenant schema for that thread
```

**RBAC mapping:**

| Keycloak Role | Spring Authority | Usage |
|---|---|---|
| `{module}_create` | `{module}:create` | POST endpoints |
| `{module}_view` | `{module}:view` | GET endpoints |
| `{module}_update` | `{module}:update` | PUT / PATCH endpoints |
| `{module}_approve` | `{module}:approve` | Approval actions |

Access groups aggregate roles; users inherit permissions through their access group. Approval groups are separate — they define who can approve transactions within configured amount ranges.

**NDPR compliance:**

- PII fields (name, DOB, NIN, address, email) encrypted at rest via PostgreSQL `pgcrypto`.
- All data access logged to per-tenant audit table.
- Data retention period enforced per tenant config via scheduled Temporal purge workflow.
- Data export endpoint available to satisfy NDPR data subject access requests.

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
  ├── postgres:16        :5432   — PostgreSQL
  ├── keycloak:24        :8180   — Keycloak (dev realm pre-seeded)
  ├── temporalite        :7233   — Temporal single-binary dev server
  ├── temporal-ui        :8088   — Temporal workflow browser
  └── minio              :9000   — Object storage
                         :9001   — MinIO console

Spring Boot (cia-api)  :8080    — mvn spring-boot:run -pl cia-api -Pdev
Vite dev server        :5173    — npm run dev (inside cia-frontend/)
```

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

**Frontend deployment:**

- Vercel: connect `cia-frontend/` subdirectory; auto-deploy on push to `main`.
- `VITE_API_BASE_URL` and `VITE_KEYCLOAK_URL` set as Vercel environment variables per environment (dev / staging / prod).

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
| 1 | Setup & Administration | 35 | Products, classes, approval groups, master data, partner management |
| 2 | Quotation | 5 | Quote documents, approval workflow |
| 3 | Policy | 23 | Policy documents, debit notes, NAICOM/NIID upload |
| 4 | Endorsements | 10 | Endorsement documents, debit/credit notes |
| 5 | Claims | 23 | Reserves, DVs, claim settlements, credit notes to finance |
| 6 | Reinsurance | 17 | RI allocations, offer slips, credit notes, bordereaux |
| 7 | Customer Onboarding | 10 | Customer records, KYC status, reports |
| 8 | Finance | 5 | Receipts, payments, settled/outstanding tracking |
| 9 | Partner Open API | 15 | OAuth2 client management, REST partner API, webhooks, OpenAPI docs, Postman collection |

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

### API Design
- RESTful JSON APIs.
- All endpoints prefixed `/api/v1/`.
- Tenant context always resolved from JWT, never from request body.
- Standard response envelope: `{ "data": ..., "meta": ..., "errors": [...] }`.
- Pagination: cursor-based for large lists.

### Partner API Design (cia-partner-api specific)

- Partner controllers never expose business module entities or internal DTOs directly. All partner responses use `Partner*Response` types defined in `cia-partner-api/controller/dto/`.
- Each `Partner*Response` carries a static `from(BusinessDto)` factory that maps from the business module DTO to the partner contract. This isolates the external API surface from internal model changes.
- `@Schema` annotations (class-level and field-level with `example`) belong **exclusively** on `cia-partner-api` DTOs. Business modules (`cia-policy`, `cia-quotation`, `cia-customer`, `cia-setup`, etc.) must not import or use swagger-annotations — they are a presentation concern.
- Every `@RestController` method in `cia-partner-api` must have `@ApiResponses` covering all applicable codes: 200/201, 400, 401, 403, 404 (where applicable), 429.

### Testing Requirements
- Backend: unit tests for all business logic; integration tests for all repository methods (Testcontainers); API tests for all controllers.
- Frontend: unit tests for all utility functions and hooks; component tests for critical flows.
- E2E: golden paths for each module (Playwright).
- Minimum coverage: 80% line coverage on backend business logic.

### Database
- Migrations via Flyway. One migration file per change. Never edit existing migrations.
- All foreign keys enforced at DB level.
- Soft deletes (`deleted_at`) for all master data entities (brokers, products, etc.).
- Indexes on all foreign keys and common filter columns.

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
| `SMS_PROVIDER_URL` | SMS gateway | env / vault |
| `PARTNER_API_RATE_LIMIT_STORE` | `redis` / `in-memory` for bucket4j | env |
| `REDIS_URL` | Redis connection (partner rate limiting) | env / vault |
| `WEBHOOK_SIGNING_SECRET` | Default HMAC-SHA256 key for webhook payloads | env / vault |

---

## Open Questions (Resolve Before Building Affected Modules)

1. ~~**KYC Provider**~~ — **Resolved: provider-agnostic abstraction** (`KycVerificationService` interface; implementations per provider injected via config).
2. ~~**Phase 1 Scope**~~ — **Resolved:** Build order confirmed: Setup & Admin → Customer Onboarding → Quotation → Policy → Finance → Endorsements → Claims → Reinsurance.
3. ~~**Email/SMS Provider**~~ — **Resolved: provider-agnostic abstraction** (`NotificationService` interface; email and SMS implementations injected via config).
4. **NAICOM/NIID API** — Do we have sandbox credentials and API documentation?
5. **Currency** — Is NGN the only currency at launch, or do we need multi-currency from day one?
6. **Reporting** — PRD mentions several reports. Is there a BI tool requirement, or are all reports in-app exports only?
