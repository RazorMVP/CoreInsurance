# Slice B — Kubernetes / Helm Deployment Artifact — Design

> **Status:** Approved (brainstorm complete 2026-06-07). Next: writing-plans.
> **Milestone:** First deployable milestone = P0-1 (tenant provisioning, Slice A ✓) + P0-2 + the P1 operability bundle (Slice C ✓). This is **Slice B** — the final piece (A → C → B order). Drains backlog `prod-deployability-k8s-manifests` (P1).

## Goal

Produce a versioned, CI-validated **Helm chart for cia-api** that wires the Slice-C prod profile, with the 5 backing services treated as external endpoints supplied via an externally-managed `Secret`. Prove it by a `kind` smoke that brings the real image up against ephemeral Postgres + Temporal fixtures. **No live cloud provisioning** — going live is a separate ops step.

## Decisions (from brainstorming Q&A)

| # | Decision | Choice |
|---|---|---|
| Q1 | Deliverable boundary | **Authored + locally/CI-validated artifacts; cia-api only; no live cloud provisioning.** |
| Q2 | Backing-services posture | **External / managed** — chart deploys cia-api only; Postgres, Keycloak, Temporal, MinIO/S3, Redis are external endpoints supplied via a `Secret`. |
| Q3 | Manifest tooling | **Helm chart.** |
| Q4 | Secret delivery | **Reference a pre-existing, externally-managed `Secret`** via `envFrom: secretRef`; the chart never templates secret *values*. |
| Q5 | Validation gate | **B1 — full-dep `kind` smoke in CI**: ephemeral Postgres + Temporal auto-setup fixtures → `helm install` → assert cia-api pod `Ready` + `/actuator/health` == 200. No app code change. |

## Ground truth (verified against `main`, 2026-06-07)

- **Backend image (S143):** `cia-backend/Dockerfile` — multi-stage, non-root user `cia:cia`, `EXPOSE 8090`, `JAVA_OPTS` pass-through, `HEALTHCHECK` on `/actuator/health/liveness`. CI `backend-image.yml` builds + Trivy-gates + pushes to `ghcr.io/RazorMVP/CoreInsurance/cia-backend` tagged by commit SHA (`type=sha,prefix=`). 0 CRITICAL/HIGH.
- **Slice C (just merged):** `application-prod.yml` (Hikari `DB_POOL_*`, ECS structured logging, actuator exposure `health,info,prometheus`). Dual-env prod requirement: `SPRING_PROFILES_ACTIVE=prod` **+** `CIA_DEPLOYMENT_ENVIRONMENT=production` (neither implies the other; `ProductionSafetyValidator` keys off the marker).
- **Probes are reachable unauthenticated:** `SecurityConfig.java:34` `permitAll`s `/actuator/health/**` (covers `/liveness` + `/readiness`) plus `/actuator/health` and `/actuator/info`. **No app change needed** for kubelet probes. `/actuator/prometheus` is NOT permitAll (auth-gated) — fine; kept off the public ingress; in-cluster scrape auth is the `prometheus-endpoint-authz` backlog item.
- **Temporal is a hard boot dependency:** `TemporalConfig.java:24` `@Bean WorkflowServiceStubs workflowServiceStubs(...)` calls `WorkflowServiceStubs.newInstance(...)` **eagerly and unguarded** (no `@ConditionalOnProperty`) — it dials Temporal (health-check) at context init. The entire IT suite avoids needing a Temporal server by `@MockBean`-ing the three Temporal beans (`FinanceWebItSupport:102-105`); there is **no Temporal Testcontainer anywhere**. Consequence: the pod reaches `Ready` only with **Postgres + Temporal** both reachable. (Logged as a new backlog row — see §10.)
- **Tenant bootstrap** is gated off by default (`cia.tenants.bootstrap.enabled=false`, Slice A); enabling needs `CIA_KEYCLOAK_ADMIN_ENABLED=true` + a per-tenant list with admin temp-passwords.
- **No k8s/Helm/kustomize exists** on `main` (only the local-dev `docker-compose.yml`). Frontend already deploys to Vercel.
- **Backing-service versions** (from `docker-compose.yml`, for the kind fixtures): `postgres:16`, `temporalio/auto-setup:1.25.0` (DB=postgres12, needs the Postgres fixture), `quay.io/keycloak/keycloak:24.0`, MinIO, `redis:7-alpine`.

## Components

### 1. Repo layout & chart skeleton

