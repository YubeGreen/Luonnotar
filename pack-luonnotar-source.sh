#!/usr/bin/env bash
set -euo pipefail

# Luonnotar source packer
# Usage:
#   ./pack-luonnotar-source.sh
#   ./pack-luonnotar-source.sh /path/to/Luonnotar /path/to/output

PROJECT_DIR="${1:-/Users/nazuna/Developer/Luonnotar}"
OUT_DIR="${2:-$HOME/Downloads}"

if [[ ! -d "$PROJECT_DIR" ]]; then
  echo "ERROR: project directory not found: $PROJECT_DIR" >&2
  exit 1
fi

PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

extract_gradle_string() {
  local key="$1"
  local f value
  for f in "$PROJECT_DIR/app/build.gradle.kts" "$PROJECT_DIR/app/build.gradle"; do
    [[ -f "$f" ]] || continue
    value="$(
      sed -nE \
        -e "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*\"([^\"]+)\".*/\1/p" \
        -e "s/^[[:space:]]*${key}[[:space:]]+\"([^\"]+)\".*/\1/p" \
        "$f" | head -n 1
    )"
    if [[ -n "$value" ]]; then
      printf '%s' "$value"
      return 0
    fi
  done
  return 1
}

extract_gradle_int() {
  local key="$1"
  local f value
  for f in "$PROJECT_DIR/app/build.gradle.kts" "$PROJECT_DIR/app/build.gradle"; do
    [[ -f "$f" ]] || continue
    value="$(
      sed -nE \
        -e "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p" \
        -e "s/^[[:space:]]*${key}[[:space:]]+([0-9]+).*/\1/p" \
        "$f" | head -n 1
    )"
    if [[ -n "$value" ]]; then
      printf '%s' "$value"
      return 0
    fi
  done
  return 1
}

VERSION_NAME="$(extract_gradle_string versionName || true)"
VERSION_CODE="$(extract_gradle_int versionCode || true)"

[[ -n "$VERSION_NAME" ]] || VERSION_NAME="unknown"
[[ -n "$VERSION_CODE" ]] || VERSION_CODE="unknown"

safe_component() {
  printf '%s' "$1" | tr '/:[:space:]' '---' | tr -cd '[:alnum:]._-'
}

VERSION_NAME_SAFE="$(safe_component "$VERSION_NAME")"
VERSION_CODE_SAFE="$(safe_component "$VERSION_CODE")"
TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"

ARCHIVE_NAME="Luonnotar-${VERSION_NAME_SAFE}-vCode${VERSION_CODE_SAFE}-source-${TIMESTAMP}.tar.gz"
ARCHIVE_PATH="$OUT_DIR/$ARCHIVE_NAME"

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/luonnotar-pack.XXXXXX")"
trap 'rm -rf "$TMP_ROOT"' EXIT
STAGE="$TMP_ROOT/Luonnotar"
mkdir -p "$STAGE"

echo "Project : $PROJECT_DIR"
echo "Version : $VERSION_NAME (vCode $VERSION_CODE)"
echo "Output  : $ARCHIVE_PATH"
echo
echo "Collecting source..."

# Copy the repository, but leave out generated files, local IDE state,
# large build artifacts, VCS history, and signing/local-machine secrets.
rsync -a \
  --exclude='.git/' \
  --exclude='.gradle/' \
  --exclude='.idea/' \
  --exclude='.kotlin/' \
  --exclude='.cxx/' \
  --exclude='.externalNativeBuild/' \
  --exclude='**/build/' \
  --exclude='build/' \
  --exclude='captures/' \
  --exclude='node_modules/' \
  --exclude='.DS_Store' \
  --exclude='local.properties' \
  --exclude='keystore.properties' \
  --exclude='signing.properties' \
  --exclude='*.jks' \
  --exclude='*.keystore' \
  --exclude='*.apk' \
  --exclude='*.aab' \
  --exclude='*.apks' \
  --exclude='*.log' \
  --exclude='*.tmp' \
  --exclude='*.swp' \
  "$PROJECT_DIR/" "$STAGE/"

GIT_BRANCH="unknown"
GIT_COMMIT="unknown"
GIT_DIRTY="unknown"
if command -v git >/dev/null 2>&1 && git -C "$PROJECT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  GIT_BRANCH="$(git -C "$PROJECT_DIR" branch --show-current 2>/dev/null || true)"
  GIT_COMMIT="$(git -C "$PROJECT_DIR" rev-parse HEAD 2>/dev/null || true)"
  if [[ -n "$(git -C "$PROJECT_DIR" status --porcelain 2>/dev/null || true)" ]]; then
    GIT_DIRTY="yes"
  else
    GIT_DIRTY="no"
  fi
fi

cat > "$STAGE/PACKAGE_INFO.txt" <<EOF
Project: Luonnotar
Source path: $PROJECT_DIR
Packed at: $(date '+%Y-%m-%d %H:%M:%S %z')
versionName: $VERSION_NAME
versionCode: $VERSION_CODE
Git branch: ${GIT_BRANCH:-unknown}
Git commit: ${GIT_COMMIT:-unknown}
Working tree dirty: $GIT_DIRTY

Excluded intentionally:
- Git history and IDE/cache/build directories
- APK/AAB/build outputs
- local.properties
- keystore/signing property files
- *.jks and *.keystore
- logs and temporary files
EOF

echo "Creating archive..."
(
  cd "$TMP_ROOT"
  tar -czf "$ARCHIVE_PATH" Luonnotar
)

echo
echo "Done."
echo "$ARCHIVE_PATH"

if command -v shasum >/dev/null 2>&1; then
  echo
  shasum -a 256 "$ARCHIVE_PATH"
fi
