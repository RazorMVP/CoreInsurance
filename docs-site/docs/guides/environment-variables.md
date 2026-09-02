---
id: environment-variables
title: Environment Variables
sidebar_label: Environment Variables
---

## Backend (`cia-api`)

All variables have defaults for local development. Production values must be supplied via Kubernetes Secrets or a vault.

### Core

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8090` | HTTP port the Spring Boot API listens on |
| `DB_URL` | `jdbc:postgresql://localhost:5432/cia` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `cia` | PostgreSQL user |
| `DB_PASSWORD` | `cia_dev` | PostgreSQL password |
| `KEYCLOAK_URL` | `http://localhost:8180` | Keycloak server base URL |

### Tenant Provisioning

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `CIA_TENANT_BOOTSTRAP_ENABLED` | `false` | Master switch for `TenantBootstrapRunner`. Set `true` to provision tenants on startup. Requires `KEYCLOAK_ADMIN_ENABLED=true`; startup fails fast if that flag is absent. **Never enable in the IT suite or local dev unless you intend to provision.** |
| `cia.tenants.bootstrap.tenants[n].schema` | — | PostgreSQL schema name for this tenant (e.g. `tenant_acme`). Also used as the Flyway target. |
| `cia.tenants.bootstrap.tenants[n].realm` | — | Keycloak realm name. Convention: same as `schema`. |
| `cia.tenants.bootstrap.tenants[n].display-name` | — | Human-readable name shown in the UI (e.g. `"Acme Insurance"`). |
| `cia.tenants.bootstrap.tenants[n].subdomain` | — | Subdomain component for this tenant (e.g. `acme` → `acme.cia.app`). |
| `cia.tenants.bootstrap.tenants[n].admin-username` | — | Username for the first-admin Keycloak user created during provisioning. |
| `cia.tenants.bootstrap.tenants[n].admin-email` | — | Email address for the first-admin user. |
| `cia.tenants.bootstrap.tenants[n].admin-temp-password` | — | **Secret.** Temporary password for the first-admin user. Keycloak forces `UPDATE_PASSWORD` on first login — this value is never a long-lived credential. Supply via vault or a per-tenant `${ENV_VAR}` reference. |

### Temporal

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `TEMPORAL_HOST` | `localhost` | Temporal frontend host |
| `TEMPORAL_PORT` | `7233` | Temporal frontend port |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace |

### Storage

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `STORAGE_TYPE` | `local` | `minio` / `s3` / `gcs` / `azure` / `local` |
| `STORAGE_ENDPOINT` | `http://localhost:9000` | MinIO / S3-compatible endpoint |
| `STORAGE_ACCESS_KEY` | `minioadmin` | Storage access key |
| `STORAGE_SECRET_KEY` | `minioadmin` | Storage secret key |
| `STORAGE_BUCKET` | `cia-documents` | Default bucket name |

### Integrations

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `KYC_PROVIDER` | `mock` | `dojah` / `prembly` / `nibss` / `mock` |
| `KYC_PROVIDER_URL` | — | KYC provider API endpoint (prod) |
| `NAICOM_API_URL` | — | NAICOM REST API endpoint (prod) |
| `NIID_API_URL` | — | NIID REST API endpoint (prod) |

### Notifications

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `NOTIFICATION_EMAIL_PROVIDER` | `log` | `sendgrid` / `ses` / `smtp` / `log` |
| `NOTIFICATION_SMS_PROVIDER` | `log` | `termii` / `twilio` / `log` |
| `SMTP_HOST` | `localhost` | SMTP host (when provider = `smtp`) |
| `SMTP_PORT` | `587` | SMTP port |

### Partner API

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `CIA_PARTNER_RATE_LIMIT_STORE` | `in-memory` | `redis` / `in-memory` — active `PartnerBucketStore` impl for per-client rate-limit buckets (`cia.partner.rate-limit.store`). The declarative `bucket4j` starter is disabled; `PartnerRateLimitFilter` owns rate limiting. |
| `REDIS_URL` | `redis://localhost:6379` | Redis connection (partner rate limiting + usage rollups + portal session/login-state store) |
| `CIA_PARTNER_USAGE_STORE` | `in-memory` | `redis` / `in-memory` — active `PartnerUsageRollupStore` impl for request telemetry rollups (`cia.partner-usage.store`), powering `/portal` usage. |
| `WEBHOOK_SIGNING_SECRET` | — | Default HMAC-SHA256 key for webhook payloads |
| `PII_ENCRYPTION_KEY` | `dev-pii-key-do-not-use-in-prod-CHANGE-ME` | pgcrypto symmetric key for NDPR PII encryption on `customers` + `customer_directors`. Loss = unrecoverable customer PII. Recommended: 32+ random bytes, base64-encoded. Set via env / vault in production. |

