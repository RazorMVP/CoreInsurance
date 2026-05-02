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
| `PARTNER_API_RATE_LIMIT_STORE` | `in-memory` | `redis` / `in-memory` for bucket4j |
| `REDIS_URL` | `redis://localhost:6379` | Redis connection (partner rate limiting) |
| `WEBHOOK_SIGNING_SECRET` | — | Default HMAC-SHA256 key for webhook payloads |
| `PII_ENCRYPTION_KEY` | `dev-pii-key-do-not-use-in-prod-CHANGE-ME` | pgcrypto symmetric key for NDPR PII encryption on `customers` + `customer_directors`. Loss = unrecoverable customer PII. Recommended: 32+ random bytes, base64-encoded. Set via env / vault in production. |

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
