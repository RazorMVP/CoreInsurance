# NDPR DSAR Export + PII Retention Purge — Design

**Date:** 2026-06-15
**Backlog item:** `ndpr-dsar-and-retention` (P1)
**Branch:** `feature/ndpr-dsar-retention` (off `main`)
**Status:** design approved; ready for implementation plan.

---

## 1. Goal

Deliver the two NDPR obligations CLAUDE.md §8 already claims are present but which do **not** exist in code:

1. **DSAR export** — a Data Subject Access Request endpoint that gathers a customer's full data footprint (including decrypted high-risk PII) and returns it as a downloadable **JSON + PDF** bundle (the "right of access" / data portability).
2. **PII retention purge** — a scheduled, per-tenant Temporal workflow that **anonymizes** a customer's master PII once it passes the tenant's configured retention period (the "storage limitation" / retention-enforcement obligation).

Both operate on the **customer master PII profile** only. Policy/claim/quote/endorsement records are NAICOM/NIID-mandated regulatory records and are **never** touched.

### Decisions locked during brainstorming
| # | Decision | Choice |
|---|---|---|
| Q1 | Scope | **Export + scheduled retention purge** (on-demand "erase now" deferred to a follow-up) |
| Q2 | Erasure mechanism | **Anonymize-in-place** (null/tombstone master PII, delete directors + KYC blobs, keep the `customers` row as an anonymized stub, retain policy/claim snapshots) |
| Q3 | Retention clock | **Inactivity-based** (no `ACTIVE` policy AND last activity older than the retention period) |
| Q4 | Export format/delivery | **JSON + PDF together** (default = a ZIP of both; `?format=json\|pdf` fetches one) |
| Q5 | Authorization | **New dedicated `DATA_PROTECTION` role** (gates both the export and the retention-config management) |

---

## 2. Why this is safe — the core NDPR-vs-NAICOM reconciliation

