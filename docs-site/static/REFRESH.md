# Refreshing the Static OpenAPI Specs

These two files are committed to the repo but generated from the live Springdoc output:

- `internal-api.json` — `internal-api` group (paths `/api/v1/**`)
- `openapi.json` — `partner-api` group (paths `/partner/v1/**`)

The Scalar pages on the doc-site (`/internal/api-reference` and `/partner/api-reference`) fetch these files directly, so they need to be refreshed whenever the API surface changes.

## When to refresh

After any PR that:

- Adds, removes, or renames an endpoint
- Changes a request body or response shape
- Adds `@Operation`, `@ApiResponses`, `@Schema`, `@Tag`, `@SecurityRequirement`, or `@Parameter` annotations

A drift CI check would catch silent regressions; until that exists, refresh manually as part of the PR that changes the surface.

## Procedure

From the repo root:

```bash
# 1. Make sure the docker-compose stack is up
docker-compose up -d
docker ps --filter "name=coreinsurance" --format "{{.Names}}\t{{.Status}}"
# All 6 containers should show "(healthy)"

# 2. Build cia-api (and its module deps) with your latest changes
cd cia-backend
mvn install -DskipTests -pl cia-api -am

# 3. Boot Spring Boot dev profile
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run -pl cia-api -Dspring-boot.run.profiles=dev
# Wait for "Started CiaApiApplication" in the log

# 4. In a separate terminal, regenerate both specs
cd ..
curl -sS http://localhost:8090/partner/v3/api-docs/internal-api \
  | jq '.servers = [
      {"url": "https://api.cia.app", "description": "Production"},
      {"url": "http://localhost:8090", "description": "Local development"}
    ] | .info += {"contact": {"name": "Nubeero Engineering", "email": "engineering@nubeero.com"}, "version": "2026.05.20"}' \
  > docs-site/static/internal-api.json

curl -sS http://localhost:8090/partner/v3/api-docs/partner-api \
  | jq '.servers = [
      {"url": "https://api.cia.app", "description": "Production"},
      {"url": "http://localhost:8090", "description": "Local development"}
    ] | .info += {"contact": {"name": "CIA Partner Support", "email": "partners@cia.app"}, "version": "2026.05.20"}' \
  > docs-site/static/openapi.json

# 5. Inspect the diff
git diff --stat docs-site/static/

# 6. Commit alongside your code change
```

## Why the jq wrapper

The raw Springdoc output uses a single bare-host server URL (`http://localhost:8090`) and an empty `info.contact` block. The wrapper above:

- Replaces `servers` with both Production and Local-development URLs so the Scalar environment switcher works
- Adds a `contact` and `version` so the doc-site landing shows them in the API reference header

If you change tenants or version, update the `version` string (it has no semver meaning — it's a freshness marker so readers can tell when the spec was last regenerated; using YYYY-MM-DD is convenient).

## What lives where

| File | Group | Generated from | Consumed by |
| --- | --- | --- | --- |
| `internal-api.json` | `internal-api` (`InternalApiOpenApiConfig`) | All `/api/v1/**` controllers across all modules | Scalar at `/internal/api-reference` |
| `openapi.json` | `partner-api` (`OpenApiConfig` in cia-partner-api) | All `/partner/v1/**` controllers in `cia-partner-api` | Scalar at `/partner/api-reference`; openapi-generator-maven-plugin → `cia-partner-api/docs/postman.json` |

## Drift detection (future work)

A CI check that:

1. Boots `cia-api` against a Testcontainers stack
2. Curls both spec endpoints
3. Compares against the committed JSON
4. Fails the build if they diverge

would prevent stale-spec regressions silently shipping. Not wired today; tracked in `cia-log.md` as a follow-up.
