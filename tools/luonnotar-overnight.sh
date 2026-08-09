#!/usr/bin/env bash
set -euo pipefail

# Luonnotar low-interference overnight observer for macOS.
# Host-side only: it does not change Luonnotar/GMS/VPN/freezer settings and does
# not perform push/GMS recovery during the observation window.

PROJECT_ROOT="${LUONNOTAR_PROJECT_ROOT:-$(cd "$(dirname "$0")/.." && pwd -P)}"
SEND_EVENTS="${LUOOVN_SEND_EVENTS:-/Volumes/SMBProbe/send-events.csv}"
OUTPUT_BASE="${LUOOVN_OUTPUT_BASE:-$HOME/Desktop}"
RESCUE_PORT="${LUONNOTAR_RESCUE_PORT:-8025}"
RESCUE_KEY="${LUONNOTAR_RESCUE_KEY:-$HOME/.ssh/luonnotar_iqoo_ed25519}"
LOGCAT_RING_SIZE="${LUOOVN_LOGCAT_RING_SIZE:-64M}"
DEFAULT_HHMM="${LUOOVN_DEFAULT_END_HHMM:-1600}"
DETACH_ADB="${LUOOVN_DETACH_ADB:-1}"
REQUIRE_SCREEN_OFF="${LUOOVN_REQUIRE_SCREEN_OFF:-1}"

DEVICE_NAME=""
HOST=""
SERIAL=""
END_HHMM="$DEFAULT_HHMM"
TARGET_SEEN=0
TIME_SEEN=0
DRY_RUN=0
NO_RING_RESIZE=0
FINALIZED=0
CAFFEINATE_PID=""
START_LOCAL=""
START_UTC=""
START_EPOCH=""
END_LOCAL=""
END_EPOCH=""
DEVICE_LOGCAT_START=""
ROOT=""
ZIP_PATH=""
RUN_LOG=""

usage() {
  cat <<'EOF_USAGE'
Usage:
  luoovn --iq [-HHMM]
  luoovn --pad [-HHMM]
  luoovn --serial HOST[:PORT] [--name NAME] [-HHMM]

Examples:
  luoovn --iq -1600       # iQOO, stop at the next local 16:00
  luoovn --pad -1600      # Xiaomi Pad, stop at the next local 16:00
  luoovn --iq -1000       # stop at the next local 10:00
  luoovn --iq -1600 --no-screen-check
                            # skip only the startup screen-off gate

Device shortcuts:
  --iq    100.111.89.64:5555
  --pad   100.117.209.84:5555

Time syntax:
  -HHMM means the next occurrence of that local Mac clock time. If omitted,
  the default is -1600.

Options:
  --send-events PATH     sender truth CSV (default: /Volumes/SMBProbe/send-events.csv)
  --output-base DIR      parent directory for capture folder (default: ~/Desktop)
  --no-ring-resize       do not request a 64M Android logcat ring
  --keep-adb             keep the host ADB transport attached during observation
                          (explicitly accepts the extra ADB-side interference)
  --no-screen-check      skip the startup screen-off confirmation gate
                          (the initial power state is still captured; no keyevent is sent)
  --screen-check         explicitly require the default screen-off gate
  --dry-run              print resolved plan only; do not touch the device
  -h, --help             show this help

Observation policy:
  * no periodic ADB polling
  * no live logcat streaming
  * no screen keyevents
  * no GMS/WhatsApp force-stop
  * no VPN/freezer/Luonnotar config changes
  * no explicit rescue_* actions during the observation window
  * sender CSV is snapshotted, never held open continuously
EOF_USAGE
}

