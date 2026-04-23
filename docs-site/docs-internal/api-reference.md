---
id: api-reference
title: Internal API Reference
sidebar_label: API Reference
---

# Internal API Reference

All endpoints use the base path `https://api.cia.app/api/v1` in production. For local development use `http://localhost:8090/api/v1`.

**Authentication:** Keycloak JWT Bearer token — `Authorization: Bearer <token>` — required on all endpoints except `POST /api/v1/auth/login/failed`.  
**Tenant context:** Resolved automatically from the `tenant_id` claim in the JWT. Never passed in the request body.  
**Response envelope:** `{ "data": ..., "meta": { "page": 0, "size": 20, "totalElements": N }, "errors": [...] }`  
**Pagination:** Query params `page` (0-indexed, default `0`) and `size` (default `20`) on all list endpoints.

---

## 1. Setup & Administration

**Required roles:** `SETUP_UPDATE` for all writes. Most reads are accessible to any authenticated user.

### Company Settings

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/company-settings` | Get the tenant's company profile |
| `PUT` | `/api/v1/setup/company-settings` | Update company name, logo, address, and contact details |

### Strategic Business Units

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/sbus` | List all SBUs |
| `POST` | `/api/v1/setup/sbus` | Create an SBU |
| `GET` | `/api/v1/setup/sbus/{id}` | Get SBU details |
| `PUT` | `/api/v1/setup/sbus/{id}` | Update an SBU |
| `DELETE` | `/api/v1/setup/sbus/{id}` | Deactivate an SBU (soft delete) |

### Branches

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/branches` | List all branches |
| `POST` | `/api/v1/setup/branches` | Create a branch |
| `GET` | `/api/v1/setup/branches/{id}` | Get branch details |
| `PUT` | `/api/v1/setup/branches/{id}` | Update a branch |
| `DELETE` | `/api/v1/setup/branches/{id}` | Deactivate a branch (soft delete) |

### Products

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/products` | List all insurance products |
| `POST` | `/api/v1/setup/products` | Create a new product |
| `GET` | `/api/v1/setup/products/{id}` | Get product details |
| `PUT` | `/api/v1/setup/products/{id}` | Update a product |
| `DELETE` | `/api/v1/setup/products/{id}` | Deactivate a product (soft delete) |
| `GET` | `/api/v1/setup/products/{productId}/policy-number-format` | Get the policy number format for a product |
| `PUT` | `/api/v1/setup/products/{productId}/policy-number-format` | Set the policy number format |
| `GET` | `/api/v1/setup/products/{productId}/policy-specification` | Get the product's risk specification (field definitions) |
| `PUT` | `/api/v1/setup/products/{productId}/policy-specification` | Update the risk specification |
| `GET` | `/api/v1/setup/products/{productId}/commission-setups` | List commission configurations |
| `POST` | `/api/v1/setup/products/{productId}/commission-setups` | Add a commission configuration |
| `GET` | `/api/v1/setup/products/{productId}/commission-setups/{id}` | Get a commission configuration |
| `PUT` | `/api/v1/setup/products/{productId}/commission-setups/{id}` | Update a commission configuration |
| `DELETE` | `/api/v1/setup/products/{productId}/commission-setups/{id}` | Remove a commission configuration |
| `GET` | `/api/v1/setup/products/{productId}/survey-thresholds` | List survey amount thresholds |
| `POST` | `/api/v1/setup/products/{productId}/survey-thresholds` | Add a survey threshold |
| `GET` | `/api/v1/setup/products/{productId}/survey-thresholds/{id}` | Get a survey threshold |
| `PUT` | `/api/v1/setup/products/{productId}/survey-thresholds/{id}` | Update a survey threshold |
| `DELETE` | `/api/v1/setup/products/{productId}/survey-thresholds/{id}` | Remove a survey threshold |
| `GET` | `/api/v1/setup/products/{productId}/claim-document-requirements` | List required claim documents for a product |
| `POST` | `/api/v1/setup/products/{productId}/claim-document-requirements` | Add a claim document requirement |
| `GET` | `/api/v1/setup/products/{productId}/claim-document-requirements/{id}` | Get a requirement |
| `PUT` | `/api/v1/setup/products/{productId}/claim-document-requirements/{id}` | Update a requirement |
| `DELETE` | `/api/v1/setup/products/{productId}/claim-document-requirements/{id}` | Remove a requirement |
| `GET` | `/api/v1/setup/products/{productId}/claim-notification-timeline` | Get the claim notification timeline |
| `PUT` | `/api/v1/setup/products/{productId}/claim-notification-timeline` | Update the claim notification timeline |

