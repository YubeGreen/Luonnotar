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
start_out=$(adb "${adb_args[@]}" shell content call \
  --uri content://com.yubegreen.luonnotar.adb_runtime_config \
  --method self_update \
  --extra "apk_path:s:$remote" 2>&1 || true)

field() {
  local name="$1" text="$2" value
  # self_update_status fields appear progressively. Under `set -euo pipefail`,
  # a grep miss must mean "field not present yet", not "abort the updater".
  value=$(printf '%s\n' "$text" | grep -oE "${name}=[^,;}]*" | tail -n 1 | sed -E "s/^${name}=//" || true)
  printf '%s' "$value"
  return 0
}

summarize() {
  local text="$1" state install handoff code version expected candidate pid
  state=$(field state "$text")
  install=$(field installState "$text")
  handoff=$(field handoffState "$text")
  code=$(field code "$text")
  version=$(field versionCode "$text")
  expected=$(field handoffExpectedRevision "$text")
  candidate=$(field handoffCandidateRevision "$text")
  pid=$(field handoffCandidatePid "$text")
  if [[ -z "$state$install$handoff" ]]; then
    printf '%s\n' "$text"
    return
  fi
  printf 'Self-update: state=%s install=%s handoff=%s' \
    "${state:-?}" "${install:-?}" "${handoff:-?}"
  [[ -n "$code" && "$code" != "" ]] && printf ' code=%s' "$code"
  [[ -n "$version" && "$version" != "-1" ]] && printf ' v=%s' "$version"
  [[ -n "$expected" && "$expected" != "-1" ]] && printf ' expected-r%s' "$expected"
  [[ -n "$candidate" && "$candidate" != "-1" ]] && printf ' candidate-r%s' "$candidate"
  [[ -n "$pid" && "$pid" != "-1" ]] && printf ' pid=%s' "$pid"
  printf '\n'
}

summarize "$start_out"
last_signature=""
for i in $(seq 1 60); do
  out=$(adb "${adb_args[@]}" shell content call \
    --uri content://com.yubegreen.luonnotar.adb_runtime_config \
    --method self_update_status 2>&1 || true)
  signature="$(field state "$out")|$(field installState "$out")|$(field handoffState "$out")|$(field code "$out")|$(field versionCode "$out")|$(field handoffExpectedRevision "$out")|$(field handoffCandidateRevision "$out")|$(field handoffCandidatePid "$out")|$(field handoffReason "$out")"
  if [[ "$signature" != "$last_signature" ]]; then
    summarize "$out"
    last_signature="$signature"
  fi
  echo "$out" | grep -q 'state=success' && exit 0
  echo "$out" | grep -q 'state=failure' && exit 1
  sleep 1
done
echo "self update status timeout" >&2
exit 124
