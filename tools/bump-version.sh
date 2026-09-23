#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Dialex AI — Automated Version Bump & Changelog Helper
# Usage:
#   ./tools/bump-version.sh 1.1.0
#   ./tools/bump-version.sh 1.1.0 --dry-run
#   ./tools/bump-version.sh 1.1.0 --no-git
# ==============================================================================

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_PROPERTIES="${PROJECT_ROOT}/gradle.properties"
CHANGELOG_FILE="${PROJECT_ROOT}/CHANGELOG.md"

if [ $# -lt 1 ]; then
    echo "❌ Error: Version argument required."
    echo "Usage: $0 <new-version> [--dry-run] [--no-git]"
    echo "Example: $0 1.1.0"
    exit 1
fi

NEW_VERSION="$1"
DRY_RUN=false
NO_GIT=false

shift
while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --no-git)
            NO_GIT=true
            shift
            ;;
        *)
            echo "Unknown flag: $1"
            exit 1
            ;;
    esac
done

# Strip leading 'v' if present
NEW_VERSION="${NEW_VERSION#v}"

# Validate SemVer pattern (e.g. 1.2.3 or 1.2.3-rc1)
if [[ ! "${NEW_VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
    echo "❌ Error: Version '${NEW_VERSION}' does not match Semantic Versioning (X.Y.Z)."
    exit 1
fi

# Read current versionCode
CURRENT_VERSION_CODE=$(grep -E "^app.versionCode=" "${GRADLE_PROPERTIES}" | cut -d'=' -f2 || echo "1")
NEW_VERSION_CODE=$((CURRENT_VERSION_CODE + 1))
TODAY=$(date +"%Y-%m-%d")

echo "=================================================="
echo "🚀 Bumping Dialex AI Version"
echo "  • New Version:      ${NEW_VERSION}"
echo "  • New Version Code: ${NEW_VERSION_CODE}"
echo "  • Release Date:     ${TODAY}"
echo "=================================================="

if [ "${DRY_RUN}" = true ]; then
    echo "🔍 [DRY RUN] Would update ${GRADLE_PROPERTIES} and ${CHANGELOG_FILE} without making changes."
    exit 0
fi

# 1. Update gradle.properties
sed -i '' -e "s/^app.version=.*/app.version=${NEW_VERSION}/" "${GRADLE_PROPERTIES}"
sed -i '' -e "s/^app.versionCode=.*/app.versionCode=${NEW_VERSION_CODE}/" "${GRADLE_PROPERTIES}"
echo "✅ Updated ${GRADLE_PROPERTIES}"

# 2. Update CHANGELOG.md if Unreleased section exists
if grep -q "## \[Unreleased\]" "${CHANGELOG_FILE}"; then
    TEMP_CHANGELOG=$(mktemp)
    awk -v ver="${NEW_VERSION}" -v date="${TODAY}" '
    /## \[Unreleased\]/ {
        print "## [Unreleased]\n"
        print "### Added\n"
        print "---\n"
        print "## [" ver "] - " date
        next
    }
    { print }
    ' "${CHANGELOG_FILE}" > "${TEMP_CHANGELOG}"
    mv "${TEMP_CHANGELOG}" "${CHANGELOG_FILE}"
    echo "✅ Updated ${CHANGELOG_FILE}"
fi

# 3. Optional Git Commit & Tag
if [ "${NO_GIT}" = false ]; then
    cd "${PROJECT_ROOT}"
    git add "${GRADLE_PROPERTIES}" "${CHANGELOG_FILE}"
    git commit -m "chore(release): bump version to v${NEW_VERSION}"
    git tag -a "v${NEW_VERSION}" -m "Release v${NEW_VERSION}"
    echo "✅ Created git commit and tag: v${NEW_VERSION}"
    echo ""
    echo "💡 To trigger the GitHub Actions release workflow, run:"
    echo "   git push origin main && git push origin v${NEW_VERSION}"
fi

echo "🎉 Version bump completed successfully."