### Classes of Business

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/classes-of-business` | List all classes of business |
| `POST` | `/api/v1/setup/classes-of-business` | Create a class of business |
| `GET` | `/api/v1/setup/classes-of-business/{id}` | Get class details |
| `PUT` | `/api/v1/setup/classes-of-business/{id}` | Update a class of business |
| `DELETE` | `/api/v1/setup/classes-of-business/{id}` | Deactivate a class of business (soft delete) |

### Insurance Companies

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/insurance-companies` | List co-insurance partner companies |
| `POST` | `/api/v1/setup/insurance-companies` | Register a co-insurance company |
| `GET` | `/api/v1/setup/insurance-companies/{id}` | Get company details |
| `PUT` | `/api/v1/setup/insurance-companies/{id}` | Update a company record |
| `DELETE` | `/api/v1/setup/insurance-companies/{id}` | Deactivate a company record (soft delete) |

### Reinsurance Companies

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/reinsurance-companies` | List reinsurance companies |
| `POST` | `/api/v1/setup/reinsurance-companies` | Register a reinsurance company |
| `GET` | `/api/v1/setup/reinsurance-companies/{id}` | Get reinsurer details |
| `PUT` | `/api/v1/setup/reinsurance-companies/{id}` | Update a reinsurer record |
| `DELETE` | `/api/v1/setup/reinsurance-companies/{id}` | Deactivate a reinsurer record (soft delete) |

### Brokers

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/brokers` | List all brokers |
| `POST` | `/api/v1/setup/brokers` | Register a broker |
| `GET` | `/api/v1/setup/brokers/{id}` | Get broker details |
| `PUT` | `/api/v1/setup/brokers/{id}` | Update a broker record |
| `DELETE` | `/api/v1/setup/brokers/{id}` | Deactivate a broker (soft delete) |

### Surveyors

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/surveyors` | List all surveyors |
| `POST` | `/api/v1/setup/surveyors` | Register a surveyor |
| `GET` | `/api/v1/setup/surveyors/{id}` | Get surveyor details |
| `PUT` | `/api/v1/setup/surveyors/{id}` | Update a surveyor record |
| `DELETE` | `/api/v1/setup/surveyors/{id}` | Deactivate a surveyor (soft delete) |

### Relationship Managers

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/relationship-managers` | List all relationship managers |
| `POST` | `/api/v1/setup/relationship-managers` | Create a relationship manager record |
| `GET` | `/api/v1/setup/relationship-managers/{id}` | Get relationship manager details |
| `GET` | `/api/v1/setup/relationship-managers/by-branch/{branchId}` | List relationship managers for a branch |
| `PUT` | `/api/v1/setup/relationship-managers/{id}` | Update a relationship manager record |
| `DELETE` | `/api/v1/setup/relationship-managers/{id}` | Deactivate a relationship manager (soft delete) |

### Access Groups

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/access-groups` | List all access groups (RBAC role bundles) |
| `POST` | `/api/v1/setup/access-groups` | Create an access group |
| `GET` | `/api/v1/setup/access-groups/{id}` | Get access group details and assigned roles |
| `PUT` | `/api/v1/setup/access-groups/{id}` | Update an access group |
| `DELETE` | `/api/v1/setup/access-groups/{id}` | Delete an access group |

### Approval Groups

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/approval-groups` | List all approval groups |
| `POST` | `/api/v1/setup/approval-groups` | Create an approval group with amount ranges |
| `GET` | `/api/v1/setup/approval-groups/{id}` | Get approval group details |
| `GET` | `/api/v1/setup/approval-groups/by-entity-type/{entityType}` | Get approval groups for a given entity type |
| `PUT` | `/api/v1/setup/approval-groups/{id}` | Update an approval group |
| `DELETE` | `/api/v1/setup/approval-groups/{id}` | Delete an approval group |

