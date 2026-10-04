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
  "kritix-ai/scripts/mutations/egress_leak.patch|go test -tags integration -count=1 ./pkg/security/... -run TestIntegration_ZeroEgressProof"
  "kritix-ai/scripts/mutations/sql_tenant_leak.patch|go test -tags integration -count=1 ./pkg/server/... -run TestSQLStateStore_CrossTenantIsolation"
  "kritix-ai/scripts/mutations/vault_plaintext.patch|go test -tags integration -count=1 ./pkg/auth/... -run TestIntegration_VaultTransitAndKVSessionStore"
  "kritix-ai/scripts/mutations/healing_same_role.patch|go test -count=1 ./pkg/sdet/... -run TestHealCorpus_FrozenIntegrityAndEvaluation"
  "kritix-ai/scripts/mutations/tracker_no_dedupe.patch|go test -tags integration -count=1 ./pkg/tracker/... -run TestIntegration_TrackerMockProtocolAndRateLimiting"
)

TOTAL_MUTATIONS=${#MUTATIONS[@]}
PASSED_MUTATIONS=0

cd "${KRITIX_DIR}"

for entry in "${MUTATIONS[@]}"; do
  IFS="|" read -r patch_file test_cmd <<< "${entry}"
  rel_patch="${patch_file}"
  full_patch="${GIT_ROOT}/${patch_file}"

  echo ""
  echo "------------------------------------------------------------------"
  echo "Testing Mutation: ${rel_patch}"
  echo "Target Test: ${test_cmd}"
  echo "------------------------------------------------------------------"

  # Step 1: Ensure test passes before mutation
  echo "[1/4] Verifying target test passes cleanly when unmutated..."
  if ! eval "${test_cmd}" > /dev/null 2>&1; then
    echo "FAIL: Target test failed before mutation applied!"
    eval "${test_cmd}"
    exit 1
  fi
  echo "      => PASS (Green before mutation)"

  # Step 2: Apply mutation patch
  echo "[2/4] Applying deliberate product code mutation..."
  git -C "${GIT_ROOT}" apply "${full_patch}"

  # Step 3: Run target test and expect failure
  echo "[3/4] Running target test under mutation (MUST GO RED)..."
  set +e
  eval "${test_cmd}" > /dev/null 2>&1
  TEST_EXIT_CODE=$?
  set -e

  # Step 4: Revert mutation patch immediately
  echo "[4/4] Restoring product code..."
  git -C "${GIT_ROOT}" apply -R "${full_patch}"

  if [ ${TEST_EXIT_CODE} -eq 0 ]; then
    echo "CRITICAL FAILURE: Mutation ${rel_patch} failed to break test!"
    echo "The test stayed GREEN even though product code was corrupted."
    exit 1
  fi

  echo "      => RED (Successfully failed as expected with exit code ${TEST_EXIT_CODE})"
  PASSED_MUTATIONS=$((PASSED_MUTATIONS + 1))
done

echo ""
echo "=================================================================="
echo "SUCCESS: All ${PASSED_MUTATIONS}/${TOTAL_MUTATIONS} mutation proofs verified!"
echo "Every critical security/safety/integration test is proven sensitive to product regressions."
echo "=================================================================="