```
deploy/helm/cia-backend/
├── Chart.yaml                 # name: cia-backend, type: application, version (chart), appVersion (image)
├── values.yaml                # safe non-secret defaults + placeholders — NEVER secret values
├── values-prod.example.yaml   # example prod overrides (ingress host, replicas, image.tag, resources)
├── secret.example.yaml        # the expected Secret contract — placeholder values only (§6)
├── .helmignore
└── templates/
    ├── _helpers.tpl           # fullname/labels/selectorLabels helpers
    ├── serviceaccount.yaml    # dedicated SA (no automounted token unless needed)
    ├── configmap.yaml         # non-secret env (§3 ConfigMap set)
    ├── deployment.yaml        # cia-api Deployment (§3)
    ├── service.yaml           # ClusterIP :8090
    ├── ingress.yaml           # /api + /partner only — NOT /actuator (§4)
    ├── hpa.yaml               # CPU-based (§5)
    ├── pdb.yaml               # PodDisruptionBudget (§5)
    └── NOTES.txt              # post-install usage (Secret creation reminder, dual-env requirement)
```

Chart lives at repo root under `deploy/helm/` (sibling to `cia-backend/` and `cia-frontend/`), so it isn't coupled to the Maven build context.

### 2. `values.yaml` shape (non-secret only)

```yaml
image:
  repository: ghcr.io/razormvp/coreinsurance/cia-backend
  tag: ""                       # REQUIRED at deploy — pin to a commit SHA; empty default forces an explicit set
  pullPolicy: IfNotPresent
replicaCount: 3
existingSecret: cia-backend-secrets   # name of the externally-managed Secret (§6)
env:                            # non-secret env -> ConfigMap
  SPRING_PROFILES_ACTIVE: prod
  CIA_DEPLOYMENT_ENVIRONMENT: production
  SERVER_PORT: "8090"
  CIA_TENANT_BOOTSTRAP_ENABLED: "false"
  DB_POOL_MAX: "10"
  DB_POOL_MIN: "10"
  KEYCLOAK_URL: ""              # external endpoint
  TEMPORAL_HOST: ""            # host:7233
  STORAGE_TYPE: s3
  STORAGE_ENDPOINT: ""
  STORAGE_BUCKET: cia-documents
resources:
  requests: { cpu: 500m, memory: 1Gi }
  limits:   { cpu: "1",  memory: 1.5Gi }
javaOpts: "-XX:MaxRAMPercentage=70"
ingress:
  enabled: true
  className: nginx
  host: "*.cia.app"
  tlsSecretName: cia-backend-tls
  annotations: {}              # cert-manager.io/cluster-issuer placeholder documented in values-prod.example.yaml
hpa:
  minReplicas: 3
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70
pdb:
  minAvailable: 2
```

### 3. Deployment

