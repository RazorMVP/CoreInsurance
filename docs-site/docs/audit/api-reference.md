---
id: api-reference
title: Audit API Reference
sidebar_label: API Reference
---

# Audit & Compliance API Reference

All endpoints are under the `/api/v1/` base path. Authentication requires a Keycloak JWT Bearer token. Roles: `AUDIT_VIEW` (read-only) or `SETUP_UPDATE` (read + config write).

For an interactive explorer, see the [Internal API Reference](/internal/api-reference).

---

## Audit Log

### `GET /api/v1/audit/logs`

Search the full system audit trail.

**Required role:** `AUDIT_VIEW` or `SETUP_UPDATE`

**Query parameters:**

| Parameter | Type | Description |
| --- | --- | --- |
| `entityType` | string | e.g. `Policy`, `Claim`, `Customer` |
| `entityId` | UUID | Specific entity ID |
| `userId` | string | Keycloak subject (sub) of the acting user |
| `action` | string | One of `CREATE`, `UPDATE`, `DELETE`, `APPROVE`, `REJECT`, `SUBMIT`, `SEND`, `CANCEL`, `REVERSE`, `EXECUTE` |
| `from` | ISO 8601 datetime | Start of date range |
| `to` | ISO 8601 datetime | End of date range |
| `page` | integer | 0-indexed page number (default: 0) |
| `size` | integer | Page size (default: 20) |

**Example:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8090/api/v1/audit/logs?entityType=Policy&action=APPROVE&from=2026-01-01T00:00:00Z"
```

---

## Login & Session Logs

### `POST /api/v1/auth/session/start` *(requires JWT)*

Called by the frontend after successful Keycloak login. Records a `LOGIN` event.

### `POST /api/v1/auth/session/end` *(requires JWT)*

Called by the frontend on logout. Records a `LOGOUT` event.

### `POST /api/v1/auth/login/failed` *(public — no JWT)*

Records a `LOGIN_FAILED` event and checks the failed-login alert threshold. Called when JWT validation fails.

**Query parameters:** `userId` (required), `userName` (optional)

### `GET /api/v1/audit/login-logs`

**Required role:** `AUDIT_VIEW` or `SETUP_UPDATE`

**Query parameters:** `userId`, `from`, `to`, `page`, `size`

---

## Alerts

### `GET /api/v1/audit/alerts`

**Required role:** `AUDIT_VIEW` or `SETUP_UPDATE`

| Parameter | Type | Description |
| --- | --- | --- |
| `unacknowledgedOnly` | boolean | Return only unacknowledged alerts (default: `false`) |
| `page`, `size` | integer | Pagination |

### `POST /api/v1/audit/alerts/{id}/acknowledge`

Marks an alert as acknowledged. Records the acknowledging user.

**Required role:** `AUDIT_VIEW` or `SETUP_UPDATE`

---

## Alert Configuration

### `GET /api/v1/setup/audit-config`

**Required role:** `AUDIT_VIEW` or `SETUP_UPDATE`

### `PUT /api/v1/setup/audit-config`

**Required role:** `SETUP_UPDATE` (System Admin only)

**Request body:**

```json
{
  "businessHoursStart": "08:00",
  "businessHoursEnd": "18:00",
  "businessDays": "MON,TUE,WED,THU,FRI",
  "largeApprovalThreshold": 50000000,
  "maxFailedLoginAttempts": 3,
  "bulkDeleteCount": 5,
  "bulkDeleteWindowMinutes": 5,
  "retentionYears": 7
}
```

---

## CSV Export

### `GET /api/v1/audit/export`

**Required role:** `AUDIT_VIEW` or `SETUP_UPDATE`

**Produces:** `text/csv` with `Content-Disposition: attachment`

Accepts the same filter parameters as `GET /api/v1/audit/logs`.

**Example:**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8090/api/v1/audit/export?entityType=Claim&from=2026-01-01T00:00:00Z" \
  -o audit-export.csv
```

---

## Reports

All report endpoints require `AUDIT_VIEW` or `SETUP_UPDATE` role.

| Endpoint | Required params | Description |
| --- | --- | --- |
| `GET /api/v1/audit/reports/actions-by-user` | `userId` | All actions by a user |
| `GET /api/v1/audit/reports/actions-by-module` | `entityType` | All actions in a module |
| `GET /api/v1/audit/reports/approvals` | none | All approvals & rejections |
| `GET /api/v1/audit/reports/data-changes` | `entityType`, `entityId` | Before/after history for an entity |
| `GET /api/v1/audit/reports/login-security` | `from`, `to` | Authentication events |
| `GET /api/v1/audit/reports/user-activity` | `from`, `to` | Ranked user action counts |
