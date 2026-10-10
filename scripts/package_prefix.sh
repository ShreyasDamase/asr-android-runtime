#!/usr/bin/env bash
# ==============================================================================
# package_prefix.sh - Package ROS 2 Android build prefix for release
# ==============================================================================
# Usage:
#   ./scripts/package_prefix.sh [PREFIX_DIR] [VERSION_TAG] [OUTPUT_DIR]
#
# Examples:
#   ./scripts/package_prefix.sh
#   ./scripts/package_prefix.sh /Users/shreyasdamase/ros2_android_install v1.0.0 ./release_artifacts
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# 1. Determine prefix path
PREFIX_DIR="${1:-}"

if [[ -z "${PREFIX_DIR}" ]]; then
    # Try reading from local.properties
    LOCAL_PROPS="${REPO_ROOT}/local.properties"
    if [[ -f "${LOCAL_PROPS}" ]]; then
        PROP_VAL="$(grep -E '^ROS2_ANDROID_PREFIX=' "${LOCAL_PROPS}" | cut -d'=' -f2- | tr -d ' \r\n' || true)"
        if [[ -n "${PROP_VAL}" ]]; then
            PREFIX_DIR="${PROP_VAL}"
        fi
    fi
fi

if [[ -z "${PREFIX_DIR}" ]]; then
    # Try env var
    PREFIX_DIR="${ROS2_ANDROID_PREFIX:-${HOME}/ros2_android_install}"
fi

# Expand tilde if present
PREFIX_DIR="${PREFIX_DIR/#\~/$HOME}"

echo "==> Validating ROS 2 Android prefix at: ${PREFIX_DIR}"

if [[ ! -d "${PREFIX_DIR}" ]]; then
    echo "ERROR: Directory does not exist: ${PREFIX_DIR}" >&2
    exit 1
fi

if [[ ! -d "${PREFIX_DIR}/include" || ! -d "${PREFIX_DIR}/lib" ]]; then
    echo "ERROR: ${PREFIX_DIR} is not a valid ROS 2 install prefix (missing include/ or lib/ directory)" >&2
    exit 1
fi

# 2. Determine version tag
VERSION_TAG="${2:-}"
if [[ -z "${VERSION_TAG}" ]]; then
    # Default to 1.0.0
    VERSION_TAG="v1.0.0"
fi

# Ensure 'v' prefix
if [[ "${VERSION_TAG}" != v* ]]; then
    VERSION_TAG="v${VERSION_TAG}"
fi

# 3. Output directory
OUTPUT_DIR="${3:-${REPO_ROOT}/build/release_artifacts}"
mkdir -p "${OUTPUT_DIR}"

ARCHIVE_NAME="ros2_android_install-${VERSION_TAG}.zip"
DEST_ARCHIVE="${OUTPUT_DIR}/${ARCHIVE_NAME}"

echo "==> Target archive: ${DEST_ARCHIVE}"
echo "==> Source directory: ${PREFIX_DIR}"
echo "==> Creating zip archive (excluding .DS_Store and build caches)..."

# Temporarily switch to parent of prefix dir to preserve directory name in zip
PARENT_DIR="$(dirname "${PREFIX_DIR}")"
BASE_DIR="$(basename "${PREFIX_DIR}")"

(
    cd "${PARENT_DIR}"
    zip -r -q "${DEST_ARCHIVE}" "${BASE_DIR}" \
        -x "*.DS_Store" "*__pycache__*" "*.log"
)

echo "==> Successfully created ${DEST_ARCHIVE}"

# Calculate checksum
if command -v shasum >/dev/null 2>&1; then
    SHA=$(shasum -a 256 "${DEST_ARCHIVE}" | awk '{print $1}')
    echo "==> SHA-256 Checksum: ${SHA}"
    echo "${SHA}  ${ARCHIVE_NAME}" > "${DEST_ARCHIVE}.sha256"
elif command -v sha256sum >/dev/null 2>&1; then
    SHA=$(sha256sum "${DEST_ARCHIVE}" | awk '{print $1}')
    echo "==> SHA-256 Checksum: ${SHA}"
    echo "${SHA}  ${ARCHIVE_NAME}" > "${DEST_ARCHIVE}.sha256"
fi

ls -lh "${DEST_ARCHIVE}"
echo ""
echo "=== Done! ==="
echo "Attach ${DEST_ARCHIVE} to the GitHub Release for tag ${VERSION_TAG}."