### Currencies

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/currencies` | List supported currencies |
| `POST` | `/api/v1/setup/currencies` | Add a currency |
| `GET` | `/api/v1/setup/currencies/{id}` | Get currency details |
| `PUT` | `/api/v1/setup/currencies/{id}` | Update a currency record |
| `DELETE` | `/api/v1/setup/currencies/{id}` | Remove a currency |

### Banks

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/banks` | List all banks |
| `POST` | `/api/v1/setup/banks` | Add a bank |
| `GET` | `/api/v1/setup/banks/{id}` | Get bank details |
| `PUT` | `/api/v1/setup/banks/{id}` | Update a bank record |
| `DELETE` | `/api/v1/setup/banks/{id}` | Remove a bank |

### Vehicles (Motor)

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/vehicle-makes` | List all vehicle makes |
| `POST` | `/api/v1/setup/vehicle-makes` | Add a vehicle make |
| `GET` | `/api/v1/setup/vehicle-makes/{id}` | Get vehicle make details |
| `PUT` | `/api/v1/setup/vehicle-makes/{id}` | Update a vehicle make |
| `DELETE` | `/api/v1/setup/vehicle-makes/{id}` | Remove a vehicle make |
| `GET` | `/api/v1/setup/vehicle-makes/{makeId}/models` | List models for a vehicle make |
| `POST` | `/api/v1/setup/vehicle-makes/{makeId}/models` | Add a model to a make |
| `GET` | `/api/v1/setup/vehicle-makes/{makeId}/models/{id}` | Get vehicle model details |
| `PUT` | `/api/v1/setup/vehicle-makes/{makeId}/models/{id}` | Update a vehicle model |
| `DELETE` | `/api/v1/setup/vehicle-makes/{makeId}/models/{id}` | Remove a vehicle model |
| `GET` | `/api/v1/setup/vehicle-types` | List vehicle types (e.g. Saloon, SUV, Truck) |
| `POST` | `/api/v1/setup/vehicle-types` | Add a vehicle type |
| `GET` | `/api/v1/setup/vehicle-types/{id}` | Get vehicle type details |
| `PUT` | `/api/v1/setup/vehicle-types/{id}` | Update a vehicle type |
| `DELETE` | `/api/v1/setup/vehicle-types/{id}` | Remove a vehicle type |

### Claims Reference Data

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/nature-of-loss` | List all natures of loss |
| `POST` | `/api/v1/setup/nature-of-loss` | Create a nature of loss |
| `GET` | `/api/v1/setup/nature-of-loss/{id}` | Get nature of loss details |
| `PUT` | `/api/v1/setup/nature-of-loss/{id}` | Update a nature of loss |
| `DELETE` | `/api/v1/setup/nature-of-loss/{id}` | Remove a nature of loss |
| `GET` | `/api/v1/setup/cause-of-loss` | List all causes of loss |
| `POST` | `/api/v1/setup/cause-of-loss` | Create a cause of loss |
| `GET` | `/api/v1/setup/cause-of-loss/{id}` | Get cause of loss details |
| `GET` | `/api/v1/setup/cause-of-loss/by-nature/{natureOfLossId}` | List causes of loss for a given nature of loss |
| `PUT` | `/api/v1/setup/cause-of-loss/{id}` | Update a cause of loss |
| `DELETE` | `/api/v1/setup/cause-of-loss/{id}` | Remove a cause of loss |
| `GET` | `/api/v1/setup/claim-reserve-categories` | List reserve categories |
| `POST` | `/api/v1/setup/claim-reserve-categories` | Create a reserve category |
| `GET` | `/api/v1/setup/claim-reserve-categories/{id}` | Get reserve category details |
| `PUT` | `/api/v1/setup/claim-reserve-categories/{id}` | Update a reserve category |
| `DELETE` | `/api/v1/setup/claim-reserve-categories/{id}` | Remove a reserve category |

### Document Templates

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/document-templates` | List all document templates (policy, endorsement, DV) |
| `POST` | `/api/v1/document-templates` | Upload a new document template |
| `GET` | `/api/v1/document-templates/{id}` | Get template details |
| `DELETE` | `/api/v1/document-templates/{id}` | Delete a template |

### Audit Alert Configuration

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/setup/audit-config` | Get the current alert thresholds and retention settings |
| `PUT` | `/api/v1/setup/audit-config` | Update alert thresholds, business hours, and retention period — `SETUP_UPDATE` only |

