#!/usr/bin/env bash
# CIA SESSION COMPLETION GATE — automated validator
# Exit 0 = all clear (session ends normally)
# Exit 2 = gate items missing (asyncRewake wakes Claude to fix them)
#
# Checks:
#   1. No uncommitted changes (code without logging)
#   2. If last commit touched cia-frontend/**, cia-log.md must also be in that commit
#   3. cia-log.md was updated today

REPO="$(git rev-parse --show-toplevel 2>/dev/null)"
if [ -z "$REPO" ]; then exit 0; fi   # not a git repo, skip

TODAY=$(date +%Y-%m-%d)
ISSUES=()

# ── 1. Uncommitted changes? ────────────────────────────────────────────────
UNSTAGED=$(git -C "$REPO" diff --name-only 2>/dev/null)
STAGED=$(git -C "$REPO" diff --cached --name-only 2>/dev/null)
if [ -n "$UNSTAGED" ] || [ -n "$STAGED" ]; then
    CHANGED=$(echo "$UNSTAGED $STAGED" | tr ' ' '\n' | grep -v '^$' | head -5 | tr '\n' ', ' | sed 's/,$//')
    ISSUES+=("Uncommitted changes: $CHANGED — commit or stash before closing.")
fi

# ── 2. Last commit had frontend changes but no cia-log.md update? ──────────
LAST_COMMIT_FILES=$(git -C "$REPO" diff --name-only HEAD~1 HEAD 2>/dev/null)
HAS_FRONTEND=$(echo "$LAST_COMMIT_FILES" | grep -c "cia-frontend/" || true)
HAS_LOG=$(echo "$LAST_COMMIT_FILES" | grep -c "cia-log.md" || true)
if [ "$HAS_FRONTEND" -gt 0 ] && [ "$HAS_LOG" -eq 0 ]; then
    ISSUES+=("Last commit changed cia-frontend/ but cia-log.md was NOT updated. Add a session log entry.")
fi

# ── 3. cia-log.md last touched today? ─────────────────────────────────────
LAST_LOG_DATE=$(git -C "$REPO" log --format="%ci" -1 -- cia-log.md 2>/dev/null | cut -c1-10)
if [ -n "$LAST_LOG_DATE" ] && [ "$LAST_LOG_DATE" != "$TODAY" ]; then
    ISSUES+=("cia-log.md last updated $LAST_LOG_DATE — today is $TODAY. Add a session log entry.")
fi

# ── Report ─────────────────────────────────────────────────────────────────
if [ ${#ISSUES[@]} -eq 0 ]; then
    echo "✓ Session gate: all checks passed."
    exit 0
fi

echo "SESSION GATE — the following items need to be completed before closing:"
for issue in "${ISSUES[@]}"; do
    echo "  • $issue"
done
echo ""
echo "Complete these items now, then the session can end."
exit 2
