#!/usr/bin/env bash
set -euo pipefail
serial=""
apk=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) serial="${2:?}"; shift 2 ;;
    --apk) apk="${2:?}"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 64 ;;
  esac
done
[[ -n "$apk" && -f "$apk" ]] || { echo "--apk FILE required" >&2; exit 64; }
adb_args=()
[[ -z "$serial" ]] || adb_args=(-s "$serial")
remote="/data/local/tmp/luonnotar-self-update/$(basename "$apk")"
adb "${adb_args[@]}" shell 'mkdir -p /data/local/tmp/luonnotar-self-update && chmod 700 /data/local/tmp/luonnotar-self-update'
adb "${adb_args[@]}" push "$apk" "$remote"
adb "${adb_args[@]}" shell chmod 600 "$remote"
adb "${adb_args[@]}" shell content call \
  --uri content://com.yubegreen.luonnotar.adb_runtime_config \
  --method self_update \
  --extra string apk_path "$remote"
for i in $(seq 1 60); do
  out=$(adb "${adb_args[@]}" shell content call \
    --uri content://com.yubegreen.luonnotar.adb_runtime_config \
    --method self_update_status 2>&1 || true)
  printf '%s\n' "$out"
  echo "$out" | grep -q 'state=success' && exit 0
  echo "$out" | grep -q 'state=failure' && exit 1
  sleep 1
done
echo "self update status timeout" >&2
exit 124
