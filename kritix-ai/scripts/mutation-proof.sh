#!/usr/bin/env bash
set -euo pipefail

# scripts/mutation-proof.sh
# Verifies that every security/safety/integration test is backed by a registered mutation
# that provably turns the test RED when the product code is intentionally broken.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KRITIX_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
GIT_ROOT="$(git -C "${KRITIX_DIR}" rev-parse --show-toplevel)"

echo "=== Running Mutation Proofs for Kritix AI ==="
echo "Git root: ${GIT_ROOT}"
echo "Working directory: ${KRITIX_DIR}"

MUTATIONS=(
  "kritix-ai/scripts/mutations/egress_leak.patch|go test -tags integration -count=1 -v ./pkg/security/... -run TestIntegration_ZeroEgressProof"
  "kritix-ai/scripts/mutations/sql_tenant_leak.patch|go test -tags integration -count=1 ./pkg/server/... -run TestSQLStateStore_CrossTenantIsolation"
  "kritix-ai/scripts/mutations/vault_plaintext.patch|go test -tags integration -count=1 ./pkg/auth/... -run TestIntegration_VaultTransitAndKVSessionStore"
  "kritix-ai/scripts/mutations/healing_same_role.patch|go test -count=1 ./pkg/sdet/... -run TestHealDeveloperUnitCases_SemanticSwaps"
  "kritix-ai/scripts/mutations/tracker_no_dedupe.patch|go test -tags integration -count=1 ./pkg/tracker/... -run TestIntegration_TrackerMockProtocolAndRateLimiting"
  "kritix-ai/scripts/mutations/k6_drop_threshold.patch|go test -count=1 ./pkg/perf/... -run TestParseK6SummaryJSON"
  "kritix-ai/scripts/mutations/playwright_broken_locator.patch|go test -tags integration -count=1 ./pkg/triage/... -run TestIntegration_PlaywrightExportAndVanillaExecution"
  "kritix-ai/scripts/mutations/cdp_pierce_unsupported.patch|go test -count=1 ./pkg/driver/... -run TestDetectUnsupportedSurfaces_Classification"
  "kritix-ai/scripts/mutations/vault_empty_token.patch|go test -count=1 ./pkg/auth/... -run TestVaultFailClosedWithoutToken"
  "kritix-ai/scripts/mutations/oidc_skip_issuer.patch|go test -count=1 ./pkg/auth/... -run TestOIDCClient_ParseAndValidateIDToken_Mismatches"
  "kritix-ai/scripts/mutations/rbac_bypass_permission.patch|go test -count=1 ./pkg/auth/... -run TestRolePermissionMatrix"
  "kritix-ai/scripts/mutations/audit_break_hash_link.patch|go test -count=1 ./pkg/auth/... -run TestAuditChainDurableAndTamperEvident"
  "kritix-ai/scripts/mutations/quarantine_drop_cap.patch|go test -count=1 ./pkg/quarantine/... -run TestTeamQuarantineCapEnforcement"
  "kritix-ai/scripts/mutations/kill_switch_ignore.patch|go test -count=1 ./pkg/server/... -run TestRateLimiterAndKillSwitch"
)

ROUND="${1:-7}"
EVIDENCE_DIR="${GIT_ROOT}/.dev/kritix-ai/evidence/round${ROUND}/mutations"
mkdir -p "${EVIDENCE_DIR}"

TOTAL_MUTATIONS=${#MUTATIONS[@]}
PASSED_MUTATIONS=0
BLOCKED_MUTATIONS=0

cd "${KRITIX_DIR}"

for entry in "${MUTATIONS[@]}"; do
  IFS="|" read -r patch_file test_cmd <<< "${entry}"
  rel_patch="${patch_file}"
  full_patch="${GIT_ROOT}/${patch_file}"
  patch_base=$(basename "${patch_file}" .patch)
  log_file="${EVIDENCE_DIR}/${patch_base}.log"

  echo ""
  echo "------------------------------------------------------------------"
  echo "Testing Mutation: ${rel_patch}"
  echo "Target Test: ${test_cmd}"
  echo "Log: ${log_file}"
  echo "------------------------------------------------------------------"

  {
    echo "=== Mutation Proof: ${rel_patch} ==="
    echo "Target Test: ${test_cmd}"
    echo "Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    echo ""
  } > "${log_file}"

  # Step 1: Check if prerequisite missing or passes cleanly when unmutated
  echo "[1/4] Verifying target test passes cleanly when unmutated..."
  set +e
  PRE_OUT=$(eval "${test_cmd}" 2>&1)
  PRE_EXIT=$?
  set -e
  echo "${PRE_OUT}" >> "${log_file}"

  if echo "${PRE_OUT}" | grep -q "PREREQUISITE_MISSING"; then
    prereq_msg=$(echo "${PRE_OUT}" | grep "PREREQUISITE_MISSING" | head -n 1 | sed 's/^[[:space:]]*//')
    echo "      => BLOCKED: ${prereq_msg}"
    echo "BLOCKED: ${prereq_msg}" >> "${log_file}"
    BLOCKED_MUTATIONS=$((BLOCKED_MUTATIONS + 1))
    continue
  fi

  if [ ${PRE_EXIT} -ne 0 ]; then
    echo "FAIL: Target test failed before mutation applied!"
    echo "FAIL: Target test failed before mutation applied!" >> "${log_file}"
    exit 1
  fi
  echo "      => PASS (Green before mutation)"
  echo "GREEN_BEFORE: exit code 0" >> "${log_file}"

  # Step 2: Apply mutation patch
  echo "[2/4] Applying deliberate product code mutation..."
  git -C "${GIT_ROOT}" apply "${full_patch}"

  # Step 3: Run target test and expect failure
  echo "[3/4] Running target test under mutation (MUST GO RED)..."
  set +e
  MUT_OUT=$(eval "${test_cmd}" 2>&1)
  TEST_EXIT_CODE=$?
  set -e
  echo "" >> "${log_file}"
  echo "=== Test Output Under Mutation ===" >> "${log_file}"
  echo "${MUT_OUT}" >> "${log_file}"

  # Step 4: Revert mutation patch immediately
  echo "[4/4] Restoring product code..."
  git -C "${GIT_ROOT}" apply -R "${full_patch}"

  if [ ${TEST_EXIT_CODE} -eq 0 ]; then
    echo "CRITICAL FAILURE: Mutation ${rel_patch} failed to break test!"
    echo "The test stayed GREEN even though product code was corrupted."
    echo "MUTATION_RESULT: FAILED_TO_BREAK (stayed green)" >> "${log_file}"
    exit 1
  fi

  echo "      => RED (Successfully failed as expected with exit code ${TEST_EXIT_CODE})"
  echo "MUTATION_RESULT: PROVEN_RED (exit code ${TEST_EXIT_CODE})" >> "${log_file}"
  PASSED_MUTATIONS=$((PASSED_MUTATIONS + 1))
done

echo ""
echo "=================================================================="
echo "Mutation Proofs Summary:"
echo "  • Passed (Proven RED): ${PASSED_MUTATIONS}/${TOTAL_MUTATIONS}"
echo "  • Blocked (Missing Host Tools): ${BLOCKED_MUTATIONS}/${TOTAL_MUTATIONS}"
echo "  • Logs saved under: ${EVIDENCE_DIR}"
echo "=================================================================="
if [ $((PASSED_MUTATIONS + BLOCKED_MUTATIONS)) -ne ${TOTAL_MUTATIONS} ]; then
  exit 1
fi