NDPR grants a right to erasure; NAICOM/NIID require multi-year retention of policy/claim records. These are reconciled by the **existing snapshot architecture** (confirmed during recon): policies, quotes, claims, endorsements, debit/credit notes reference the customer by a **denormalized snapshot** (`customer_id` + `customer_name` captured at creation; *no* hard FK) and are **never soft-deleted**. Therefore we can erase/anonymize the reusable **customer master PII** (id number, address, DOB, contact details, directors' IDs) while the legally-required named transaction records stay intact and linkable to an anonymized stub. This mirrors **GDPR Art. 17(3)(b) / NDPR's legal-obligation carve-out**: erasure does not extend to data a regulator legally requires you to keep.

**NDPR applies to natural persons.** Individual customers and *all* directors (of any customer) are data subjects → their personal PII is anonymized. A **corporate entity itself is not a data subject** — `company_name`/`rc_number` are corporate-identity, not personal data, and are retained (they also name the corporate's regulatory policy records); but that corporate's **directors' personal PII is purged**.

---

## 3. Architecture — a new `cia-compliance` module

A new Maven module `cia-compliance`, mirroring `cia-reports`' proven pattern: **native SQL queries against the tenant schema, zero business-module dependencies.** The customer footprint spans 6+ modules (customer, policy, quotation, claims, endorsement, finance, audit); a JPA gather would couple to all of them. A native-query gather stays clean and decrypts high-risk PII **inline in SQL** via `pgp_sym_decrypt(col, current_setting('app.pii_key'))` (the `app.pii_key` session var is set on every Hikari connection, so any connection can decrypt — to be re-confirmed at implementation).

**Dependencies:** `cia-common` (AuditService, ApiResponse, BaseEntity, TenantContext), `cia-storage` (DocumentStorageService — delete KYC blobs, optionally store export artifacts), `cia-workflow` (Temporal client + the `TenantAwareWorkerInterceptor` pattern). **No** dependency on cia-customer/policy/claims/etc.

> **Alternative considered:** fold into `cia-api`. Rejected for the same reason `cia-reports` is its own module — keeping the cross-schema aggregation in a boundaried module is cleaner. (If the implementer hits unexpected friction standing up a new module, falling back to a `cia-api` package is an acceptable descope — flag it, don't silently switch.)

**Module contents:**
- `data_retention_policy` entity + repository + `RetentionPolicyService` + `RetentionPolicyController`.
- `DsarGatherService` (native queries) + `DsarJsonRenderer` + `DsarPdfRenderer` + `DsarExportController`.
- `CustomerPiiPurgeWorkflow` + activities + `ComplianceWorkerConfig` (worker registration + cron scheduling).

---

## 4. Per-tenant retention config

A new Flyway migration **`Vnn__data_retention_policy.sql`** (next free version — **V68 on this branch, but pin to avoid colliding with the in-flight SP2 `V68__platform_audit_log_public_only.sql` on `platform-admin-ui`; use V69+ if needed**). Tenant-schema **singleton** table (the `customer_number_format` precedent), applied to every tenant schema by the per-schema migration sweep.

| Column | Type | Default | Meaning |
|---|---|---|---|
| `id` | UUID | — | PK (BaseEntity) |
| `customer_pii_retention_days` | INT | **2555** (7 years) | Days after last activity before a customer's PII is purge-eligible |
| `purge_enabled` | BOOLEAN | **false** | **Opt-in safety rail** — the destructive purge does not run for a tenant until a DPO explicitly enables it |
| `purge_frequency` | VARCHAR(10) | **`WEEKLY`** | `WEEKLY` or `MONTHLY` — how often this tenant's purge runs |
| `purge_day_of_week` | SMALLINT | **0** (Sunday) | 0–6 (Sun–Sat); the day the purge runs when `WEEKLY` (ignored for `MONTHLY`, which runs on the 1st of the month) |
| `purge_hour_utc` | SMALLINT | **3** | 0–23; the UTC hour the purge runs (3 ≈ 04:00 WAT, off-peak) |
| `last_purge_run_at` | TIMESTAMPTZ | NULL | Debounce — set when a window fires so the hourly cron runs a tenant's purge at most once per window |
| `created_at`/`updated_at`/`created_by` | — | — | BaseEntity audit columns |

Managed via `GET`/`PUT /api/v1/compliance/retention-policy` (`hasRole('DATA_PROTECTION')`). The service lazily creates the singleton with defaults on first read so every tenant has a row. The `PUT` validates `customer_pii_retention_days > 0`, `purge_frequency ∈ {WEEKLY, MONTHLY}`, `purge_day_of_week ∈ 0..6`, `purge_hour_utc ∈ 0..23` (rejected via `BusinessRuleException` → **HTTP 422** with `{errorCode, message}`, the codebase's well-formed-but-invalid convention). **A schedule change is just a config write** — it takes effect at the next matching window; there is no Temporal re-registration, because a single global cron reads the per-tenant schedule each run (§6).

---

## 5. DSAR export (read-only; always available)

**Endpoint:** `GET /api/v1/customers/{id}/dsar-export?format=json|pdf` — `hasRole('DATA_PROTECTION')`. Lives in `DsarExportController` (cia-compliance). Runs inside an HTTP request (tenant context present), so native queries decrypt PII inline.

**Gather (`DsarGatherService`, native queries keyed by `customer_id`):** assembles a structured `DsarExport` object:
- **Customer** — all columns, with `id_number`/`id_document_url`/`address` **decrypted**.
- **Directors** — each with decrypted `id_number`/`id_document_url`.
- **KYC documents** — metadata (type, path, uploaded-at); not the blob bytes.
- **Policies / Quotes / Claims / Endorsements** — the customer's records (number, status, dates, amounts).
- **Finance** — receipts/payments/debit-notes/credit-notes tied to the customer.
- **Audit history** — `audit_log` rows where `entity_id = customer.id` (the customer's own change history).

**Render:**
- `DsarJsonRenderer` — Jackson → pretty structured JSON (the machine-readable portability copy).
- `DsarPdfRenderer` — PDFBox, the `ReportPdfRenderer`/`HtmlToPdfConverter` pattern (NotoSans-embedded for the ₦ glyph since amounts appear). Human-readable, sectioned.
- **Default (no `format`)** → a **ZIP** (`ZipOutputStream`, the `PdfZipService` pattern) containing `dsar-{customer_number}.json` + `dsar-{customer_number}.pdf`, streamed as `application/zip` attachment. `?format=json` / `?format=pdf` streams that single file.

**Audit:** writes one audit row recording the DSAR export event — **metadata only** (`entityType="Customer"`, `entityId`, `action=SEND`, `reason="NDPR_DSAR_EXPORT"`, who/when). It must **NOT** store the exported PII payload (that would re-introduce the disclosure into `audit_log`).

---

## 6. Scheduled PII purge (`CustomerPiiPurgeWorkflow`)

A **single global** Temporal **cron** workflow firing **hourly** (`"0 * * * *"`, workflow-id `customer-pii-retention-purge-cron`), registered once by `ComplianceWorkerConfig` on a dedicated `TemporalQueues.COMPLIANCE_QUEUE` (new constant) — mirrors `PdfDownloadLogRetentionWorkflow` + `NotificationsWorkerConfig`. The hourly firing is cheap — each run only checks whether a tenant's configured window matches the current UTC hour; the **actual purge schedule is per-tenant** (`purge_frequency` / `purge_day_of_week` / `purge_hour_utc`, §4). Keeping one global cron (vs one cron per tenant) means a schedule change is a plain config write the next hourly sweep observes — **no per-tenant Temporal registration lifecycle** to manage on onboard/suspend/config-change.

### 6.1 Multi-tenant sweep (mandatory — see §6.2 for why)
```
purge():  // fires hourly
  1. activity: listActiveTenants()  → SELECT schema_name FROM public.tenants WHERE active = true
       (runs with no tenant context ⇒ resolver defaults to "public" ⇒ exactly where the registry lives)
  2. for each tenant schema → activity: purgeTenant(schema):
       (TenantAwareWorkerInterceptor sets TenantContext from the carried schema; ActivityThreadCleanup clears it)
        a. read this tenant's data_retention_policy; return early UNLESS all of:
             • purge_enabled = true
             • window match (UTC now): hour == purge_hour_utc AND
                 (purge_frequency = WEEKLY  ⇒ day_of_week(now) == purge_day_of_week)
                 (purge_frequency = MONTHLY ⇒ day_of_month(now) == 1)
             • debounce: last_purge_run_at IS NULL OR last_purge_run_at < now() − 23h
                 (fire once per window; survives hourly retries / restarts within the matched hour)
        b. UPDATE data_retention_policy SET last_purge_run_at = now()   (claim the window before purging)
        c. eligibility query (§6.3)
        d. for each eligible customer → anonymize (§6.4) + delete blobs + audit (§6.5)
        e. per-customer failures are caught + logged + skipped (one bad row never aborts the tenant)
       per-tenant failures are caught + logged + skipped (one bad tenant never aborts the sweep)
```

### 6.2 Why the sweep is required (not optional)
A cron has no HTTP request → `TenantContext` is empty → `TenantIdentifierResolver` returns its `DEFAULT_SCHEMA = "public"` (confirmed at `cia-common/.../TenantIdentifierResolver.java:9-14`). So a context-less query lands on `public`, where `customers`/`policies` don't exist, and would only ever touch one schema anyway. We must explicitly enumerate `public.tenants WHERE active` and set the tenant context per schema. This reuses three things already in the codebase: `TenantContext` (ThreadLocal), the `TenantAwareWorkerInterceptor` + `ActivityThreadCleanup` (the backfill feature's set/clear-per-activity machinery), and the `public.tenants WHERE active` sweep idiom (the `TenantBootstrapRunner` already uses it). **Suspended tenants (`active=false`) are skipped** — frozen data pending reactivation/decommission, not routine purge.

> **Side-discovery (→ backlog `pdf-retention-multitenant-gap`):** the existing `PdfDownloadLogRetentionActivitiesImpl` does a bare `repository.deleteByDownloadedAtBefore(cutoff)` with **no** tenant iteration — so under no context it resolves to `public` and is effectively a silent no-op in real multi-tenant. Tolerable for a log purge; it must NOT be copied. Logged, not fixed in this slice.

### 6.3 Eligibility (native query, per tenant)
A customer is purge-eligible iff **all**:
- `pii_purged_at IS NULL` (idempotent — never re-purge).
- **No `ACTIVE` policy:** `NOT EXISTS (SELECT 1 FROM policies p WHERE p.customer_id = c.id AND p.status = 'ACTIVE')`.
- **Last activity older than the cutoff:** `last_activity < now() - (retention_days || ' days')::interval`, where `last_activity = GREATEST(` max `policies.policy_end_date`, max `claims.reported_date` `)` for that customer, falling back to `customers.created_at` when the customer has no policies/claims.

A customer with a live policy is therefore **never** purged.

### 6.4 Anonymize-in-place (field-by-field)
`UPDATE customers SET ... WHERE id = ?` — for an **individual** customer:

| Field | Action | Rationale |
|---|---|---|
| `id_number`, `id_document_url`, `address` (encrypted) | **NULL** | High-risk personal PII |
| `date_of_birth`, `email`, `phone`, `alternate_phone` | **NULL** | Personal PII |
| `first_name`, `last_name` | **tombstone** `'[ERASED]'` | Personal PII (kept non-null so the stub renders) |
| `gender`, `marital_status`, `city`, `state` | **NULL** | Personal/demographic PII |
| `blacklist_reason`, `kyc_provider_ref`, `kyc_failure_reason` | **NULL** | Free-text may contain PII |
| `customer_number`, `country`, `kyc_status`, `relationship_manager_id`, timestamps | **retain** | Non-personal / operational; needed for the stub + audit |
| `pii_purged_at` | **set `now()`**, `deleted_at` set | Mark anonymized + hide from active lists |

For a **corporate** customer: `company_name`, `rc_number`, `cac_certificate_url`, `incorporation_date`, `industry` are **retained** (corporate identity, not personal data); its **directors are purged** (below).

**Cascade:** `DELETE FROM customer_directors WHERE customer_id = ?` (directors are personal data of natural persons); for `customer_documents` and the customer's/directors' `id_document_url` + `cac_certificate_url`, resolve the storage paths then `DocumentStorageService.delete(...)` each blob, then delete the `customer_documents` rows.

### 6.5 Audit (without re-introducing PII)
Each anonymized customer writes one audit row: `action=DELETE`, `reason="NDPR_RETENTION_PURGE"`, and a `new_value` JSON of **metadata only** — `{customerId, retentionDays, fieldsAnonymized:[...], directorsDeleted:N, blobsDeleted:M, purgedAt}`. It must **NOT** snapshot the erased PII values (`old_value` is null/omitted) — auditing the erased data would defeat the erasure. Audit writes use `REQUIRES_NEW` (a failed audit write never rolls back the purge).

---

## 7. Error handling & safety rails

- **Opt-in:** `purge_enabled=false` by default — no tenant is purged until a DPO turns it on. The single most important guard against accidental mass erasure.
- **Idempotent:** `pii_purged_at` sentinel; re-runs skip already-purged customers.
- **Window debounce:** the hourly cron stamps `last_purge_run_at` before purging and skips a tenant whose window already fired in the last 23h — so each tenant runs **at most once per scheduled window** despite the hourly firing + Temporal activity retries.
- **Active-customer protection:** the eligibility query structurally excludes any customer with an `ACTIVE` policy.
- **Failure isolation:** per-customer and per-tenant try/catch — one bad row/tenant logs + continues; the sweep always completes.
- **DSAR export:** unknown/foreign `customer_id` → 404; render/storage failure → 500 with a generic message (never echo PII into an error body or log line).
- **ThreadLocal hygiene:** tenant context is set and cleared per tenant (via `TenantAwareWorkerInterceptor`/`ActivityThreadCleanup`) to prevent cross-tenant bleed on pooled worker threads.

---

## 8. Testing

Testcontainers (real Postgres + pgcrypto + `app.pii_key`) integration tests:
- **DSAR gather/render:** seed a customer + directors + KYC docs + ≥1 policy/quote/claim/finance record; export; assert the JSON contains the **decrypted** `id_number`/`address` + every related-record section; assert the PDF renders (non-empty, contains the customer number); assert the ZIP contains both files; assert the export writes a metadata-only audit row (no PII payload).
- **Purge eligibility:** seed (a) an eligible inactive customer, (b) a customer with an `ACTIVE` policy, (c) a recently-active customer inside the retention window, (d) an already-`pii_purged_at` customer. Assert only (a) is selected.
- **Purge anonymize:** run the activity; assert (a) has PII nulled/tombstoned + `pii_purged_at` set + directors deleted + blobs deleted via a stubbed `DocumentStorageService`; assert the related policy/claim snapshots are **untouched**; assert a metadata-only audit row; assert **idempotency** (second run is a no-op).
- **Opt-in gating:** `purge_enabled=false` → zero customers touched.
- **Per-tenant schedule + debounce:** with `purge_enabled=true`, assert the purge runs only when UTC "now" matches the tenant's window (`purge_hour_utc` + `WEEKLY` day-of-week / `MONTHLY` day-1), is a no-op outside it, and runs **at most once per window** (a second hourly fire inside the matched window is debounced via `last_purge_run_at`); assert `PUT` validation rejects out-of-range `purge_frequency`/`purge_day_of_week`/`purge_hour_utc` (400).
- **Multi-tenant sweep:** provision two tenant schemas; eligible customers in both; assert both are purged and that context doesn't bleed (tenant A's purge doesn't touch tenant B's ineligible rows).
- **Eligibility SQL** and the renderers get focused unit coverage where pure.

---

## 9. Out of scope / follow-ups

- **On-demand erasure** (`POST /customers/{id}/erase`, right-to-be-forgotten button) — deferred (Q1 scope A). Trivial once the anonymize engine exists; a fast follow.
- **Back-office DPO UI** (retention-config screen, DSAR export button) — frontend, separate slice; this is the backend item.
- **`pdf-retention-multitenant-gap`** — backlog row (the existing PDF-log retention cron doesn't sweep tenants).
- **Anonymizing the denormalized `customer_name` on policies/claims** — deliberately NOT done (NAICOM/NIID require the named record; §2).
- The migration version must be pinned to avoid the in-flight SP2 `V68` collision.

### Likely implementation decomposition (for writing-plans)
- **Slice A:** `cia-compliance` module + `data_retention_policy` (entity/migration/service/controller, incl. the per-tenant schedule fields + `PUT` validation) + `DATA_PROTECTION` role + DSAR export (gather/JSON/PDF/ZIP/controller/audit) + ITs.
- **Slice B:** `CustomerPiiPurgeWorkflow` + activities + multi-tenant sweep + per-tenant window-match + `last_purge_run_at` debounce + `ComplianceWorkerConfig` hourly cron + ITs.
