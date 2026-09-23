#!/bin/bash
# ==============================================================================
# Dialex Engine Automated Backup Script
# ==============================================================================
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
CONFIG_DIR="${DIALEX_CONFIG_DIR:-$HOME/.config/dialex}"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
ARCHIVE_NAME="dialex_backup_${TIMESTAMP}.tar.gz"

mkdir -p "$BACKUP_DIR"

if [ ! -d "$CONFIG_DIR" ]; then
    echo "Error: Config directory $CONFIG_DIR does not exist." >&2
    exit 1
fi

echo "Backing up Dialex data from $CONFIG_DIR..."
tar -czf "${BACKUP_DIR}/${ARCHIVE_NAME}" -C "$(dirname "$CONFIG_DIR")" "$(basename "$CONFIG_DIR")"

echo "Backup created successfully at: ${BACKUP_DIR}/${ARCHIVE_NAME}"
ls -lh "${BACKUP_DIR}/${ARCHIVE_NAME}"
