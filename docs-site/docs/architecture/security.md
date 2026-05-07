---
id: security
title: Security
sidebar_label: Security
---

# Security

## Authentication Flow

CIA uses **Keycloak** as the OAuth2 authorization server with JWT (RS256) tokens.

```text
1. User visits tenant subdomain → React SPA loads
2. Keycloak JS adapter: no session → redirect to Keycloak login (tenant realm)
3. User authenticates → Keycloak issues RS256 JWT
   Claims: sub (user_id), realm_access.roles, tenant_id (custom claim)
4. React attaches JWT as Authorization: Bearer on every API request
5. Spring Security validates JWT signature using Keycloak JWKS (cached, auto-refreshed)
6. JwtAuthConverter maps roles, permissions, and scopes → Spring GrantedAuthority list
7. TenantContextFilter reads tenant_id claim → sets schema ThreadLocal
8. @PreAuthorize on controllers enforces authority requirements per endpoint
9. Hibernate routes all queries to the correct tenant schema
```

## Role-Based Access Control

Method-level authorization is enabled outside the local `dev` profile. The
`dev` profile remains intentionally permissive for local setup only; production
startup guardrails prevent production-like environments from running with
`dev`.

Keycloak realm roles and permission-style authorities are normalized both ways
so the system can safely support the legacy role-style controllers and the
newer permission-style report/setup checks:

| Keycloak Role | Spring Authority | Usage |
| --- | --- | --- |
| `{module}_create` | `{module}:create`, `ROLE_{MODULE}_CREATE` | POST endpoints |
| `{module}_view` | `{module}:view`, `ROLE_{MODULE}_VIEW` | GET endpoints |
| `{module}_update` | `{module}:update`, `ROLE_{MODULE}_UPDATE` | PUT / PATCH endpoints |
| `{module}_approve` | `{module}:approve`, `ROLE_{MODULE}_APPROVE` | Approval actions |
| `audit_view` or `audit:view` | `audit:view`, `ROLE_AUDIT_VIEW` | System Auditor — read-only access to all audit data |
| `setup_update` or `setup:update` | `setup:update`, `ROLE_SETUP_UPDATE` | System Admin — full setup + audit config |
| `reports:view` | `reports:view`, `ROLE_REPORTS_VIEW` | Permission-style report access |

OAuth2 scopes from `scope` or `scp` claims map to both raw scope authorities
and `SCOPE_*` authorities, for example `quotes:create` and
`SCOPE_quotes:create`.

Access groups in Keycloak aggregate roles and permission-style authorities.
Users inherit permissions through their assigned access group.

The [Authorization Matrix](../audit/authorization-matrix) lists the current
endpoint requirements and the regression tests that keep this coverage from
silently drifting.

## Audit Trail

Every write operation records to a per-tenant `audit_log` table:

```sql
audit_log (
  id UUID, entity_type VARCHAR, entity_id VARCHAR,
  action       -- CREATE | UPDATE | DELETE | APPROVE | REJECT | SUBMIT | SEND | CANCEL | REVERSE | EXECUTE
  user_id VARCHAR, user_name VARCHAR,
  timestamp TIMESTAMPTZ,
  old_value JSONB,   -- entity snapshot before change
  new_value JSONB,   -- entity snapshot after change
  ip_address VARCHAR, approval_amount NUMERIC
)
```

`AuditService.log()` is called by every service write method. Immediately after saving, it publishes an `AuditLogCreatedEvent` consumed by `AlertDetectionService` for real-time anomaly detection.

## Real-Time Alerts

`AlertDetectionService` runs `@Async` so it never blocks the request thread. It fires alerts for:

| Alert | Trigger |
| --- | --- |
| `FAILED_LOGIN` | ≥3 consecutive login failures for the same user |
| `BULK_DELETE` | ≥5 DELETE actions within any 5-minute window by one user |
| `OFF_HOURS_ACTIVITY` | APPROVE or CREATE actions outside 08:00–18:00 Mon–Fri (Africa/Lagos) |
| `LARGE_FINANCIAL_APPROVAL` | Approval amount ≥ ₦50,000,000 |

All thresholds are configurable per tenant via `GET/PUT /api/v1/setup/audit-config` (System Admin only).

Alerts fire in-app and email notifications via `NotificationService`. Auditors can acknowledge alerts via `POST /api/v1/audit/alerts/{id}/acknowledge`.

## Login & Session Logging

`LoginAuditLog` records every authentication event:

| Event | Trigger |
| --- | --- |
| `LOGIN` | Frontend calls `POST /api/v1/auth/session/start` after Keycloak auth |
| `LOGOUT` | Frontend calls `POST /api/v1/auth/session/end` |
| `LOGIN_FAILED` | Backend calls `POST /api/v1/auth/login/failed` on JWT validation failure |
| `PASSWORD_RESET` | Recorded on Keycloak password reset events |
| `ACCOUNT_LOCKED` | Recorded when Keycloak locks an account after repeated failures |

`/api/v1/auth/session/start` and `/api/v1/auth/session/end` require an authenticated JWT. `/api/v1/auth/login/failed` is intentionally public and explicitly annotated `permitAll()` because it records failed authentication before a valid JWT exists.

## Data Protection (NDPR)

- PII fields (name, DOB, NIN, address, email) encrypted at rest via PostgreSQL `pgcrypto`.
- All data access logged to the per-tenant audit table.
- Data retention period configurable per tenant (default: 7 years, per NAICOM requirements).
- NDPR data subject access request export available.
- Data purge workflow runs on schedule via Temporal.

## Partner API Security

Insurtech partners authenticate machine-to-machine using **OAuth2 Client Credentials** (no human login):

```http
POST /realms/{tenant}/protocol/openid-connect/token
grant_type=client_credentials&client_id=...&client_secret=...
```

The `PartnerScopeFilter` checks the required scope per endpoint (e.g., `quotes:create`). Rate limiting via **bucket4j** (token bucket) enforces per-partner limits (60 / 300 / 1000 rpm). Webhook payloads are signed with HMAC-SHA256 (`X-CIA-Signature` header); partners must verify signatures and reject payloads older than 5 minutes.

## Audit Retention

Default retention: **7 years** (configurable per tenant). Both `audit_log` and `login_audit_log` rows are retained for this period. A Temporal purge workflow enforces the policy on schedule.
