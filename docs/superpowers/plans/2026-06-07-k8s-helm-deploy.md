# Slice B — Kubernetes / Helm Deployment Artifact — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a versioned, CI-validated Helm chart for cia-api (backing services external, secrets referenced from a pre-existing `Secret`), proven by a `kind` smoke that boots the real image against ephemeral Postgres + Temporal fixtures.

**Architecture:** A self-contained Helm chart under `deploy/helm/cia-backend/` renders Deployment/Service/Ingress/HPA/PDB/ConfigMap/ServiceAccount for cia-api only; non-secret env via ConfigMap, secret env via `envFrom: secretRef` to an operator-managed Secret. A `helm-chart.yml` GitHub Actions workflow gates every change with `helm lint` + `helm template | kubeconform` + a `kind` smoke (extracted into a committed shell script so it runs locally too). No app/Java code changes; no live cloud provisioning.

**Tech Stack:** Helm 3, kubeconform, kind, kubectl, GitHub Actions, Docker. Target app: Spring Boot 3.5.14 image at `ghcr.io/RazorMVP/CoreInsurance/cia-backend`.

**Spec:** `docs/superpowers/specs/2026-06-07-k8s-helm-deploy-design.md`

---

## Prerequisites (local validation tooling)

The implementer needs these on PATH for the local "verify" steps (all macOS-installable):

```bash
brew install helm kubeconform kind kubectl   # or equivalent
helm version    # expect v3.x
kubeconform -v  # expect v0.6.x
kind version
```

Tasks 1–5 validate with `helm`/`kubeconform` (fast, no Docker). Task 7's smoke needs Docker running. If Docker is unavailable locally, the implementer may rely on the CI run for the smoke, but MUST still author the script + fixtures exactly as specified.

---

## File Structure

```
deploy/helm/cia-backend/
├── Chart.yaml
├── .helmignore
├── values.yaml
├── values-prod.example.yaml
├── secret.example.yaml
├── README.md
├── templates/
│   ├── _helpers.tpl
│   ├── serviceaccount.yaml
│   ├── configmap.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── ingress.yaml
│   ├── hpa.yaml
│   ├── pdb.yaml
│   └── NOTES.txt
└── smoke/
    ├── smoke-test.sh
    └── fixtures/
        ├── postgres.yaml
        └── temporal.yaml
.github/workflows/helm-chart.yml
```

---

## Task 1: Chart skeleton (Chart.yaml, values.yaml, helpers, ServiceAccount)

A minimal chart that `helm lint` accepts and `helm template` renders.

**Files:**
- Create: `deploy/helm/cia-backend/Chart.yaml`
- Create: `deploy/helm/cia-backend/.helmignore`
- Create: `deploy/helm/cia-backend/values.yaml`
- Create: `deploy/helm/cia-backend/templates/_helpers.tpl`
- Create: `deploy/helm/cia-backend/templates/serviceaccount.yaml`

- [ ] **Step 1: Verify the chart does not yet exist (red)**

Run: `helm lint deploy/helm/cia-backend`
Expected: FAIL — `Error: ... no such file or directory` (chart absent).

- [ ] **Step 2: Create `Chart.yaml`**

```yaml
apiVersion: v2
name: cia-backend
description: CIA insurance backend (cia-api) — single Spring Boot service; backing services are external.
type: application
version: 0.1.0
appVersion: "1.0.0"
```

- [ ] **Step 3: Create `.helmignore`**

```
.git
*.tmp
smoke/
ci/
```

- [ ] **Step 4: Create `templates/_helpers.tpl`**

```yaml
{{- define "cia-backend.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "cia-backend.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "cia-backend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cia-backend.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "cia-backend.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "cia-backend.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "cia-backend.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "cia-backend.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
```

- [ ] **Step 5: Create `values.yaml`**

