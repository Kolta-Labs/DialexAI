#!/usr/bin/env bash
# ==============================================================================
# Kritix AI Council Critic Verbatim Capture Script
# Saves critic output byte-for-byte into .dev/kritix-ai/COUNCIL_ROUND_<n>/<role>.md
# Computes SHA-256 and appends to capture_log.jsonl
# ==============================================================================

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: scripts/capture-critic.sh <round_num> <role> [input_file]"
  echo "Example: scripts/capture-critic.sh 1 cto /path/to/cto_raw.md"
  exit 1
fi

ROUND_NUM="$1"
ROLE="$2"
INPUT_FILE="${3:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

COUNCIL_DIR="$REPO_ROOT/.dev/kritix-ai/COUNCIL_ROUND_${ROUND_NUM}"
mkdir -p "$COUNCIL_DIR"

DEST_FILE="$COUNCIL_DIR/${ROLE}.md"

if [ -n "$INPUT_FILE" ] && [ -f "$INPUT_FILE" ]; then
  cp "$INPUT_FILE" "$DEST_FILE"
else
  # Read from stdin
  cat > "$DEST_FILE"
fi

# Compute SHA-256
if command -v shasum &>/dev/null; then
  FILE_SHA=$(shasum -a 256 "$DEST_FILE" | awk '{print $1}')
elif command -v sha256sum &>/dev/null; then
  FILE_SHA=$(sha256sum "$DEST_FILE" | awk '{print $1}')
else
  FILE_SHA=$(openssl dgst -sha256 "$DEST_FILE" | awk '{print $NF}')
fi

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
CAPTURE_LOG="$REPO_ROOT/.dev/kritix-ai/capture_log.jsonl"
mkdir -p "$(dirname "$CAPTURE_LOG")"

ENTRY="{\"timestamp\":\"$TIMESTAMP\",\"round\":\"round${ROUND_NUM}\",\"role\":\"$ROLE\",\"file\":\".dev/kritix-ai/COUNCIL_ROUND_${ROUND_NUM}/${ROLE}.md\",\"sha256\":\"$FILE_SHA\"}"
echo "$ENTRY" >> "$CAPTURE_LOG"

echo "✓ Captured critic report for $ROLE (round $ROUND_NUM) -> $DEST_FILE"
echo "  SHA-256: $FILE_SHA recorded in capture_log.jsonl"
