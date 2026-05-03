#!/usr/bin/env bash
# CI guard: enforce that every form submit hits useMutation, every list/detail
# page hits useQuery, and no stale mock arrays sneak back in. Catches the H2/M1
# regressions documented in CLAUDE.md (Frontend API wiring rules).
#
# Opt out a legitimate fallback by adding a comment immediately above the
# declaration:
#
#   // allow-mock: <one-line reason>
#   const mockFallback = [...]
#
# The reason ends up in `git blame` so future readers know why it survives.

set -uo pipefail

ROOT="cia-frontend/apps/back-office/src/modules"

if [ ! -d "$ROOT" ]; then
  echo "✗ $ROOT not found — run from repo root."
  exit 2
fi

VIOLATIONS=0

# Helper: emit a violation if the line above the match doesn't contain the
# allow-mock opt-out marker.
report_with_optout() {
  local label="$1"; shift
  local matches="$1"
  [ -z "$matches" ] && return 0

  while IFS= read -r match; do
    [ -z "$match" ] && continue
    local file line content
    file=$(printf '%s' "$match" | cut -d: -f1)
    line=$(printf '%s' "$match" | cut -d: -f2)
    content=$(printf '%s' "$match" | cut -d: -f3-)

    # Check the previous line for the opt-out marker.
    if [ "$line" -gt 1 ]; then
      local prev
      prev=$(sed -n "$((line - 1))p" "$file" 2>/dev/null || true)
      if printf '%s' "$prev" | grep -qE '//[[:space:]]*allow-mock:'; then
        continue
      fi
    fi

    echo "  ✗ $label"
    echo "      $file:$line"
    echo "      → $(printf '%s' "$content" | sed 's/^[[:space:]]*//' | cut -c1-100)"
    VIOLATIONS=$((VIOLATIONS + 1))
  done <<< "$matches"
}

echo "Checking $ROOT for API-wiring violations..."
echo ""

# ── Rule 1: console.log in module code ───────────────────────────────────────
# Even outside onSubmit, console.log shouldn't ship to production. Allow
# explicit opt-outs (e.g. dev-only debug temporarily kept while triaging).
matches=$(grep -rEnH 'console\.log\(' --include='*.tsx' --include='*.ts' "$ROOT" 2>/dev/null || true)
report_with_optout "console.log in module code (use logger or remove)" "$matches"

# ── Rule 2: top-level mock array declarations ────────────────────────────────
# Match `const mockX = [` or `const MOCK_X = [` at line start. Inline mocks
# inside test files or hooks/helpers/ subdirs are out of scope — those
# directories aren't covered by this scan.
matches=$(grep -rEnH '^const (mock[A-Z][A-Za-z0-9_]*|MOCK_[A-Z0-9_]+)\s*[:=]' \
  --include='*.tsx' --include='*.ts' "$ROOT" 2>/dev/null || true)
report_with_optout "top-level mock declaration (wire to useQuery or mark with // allow-mock:)" "$matches"

# ── Rule 3: stale TODOs that mean "I haven't wired this yet" ─────────────────
matches=$(grep -rEnH '//[[:space:]]*TODO:?[[:space:]]*(useMutation|useQuery|useCreate|useUpdate)' \
  --include='*.tsx' --include='*.ts' "$ROOT" 2>/dev/null || true)
report_with_optout "stale TODO — wire the hook now" "$matches"

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
if [ "$VIOLATIONS" -gt 0 ]; then
  echo "✗ $VIOLATIONS API-wiring violation(s) found."
  echo ""
  echo "  Fix options:"
  echo "    1. Replace mock data with useQuery / form submits with useMutation."
  echo "    2. If the mock is a deliberate fallback, add this comment above it:"
  echo ""
  echo "         // allow-mock: <one-line reason>"
  echo "         const mockFallback = [...]"
  echo ""
  echo "  See CLAUDE.md → 'Frontend API wiring rules' for the full convention."
  exit 1
fi

echo "✓ No API-wiring violations."
exit 0
