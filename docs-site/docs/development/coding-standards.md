---
id: coding-standards
title: Coding Standards
sidebar_label: Coding Standards
---

## Backend (Java)

Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) throughout all `cia-backend` modules.

### Package Structure

Every business module follows the same internal layout:

```text
com.nubeero.cia.<module>/
├── <Module>Service.java          # Business logic, @Transactional
├── <Module>Controller.java       # REST endpoints, @PreAuthorize
├── <Entity>.java                 # JPA entity extending BaseEntity
├── <Entity>Repository.java       # Spring Data JPA
└── dto/
    ├── <Entity>Request.java      # Inbound DTOs (validation annotations)
    └── <Entity>Response.java     # Outbound DTOs (records preferred)
```

Partner API DTOs live exclusively in `cia-partner-api/…/dto/` as `Partner*Request` / `Partner*Response` types. Business module DTOs are **never** exposed directly through the partner API surface.

### Naming Conventions

| Kind | Convention | Example |
| --- | --- | --- |
| Classes | PascalCase | `PolicyService`, `ClaimRepository` |
| Methods | camelCase | `submitForApproval`, `findByPolicyNumber` |
| Constants | UPPER_SNAKE_CASE | `MAX_RETRY_ATTEMPTS` |
| Database columns | snake_case | `policy_number`, `created_at` |
| REST paths | kebab-case | `/api/v1/policy-documents` |

### Entity Rules

- All entities extend `BaseEntity` — provides `id` (UUID), `createdAt`, `updatedAt`, `createdBy`, `deletedAt`.
- Soft delete via `deletedAt` for all master data (products, customers, brokers, etc.).
- Use `@Builder` + `@Data` (or `@Getter`/`@Setter`) via Lombok — no hand-written boilerplate.
- No positional constructors on `@Builder`-annotated classes; always use builder.

### API Design

- All endpoints under `/api/v1/` (internal) or `/partner/v1/` (partner API).
- Tenant context resolved from JWT only — never from request body or query param.
- Standard response envelope:

```json
{ "data": {}, "meta": { "page": 0, "size": 20, "total": 150 }, "errors": [] }
```

- Pagination: cursor-based for large lists; avoid offset pagination on high-cardinality tables.
- No string concatenation in queries — all queries via JPA/JPQL or `@Query` with named parameters.

### Security Rules

- `@PreAuthorize` on every `@RestController` method — no endpoint is unauthenticated.
- Authority strings follow `{module}:{action}` — e.g. `hasAuthority("underwriting:create")`.
- File uploads: validate MIME type server-side; never trust `Content-Type` header alone.
- No hardcoded tenant IDs, currency codes, or country codes anywhere in the codebase.

### `@Schema` Annotations

`@Schema` (springdoc) belongs **exclusively** on `cia-partner-api` DTOs. Business modules must not import `swagger-annotations` — it is a presentation concern, not a domain concern.

---

## Frontend (TypeScript / React)

Format with [Prettier](https://prettier.io/) and lint with ESLint (Airbnb config).

### Component Rules

- One component per file; file name matches component name (PascalCase).
- shadcn/ui components are **extended** (wrap with your own component), never patched at source.
- No Redux for remote data — React Query manages all server state.
- Lazy-load each module's routes:

```tsx
const PolicyModule = React.lazy(() => import('./modules/policy'));
```

### State Management

| State kind | Tool |
| --- | --- |
| Server / remote | React Query (`useQuery`, `useMutation`) |
| Local UI | `useState` / `useReducer` |
| Auth | `useAuth()` from `shared/auth/` (Keycloak JS adapter) |
| Cross-component shared | React Context (small, scoped — not a global store) |

### API Layer

- All API calls go through the Axios instance in `shared/api/` — it injects `Authorization: Bearer` and `X-Tenant-ID` headers automatically.
- Never call `fetch` directly — use the configured Axios instance.

### i18n

All user-visible strings must be externalised even though the initial release is English-only. This preserves the upgrade path to `react-i18next` without a rewrite.

---

## General

- No hardcoded values for tenant, currency, or country.
- Migrations via Flyway only — never edit an existing migration file.
- Tests required at every layer (see [Testing](./testing)).
- All traffic TLS in production — no plain HTTP endpoints.
