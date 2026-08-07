#!/usr/bin/env bash
# Static regression guard: every production freeze/unfreeze command surface must
# remain in the explicit owner allowlist below.
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$ROOT"

command -v rg >/dev/null 2>&1 || { echo "rg is required" >&2; exit 69; }

DIRECT_FILES=()
while IFS= read -r file; do
  [[ -n "$file" ]] && DIRECT_FILES+=("$file")
done < <(
  rg -l --hidden -g '!**/.git/**' -g '!**/build/**' -g '!**/.gradle/**' \
    'run_(captured|limited)[^[:cntrl:]]*(cmd activity|am) (unfreeze|freeze)|output=\$\(am (unfreeze|freeze)' \
    app/src/main tools/device 2>/dev/null | sort -u
)

ALLOWED=(
  "app/src/main/java/com/yubegreen/luonnotar/privileged/GmsFreezerFastLane.kt"
  "app/src/main/java/com/yubegreen/luonnotar/privileged/GmsVendorFreezeBridge.kt"
  "tools/device/luonnotar-guardian-v2.sh"
)

unexpected=0
for file in "${DIRECT_FILES[@]}"; do
  allowed=0
  for candidate in "${ALLOWED[@]}"; do
    [[ "$file" == "$candidate" ]] && allowed=1 && break
  done
  if [[ "$allowed" -ne 1 ]]; then
    echo "UNEXPECTED direct freezer command surface: $file" >&2
    unexpected=1
  fi
done
[[ "$unexpected" -eq 0 ]] || exit 1

# Broader production audit: builders and helper references may construct the
# command without embedding the literal shell text. Keep that surface explicit.
OWNER_SURFACE_FILES=()
while IFS= read -r file; do
  [[ -n "$file" ]] && OWNER_SURFACE_FILES+=("$file")
done < <(
  rg -l --hidden -g '!**/.git/**' -g '!**/build/**' -g '!**/.gradle/**' \
    -g '!**/test/**' -g '!**/androidTest/**' \
    'ActivityManagerUnfreezeCommand|cmd activity unfreeze|am unfreeze|scheduleGmsFastThaw|unfreezeProcessNameLocked' \
    app/src/main tools/device 2>/dev/null | sort -u
)

OWNER_SURFACE_ALLOWED=(
  "app/src/main/java/com/yubegreen/luonnotar/privileged/ActivityManagerUnfreezeCommand.kt"
  "app/src/main/java/com/yubegreen/luonnotar/privileged/GmsFreezerFastLane.kt"
  "app/src/main/java/com/yubegreen/luonnotar/privileged/GmsVendorFreezeBridge.kt"
  "app/src/main/java/com/yubegreen/luonnotar/privileged/GuardianTargetResolver.kt"
  "app/src/main/java/com/yubegreen/luonnotar/privileged/PrivilegedGuardianUserService.kt"
  "tools/device/luonnotar-guardian-v2.sh"
)

for file in "${OWNER_SURFACE_FILES[@]}"; do
  allowed=0
  for candidate in "${OWNER_SURFACE_ALLOWED[@]}"; do
    [[ "$file" == "$candidate" ]] && allowed=1 && break
  done
  if [[ "$allowed" -ne 1 ]]; then
    echo "UNEXPECTED freezer owner surface: $file" >&2
    exit 1
  fi
done

BRIDGE="app/src/main/java/com/yubegreen/luonnotar/privileged/GmsVendorFreezeBridge.kt"
FAST="app/src/main/java/com/yubegreen/luonnotar/privileged/GmsFreezerFastLane.kt"
SERVICE="app/src/main/java/com/yubegreen/luonnotar/privileged/PrivilegedGuardianUserService.kt"
LEGACY="tools/device/luonnotar-guardian-v2.sh"

rg -q 'command_owner_is_self' "$BRIDGE"
rg -q 'require_command_owner' "$BRIDGE"
rg -q 'type=owner_lost' "$BRIDGE"
rg -q 'heartbeat_file=.*base.heartbeat' "$BRIDGE"
rg -q 'pid_matches_target' "$BRIDGE"
rg -q 'framework_snapshot_lists_pid' "$BRIDGE"
rg -q 'parentStartTicks' "$BRIDGE"
rg -q 'shellStartTicks' "$BRIDGE"
rg -q 'pid_start_matches' "$BRIDGE"
rg -q 'vendor_bridge_owns_commands' "$FAST"
rg -q 'command_owner_suppressed' "$FAST"
rg -q 'parentStartTicks' "$FAST"
rg -q 'pid_start_matches' "$FAST"
rg -q 'vendorBridgeOwnerPackageLocked' "$SERVICE"
rg -q 'vendorBridgeOwnsGmsCommandsLocked' "$SERVICE"
rg -q 'isExactLegacyGuardianCommand' "$SERVICE"
rg -q 'readProcStartTimeTicks' "$SERVICE"
rg -q 'modern_owner_active' "$LEGACY"
rg -q 'unfreeze_suppressed owner=vendor_bridge' "$LEGACY"
rg -Fq 'refusing to overlap live r258+ freezer-command owner' "$LEGACY"
rg -q 'parentStartTicks' "$LEGACY"
rg -q 'pid_start_matches' "$LEGACY"

if rg -n '(^|[;&|[:space:]])(echo|printf)[^\n]*(0|THAWED)[^\n]*>[[:space:]]*[^\n]*(cgroup\.freeze|freezer\.state)' \
    app/src/main tools/device; then
  echo "Direct cgroup thaw write found" >&2
  exit 1
fi

echo "unfreeze-owner-surface-ok"
printf 'allowed_direct_surfaces=%s\n' "${DIRECT_FILES[*]}"
printf 'allowed_owner_surfaces=%s\n' "${OWNER_SURFACE_FILES[*]}"