### Partner App Management

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/partner-apps` | List all registered Insurtech partner apps |
| `POST` | `/api/v1/partner-apps` | Register a new partner app (creates Keycloak client) |
| `GET` | `/api/v1/partner-apps/{id}` | Get partner app details and allowed scopes |
| `PATCH` | `/api/v1/partner-apps/{id}/activate` | Enable or disable a partner app |
| `DELETE` | `/api/v1/partner-apps/{id}` | Revoke a partner app (soft delete, removes Keycloak client) |

---

## 2. Customer Onboarding

**Required roles:** `customers:create`, `customers:view`, `customers:update`

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/customers` | List all customers (paginated) |
| `GET` | `/api/v1/customers/search` | Search customers by name, email, or ID number |
| `POST` | `/api/v1/customers/individual` | Register an individual customer (triggers KYC verification) |
| `POST` | `/api/v1/customers/corporate` | Register a corporate customer (triggers KYC verification) |
| `GET` | `/api/v1/customers/{id}` | Get customer profile and KYC status |
| `PUT` | `/api/v1/customers/{id}` | Update customer details |
| `POST` | `/api/v1/customers/{id}/retrigger-kyc` | Re-submit KYC verification after a failed attempt |
| `POST` | `/api/v1/customers/{id}/blacklist` | Blacklist a customer |
| `DELETE` | `/api/v1/customers/{id}/blacklist` | Remove a customer from the blacklist |
| `GET` | `/api/v1/customers/{customerId}/documents` | List KYC documents uploaded for a customer |
| `POST` | `/api/v1/customers/{customerId}/documents` | Upload a KYC document |
| `DELETE` | `/api/v1/customers/{customerId}/documents/{documentId}` | Delete a KYC document |

---

## 3. Quotation

**Required roles:** `underwriting:create`, `underwriting:view`, `underwriting:approve`

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/quotes` | List all quotes (paginated) |
| `GET` | `/api/v1/quotes/search` | Search quotes by customer, product, or status |
| `POST` | `/api/v1/quotes` | Create a new quote |
| `GET` | `/api/v1/quotes/{id}` | Get quote details and premium breakdown |
| `PUT` | `/api/v1/quotes/{id}` | Update a draft quote |
| `POST` | `/api/v1/quotes/{id}/submit` | Submit a quote for approval — starts the Temporal approval workflow |
| `POST` | `/api/v1/quotes/{id}/approve` | Approve a submitted quote |
| `POST` | `/api/v1/quotes/{id}/reject` | Reject a submitted quote |

---

## 4. Policy

**Required roles:** `underwriting:create`, `underwriting:view`, `underwriting:approve`

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/policies` | List all policies (paginated) |
| `GET` | `/api/v1/policies/search` | Search policies by customer, product, status, or date range |
| `POST` | `/api/v1/policies` | Create a new policy |
| `POST` | `/api/v1/policies/bind-from-quote/{quoteId}` | Bind a policy directly from an approved quote |
| `GET` | `/api/v1/policies/{id}` | Get policy details |
| `PUT` | `/api/v1/policies/{id}` | Update a draft policy |
| `POST` | `/api/v1/policies/{id}/submit` | Submit a policy for approval — starts the Temporal approval workflow |
| `POST` | `/api/v1/policies/{id}/approve` | Approve a submitted policy (generates PDF, triggers NAICOM upload) |
| `POST` | `/api/v1/policies/{id}/reject` | Reject a submitted policy |
| `POST` | `/api/v1/policies/{id}/cancel` | Cancel an active policy |
| `POST` | `/api/v1/policies/{id}/reinstate` | Reinstate a cancelled policy |
| `POST` | `/api/v1/policies/{id}/naicom-upload` | Manually trigger NAICOM upload for a policy |

---

## 5. Endorsements

