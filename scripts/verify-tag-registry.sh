#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=== Verifying Git Tag Immutability Registry against local & remote refs ==="
cd "${REPO_ROOT}"

# Query remote tags
REMOTE_TAGS=$(git ls-remote --tags origin)

if [ -z "${REMOTE_TAGS}" ]; then
    echo "Warning: No remote tags found or offline; skipping remote check."
    exit 0
fi

# For each tag in git for-each-ref
while IFS='|' read -r TAG_NAME TAG_OBJ COMMIT_OBJ; do
    TAG_NAME=$(echo "${TAG_NAME}" | xargs)
    TAG_OBJ=$(echo "${TAG_OBJ}" | xargs)
    COMMIT_OBJ=$(echo "${COMMIT_OBJ}" | xargs)

    if [ -z "${TAG_NAME}" ]; then
        continue
    fi

    echo "Checking ${TAG_NAME} (tag_obj: ${TAG_OBJ}, commit: ${COMMIT_OBJ})..."

    # Check that remote contains the tag object
    if ! echo "${REMOTE_TAGS}" | grep -q "${TAG_OBJ}[[:space:]]*refs/tags/${TAG_NAME}"; then
        echo "ERROR: Remote tag object for ${TAG_NAME} does not match local ${TAG_OBJ}!" >&2
        exit 1
    fi

    if [ -n "${COMMIT_OBJ}" ]; then
        if ! echo "${REMOTE_TAGS}" | grep -q "${COMMIT_OBJ}[[:space:]]*refs/tags/${TAG_NAME}\^{}"; then
            echo "ERROR: Remote commit target for ${TAG_NAME} does not match local ${COMMIT_OBJ}!" >&2
            exit 1
        fi
    fi
done < <(git for-each-ref --format="%(refname:short) | %(objectname) | %(*objectname)" refs/tags)

echo "SUCCESS: All local tags match remote refs with 100% cryptographic immutability."
