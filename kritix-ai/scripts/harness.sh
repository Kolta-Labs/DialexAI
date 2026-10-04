#!/usr/bin/env bash
# ==============================================================================
# Kritix AI Evidence Harness Runner v4 (Integrity & Honest Prerequisite Enforcement)
# Refuses to run with missing prerequisites; exits PREREQUISITE_MISSING: <tool>
# and writes NO results when required infrastructure is absent.
# ==============================================================================

set -euo pipefail

ROUND="${1:-round1}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KRITIX_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🔬 [Evidence Harness] Checking prerequisites for $ROUND..."

MISSING=()
if ! command -v docker &>/dev/null; then
  MISSING+=("docker")
fi

HAS_CHROME="false"
if command -v google-chrome &>/dev/null || command -v chromium &>/dev/null || [ -f "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]; then
  HAS_CHROME="true"
fi
if [ "$HAS_CHROME" = "false" ]; then
  MISSING+=("chrome")
fi

if ! command -v k6 &>/dev/null; then
  MISSING+=("k6")
fi

if ! command -v vault &>/dev/null; then
  MISSING+=("vault")
fi

if ! (command -v psql &>/dev/null || command -v postgres &>/dev/null); then
  MISSING+=("postgres")
fi

if [ ${#MISSING[@]} -gt 0 ]; then
  echo "⛔ PREREQUISITE CHECK FAILED: Required external tools are missing." >&2
  for tool in "${MISSING[@]}"; do
    echo "PREREQUISITE_MISSING: $tool" >&2
  done
  echo "Integrity rule: Harness never substitutes cheaper measurements or writes partial results." >&2
  exit 1
fi

EVIDENCE_DIR="$REPO_ROOT/.dev/kritix-ai/evidence/$ROUND"
mkdir -p "$EVIDENCE_DIR"

echo "✓ All prerequisites verified. Executing full enterprise test and benchmark harness..."

# 1. Probe host capabilities and record availability profile
CAPABILITIES_JSON="$EVIDENCE_DIR/capabilities.json"
cat > "$CAPABILITIES_JSON" <<EOF
{
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "round": "$ROUND",
  "host": {
    "os": "$(uname -s)",
    "arch": "$(uname -m)",
    "go_version": "$(go version)"
  },
  "capabilities": {
    "docker": true,
    "chrome_cdp": true,
    "postgres": true,
    "vault": true,
    "k6": true
  }
}
EOF

# Set harness mode: skips are strictly prohibited in harness mode
export KRITIX_HARNESS=1

# 2. Run structural truth audit and integration vet
echo "🔍 [1/3] Executing structural truth audit and integration vet..."
(cd "$KRITIX_DIR" && go vet -tags integration ./...)
(cd "$KRITIX_DIR" && go run ./cmd/truth-audit)

# 3. Run full unit, race, and integration suite with zero-skip enforcement
echo "🧪 [2/3] Executing full test suite with race detector and -tags integration..."
TEST_JSON_OUT="$EVIDENCE_DIR/test_results.jsonl"
set +e
(cd "$KRITIX_DIR" && go test -tags integration -json ./... -race -count=1) > "$TEST_JSON_OUT"
TEST_EXIT=$?
set -e

if [ $TEST_EXIT -ne 0 ]; then
  echo "⛔ TEST SUITE FAILED with exit code $TEST_EXIT" >&2
  exit $TEST_EXIT
fi

# Zero-skip enforcement in harness mode
SKIPPED_TESTS=$(grep '"Action":"skip"' "$TEST_JSON_OUT" | grep '"Test":' || true)
if [ -n "$SKIPPED_TESTS" ]; then
  SKIP_COUNT=$(echo "$SKIPPED_TESTS" | wc -l | tr -d ' ')
  echo "⛔ ZERO-SKIP CHECK FAILED: Found $SKIP_COUNT skipped tests in harness mode (KRITIX_HARNESS=1 prohibits skips):" >&2
  echo "$SKIPPED_TESTS" >&2
  exit 1
fi
echo "✓ Zero-skip check passed: all tests executed without skipping."

# 4. Run real benchmark
echo "📊 [3/3] Generating benchmark evidence from real executions..."
SUMMARY_JSON="$EVIDENCE_DIR/benchmark_summary.json"
SUMMARY_MD="$EVIDENCE_DIR/benchmark_report.md"

export KRITIX_AUTH_SECRET="${KRITIX_AUTH_SECRET:-01234567890123456789012345678901}"
export KRITIX_TOKEN="$(cd "$KRITIX_DIR" && go run ./cmd/kritix auth token admin 2>/dev/null | grep -E '^[a-zA-Z0-9\._\-]+$' || true)"

(cd "$KRITIX_DIR" && go run ./cmd/kritix benchmark --corpus testdata/regressions --runs 2 --output "$SUMMARY_JSON" | tee "$SUMMARY_MD")

cp "$SUMMARY_JSON" "$KRITIX_DIR/benchmark.json"

echo "✅ [Evidence Harness Complete] Evidence recorded in $EVIDENCE_DIR"

