#!/usr/bin/env bash
set -euo pipefail

IQ_HOST="${LUONNOTAR_IQ_HOST:-100.111.89.64}"
IQ_USER="${LUONNOTAR_IQ_TERMUX_USER:-u0_a440}"
IQ_PORT="${LUONNOTAR_IQ_TERMUX_PORT:-8022}"
IQ_SSH_ALIAS="${LUONNOTAR_IQ_SSH_ALIAS:-iqoo}"
TERMUX_USER="${LUONNOTAR_TERMUX_USER:-u0_a440}"
TERMUX_PORT="${LUONNOTAR_TERMUX_PORT:-8022}"
RESCUE_PORT="${LUONNOTAR_RESCUE_PORT:-8025}"
RESCUE_KEY="${LUONNOTAR_RESCUE_KEY:-$HOME/.ssh/luonnotar_iqoo_ed25519}"
WAIT_SECONDS="${LUONNOTAR_TERMUX_WAIT_SECONDS:-75}"
HOST=""
TARGET_SEEN=0
IQ_SELECTED=0

usage() {
  cat <<'EOF_USAGE'
Usage:
  luoterm --iq [--user USER] [--port PORT] [--wait SECONDS]
  luoterm HOST [--user USER] [--port PORT] [--wait SECONDS]
  luoterm HOST:PORT [--user USER] [--wait SECONDS]
  luoterm USER@HOST[:PORT] [--wait SECONDS]

Device selector logic mirrors luosfud:
  --iq                iQOO shortcut -> 100.111.89.64 (Termux :8022; SSH Host alias: iqoo)
  HOST                explicit target device
  HOST:PORT           explicit target and Termux SSH port
  USER@HOST[:PORT]    explicit Termux user, target and optional port

If Termux sshd is down, Luonnotar first requests recovery over ADB :5555,
then falls back to the independent Luonnotar shell SSH :8025. If :8025 is
briefly down during an adbd restart, it waits for the keeper to respawn it.
EOF_USAGE
}

set_target() {
  local target="$1"
  [[ "$TARGET_SEEN" -eq 0 ]] || {
    echo "LUOTERM_ERROR: multiple device selectors supplied" >&2
    usage >&2
    exit 2
  }
  TARGET_SEEN=1

  if [[ "$target" == *@* ]]; then
    TERMUX_USER="${target%%@*}"
    target="${target#*@}"
  fi

  # Tailscale/IPv4 targets are expected here. Accept HOST:PORT as a convenience.
  if [[ "$target" =~ ^([^:]+):([0-9]+)$ ]]; then
    HOST="${BASH_REMATCH[1]}"
    TERMUX_PORT="${BASH_REMATCH[2]}"
  else
    HOST="$target"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --iq)
      [[ "$TARGET_SEEN" -eq 0 ]] || {
        echo "LUOTERM_ERROR: multiple device selectors supplied" >&2
        usage >&2
        exit 2
      }
      TARGET_SEEN=1
      IQ_SELECTED=1
      HOST="$IQ_HOST"
      TERMUX_USER="$IQ_USER"
      TERMUX_PORT="$IQ_PORT"
      shift
      ;;
    --user)
      TERMUX_USER="${2:?missing --user value}"
      shift 2
      ;;
    --port)
      TERMUX_PORT="${2:?missing --port value}"
      shift 2
      ;;
    --wait)
      WAIT_SECONDS="${2:?missing --wait value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --*)
      echo "LUOTERM_ERROR: unknown option $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      set_target "$1"
      shift
      ;;
  esac
done

if [[ "$TARGET_SEEN" -eq 0 || -z "$HOST" ]]; then
  echo "LUOTERM_ERROR: choose a device, e.g. 'luoterm --iq' or 'luoterm HOST'" >&2
  usage >&2
  exit 2
fi

if ! [[ "$TERMUX_PORT" =~ ^[0-9]+$ ]] || (( TERMUX_PORT < 1 || TERMUX_PORT > 65535 )); then
  echo "LUOTERM_ERROR: invalid Termux SSH port: $TERMUX_PORT" >&2
  exit 2
fi
if ! [[ "$WAIT_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "LUOTERM_ERROR: invalid wait seconds: $WAIT_SECONDS" >&2
  exit 2
fi

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

echo "LUOTERM: device=$HOST termux=$TERMUX_USER:$TERMUX_PORT" >&2

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
  exit 1
fi

if (( IQ_SELECTED )); then
  command ssh -p "$TERMUX_PORT" -l "$TERMUX_USER" "$IQ_SSH_ALIAS"
else
  command ssh -p "$TERMUX_PORT" "$TERMUX_USER@$HOST"
fi