```yaml
# Non-secret defaults + placeholders. NEVER put secret values here (see secret.example.yaml).
nameOverride: ""
fullnameOverride: ""

image:
  repository: ghcr.io/razormvp/coreinsurance/cia-backend
  tag: ""                       # REQUIRED at deploy — pin to a commit SHA. Empty fails rendering on purpose.
  pullPolicy: IfNotPresent

replicaCount: 3

# Name of the pre-existing, operator-managed Secret carrying all secret env (see secret.example.yaml).
existingSecret: cia-backend-secrets

serviceAccount:
  create: true
  name: ""

# Non-secret env -> ConfigMap (envFrom). Secret env comes from existingSecret.
env:
  SPRING_PROFILES_ACTIVE: prod
  CIA_DEPLOYMENT_ENVIRONMENT: production
  SERVER_PORT: "8090"
  CIA_TENANT_BOOTSTRAP_ENABLED: "false"
  CIA_KEYCLOAK_ADMIN_ENABLED: "false"
  DB_POOL_MAX: "10"
  DB_POOL_MIN: "10"
  KEYCLOAK_URL: ""
  TEMPORAL_HOST: ""
  STORAGE_TYPE: s3
  STORAGE_ENDPOINT: ""
  STORAGE_BUCKET: cia-documents
  PARTNER_API_RATE_LIMIT_STORE: in-memory

javaOpts: "-XX:MaxRAMPercentage=70"

resources:
  requests:
    cpu: 500m
    memory: 1Gi
  limits:
    cpu: "1"
    memory: 1.5Gi

service:
  port: 8090

ingress:
  enabled: true
  className: nginx
  host: "*.cia.app"
  tlsSecretName: cia-backend-tls
  annotations: {}

hpa:
  enabled: true
  minReplicas: 3
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70

pdb:
  enabled: true
  minAvailable: 2
```

- [ ] **Step 6: Create `templates/serviceaccount.yaml`**

```yaml
{{- if .Values.serviceAccount.create -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "cia-backend.serviceAccountName" . }}
  labels:
    {{- include "cia-backend.labels" . | nindent 4 }}
automountServiceAccountToken: false
{{- end -}}
```

- [ ] **Step 7: Verify lint + render pass (green)**

Run: `helm lint deploy/helm/cia-backend --set image.tag=dummy`
Expected: `1 chart(s) linted, 0 chart(s) failed`.

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | kubeconform -strict -summary -`
Expected: summary with `Valid: 1` (the ServiceAccount), `Invalid: 0`, `Errors: 0`.

- [ ] **Step 8: Commit**

```bash
git add deploy/helm/cia-backend/Chart.yaml deploy/helm/cia-backend/.helmignore \
        deploy/helm/cia-backend/values.yaml deploy/helm/cia-backend/templates/_helpers.tpl \
        deploy/helm/cia-backend/templates/serviceaccount.yaml
git commit -m "feat(deploy): scaffold cia-backend Helm chart skeleton"
```

---

## Task 2: ConfigMap + Deployment

The core workload — non-secret env via ConfigMap, secret env via `envFrom: secretRef`, probes, resources, hardened securityContext.

**Files:**
- Create: `deploy/helm/cia-backend/templates/configmap.yaml`
- Create: `deploy/helm/cia-backend/templates/deployment.yaml`

- [ ] **Step 1: Verify the Deployment isn't rendered yet (red)**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | grep -c 'kind: Deployment'`
Expected: `0`.

- [ ] **Step 2: Create `templates/configmap.yaml`**

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "cia-backend.fullname" . }}-env
  labels:
    {{- include "cia-backend.labels" . | nindent 4 }}
data:
  {{- range $k, $v := .Values.env }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
  JAVA_OPTS: {{ .Values.javaOpts | quote }}
```

- [ ] **Step 3: Create `templates/deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "cia-backend.fullname" . }}
  labels:
    {{- include "cia-backend.labels" . | nindent 4 }}
spec:
  {{- if not .Values.hpa.enabled }}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "cia-backend.selectorLabels" . | nindent 6 }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  template:
    metadata:
      labels:
        {{- include "cia-backend.selectorLabels" . | nindent 8 }}
    spec:
      serviceAccountName: {{ include "cia-backend.serviceAccountName" . }}
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: cia-api
          image: "{{ .Values.image.repository }}:{{ required "Set image.tag to a pinned image tag (commit SHA)" .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.service.port }}
          envFrom:
            - configMapRef:
                name: {{ include "cia-backend.fullname" . }}-env
            - secretRef:
                name: {{ .Values.existingSecret }}
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 10
            failureThreshold: 30
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            periodSeconds: 30
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            periodSeconds: 10
            failureThreshold: 3
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop:
                - ALL
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
```

- [ ] **Step 4: Verify render + schema (green)**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | kubeconform -strict -summary -`
Expected: `Valid: 3` (ServiceAccount + ConfigMap + Deployment), `Invalid: 0`, `Errors: 0`.

- [ ] **Step 5: Verify the required-tag guard (red without tag)**

Run: `helm template cia-api deploy/helm/cia-backend`
Expected: FAIL — `execution error ... Set image.tag to a pinned image tag (commit SHA)`.

