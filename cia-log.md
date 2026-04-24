# CIA Project Change Log

All changes, decisions, and configurations made during the development of the Core Insurance Application (General Business).

---

## 2026-04-20

### Session 1 — Project Setup & Planning

**Changes made:**

- `.claude/settings.json` — Created project-level permissions file. Allowed: WebSearch, WebFetch, and non-destructive Bash commands (source, export, curl, jq, cat, ls, grep, echo, which, wc, file, pwd, mkdir, touch, head, tail, find, sort, tree, diff, node, npm, npx, git status, git diff, git log).

- `.claude/settings.local.json` — Created local settings file with `ANTHROPIC_API_KEY` env placeholder. Gitignored by default.

- `.claude/skills/cia/SKILL.md` — Created the `cia` Claude skill. Encodes full domain context: 8 modules, 128 features, tech stack, multi-tenancy model, Nigerian regulatory integrations (NAICOM, NIID, NDPR), key business rules, data model highlights, and development conventions.

- `CLAUDE.md` — Created project CLAUDE.md. Codifies project overview, tech stack decisions with rationale, architecture, module inventory, development standards, and open questions.

**Decisions made:**

- **Stack confirmed:** React + Vite (frontend), Java 21 + Spring Boot 3 (backend), PostgreSQL schema-per-tenant, Keycloak (auth), Temporal (workflows), MinIO S3-compatible adapter (storage).
- Better Auth → replaced with **Keycloak** (Java ecosystem fit, self-hostable).
- Inngest → replaced with **Temporal** (mature Java SDK, durable workflows, self-hostable, used in financial systems at scale).
- Storage abstracted behind S3-compatible interface for cloud-agnostic / on-prem deployment.
- Claude API integration is **optional and feature-flagged per tenant**.

**PRD ingested:**

