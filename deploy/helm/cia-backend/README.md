# cia-backend Helm chart

Deploys the CIA insurance backend (`cia-api`) — a single Spring Boot service. All 5 backing
services (PostgreSQL, Keycloak, Temporal, object storage, Redis) are **external**; the chart
consumes their endpoints via ConfigMap env (URLs) + a pre-existing Secret (credentials).

## Install

```bash
# 1. Create the secret (out of band — never via Helm values). See secret.example.yaml.
kubectl create secret generic cia-backend-secrets --from-literal=DB_URL=... [...]

# 2. Install, pinning the image to a built commit SHA.
helm upgrade --install cia-api deploy/helm/cia-backend \
  -f deploy/helm/cia-backend/values-prod.example.yaml \
  --set image.tag=<commit-sha>
```

## Required env pairing

A real production deploy MUST set BOTH `SPRING_PROFILES_ACTIVE=prod` (loads `application-prod.yml`)
and `CIA_DEPLOYMENT_ENVIRONMENT=production` (arms `ProductionSafetyValidator`). Neither implies the
other.

## Secrets

The chart never templates secret values — it references `values.existingSecret` via `envFrom`.
Populate that Secret with the keys in `secret.example.yaml` (kubectl / Sealed Secrets / External
Secrets Operator).

## Management surface

`/actuator/**` is not routed on the Ingress (only `/api` and `/partner` are). Prometheus scrapes
`/actuator/prometheus` in-cluster via the Service. **That endpoint is auth-gated** — an
unauthenticated scrape returns 401; wire a scrape credential + a `NetworkPolicy` restricting it to
the monitoring namespace at go-live (tracked as backlog `prometheus-endpoint-authz`).

## Validate locally

```bash
helm lint deploy/helm/cia-backend --set image.tag=dummy
helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | kubeconform -strict -summary -
bash deploy/helm/cia-backend/smoke/smoke-test.sh   # needs Docker + kind
```