set_target() {
  local name="$1" host="$2" serial="$3"
  if (( TARGET_SEEN != 0 )); then
    echo "LUOOVN_ERROR: multiple device selectors supplied" >&2
    exit 2
  fi
  TARGET_SEEN=1
  DEVICE_NAME="$name"
  HOST="$host"
  SERIAL="$serial"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --iq)
      set_target "iqoo-v2352a" "100.111.89.64" "100.111.89.64:5555"
      shift
      ;;
    --pad)
      set_target "xiaomi-pad" "100.117.209.84" "100.117.209.84:5555"
      shift
      ;;
    --serial)
      raw="${2:?missing --serial value}"
      if [[ "$raw" == *:* ]]; then
        host="${raw%:*}"
        serial="$raw"
      else
        host="$raw"
        serial="$raw:5555"
      fi
      set_target "manual" "$host" "$serial"
      shift 2
      ;;
    --name)
      DEVICE_NAME="${2:?missing --name value}"
      shift 2
      ;;
    --send-events)
      SEND_EVENTS="${2:?missing --send-events value}"
      shift 2
      ;;
    --output-base)
      OUTPUT_BASE="${2:?missing --output-base value}"
      shift 2
      ;;
    --no-ring-resize)
      NO_RING_RESIZE=1
      shift
      ;;
    --keep-adb)
      DETACH_ADB=0
      shift
      ;;
    --no-screen-check|--skip-screen-check)
      REQUIRE_SCREEN_OFF=0
      shift
      ;;
    --screen-check|--require-screen-off)
      REQUIRE_SCREEN_OFF=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -[0-9][0-9][0-9][0-9])
      if (( TIME_SEEN != 0 )); then
        echo "LUOOVN_ERROR: multiple end-time selectors supplied" >&2
        exit 2
      fi
      TIME_SEEN=1
      END_HHMM="${1#-}"
      shift
      ;;
    --*)
      echo "LUOOVN_ERROR: unknown option $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      echo "LUOOVN_ERROR: unexpected argument $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if (( TARGET_SEEN == 0 )); then
  echo "LUOOVN_ERROR: choose a device: --iq, --pad, or --serial HOST[:PORT]" >&2
  exit 2
fi
if [[ ! "$END_HHMM" =~ ^([01][0-9]|2[0-3])[0-5][0-9]$ ]]; then
  echo "LUOOVN_ERROR: invalid end time -$END_HHMM; expected -HHMM" >&2
  exit 2
fi
if [[ -z "$DEVICE_NAME" ]]; then
  DEVICE_NAME="manual"
fi
if [[ "$REQUIRE_SCREEN_OFF" != "0" && "$REQUIRE_SCREEN_OFF" != "1" ]]; then
  echo "LUOOVN_ERROR: LUOOVN_REQUIRE_SCREEN_OFF must be 0 or 1" >&2
  exit 2
fi
DEVICE_NAME="$(printf '%s' "$DEVICE_NAME" | tr -cs 'A-Za-z0-9._-' '-')"

TIME_RAW="$(python3 - "$END_HHMM" <<'PY'
from datetime import datetime, timedelta
import sys
hhmm=sys.argv[1]
now=datetime.now().astimezone()
hour=int(hhmm[:2]); minute=int(hhmm[2:])
target=now.replace(hour=hour, minute=minute, second=0, microsecond=0)
if target <= now:
    target += timedelta(days=1)
print("|".join([
    now.isoformat(timespec='seconds'),
    str(int(now.timestamp())),
    target.isoformat(timespec='seconds'),
    str(int(target.timestamp())),
    now.strftime('%Y%m%d-%H%M%S'),
    target.strftime('%Y%m%d-%H%M%S'),
]))
PY
)"
IFS='|' read -r START_LOCAL START_EPOCH END_LOCAL END_EPOCH START_STAMP END_STAMP <<< "$TIME_RAW"
START_UTC="$(python3 - "$START_EPOCH" <<'PY'
from datetime import datetime, timezone
import sys
print(datetime.fromtimestamp(int(sys.argv[1]), timezone.utc).isoformat(timespec='milliseconds'))
PY
)"

ROOT="$OUTPUT_BASE/luonnotar-overnight-${DEVICE_NAME}-${START_STAMP}-to-${END_STAMP}"
ZIP_PATH="$ROOT.zip"
RUN_LOG="$ROOT/00-orchestrator.log"

if (( DRY_RUN != 0 )); then
  cat <<EOF_PLAN