- [ ] **Step 6: Verify env wiring renders correctly**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | grep -E 'configMapRef|secretRef|SPRING_PROFILES_ACTIVE: "prod"|readOnlyRootFilesystem: true'`
Expected: lines for `configMapRef`, `secretRef`, `SPRING_PROFILES_ACTIVE: "prod"`, `readOnlyRootFilesystem: true` all present.

- [ ] **Step 7: Commit**

```bash
git add deploy/helm/cia-backend/templates/configmap.yaml deploy/helm/cia-backend/templates/deployment.yaml
git commit -m "feat(deploy): cia-api ConfigMap + hardened Deployment with health probes"
```

---

## Task 3: Service + Ingress

ClusterIP Service; Ingress routes only `/api` and `/partner` (never `/actuator`).

**Files:**
- Create: `deploy/helm/cia-backend/templates/service.yaml`
- Create: `deploy/helm/cia-backend/templates/ingress.yaml`

- [ ] **Step 1: Verify no Service/Ingress yet (red)**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | grep -cE 'kind: (Service|Ingress)'`
Expected: `0`.

- [ ] **Step 2: Create `templates/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "cia-backend.fullname" . }}
  labels:
    {{- include "cia-backend.labels" . | nindent 4 }}
spec:
  type: ClusterIP
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP
  selector:
    {{- include "cia-backend.selectorLabels" . | nindent 4 }}
```

- [ ] **Step 3: Create `templates/ingress.yaml`**

```yaml
{{- if .Values.ingress.enabled -}}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ include "cia-backend.fullname" . }}
  labels:
    {{- include "cia-backend.labels" . | nindent 4 }}
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  ingressClassName: {{ .Values.ingress.className }}
  tls:
    - hosts:
        - {{ .Values.ingress.host | quote }}
      secretName: {{ .Values.ingress.tlsSecretName }}
  rules:
    - host: {{ .Values.ingress.host | quote }}
      http:
        paths:
          # Public surface only. /actuator/** is deliberately NOT routed — the
          # management surface (incl. /actuator/prometheus) is never publicly
          # reachable; in-cluster scrape hits the Service directly.
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: {{ include "cia-backend.fullname" . }}
                port:
                  number: {{ .Values.service.port }}
          - path: /partner
            pathType: Prefix
            backend:
              service:
                name: {{ include "cia-backend.fullname" . }}
                port:
                  number: {{ .Values.service.port }}
{{- end -}}
```

- [ ] **Step 4: Verify render + schema (green)**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | kubeconform -strict -summary -`
Expected: `Valid: 5`, `Invalid: 0`, `Errors: 0`.

- [ ] **Step 5: Verify /actuator is NOT in the ingress**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | awk '/kind: Ingress/,0' | grep -c actuator`
Expected: `0`.

- [ ] **Step 6: Commit**

```bash
git add deploy/helm/cia-backend/templates/service.yaml deploy/helm/cia-backend/templates/ingress.yaml
git commit -m "feat(deploy): ClusterIP Service + Ingress (api/partner only, no actuator)"
```

---

## Task 4: HPA + PodDisruptionBudget

**Files:**
- Create: `deploy/helm/cia-backend/templates/hpa.yaml`
- Create: `deploy/helm/cia-backend/templates/pdb.yaml`

- [ ] **Step 1: Verify no HPA/PDB yet (red)**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | grep -cE 'kind: (HorizontalPodAutoscaler|PodDisruptionBudget)'`
Expected: `0`.

- [ ] **Step 2: Create `templates/hpa.yaml`**

```yaml
{{- if .Values.hpa.enabled -}}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "cia-backend.fullname" . }}
  labels:
    {{- include "cia-backend.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "cia-backend.fullname" . }}
  minReplicas: {{ .Values.hpa.minReplicas }}
  maxReplicas: {{ .Values.hpa.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.hpa.targetCPUUtilizationPercentage }}
{{- end -}}
```

- [ ] **Step 3: Create `templates/pdb.yaml`**

```yaml
{{- if .Values.pdb.enabled -}}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "cia-backend.fullname" . }}
  labels:
    {{- include "cia-backend.labels" . | nindent 4 }}
spec:
  minAvailable: {{ .Values.pdb.minAvailable }}
  selector:
    matchLabels:
      {{- include "cia-backend.selectorLabels" . | nindent 6 }}
{{- end -}}
```

- [ ] **Step 4: Verify render + schema (green)**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | kubeconform -strict -summary -`
Expected: `Valid: 7`, `Invalid: 0`, `Errors: 0`.

- [ ] **Step 5: Verify HPA suppresses Deployment.replicas**

Run: `helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | awk '/kind: Deployment/,/kind: /' | grep -c 'replicas:'`
Expected: `0` (HPA owns scaling; Deployment omits `replicas` when `hpa.enabled`).

- [ ] **Step 6: Commit**

```bash
git add deploy/helm/cia-backend/templates/hpa.yaml deploy/helm/cia-backend/templates/pdb.yaml
git commit -m "feat(deploy): HPA (CPU 3->10) + PodDisruptionBudget (minAvailable 2)"
```