### Partner Portal BFF

The Partner Portal BFF (`cia-partner-portal-bff`) is a third auth plane — Insurtech developer
users only, authenticated via a `partner` Keycloak realm. The browser SPA never sees a Keycloak
token; the BFF is the OAuth2 client (token-handler pattern) and the browser carries only the
opaque `cia_portal_session` cookie.

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `CIA_PARTNER_PORTAL_REALM` | `partner` | Name of the Keycloak realm holding Insurtech developer users (`cia.partner-portal.realm`). Bound identically in cia-api and cia-auth — keep in sync. |
| `CIA_PARTNER_PORTAL_CLIENT_ID` | `cia-partner-portal` | Confidential Keycloak client id for the BFF's token-handler OAuth2 flow (`cia.partner-portal.client-id`). |
| `CIA_PARTNER_PORTAL_REDIRECT_URIS` | `http://localhost:8090/portal/auth/callback` | The BFF's own `/portal/auth/callback` endpoint — the value registered as the Keycloak client's `redirect_uri` (`cia.partner-portal.redirect-uris`, CSV). **Not** the SPA origin. |
| `CIA_PARTNER_PORTAL_ALLOWED_ORIGINS` | `http://localhost:5174` | CSV of browser origins allowed to call `/portal/**` cross-origin with credentials (`cia.partner-portal.allowed-origins`). `allowCredentials(true)` ⇒ no `*` wildcard. `/partner/**` stays CORS-free (M2M). |
| `CIA_PARTNER_PORTAL_STORE` | `in-memory` | `redis` / `in-memory` — selects `PortalSessionStore` + `PortalLoginStateStore` impl (`cia.partner-portal.store`). `redis` required for a multi-replica deployment. |
| `CIA_PARTNER_PORTAL_BOOTSTRAP_ENABLED` | `false` | Master switch for `PartnerPortalBootstrapRunner` (provisions the `partner` realm + first partner-developer admin on boot). Requires `KEYCLOAK_ADMIN_ENABLED=true`. |
| `CIA_PARTNER_PORTAL_BOOTSTRAP_ADMIN_USERNAME` / `_EMAIL` / `_TEMP_PASSWORD` | `partnerportaladmin` / `partnerportaladmin@cia.local` / — | First partner-developer admin's credentials, consumed when bootstrap is enabled. The temp password is a secret — forces `UPDATE_PASSWORD` on first login. |

### AI

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `ANTHROPIC_API_KEY` | — | Claude API key (optional; features off when absent) |

---

## Frontend (`cia-frontend`)

Vite exposes only variables prefixed with `VITE_` to the browser bundle.

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `VITE_API_BASE_URL` | `http://localhost:8090` | Spring Boot API base URL |
| `VITE_KEYCLOAK_URL` | `http://localhost:8180` | Keycloak server URL |
| `VITE_KEYCLOAK_REALM` | `tenant_dev` | Default Keycloak realm (overridden per tenant at runtime) |
| `VITE_KEYCLOAK_CLIENT_ID` | `cia-frontend` | Keycloak public client ID |

---

## Local Development

Copy the template and fill in any missing values:

```bash
cp .env.example .env
```

The `docker-compose.yml` services use their own hardcoded dev defaults. The `.env` file is only read by Spring Boot (via `application.yml` `${VAR:default}` syntax) and Vite.

---

## Production

Never commit secrets to the repository. Use:

- **Kubernetes:** `Secret` objects mounted as environment variables.
- **Cloud:** AWS Secrets Manager / GCP Secret Manager / Azure Key Vault.
- **Local prod-like:** HashiCorp Vault with Spring Cloud Vault Config.

All secret rotation must not require a restart — use dynamic secret providers where possible.
