#!/usr/bin/env bash
# Read-only by default. Use --quarantine-legacy only to stop the exact retired
# luonnotar-guardian-v2.sh daemon after PID/start-time/cmdline revalidation.
set -u
set -o pipefail

SERIAL=""
QUARANTINE=0
SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<'EOF'
Usage:
  tools/audit-unfreeze-owners.sh [--device SERIAL] [--quarantine-legacy] [--source-root PATH]

Default mode is read-only. The quarantine flag can only signal a process whose
/proc cmdline contains an exact token named luonnotar-guardian-v2.sh followed
immediately by the token daemon, and whose /proc start time remains unchanged.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --device) SERIAL="${2:-}"; shift 2 ;;
    --quarantine-legacy) QUARANTINE=1; shift ;;
    --source-root) SOURCE_ROOT="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 64 ;;
  esac
done

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB+=( -s "$SERIAL" )
adb_shell() { "${ADB[@]}" shell "$@" 2>/dev/null | tr -d '\r'; }

section() { printf '\n===== %s =====\n' "$1"; }

section "HOST SOURCE OWNERS"
if command -v rg >/dev/null 2>&1 && [[ -d "$SOURCE_ROOT" ]]; then
  rg -n --hidden \
    -g '!**/.git/**' -g '!**/build/**' -g '!**/.gradle/**' \
    'cmd activity unfreeze|am unfreeze|ActivityManagerUnfreezeCommand|scheduleGmsFastThaw|unfreezeProcessNameLocked' \
    "$SOURCE_ROOT/app/src/main" "$SOURCE_ROOT/tools" 2>/dev/null || true
else
  echo "source scan unavailable (rg or source root missing): $SOURCE_ROOT"
fi

section "DEVICE"
STATE="$("${ADB[@]}" get-state 2>/dev/null || true)"
echo "serial=${SERIAL:-default} state=$STATE"
[[ "$STATE" == "device" ]] || exit 1

OWNER_PATH="/data/local/tmp/luonnotar-freezer-command-owner"
LEGACY_PID_PATH="/data/local/tmp/luonnotar2/guardian.pid"
STATUS_PATH="/data/local/tmp/luonnotar-guardian-status.json"

section "PRIVILEGED ENGINE SINGLETON"
adb_shell ps -A -o USER,PID,PPID,NAME,ARGS | \
  grep -E '(^|[[:space:]])luonnotar_privileged_engine([[:space:]]|$)|com\.yubegreen\.luonnotar(:keeper)?([[:space:]]|$)' || true
adb_shell cat /data/local/tmp/luonnotar-guardian-engine-state.json || true

section "FREEZER COMMAND OWNER"
OWNER_RAW="$(adb_shell cat "$OWNER_PATH" || true)"
printf '%s\n' "${OWNER_RAW:-missing}"
OWNER_SHELL="$(printf '%s\n' "$OWNER_RAW" | awk -F= '$1=="shellPid" {print $2; exit}')"
OWNER_PARENT="$(printf '%s\n' "$OWNER_RAW" | awk -F= '$1=="parentPid" {print $2; exit}')"
OWNER_PARENT_START="$(printf '%s\n' "$OWNER_RAW" | awk -F= '$1=="parentStartTicks" {print $2; exit}')"
OWNER_SHELL_START="$(printf '%s\n' "$OWNER_RAW" | awk -F= '$1=="shellStartTicks" {print $2; exit}')"
OWNER_HEARTBEAT="$(printf '%s\n' "$OWNER_RAW" | awk -F= '$1=="heartbeatPath" {print $2; exit}')"
if [[ "$OWNER_HEARTBEAT" == /data/local/tmp/luonnotar-vendor-freeze-bridge-*.heartbeat ]]; then
  echo "--- heartbeat ---"
  adb_shell cat "$OWNER_HEARTBEAT" || true
else
  echo "heartbeat_path=invalid_or_missing"
fi
for pid in "$OWNER_PARENT" "$OWNER_SHELL"; do
  [[ "$pid" =~ ^[0-9]+$ ]] || continue
  echo "--- proc/$pid ---"
  adb_shell sh -c "tr '\\000' ' ' < /proc/$pid/cmdline 2>/dev/null; echo; cat /proc/$pid/stat 2>/dev/null" || true
done

section "ALL SHELL-LIKE CANDIDATES"
PS_RAW="$(adb_shell ps -A -o USER,PID,PPID,NAME,ARGS || adb_shell ps -A || true)"
printf '%s\n' "$PS_RAW" | grep -iE \
  'luonnotar|guardian-v2|vendor-freeze-bridge|gms-fast-lane|(^|[[:space:]])(sh|bash)([[:space:]]|$)|unfreeze' || true

proc_start_time() {
  local pid="$1" raw tail
  raw="$(adb_shell cat "/proc/$pid/stat" || true)"
  [[ "$raw" == *') '* ]] || return 1
  tail="${raw##*) }"
  awk '{print $20}' <<<"$tail"
}

