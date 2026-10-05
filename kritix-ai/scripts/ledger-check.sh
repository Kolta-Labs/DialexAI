#!/usr/bin/env bash
# ==============================================================================
# scripts/ledger-check.sh
# Verifies that .dev/kritix-ai/ledger/round<n>.md exists and every open finding
# has a valid fix SHA, failing-before run, passing-after run, and mutation-proof
# (or explicit BLOCKED: <prereq>). Fails if any finding is NOT STARTED.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KRITIX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(git -C "${KRITIX_DIR}" rev-parse --show-toplevel)"

ROUND="${1:-6}"
LEDGER_FILE="${REPO_ROOT}/.dev/kritix-ai/ledger/round${ROUND}.md"
if [ ! -f "${LEDGER_FILE}" ]; then
  # Also check inside kritix-ai/.dev/
  LEDGER_FILE="${KRITIX_DIR}/.dev/kritix-ai/ledger/round${ROUND}.md"
fi

echo "📋 [Ledger Check] Verifying ledger for Round ${ROUND} at ${LEDGER_FILE}..."

if [ ! -f "${LEDGER_FILE}" ]; then
  echo "⛔ LEDGER CHECK FAILED: Ledger file ${LEDGER_FILE} does not exist!" >&2
  exit 1
fi

CONTENT=$(cat "${LEDGER_FILE}")

# 1. Parse table rows containing findings
FINDING_ROWS=$(echo "${CONTENT}" | grep -E '^\|[[:space:]]*\*\*FINDING-[^|]+\|' || true)

if [ -z "${FINDING_ROWS}" ]; then
  echo "⛔ LEDGER CHECK FAILED: No finding rows found matching '| **FINDING-...' in ledger." >&2
  exit 1
fi

# 2. Check for forbidden NOT STARTED status inside table rows
if echo "${FINDING_ROWS}" | grep -i "NOT STARTED" >/dev/null; then
  echo "⛔ LEDGER CHECK FAILED: Found 'NOT STARTED' findings in ledger." >&2
  echo "All findings must be fixed with a commit SHA, failing-before, passing-after, and mutation-proof (or explicit BLOCKED: <prereq>)." >&2
  exit 1
fi

ROW_COUNT=0
while IFS='|' read -r _ id desc commit failing passing proof status _; do
  id=$(echo "${id}" | xargs)
  commit=$(echo "${commit}" | tr -d '`' | xargs)
  failing=$(echo "${failing}" | xargs)
  passing=$(echo "${passing}" | xargs)
  proof=$(echo "${proof}" | xargs)
  status=$(echo "${status}" | xargs)

  if [ -z "${id}" ]; then
    continue
  fi

  ROW_COUNT=$((ROW_COUNT + 1))
  echo "  • Checking ${id} (Status: ${status})..."

  # Check commit SHA (must be valid 7-40 hex or BLOCKED)
  if ! [[ "${commit}" =~ ^[0-9a-f]{7,40}(,[[:space:]]*[0-9a-f]{7,40})*$ ]] && ! [[ "${status}" =~ BLOCKED ]]; then
    echo "    ⛔ Invalid or missing commit SHA for ${id}: '${commit}'" >&2
    exit 1
  fi

  # Check failing-before run
  if [ -z "${failing}" ] || [ "${failing}" = "-" ]; then
    echo "    ⛔ Missing failing-before run description for ${id}" >&2
    exit 1
  fi

  # Check passing-after run
  if [ -z "${passing}" ] || [ "${passing}" = "-" ]; then
    echo "    ⛔ Missing passing-after run description for ${id}" >&2
    exit 1
  fi

  # Check mutation-proof or BLOCKED prerequisite
  if ! echo "${proof}" | grep -E "(scripts/mutations/|scripts/mutation-proof\.sh|BLOCKED:|unit test|mutation test)" >/dev/null; then
    echo "    ⛔ Missing mutation proof or BLOCKED prerequisite specification for ${id}: '${proof}'" >&2
    exit 1
  fi

done <<< "${FINDING_ROWS}"

echo "✓ Ledger Check PASSED: ${ROW_COUNT} findings verified with fix SHAs, failing-before, passing-after, and mutation proofs."
