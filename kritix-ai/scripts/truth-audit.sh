#!/usr/bin/env bash
# ==============================================================================
# Kritix AI Enterprise Truth & Integrity Audit Gate v3 (Structural, AST-based)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ -d "$REPO_ROOT/kritix-ai" ]; then
  KRITIX_DIR="$REPO_ROOT/kritix-ai"
else
  KRITIX_DIR="$REPO_ROOT"
fi

echo "🔍 [Truth Audit v3] Running structural mechanical verification..."
(cd "$KRITIX_DIR" && go vet -tags integration ./...)
(cd "$KRITIX_DIR" && go run ./cmd/truth-audit)
