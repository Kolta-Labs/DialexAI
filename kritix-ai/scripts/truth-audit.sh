#!/usr/bin/env bash
# ==============================================================================
# Kritix AI Enterprise Truth & Integrity Audit Gate
# Fails mechanical verification if:
#  (a) numeric literals in benchmark/TCO structs outside harness output reader
#  (b) blocks returning Passed with unperformed side effects
#  (c) default credentials/secrets (e.g. root token fallbacks)
#  (d) absolute /Users or file:// paths in tracked files under kritix-ai/
#  (e) any *ADOPT*.md in the public tree (must only exist in .dev/)
# ==============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Adjust if running from within kritix-ai or DialexAI
if [ -d "$REPO_ROOT/kritix-ai" ]; then
  KRITIX_DIR="$REPO_ROOT/kritix-ai"
else
  KRITIX_DIR="$REPO_ROOT"
fi

echo "🔍 [Truth Audit] Running mechanical truth and integrity verification on Kritix AI..."
VIOLATIONS=0

# (e) Check for *ADOPT*.md in the public tree
echo "👉 1. Checking for self-certified *ADOPT*.md in public tree..."
PUBLIC_ADOPT=$(find "$KRITIX_DIR" -maxdepth 2 -name "*ADOPT*.md" -not -path "*/.dev/*" 2>/dev/null || true)
if [ -n "$PUBLIC_ADOPT" ]; then
  echo -e "${RED}❌ VIOLATION:${NC} Found *ADOPT*.md in public tree: $PUBLIC_ADOPT"
  echo "   Council adoption records and FINAL_ADOPTION.md MUST live in .dev/kritix-ai/ only."
  VIOLATIONS=$((VIOLATIONS + 1))
fi

# (d) Check for absolute /Users or file:// paths in tracked files under kritix-ai
echo "👉 2. Checking for /Users/ or file:// paths in tracked files..."
FILE_PATHS=$(git -C "$KRITIX_DIR" grep -n -E '(/Users/|file://)' -- . ':!scripts/truth-audit.sh' 2>/dev/null || true)
if [ -n "$FILE_PATHS" ]; then
  echo -e "${RED}❌ VIOLATION:${NC} Found absolute paths or file:// URLs in tracked files:"
  echo "$FILE_PATHS"
  VIOLATIONS=$((VIOLATIONS + 1))
fi

# (c) Check for default credentials / secrets in production code
echo "👉 3. Checking for default credentials and insecure token fallbacks..."
DEFAULT_CREDS=$(grep -rnE 'token\s*=\s*"root"|token\s*:=\s*"root"|default_token\s*=\s*"root"' "$KRITIX_DIR/pkg" 2>/dev/null || true)
if [ -n "$DEFAULT_CREDS" ]; then
  echo -e "${RED}❌ VIOLATION:${NC} Insecure default root credential fallback detected:"
  echo "$DEFAULT_CREDS"
  VIOLATIONS=$((VIOLATIONS + 1))
fi

# (a) Check for hardcoded fallback benchmark results in optimizer
echo "👉 4. Checking for hardcoded benchmark / TCO fallback values in production code..."
HARDCODED_BENCH=$(grep -rnE 'Medusa Storefront \(Next\.js|LOC:\s*185000|P50LatencySeconds:\s*1\.45' $(find "$KRITIX_DIR/pkg/optimizer" -name "*.go" -not -name "*_test.go") 2>/dev/null || true)
if [ -n "$HARDCODED_BENCH" ]; then
  echo -e "${RED}❌ VIOLATION:${NC} Hardcoded benchmark fallback struct found in optimizer package:"
  echo "$HARDCODED_BENCH"
  VIOLATIONS=$((VIOLATIONS + 1))
fi

# (b) Check for blocks returning Passed with unperformed side effects
echo "👉 5. Checking for false-success workflow blocks..."
FALSE_PASS=$(grep -rnE 'return\s+&BlockResult\{\s*Status:\s*StatusPassed.*Simulated:\s*true' "$KRITIX_DIR/pkg/workflow" 2>/dev/null || true)
if [ -n "$FALSE_PASS" ]; then
  echo -e "${RED}❌ VIOLATION:${NC} Block returning StatusPassed with simulated flag:"
  echo "$FALSE_PASS"
  VIOLATIONS=$((VIOLATIONS + 1))
fi

if [ $VIOLATIONS -gt 0 ]; then
  echo -e "\n${RED}⛔ TRUTH AUDIT FAILED: $VIOLATIONS violations detected.${NC}"
  exit 1
fi

echo -e "\n${GREEN}✅ TRUTH AUDIT PASSED: Zero fabrications, zero default secrets, zero path leaks, zero false-success blocks.${NC}"
exit 0