LUOOVN_DRY_RUN
Device      : $DEVICE_NAME
Serial      : $SERIAL
Host        : $HOST
Start       : $START_LOCAL
Planned end : $END_LOCAL
Send events : $SEND_EVENTS
Output      : $ROOT
ADB detach  : $DETACH_ADB
Screen gate : $([[ $REQUIRE_SCREEN_OFF -eq 1 ]] && echo required || echo disabled)
Logcat ring : $([[ $NO_RING_RESIZE -eq 1 ]] && echo unchanged || echo "$LOGCAT_RING_SIZE")
EOF_PLAN
  exit 0
fi

command -v adb >/dev/null 2>&1 || { echo "LUOOVN_ERROR: adb not found in PATH" >&2; exit 3; }
command -v python3 >/dev/null 2>&1 || { echo "LUOOVN_ERROR: python3 not found in PATH" >&2; exit 3; }
[[ -f "$SEND_EVENTS" ]] || { echo "LUOOVN_ERROR: sender CSV not found: $SEND_EVENTS" >&2; exit 3; }
mkdir -p "$ROOT"

log() {
  local line
  line="[$(date '+%Y-%m-%d %H:%M:%S')] $*"
  printf '%s\n' "$line" | tee -a "$RUN_LOG"
}

probe_port() {
  local port="$1"
  if nc -z -G 2 "$HOST" "$port" >/dev/null 2>&1; then
    printf 'UP'
  else
    printf 'DOWN'
  fi
}

adb_ready() {
  adb connect "$SERIAL" >/dev/null 2>&1 || true
  [[ "$(adb -s "$SERIAL" get-state 2>/dev/null || true)" == "device" ]]
}

save_adb() {
  local path="$1"; shift
  {
    printf 'COMMAND: adb -s %q' "$SERIAL"
    printf ' %q' "$@"
    printf '\n\n'
    adb -s "$SERIAL" "$@"
  } >"$path" 2>&1 || true
}

save_shell() {
  local path="$1" cmd="$2"
  {
    printf 'COMMAND: %s\n\n' "$cmd"
    adb -s "$SERIAL" shell "$cmd"
  } >"$path" 2>&1 || true
}

save_shell_end() {
  local path="$1" cmd="$2"
  if adb_ready; then
    {
      printf 'TRANSPORT: adb %s\nCOMMAND: %s\n\n' "$SERIAL" "$cmd"
      adb -s "$SERIAL" shell "$cmd"
    } >"$path" 2>&1 || true
    return
  fi
  if [[ -r "$RESCUE_KEY" ]] && nc -z -G 2 "$HOST" "$RESCUE_PORT" >/dev/null 2>&1; then
    {
      printf 'TRANSPORT: luonnotar-ssh %s:%s\nCOMMAND: %s\n\n' "$HOST" "$RESCUE_PORT" "$cmd"
      ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=4 \
        -i "$RESCUE_KEY" -p "$RESCUE_PORT" "shell@$HOST" "$cmd"
    } >"$path" 2>&1 || true
    return
  fi
  printf 'ERROR: no read-only shell transport available at final capture\n' >"$path"
}

csv_age_seconds="$(python3 - "$SEND_EVENTS" <<'PY'
from pathlib import Path
import time,sys
p=Path(sys.argv[1])
print(max(0,int(time.time()-p.stat().st_mtime)))
PY
)"
if (( csv_age_seconds > 900 )); then
  log "WARNING: send-events.csv has not changed for ${csv_age_seconds}s; sender may be idle/stale."
fi

log "Preflight: $DEVICE_NAME $SERIAL -> next $END_HHMM ($END_LOCAL)"
if ! adb_ready; then
  log "ERROR: ADB device is not ready: $SERIAL"
  exit 4
fi

POWER_START_RAW="$(adb -s "$SERIAL" shell dumpsys power 2>/dev/null || true)"
START_SCREEN_STATE="non_interactive_or_unknown"
if grep -Eq 'mWakefulness=Awake|mInteractive=true' <<<"$POWER_START_RAW"; then
  START_SCREEN_STATE="interactive"