- **Image:** `{{ .Values.image.repository }}:{{ .Values.image.tag }}` (tag required — empty default fails `helm template` intentionally so a release can't ship `:latest`).
- **Env split:**
  - **ConfigMap** (`envFrom: configMapRef`): everything in `.Values.env` — profile, dual-env marker, pool sizes, external service URLs, bootstrap toggle, `JAVA_OPTS`.
  - **Secret** (`envFrom: secretRef: {{ .Values.existingSecret }}`): all secret keys (§6). The chart references it; it does not own it.
- **Probes:**
  - `startupProbe`: httpGet `/actuator/health/liveness` :8090, `periodSeconds: 10`, `failureThreshold: 30` (≈5 min — covers Flyway public-schema migration + Temporal dial at boot).
  - `livenessProbe`: httpGet `/actuator/health/liveness`, `periodSeconds: 30`, `failureThreshold: 3`.
  - `readinessProbe`: httpGet `/actuator/health/readiness`, `periodSeconds: 10`, `failureThreshold: 3`.
- **Resources:** as `values.yaml` (overridable). `JAVA_OPTS=-XX:MaxRAMPercentage=70` so the heap tracks the container memory limit.
- **securityContext (pod + container):** `runAsNonRoot: true`, `runAsUser: <cia uid>` (or rely on image USER), `readOnlyRootFilesystem: true` + an `emptyDir` volume mounted at `/tmp` (PDFBox/Thymeleaf temp), `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `seccompProfile.type: RuntimeDefault`.
- **Rollout:** `RollingUpdate` `maxUnavailable: 0, maxSurge: 1` (zero-downtime).

### 4. Service & Ingress

- **Service:** `ClusterIP`, port 8090 → targetPort 8090. In-cluster Prometheus scrapes `/actuator/prometheus` via the Service (auth per the backlog item), not the ingress.
- **Ingress:** `className` from values; TLS referencing `tlsSecretName`; host `*.cia.app` (multi-tenant subdomains). **Path rules:** `pathType: Prefix` for `/api` and `/partner` only. `/actuator/**` is deliberately **absent** — the management surface is never publicly routable (closes the public-exposure half of `prometheus-endpoint-authz`).

### 5. Availability

- **HPA** (autoscaling/v2): targets the Deployment, `minReplicas`/`maxReplicas`/`targetCPUUtilizationPercentage` from values. Requires the CPU `requests` set in §3 (they are).
- **PodDisruptionBudget:** `minAvailable: 2` so a node drain can't drop below 2 replicas.

### 6. Secret contract (`secret.example.yaml`)

The chart never templates secret values; this file documents the keys the externally-managed Secret must carry (placeholders only — committed safely). Keys derived from CLAUDE.md's env table and `ProductionSafetyValidator`'s weak-default list:

```yaml
# kubectl create secret generic cia-backend-secrets --from-literal=... (or via External Secrets Operator)
apiVersion: v1
kind: Secret
metadata:
  name: cia-backend-secrets
type: Opaque
stringData:
  DB_URL: "jdbc:postgresql://<managed-pg-host>:5432/cia"
  DB_USERNAME: "cia"
  DB_PASSWORD: "REPLACE_ME"
  PII_ENCRYPTION_KEY: "REPLACE_ME_32B_BASE64"
  WEBHOOK_SIGNING_SECRET: "REPLACE_ME"
  STORAGE_ACCESS_KEY: "REPLACE_ME"
  STORAGE_SECRET_KEY: "REPLACE_ME"
  KEYCLOAK_ADMIN_CLIENT_ID: "cia-admin"
  KEYCLOAK_ADMIN_CLIENT_SECRET: "REPLACE_ME"
  SENDGRID_API_KEY: ""        # only if cia.notifications.email.provider=sendgrid
```

`DB_URL`/`DB_USERNAME` live in the Secret (alongside `DB_PASSWORD`) so the whole datasource credential set is managed in one place. `ProductionSafetyValidator` enforces none of `PII_ENCRYPTION_KEY` / `WEBHOOK_SIGNING_SECRET` / `STORAGE_*` / `DB_PASSWORD` retains its weak dev default once `CIA_DEPLOYMENT_ENVIRONMENT=production`.

### 7. Tenant bootstrap

`CIA_TENANT_BOOTSTRAP_ENABLED` defaults `"false"` in `values.yaml.env` (matches Slice A). `NOTES.txt` + `values-prod.example.yaml` document that turning it on additionally requires `CIA_KEYCLOAK_ADMIN_ENABLED=true` and the per-tenant list (`CIA_TENANTS_BOOTSTRAP_TENANTS_<n>_*`, incl. admin temp-passwords) supplied via the Secret. Not exercised by the smoke (stays off).

### 8. Validation — `helm-chart.yml` CI workflow (B1)

New workflow on PR + push to `main` + `workflow_dispatch`, mirroring `backend-image.yml`:

1. **Lint:** `helm lint deploy/helm/cia-backend`.
2. **Schema validate:** `helm template deploy/helm/cia-backend --set image.tag=dummy -f deploy/helm/cia-backend/values-prod.example.yaml | kubeconform -strict -summary` (validates every rendered manifest against the k8s API schemas; `-strict` rejects unknown fields).
3. **kind smoke (B1):**
   - `helm/kind-action` creates a kind cluster.
   - Apply ephemeral fixtures into the cluster: a `postgres:16` Deployment+Service (db `cia`, user `cia`) and a `temporalio/auto-setup:1.25.0` Deployment+Service (pointed at the Postgres fixture). Wait for both Ready.
   - Create the cia-api Secret from `secret.example.yaml` with `DB_URL`/`TEMPORAL_HOST` pointed at the in-cluster fixtures; set `CIA_DEPLOYMENT_ENVIRONMENT=local` for the smoke so `ProductionSafetyValidator` does not demand real secrets (the smoke proves boot+wiring, not prod-secret hygiene — that's the validator's own ITs).
   - `helm install cia-api deploy/helm/cia-backend --set image.tag=<sha built by backend-image, or a known-good tag> --set env.KEYCLOAK_URL=... --set env.CIA_DEPLOYMENT_ENVIRONMENT=local --set ingress.enabled=false`.
   - `kubectl rollout status deploy/cia-api --timeout=300s` (waits for Ready — proves the real image booted against real Postgres + Temporal).
   - `kubectl run` a curl/`port-forward` and assert `GET /actuator/health` == 200.

   **Image source for the smoke:** the smoke needs a built image. Pull the GHCR image for the current commit (the `backend-image.yml` workflow builds it); the chart-workflow `needs:` is documented, or it `docker build`s locally and `kind load`s. Decision recorded in the plan: **build-and-`kind load` locally within the smoke job** to avoid cross-workflow ordering/auth coupling (slower but self-contained).

   **Profile note for the smoke:** the smoke runs with `SPRING_PROFILES_ACTIVE=prod` (so it exercises `application-prod.yml`) but `CIA_DEPLOYMENT_ENVIRONMENT=local` (so the weak-default guard stays disarmed — fixtures use dev credentials). This is the honest split: prove the prod profile *loads and boots*; prove secret-hygiene separately via the existing `ProductionSafetyValidator` unit tests.

   **PiiKeyValidator note:** `PiiKeyValidator` enforces the PII key's charset/length in **every** environment (it's a SQL-injection guard, independent of `CIA_DEPLOYMENT_ENVIRONMENT`). So the smoke's Secret must carry a valid-length `PII_ENCRYPTION_KEY` — supply the known dev default (`dev-pii-key-do-not-use-in-prod-CHANGE-ME`), which passes `PiiKeyValidator` and is accepted because `CIA_DEPLOYMENT_ENVIRONMENT=local` disarms `ProductionSafetyValidator`'s weak-default check. Same applies to any other key with a non-hardened format validator.

### 9. Docs

- **CLAUDE.md §10:** replace the abstract "Deployments: cia-api 3+ replicas…" sketch with a pointer to the real chart (`deploy/helm/cia-backend`), the external-backing-services posture, the `existingSecret` contract, and the dual-env (`SPRING_PROFILES_ACTIVE=prod` + `CIA_DEPLOYMENT_ENVIRONMENT=production`) requirement.
- **`cia-log.md`:** session entry + backlog reconciliation (§10).
- A short `deploy/helm/cia-backend/README.md` (install steps, Secret creation, required `--set`s, the in-cluster vs ingress actuator note).

### 10. Backlog reconciliation

- **Drain:** `prod-deployability-k8s-manifests` (P1) — landed by this slice.
- **Update (keep, narrow):** `prometheus-endpoint-authz` (P3) — the *public-ingress* half is closed (the chart never routes `/actuator/**` publicly). The remaining half (authn/network-policy for in-cluster scraping of the auth-gated `/actuator/prometheus`) stays open, now scoped to "add a NetworkPolicy + scrape auth when a real Prometheus is wired."
- **Add:** `temporal-eager-boot-dial` (P2) — `TemporalConfig.workflowServiceStubs` dials Temporal eagerly/unguarded at context init, so a cia-api pod crashloops if Temporal is briefly unreachable at startup (prod resilience gap) and the kind smoke is forced to stand up a full Temporal fixture. Fix: make the stubs lazy / `setDisableHealthCheck(true)` (or `@ConditionalOnProperty` + start-degraded), which both hardens prod startup and would let the smoke drop the Temporal fixture (Postgres-only). This was the B2 option, deliberately deferred out of the deploy slice.

## Testing posture

| Concern | Test | Kind |
|---|---|---|
| Manifests are well-formed | `helm lint` | CI |
| Manifests valid against k8s API | `helm template \| kubeconform -strict` | CI |
| **Chart actually deploys + app boots** | kind: Postgres + Temporal fixtures → `helm install` → `rollout status` Ready → `/actuator/health` 200 | CI (B1) |
| Values bind / template correctness | covered by `helm template` rendering under both `values.yaml` and `values-prod.example.yaml` | CI |

No backend Java tests change in this slice (no app code change). The full `mvn verify` reactor is untouched.

## Out of scope (YAGNI / deferred)

- **Live cloud provisioning** — real cluster, DNS, cert-manager issuer, secret-store (External Secrets/Vault) wiring, managed Postgres/Keycloak/Temporal/MinIO/Redis endpoints. Separate go-live ops step.
- **Self-hosting any backing service** in-cluster (StatefulSets for PG/Keycloak/Temporal/MinIO/Redis) — explicitly rejected (Q2).
- **A deploy-to-cluster CD workflow** (`helm upgrade` against a live cluster) — needs a cluster + creds (boundary A).
- **Tenant-Onboarding API + Platform-Admin UI** — the agreed *next epic* (its own brainstorm; needs a platform-admin auth story).
- **B2 Temporal-lazy code change** — backlogged as `temporal-eager-boot-dial` (P2).
- **NetworkPolicy / scrape-auth for `/actuator/prometheus`** — the open half of `prometheus-endpoint-authz` (P3).