proc_cmdline() {
  local pid="$1"
  adb_shell sh -c "tr '\\000' ' ' < /proc/$pid/cmdline 2>/dev/null" || true
}

is_exact_legacy_cmdline() {
  local text="$1" previous="" token
  # shellcheck disable=SC2206
  local tokens=( $text )
  for token in "${tokens[@]}"; do
    if [[ "${previous##*/}" == "luonnotar-guardian-v2.sh" && "$token" == "daemon" ]]; then
      return 0
    fi
    previous="$token"
  done
  return 1
}

LEGACY_CANDIDATES=()
LEGACY_PID="$(adb_shell cat "$LEGACY_PID_PATH" || true)"
[[ "$LEGACY_PID" =~ ^[0-9]+$ ]] && LEGACY_CANDIDATES+=("$LEGACY_PID")
while IFS= read -r pid; do
  [[ "$pid" =~ ^[0-9]+$ ]] && LEGACY_CANDIDATES+=("$pid")
done < <(printf '%s\n' "$PS_RAW" | awk '/luonnotar-guardian-v2\.sh/ && /daemon/ {print $2}')

section "RETIRED LEGACY UNFREEZE DAEMON"
if [[ ${#LEGACY_CANDIDATES[@]} -eq 0 ]]; then
  echo "none"
else
  printf '%s\n' "${LEGACY_CANDIDATES[@]}" | sort -u | while IFS= read -r pid; do
    cmd="$(proc_cmdline "$pid")"
    start="$(proc_start_time "$pid" || true)"
    if ! is_exact_legacy_cmdline "$cmd" || [[ ! "$start" =~ ^[0-9]+$ ]]; then
      echo "pid=$pid verdict=ignored_not_exact cmd=$cmd"
      continue
    fi
    echo "pid=$pid start=$start verdict=exact_legacy_daemon cmd=$cmd"
    [[ "$QUARANTINE" -eq 1 ]] || continue

    cmd2="$(proc_cmdline "$pid")"
    start2="$(proc_start_time "$pid" || true)"
    if [[ "$cmd2" != "$cmd" || "$start2" != "$start" ]] || ! is_exact_legacy_cmdline "$cmd2"; then
      echo "pid=$pid quarantine=aborted_identity_changed"
      continue
    fi
    adb_shell kill -TERM "$pid" >/dev/null || true
    sleep 0.2
    cmd3="$(proc_cmdline "$pid")"
    start3="$(proc_start_time "$pid" || true)"
    if [[ "$cmd3" == "$cmd" && "$start3" == "$start" ]] && is_exact_legacy_cmdline "$cmd3"; then
      adb_shell kill -KILL "$pid" >/dev/null || true
      sleep 0.1
    fi
    cmd4="$(proc_cmdline "$pid")"
    start4="$(proc_start_time "$pid" || true)"
    if [[ "$cmd4" == "$cmd" && "$start4" == "$start" ]]; then
      echo "pid=$pid quarantine=failed"
    else
      echo "pid=$pid quarantine=stopped"
    fi
  done
fi

section "CURRENT STATUS OWNER FIELDS"
if command -v python3 >/dev/null 2>&1; then
  adb_shell cat "$STATUS_PATH" | python3 -c '
import json, sys
try:
    j=json.load(sys.stdin)
except Exception as e:
    print("status_error=", e)
    raise SystemExit(0)
b=j.get("gmsVendorFreezeBridge") or {}
f=j.get("gmsFreezerFastLane") or {}
print("schema=", j.get("schema"))
print("running=", j.get("running"))
for k in ("targetEnabled","targets","alive","ready","strategy","shellPid","parentStartTimeTicks","shellStartTimeTicks","heartbeatPath","heartbeatFileValid","heartbeatFileAgeMs","ownerPath","ownerLeaseValid","ownershipPolicyActive","gmsFallbackSuppressed","adoptReleaseCount","adoptUnconfirmedCount","frameworkLedgerRetryCount","legacyGuardianDetectedCount","legacyGuardianStoppedCount","legacyGuardianLastResult"):
    print(f"bridge.{k}=", b.get(k))
print("fastlane.ready=", f.get("ready"))
print("fastlane.backend=", f.get("backend"))
' || true
else
  adb_shell cat "$STATUS_PATH" || true
fi

section "VERDICT"
ENGINE_COUNT="$(printf '%s\n' "$PS_RAW" | grep -c 'luonnotar_privileged_engine' || true)"
echo "privileged_engine_count=$ENGINE_COUNT"
if [[ "$ENGINE_COUNT" -gt 1 ]]; then
  echo "ERROR: multiple privileged engines"
elif [[ "$ENGINE_COUNT" -eq 1 ]]; then
  echo "OK: one privileged engine"
else
  echo "WARN: privileged engine not visible"
fi
[[ -n "$OWNER_RAW" ]] || echo "WARN: no active vendor bridge command owner"
echo "read_only=$((1-QUARANTINE))"