fi
if (( REQUIRE_SCREEN_OFF != 0 )) && [[ "$START_SCREEN_STATE" == "interactive" ]]; then
  printf '%s\n' "$POWER_START_RAW" >"$ROOT/04-power-start.txt"
  log "ERROR: target screen/device is interactive. Turn the screen off manually, then rerun; or append --no-screen-check to disable only this startup gate. luoovn will never send a screen keyevent."
  exit 5
fi
if (( REQUIRE_SCREEN_OFF == 0 )); then
  log "Startup screen-off gate disabled by suffix; captured initial screen state=$START_SCREEN_STATE and continuing without keyevents."
fi

DEVICE_LOGCAT_START="$(adb -s "$SERIAL" shell "date '+%m-%d %H:%M:%S.000'" 2>/dev/null | tr -d '\r' | tail -n 1)"
PACKAGE_DUMP="$(adb -s "$SERIAL" shell dumpsys package com.yubegreen.luonnotar 2>/dev/null || true)"
VERSION_NAME="$(sed -nE 's/^[[:space:]]*versionName=([^[:space:]]+).*/\1/p' <<<"$PACKAGE_DUMP" | head -n1)"
VERSION_CODE="$(sed -nE 's/^[[:space:]]*versionCode=([0-9]+).*/\1/p' <<<"$PACKAGE_DUMP" | head -n1)"

cp -f "$SEND_EVENTS" "$ROOT/01-send-events-start.csv"
adb devices -l >"$ROOT/02-adb-devices-start.txt" 2>&1 || true
printf '%s\n' "$PACKAGE_DUMP" >"$ROOT/03-luonnotar-package-start.txt"
printf '%s\n' "$POWER_START_RAW" >"$ROOT/04-power-start.txt"
save_shell "$ROOT/05-deviceidle-start.txt" "dumpsys deviceidle"
save_shell "$ROOT/06-battery-start.txt" "dumpsys battery"
save_shell "$ROOT/07-guardian-status-start.json" "cat /data/local/tmp/luonnotar-guardian-status.json 2>/dev/null || true"
save_shell "$ROOT/08-guardian-events-start-tail.txt" "tail -n 400 /data/local/tmp/luonnotar-guardian-events.log 2>/dev/null || true"
save_adb "$ROOT/09-logcat-buffer-before.txt" logcat -g

if (( NO_RING_RESIZE == 0 )); then
  if grep -Eq '64 MiB|67108864' "$ROOT/09-logcat-buffer-before.txt" && [[ "$LOGCAT_RING_SIZE" == "64M" ]]; then
    echo "Already at requested 64M ring; no resize issued." >"$ROOT/10-logcat-buffer-after.txt"
  else
    {
      echo "Requested logcat ring: $LOGCAT_RING_SIZE"
      adb -s "$SERIAL" logcat -G "$LOGCAT_RING_SIZE"
      echo "rc=$?"
      adb -s "$SERIAL" logcat -g
    } >"$ROOT/10-logcat-buffer-after.txt" 2>&1 || true
  fi
else
  echo "Skipped by --no-ring-resize" >"$ROOT/10-logcat-buffer-after.txt"
fi

cat >"$ROOT/00-test-info.txt" <<EOF_INFO
Test: Luonnotar unattended overnight push soak
Device: $DEVICE_NAME
Serial: $SERIAL
Host: $HOST
Luonnotar versionName: ${VERSION_NAME:-unknown}
Luonnotar versionCode: ${VERSION_CODE:-unknown}
Started local: $START_LOCAL
Started UTC: $START_UTC
Device logcat start marker: ${DEVICE_LOGCAT_START:-unknown}
Planned end local: $END_LOCAL
Sender truth: $SEND_EVENTS
Output: $ROOT

Control conditions:
- low-interference unattended observation
- startup screen-off gate: $([[ $REQUIRE_SCREEN_OFF -eq 1 ]] && echo required || echo disabled)
- startup screen state: $START_SCREEN_STATE
- no periodic ADB polling
- no live logcat stream
- no screen keyevents
- no GMS/WhatsApp force-stop
- no VPN/freezer/Luonnotar configuration writes
- no explicit rescue_* calls during observation window
- send-events.csv is copied only at start/end; it is never held open continuously
- Android logcat is not cleared
- requested ring size: $([[ $NO_RING_RESIZE -eq 1 ]] && echo unchanged || echo "$LOGCAT_RING_SIZE")
- detach host ADB during observation: $DETACH_ADB