---

## Task 5: Secret contract, prod example values, NOTES.txt, README

Operator-facing artifacts. No secret *values* committed — placeholders only.

**Files:**
- Create: `deploy/helm/cia-backend/secret.example.yaml`
- Create: `deploy/helm/cia-backend/values-prod.example.yaml`
- Create: `deploy/helm/cia-backend/templates/NOTES.txt`
- Create: `deploy/helm/cia-backend/README.md`

- [ ] **Step 1: Create `secret.example.yaml`**

```yaml
# CONTRACT ONLY — placeholders, safe to commit. Create the REAL Secret out of band
# (kubectl / Sealed Secrets / External Secrets Operator); the chart references it by
# name via values.existingSecret and never templates these values.
#
#   kubectl create secret generic cia-backend-secrets \
#     --from-literal=DB_URL='jdbc:postgresql://<host>:5432/cia' \
#     --from-literal=DB_USERNAME='cia' --from-literal=DB_PASSWORD='...' \
#     --from-literal=PII_ENCRYPTION_KEY='...' ...
apiVersion: v1
kind: Secret
metadata:
  name: cia-backend-secrets
type: Opaque
stringData:
  DB_URL: "jdbc:postgresql://REPLACE_ME:5432/cia"
  DB_USERNAME: "cia"
  DB_PASSWORD: "REPLACE_ME"
  PII_ENCRYPTION_KEY: "REPLACE_ME_32B_BASE64"      # ProductionSafetyValidator rejects the dev default in prod
  WEBHOOK_SIGNING_SECRET: "REPLACE_ME"
  STORAGE_ACCESS_KEY: "REPLACE_ME"
  STORAGE_SECRET_KEY: "REPLACE_ME"
  KEYCLOAK_ADMIN_CLIENT_ID: "cia-admin"
  KEYCLOAK_ADMIN_CLIENT_SECRET: "REPLACE_ME"
  SENDGRID_API_KEY: ""                              # only if cia.notifications.email.provider=sendgrid
```

- [ ] **Step 2: Create `values-prod.example.yaml`**

```yaml
# Example prod overrides: helm upgrade --install cia-api deploy/helm/cia-backend \
#   -f deploy/helm/cia-backend/values-prod.example.yaml --set image.tag=<commit-sha>
#
# REQUIRED at deploy time (NOT committed here): the cia-backend-secrets Secret must exist,
# and BOTH SPRING_PROFILES_ACTIVE=prod (below) AND CIA_DEPLOYMENT_ENVIRONMENT=production
# (below) must be set — neither implies the other (ProductionSafetyValidator keys off the marker).
image:
  tag: ""                         # set via --set image.tag=<sha> on the command line
replicaCount: 3
existingSecret: cia-backend-secrets
env:
  SPRING_PROFILES_ACTIVE: prod
  CIA_DEPLOYMENT_ENVIRONMENT: production
  SERVER_PORT: "8090"
  CIA_TENANT_BOOTSTRAP_ENABLED: "false"
  CIA_KEYCLOAK_ADMIN_ENABLED: "true"
  DB_POOL_MAX: "10"
  DB_POOL_MIN: "10"
  KEYCLOAK_URL: "https://auth.cia.app"
  TEMPORAL_HOST: "temporal.internal:7233"
  STORAGE_TYPE: s3
  STORAGE_ENDPOINT: "https://s3.amazonaws.com"
  STORAGE_BUCKET: cia-documents
  PARTNER_API_RATE_LIMIT_STORE: redis
ingress:
  enabled: true
  className: nginx
  host: "*.cia.app"
  tlsSecretName: cia-backend-tls
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
```

- [ ] **Step 3: Create `templates/NOTES.txt`**

```
cia-backend deployed as release {{ .Release.Name }} (image tag {{ .Values.image.tag }}).

REQUIRED before the pods become healthy:
  1. The Secret "{{ .Values.existingSecret }}" must exist in namespace {{ .Release.Namespace }}.
     See secret.example.yaml for the required keys.
  2. The pod env sets SPRING_PROFILES_ACTIVE={{ .Values.env.SPRING_PROFILES_ACTIVE }} and
     CIA_DEPLOYMENT_ENVIRONMENT={{ .Values.env.CIA_DEPLOYMENT_ENVIRONMENT }}.
     For a real production deploy BOTH must be prod/production — the app fail-fasts otherwise.

Backing services (Postgres, Keycloak, Temporal, object storage, Redis) are EXTERNAL —
supply their endpoints via the ConfigMap env (URLs) and the Secret (credentials).

The management surface (/actuator/**) is intentionally NOT exposed on the Ingress.
Scrape metrics in-cluster via the Service at :{{ .Values.service.port }}/actuator/prometheus.
```

