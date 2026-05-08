#!/usr/bin/env bash
set -euo pipefail

allow_placeholders=false
env_file=""

usage() {
  echo "Usage: $0 [--allow-placeholders] <env-file>" >&2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-placeholders)
      allow_placeholders=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      usage
      exit 2
      ;;
    *)
      if [[ -n "$env_file" ]]; then
        usage
        exit 2
      fi
      env_file="$1"
      shift
      ;;
  esac
done

if [[ -z "$env_file" ]]; then
  usage
  exit 2
fi

if [[ ! -f "$env_file" ]]; then
  echo "Environment file not found: $env_file" >&2
  exit 1
fi

required_vars=(
  CIA_BACKEND_IMAGE
  SPRING_PROFILES_ACTIVE
  CIA_ENV
  DB_URL
  DB_USERNAME
  DB_PASSWORD
  KEYCLOAK_URL
  KEYCLOAK_REALM
  REDIS_HOST
  TEMPORAL_HOST
  TEMPORAL_NAMESPACE
  STORAGE_TYPE
  STORAGE_ENDPOINT
  STORAGE_BUCKET
  STORAGE_ACCESS_KEY
  STORAGE_SECRET_KEY
  STORAGE_REGION
  PII_ENCRYPTION_KEY
  WEBHOOK_SIGNING_SECRET
  PARTNER_TOKEN_URL
  KYC_PROVIDER
  KYC_PROVIDER_URL
  NAICOM_MODE
  NAICOM_API_URL
  NIID_MODE
  NIID_API_URL
  SMTP_HOST
  SMTP_USERNAME
  SMTP_PASSWORD
  RATE_LIMIT_ENABLED
)

failures=0

value_for() {
  local name="$1"
  local line
  line="$(grep -E "^[[:space:]]*${name}=" "$env_file" | tail -n 1 || true)"
  line="${line#*=}"
  line="${line%$'\r'}"
  line="${line%\"}"
  line="${line#\"}"
  line="${line%\'}"
  line="${line#\'}"
  printf '%s' "$line"
}

fail() {
  echo "ERROR: $1" >&2
  failures=$((failures + 1))
}

for name in "${required_vars[@]}"; do
  value="$(value_for "$name")"
  if [[ -z "$value" ]]; then
    fail "$name is required and must not be empty."
  fi
done

spring_profile="$(value_for SPRING_PROFILES_ACTIVE)"
cia_env="$(value_for CIA_ENV)"
backend_image="$(value_for CIA_BACKEND_IMAGE)"
pii_key="$(value_for PII_ENCRYPTION_KEY)"
webhook_secret="$(value_for WEBHOOK_SIGNING_SECRET)"
kyc_provider="$(value_for KYC_PROVIDER)"
naicom_mode="$(value_for NAICOM_MODE)"
niid_mode="$(value_for NIID_MODE)"
rate_limit_enabled="$(value_for RATE_LIMIT_ENABLED)"

if [[ "$spring_profile" =~ (^|,)(dev|test)(,|$) ]]; then
  fail "SPRING_PROFILES_ACTIVE must not include dev or test for a production rehearsal."
fi

if [[ "$cia_env" != "prod" && "$cia_env" != "production" ]]; then
  fail "CIA_ENV must be prod or production for a production rehearsal."
fi

if [[ "$kyc_provider" == "mock" ]]; then
  fail "KYC_PROVIDER must not be mock for a production rehearsal."
fi

if [[ "$naicom_mode" == "stub" ]]; then
  fail "NAICOM_MODE must not be stub for a production rehearsal."
fi

if [[ "$niid_mode" == "stub" ]]; then
  fail "NIID_MODE must not be stub for a production rehearsal."
fi

if [[ "$rate_limit_enabled" != "true" ]]; then
  fail "RATE_LIMIT_ENABLED must be true for a production rehearsal."
fi

if [[ "$allow_placeholders" == "false" ]]; then
  if grep -Eiq 'replace-with|example\.com|example\.internal|localhost|127\.0\.0\.1' "$env_file"; then
    fail "environment file still contains placeholders or local endpoints."
  fi

  if [[ "$backend_image" == *":latest" ]]; then
    fail "CIA_BACKEND_IMAGE must use an immutable tag or digest, not latest."
  fi

  if [[ ${#pii_key} -lt 32 ]]; then
    fail "PII_ENCRYPTION_KEY must be at least 32 characters."
  fi

  if [[ ${#webhook_secret} -lt 32 ]]; then
    fail "WEBHOOK_SIGNING_SECRET must be at least 32 characters."
  fi
fi

if [[ $failures -gt 0 ]]; then
  echo "Production environment preflight failed with $failures issue(s)." >&2
  exit 1
fi

echo "Production environment preflight passed for $env_file."