Purpose:
- correlate sender SEND_RESULT records with Luonnotar push_test_arrival_observed evidence
- calculate end-to-end delays and missing-arrival evidence automatically
- preserve GMS/WhatsApp/Luonnotar/freezer/network evidence without active recovery
- retain screen/wake evidence so manual wake-up remains a visible confounder
EOF_INFO

{
  echo "captured=$(date '+%Y-%m-%dT%H:%M:%S%z')"
  echo "8022=$(probe_port 8022)"
  echo "8025=$(probe_port 8025)"
  echo "5555=$(probe_port 5555)"
} >"$ROOT/11-control-planes-start.txt"

log "Start snapshot complete. No device polling will occur during the observation window."
if (( DETACH_ADB != 0 )); then
  adb disconnect "$SERIAL" >/dev/null 2>&1 || true
  log "Host ADB transport detached for low-interference observation."
  sleep 3
  ADB_AFTER_DETACH="$(adb devices 2>/dev/null || true)"
  if grep -F "$SERIAL" <<<"$ADB_AFTER_DETACH" >/dev/null; then
    log "ERROR: the device reattached itself after detach. Pause any external adb-connect automation, or rerun with --keep-adb if you intentionally accept that interference."
    exit 6
  fi
else
  log "Host ADB transport left attached (--keep-adb)."
fi

if command -v caffeinate >/dev/null 2>&1; then
  caffeinate -i -w $$ >/dev/null 2>&1 &
  CAFFEINATE_PID=$!
  log "Mac caffeinate armed (host only)."
fi