- [ ] **Step 4: Create `README.md`**

```markdown
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
`/actuator/prometheus` in-cluster via the Service.

## Validate locally

```bash
helm lint deploy/helm/cia-backend --set image.tag=dummy
helm template cia-api deploy/helm/cia-backend --set image.tag=dummy | kubeconform -strict -summary -
bash deploy/helm/cia-backend/smoke/smoke-test.sh   # needs Docker + kind
```
```

- [ ] **Step 5: Verify the prod example renders + schema-validates**

Run: `helm template cia-api deploy/helm/cia-backend -f deploy/helm/cia-backend/values-prod.example.yaml --set image.tag=dummy | kubeconform -strict -summary -`
Expected: `Valid: 7`, `Invalid: 0`, `Errors: 0`.

- [ ] **Step 6: Verify secret.example.yaml is itself valid k8s (catch typos in the contract)**

Run: `kubeconform -strict -summary deploy/helm/cia-backend/secret.example.yaml`
Expected: `Valid: 1`, `Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add deploy/helm/cia-backend/secret.example.yaml deploy/helm/cia-backend/values-prod.example.yaml \
        deploy/helm/cia-backend/templates/NOTES.txt deploy/helm/cia-backend/README.md
git commit -m "docs(deploy): secret contract, prod example values, NOTES + README"
```

---

## Task 6: kind smoke fixtures + script

The ephemeral Postgres + Temporal fixtures and the smoke driver, extracted into a committed script so it runs identically in CI and locally.

**Files:**
- Create: `deploy/helm/cia-backend/smoke/fixtures/postgres.yaml`
- Create: `deploy/helm/cia-backend/smoke/fixtures/temporal.yaml`
- Create: `deploy/helm/cia-backend/smoke/smoke-test.sh`

- [ ] **Step 1: Create `smoke/fixtures/postgres.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cia-smoke-postgres
  labels: { app: cia-smoke-postgres }
spec:
  replicas: 1
  selector:
    matchLabels: { app: cia-smoke-postgres }
  template:
    metadata:
      labels: { app: cia-smoke-postgres }
    spec:
      containers:
        - name: postgres
          image: postgres:16
          env:
            - { name: POSTGRES_USER, value: cia }
            - { name: POSTGRES_PASSWORD, value: cia_dev }
            - { name: POSTGRES_DB, value: cia }
          ports:
            - containerPort: 5432
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "cia"]
            periodSeconds: 5
            failureThreshold: 20
---
apiVersion: v1
kind: Service
metadata:
  name: cia-smoke-postgres
spec:
  selector: { app: cia-smoke-postgres }
  ports:
    - port: 5432
      targetPort: 5432
```

- [ ] **Step 2: Create `smoke/fixtures/temporal.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cia-smoke-temporal
  labels: { app: cia-smoke-temporal }
spec:
  replicas: 1
  selector:
    matchLabels: { app: cia-smoke-temporal }
  template:
    metadata:
      labels: { app: cia-smoke-temporal }
    spec:
      containers:
        - name: temporal
          image: temporalio/auto-setup:1.25.0
          env:
            - { name: DB, value: postgres12 }
            - { name: DB_PORT, value: "5432" }
            - { name: POSTGRES_USER, value: cia }
            - { name: POSTGRES_PWD, value: cia_dev }
            - { name: POSTGRES_SEEDS, value: cia-smoke-postgres }
            - { name: BIND_ON_IP, value: 0.0.0.0 }
          ports:
            - containerPort: 7233
          readinessProbe:
            tcpSocket:
              port: 7233
            initialDelaySeconds: 20
            periodSeconds: 5
            failureThreshold: 30
---
apiVersion: v1
kind: Service
metadata:
  name: cia-smoke-temporal
spec:
  selector: { app: cia-smoke-temporal }
  ports:
    - port: 7233
      targetPort: 7233
```

- [ ] **Step 3: Create `smoke/smoke-test.sh`**

