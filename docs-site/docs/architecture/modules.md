---
id: modules
title: Module Reference
sidebar_label: Modules
---

# Module Reference

The backend is a Maven multi-module project under `cia-backend/`. Each module compiles to a JAR and is assembled by `cia-api`.

## Module Inventory

```
cia-backend/
├── cia-common/          # TenantContext, audit, ApiResponse<T>, BaseEntity, exceptions
├── cia-auth/            # Keycloak OAuth2 resource server config, JwtAuthConverter
├── cia-storage/         # DocumentStorageService + MinIO/S3/GCS/Azure adapters
├── cia-integrations/    # NAICOM, NIID, KYC interfaces + stub implementations
├── cia-notifications/   # NotificationService, Email + SMS implementations
├── cia-workflow/        # Temporal client config, workflow & activity interfaces
├── cia-documents/       # PDF generation (Apache PDFBox), template rendering
├── cia-setup/           # Module 1: Setup & Administration
├── cia-customer/        # Module 7: Customer Onboarding & KYC
├── cia-quotation/       # Module 2: Quotation
├── cia-policy/          # Module 3: Policy
├── cia-endorsement/     # Module 4: Endorsements
├── cia-claims/          # Module 5: Claims
├── cia-reinsurance/     # Module 6: Reinsurance
├── cia-finance/         # Module 8: Finance
├── cia-partner-api/     # Module 9: Partner Open API
├── cia-audit/           # Module 10: Audit & Compliance
└── cia-api/             # Assembly: main app, REST controllers, Flyway, config
```

## Dependency Rules

- All business modules depend on `cia-common`. Never the reverse.
- `cia-auth`, `cia-storage`, `cia-integrations`, `cia-notifications`, `cia-workflow`, `cia-documents` are infrastructure modules — business modules depend on them, not each other (except explicit cross-module relationships listed below).
- `cia-partner-api` is a **pure facade** — it only maps internal service results to partner-safe DTOs. No business logic lives in this module.

### Cross-Module Dependencies

| Module | Depends On |
|--------|-----------|
| `cia-policy` | `cia-workflow`, `cia-documents`, `cia-integrations` |
| `cia-endorsement` | `cia-workflow`, `cia-documents`, `cia-policy` |
| `cia-claims` | `cia-workflow`, `cia-documents`, `cia-policy` |
| `cia-customer` | `cia-integrations` (KYC) |
| `cia-quotation` | `cia-workflow` |
| `cia-partner-api` | `cia-common`, `cia-auth`, `cia-storage`, `cia-setup`, `cia-customer`, `cia-quotation`, `cia-policy`, `cia-claims`, `cia-workflow`, `cia-notifications` |
| `cia-audit` | `cia-common`, `cia-notifications` |
| `cia-api` | All modules |

## Package Conventions

Each business module follows this package layout:

```
com.nubeero.cia.<module>/
├── <Entity>.java              # JPA entity
├── <Entity>Repository.java    # Spring Data JPA repository
├── <Entity>Service.java       # Business logic
├── <Entity>Controller.java    # REST controller
├── dto/                       # Request/Response DTOs
└── <Entity>Status.java        # Status enum (if applicable)
```
