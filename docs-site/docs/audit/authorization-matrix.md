---
id: authorization-matrix
title: Authorization Matrix
sidebar_label: Authorization Matrix
---

# Authorization Matrix

Last updated: 2026-05-07 02:31 Africa/Lagos

This matrix records the backend authorities and partner scopes enforced for the
Core Insurance Application. It is the Phase 2 decision record for endpoint
authorization.

## Authority Model

Human back-office users authenticate through Keycloak JWTs. Realm roles and
permission-style authorities are normalized by `JwtAuthConverter`:

| Keycloak grant | Backend authorities produced |
| --- | --- |
| `SETUP_VIEW` or `setup_view` | `ROLE_SETUP_VIEW`, `setup:view` |
| `setup:view` | `setup:view`, `ROLE_SETUP_VIEW` |
| `reports:view` | `reports:view`, `ROLE_REPORTS_VIEW` |
| OAuth2 scope `quotes:create` | `quotes:create`, `SCOPE_quotes:create` |

Back-office endpoints use Spring `@PreAuthorize`. Partner API endpoints use the
OAuth2 client-credentials token plus `PartnerScopeFilter`.

## Back-Office Routes

| Area | Endpoint pattern | Required authority |
| --- | --- | --- |
| Dashboard | `GET /api/v1/dashboard/**` | Authenticated user |
| Setup master data | `GET /api/v1/setup/**` | `SETUP_VIEW` |
| Setup create | `POST /api/v1/setup/**` | `SETUP_CREATE` |
| Setup update | `PUT /api/v1/setup/**` | `SETUP_UPDATE` |
| Setup delete | `DELETE /api/v1/setup/**` | `SETUP_DELETE` |
| Document templates | `GET /api/v1/document-templates/**` | `SETUP_VIEW` |
| Document template upload/delete | `POST, DELETE /api/v1/document-templates/**` | `SETUP_UPDATE` |
| Customers | `GET /api/v1/customers/**` | `CUSTOMER_VIEW` |
| Customer onboarding | `POST /api/v1/customers/**` | `CUSTOMER_CREATE` |
| Customer updates and documents | `PUT, PATCH, POST, DELETE /api/v1/customers/**` | `CUSTOMER_UPDATE` |
| Quotes | `GET /api/v1/quotes/**` | `QUOTATION_VIEW` |
| Quote create | `POST /api/v1/quotes` | `QUOTATION_CREATE` |
| Quote update/submit | `PUT /api/v1/quotes/{id}`, `POST /api/v1/quotes/{id}/submit` | `QUOTATION_UPDATE` |
| Quote approve/reject | `POST /api/v1/quotes/{id}/approve`, `/reject` | `QUOTATION_APPROVE` |
| Policies | `GET /api/v1/policies/**` | `UNDERWRITING_VIEW` |
| Policy create/bind | `POST /api/v1/policies`, `/bind-from-quote/{quoteId}` | `UNDERWRITING_CREATE` |
| Policy update/cancel/documents/regulatory upload/survey assign | `PUT, POST, DELETE /api/v1/policies/**` update paths | `UNDERWRITING_UPDATE` |
| Policy approve/reject/reinstate/survey approve | `POST /api/v1/policies/**` approval paths | `UNDERWRITING_APPROVE` |
| Endorsements | `GET /api/v1/endorsements/**` | `UNDERWRITING_VIEW` |
| Endorsement create/submit | `POST /api/v1/endorsements`, `/submit` | `UNDERWRITING_CREATE` |
| Endorsement cancel | `POST /api/v1/endorsements/{id}/cancel` | `UNDERWRITING_UPDATE` |
| Endorsement approve/reject | `POST /api/v1/endorsements/{id}/approve`, `/reject` | `UNDERWRITING_APPROVE` |
| Claims | `GET /api/v1/claims/**` | `CLAIMS_VIEW` |
| Claim register/submit/doc upload/expenses | `POST /api/v1/claims/**` create paths | `CLAIMS_CREATE` |
| Claim updates, comments, documents, reserves, inspection reports | `PATCH, POST, DELETE /api/v1/claims/**` update paths | `CLAIMS_UPDATE` |
| Claim approval, settlement, DV, inspection approval | `POST /api/v1/claims/**` approval paths | `CLAIMS_APPROVE` |
| Reinsurance | `GET /api/v1/reinsurance/**`, controller-specific RI paths | `REINSURANCE_VIEW` |
| Reinsurance create | RI `POST` create paths | `REINSURANCE_CREATE` |
| Reinsurance update/cancel/delete | RI update paths | `REINSURANCE_UPDATE` |
| Reinsurance approve | RI approval paths | `REINSURANCE_APPROVE` |
| Finance debit/credit notes | `GET /api/v1/debit-notes/**`, `GET /api/v1/credit-notes/**` | `FINANCE_VIEW` |
| Finance receipt/payment posting | `POST /api/v1/debit-notes/{id}/receipts`, `POST /api/v1/credit-notes/{id}/payments` | `FINANCE_CREATE` |
| Finance cancel/void/reverse | finance `POST /**/cancel`, `/void`, `/reverse` | `FINANCE_UPDATE` |
| Reports | `GET /api/v1/reports/**`, pins, JSON run | `reports:view` |
| Custom report definitions | `POST, PUT, DELETE /api/v1/reports/definitions/**`, clone | `reports:create_custom` |
| Report CSV export | `POST /api/v1/reports/run/csv` | `reports:export_csv` |
| Report PDF export | `POST /api/v1/reports/run/pdf` | `reports:export_pdf` |
| Report access policies | `GET, PUT /api/v1/reports/access-policies` | `reports:manage_access` |
| Audit logs, alerts, reports, CSV export | `GET /api/v1/audit/**`, acknowledge alerts | `AUDIT_VIEW` or `SETUP_UPDATE` |
| Audit alert config read | `GET /api/v1/setup/audit-config` | `AUDIT_VIEW` or `SETUP_UPDATE` |
| Audit alert config update | `PUT /api/v1/setup/audit-config` | `SETUP_UPDATE` |
| Session start/end | `POST /api/v1/auth/session/start`, `/session/end` | Authenticated user |
| Failed-login audit | `POST /api/v1/auth/login/failed` | Public by design, explicitly annotated `permitAll()` |
| Tenant provisioning | `POST /admin/v1/tenants` | `PLATFORM_ADMIN`; may run without `tenant_id` because it creates tenant context |
| Partner app administration | `GET /api/v1/setup/partner-apps/**` | `setup:view` |
| Partner app create | `POST /api/v1/setup/partner-apps` | `setup:create` |
| Partner app update/rotate/revoke | `PUT, POST /api/v1/setup/partner-apps/**` | `setup:update` |

