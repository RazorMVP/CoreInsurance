---
title: Backup & Disaster Recovery Runbook
sidebar_label: Backup & DR Runbook
---

# Database Backup & Disaster Recovery — Operational Runbook

> Audience: platform-engineering on-call, paired with a tenant/compliance owner for any restore that touches regulated data.
> Last edited 2026-06-23.

## 1. Why this is not "just turn on RDS backups"

CIAGB holds **regulated, schema-per-tenant financial data** (NAICOM retention,
NDPR PII). Three facts make its DR materially different from a single-database
app, and getting any of them wrong turns a "successful" restore into an
unrecoverable one:

1. **PII is encrypted with a key that is NOT in the database.** The high-risk
   PII columns (`customers.id_number` / `.address` / `.id_document_url` and the
   same on `customer_directors`) are `pgcrypto`-encrypted `bytea`. A backup —
   any backup, managed snapshot or `pg_dump` — contains only ciphertext.
   Decryption depends on `PII_ENCRYPTION_KEY`, which lives in the **secret
   store** (`cia-backend-secrets`), never in the DB. **Restore the database
   without the key and the PII is permanently unreadable.** The key is part of
   the backup surface, with its own (separate) custody.
2. **There are three independent datastores, not one.** Tenant business data
   (Postgres), **auth** (Keycloak's own Postgres), and **documents** (object
   storage: policy PDFs, claim photos, KYC uploads) each fail and restore
   independently. A DB-only restore leaves dangling document links and
   tenants whose users can't log in.
3. **Schema-per-tenant is a DR superpower** — a single tenant can be restored
   surgically without touching the other tenants — *if* the restore procedure
   respects the `public.tenants` registry and the per-schema
   `flyway_schema_history`.

This runbook codifies the strategy across all three datastores, with the
encryption key treated as a first-class recovery dependency.

## 2. Recovery objectives

| Scope | Mechanism | RPO (data loss) | RTO (time to restore) |
|---|---|---|---|
| Full Postgres (region/instance loss) | Managed PITR (continuous WAL) | seconds–minutes | tier-dependent (size of restore) |
| Full Postgres (managed-backup corruption / provider exit) | Logical `pg_dump` CronJob → object storage | ≤ backup interval (daily ⇒ ≤ 24h) | dump size + import time |
| Single tenant schema (accidental drop / logical corruption) | PITR to a staging instance → extract one schema | seconds–minutes | minutes |
| Keycloak (auth) | Managed PITR on Keycloak's DB | seconds–minutes | tier-dependent |
| Documents (object storage) | Versioning + cross-region replication | near-zero (versioned) | near-zero (in place) |

> RPO/RTO **targets** are set per tenant contract; the table above is what the
> mechanisms physically deliver. Validate them in the quarterly drill (§7), not
> on paper.

## 3. Backup layers

### 3.1 PRIMARY — managed-Postgres PITR (no application code)

Production Postgres is a managed service (RDS / Cloud SQL / AlloyDB). Enable, at
the instance level:

- **Automated backups + point-in-time recovery** (continuous WAL archiving).
  Retention ≥ the longest tenant retention contract (default target: 35 days
  hot PITR; longer retention is served by the logical layer in §3.2).
- **Cross-AZ** storage for the backups; **cross-region** copy for the daily
  snapshot if any tenant contract requires region-failure survival.
- **Deletion protection** on the instance, and an **immutable/object-locked**
  copy target if ransomware is in the threat model.

This layer is configured in the managed provider / IaC (Terraform), **not** in
this Helm chart — the chart treats Postgres as an external backing service (see
the chart README). PITR is the DR primary because its RPO (WAL-level) is far
tighter than any scheduled logical dump.

### 3.2 SECONDARY — logical `pg_dump` CronJob (this chart)

The Helm chart ships an **opt-in** logical-backup `CronJob`
(`templates/backup-cronjob.yaml`, `backup.enabled=false` by default). It is the
**portable, provider-independent** layer — the escape hatch from managed-backup
corruption, provider lock-in, and the source for refreshing staging
environments. It is **not** a replacement for PITR (its RPO is the schedule
interval).

What it does each run:

