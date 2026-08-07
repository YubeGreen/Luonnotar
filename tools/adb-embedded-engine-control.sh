#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  tools/adb-embedded-engine-control.sh [--serial SERIAL] status
  tools/adb-embedded-engine-control.sh [--serial SERIAL] restart
USAGE
}

serial=""
if [ "${1:-}" = "--serial" ]; then
  [ "$#" -ge 3 ] || { usage >&2; exit 2; }
  serial="$2"
  shift 2
fi
command_name="${1:-status}"
[ "$#" -le 1 ] || { usage >&2; exit 2; }

adb_cmd=(adb)
[ -z "$serial" ] || adb_cmd+=(-s "$serial")
authority="content://com.yubegreen.luonnotar.adb_runtime_config"

call_provider() {
  local method="$1"
  "${adb_cmd[@]}" shell content call \
    --uri "$authority" \
    --method "$method"
}

wire_value() {
  local key="$1" input="$2"
  printf '%s\n' "$input" \
    | grep -o "${key}=[^;},]*" \
    | head -n 1 \
    | cut -d= -f2-
}

case "$command_name" in
  status)
    call_provider engine_status
    ;;
  restart)
    before="$(call_provider engine_status)"
    old_pid="$(wire_value pid "$before" || true)"
    old_revision="$(wire_value actualRevision "$before" || true)"
    echo "BEFORE pid=${old_pid:-unknown} revision=${old_revision:-unknown}"

    dispatch="$(call_provider engine_restart)"
    printf '%s\n' "$dispatch"
    if ! grep -q 'dispatched=true' <<<"$dispatch"; then
      echo "ENGINE_RESTART_DISPATCH_FAILED" >&2
      exit 1
    fi

    last=""
    for attempt in $(seq 1 20); do
      sleep 1
      last="$(call_provider engine_status)"
      if grep -q 'engineReachable=true' <<<"$last" && \
         grep -q 'revisionCurrent=true' <<<"$last"; then
        new_pid="$(wire_value pid "$last" || true)"
        new_revision="$(wire_value actualRevision "$last" || true)"
        echo "ENGINE_RESTART_OK attempt=$attempt oldPid=${old_pid:-unknown} newPid=${new_pid:-unknown} oldRevision=${old_revision:-unknown} newRevision=${new_revision:-unknown}"
        printf '%s\n' "$last"
        exit 0
      fi
    done
    echo "ENGINE_RESTART_VERIFY_TIMEOUT" >&2
    printf '%s\n' "$last" >&2
    exit 1
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