**Required roles:** `underwriting:create`, `underwriting:view`, `underwriting:approve`

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/endorsements` | List all endorsements (paginated) |
| `GET` | `/api/v1/endorsements/premium-preview` | Preview the pro-rata premium adjustment before creating an endorsement |
| `POST` | `/api/v1/endorsements` | Create an endorsement on an existing policy |
| `GET` | `/api/v1/endorsements/{id}` | Get endorsement details and premium delta |
| `POST` | `/api/v1/endorsements/{id}/submit` | Submit an endorsement for approval |
| `POST` | `/api/v1/endorsements/{id}/approve` | Approve an endorsement (generates PDF, debit/credit note) |
| `POST` | `/api/v1/endorsements/{id}/reject` | Reject an endorsement |
| `POST` | `/api/v1/endorsements/{id}/cancel` | Cancel a draft endorsement |

---

## 6. Claims

**Required roles:** `claims:create`, `claims:view`, `claims:approve`

### Claims

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/claims` | List all claims (paginated) |
| `GET` | `/api/v1/claims/search` | Search claims by policy, customer, or status |
| `POST` | `/api/v1/claims` | Register a new claim notification |
| `GET` | `/api/v1/claims/{id}` | Get claim details |
| `PATCH` | `/api/v1/claims/{id}` | Update claim fields (date of loss, description, etc.) |
| `POST` | `/api/v1/claims/{id}/submit` | Submit a claim for approval |
| `POST` | `/api/v1/claims/{id}/assign-surveyor` | Assign a surveyor to the claim |
| `POST` | `/api/v1/claims/{id}/reserve` | Set or update the claim reserve amount |
| `GET` | `/api/v1/claims/{id}/reserves` | Get reserve history for a claim |
| `POST` | `/api/v1/claims/{id}/approve` | Approve a claim (generates Discharge Voucher) |
| `POST` | `/api/v1/claims/{id}/reject` | Reject a claim |
| `POST` | `/api/v1/claims/{id}/settle` | Mark a claim as settled after payment execution |
| `POST` | `/api/v1/claims/{id}/withdraw` | Withdraw a claim |

### Claim Documents

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/claims/{claimId}/documents` | List all documents uploaded for a claim |
| `POST` | `/api/v1/claims/{claimId}/documents` | Upload a claim document |
| `GET` | `/api/v1/claims/{claimId}/documents/{id}` | Get a document record |
| `DELETE` | `/api/v1/claims/{claimId}/documents/{id}` | Delete a claim document |

### Claim Expenses

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/claims/{claimId}/expenses` | List all expenses for a claim |
| `POST` | `/api/v1/claims/{claimId}/expenses` | Add an expense to a claim (survey fee, legal, etc.) |
| `GET` | `/api/v1/claims/{claimId}/expenses/{id}` | Get expense details |
| `POST` | `/api/v1/claims/{claimId}/expenses/{id}/approve` | Approve a claim expense |
| `POST` | `/api/v1/claims/{claimId}/expenses/{id}/cancel` | Cancel a claim expense |

---

## 7. Reinsurance

**Required roles:** `reinsurance:create`, `reinsurance:view`, `reinsurance:approve`

### Treaties

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/ri/treaties` | List all reinsurance treaties |
| `POST` | `/api/v1/ri/treaties` | Create a treaty (Surplus, Quota Share, or XOL) |
| `GET` | `/api/v1/ri/treaties/{id}` | Get treaty details and participants |
| `POST` | `/api/v1/ri/treaties/{id}/activate` | Activate a draft treaty |
| `POST` | `/api/v1/ri/treaties/{id}/cancel` | Cancel a treaty |
| `POST` | `/api/v1/ri/treaties/{id}/expire` | Manually expire a treaty |
| `POST` | `/api/v1/ri/treaties/{id}/participants` | Add a reinsurer participant to a treaty |
| `DELETE` | `/api/v1/ri/treaties/{id}/participants/{participantId}` | Remove a participant from a treaty |

### Allocations

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/ri/allocations` | List all RI allocations |
| `POST` | `/api/v1/ri/allocations` | Create a treaty RI allocation for a policy |
| `GET` | `/api/v1/ri/allocations/{id}` | Get allocation details (retained share, ceded share) |
| `POST` | `/api/v1/ri/allocations/{id}/confirm` | Confirm an RI allocation |
| `POST` | `/api/v1/ri/allocations/{id}/cancel` | Cancel an RI allocation |