```bash
#!/usr/bin/env bash
# Full-dependency kind smoke for the cia-backend chart: builds the real image,
# loads it into kind, stands up ephemeral Postgres + Temporal, installs the chart,
# and asserts the cia-api pod reaches Ready + /actuator/health == 200.
#
# Assumes a kind cluster already exists and kubectl targets it (CI uses helm/kind-action;
# locally: `kind create cluster --name cia-smoke`). Requires Docker, helm, kubectl.
set -euo pipefail

KIND_CLUSTER="${KIND_CLUSTER:-cia-smoke}"
IMAGE="${IMAGE:-cia-backend:smoke}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
CHART="$REPO_ROOT/deploy/helm/cia-backend"
FIXTURES="$CHART/smoke/fixtures"

echo "==> Building backend image $IMAGE"
docker build -t "$IMAGE" -f "$REPO_ROOT/cia-backend/Dockerfile" "$REPO_ROOT/cia-backend"

echo "==> Loading image into kind cluster $KIND_CLUSTER"
kind load docker-image "$IMAGE" --name "$KIND_CLUSTER"

echo "==> Applying Postgres + Temporal fixtures"
kubectl apply -f "$FIXTURES/postgres.yaml"
kubectl rollout status deploy/cia-smoke-postgres --timeout=120s
kubectl apply -f "$FIXTURES/temporal.yaml"
kubectl rollout status deploy/cia-smoke-temporal --timeout=240s

echo "==> Creating cia-api secret (dev creds; CIA_DEPLOYMENT_ENVIRONMENT=local disarms the weak-default guard)"
kubectl delete secret cia-backend-secrets --ignore-not-found
kubectl create secret generic cia-backend-secrets \
  --from-literal=DB_URL='jdbc:postgresql://cia-smoke-postgres:5432/cia' \
  --from-literal=DB_USERNAME='cia' \
  --from-literal=DB_PASSWORD='cia_dev' \
  --from-literal=PII_ENCRYPTION_KEY='dev-pii-key-do-not-use-in-prod-CHANGE-ME' \
  --from-literal=WEBHOOK_SIGNING_SECRET='dev-secret-replace-in-prod' \
  --from-literal=STORAGE_ACCESS_KEY='minioadmin' \
  --from-literal=STORAGE_SECRET_KEY='minioadmin'

echo "==> Installing chart"
# fullnameOverride=cia-api makes every resource (Deployment, Service) named exactly
# "cia-api", so the rollout target and the curl host below are simply "cia-api".
helm upgrade --install cia-api "$CHART" \
  --set fullnameOverride=cia-api \
  --set image.repository="${IMAGE%:*}" \
  --set image.tag="${IMAGE##*:}" \
  --set image.pullPolicy=IfNotPresent \
  --set ingress.enabled=false \
  --set hpa.enabled=false \
  --set replicaCount=1 \
  --set env.CIA_DEPLOYMENT_ENVIRONMENT=local \
  --set env.TEMPORAL_HOST='cia-smoke-temporal:7233' \
  --set env.KEYCLOAK_URL='http://unused.local' \
  --set env.STORAGE_TYPE=minio \
  --set env.STORAGE_ENDPOINT='http://unused.local:9000'

echo "==> Waiting for cia-api rollout (proves the image boots against real Postgres + Temporal)"
kubectl rollout status deploy/cia-api --timeout=300s

echo "==> Asserting /actuator/health == 200"
kubectl run smoke-curl --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
  curl -sf -o /dev/null -w '%{http_code}' http://cia-api:8090/actuator/health | grep -q 200

echo "==> SMOKE PASSED"
```

NOTE on resource names: the install pins `fullnameOverride=cia-api`, so the Deployment and Service are both named `cia-api` (hence `deploy/cia-api` and the `cia-api:8090` Service DNS). Without the override, `cia-backend.fullname` would render `cia-api-cia-backend` (release + chart name) and these references would have to change.

- [ ] **Step 4: Make the script executable + commit**

```bash
chmod +x deploy/helm/cia-backend/smoke/smoke-test.sh
git add deploy/helm/cia-backend/smoke/
git update-index --chmod=+x deploy/helm/cia-backend/smoke/smoke-test.sh
git commit -m "feat(deploy): kind smoke fixtures (Postgres + Temporal) + driver script"
```

- [ ] **Step 5 (optional local run — only if Docker is available): execute the smoke**

```bash
kind create cluster --name cia-smoke
KIND_CLUSTER=cia-smoke bash deploy/helm/cia-backend/smoke/smoke-test.sh
kind delete cluster --name cia-smoke
```
Expected: ends with `==> SMOKE PASSED`. If Docker is unavailable locally, skip — Task 7's CI runs it. Do NOT commit anything from this step.

---

## Task 7: CI workflow `helm-chart.yml`

Gate every change: `helm lint` + `kubeconform` + the kind smoke.

**Files:**
- Create: `.github/workflows/helm-chart.yml`

- [ ] **Step 1: Create `.github/workflows/helm-chart.yml`**

