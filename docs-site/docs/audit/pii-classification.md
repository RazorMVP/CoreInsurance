---
id: pii-classification
title: PII Classification
sidebar_label: PII Classification
---

# PII Classification

This document classifies the data handled by the Core Insurance Application and defines the required protection controls for production use by insurers.

## Classification Levels

| Level | Description | Minimum controls |
| --- | --- | --- |
| Public | Product, class, and non-sensitive setup data intended for broad viewing. | Authenticated access unless explicitly published through partner APIs. |
| Internal | Operational records that identify business activity but do not directly expose customer PII. | Tenant isolation, role authorization, audit trail. |
| Confidential | Business-sensitive insurer data, financial values, workflow outcomes, and policy/claim references. | Tenant isolation, role authorization, audit trail, no public exposure. |
| Restricted PII | Data that directly identifies a person, company, director, insured asset owner, claimant, or beneficiary. | Encryption or redaction at rest where implemented, audit-log redaction, log redaction, strict authorization, no webhook/history persistence unless explicitly redacted. |
| Restricted Document | Uploaded files and generated documents that can contain PII, signatures, financial instructions, medical or loss evidence, ID documents, and claim proof. | Tenant-scoped object storage, size limits, type validation, malware-scanning integration point, short-lived access URLs, no fallback to public tenant outside dev/test. |

## Data Inventory

| Domain | Examples | Classification | Required handling |
| --- | --- | --- | --- |
| Customer individual records | Names, date of birth, ID type, ID number, phone, email, address, KYC status. | Restricted PII | Encrypt configured fields at rest, redact audit snapshots, restrict to customer/policy/claim roles. |
| Customer corporate records | Company name, RC number, contact person, company email/phone/address. | Restricted PII | Redact RC number and contact details from audit snapshots, restrict to authorized tenant users. |
| Director KYC records | Director names, date of birth, ID type, ID number, ID expiry, ID document path. | Restricted PII | Encrypt configured fields at rest, redact audit snapshots, restrict to authorized tenant users. |
| KYC documents | ID cards, passports, driver's licenses, CAC certificate uploads. | Restricted Document | Enforce file size/type controls, tenant-scoped storage, scanner hook before storage, no public tenant fallback. |
| Policy records | Policy number, customer, insured object, premium, document path, NAICOM/NIID references. | Confidential, with Restricted PII where customer details are present. | Role authorization, tenant isolation, redact customer/contact fields from audit snapshots. |
| Claim records | Claim number, loss description, claimant contact details, settlement amounts, DV document paths. | Confidential, with Restricted PII where claimant details are present. | Role authorization, tenant isolation, redact contact/identity fields from audit snapshots. |
| Claim documents | Photos, survey reports, invoices, police reports, discharge vouchers. | Restricted Document | Enforce file size/type controls, tenant-scoped storage, scanner hook before storage. |
| Finance records | Debit notes, credit notes, receipts, payments, settlement state. | Confidential | Role authorization, tenant isolation, audit trail without customer PII expansion. |
| Audit logs | Entity type, entity id, action, user, timestamp, sanitized old/new snapshots. | Confidential | Store only redacted snapshots for Restricted PII fields. |
| Login audit logs | User id, user name, IP address, user agent, event type. | Confidential | Restrict to audit/setup roles and rate-limit failed-login reporting endpoint. |
| Partner webhooks | Event type, partner endpoint, delivery status, response/error excerpts. | Confidential | Validate HTTPS public target URLs, do not persist payload bodies, redact/cap response and error text. |

## Implementation Rules

- Audit snapshots must pass through the shared audit sanitizer before persistence.
- Uploaded KYC and claim files must pass through the shared upload security policy before object storage.
- Allowed upload types are PDF, JPEG, and PNG unless this document is updated and tests are changed.
- Default upload limits are 10 MB for KYC documents and 25 MB for claim documents.
- A real malware scanner can replace the default scanner bean without changing customer or claim upload services.
- Webhook payload bodies must not be retained in delivery history.
- Internal and partner API docs must remain private outside dev unless explicitly enabled for a controlled environment.
- Sensitive and partner endpoints must be rate-limited server-side.

## Open Go-Live Decisions

The default malware scanner integration point is intentionally non-blocking for local and pre-provider environments. Before live deployment, the insurer must select the scanning provider, register a production `MalwareScanner` bean, and decide whether additional file types are required by underwriting or claims operations.
