---
id: local-setup
title: Local Development Setup
sidebar_label: Local Setup
---

# Local Development Setup

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 21 | `brew install openjdk@21` |
| Maven | 3.9+ | `brew install maven` (or use `./mvnw`) |
| Node.js | 20+ | `brew install node` |
| Docker | Latest | [Docker Desktop](https://www.docker.com/products/docker-desktop/) |
| GitHub CLI | Latest | `brew install gh` |

## 1. Clone and Configure

```bash
git clone https://github.com/RazorMVP/CoreInsurance.git
cd CoreInsurance
```

The checked-in defaults are enough to run the local stack. If you need to
override a value, export the relevant environment variable before starting the
backend or add an app-local Vite env file such as
`cia-frontend/apps/back-office/.env.local`.

## 2. Start Infrastructure

```bash
docker-compose up -d
```

This starts:

| Service | Port | Purpose |
|---------|------|---------|
| PostgreSQL 16 | 5434 | Primary database |
| Keycloak 24 | 8280 | Auth server |
| Temporal | 7233 | Workflow engine |
| Temporal UI | 8088 | Workflow browser |
| MinIO | 9000 / 9001 | Object storage |
| Redis | 6380 | Rate limit counters |

Wait for all services to be healthy:

```bash
docker-compose ps
```

## 3. Start the Backend

```bash
cd cia-backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run -pl cia-api -Dspring-boot.run.profiles=dev
```

The API starts at `http://localhost:8090`. Flyway runs migrations on boot.
The backend requires an explicit Spring profile. Use `dev` for local
development; the Maven `-Pdev` flag is not a Spring profile and does not
activate the local security/configuration profile.

## 4. Start the Frontend

```bash
cd cia-frontend
pnpm install
pnpm dev:back-office
```

The Vite dev server starts at `http://localhost:5173`.

## 5. Verify

- **API Health**: `curl http://localhost:8090/actuator/health`
- **Internal API Docs**: `http://localhost:8090/internal/docs`
- **Partner API Docs**: `http://localhost:8090/partner/docs`
- **Keycloak Admin**: `http://localhost:8280` (admin / admin)
- **Temporal UI**: `http://localhost:8088`
- **MinIO Console**: `http://localhost:9001` (minioadmin / minioadmin)

## Running Tests

```bash
cd cia-backend

# Full test suite (JUnit 5 + Testcontainers — requires Docker)
./mvnw verify

# Single module
./mvnw verify -pl cia-policy
```

## Common Issues

**`Lombok @Builder not found` / `TypeTag :: UNKNOWN`** — ensure Lombok is at `1.18.46` or later in the parent `pom.xml`. Versions below 1.18.40 are incompatible with JDK 24+. If you must use JDK 21, set `JAVA_HOME=/opt/homebrew/opt/openjdk@21` before running Maven.

**`Keycloak fails to start`** — confirm the `keycloak` PostgreSQL database was created by the init script. Check with:
```bash
docker exec -it coreinsurance-postgres-1 psql -U cia -c "\l"
```

**`Temporal startup fails`** — make sure PostgreSQL is fully healthy before Temporal starts (`docker-compose ps` shows `healthy`).