```yaml
name: Helm Chart

# Validates the cia-backend Helm chart: lint + kubeconform schema validation on
# every change, plus a full-dependency kind smoke that boots the real image
# against ephemeral Postgres + Temporal and asserts /actuator/health == 200.

on:
  push:
    branches: [main]
    paths: ['deploy/helm/**', 'cia-backend/**', '.github/workflows/helm-chart.yml']
  pull_request:
    branches: [main]
    paths: ['deploy/helm/**', 'cia-backend/**', '.github/workflows/helm-chart.yml']
  workflow_dispatch:

jobs:
  lint-and-validate:
    name: helm lint + kubeconform
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
        with:
          version: v3.16.2
      - name: Install kubeconform
        run: |
          curl -sSL -o /tmp/kubeconform.tar.gz \
            https://github.com/yannh/kubeconform/releases/download/v0.6.7/kubeconform-linux-amd64.tar.gz
          tar -xzf /tmp/kubeconform.tar.gz -C /tmp
          sudo install /tmp/kubeconform /usr/local/bin/
      - name: helm lint
        run: helm lint deploy/helm/cia-backend --set image.tag=dummy
      - name: helm template | kubeconform (default values)
        run: |
          helm template cia-api deploy/helm/cia-backend --set image.tag=dummy \
            | kubeconform -strict -summary -
      - name: helm template | kubeconform (prod example values)
        run: |
          helm template cia-api deploy/helm/cia-backend \
            -f deploy/helm/cia-backend/values-prod.example.yaml --set image.tag=dummy \
            | kubeconform -strict -summary -

  kind-smoke:
    name: kind smoke (Postgres + Temporal + chart)
    runs-on: ubuntu-latest
    needs: lint-and-validate
    steps:
      - uses: actions/checkout@v4
      - uses: azure/setup-helm@v4
        with:
          version: v3.16.2
      - name: Create kind cluster
        uses: helm/kind-action@v1.10.0
        with:
          cluster_name: cia-smoke
      - name: Run smoke test
        env:
          KIND_CLUSTER: cia-smoke
          IMAGE: cia-backend:smoke
        run: bash deploy/helm/cia-backend/smoke/smoke-test.sh
      - name: Dump diagnostics on failure
        if: failure()
        run: |
          kubectl get pods -A
          kubectl describe deploy/cia-api || true
          kubectl logs deploy/cia-api --tail=200 || true
          kubectl logs deploy/cia-smoke-temporal --tail=100 || true
```