- Source: [CIAGB Confluence](https://akinwalenubeero.atlassian.net/wiki/spaces/CIAGB/overview)
- All 8 module pages read in full (Setup & Admin, Quotation, Policy, Endorsements, Claims, Reinsurance, Customer Onboarding, Finance).

**Open questions (pending clarification):**

- ~~KYC provider~~ → **Provider-agnostic** (resolved 2026-04-20)
- ~~Phase 1 module priority~~ → **Confirmed order:** Setup → Customer → Quotation → Policy → Finance → Endorsements → Claims → Reinsurance (resolved 2026-04-20)
- ~~Email/SMS notification provider~~ → **Provider-agnostic** (`NotificationService` abstraction — email + SMS implementations via config) (resolved 2026-04-20)
- ~~NAICOM/NIID API access~~ → **Stub adapters** confirmed. Post-approval async Temporal workflow with exponential backoff retry. Approval flow never blocks on NAICOM/NIID. Swap to live adapter via Spring profile when credentials arrive. (resolved 2026-04-20)

---

## 2026-04-21

### Session 2 — System Architecture, Partner Open API Design & Backend Scaffold

**Architecture documentation:**

- `CLAUDE.md` — Replaced generic `## Architecture` section with comprehensive `## System Architecture` (11 subsections: request flow, multi-tenancy, security layers, module topology, workflow engine, document generation, storage abstraction, KYC abstraction, partner API platform, AI integration, regulatory integrations). Added `## Partner Open API Platform` section (9: target users, API surface, OAuth2 CC auth, webhook system, rate limiting, docs deliverables, partner management, sandbox).

**Skill updated:**

- `.claude/skills/cia/SKILL.md` — Updated module count (8 → 9 modules, 128 → 143 features). Added Module 9 — Partner Open API (15 features). Added partner entities to data model. Added `## SESSION COMPLETION GATE` section with mandatory 6-item protocol (cia-log.md, CLAUDE.md, OpenAPI endpoints, Postman collection, backend APIs). Added mandatory `@Operation` / `@ApiResponse` / `@SecurityRequirement` annotation requirements for all partner controllers.

**Hooks added:**

- `.claude/settings.json` — Added `Stop` hook (displays 6-item SESSION COMPLETION GATE checklist to user on session end) and `PreCompact` hook (injects gate checklist into model context via `hookSpecificOutput.additionalContext` before compaction).

**Backend scaffold created — `cia-backend/` (Maven multi-module):**

Parent POM: `com.nubeero.cia:cia-backend:1.0.0-SNAPSHOT`, Spring Boot 3.3.5 parent, Java 21. 17 modules declared in build order. Key version pins: Temporal 1.25.0, MapStruct 1.5.5.Final, Springdoc 2.5.0, PDFBox 3.0.2, MinIO 8.5.11, AWS SDK v2 2.25.60, Bucket4j 0.12.7, Testcontainers 1.20.1.

**`cia-common` module — shared infrastructure:**

| File | Description |
| --- | --- |
| `tenant/TenantContext.java` | ThreadLocal holding current tenant schema name; `setTenantId`, `getTenantId`, `clear` |
| `tenant/MultiTenantConnectionProvider.java` | Hibernate `MultiTenantConnectionProvider<String>`; sets PostgreSQL schema per connection |
| `tenant/TenantIdentifierResolver.java` | Hibernate `CurrentTenantIdentifierResolver<String>`; reads from TenantContext or defaults to "public" |
| `entity/BaseEntity.java` | `@MappedSuperclass`; UUID PK, JPA-audited createdAt/updatedAt/createdBy, softDelete() |
| `api/ApiResponse.java` | Generic response envelope: `{ data, meta, errors }` with static factories |
| `api/ApiMeta.java` | Pagination metadata: total, page, size, nextCursor, prevCursor |
| `api/ApiError.java` | Error detail: code, message, field |
| `exception/CiaException.java` | Base RuntimeException with errorCode + HttpStatus |
| `exception/ResourceNotFoundException.java` | 404 for missing entities |
| `exception/BusinessRuleException.java` | 422 for business rule violations |
| `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`; handles CiaException, validation, unexpected errors |
| `audit/AuditAction.java` | Enum: CREATE, UPDATE, DELETE, APPROVE, REJECT, SUBMIT, SEND, CANCEL, REVERSE, EXECUTE |
| `audit/AuditLog.java` | `@Entity audit_log`; entity snapshots with JSONB old/new values |
| `audit/AuditLogRepository.java` | JPA repository; query by entity, user, time range |
| `audit/AuditService.java` | Writes audit records; resolves userId/userName from SecurityContextHolder JWT |
| `config/CiaCommonAutoConfiguration.java` | `@EnableJpaAuditing`; `AuditorAware` bean reading JWT subject |

**`cia-auth` module — Keycloak / Spring Security:**

| File | Description |
| --- | --- |
| `TenantContextFilter.java` | `OncePerRequestFilter`; reads `tenant_id` JWT claim → TenantContext |
| `JwtAuthConverter.java` | Maps `realm_access.roles` to `ROLE_*` Spring authorities |
| `SecurityConfig.java` | `@EnableWebSecurity`; stateless JWT, permits health/partner-docs, adds TenantContextFilter |
| `AuthenticatedUserService.java` | `currentUserId()`, `currentUserName()`, `currentTenantId()`, `hasRole()` |

**`cia-storage` module — document storage abstraction:**

| File | Description |
| --- | --- |
| `DocumentStorageService.java` | Interface: upload, download, delete, presignedUrl |
| `config/StorageProperties.java` | `@ConfigurationProperties(cia.storage)`: type, endpoint, bucket, credentials, region |
| `impl/MinioStorageService.java` | MinIO adapter; `@ConditionalOnProperty(cia.storage.type=minio)` |
| `impl/S3StorageService.java` | AWS S3 adapter; `@ConditionalOnProperty(cia.storage.type=s3)` |
| `config/StorageAutoConfiguration.java` | MinioClient + S3Client + S3Presigner beans, conditional per storage type |

**`cia-notifications` module — notification abstraction:**

| File | Description |
| --- | --- |
| `model/NotificationChannel.java` | Enum: EMAIL, SMS |
| `model/NotificationRequest.java` | recipient, subject, body, channel, tenantId |
| `model/NotificationResult.java` | success, providerId, errorMessage |
| `NotificationService.java` | Interface with `send()` and default `supports(channel)` |
| `impl/EmailNotificationService.java` | JavaMailSender SMTP adapter; conditional on `cia.notifications.email.enabled` |
| `impl/SmsNotificationService.java` | Stub logging adapter (Termii/Infobip/Twilio TBD) |
| `impl/CompositeNotificationService.java` | `@Primary` router — delegates to matching channel service |
| `config/NotificationsAutoConfiguration.java` | `JavaMailSender` bean from `spring.mail.*` properties |

**`cia-integrations` module — external provider stubs:**

KYC: `IndividualKycRequest`, `CorporateKycRequest`, `DirectorKycRequest`, `KycResult`, `KycVerificationService` (interface), `MockKycService` (`@Profile("dev | test")`), `DojahKycService` (stub, `cia.kyc.provider=dojah`), `PremblyKycService` (stub, `cia.kyc.provider=prembly`).

NAICOM: `NaicomUploadRequest`, `NaicomUploadResult`, `NaicomService` (interface), `StubNaicomService` (default, `cia.naicom.mode=stub`), `NaicomRestService` (live stub — pending credentials).

NIID: `NiidUploadRequest`, `NiidUploadResult`, `NiidService` (interface), `StubNiidService` (default), `NiidRestService` (live stub — pending credentials).

**`cia-workflow` module — Temporal workflow definitions:**

| File | Description |
| --- | --- |
| `config/TemporalConfig.java` | `WorkflowServiceStubs`, `WorkflowClient`, `WorkerFactory` beans |
| `TemporalQueues.java` | Constants: approval-queue, naicom-upload-queue, niid-upload-queue, notification-queue, webhook-dispatch-queue |
| `approval/ApprovalWorkflow.java` | `@WorkflowInterface`; `@WorkflowMethod runApproval`, `@SignalMethod approve/reject`, `@QueryMethod getStatus` |
| `approval/ApprovalRequest.java` | entityType, entityId, tenantId, initiatedBy, amount, currency |
| `approval/ApprovalStatus.java` | Enum: PENDING, APPROVED, REJECTED |
| `approval/ApprovalActivity.java` | `@ActivityInterface`; `notifyApprovers`, `finaliseApproval` |
| `naicom/NaicomUploadWorkflow.java` | `@WorkflowInterface`; `uploadPolicy(policyId, tenantId)` |
| `naicom/NaicomUploadActivity.java` | `fetchPolicyPayload`, `uploadToNaicom`, `updatePolicyCertificate` |
| `webhook/WebhookDispatchWorkflow.java` | `@WorkflowInterface`; `dispatch(WebhookDispatchRequest)` |
| `webhook/WebhookDispatchRequest.java` | webhookRegistrationId, tenantId, eventType, payloadJson, timestamp |
| `webhook/WebhookDispatchActivity.java` | `send(WebhookDispatchRequest) → WebhookDeliveryResult` |
| `webhook/WebhookDeliveryResult.java` | success, httpStatus, responseBody, errorMessage |

**`cia-partner-api` module — Insurtech Open API platform:**

| File | Description |
| --- | --- |
| `config/PartnerSecurityConfig.java` | `@Order(1)` SecurityFilterChain scoped to `/partner/**`; OAuth2 JWT resource server |
| `config/OpenApiConfig.java` | Springdoc `OpenAPI` bean (bearer + OAuth2 CC schemes) + `GroupedOpenApi` for `/partner/v1/**` |
| `config/RateLimitConfig.java` | Documents Bucket4j Redis rate-limit config (tuned via application.yml) |
| `app/PartnerApp.java` | `@Entity partner_apps`; clientId, appName, contactEmail, tenantId, active, PartnerPlan |
| `app/PartnerPlan.java` | Enum: SANDBOX, STARTER, GROWTH, ENTERPRISE |
| `app/PartnerAppRepository.java` | JPA repository; `findByClientId` |
| `webhook/WebhookRegistration.java` | `@Entity webhook_registrations`; partnerAppId, targetUrl, secret, eventTypes, active |
| `webhook/WebhookRegistrationRepository.java` | JPA repository; `findByPartnerAppIdAndActiveTrue` |
| `webhook/WebhookDispatchActivityImpl.java` | Temporal activity impl; HMAC-SHA256 signed HTTP POST delivery |
| `controller/PartnerProductController.java` | `GET /partner/v1/products`; placeholder with full Springdoc `@Operation` / `@ApiResponse` annotations |

**`cia-api` module — main application:**

| File | Description |
| --- | --- |
| `CiaApplication.java` | `@SpringBootApplication(scanBasePackages="com.nubeero.cia")` |
| `resources/application.yml` | Full application config: datasource, JPA multi-tenancy, Flyway, Keycloak JWT, mail, Redis, Temporal, storage, NAICOM/NIID/KYC stubs, partner API, Springdoc, Bucket4j, logging |
| `resources/application-dev.yml` | Dev overrides: SQL logging, DEBUG levels, all stubs enabled |
| `resources/db/migration/V1__create_public_schema.sql` | `tenants` table (schema registry) in public schema |
| `resources/db/migration/V2__create_tenant_schema_template.sql` | `template_` schema with `audit_log`, `webhook_registrations`, `partner_apps` tables |

**`docker-compose.yml` — local dev environment:**

Services: PostgreSQL 16, Keycloak 24.0, Temporal 1.25.0 (auto-setup), Temporal UI 2.26.2, MinIO (latest), Redis 7 (alpine). `cia-api` service commented out (uncomment when ready). Volumes: `postgres_data`, `minio_data`.

**OpenAPI endpoints added this session:**

| Method | Path                 | Module          | Description                                       |
| ------ | -------------------- | --------------- | ------------------------------------------------- |
| GET    | /partner/v1/products | cia-partner-api | List insurance products available to partner      |

**Partner API authentication:** OAuth2 Client Credentials flow. Token URL: `{KEYCLOAK_URL}/realms/cia/protocol/openid-connect/token`. Swagger UI available at `/partner/docs`. OpenAPI spec at `/partner/v3/api-docs`.

**Next session — build order:**

1. `cia-setup` module — Module 1: Setup & Administration (35 features): products, classes of business, approval groups, master data, partner app management.
2. `cia-customer` module — Module 7: Customer Onboarding & KYC (10 features).
3. `cia-quotation` module — Module 2: Quotation (5 features).
4. Continue in PRD build order: Policy → Finance → Endorsements → Claims → Reinsurance.

---

## 2026-04-20 (continued)

### Session 3 — cia-setup Module: Full REST API Layer

**Module completed:** `cia-setup` — Module 1 (Setup & Administration). All 26 controllers written covering all 35 features.

**Flyway migration:**

`V3__create_setup_tables.sql` — 30 tables across all setup domains.

**Entities written (previously):** `CompanySettings`, `PasswordPolicy`, `Bank`, `Currency`, `AccessGroup`, `AccessGroupPermission`, `ApprovalGroup`, `ApprovalGroupLevel`, `ClassOfBusiness`, `Product`, `ProductSection`, `CommissionSetup`, `PolicySpecification`, `PolicyNumberFormat`, `ClaimDocumentRequirement`, `ClaimNotificationTimeline`, `SurveyThreshold`, `NatureOfLoss`, `CauseOfLoss`, `ClaimReserveCategory`, `Sbu`, `Branch`, `Broker`, `RelationshipManager`, `Surveyor`, `InsuranceCompany`, `ReinsuranceCompany`, `VehicleMake`, `VehicleModel`, `VehicleType`.

**REST controllers — 26 endpoints:**

| Controller | Path | Notes |
| --- | --- | --- |
| `CompanySettingsController` | `GET/PUT /api/v1/setup/company-settings` | Singleton upsert |
| `BankController` | `CRUD /api/v1/setup/banks` | |
| `CurrencyController` | `CRUD /api/v1/setup/currencies` | |
| `AccessGroupController` | `CRUD /api/v1/setup/access-groups` | Nested permissions list |
| `ApprovalGroupController` | `CRUD /api/v1/setup/approval-groups` + `GET /by-entity-type/{entityType}` | Nested levels |
| `ClassOfBusinessController` | `CRUD /api/v1/setup/classes-of-business` | |
| `ProductController` | `CRUD /api/v1/setup/products` | Nested sections |
| `NatureOfLossController` | `CRUD /api/v1/setup/nature-of-loss` | |
| `CauseOfLossController` | `CRUD /api/v1/setup/cause-of-loss` + `GET /by-nature/{natureOfLossId}` | |
| `ClaimReserveCategoryController` | `CRUD /api/v1/setup/claim-reserve-categories` | |
| `SbuController` | `CRUD /api/v1/setup/sbus` | |
| `BranchController` | `CRUD /api/v1/setup/branches` | FK: Sbu |
| `BrokerController` | `CRUD /api/v1/setup/brokers` | |
| `RelationshipManagerController` | `CRUD /api/v1/setup/relationship-managers` + `GET /by-branch/{branchId}` | FK: Branch |
| `SurveyorController` | `CRUD /api/v1/setup/surveyors` | SurveyorType enum |
| `InsuranceCompanyController` | `CRUD /api/v1/setup/insurance-companies` | |
| `ReinsuranceCompanyController` | `CRUD /api/v1/setup/reinsurance-companies` | |
| `VehicleTypeController` | `CRUD /api/v1/setup/vehicle-types` | |
| `VehicleMakeController` | `CRUD /api/v1/setup/vehicle-makes` | |
| `VehicleModelController` | `CRUD /api/v1/setup/vehicle-makes/{makeId}/models` | Nested sub-resource |
| `CommissionSetupController` | `CRUD /api/v1/setup/products/{productId}/commission-setups` | |
| `PolicySpecificationController` | `GET/PUT /api/v1/setup/products/{productId}/policy-specification` | Singleton upsert |
| `PolicyNumberFormatController` | `GET/PUT /api/v1/setup/products/{productId}/policy-number-format` | Singleton upsert; `generateNext()` used by policy module |
| `ClaimDocumentRequirementController` | `CRUD /api/v1/setup/products/{productId}/claim-document-requirements` | |
| `ClaimNotificationTimelineController` | `GET/PUT /api/v1/setup/products/{productId}/claim-notification-timeline` | Singleton upsert |
| `SurveyThresholdController` | `CRUD /api/v1/setup/products/{productId}/survey-thresholds` | |

**Key design decisions:**

- All controllers use `@PreAuthorize("hasRole('SETUP_VIEW|CREATE|UPDATE|DELETE')")` — Keycloak roles map to `ROLE_SETUP_*` Spring authorities.
- Product-linked singletons (PolicySpec, PolicyNumberFormat, ClaimNotificationTimeline) use PUT for upsert — avoids client-side "does it exist?" checks.
- Sub-resource controllers (VehicleModel under VehicleMake, product-config under Product) enforce parent ownership in service layer — cross-parent access returns 404.
- `PolicyNumberFormatService.generateNext()` uses `@Lock(PESSIMISTIC_WRITE)` to prevent duplicate sequence numbers under concurrent policy approvals.
- `AccessGroupService.softDelete()` cascades through `permissions.clear()` on update; orphanRemoval handles DB cleanup.
- `AuditService.log()` called on every write; catches all exceptions so audit failure never breaks the business operation.

**Next session — build order:**

1. `cia-customer` module — Module 7: Customer Onboarding & KYC (10 features).
2. `cia-quotation` module — Module 2: Quotation (5 features).
3. Continue in PRD build order: Policy → Finance → Endorsements → Claims → Reinsurance.

---

## 2026-04-21 (continued)

### Session 4 — cia-customer, cia-quotation, cia-policy, cia-finance, cia-endorsement, cia-claims

**Modules completed:** cia-customer (24 files), cia-quotation (21 files), cia-policy (21 files), cia-finance (37 files), cia-endorsement (18 files), cia-claims (34 files).

**Flyway migrations added:**

| Migration | Tables |
|---|---|
| `V4__create_customer_tables.sql` | `customers`, `customer_directors`, `customer_documents` |
| `V5__create_quotation_tables.sql` | `quote_counters`, `quotes`, `quote_risks`, `quote_coinsurance_participants` |
| `V6__create_policy_tables.sql` | `policy_counters`, `policies`, `policy_risks`, `policy_coinsurance_participants`, `policy_documents` |
| `V7__create_finance_tables.sql` | `debit_note_counters`, `credit_note_counters`, `receipt_counters`, `payment_counters`, `debit_notes`, `credit_notes`, `receipts`, `payments` |
| `V8__create_endorsement_tables.sql` | `endorsement_counters`, `endorsements`, `endorsement_risks` |
| `V9__create_claims_tables.sql` | `claim_counters`, `claims`, `claim_reserves`, `claim_expenses`, `claim_documents` |

**Key files created — cia-customer:**

| File | Description |
|---|---|
| `Customer.java` | Entity; `CustomerType` (INDIVIDUAL/CORPORATE), `KycStatus`, `IdType` enum fields; soft-delete |
| `CustomerDirector.java` | Corporate director entity; linked to Customer |
| `CustomerDocument.java` | KYC document upload entity |
| `CustomerService.java` | `createIndividual()`, `createCorporate()`, `update()`, `retriggerKyc()`, `blacklist()`, `unblacklist()` |
| `CustomerController.java` | Full CRUD + KYC retrigger + blacklist endpoints |
| `CustomerDocumentService/Controller` | Multipart upload, download, delete |
| DTOs | `IndividualCustomerRequest`, `CorporateCustomerRequest`, `CustomerDirectorRequest`, `CustomerResponse`, `CustomerSummaryResponse`, `CustomerUpdateRequest`, `BlacklistRequest` |

**Key files created — cia-quotation:**

| File | Description |
|---|---|
| `Quote.java` | Entity; `QuoteStatus` (DRAFT/SUBMITTED/APPROVED/REJECTED/CONVERTED/EXPIRED), `BusinessType` |
| `QuoteRisk.java` | Risk line item on a quote |
| `QuoteCoinsuranceParticipant.java` | Coinsurance participant |
| `QuoteService.java` | `create()`, `update()`, `submit()`, `approve()`, `reject()`, `markConverted()` |
| `QuoteController.java` | Full REST surface with `@PreAuthorize` |
| `QuoteNumberService.java` | Gap-free sequential quote numbers; `@Lock(PESSIMISTIC_WRITE)` |

**Key files created — cia-policy:**

| File | Description |
|---|---|
| `Policy.java` | Entity; `PolicyStatus`, `BusinessType`; NAICOM/NIID UID fields; `policyDocumentPath` |
| `PolicyRisk.java` | Risk item; `riskDetails` JSONB |
| `PolicyService.java` | `bindFromQuote()`, `create()`, `submit()`, `approve()`, `reject()`, `cancel()`, `reinstate()`, `triggerNaicomUpload()` |
| `PolicyController.java` | Full REST; `@PreAuthorize` per action |
| `PolicyNumberService.java` | Gap-free sequential numbers |

Policy approval publishes `PolicyApprovedEvent` with 14 fields (including RI allocation fields added later).

**Key files created — cia-finance:**

| File | Description |
|---|---|
| `DebitNote.java` / `CreditNote.java` | Finance note entities; linked to source entity type + ID |
| `Receipt.java` / `Payment.java` | Settlement entities |
| `FinanceService.java` | Creates debit/credit notes; receipt + payment approval workflows |
| Event listeners | `PolicyApprovedEventListener` → debit note; `EndorsementApprovedEventListener` → debit/credit note; `ClaimApprovedEventListener` → credit note; `FacPremiumCededEventListener` → credit note |

**Key files created — cia-endorsement:**

| File | Description |
|---|---|
| `Endorsement.java` | Entity; `EndorsementStatus`, `EndorsementType` (ADDITIONAL_PREMIUM/RETURN_PREMIUM/NON_PREMIUM_BEARING) |
| `EndorsementRisk.java` | Risk snapshot on endorsement |
| `EndorsementService.java` | `create()`, `submitForApproval()`, `approve()`, `reject()`, `cancel()`; pro-rata premium calculation |
| `EndorsementNumberService.java` | Gap-free sequential numbers |

**Key files created — cia-claims:**

| File | Description |
|---|---|
| `Claim.java` | Entity; `ClaimStatus` (REGISTERED/UNDER_INVESTIGATION/RESERVED/PENDING_APPROVAL/APPROVED/SETTLED/REJECTED/WITHDRAWN) |
| `ClaimReserve.java` / `ClaimExpense.java` / `ClaimDocument.java` | Sub-entities |
| `ClaimService.java` | Full lifecycle: `register()`, `assignSurveyor()`, `setReserve()`, `submitForApproval()`, `approve()`, `reject()`, `withdraw()`, `markSettled()` |
| `ClaimController.java` | Full REST surface |
| `ClaimNumberService.java` | Gap-free sequential numbers |

**Common events published from this session (in cia-common):**

| Event | Published by | Consumed by |
|---|---|---|
| `PolicyApprovedEvent` | `PolicyService.approve()` | cia-finance (debit note), cia-reinsurance (auto-allocation), cia-partner-api (webhook) |
| `EndorsementApprovedEvent` | `EndorsementService.approve()` | cia-finance (debit/credit note), cia-partner-api (webhook) |
| `ClaimApprovedEvent` | `ClaimService.approve()` | cia-finance (credit note), cia-partner-api (webhook) |

---

## 2026-04-21 (continued)

### Session 5 — cia-reinsurance Module

**Module completed:** `cia-reinsurance` — Module 6 (Reinsurance). 37 Java files.

**Flyway migration:** `V10__create_reinsurance_tables.sql`

Tables: `ri_counters`, `ri_fac_counters`, `ri_treaties`, `ri_treaty_participants`, `ri_allocations`, `ri_allocation_lines`, `ri_fac_covers`.

**Enums:** `TreatyType` (SURPLUS, QUOTA_SHARE, XOL), `TreatyStatus` (DRAFT, ACTIVE, EXPIRED, CANCELLED), `AllocationStatus` (DRAFT, CONFIRMED, CANCELLED), `FacCoverStatus` (PENDING, CONFIRMED, CANCELLED).

**Key files:**

| File | Description |
|---|---|
| `RiTreaty.java` | Treaty entity; retentionLimit, surplusCapacity, quotaSharePercent, xolLimit per treaty type |
| `RiTreatyParticipant.java` | Reinsurer share on a treaty |
| `RiAllocation.java` / `RiAllocationLine.java` | Per-policy RI allocation with retained/ceded split |
| `RiFacCover.java` | Outward facultative cover |
| `AllocationService.java` | SURPLUS/QUOTA_SHARE/XOL strategies; `autoAllocate()` wrapped in try/catch — RI failure never blocks policy approval |
| `PolicyApprovedEventListener.java` | Listens for `PolicyApprovedEvent`; triggers `autoAllocate()` |
| `FacCoverService.java` | `confirm()` publishes `FacPremiumCededEvent` |
| `RiNumberService.java` | Sequential `RIA-YYYY-NNNNNN` and `FAC-YYYY-NNNNNN` format; `REQUIRES_NEW` transaction |
| `RiTreatyController.java` | `GET/POST/PUT/DELETE /api/v1/ri/treaties` |
| `RiAllocationController.java` | `GET/POST /api/v1/ri/allocations` |
| `RiFacCoverController.java` | `GET/POST/PUT /api/v1/ri/fac-covers` |

**New events added to cia-common:**

| Event | Fields |
|---|---|
| `FacPremiumCededEvent` | facCoverId, facReference, policyId, policyNumber, reinsuranceCompanyId, reinsuranceCompanyName, premiumCeded, commissionAmount, netPremiumCeded, currencyCode |

**Cross-module changes:**

- `PolicyApprovedEvent` enriched with 4 new RI fields: `productId`, `classOfBusinessId`, `totalSumInsured`, `policyStartDate`
- `ReinsuranceCompanyRepository` — added `findByIdAndDeletedAtIsNull(UUID id)` (was missing)
- `cia-reinsurance/pom.xml` — added `cia-policy` and `cia-setup` dependencies

---

## 2026-04-21 (continued)

### Session 6 — cia-documents Module

**Module completed:** `cia-documents` — PDF generation module. 13 Java files + 3 HTML templates.

**Flyway migration:** `V11__add_document_tables.sql`

```sql
CREATE TABLE document_templates (id, template_type, product_id, class_of_business_id, storage_path, description, active, created_at, ...);
ALTER TABLE endorsements ADD COLUMN document_path VARCHAR(500);
ALTER TABLE claims ADD COLUMN dv_document_path VARCHAR(500);
```

**Key files:**

| File | Description |
|---|---|
| `DocumentGenerationService.java` | Interface; all methods return `null` on failure — approval flow is never blocked |
| `DocumentGenerationServiceImpl.java` | Resolves template (DB → MinIO → classpath fallback); renders via Thymeleaf; converts to PDF via PDFBox; stores via DocumentStorageService |
| `HtmlToPdfConverter.java` | Walks JSoup HTML tree; renders h1/h2/h3/p/br/hr/ul/ol/table/b to PDFBox; auto page breaks; word wrapping |
| `DocumentEngineConfig.java` | `@Bean("documentTemplateEngine")` with `StringTemplateResolver` — isolated from main Thymeleaf engine |
| `DocumentTemplateService.java` | CRUD; `upload()` deactivates prior active template for same type+scope |
| `DocumentTemplateController.java` | `POST /api/v1/document-templates` (multipart), GET list/single, DELETE |
| Context records | `PolicyDocumentContext`, `EndorsementDocumentContext`, `ClaimDvContext` |
| Templates | `policy-default.html`, `endorsement-default.html`, `claim-dv-default.html` (Thymeleaf inline `[[${var}]]`) |

**Cross-module changes:**

| Module | Change |
|---|---|
| `cia-policy / PolicyService.approve()` | Added `DocumentGenerationService` injection; generates + stores policy PDF on approval; stores path in `policy_document_path` |
| `cia-endorsement / EndorsementService.approve()` | Added PDF generation; stores path in `document_path` |
| `cia-claims / ClaimService.approve()` | Added DV PDF generation; stores path in `dv_document_path` |
| `cia-endorsement / Endorsement.java` | Added `document_path` field |
| `cia-claims / Claim.java` | Added `dv_document_path` field |

**Technical decisions:**

- PDFBox 3.x API: `Standard14Fonts.FontName.HELVETICA` (not deprecated PDFBox 2.x constants)
- `getStringWidth()` returns units/1000 — multiply by fontSize for actual points
- `sanitise()` strips non-WinAnsi characters (PDFBox chokes on them)
- jsoup `1.17.2` added explicitly — Spring Boot BOM does not manage it directly

---

## 2026-04-22

### Session 7 — cia-partner-api Module (Full Implementation)

**Module completed:** `cia-partner-api` — Module 9 (Partner Open API). Upgraded from 10 skeletal files to 27 files. Covers all 15 endpoints in spec.

**Flyway migration:** `V12__create_partner_tables.sql`

Tables: `partner_apps`, `webhook_registrations`, `webhook_delivery_logs`.

**New files:**

| File | Description |
|---|---|
| `app/PartnerApp.java` | Enriched with `scopes`, `rateLimitRpm`, `allowedIps`, `plan`; `@Setter` added |
| `app/PartnerAppService.java` | CRUD; `create()` checks duplicate `clientId`; `toggleActive()`; `softDelete()` |
| `app/dto/CreatePartnerAppRequest.java` | Validation: `@Email`, `@NotBlank`, `@Positive` |
| `webhook/WebhookRegistration.java` | `partnerAppId` corrected to `UUID`; `@Setter` added |
| `webhook/WebhookDeliveryLog.java` | Audit entity; `webhookRegistrationId`, `eventType`, `payloadJson`, `success`, `httpStatus`, `responseBody`, `errorMessage`, `attempt` |
| `webhook/WebhookDeliveryLogRepository.java` | JPA repository |
| `webhook/WebhookEvent.java` | Enum: 10 event types; `eventName()` converts `CLAIM_APPROVED` → `claim.approved` |
| `webhook/WebhookService.java` | `register()`, `list()`, `findOrThrow()`, `delete()`; `publish()` fans out to all active matching registrations via Temporal |
| `webhook/WebhookRegistrationRepository.java` | `findAllByPartnerAppIdAndDeletedAtIsNull()`, `findByIdAndDeletedAtIsNull()`, `findAllByActiveTrue()` |
| `webhook/WebhookEventListener.java` | Listens for `PolicyApprovedEvent`, `EndorsementApprovedEvent`, `ClaimApprovedEvent`, `ClaimSettledEvent`; synchronous (not `@Async`) so `TenantContext` ThreadLocal is still set |
| `webhook/WebhookDispatchActivityImpl.java` | Upgraded: now logs every delivery to `webhook_delivery_logs` |
| `webhook/WebhookDispatchWorkflowImpl.java` | Temporal workflow impl; 4-attempt retry, exponential backoff (30s → 10min) |
| `webhook/dto/RegisterWebhookRequest.java` | `targetUrl`, `secret` (min 16 chars), `eventTypes` |
| `config/PartnerScopeFilter.java` | `OncePerRequestFilter`; enforces OAuth2 scope per endpoint path+method after JWT validation |
| `config/PartnerSecurityConfig.java` | Added `PartnerScopeFilter` registration after `TenantContextFilter`; removed unused `@Value` |
| `config/WebhookWorkerConfig.java` | `@PostConstruct` registers `WebhookDispatchWorkflowImpl` + activity on `WEBHOOK_QUEUE` |
| `controller/PartnerProductController.java` | `GET /partner/v1/products`, `GET /partner/v1/products/{id}`, `GET /partner/v1/products/{id}/classes` |
| `controller/PartnerQuoteController.java` | `POST /partner/v1/quotes`, `GET /partner/v1/quotes/{id}` |
| `controller/PartnerCustomerController.java` | `POST /partner/v1/customers/individual`, `POST /partner/v1/customers/corporate`, `GET /partner/v1/customers/{id}` |
| `controller/PartnerPolicyController.java` | `POST /partner/v1/policies` (bind from quote), `GET /partner/v1/policies/{id}`, `GET /partner/v1/policies/{id}/document` |
| `controller/PartnerClaimController.java` | `POST /partner/v1/policies/{policyId}/claims`, `GET /partner/v1/claims/{id}` |
| `controller/PartnerWebhookController.java` | `POST/GET /partner/v1/webhooks`, `DELETE /partner/v1/webhooks/{id}`; resolves `partnerAppId` from JWT `partner_app_id` claim |
| `controller/PartnerAppController.java` | Internal admin: `GET/POST /api/v1/partner-apps`, `PATCH /{id}/activate`, `DELETE /{id}`; `@PreAuthorize("hasAuthority('setup:*')")` |
| `docs/postman_environment.json` | Postman environment with `baseUrl`, `keycloakUrl`, `tenantRealm`, `clientId`, `clientSecret`, `accessToken` |
| `docs/developer-guide.md` | Full integration guide: auth, scopes, quick start, webhook verification, rate limits, error format, sandbox |

**Cross-module changes:**

| Module | File | Change |
|---|---|---|
| `cia-common` | `ClaimSettledEvent.java` | New event: `claimId`, `claimNumber`, `policyId`, `policyNumber`, `customerId`, `customerName`, `settledAt` |
| `cia-claims` | `ClaimService.markSettled()` | Now publishes `ClaimSettledEvent` |
| `cia-api` | `config/TemporalWorkerStarter.java` | New: `@EventListener(ApplicationReadyEvent)` starts `WorkerFactory` after all module workers are registered via `@PostConstruct` — fixes project-wide gap |
| `cia-partner-api` | `pom.xml` | Added `cia-auth` and `cia-setup` as explicit dependencies |

**Design decisions:**

- Partner API is a **pure facade** — zero business logic; all rules enforced by existing business module services.
- Webhook listeners are **synchronous** (not `@Async`) so `TenantContext` ThreadLocal is available; actual HTTP delivery is async inside Temporal.
- `TemporalWorkerStarter` fires on `ApplicationReadyEvent` — guarantees all `@PostConstruct` worker registrations across all modules complete before `factory.start()`.
- `partnerAppId` resolved from JWT `partner_app_id` custom claim (set at Keycloak client creation time).

**Postman collection regeneration required** — new endpoints added. Run: `mvn package -pl cia-partner-api` (openapi-generator-maven-plugin executes at package phase).

**Open questions:** None — both items from Session 7 closed in Session 8.

---

### Session 8 — cia-partner-api: @Schema Annotations + Document Streaming

**Items closed from Session 7:**

1. **`@Schema` annotations on all partner API DTOs** — CLOSED.
2. **Document streaming in `GET /partner/v1/policies/{id}/document`** — CLOSED.

**New partner DTO layer introduced** (all in `cia-partner-api/src/.../partner/controller/dto/`):

| File | Description |
|---|---|
| `PartnerClaimResponse.java` | Partner-safe projection of `Claim` entity; omits internal workflow, surveyor, and withdrawal fields; includes static `from(Claim)` factory |
| `PartnerWebhookResponse.java` | Partner-safe projection of `WebhookRegistration`; omits `secret`; splits comma-delimited `eventTypes` into `List<String>` |
| `PartnerPolicyResponse.java` | Partner projection of `PolicyResponse`; omits internal workflow ID and user audit fields; includes `@Schema` on class + every field |
| `PartnerQuoteResponse.java` | Partner projection of `QuoteResponse`; `@Schema` on class + every field |
| `PartnerCustomerResponse.java` | Partner projection of `CustomerResponse`; omits `kycProviderRef`, `alternatePhone`, `directors`, `documents`; `@Schema` on class + every field |
| `PartnerProductResponse.java` | Partner projection of `ProductResponse`; omits `sections`; `@Schema` on class + every field |
| `PartnerClassOfBusinessResponse.java` | Partner projection of `ClassOfBusinessResponse`; `@Schema` on class + every field |

**Architectural decision:** `@Schema` annotations live only in `cia-partner-api` (where springdoc is a dependency). Business modules (`cia-policy`, `cia-quotation`, `cia-customer`, `cia-setup`) do not depend on swagger-annotations — documentation concerns belong in the API surface module, not domain modules.

**Updated controllers (all 6 partner controllers now have full `@ApiResponse` annotations):**

| Controller | Change |
|---|---|
| `PartnerProductController.java` | Switched to `PartnerProductResponse`/`PartnerClassOfBusinessResponse`; added `@ApiResponse` for all response codes |
| `PartnerQuoteController.java` | Switched to `PartnerQuoteResponse`; added `@ApiResponse` for all response codes |
| `PartnerCustomerController.java` | Switched to `PartnerCustomerResponse`; added `@ApiResponse` for all response codes |
| `PartnerPolicyController.java` | Switched to `PartnerPolicyResponse`; wired `DocumentStorageService` for real PDF streaming; added `@ApiResponse` for all response codes |
| `PartnerClaimController.java` | Switched from `Claim` entity to `PartnerClaimResponse`; added `@ApiResponse` for all response codes |
| `PartnerWebhookController.java` | Switched from `WebhookRegistration` entity to `PartnerWebhookResponse`; added `@ApiResponse` for all response codes |

**pom.xml changes:**

- `cia-partner-api/pom.xml` — Added `cia-storage` as explicit dependency (required for `DocumentStorageService` injection)

**Document streaming implementation (`PartnerPolicyController.downloadDocument`):**

- Reads `TenantContext.getTenantId()` for storage tenant isolation
- Calls `documentStorageService.download(tenantId, policy.getPolicyDocumentPath())`
- Returns `InputStreamResource` with `Content-Type: application/pdf` and `Content-Disposition: attachment; filename="policy-{policyNumber}.pdf"`
- Returns 404 if `policyDocumentPath` is null (policy not yet approved)

**Postman collection regeneration required** — partner DTO types changed. Run: `mvn package -pl cia-partner-api`

**Open questions:** None.

---

### Session 9 — Backend Verification, GitHub Repo, CI Pipeline, Docusaurus Docs Site

**Primary deliverables:**

1. Backend compiled and full test suite run (`mvn verify`)
2. Private GitHub repo created and pushed (`RazorMVP/CoreInsurance`)
3. GitHub Actions CI pipeline covering all four testing layers
4. Docusaurus documentation site on GitHub Pages

---

**Compilation fixes applied:**

| File | Problem | Fix |
|---|---|---|
| `cia-backend/pom.xml` | `temporal-spring-boot-starter-alpha:1.25.0` does not exist in Maven Central | Renamed to `temporal-spring-boot-starter` (artifact renamed from v1.24+) |
| `cia-backend/cia-workflow/pom.xml` | Same artifact rename + missing `cia-integrations` dependency (required by `NaicomUploadActivity`/`NiidUploadActivity`) | Added both fixes |
| `cia-endorsement/EndorsementService.java` | `workflow::startApproval` (no such method) + `new ApprovalRequest(…)` positional constructor (no-arg Lombok `@Builder`) | Changed to `workflow::runApproval` + builder pattern |
| `cia-claims/ClaimService.java` | Same pattern as EndorsementService | Same fix |
| `cia-documents/DocumentGenerationServiceImpl.java` | `Map.of()` called with 12–13 entries (limit is 10) | Switched to `Map.ofEntries(entry(…), …)` |
| `cia-finance/CreditNoteController.java` | `BaseEntity.getCreatedAt()` returns `Instant`; `CreditNoteResponse` expects `OffsetDateTime` | Added `ZoneOffset.UTC` conversion |

**Runtime environment:** Java 21 required (Lombok 1.18.36 is incompatible with Java 25 due to removed `com.sun.tools.javac.code.TypeTag` internals).

---

**GitHub repository:**

- Remote: `https://github.com/RazorMVP/CoreInsurance` (private)
- All backend modules, frontend, docs-site, CI workflows pushed to `main`

---

**CI pipeline (`.github/workflows/ci.yml`):**

| Job | Runner | Status |
|---|---|---|
| `backend` | `ubuntu-latest` / Java 21 / Maven | Active — runs `mvn verify` with Testcontainers (Docker socket available on ubuntu-latest) |
| `frontend` | `ubuntu-latest` / Node 20 | Stubbed (`if: false`) — Vitest runs cleanly; enables when frontend reaches feature parity |
| `docs` | `ubuntu-latest` / Node 20 | Stubbed (`if: false`) — enables when docs build is fully validated |

**Docs deploy pipeline (`.github/workflows/docs-deploy.yml`):** GitHub Pages deployment from `docs-site/build/`; jobs stubbed with `if: false` until docs build is stable.

---

**OpenAPI source artifact (`cia-backend/cia-partner-api/docs/openapi.json`):**

- Hand-crafted OpenAPI 3.1.0 spec checked into the repo as a build-time source artifact
- Covers all 15 partner API endpoints across 7 resource groups
- Drives Postman collection generation at build time via `openapi-generator-maven-plugin`
- Springdoc validates runtime output against this spec

---

**Docusaurus site (`docs-site/`):**

- Docusaurus 3.10 + React 19; targets `https://razormvp.github.io/CoreInsurance/`
- **Dropped `docusaurus-theme-openapi-docs`** — React 19 SSR incompatibility (`useTabsContext()` outside `Tabs.Provider` during static generation); replaced with sidebar links to live Swagger UI at `/partner/docs`
- **Webpack `webpackbar` v7 override** — `@docusaurus/bundler` nested `webpackbar@6.x` passed invalid props to webpack's `ProgressPlugin`; forced to v7 via npm overrides (later removed when openapi plugin was dropped)

**Internal developer documentation written:**

| Doc | Path |
|---|---|
| Architecture Overview | `docs/architecture/overview.md` |
| Module Inventory | `docs/architecture/modules.md` |
| Multi-Tenancy | `docs/architecture/multi-tenancy.md` |
| Security Architecture | `docs/architecture/security.md` |
| Workflow Architecture | `docs/architecture/workflows.md` |
| Integrations | `docs/architecture/integrations.md` |
| Local Setup Guide | `docs/guides/local-setup.md` |
| Tenant Provisioning | `docs/guides/tenant-provisioning.md` |
| Environment Variables | `docs/guides/environment-variables.md` |
| Database Migrations | `docs/guides/database-migrations.md` |
| Coding Standards | `docs/development/coding-standards.md` |
| Testing Guide | `docs/development/testing.md` |
| Adding a Module | `docs/development/adding-a-module.md` |

**Partner API documentation written:**

| Doc | Path |
|---|---|
| Partner API Overview | `docs/partner/overview.md` |
| Authentication Guide | `docs/partner/authentication.md` (cURL, TypeScript, Python, Java examples) |
| Webhook Integration | `docs/partner/webhooks.md` (TypeScript + Python signature verification) |
| Rate Limiting | `docs/partner/rate-limiting.md` |
| Sandbox Environment | `docs/partner/sandbox.md` |

**Open questions:** None from this session.

---

## 2026-04-23

### Session — Audit & Compliance Module (Module 10) + Build Fixes + Docs Update

**New Maven module: `cia-audit`**

| File | Description |
|---|---|
| `cia-audit/pom.xml` | New module; deps: cia-common, cia-notifications, commons-csv:1.10.0 |
| `V16__create_audit_module_tables.sql` | Adds `approval_amount` column to `audit_log`; creates `login_audit_log`, `audit_alert_config` (singleton row seeded), `audit_alert` tables |

**`cia-common` extensions:**

| File | Change |
|---|---|
| `AuditLog.java` | Added `approval_amount NUMERIC(19,2)` field |
| `AuditLogRepository.java` | Added `JpaSpecificationExecutor<AuditLog>`, `countByUserIdAndActionAndTimestampAfter()`, JPQL `findUserActivitySummary()` with `UserActivityProjection` inner interface |
| `AuditService.java` | Added `ApplicationEventPublisher`; refactored to publish `AuditLogCreatedEvent` after every save; added `logWithAmount()` overload |
| `AuditLogCreatedEvent.java` | New Spring `ApplicationEvent` wrapping `AuditLog` |

**`cia-audit` entities / repos / DTOs / services / controllers — all new:**

| Layer | Files |
|---|---|
| Entities | `AlertType`, `AuditAlertConfig`, `AuditAlert`, `LoginEventType`, `LoginAuditLog` |
| Repositories | `AuditAlertConfigRepository`, `AuditAlertRepository`, `LoginAuditLogRepository` |
| DTOs | `AuditLogFilter`, `AuditLogResponse`, `LoginAuditLogResponse`, `AuditAlertResponse`, `AuditAlertConfigRequest/Response`, `UserActivitySummary` |
| Services | `AuditQueryService`, `LoginAuditService`, `AuditAlertConfigService`, `AuditAlertService`, `AlertDetectionService`, `AuditExportService`, `AuditReportService` |
| Controllers | `AuditLogController`, `LoginAuditController`, `AuditAlertController`, `AuditAlertConfigController`, `AuditExportController`, `AuditReportController` |

**API endpoints added (15):**

| Endpoint | Notes |
|---|---|
| `GET /api/v1/audit/logs` | Filterable audit log with pagination |
| `POST /api/v1/auth/session/start` | Login event recording (public — requires valid JWT) |
| `POST /api/v1/auth/session/end` | Logout event recording |
| `POST /api/v1/auth/login/failed` | Failed login recording (**public endpoint** — no JWT) |
| `GET /api/v1/audit/login-logs` | Login log viewer |
| `GET /api/v1/audit/alerts` | List alerts (with `?unacknowledgedOnly=true`) |
| `POST /api/v1/audit/alerts/{id}/acknowledge` | Acknowledge an alert |
| `GET /api/v1/setup/audit-config` | Read alert config (AUDIT_VIEW + SETUP_UPDATE) |
| `PUT /api/v1/setup/audit-config` | Update alert config (SETUP_UPDATE only) |
| `GET /api/v1/audit/export` | CSV export of audit log (text/csv, streaming) |
| `GET /api/v1/audit/reports/actions-by-user` | Report 1 |
| `GET /api/v1/audit/reports/actions-by-module` | Report 2 |
| `GET /api/v1/audit/reports/approvals` | Report 3 |
| `GET /api/v1/audit/reports/data-changes` | Report 4 |
| `GET /api/v1/audit/reports/login-security` | Report 5 |
| `GET /api/v1/audit/reports/user-activity` | Report 6 |

**Other changes:**

| File | Change |
|---|---|
| `CiaApplication.java` | Added `@EnableAsync` for `AlertDetectionService` |
| `SecurityConfig.java` | Added `AntPathRequestMatcher("/api/v1/auth/login/failed")` to permit list |
| `cia-backend/pom.xml` | Upgraded Lombok from `1.18.36` → `1.18.46` (JDK 25 compatibility fix) |

**Documentation updated:**

| Doc | What changed |
|---|---|
| `CLAUDE.md` | Module Summary: added row 10; Backend Module Inventory: added `cia-audit`; Dependency Graph: added `cia-audit` entry |
| `SKILL.md` | Frontmatter: 9 → 10 modules, 143 → 158 features; added Module 10 section; added 4 new entities; added 8 new development conventions |
| `docs-site/docs/architecture/modules.md` | Added `cia-audit` to inventory and cross-module dependency table |
| `docs-site/docs/architecture/overview.md` | Module count 18 → 19; added row 10 to Business Modules table |
| `docs-site/docs/architecture/security.md` | Replaced placeholder stub with full security documentation |
| `docs-site/docs/guides/local-setup.md` | Updated Lombok troubleshooting note for JDK 24+ |

**Decisions made:**

- `cia-audit` depends only on `cia-common` + `cia-notifications` — zero dependency on business modules.
- `audit_alert_config` is a singleton per tenant (one row, seeded by migration); `loadConfig()` always reads `findFirstByOrderByCreatedAtAsc()`.
- Off-hours login detection is handled directly in `LoginAuditController.loginFailed()` via `checkFailedLogins()`, not via `AuditLogCreatedEvent` (logins are not in `AuditLog`).
- `AuditAction.LOGIN` does not exist — login events use `LoginEventType` in a separate table.
- System Auditor role (`AUDIT_VIEW`) is strictly read-only; only System Admin (`SETUP_UPDATE`) can modify alert config.

**Open questions:** None.

---

## 2026-04-24

### Session 4 — Frontend Monorepo Scaffold

**Files created:**

| File | Description |
|---|---|
| `cia-frontend/package.json` | pnpm workspace root; Turborepo + TypeScript devDeps |
| `cia-frontend/pnpm-workspace.yaml` | Declares `apps/*` and `packages/*` workspaces |
| `cia-frontend/turbo.json` | Pipeline: build, dev, lint, typecheck with `^build` dependency |
| `cia-frontend/tsconfig.base.json` | Shared TS config: ES2022, bundler moduleResolution, strict |
| `cia-frontend/.impeccable.md` | Design context: users, brand, aesthetic, font selection, principles |
| `packages/ui/src/tokens.css` | Full OKLCH design token file: Nubeero teal/charcoal palette, shadcn semantic tokens, status tokens, dark mode |
| `packages/ui/tailwind.config.ts` | Shared Tailwind config mapping CSS vars to Tailwind utilities |
| `packages/ui/src/components/button.tsx` | shadcn Button with CIA brand variants |
| `packages/ui/src/components/badge.tsx` | Status Badge: active/pending/rejected/draft/cancelled variants |
| `packages/api-client/src/client.ts` | `createApiClient()` + `initApiClient()` + `setTokenGetter()` — env-agnostic |
| `packages/api-client/src/types.ts` | `ApiResponse<T>`, `PageResponse<T>`, `ApiMeta`, `ApiError` |
| `packages/auth/src/keycloak.ts` | Keycloak instance + `configureKeycloak()` + init/refresh helpers |
| `packages/auth/src/AuthProvider.tsx` | React context: user, token, roles, `hasRole()`, `logout()` |
| `apps/back-office/src/app/layout/AppShell.tsx` | Sidebar + Topbar + `<Outlet />` |
| `apps/back-office/src/app/layout/Sidebar.tsx` | Three nav groups; teal active state; user profile + logout |
| `apps/back-office/src/app/layout/Topbar.tsx` | Route-aware page title + notification icon |
| `apps/back-office/src/app/router.tsx` | Lazy-loaded module routes + skeleton fallback |
| `apps/back-office/src/modules/dashboard/DashboardPage.tsx` | Stats grid + recent activity |
| `apps/back-office/src/modules/*/index.tsx` | Stub entry points for 9 business modules |
| `apps/partner/` | Dark-mode portal skeleton; port 5174 |

**Decisions made:**

- pnpm + Turborepo selected; `^build` chain ensures `@cia/ui` builds before apps.
- Two apps: `@cia/back-office` (light, port 5173) and `@cia/partner` (dark, port 5174).
- Three shared packages: `@cia/ui`, `@cia/api-client`, `@cia/auth`.
- OKLCH color tokens stored as full `oklch(L C H)` values (not channels) for devtools readability.
- Fonts: Bricolage Grotesque (headings) + Geist (body) via Google Fonts.
- Icon library: hugeicons v1.1.6 (`@hugeicons/react`).
- Shared packages are Vite env-agnostic; apps call `configureKeycloak()` and `initApiClient()` at startup.
- Figma BackOffice file (fileKey: `Zaiu2K7NvEJ7Cjj6z1xt2D`) currently empty — designs stubbed as modules are built.
- `tsc --noEmit` passes with zero errors on `@cia/back-office`.

**Open questions:**

- Partner portal auth flow: needs OAuth2 Client Credentials (machine-to-machine), not Keycloak human login.
- Figma `get_design_context` requires Figma desktop app open with node selected (desktop plugin mode).

---

### Session 4b — UI Housecleaning (NubSure rebrand + topbar/sidebar enhancements)

**Files modified:**

| File | Change |
|---|---|
| `apps/back-office/index.html` | Title + description updated to "NubSure"; favicon set to `/logo.png` |
| `apps/back-office/public/logo.png` | Nubeero PNG logo copied from `/Users/razormvp/Documents/Nubeero_Images/nubeeroLogo/` |
| `apps/back-office/src/app/layout/AppShell.tsx` | Added `collapsed` state; passes to `Sidebar` and `Topbar`; sidebar `<aside>` uses `width` + `transition` for smooth collapse |
| `apps/back-office/src/app/layout/Sidebar.tsx` | Full rewrite: logo PNG, "NubSure" name, hugeicons for all 10 modules, font 13→15px, collapsible (icon-only at 64px), `title` tooltip on collapsed items |
| `apps/back-office/src/app/layout/Topbar.tsx` | Added hamburger toggle (left), search bar (flex-1, always visible), notification + help icons (right); accepts `collapsed` + `onToggle` props |
| `packages/ui/package.json` & `apps/back-office/package.json` | Added `@hugeicons/core-free-icons@^4.1.1` dependency |

**Decisions made:**

- App name: **NubSure** (replaces CIAGB everywhere in frontend)
- Logo: PNG asset at `/public/logo.png` (28×28px in sidebar)
- Sidebar collapse trigger: **hamburger button in topbar** (best practice — stays visible when sidebar is collapsed)
- Collapsed state: 64px wide, icon-only with native `title` tooltips
- Collapse animation: `width 220ms cubic-bezier(0.16, 1, 0.3, 1)` CSS transition on `<aside>` in AppShell
- hugeicons API: `HugeiconsIcon` renderer from `@hugeicons/react` + icon data from `@hugeicons/core-free-icons`
- Icon mapping: Dashboard→`DashboardSquare01Icon`, Customers→`UserGroupIcon`, Quotation→`NoteEditIcon`, Policies→`Shield01Icon`, Endorsements→`FileEditIcon`, Claims→`AlertCircleIcon`, Finance→`Money01Icon`, Reinsurance→`RepeatIcon`, Setup→`Setting06Icon`, Audit→`Audit01Icon`
- `tsc --noEmit` passes with zero errors after all changes

**Open questions:** None.

---

### Session 4c — UI Polish, Figma Completion & Dev Tooling

**Files modified:**

| File | Change |
|---|---|
| `packages/ui/src/tokens.css` | Added `NairaFallback` @font-face (unicode-range U+20A6 → local Arial); added Noto Sans to Google Fonts import; `NairaFallback` placed first in `--font-display` and `--font-body` stacks |
| `packages/auth/src/AuthProvider.tsx` | Added `DevAuthProvider` — mock context using same `AuthContext`, provides fake admin user; added `.catch()` to Keycloak init for graceful failure |
| `packages/auth/src/keycloak.ts` | `onLoad: 'login-required'` in prod, `'check-sso'` in dev |
| `packages/auth/src/index.ts` | Exports `DevAuthProvider` |
| `apps/back-office/src/main.tsx` | Uses `DevAuthProvider` when `import.meta.env.DEV` — no Keycloak required for local dev |
| `apps/back-office/tailwind.config.ts` | Changed import from `@cia/ui/tailwind.config` (package export) to `../../packages/ui/tailwind.config` (relative path) — fixes Tailwind PostCSS CJS loader |
| `apps/partner/tailwind.config.ts` | Same relative path fix |
| `packages/ui/package.json` | Added `"./tailwind.config": "./tailwind.config.ts"` to exports (belt-and-suspenders) |
| `apps/back-office/src/app/layout/Sidebar.tsx` | Added `onToggle` prop; hamburger (`Menu01Icon`) moved to sidebar logo row (right side); sidebar group headings 10→11px; collapsed state: logo only + centered hamburger |
| `apps/back-office/src/app/layout/Topbar.tsx` | Removed hamburger toggle (now in sidebar); Topbar is stateless — no props needed |
| `apps/back-office/src/app/layout/AppShell.tsx` | Passes `onToggle` to `Sidebar`; `Topbar` receives no props |
| `CLAUDE.md` | Frontend Architecture section replaced with actual monorepo structure; design system table; layout shell diagram; frontend patterns; VITE_ env vars table added |
| `.claude/skills/cia/SKILL.md` | Frontend Conventions section added (14 conventions) |

**Figma changes (file: `Zaiu2K7NvEJ7Cjj6z1xt2D`):**

| Node | Change |
|---|---|
| Sidebar logo row | Real Nubeero PNG applied via `upload_assets` (not base64 decoding) — imageHash `48e815d859429d722f18ad2e1ce1dcedeab4a8b9` |
| Sidebar logo row | Hamburger (≡) added to right side of logo row; removed from topbar |
| Sidebar nav items | 10 placeholder squares replaced with proper SVG stroke-path vectors for each module |
| Sidebar group labels | Font size 10→11px |
| Topbar | Rebuilt: title + search bar + bell + ? icons; no hamburger |
| Search bar | Height 36→37px |
| Premiums (MTD) stat | ₦ character in `₦84.2M`, `vs ₦71.5M last month`, and activity row set to `Noto Sans Regular` via `setRangeFontName(i, i+1, ...)` |

**Decisions made:**

- Hamburger toggle lives in the **sidebar logo row** (right-aligned), not the topbar. Sidebar manages its own collapse trigger.
- `DevAuthProvider` in `@cia/auth` (not in the app) so `useAuth()` works identically in both real and dev modes — same `AuthContext`.
- Tailwind config shared via **relative path import only** — never via package name, because Tailwind's PostCSS plugin uses CJS `require()` which ignores `package.json` `exports`.
- Naira sign ₦ (U+20A6): fixed at the CSS level via `unicode-range` scoped `@font-face` pointing to local Arial; fixed in Figma via `setRangeFontName` to Noto Sans per-character.
- Figma image uploads use `mcp__claude_ai_Figma__upload_assets` + curl POST (not `figma.createImage()` with base64) — the latter silently fails in API/screenshot contexts.
- React Query DevTools icon (bottom-right in dev) is intentional — dev-only, not part of production UI.

**Open questions:** None.

---

### Session 4d — CI/CD, Vercel Deploy & SESSION COMPLETION GATE Automation

**Files created/modified:**

| File | Change |
|---|---|
| `.claude/settings.json` | Stop hook updated to 8-gate SESSION COMPLETION GATE checklist |
| `.claude/skills/cia/SKILL.md` | SESSION COMPLETION GATE expanded from 6 → 8 gates; frontend + Figma gates added |
| `.github/workflows/ci.yml` | Frontend job enabled: pnpm v9, tsc on both apps, vite build, artifact upload |
| `.github/workflows/vercel-deploy.yml` | New: Vercel preview on PR + production on push to main (cia-frontend/** filter) |
| `cia-frontend/vercel.json` | Created at monorepo root; buildCommand + outputDirectory + SPA rewrite |
| `cia-frontend/.vercel/project.json` | Vercel project link at monorepo root (projectId: prj_d9m8fgnCZlKe0xTYjeRcnSMAQnHm) |
| `cia-frontend/apps/back-office/vercel.json` | Deleted — caused Vercel to only upload 254B instead of full workspace |
| `CLAUDE.md` | Frontend deployment section updated with production URL |

**Decisions made:**

- Vercel MUST be linked from `cia-frontend/` (monorepo root) — linking from `apps/back-office/` causes Vercel to upload only that subdirectory (254B), leaving workspace packages unreachable during install.
- `vercel.json` at `cia-frontend/` root. Build: `pnpm --filter @cia/back-office build`. Output: `apps/back-office/dist`.
- First two deploy attempts failed: OOM SIGKILL (wrong root, cold turbo build) and exit 127 (vite not found at app-level node_modules). Fixed by deploying from monorepo root.
- SESSION COMPLETION GATE enforced via Claude Code `Stop` hook — fires automatically at end of every session.
- `VERCEL_PROJECT_ID` GitHub secret updated to back-office project (was previously cia-docs).

**Production URL:** [back-office-blush-six.vercel.app](https://back-office-blush-six.vercel.app)

**Open questions:** None.

---

### Session 4e — Frontend Build Queue Established

**Decision:** A comprehensive, ordered frontend build queue has been saved in `CLAUDE.md` under the section **"Frontend Build Queue"**. This section is the authoritative tracker for all frontend work and must be kept up to date throughout the build.

**Build queue summary:**

| Phase | Builds | Description |
|---|---|---|
| Phase 1 | 1a–1e | Shared infrastructure (shadcn components, data table, page layout, form infrastructure, API types + hooks) |
| Phase 2 | Builds 2–10 | All 9 back-office modules in build order |
| Phase 3 | P1–P5 | Partner portal (auth, API explorer, webhooks, sandbox, usage dashboard) |
| **Total** | **19 builds** | **0% complete as of 2026-04-24** |

**Build order (Phase 2):**

1. Module 1 — Setup & Administration (35 features) — unlocks all other modules
2. Module 7 — Customer Onboarding (10 features)
3. Module 2 — Quotation (5 features)
4. Module 3 — Policy (23 features)
5. Module 8 — Finance (5 features)
6. Module 4 — Endorsements (10 features)
7. Module 5 — Claims (23 features)
8. Module 6 — Reinsurance (17 features)
9. Module 10 — Audit & Compliance (15 features) — can run parallel with Builds 8–9

**Audit protocol:** At the start of every frontend session, check `CLAUDE.md → Frontend Build Queue` for current status. Update the `[ ]` / `[~]` / `[x]` checkboxes as builds progress. At session end, the SESSION COMPLETION GATE Stop hook will prompt verification.

**Open questions:** None.

---

### Session 5 — Phase 1: Shared Infrastructure Complete

**Build queue progress: 5/19 builds complete (26%)**

**Builds completed this session:**

| Build | Status | Key files |
|---|---|---|
| 1a — shadcn components | `[x]` | `packages/ui/src/components/`: input, label, textarea, select, checkbox, switch, tabs, dialog, sheet, toast, toaster, dropdown-menu, avatar, card, skeleton, tooltip, separator, scroll-area |
| 1b — Data table | `[x]` | `packages/ui/src/components/data-table/`: data-table, column-header, toolbar, pagination, row-actions |
| 1c — Page layout | `[x]` | `packages/ui/src/components/layout/`: page-header, page-section, empty-state, stat-card, breadcrumb |
| 1d — Form infrastructure | `[x]` | `packages/ui/src/components/form.tsx` (Form, FormField, FormItem, FormLabel, FormControl, FormMessage, FormSection, FormRow) |
| 1e — API types + hooks | `[x]` | `packages/api-client/src/modules/`: setup, customer, quotation, policy, claims, finance DTOs; `hooks.ts`: useGet, useList, useCreate, useUpdate, useRemove |

**New packages added:**

| Package | Added to | Purpose |
|---|---|---|
| `@radix-ui/react-checkbox` | `@cia/ui` | Checkbox primitive |
| `@radix-ui/react-switch` | `@cia/ui` | Switch toggle primitive |
| `@radix-ui/react-tabs` | `@cia/ui` | Tabs primitive |
| `@radix-ui/react-popover` | `@cia/ui` | Popover (future combobox) |
| `lucide-react` | `@cia/ui` | Icon chevrons inside shadcn components |
| `@tanstack/react-table` | `@cia/ui` | Headless table engine |
| `react-hook-form` | `@cia/ui` + `@cia/back-office` | Form state management |
| `zod` | `@cia/ui` + `@cia/back-office` | Schema validation |
| `@hookform/resolvers` | `@cia/ui` + `@cia/back-office` | Zod ↔ RHF bridge |

**Decisions made:**
- `lucide-react` used for shadcn component internals (chevrons, check marks, X icons). hugeicons used for application-level navigation and module icons. No conflict — different use-cases.
- `react-hook-form` and `zod` added to `@cia/ui` (not just the app) so `Form` components live in the shared package.
- TanStack Table is headless — DataTable owns all rendering, zero UI opinions from the library.
- Form pattern: shadcn `Form` → `FormField` → `FormItem` → `FormLabel` + `FormControl` + `FormMessage`. Zod schema passed to `useForm({ resolver: zodResolver(schema) })` in the consuming component.
- API DTOs added for 6 modules (Setup, Customer, Quotation, Policy, Claims, Finance). Endorsement, Reinsurance, Audit DTOs to be added when those modules are built.

**TypeScript: ✅ 0 errors on `@cia/back-office` after all changes.**

**Open questions:** None.

---

### Session 5b — Figma Gate 5 catchup: Setup module screens

Two frames pushed to Figma file `Zaiu2K7NvEJ7Cjj6z1xt2D`, new page "Setup" (id: `54:2`):

| Frame | Node ID | Represents |
|---|---|---|
| `Setup / Users` | `55:2` | Archetypal list view — AppShell + Setup secondary nav, DataTable with status badges |
| `Setup / Company Settings` | `58:2` | Archetypal form view — Card sections, form fields, Save button |

Gate 5 (Figma Sync) was missed in Session 5 and corrected here before proceeding to Build 3.

**Open questions:** None.

---

### Session 5c — ProductSheet: inline Class of Business creation

**File modified:**

| File | Change |
|---|---|
| `apps/back-office/src/modules/setup/pages/products/ProductSheet.tsx` | Full rewrite — see decisions below |
| `apps/back-office/src/modules/customers/index.tsx` | Module routing scaffold (stub pages) |
| `apps/back-office/src/modules/customers/pages/*.tsx` | Stub placeholder pages for Build 3 |

**Decisions made:**

- Classes of Business dropdown now has a `+ New Class of Business` sentinel item (`value="__create_new__"`) at the bottom, separated by a `SelectSeparator`.
- Sentinel is intercepted in `onValueChange` before `field.onChange` — the field value is never set to the sentinel string.
- Inline creation opens a **Dialog** (centred modal), not a Sheet, to avoid z-index issues from nesting a Sheet inside an already-open Sheet.
- On save: new class appended to local state (`useState`) and immediately auto-selected via `form.setValue`. When backend is wired, `onCreateClass` will POST to `/api/v1/setup/classes` and use the returned ID.
- Seed list expanded from 4 hardcoded entries to 14 covering the full Nigerian market range: Motor Private/Commercial, Fire & Burglary, Marine Cargo/Hull, Goods in Transit, Engineering/CAR, Professional Indemnity, Public Liability, Employer's Liability, Personal Accident, Travel Insurance, Group Life, Bonds.
- The same inline-create pattern (sentinel value → Dialog → append to state → auto-select) should be applied to other master-data selects (Brokers, Reinsurers, Surveyors, etc.) as those modules are built.
- `tsc --noEmit` passes with 0 errors.

**GitHub:** commit `bd39256` on `main`
**Vercel:** Production deployment `back-office-bkycm4xxs` — Status: Ready ✅

**Open questions:** None.

---

### Session 6 — Build 3: Customer Onboarding module complete

**Build queue progress: 7/19 builds complete (37%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/customers/index.tsx` | Module routing: list, detail (/:id), reports |
| `apps/back-office/src/modules/customers/pages/CustomersListPage.tsx` | DataTable with Individual/Corporate type badge, KYC badge (verified/pending/failed), Status badge, Broker column, "New Customer ▾" dropdown |
| `apps/back-office/src/modules/customers/pages/individual/IndividualOnboardingSheet.tsx` | Sheet with first/last name, email, phone, DOB, ID type (NIN/Voter/DL/Passport), ID number, address, occupation, broker-enabled toggle |
| `apps/back-office/src/modules/customers/pages/corporate/CorporateOnboardingSheet.tsx` | Sheet with company name, RC number, email, phone, address, useFieldArray directors table, broker-enabled toggle |
| `apps/back-office/src/modules/customers/pages/detail/CustomerDetailPage.tsx` | Tabs: Summary (contact details), KYC (ID + re-submit button), Policies (inline table), Claims (inline table); breadcrumb + action buttons |
| `apps/back-office/src/modules/customers/pages/reports/LossRatioReportPage.tsx` | StatCards + table by class with colour-coded rating badge (Good/Moderate/High) |
| `apps/back-office/src/modules/customers/pages/reports/ActiveCustomersReportPage.tsx` | StatCards + table by onboarding channel (individual vs corporate count + share %) |

**Figma:** Customers page created (id: `62:2`)
- `Customers / List` (node `62:3`): DataTable with all 5 rows, KYC badges, type badges, broker column
- `Customers / Detail` (node `65:2`): Summary tab with Contact Details card, tabs row (Summary/KYC/Policies 2/Claims 1)

**Decisions made:**
- Customers entry point uses a "New Customer ▾" dropdown splitting individual vs corporate onboarding — same pattern as "New Quote ▾" in quotation.
- `updatedAt` field added to all CustomerDto mock objects to satisfy the DTO type.
- Removed `Separator` unused import from CustomerDetailPage — TS strict mode catches unused imports.

**GitHub:** commit `dbd05db` | **Vercel:** Ready ✅

**Open questions:** None.

---

### Session 7 — Build 4: Quotation module complete

**Build queue progress: 8/19 builds complete (42%)**

**Files created:**

| File | Description |
|---|---|
| `apps/back-office/src/modules/quotation/index.tsx` | Module routing: list, detail (/:id), bulk-upload |
| `apps/back-office/src/modules/quotation/pages/QuotationListPage.tsx` | DataTable with quote number (teal link), customer, product, ₦ sum insured + net premium, 5 status variants (approved/submitted/draft/converted/rejected), version badge; Bulk Upload + New Quote ▾ dropdown |
| `apps/back-office/src/modules/quotation/pages/create/SingleRiskQuoteSheet.tsx` | Customer + product selects (product auto-fills rate), policy period, sum insured, rate, discount, live premium preview block (gross → discount → net) visible when SI+rate filled |
| `apps/back-office/src/modules/quotation/pages/create/MultiRiskQuoteSheet.tsx` | useFieldArray risk items each with description/SI/rate, rolling total SI + total premium summary |
| `apps/back-office/src/modules/quotation/pages/detail/QuoteDetailPage.tsx` | 2-column cards (quote details + premium summary), version history timeline with v-dot indicators, status-conditional action buttons (Submit / Convert / Edit) |
| `apps/back-office/src/modules/quotation/pages/bulk/BulkUploadPage.tsx` | Drag-and-drop CSV zone, validation results with error row detail, CSV template download section |

**Figma:** Quotation page created (id: `66:2`)
- `Quotation / List` (node `66:3`): all 5 status badge variants, ₦ premium columns, version numbers

**Decisions made:**
- `MockQuote` type defined explicitly (not `Partial<QuoteDto>`) to avoid TypeScript narrowing issues where `q.status === 'DRAFT'` was always false due to literal type.
- SingleRiskQuoteSheet auto-fills the rate field when a product is selected from the dropdown, using `form.setValue('rate', product.defaultRate)`.
- QuoteDetailPage action buttons are status-conditional: `canSubmit = DRAFT`, `canConvert = APPROVED`, `canEdit = not CONVERTED and not APPROVED`.
- Bulk upload uses a controlled `UploadState` ('idle' | 'validating' | 'done') — simulates async validation with setTimeout.

**GitHub:** commit `0ff5f66` | **Vercel:** Ready (latest production: `back-office-9dsx0cqzx`) ✅

**Open questions:** None.