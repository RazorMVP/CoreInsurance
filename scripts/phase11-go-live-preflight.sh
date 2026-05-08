#!/usr/bin/env bash
set -euo pipefail

allow_dirty=false
allow_placeholders=false
env_file=""
evidence_dir=""
image_ref=""
release_commit=""
skip_gh=false

usage() {
  cat >&2 <<'USAGE'
Usage: scripts/phase11-go-live-preflight.sh --env-file <env-file> [options]

Options:
  --allow-dirty            Allow a dirty working tree for dry runs.
  --allow-placeholders     Allow placeholders in the env file. Use only for example-file checks.
  --evidence-dir <dir>     Evidence output directory. Defaults to phase11-evidence/<timestamp>.
  --image-ref <ref>        Immutable backend image tag or digest expected for rehearsal.
  --release-commit <sha>   Release commit to record. Defaults to HEAD.
  --skip-gh                Skip GitHub Actions run collection.
  -h, --help               Show this help.

The script writes redacted evidence only. Do not place real secrets in output.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-dirty)
      allow_dirty=true
      shift
      ;;
    --allow-placeholders)
      allow_placeholders=true
      shift
      ;;
    --evidence-dir)
      evidence_dir="${2:-}"
      shift 2
      ;;
    --env-file)
      env_file="${2:-}"
      shift 2
      ;;
    --image-ref)
      image_ref="${2:-}"
      shift 2
      ;;
    --release-commit)
      release_commit="${2:-}"
      shift 2
      ;;
    --skip-gh)
      skip_gh=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

if [[ -z "$env_file" ]]; then
  usage
  exit 2
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if [[ ! -f "$env_file" ]]; then
  echo "Environment file not found: $env_file" >&2
  exit 1
fi

timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
if [[ -z "$evidence_dir" ]]; then
  evidence_dir="phase11-evidence/$timestamp"
fi

mkdir -p "$evidence_dir"

if [[ -z "$release_commit" ]]; then
  release_commit="$(git rev-parse HEAD)"
fi

branch="$(git branch --show-current)"
dirty_status="$(git status --short)"

redact() {
  sed -E \
    -e 's/((DB_PASSWORD|PII_ENCRYPTION_KEY|WEBHOOK_SIGNING_SECRET|STORAGE_ACCESS_KEY|STORAGE_SECRET_KEY|SMTP_USERNAME|SMTP_PASSWORD|KYC_API_KEY|KYC_CLIENT_SECRET|NAICOM_API_KEY|NAICOM_CLIENT_SECRET|NIID_API_KEY|NIID_CLIENT_SECRET)[=:][[:space:]]*)[^[:space:]]+/\1[REDACTED]/g' \
    -e 's/((password|secret|token|api[_-]?key|access[_-]?key)[": ]+[=:]?[[:space:]]*)[^",[:space:]]+/\1[REDACTED]/Ig'
}

run_capture() {
  local name="$1"
  shift
  local output_file="$evidence_dir/$name"
  {
    printf '$'
    printf ' %q' "$@"
    printf '\n\n'
    "$@" 2>&1
  } | redact > "$output_file"
}

record_status() {
  local status="$1"
  local message="$2"
  printf '%s %s\n' "$status" "$message" | tee -a "$evidence_dir/summary.txt"
}

cat > "$evidence_dir/manifest.md" <<EOF
# Phase 11 Go-Live Preflight Evidence

Generated: $timestamp

| Field | Value |
| --- | --- |
| Branch | \`$branch\` |
| Release commit | \`$release_commit\` |
| Environment file | \`$env_file\` |
| Evidence directory | \`$evidence_dir\` |
| Backend image reference | \`${image_ref:-not supplied}\` |
| Placeholders allowed | \`$allow_placeholders\` |
| Dirty working tree allowed | \`$allow_dirty\` |

This bundle intentionally redacts known secret keys and should be attached to
the Phase 11 evidence register after review.
EOF

printf '' > "$evidence_dir/summary.txt"

if [[ -n "$dirty_status" && "$allow_dirty" == "false" ]]; then
  printf '%s\n' "$dirty_status" > "$evidence_dir/git-status.txt"
  record_status "FAIL" "Working tree is dirty. Commit or stash changes, or rerun with --allow-dirty for a dry run."
  exit 1
fi

run_capture "git-status.txt" git status --short --branch
run_capture "git-log.txt" git log --oneline -10

validate_args=()
if [[ "$allow_placeholders" == "true" ]]; then
  validate_args+=(--allow-placeholders)
fi
validate_args+=("$env_file")
if run_capture "production-env-preflight.txt" scripts/validate-production-env.sh "${validate_args[@]}"; then
  record_status "PASS" "Production environment preflight completed for $env_file."
else
  record_status "FAIL" "Production environment preflight failed for $env_file."
  exit 1
fi

if run_capture "production-compose-config.txt" docker compose --env-file "$env_file" -f docker/production/docker-compose.yml config; then
  record_status "PASS" "Production Compose rendered successfully with redacted output."
else
  record_status "FAIL" "Production Compose render failed for $env_file."
  exit 1
fi

if [[ -n "$image_ref" ]]; then
  if [[ "$image_ref" == *":latest" ]]; then
    record_status "FAIL" "Image reference must be immutable and must not use :latest."
    exit 1
  fi
  printf '%s\n' "$image_ref" > "$evidence_dir/backend-image-ref.txt"
  record_status "PASS" "Immutable backend image reference recorded."
else
  record_status "WARN" "No --image-ref supplied; record the exact release image digest before approval."
fi

if [[ "$skip_gh" == "false" ]]; then
  if command -v gh >/dev/null 2>&1; then
    run_capture "github-runs.txt" gh run list --branch "$branch" --limit 10
    record_status "PASS" "GitHub workflow run list captured."
  else
    record_status "WARN" "GitHub CLI is not installed; attach Backend Image and CI run URLs manually."
  fi
fi

cat > "$evidence_dir/remaining-evidence.md" <<'EOF'
# Remaining Manual Evidence

Attach these items before requesting live deployment approval:

- Live KYC contract test output.
- Live NAICOM contract test output.
- Live NIID contract test output.
- Database backup or snapshot reference.
- Migration job log and final Flyway version.
- Readiness response showing `UP`.
- Smoke test output for auth, tenant isolation, customer, quote, policy, claim, finance, reports, setup, audit, partner API, and webhooks.
- Monitoring import confirmation for Prometheus alerts and Grafana dashboard.
- Rollback rehearsal result or approved forward-fix decision.
- Business, technical, security, and operations sign-off.
EOF

record_status "PASS" "Phase 11 go-live preflight bundle created at $evidence_dir."
