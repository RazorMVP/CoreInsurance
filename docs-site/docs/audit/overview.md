---
id: overview
title: Audit & Compliance
sidebar_label: Overview
---

# Audit & Compliance (Module 10)

The Audit & Compliance module provides a read-only, tamper-evident view of everything that happens inside the CIA platform — every data change, every approval, every login, every anomaly.

## Who Uses This Module

| Role | Access |
| --- | --- |
| **System Auditor** (`AUDIT_VIEW`) | Read-only access to all audit data — logs, login records, reports, alerts |
| **System Admin** (`SETUP_UPDATE`) | Same read access plus the ability to configure alert thresholds and retention |

The System Auditor role is intentionally separate from all other roles. Auditors cannot create, approve, or modify any business data.

## Features

### Audit Log Viewer

A filterable, paginated view of every write operation across all 9 business modules:

- **Filter by:** entity type (e.g. `Policy`, `Claim`), entity ID, user, action, or date range
- **Actions tracked:** CREATE, UPDATE, DELETE, APPROVE, REJECT, SUBMIT, SEND, CANCEL, REVERSE, EXECUTE
- **Each entry includes:** before/after JSON snapshots, IP address, timestamp, approval amount

**Endpoint:** `GET /api/v1/audit/logs`

### Login & Session Logs

A dedicated log of every authentication event:

| Event | Trigger |
| --- | --- |
| `LOGIN` | Successful Keycloak authentication |
| `LOGOUT` | User-initiated logout |
| `LOGIN_FAILED` | JWT validation failure — no valid token |
| `PASSWORD_RESET` | Keycloak password reset |
| `ACCOUNT_LOCKED` | Account locked after repeated failures |

**Endpoint:** `GET /api/v1/audit/login-logs`

### Real-Time Alerts

Four automated anomaly detectors run asynchronously after every audit event:

| Alert | Default Threshold | Configurable |
| --- | --- | --- |
| **Failed Login** | ≥ 3 consecutive failures for one user | Yes |
| **Bulk Delete** | ≥ 5 DELETE actions within 5 minutes by one user | Yes |
| **Off-Hours Activity** | APPROVE or CREATE outside 08:00–18:00 Mon–Fri (Africa/Lagos) | Yes |
| **Large Financial Approval** | Approval amount ≥ ₦50,000,000 | Yes |

Alerts appear in-app and are sent by email via `NotificationService`. An auditor or admin can acknowledge them at `POST /api/v1/audit/alerts/{id}/acknowledge`.

### CSV Export

Exports the audit log as a streaming CSV file, applying the same filters as the log viewer. Suitable for regulatory submissions and offline analysis.

**Endpoint:** `GET /api/v1/audit/export`

### Six Pre-Built Reports

| # | Report | Endpoint |
| --- | --- | --- |
| 1 | All actions by a specific user in a date range | `GET /api/v1/audit/reports/actions-by-user` |
| 2 | All actions within a specific module (entity type) | `GET /api/v1/audit/reports/actions-by-module` |
| 3 | All approvals and rejections across all modules | `GET /api/v1/audit/reports/approvals` |
| 4 | Full before/after change history for a specific entity | `GET /api/v1/audit/reports/data-changes` |
| 5 | Login and security events in a date range | `GET /api/v1/audit/reports/login-security` |
| 6 | Ranked user activity summary (by action count) | `GET /api/v1/audit/reports/user-activity` |

### Alert Configuration

System Admins can configure all alert thresholds, business hours, and data retention period via `PUT /api/v1/setup/audit-config`. A GET endpoint allows Auditors to view the current config.

Configuration is per-tenant, singleton (one row per schema), and takes effect immediately on the next audit event.

## Data Retention

Default: **7 years** (NAICOM regulatory requirement). Configurable per tenant. Both `audit_log` and `login_audit_log` are governed by the same retention period. A Temporal purge workflow enforces the policy on schedule.

## Database Tables

| Table | Purpose |
| --- | --- |
| `audit_log` | All business write events (entity snapshots, actions, approval amounts) |
| `login_audit_log` | Authentication events per user |
| `audit_alert` | Fired alert records with acknowledgement tracking |
| `audit_alert_config` | Singleton per tenant — all thresholds and retention config |

## Technical Notes

- `AlertDetectionService` runs `@Async` — alert detection never blocks the calling request thread.
- `cia-audit` depends only on `cia-common` and `cia-notifications` — it has zero dependency on any business module.
- All audit events flow into `cia-audit` via Spring `ApplicationEvent` (`AuditLogCreatedEvent`) published by `AuditService`.
- Login events flow in directly via controller calls to `LoginAuditService`.
