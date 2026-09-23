#!/bin/bash
# ==============================================================================
# Dialex Engine Restore Script
# ==============================================================================
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <path-to-backup.tar.gz> [target-config-dir]"
    exit 1
fi

BACKUP_FILE="$1"
TARGET_DIR="${2:-${DIALEX_CONFIG_DIR:-$HOME/.config/dialex}}"

if [ ! -f "$BACKUP_FILE" ]; then
    echo "Error: Backup file $BACKUP_FILE not found." >&2
    exit 1
fi

echo "Restoring Dialex data to $TARGET_DIR..."
mkdir -p "$TARGET_DIR"

# Extract archive
tar -xzf "$BACKUP_FILE" -C "$(dirname "$TARGET_DIR")"

echo "Restore completed successfully!"
ls -la "$TARGET_DIR"
