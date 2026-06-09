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
  --set env.STORAGE_ENDPOINT='http://unused.local:9000' \
  --set env.MANAGEMENT_HEALTH_REDIS_ENABLED=false \
  --set env.MANAGEMENT_HEALTH_MAIL_ENABLED=false

echo "==> Waiting for cia-api rollout (proves the image boots against real Postgres + Temporal)"
kubectl rollout status deploy/cia-api --timeout=300s

echo "==> Asserting /actuator/health == 200"
# The aggregate /actuator/health folds in db (must be UP) but NOT redis/mail —
# those indicators are disabled above because the smoke deliberately omits Redis
# and SMTP; otherwise an intentionally-absent optional service would 503 the
# aggregate even though the app is healthy. Self-diagnosing: print the code, and
# dump the body on mismatch so any remaining DOWN indicator is visible in the log.
# Pre-delete in case a previous hard-killed run left the pod behind (--rm only cleans up on normal exit).
kubectl delete pod smoke-curl --ignore-not-found
code="$(kubectl run smoke-curl --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
  curl -s --max-time 10 -o /dev/null -w '%{http_code}' http://cia-api:8090/actuator/health 2>/dev/null)"
echo "health HTTP code: ${code}"
if ! printf '%s' "${code}" | grep -q 200; then
  echo "Health endpoint did not return 200 — body for diagnosis:"
  kubectl delete pod smoke-curl-body --ignore-not-found
  kubectl run smoke-curl-body --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
    curl -s --max-time 10 http://cia-api:8090/actuator/health 2>/dev/null || true
  echo
  exit 1
fi

echo "==> SMOKE PASSED"