- `pg_dump --format=plain --no-owner --no-privileges --schema='*'` of the whole
  `cia` database (every tenant schema + `public`), gzipped to a writable
  `emptyDir` (the pod runs `readOnlyRootFilesystem`).
- Uploads to object storage via a configurable `backup.uploadCommand`
  (aws-cli default; override for `mc` / `gsutil`).
- Runs hardened: non-root, all caps dropped, `RuntimeDefault` seccomp,
  least-privilege secret access (only `DB_USERNAME` / `DB_PASSWORD` /
  `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` from `cia-backend-secrets`).

Enable it (go-live):

```yaml
# values-prod.yaml
backup:
  enabled: true
  image: "<registry>/pg-dump-s3@sha256:..."  # pg_dump 16 + aws-cli; pin by DIGEST
  schedule: "0 1 * * *"                       # daily 01:00 UTC
  db: { host: "<pg-host>", port: 5432, name: cia }
  s3:
    bucket: "cia-db-backups"   # SEPARATE bucket from STORAGE_BUCKET (documents)
    prefix: db-backups
    region: us-east-1
    endpoint: ""               # set for MinIO / non-AWS S3-compatible
```

Notes:

- **Image:** stock `postgres:16` has `pg_dump` but **no** object-storage client.
  Supply a combined image and **pin it by digest** (supply-chain). `backup.image`
  is `required` when `backup.enabled=true` — the chart fails to render rather
  than silently misconfigure.
- **Separate bucket** from `STORAGE_BUCKET`, so document objects and DB backups
  carry independent lifecycle/retention/access policies.
- **Retention** is an **object-storage lifecycle policy** on the bucket/prefix
  (e.g. transition→Glacier at 30d, expire at the contracted retention) — it is
  deliberately **not** enforced in the CronJob. The dump major version must
  match the server major version.

### 3.3 The encryption key — back it up, separately

`PII_ENCRYPTION_KEY` (and `WEBHOOK_SIGNING_SECRET`) are **not** in any DB backup.
A DB restore is only useful with the key that was active when the data was
written.

- Store the key in the secret manager (AWS Secrets Manager / Vault) with
  **versioning enabled** and its own backup/replication.
- **Never rotate the PII key in place** without a re-encryption migration — old
  ciphertext was sealed with the old key. If the key is ever rotated, the old
  versions must be retained for as long as any backup that used them.
- The quarterly drill (§7) **must** include a decrypt round-trip with the
  restored key, or the backup's PII recoverability is unproven.

## 4. Restore — full database (PITR)

For region/instance loss or broad corruption.

1. **Freeze writes.** Scale `cia-api` to 0 (`kubectl scale deploy/cia-api
   --replicas=0`) so nothing writes to the recovering target.
2. **Restore via the managed console/IaC** to a new instance at the chosen
   timestamp (just before the incident for corruption; latest for hardware
   loss).
3. **Point the app at the restored endpoint** — update `DB_URL` in
   `cia-backend-secrets`, confirm `PII_ENCRYPTION_KEY` is the version that was
   active for the restored data.
4. **Let Flyway re-converge.** On boot, `cia-api` sweeps `public.tenants WHERE
   active` and re-migrates each schema (Flyway-per-schema). A restore of an
   older snapshot heals **forward** automatically on restart — but never expect
   a restore to *undo* a destructive migration; for that, PITR to before the
   migration's transaction.
5. **Scale back up**, verify `/actuator/health` 200 and a per-tenant smoke
   (§7 checklist).

## 5. Restore — a single tenant (schema-per-tenant)

The high-value, common case: one tenant's schema was dropped, corrupted, or
needs rollback, and the other tenants must stay live and untouched.

1. **Restore to a staging instance** via PITR (do **not** restore over prod).
2. **Extract just that tenant's schema** from staging:
   ```bash
   pg_dump -n 'tenant_acme' --no-owner --no-privileges \
     "postgresql://<user>@<staging-host>:5432/cia" | gzip > tenant_acme.sql.gz
   ```
   (The logical CronJob dumps all schemas in one file; for routine per-tenant
   granularity, run a per-tenant `pg_dump -n` variant on a separate schedule.)