## Partner API Scopes

| Endpoint | Required scope |
| --- | --- |
| `GET /partner/v1/products`, `/products/{id}`, `/products/{id}/classes` | `products:read` |
| `POST /partner/v1/quotes` | `quotes:create` |
| `GET /partner/v1/quotes/{id}` | `quotes:read` |
| `POST /partner/v1/customers/individual`, `/corporate` | `customers:create` |
| `GET /partner/v1/customers/{id}` | `customers:read` |
| `POST /partner/v1/policies` | `policies:create` |
| `GET /partner/v1/policies/{id}`, `/policies/{id}/document` | `policies:read` |
| `POST /partner/v1/policies/{id}/claims` | `claims:create` |
| `GET /partner/v1/claims/{id}` | `claims:read` |
| `POST, GET /partner/v1/webhooks`, `DELETE /partner/v1/webhooks/{id}` | `webhooks:manage` |

## Regression Coverage

| Test | Purpose |
| --- | --- |
| `MethodSecurityConfigTest` | Proves method security allows correct roles/authorities and denies wrong ones outside `dev`. |
| `JwtAuthConverterTest` | Proves Keycloak role, permission, and scope normalization. |
| `TenantContextFilterTest` | Proves authenticated requests require a resolvable tenant claim, except platform tenant provisioning which is authorized separately. |
| `TenantProvisioningControllerAuthorizationTest` | Proves HTTP access to platform tenant provisioning is limited to `PLATFORM_ADMIN` and rejected roles cannot invoke provisioning. |
| `ReportControllerAuthorizationTest` | Proves HTTP access to a real reports endpoint returns `200` with `reports:view` and `403` with the wrong role. |
| `PartnerScopeFilterTest` | Proves partner routes resolve the correct scope and reject missing auth or missing scope. |
| `ControllerAuthorizationCoverageTest` | Fails the build if a new back-office REST handler lacks explicit `@PreAuthorize`. |