### Facultative Covers

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/ri/fac-covers` | List all facultative covers |
| `POST` | `/api/v1/ri/fac-covers` | Create an outward facultative cover |
| `GET` | `/api/v1/ri/fac-covers/{id}` | Get facultative cover details |
| `POST` | `/api/v1/ri/fac-covers/{id}/confirm` | Confirm a facultative cover |
| `POST` | `/api/v1/ri/fac-covers/{id}/cancel` | Cancel a facultative cover |

---

## 8. Finance

**Required roles:** `finance:create`, `finance:view`, `finance:update`

### Debit Notes & Receipts

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/debit-notes` | List all debit notes (policy and endorsement premiums receivable) |
| `GET` | `/api/v1/debit-notes/{id}` | Get debit note details |
| `POST` | `/api/v1/debit-notes/{id}/cancel` | Cancel a debit note |
| `POST` | `/api/v1/debit-notes/{id}/void` | Void a debit note |
| `GET` | `/api/v1/debit-notes/{debitNoteId}/receipts` | List receipts for a debit note |
| `POST` | `/api/v1/debit-notes/{debitNoteId}/receipts` | Record a receipt against a debit note |
| `GET` | `/api/v1/debit-notes/{debitNoteId}/receipts/{id}` | Get receipt details |
| `POST` | `/api/v1/debit-notes/{debitNoteId}/receipts/{id}/reverse` | Reverse a receipt |

### Credit Notes & Payments

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/credit-notes` | List all credit notes (claims, commissions, RI payables) |
| `GET` | `/api/v1/credit-notes/{id}` | Get credit note details |
| `POST` | `/api/v1/credit-notes/{id}/cancel` | Cancel a credit note |
| `GET` | `/api/v1/credit-notes/{creditNoteId}/payments` | List payments for a credit note |
| `POST` | `/api/v1/credit-notes/{creditNoteId}/payments` | Record a payment against a credit note |
| `GET` | `/api/v1/credit-notes/{creditNoteId}/payments/{id}` | Get payment details |
| `POST` | `/api/v1/credit-notes/{creditNoteId}/payments/{id}/reverse` | Reverse a payment |

---

## 9. Audit & Compliance

**Required roles:** `AUDIT_VIEW` or `SETUP_UPDATE` for all read endpoints. `SETUP_UPDATE` only for config writes. `/api/v1/auth/login/failed` is public (no JWT).

### Audit Logs

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/audit/logs` | Search the full system audit trail — filterable by entity type, entity ID, user, action, and date range |
| `GET` | `/api/v1/audit/export` | Export the audit log as a streaming CSV file (same filters as the log viewer) |

### Login & Session Logs

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/api/v1/auth/session/start` | Record a LOGIN event after successful Keycloak authentication |
| `POST` | `/api/v1/auth/session/end` | Record a LOGOUT event |
| `POST` | `/api/v1/auth/login/failed` | Record a LOGIN_FAILED event — **public, no JWT required** |
| `GET` | `/api/v1/audit/login-logs` | Search login and session history by user or date range |

### Alerts

| Method | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/api/v1/audit/alerts` | List anomaly alerts — filter by `unacknowledgedOnly=true` |
| `POST` | `/api/v1/audit/alerts/{id}/acknowledge` | Acknowledge an alert (records the acknowledging user) |

### Reports

| Method | Endpoint | Required Params | Description |
| --- | --- | --- | --- |
| `GET` | `/api/v1/audit/reports/actions-by-user` | `userId` | All actions performed by a specific user |
| `GET` | `/api/v1/audit/reports/actions-by-module` | `entityType` | All actions within a module (e.g. `Policy`, `Claim`) |
| `GET` | `/api/v1/audit/reports/approvals` | — | All approvals and rejections across all modules |
| `GET` | `/api/v1/audit/reports/data-changes` | `entityType`, `entityId` | Before/after field-level change history for a specific entity |
| `GET` | `/api/v1/audit/reports/login-security` | `from`, `to` | All authentication events in a date range |
| `GET` | `/api/v1/audit/reports/user-activity` | `from`, `to` | Ranked user action counts — who did what and how much |

---

## Pagination

All list endpoints support the following query parameters:

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `page` | integer | `0` | 0-indexed page number |
| `size` | integer | `20` | Results per page |

Response `meta` object:

```json
{
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 142,
    "totalPages": 8
  }
}
```

## Error Responses

| Status | Meaning |
| --- | --- |
| `400 Bad Request` | Validation failed — see `errors` array for field-level messages |
| `401 Unauthorized` | Missing or invalid JWT |
| `403 Forbidden` | Valid JWT but insufficient role for this endpoint |
| `404 Not Found` | Entity does not exist in this tenant's schema |
| `409 Conflict` | Duplicate or state conflict (e.g. policy already submitted) |
| `422 Unprocessable Entity` | Business rule violation (e.g. approval amount exceeds limit) |
