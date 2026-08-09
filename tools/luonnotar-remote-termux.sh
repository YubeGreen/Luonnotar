#!/usr/bin/env bash
set -euo pipefail

IQ_HOST="${LUONNOTAR_IQ_HOST:-100.111.89.64}"
HOST="$IQ_HOST"
TERMUX_USER="${LUONNOTAR_TERMUX_USER:-u0_a440}"
TERMUX_PORT="${LUONNOTAR_TERMUX_PORT:-8022}"
RESCUE_PORT="${LUONNOTAR_RESCUE_PORT:-8025}"
RESCUE_KEY="${LUONNOTAR_RESCUE_KEY:-$HOME/.ssh/luonnotar_iqoo_ed25519}"
WAIT_SECONDS="${LUONNOTAR_TERMUX_WAIT_SECONDS:-75}"

usage() {
  cat <<'EOF'
Usage: luonnotar-remote-termux.sh [--iq|HOST] [--user USER] [--port PORT] [--wait SECONDS]

Connects to Termux SSH. If Termux sshd is down, it first asks Luonnotar to
restart sshd through RUN_COMMAND, preferring ADB :5555 and falling back to the
independent Luonnotar shell SSH :8025. If both control paths are temporarily
down during an adbd restart, it waits for the keeper to respawn :8025.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --iq) HOST="$IQ_HOST"; shift ;;
    --user) TERMUX_USER="${2:?missing --user value}"; shift 2 ;;
    --port) TERMUX_PORT="${2:?missing --port value}"; shift 2 ;;
    --wait) WAIT_SECONDS="${2:?missing --wait value}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --*) echo "ERROR: unknown option $1" >&2; usage >&2; exit 2 ;;
    *) HOST="$1"; shift ;;
  esac
done

is_open() {
  nc -z -G 1 "$HOST" "$1" >/dev/null 2>&1
}

provider_via_adb() {
  command -v adb >/dev/null 2>&1 || return 1
  adb connect "$HOST:5555" >/dev/null 2>&1 || true
  adb -s "$HOST:5555" get-state >/dev/null 2>&1 || return 1
  local out
  out="$(adb -s "$HOST:5555" shell content call \
    --uri content://com.yubegreen.luonnotar.adb_runtime_config \
    --method rescue_termux_sshd 2>&1)" || return 1
  printf '%s\n' "$out" >&2
  [[ "$out" == *"ok=true"* ]]
}

provider_via_rescue_ssh() {
  [[ -r "$RESCUE_KEY" ]] || return 1
  is_open "$RESCUE_PORT" || return 1
  local out
  out="$(ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=3 \
    -i "$RESCUE_KEY" -p "$RESCUE_PORT" "shell@$HOST" \
    'content call --uri content://com.yubegreen.luonnotar.adb_runtime_config --method rescue_termux_sshd' \
    2>&1)" || return 1
  printf '%s\n' "$out" >&2
  [[ "$out" == *"ok=true"* ]]
}

if ! is_open "$TERMUX_PORT"; then
  echo "LUOTERM: Termux :$TERMUX_PORT is down; requesting Luonnotar recovery..." >&2
  provider_via_adb || provider_via_rescue_ssh || true

  deadline=$((SECONDS + WAIT_SECONDS))
  requested_after_keeper=0
  while (( SECONDS < deadline )); do
    if is_open "$TERMUX_PORT"; then
      echo "LUOTERM: Termux SSH recovered." >&2
      break
    fi

    # adb usb/tcpip restarts can briefly kill :8025. Once keeper brings it back,
    # dispatch one proactive Termux rescue instead of waiting for the next policy cycle.
    if (( requested_after_keeper == 0 )) && is_open "$RESCUE_PORT"; then
      if provider_via_rescue_ssh; then
        requested_after_keeper=1
      fi
    fi
    sleep 1
  done
fi

if ! is_open "$TERMUX_PORT"; then
  echo "LUOTERM_ERROR: $HOST:$TERMUX_PORT did not recover within ${WAIT_SECONDS}s" >&2
  echo "Rescue probe: nc -vz -G 3 $HOST $RESCUE_PORT" >&2
  return_code=1
else
  return_code=0
fi

(( return_code == 0 )) || exit "$return_code"
command ssh -p "$TERMUX_PORT" "$TERMUX_USER@$HOST"
