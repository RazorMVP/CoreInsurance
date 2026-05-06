---
id: environment-variables
title: Environment Variables
sidebar_label: Environment Variables
---

## Backend (`cia-api`)

Most variables have defaults for local development. `SPRING_PROFILES_ACTIVE`
must always be supplied explicitly. Production values must be supplied via
Kubernetes Secrets or a vault.

### Core

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | — | Required. Use `dev` locally, `test` for automated tests, and deployment profiles such as `staging` or `prod` outside local development. |
| `CIA_ENV` | `local` | Runtime environment label used by startup safety checks. Production-like values include `staging`, `uat`, `preprod`, `prod`, and `production`. |
| `SERVER_PORT` | `8090` | HTTP port the Spring Boot API listens on |
| `DB_URL` | `jdbc:postgresql://localhost:5434/cia` | PostgreSQL JDBC URL |
| `DB_USERNAME` | `cia` | PostgreSQL user |
| `DB_PASSWORD` | `cia_dev` | PostgreSQL password |
| `KEYCLOAK_URL` | `http://localhost:8280` | Keycloak server base URL |

### Temporal

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `TEMPORAL_HOST` | `localhost:7233` | Temporal frontend host and port |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace |

### Storage

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `STORAGE_TYPE` | `minio` | `minio` / `s3` |
| `STORAGE_ENDPOINT` | `http://localhost:9000` | MinIO / S3-compatible endpoint |
| `STORAGE_ACCESS_KEY` | `minioadmin` | Storage access key |
| `STORAGE_SECRET_KEY` | `minioadmin` | Storage secret key |
| `STORAGE_BUCKET` | `cia-documents` | Default bucket name |

### Integrations

| Variable | Default (dev) | Description |
| --- | --- | --- |
| `KYC_PROVIDER` | `mock` | `dojah` / `prembly`; `mock` is dev/test only |
| `KYC_PROVIDER_URL` | — | KYC provider API endpoint (prod) |
| `NAICOM_MODE` | `stub` | `stub` locally; must be `live` outside dev/test |
| `NAICOM_API_URL` | — | NAICOM REST API endpoint (prod) |
| `NIID_MODE` | `stub` | `stub` locally; must be `live` outside dev/test |
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
| `REDIS_HOST` | `localhost` | Redis host for partner rate limiting |
| `REDIS_PORT` | `6380` | Redis port for partner rate limiting |
| `PARTNER_TOKEN_URL` | `http://localhost:8280/realms/cia/protocol/openid-connect/token` | Partner OAuth2 client-credentials token endpoint |
| `WEBHOOK_SIGNING_SECRET` | `dev-secret-replace-in-prod` | HMAC-SHA256 key for webhook payloads |
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
| `VITE_KEYCLOAK_URL` | `http://localhost:8280` | Keycloak server URL |
| `VITE_KEYCLOAK_REALM` | `cia-dev` | Default Keycloak realm |
| `VITE_KEYCLOAK_CLIENT_ID` | `cia-back-office` | Keycloak public client ID |

---

## Local Development

No env file is required for the default local stack. `docker-compose.yml`,
`application.yml`, and the Vite apps all provide development defaults. To
override them, export backend variables in your shell before `spring-boot:run`,
or create an app-local Vite file such as
`cia-frontend/apps/back-office/.env.local`.

---

## Production

Never commit secrets to the repository. Use:

- **Kubernetes:** `Secret` objects mounted as environment variables.
- **Cloud:** AWS Secrets Manager / GCP Secret Manager / Azure Key Vault.
- **Local prod-like:** HashiCorp Vault with Spring Cloud Vault Config.

All secret rotation must not require a restart — use dynamic secret providers where possible.

Production-like profiles fail startup if they use local defaults such as the
dev PII key, dev webhook secret, mock KYC, stub NAICOM/NIID modes, localhost
JWT/database/storage endpoints, or missing provider URLs. This is intentional:
deployment configuration must be supplied explicitly through the target
environment or secret manager.