- [ ] **Step 2: Validate the workflow YAML is well-formed**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/helm-chart.yml')); print('workflow YAML OK')"`
Expected: `workflow YAML OK`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/helm-chart.yml
git commit -m "ci(deploy): helm-chart workflow — lint + kubeconform + kind smoke"
```

- [ ] **Step 4: Push the branch and confirm the workflow is green in GitHub Actions**

This is the authoritative validation of the smoke (it needs Docker + kind, which CI provides). After pushing the feature branch:

Run: `gh run list --workflow=helm-chart.yml --limit 1`
Then watch: `gh run watch <run-id>` (or `gh run view <run-id> --log-failed` on failure).
Expected: both jobs (`lint-and-validate`, `kind-smoke`) conclude `success`. If `kind-smoke` fails, read the "Dump diagnostics on failure" step — most likely causes: the Service DNS name in the curl assertion (must match `cia-api-cia-backend`), or Temporal not Ready within the timeout (bump the fixture `failureThreshold`). Fix, recommit, re-push until green. **Do not proceed to Task 8 until the workflow is green.**

---

## Task 8: Docs (CLAUDE.md §10) + backlog reconciliation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `cia-log.md`

- [ ] **Step 1: Update CLAUDE.md §10 frontend/deployment**

In `CLAUDE.md`, under "### 10. Deployment Architecture" → "#### Production (Kubernetes)", add a sentence after the `Deployments:` block pointing at the real chart:

```
**Backend Helm chart (Slice B):** `deploy/helm/cia-backend/` — deploys cia-api only (Deployment + Service + Ingress[/api,/partner only — never /actuator] + HPA[3→10 @70% CPU] + PDB[minAvailable 2] + ConfigMap + ServiceAccount). The 5 backing services are **external** — endpoints via the ConfigMap env (URLs) + a pre-existing `Secret` referenced by `values.existingSecret` (the chart never templates secret values). A real prod deploy MUST set BOTH `SPRING_PROFILES_ACTIVE=prod` AND `CIA_DEPLOYMENT_ENVIRONMENT=production`. Validated in CI (`helm-chart.yml`) by `helm lint` + `kubeconform` + a kind smoke that boots the real image against ephemeral Postgres + Temporal and asserts `/actuator/health` 200. Live cloud provisioning (cluster/DNS/secret-store/managed backing services) is a separate go-live step.
```

- [ ] **Step 2: Drain the backlog row in cia-log.md**

In `cia-log.md`, under "## Tracked follow-up items", **delete** the `prod-deployability-k8s-manifests` table row (landed by this slice).

- [ ] **Step 3: Add + narrow backlog rows in cia-log.md**

In the same table, **add** this row (after the `prometheus-endpoint-authz` row):

```
| temporal-eager-boot-dial | P2 | `TemporalConfig.workflowServiceStubs` dials Temporal eagerly/unguarded at context init | Surfaced in Slice B. `WorkflowServiceStubs.newInstance(...)` is an unguarded `@Bean` (no `@ConditionalOnProperty`, runs a health-check on creation), so a cia-api pod **crashloops** if Temporal is briefly unreachable at startup, and the kind smoke is forced to stand up a full Temporal fixture (the entire IT suite `@MockBean`s the 3 Temporal beans; no Temporal Testcontainer exists). Fix (the deferred Slice-B "B2"): make the stubs lazy / `setDisableHealthCheck(true)` (or `@ConditionalOnProperty` + start-degraded) — hardens prod startup AND lets the smoke drop the Temporal fixture (Postgres-only). |
```

And **edit** the existing `prometheus-endpoint-authz` row's note to record the public-ingress half is now closed: append to its Notes — `Public-ingress half CLOSED in Slice B (the chart routes only /api + /partner — /actuator/** is never publicly reachable). Remaining: in-cluster scrape auth + a NetworkPolicy restricting /actuator/prometheus to the Prometheus pod, to add when a real Prometheus is wired.`

- [ ] **Step 4: Update the Slice B cia-log.md entry from DESIGN PHASE to COMPLETE**

In `cia-log.md`, change the `## 2026-06-07 — Slice B` heading suffix `— DESIGN PHASE (brainstorm + spec committed, no code yet)` to `— COMPLETE`, and replace its "**Known follow-ups / backlog reconciliation:**" paragraph with:

```
**Known follow-ups / backlog reconciliation:** Slice landed. **Drained 1 row:** `prod-deployability-k8s-manifests` (P1). **Added 1 row:** `temporal-eager-boot-dial` (P2 — the deferred B2 fix). **Narrowed 1 row:** `prometheus-endpoint-authz` (P3) — public-ingress half closed (chart routes only /api+/partner); the in-cluster scrape-auth/NetworkPolicy half stays open. The Helm chart + `helm-chart.yml` CI (lint + kubeconform + kind smoke) are green. No app/Java code changed. **Next epic:** Tenant-Onboarding API + Platform-Admin UI (separate brainstorm).
```

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md cia-log.md
git commit -m "docs(slice-b): CLAUDE.md §10 chart pointer + backlog reconciliation

Drain prod-deployability-k8s-manifests; add temporal-eager-boot-dial (P2);
narrow prometheus-endpoint-authz to the in-cluster scrape-auth half; mark
Slice B COMPLETE."
```

---

## Self-Review Notes (filled in by the plan author)

- **Spec coverage:** §1 layout → Task 1; §2 values → Task 1; §3 Deployment → Task 2; §4 Service/Ingress → Task 3; §5 HPA/PDB → Task 4; §6 Secret contract → Task 5; §7 bootstrap defaults → Task 1 (`values.env`) + Task 5 NOTES; §8 CI lint+kubeconform+kind smoke → Tasks 6+7; §9 docs → Task 5 (README/NOTES) + Task 8 (CLAUDE.md); §10 backlog → Task 8. All spec sections mapped.
- **No-app-change invariant:** no task touches `cia-backend/**` Java or `application*.yml`. The smoke supplies `CIA_DEPLOYMENT_ENVIRONMENT=local` + the dev PII key so the prod profile boots without tripping `ProductionSafetyValidator`/`PiiKeyValidator` (spec §8). Confirmed no Java edits.
- **Name consistency (resolved):** `cia-backend.fullname` with release `cia-api` would render `cia-api-cia-backend`, which mismatched the smoke's `deploy/cia-api` rollout target and `cia-api:8090` curl host. The smoke install pins `--set fullnameOverride=cia-api`, so the Deployment and Service are both named exactly `cia-api` — `deploy/cia-api` (rollout + CI diagnostics) and `cia-api:8090` (curl host) are all consistent. The non-smoke prod install (no override) names resources `<release>-cia-backend`, which is fine since prod reaches the app via the Ingress, not by hard-coded resource name.
- **Resource/replica/probe values** match the spec (requests 500m/1Gi, limits 1/1.5Gi, HPA 3→10 @70, PDB minAvailable 2, startupProbe ×30@10s).
- **Validation counts:** ServiceAccount(1)+ConfigMap(1)+Deployment(1)+Service(1)+Ingress(1)+HPA(1)+PDB(1) = 7 manifests with default values — matches the Task 4 `Valid: 7` assertion.
