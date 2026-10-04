#!/usr/bin/env bash
# ==============================================================================
# Kritix AI Enterprise Pre-Commit Credential Guard
# Enforces SOC 2 CC6.1 Logical Access Controls
# Blocks accidental commit of Playwright storageState, JWTs, and API secrets.
# ==============================================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "🛡️ [Kritix AI] Running Pre-Commit Credential & StorageState Guard..."

BLOCKED_PATTERNS=(
  "storageState.*\.json"
  "auth_session.*\.json"
  ".*\.enc$"
  "id_rsa"
  "jwt_token"
  "bearer_token"
)

STAGED_FILES=$(git diff --cached --name-only)
VIOLATIONS=0

for FILE in $STAGED_FILES; do
  # 1. Check filename patterns
  for PATTERN in "${BLOCKED_PATTERNS[@]}"; do
    if [[ "$FILE" =~ $PATTERN ]]; then
      echo -e "${RED}❌ BLOCKED COMMIT:${NC} Sensitive session state file staged: $FILE"
      echo -e "   ${YELLOW}Rule:${NC} StorageState and credential dumps must never be committed to Git."
      echo -e "   ${YELLOW}Action:${NC} Run 'git reset HEAD $FILE' and add it to .gitignore."
      VIOLATIONS=$((VIOLATIONS + 1))
    fi
  done

  # 2. Check staged file content for live Playwright storageState signatures
  if [ -f "$FILE" ]; then
    if git diff --cached "$FILE" | grep -E -q '"cookies":|localStorage.*"token"|storageState'; then
      # Ignore our own source code files that reference the string
      if [[ ! "$FILE" =~ \.go$ && ! "$FILE" =~ \.md$ && ! "$FILE" =~ \.sh$ ]]; then
        echo -e "${RED}❌ BLOCKED COMMIT:${NC} File content contains Playwright storageState / session tokens: $FILE"
        VIOLATIONS=$((VIOLATIONS + 1))
      fi
    fi
  fi
done

if [ $VIOLATIONS -gt 0 ]; then
  echo -e "\n${RED}⛔ Commit aborted by Kritix AI Pre-Commit Credential Guard ($VIOLATIONS violations found).${NC}"
  echo "Use encrypted storage (pkg/auth) with in-memory EphemeralSessionStore instead."
  exit 1
fi

echo -e "${GREEN}✓ Pre-commit credential guard passed. Zero credential leaks detected.${NC}"
exit 0