make_send_window() {
  python3 - "$ROOT/01-send-events-start.csv" "$ROOT/30-send-events-end.csv" "$ROOT/31-send-events-window.csv" "$START_UTC" "$END_LOCAL" >"$ROOT/31-send-events-window-info.txt" <<'PY'
import csv,sys
from datetime import datetime, timezone
from pathlib import Path
start_path,end_path,out_path=map(Path,sys.argv[1:4])
start_utc=datetime.fromisoformat(sys.argv[4].replace('Z','+00:00')).astimezone(timezone.utc)
end_local=datetime.fromisoformat(sys.argv[5])
end_utc=end_local.astimezone(timezone.utc)
with end_path.open('r',encoding='utf-8-sig',newline='',errors='replace') as f:
    reader=csv.DictReader(f)
    fields=reader.fieldnames or []
    rows=[]
    for row in reader:
        raw=(row.get('utc_time') or '').strip()
        try:
            dt=datetime.strptime(raw,'%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=timezone.utc)
        except ValueError:
            continue
        if start_utc <= dt <= end_utc:
            rows.append(row)
with out_path.open('w',encoding='utf-8-sig',newline='') as f:
    if fields:
        w=csv.DictWriter(f,fieldnames=fields)
        w.writeheader(); w.writerows(rows)
print(f'window_start_utc={start_utc.isoformat()}')
print(f'window_end_utc={end_utc.isoformat()}')
print(f'rows={len(rows)}')
print(f'output={out_path}')
PY
}

make_plain_summary() {
  python3 - "$ROOT/21-logcat-ring-final.txt" "$ROOT/31-send-events-window.csv" "$ROOT/90-delay-summary.txt" "$DEVICE_NAME" "$START_LOCAL" "$END_LOCAL" "$ROOT/40-screen-wake-logcat.txt" "$ROOT/22-guardian-events-final.log" "$ROOT/43-screen-wake-batterystats.txt" <<'PY'
import csv,math,re,statistics,sys
from datetime import datetime,timezone
from pathlib import Path
log_path,send_path,out_path=map(Path,sys.argv[1:4])
device,start_local,end_local=sys.argv[4:7]
screen_path=Path(sys.argv[7]); guardian_path=Path(sys.argv[8]); batterystats_screen_path=Path(sys.argv[9])
field_re=re.compile(r'([A-Za-z][A-Za-z0-9_]*)=([^,}]+)')
deliveries={}
for line in log_path.read_text(encoding='utf-8',errors='replace').splitlines() if log_path.exists() else []:
    if 'push_test_arrival_observed' not in line:
        continue
    fields={k:v.strip() for k,v in field_re.findall(line)}
    try:
        seq=int(fields.get('sequence',''))
        sender=int(fields.get('senderEpochMs','0'))
        seen=int(fields.get('seenWall','0'))
        delay=int(fields.get('endToEndDelayMs', str(seen-sender)))
    except ValueError:
        continue
    if seq < 0 or sender <= 0 or seen <= 0:
        continue
    cur=deliveries.get(seq)
    if cur is None or seen < cur['seen']:
        deliveries[seq]={'seq':seq,'sender':sender,'seen':seen,'delay':delay}
sends={}
if send_path.exists():
    with send_path.open('r',encoding='utf-8-sig',newline='',errors='replace') as f:
        for row in csv.DictReader(f):
            if row.get('event')!='SEND_RESULT' or row.get('status') not in {'ENTER_SENT','OK','SENT'}:
                continue
            try: seq=int(row.get('sequence',''))
            except ValueError: continue
            sends[seq]=row
if sends:
    deliveries={seq:item for seq,item in deliveries.items() if seq in sends}

def percentile(values,p):
    if not values: return None
    a=sorted(values)
    if len(a)==1: return a[0]
    k=(len(a)-1)*p
    lo=math.floor(k); hi=math.ceil(k)
    if lo==hi:return a[lo]
    return a[lo]+(a[hi]-a[lo])*(k-lo)

def fmt_ms(v):
    if v is None:return 'n/a'
    v=float(v)
    if v<1000:return f'{v:.0f} ms'
    if v<60000:return f'{v/1000:.3f} s'
    return f'{v/60000:.2f} min'

def fmt_epoch(ms):
    return datetime.fromtimestamp(ms/1000).astimezone().isoformat(timespec='seconds')

delays=[x['delay'] for x in deliveries.values()]
missing=sorted(set(sends)-set(deliveries))
matched=sorted(set(sends)&set(deliveries))
screen_lines=[]
if screen_path.exists():
    screen_lines=[x for x in screen_path.read_text(encoding='utf-8',errors='replace').splitlines() if x.strip()]
guardian=''
if guardian_path.exists(): guardian=guardian_path.read_text(encoding='utf-8',errors='replace')
batterystats_screen_lines=[]
if batterystats_screen_path.exists():
    batterystats_screen_lines=[x for x in batterystats_screen_path.read_text(encoding='utf-8',errors='replace').splitlines() if x.strip()]
recovery_hits=sum(guardian.count(x) for x in ['gms_recovery_','gms_verified_outage','gms_mcs_'])
lines=[
'===== LUONNOTAR OVERNIGHT DELIVERY SUMMARY =====',
f'Device: {device}',f'Started: {start_local}',f'Planned end: {end_local}','',
f'SEND_RESULT records in window: {len(sends)}',f'Arrival evidence: {len(deliveries)}',f'Sender/arrival matched sequences: {len(matched)}',f'Missing arrival evidence: {len(missing)}',
f'Median delay: {fmt_ms(percentile(delays,.50))}',f'P95 delay: {fmt_ms(percentile(delays,.95))}',f'Maximum delay: {fmt_ms(max(delays) if delays else None)}',f'Delays >10s: {sum(x>=10000 for x in delays)}',f'Delays >60s: {sum(x>=60000 for x in delays)}',f'Delays >10min: {sum(x>=600000 for x in delays)}','',
f'Screen/wake-related logcat lines captured: {len(screen_lines)}',f'Screen/wake-related batterystats lines captured: {len(batterystats_screen_lines)}',f'Guardian GMS/MCS/recovery marker hits: {recovery_hits}',
]
if missing:
    lines += ['', 'Missing sequences:', ' '.join(map(str,missing[:200])) + (' ...' if len(missing)>200 else '')]
if deliveries:
    lines += ['', 'Longest deliveries:']
    for item in sorted(deliveries.values(),key=lambda x:x['delay'],reverse=True)[:30]:
        lines.append(f"#{item['seq']}  {fmt_ms(item['delay']):>10}  sent={fmt_epoch(item['sender'])}  seen={fmt_epoch(item['seen'])}")
lines += ['', 'Interpretation:', '- Delay uses Luonnotar push_test_arrival_observed sender/seen timestamps.', '- Missing means no arrival evidence in the captured log window; log rotation remains a possible cause.', '- Screen/wake lines are evidence only; absence of a line is not proof that the display never woke.', '- Both logcat and batterystats history are retained because OEM logging coverage differs.', '- The observation window intentionally used no periodic device polling or active GMS recovery.', '']
out_path.write_text('\n'.join(lines),encoding='utf-8')
PY
}

finalize() {
  local reason="$1"
  (( FINALIZED == 0 )) || return 0
  FINALIZED=1
  log "Final capture starting: reason=$reason"

  cp -f "$SEND_EVENTS" "$ROOT/30-send-events-end.csv" 2>/dev/null || log "WARNING: could not snapshot sender CSV at end."
  make_send_window || log "WARNING: could not build send-events window."

  if adb_ready; then
    log "Final capture transport: ADB $SERIAL"
  else
    log "WARNING: fixed ADB is unavailable at final capture; read-only :8025 fallback will be attempted where possible."
  fi

  save_shell_end "$ROOT/20-power-end.txt" "dumpsys power"

  # Prefer ADB's logcat command because -T parsing is host-side and reliable. Fall back to
  # a complete dump or the shell rescue channel if needed.
  if adb_ready; then
    if [[ -n "$DEVICE_LOGCAT_START" ]]; then
      adb -s "$SERIAL" logcat -d -v threadtime -T "$DEVICE_LOGCAT_START" >"$ROOT/21-logcat-ring-final.txt" 2>"$ROOT/21-logcat-ring-final.err" || \
        adb -s "$SERIAL" logcat -d -v threadtime >"$ROOT/21-logcat-ring-final.txt" 2>"$ROOT/21-logcat-ring-final.err" || true
    else
      adb -s "$SERIAL" logcat -d -v threadtime >"$ROOT/21-logcat-ring-final.txt" 2>"$ROOT/21-logcat-ring-final.err" || true
    fi
  else
    save_shell_end "$ROOT/21-logcat-ring-final.txt" "logcat -d -v threadtime"
  fi

  save_shell_end "$ROOT/22-guardian-events-final.log" "cat /data/local/tmp/luonnotar-guardian-events.log 2>/dev/null || true"
  save_shell_end "$ROOT/23-guardian-status-end.json" "cat /data/local/tmp/luonnotar-guardian-status.json 2>/dev/null || true"
  save_shell_end "$ROOT/24-deviceidle-end.txt" "dumpsys deviceidle"
  save_shell_end "$ROOT/25-battery-end.txt" "dumpsys battery"
  save_shell_end "$ROOT/26-connectivity-end.txt" "dumpsys connectivity"
  save_shell_end "$ROOT/27-gms-processes-end.txt" "echo '== PID =='; pidof com.google.android.gms; pidof com.google.android.gms.persistent; echo '== PROCESSES =='; dumpsys activity processes com.google.android.gms"
  save_shell_end "$ROOT/28-gms-services-end.txt" "dumpsys activity services com.google.android.gms"
  save_shell_end "$ROOT/29-gms-appops-end.txt" "cmd appops get com.google.android.gms"
  save_shell_end "$ROOT/33-batterystats-history-end.txt" "dumpsys batterystats --history"

  if [[ -f "$ROOT/33-batterystats-history-end.txt" ]]; then
    grep -Ei 'screen|wakefulness|wakeup|wake_lock|interactive' "$ROOT/33-batterystats-history-end.txt" >"$ROOT/43-screen-wake-batterystats.txt" || true
  fi

  if [[ -f "$ROOT/21-logcat-ring-final.txt" ]]; then
    grep -Ei 'DisplayPowerController|PowerManagerService|mWakefulness|wakefulness|Waking up|Going to sleep|screen[_ -]?(on|off)|interactive=' "$ROOT/21-logcat-ring-final.txt" >"$ROOT/40-screen-wake-logcat.txt" || true
    grep -Ei 'GCM-GMS|FirebaseMessaging|MCS|mtalk|BAD_AUTHENTICATION|AuthPII|push_test_arrival_observed|gms_recovery_|gms_verified_outage|gms_mcs_|freezer|freeze' "$ROOT/21-logcat-ring-final.txt" >"$ROOT/41-push-gms-relevant-logcat.txt" || true
  fi

  {
    echo "captured=$(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "8022=$(probe_port 8022)"
    echo "8025=$(probe_port 8025)"
    echo "5555=$(probe_port 5555)"
  } >"$ROOT/42-control-planes-end.txt"

  make_plain_summary || log "WARNING: plain delay summary generation failed."

  ANALYZER="$PROJECT_ROOT/tools/analyze-luonnotar-push-session.py"
  if [[ -f "$ANALYZER" && -f "$ROOT/31-send-events-window.csv" && -s "$ROOT/21-logcat-ring-final.txt" ]]; then
    mkdir -p "$ROOT/analysis"
    if python3 "$ANALYZER" \
      --input "$ROOT" \
      --send-events "$ROOT/31-send-events-window.csv" \
      --output "$ROOT/analysis" \
      --source-name "$DEVICE_NAME" \
      >"$ROOT/32-analysis-console.txt" 2>&1; then
      log "Project analyzer completed."
    else
      log "WARNING: project analyzer failed; see 32-analysis-console.txt."
    fi
  else
    echo "Analyzer skipped: missing analyzer, send window, or final logcat." >"$ROOT/32-analysis-console.txt"
  fi

  cat >"$ROOT/99-completed.txt" <<EOF_DONE
Completed=$(date '+%Y-%m-%dT%H:%M:%S%z')
PlannedEnd=$END_LOCAL
Reason=$reason
Device=$DEVICE_NAME
Serial=$SERIAL
Root=$ROOT
NoPeriodicDevicePolling=true
NoLiveLogcatStream=true
NoActiveGmsRecovery=true
EOF_DONE

  if command -v ditto >/dev/null 2>&1; then
    ditto -c -k --sequesterRsrc --keepParent "$ROOT" "$ZIP_PATH" >/dev/null 2>&1 || true
  elif command -v zip >/dev/null 2>&1; then
    (cd "$(dirname "$ROOT")" && zip -qry "$ZIP_PATH" "$(basename "$ROOT")") || true
  fi

  log "OVERNIGHT TEST COMPLETE"
  log "Folder: $ROOT"
  [[ -f "$ZIP_PATH" ]] && log "ZIP: $ZIP_PATH"
  [[ -f "$ROOT/90-delay-summary.txt" ]] && {
    echo
    cat "$ROOT/90-delay-summary.txt"
  }
}

on_interrupt() {
  echo
  log "Interrupt received; preserving a partial final capture."
  finalize "interrupted"
  exit 130
}
trap on_interrupt INT TERM HUP
trap 'if [[ -n "${CAFFEINATE_PID:-}" ]]; then kill "$CAFFEINATE_PID" >/dev/null 2>&1 || true; fi' EXIT

log "UNATTENDED WINDOW STARTED"
log "Device=$DEVICE_NAME Serial=$SERIAL"
log "Planned end=$END_LOCAL"
log "Sender=$SEND_EVENTS"
log "Output=$ROOT"
log "Do not wake/unlock the target or change Luonnotar/GMS/VPN settings during the run."

while :; do
  now_epoch="$(date +%s)"
  if (( now_epoch >= END_EPOCH )); then
    break
  fi
  remaining=$((END_EPOCH - now_epoch))
  if (( remaining > 300 )); then sleep_for=300; else sleep_for="$remaining"; fi
  printf '[%s] remaining %02d:%02d:%02d\n' "$(date '+%H:%M:%S')" "$((remaining/3600))" "$(((remaining%3600)/60))" "$((remaining%60))" | tee -a "$RUN_LOG"
  sleep "$sleep_for"
done

finalize "planned_end"