3. **Quiesce the tenant** in prod — suspend it via the platform-admin plane
   (`POST /api/v1/platform/tenants/{schema}/suspend`) so no traffic writes to
   the schema being replaced, and the activation cache is evicted.
4. **Swap the schema** in prod inside one transaction:
   ```sql
   ALTER SCHEMA "tenant_acme" RENAME TO "tenant_acme_corrupt_20260623";
   -- then restore the extracted dump (it creates "tenant_acme")
   ```
   Restore the dump (`gunzip -c tenant_acme.sql.gz | psql "<prod-url>"`).
5. **Reconcile the registry & auth.** `public.tenants` row for the tenant must
   remain consistent (same schema name); the tenant's Keycloak realm
   (`realm name = tenant id`) is a **separate** datastore — if the incident also
   lost auth state, restore Keycloak to a consistent point (§6).
6. **Re-activate** (`.../activate`), verify the tenant smoke, then drop the
   `_corrupt_` schema once satisfied.

> Never edit a migration to "fix" a restored schema. The per-schema
> `flyway_schema_history` is restored with the dump; the app re-migrates forward
> from there on next boot.

## 6. The other two datastores

- **Keycloak (auth).** Realms/users/clients live in Keycloak's **own** Postgres.
  It needs its **own** managed PITR. Tenant business data and auth are loosely
  coupled (`realm name = tenant id`), so they don't need transaction-consistent
  co-restore, but a full-DR runbook step must restore both — a tenant whose
  business data is back but whose realm is gone cannot log in. `KeycloakTenant
  Bootstrap` re-heals realm **config** (unmanaged-attr policy, back-office
  client) on app restart, but it does **not** recreate users.
- **Object storage (documents).** Policy PDFs, claim photos, KYC uploads. DB
  rows store object **paths**; a DB restore without the matching objects yields
  dangling links. Protect with bucket **versioning** + **cross-region
  replication** (recovers in place, near-zero RTO). The `db-backups` bucket
  (§3.2) is separate and has its own lifecycle.
- **Temporal.** Durable workflow state (NAICOM upload retries, email/SMS,
  retention crons) is in Temporal's own persistence — managed/separate. It is
  **not** the regulated system of record; on a Temporal loss, in-flight async
  work is re-driven (NAICOM upload is idempotent on `naicom_uid`, email/SMS
  audit-after-success is idempotent). No special restore ordering required.

## 7. Quarterly restore drill — the only proof that matters

A backup is unproven until restored. Run this every quarter against a non-prod
target and record the result + timings (feeds the RTO/RPO table in §2).

- [ ] Restore the **latest** managed snapshot (or PITR to T-1h) to a fresh
      staging instance. Record wall-clock RTO.
- [ ] Restore the **latest logical dump** (§3.2) into a second throwaway DB —
      proves the secondary layer independently of the managed provider.
- [ ] Point a staging `cia-api` at the restore with the **restored
      `PII_ENCRYPTION_KEY`**; confirm `/actuator/health` 200 after Flyway
      re-migration.
- [ ] **PII decrypt round-trip:** fetch one customer with an encrypted
      `id_number`/`address` and confirm it decrypts to plaintext (proves the key
      ↔ ciphertext pairing survived).
- [ ] **Per-tenant integrity:** for 2–3 tenants, spot-check row counts and a
      trial-balance / JE reconciliation against the pre-incident figure.
- [ ] **Single-tenant extract:** run the §5 `pg_dump -n '<schema>'` extract from
      staging and confirm it restores standalone.
- [ ] **Documents:** fetch one policy PDF / claim photo by its stored path.
- [ ] Record RTO/RPO actuals, file deviations from contracted targets, destroy
      the throwaway instances.

## 8. What is codified where

| Concern | Codified in | Note |
|---|---|---|
| Managed PITR, cross-AZ/region, deletion protection | Provider / Terraform | External backing service — not this chart |
| Logical secondary backup (CronJob) | `deploy/helm/cia-backend` (`backup.*`) | Opt-in; §3.2 |
| Encryption-key custody | Secret manager | §3.3 — separate from DB backups |
| Restore procedures + drill | This runbook | §4–§7 |
| Read-scaling for reports (replica) | (follow-up — read-replica routing) | Tracked separately; not a DR mechanism |
