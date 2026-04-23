---
id: overview
title: Architecture Overview
sidebar_label: Overview
---

# Architecture Overview

CIA (Core Insurance Application) is a **multi-tenant SaaS platform** for end-to-end general insurance operations. It is Nigeria-first and compliant with NAICOM, NIID, and NDPR regulations.

## System Boundary

```
┌──────────────────────────────────────────────────────────────────┐
│                        CIA System Boundary                       │
│                                                                  │
│  ┌────────────────┐    HTTPS/REST    ┌──────────────────────┐   │
│  │   React SPA    │ ──────────────▶  │  Spring Boot API     │   │
│  │  (Vite / TS)   │                  │  (cia-api  :8090)    │   │
│  │  Vercel / CDN  │ ◀──────────────  │  19 Maven modules    │   │
│  └────────────────┘                  └──────────┬───────────┘   │
│                                                 │               │
│  ┌──────────────┐  ┌────────────────┐  ┌────────┴─────┐        │
│  │  Keycloak    │  │  PostgreSQL 16 │  │   Temporal   │        │
│  │  (auth)      │  │  schema/tenant │  │  (workflows) │        │
│  └──────────────┘  └────────────────┘  └──────────────┘        │
└──────────────────────────────────────────────────────────────────┘
```

## Tech Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Frontend | React 18 + Vite + TypeScript | SPA, clean separation, fast dev builds |
| UI | Tailwind CSS + shadcn/ui | Accessible, zero runtime cost |
| Backend | Java 21 + Spring Boot 3 | Enterprise-grade, strong ecosystem |
| Database | PostgreSQL (schema-per-tenant) | ACID, regulatory compliance |
| Auth | Keycloak | RBAC, SSO, MFA, self-hostable |
| Workflows | Temporal | Durable, crash-safe approval chains |
| Storage | S3-compatible (MinIO for on-prem) | Cloud-agnostic |
| AI | Claude API (Anthropic) | Optional per-tenant feature flag |
| Testing | Vitest + JUnit 5 + Testcontainers + Playwright | Full test pyramid |

## Request Lifecycle

```
Browser
  │  HTTPS + Bearer JWT
  ▼
Spring Boot Filter Chain
  ├── JwtAuthenticationFilter  → validates JWT against Keycloak JWKS
  ├── TenantContextFilter      → reads tenant_id → sets schema ThreadLocal
  └── @PreAuthorize            → role check (e.g. hasAuthority("underwriting:create"))
  ▼
Service Layer  (business logic, approval rules, premium calculation)
  ▼
JPA Repository  →  MultiTenantConnectionProvider  →  PostgreSQL (tenant schema)
  ▼
ApiResponse<T> { data, meta, errors }
```

## Business Modules

| # | Module | Key Output |
|---|--------|-----------|
| 1 | Setup & Administration | Products, classes, approval groups, master data |
| 2 | Quotation | Quote documents, approval workflow |
| 3 | Policy | Policy certificates, debit notes, NAICOM/NIID upload |
| 4 | Endorsements | Endorsement documents, debit/credit notes |
| 5 | Claims | Reserves, discharge vouchers, claim settlements |
| 6 | Reinsurance | RI allocations, offer slips, bordereaux |
| 7 | Customer Onboarding | Customer records, KYC status |
| 8 | Finance | Receipts, payments, reconciliation |
| 9 | Partner Open API | OAuth2 clients, REST API, webhooks |
| 10 | Audit & Compliance | Full audit trail, login logs, 6 reports, CSV export, real-time alerts |
