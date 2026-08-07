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
case "$command_name" in
  status) action="com.yubegreen.luonnotar.action.ADB_ENGINE_STATUS" ;;
  restart) action="com.yubegreen.luonnotar.action.ADB_ENGINE_RESTART" ;;
  *) usage >&2; exit 2 ;;
esac

"${adb_cmd[@]}" shell am broadcast -W \
  -a "$action" \
  -n com.yubegreen.luonnotar/.receiver.AdbEmbeddedEngineControlReceiver
