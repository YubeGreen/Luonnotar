#!/system/bin/sh
# Luonnotar 2.0.0 external shell guardian.
# Runs outside the APK UID. It never force-stops GMS or target applications.

BASE="${LUONNOTAR_GUARDIAN_HOME:-/data/local/tmp/luonnotar2}"
PID_FILE="$BASE/guardian.pid"
STATUS_FILE="$BASE/status.json"
LOG_FILE="$BASE/guardian.log"
STATE_DIR="$BASE/state"
EVENT_TRIGGER_FILE="$BASE/freezer-event.trigger"
POLL_SECONDS="${LUONNOTAR_POLL_SECONDS:-15}"
REASSERT_SECONDS="${LUONNOTAR_REASSERT_SECONDS:-60}"
TUNE_SECONDS="${LUONNOTAR_TUNE_SECONDS:-900}"

validate_seconds() {
    value="$1"
    minimum="$2"
    maximum="$3"
    case "$value" in ''|*[!0-9]*) return 1 ;; esac
    [ "$value" -ge "$minimum" ] && [ "$value" -le "$maximum" ]
}
validate_seconds "$POLL_SECONDS" 5 300 || { echo "invalid poll seconds: $POLL_SECONDS" >&2; exit 64; }
validate_seconds "$REASSERT_SECONDS" 15 1800 || { echo "invalid reassert seconds: $REASSERT_SECONDS" >&2; exit 64; }
validate_seconds "$TUNE_SECONDS" 60 86400 || { echo "invalid tune seconds: $TUNE_SECONDS" >&2; exit 64; }

PROCESS_TARGETS="
com.google.android.gms
com.google.android.gms.persistent
com.whatsapp
com.whatsapp:account_switching
com.whatsapp.w4b
com.tailscale.ipn
ch.protonvpn.android
"

PACKAGE_TARGETS="
com.google.android.gms
com.whatsapp
com.whatsapp.w4b
com.tailscale.ipn
ch.protonvpn.android
"

mkdir -p "$BASE" "$STATE_DIR" 2>/dev/null

now_epoch() { date +%s 2>/dev/null || echo 0; }
now_elapsed() { cut -d. -f1 /proc/uptime 2>/dev/null || now_epoch; }

rotate_log() {
    [ -f "$LOG_FILE" ] || return 0
    size=$(wc -c < "$LOG_FILE" 2>/dev/null || echo 0)
    [ "$size" -lt 1048576 ] && return 0
    mv "$LOG_FILE" "$LOG_FILE.1" 2>/dev/null
}

log() {
    rotate_log
    printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null)" "$*" >> "$LOG_FILE"
}

safe_key() {
    printf '%s' "$1" | tr '.:' '__' | tr -cd 'A-Za-z0-9_-'
}

is_alive() {
    [ -n "$1" ] && kill -0 "$1" 2>/dev/null
}

is_guardian_pid() {
    guardian_pid="$1"
    is_alive "$guardian_pid" || return 1
    [ -r "/proc/$guardian_pid/cmdline" ] || return 1
    cmdline=$(tr '\000' ' ' < "/proc/$guardian_pid/cmdline" 2>/dev/null)
    case "$cmdline" in
        *luonnotar-guardian-v2.sh*daemon*) return 0 ;;
        *) return 1 ;;
    esac
}

read_pid_file() {
    [ -r "$PID_FILE" ] && head -n 1 "$PID_FILE" 2>/dev/null
}

sticky_supported=0
hibernation_supported=0
cycle_count=0
action_count=0
error_count=0
process_count=0
last_cycle=0
last_tune=0
identity=""
watcher_pid=""
event_trigger_count=0

check_capabilities() {
    identity=$(id 2>/dev/null | tr '\n\r\"' '   ' | cut -c1-240)
    if am help 2>/dev/null | grep -q 'unfreeze.*--sticky\|unfreeze \[--sticky\]'; then
        sticky_supported=1
    fi
    if cmd app_hibernation help 2>/dev/null | grep -q 'set-state'; then
        hibernation_supported=1
    fi
    log "capabilities identity=$identity sticky=$sticky_supported hibernation=$hibernation_supported"
}

package_installed() {
    pm path "$1" >/dev/null 2>&1
}

tune_package() {
    pkg="$1"
    package_installed "$pkg" || return 0
    ok=0
    total=0
    for command in \
        "am set-inactive --user 0 $pkg false" \
        "am set-standby-bucket --user 0 $pkg active" \
        "dumpsys deviceidle whitelist +$pkg" \
        "cmd appops set --user 0 $pkg RUN_IN_BACKGROUND allow" \
        "cmd appops set --user 0 $pkg RUN_ANY_IN_BACKGROUND allow" \
        "cmd appops set --user 0 $pkg WAKE_LOCK allow" \
        "cmd appops set --user 0 $pkg START_FOREGROUND allow"
    do
        total=$((total + 1))
        if sh -c "$command" >/dev/null 2>&1; then ok=$((ok + 1)); else error_count=$((error_count + 1)); fi
    done
    if [ "$hibernation_supported" -eq 1 ]; then
        total=$((total + 1))
        if cmd app_hibernation set-state --user 0 "$pkg" false >/dev/null 2>&1; then
            ok=$((ok + 1))
        else
            error_count=$((error_count + 1))
        fi
    fi
    uid=$(cmd package list packages -U --user 0 "$pkg" 2>/dev/null |
        grep "^package:$pkg " | sed -n 's/.*uid:\([0-9][0-9]*\).*/\1/p' | head -n 1)
    if [ -n "$uid" ]; then
        total=$((total + 1))
        if cmd netpolicy add restrict-background-whitelist "$uid" >/dev/null 2>&1; then
            ok=$((ok + 1))
        else
            # Already present is harmless; do not count it as a guardian failure.
            cmd netpolicy list restrict-background-whitelist 2>/dev/null | grep -q "$uid" && ok=$((ok + 1))
        fi
    fi
    action_count=$((action_count + total))
    log "package_tuned pkg=$pkg ok=$ok/$total"
}

tune_all() {
    for pkg in $PACKAGE_TARGETS; do tune_package "$pkg"; done
    last_tune=$(now_elapsed)
}

pid_for_name() {
    pidof "$1" 2>/dev/null | tr ' ' '\n' | sed -n '1p'
}

freeze_evidence() {
    pid="$1"
    [ -r "/proc/$pid/cgroup" ] || { printf 'unreadable'; return; }
    while IFS=: read -r hierarchy controllers relative; do
        case "$relative" in
            /*) ;;
            *) continue ;;
        esac
        case "$relative" in *[!A-Za-z0-9_./:@-]*) continue ;; esac
        if [ -z "$controllers" ]; then
            if [ -r "/sys/fs/cgroup$relative/cgroup.freeze" ]; then
                value=$(cat "/sys/fs/cgroup$relative/cgroup.freeze" 2>/dev/null)
                printf 'cgroup2:%s' "$value"
                return
            fi
            if [ -r "/sys/fs/cgroup$relative/cgroup.events" ]; then
                value=$(grep '^frozen ' "/sys/fs/cgroup$relative/cgroup.events" 2>/dev/null | head -n 1 | tr ' ' '=')
                printf 'cgroup2events:%s' "${value:-unknown}"
                return
            fi
        fi
        case ",$controllers," in
            *,freezer,*)
                if [ -r "/sys/fs/cgroup/freezer$relative/freezer.state" ]; then
                    value=$(cat "/sys/fs/cgroup/freezer$relative/freezer.state" 2>/dev/null)
                    printf 'cgroup1:%s' "$value"
                    return
                fi
                ;;
        esac
    done < "/proc/$pid/cgroup"
    printf 'not_visible'
}

unfreeze_process() {
    name="$1"
    pid="$2"
    if [ "$sticky_supported" -eq 1 ]; then
        output=$(am unfreeze --sticky "$name" --user 0 2>&1)
        rc=$?
    else
        output=$(am unfreeze "$name" --user 0 2>&1)
        rc=$?
    fi
    if [ "$rc" -ne 0 ]; then
        if [ "$sticky_supported" -eq 1 ]; then
            output=$(am unfreeze --sticky "$pid" 2>&1)
            rc=$?
        else
            output=$(am unfreeze "$pid" 2>&1)
            rc=$?
        fi
    fi
    action_count=$((action_count + 1))
    if [ "$rc" -eq 0 ]; then
        log "unfreeze_applied name=$name pid=$pid sticky=$sticky_supported result=$(printf '%s' "$output" | tr '\n\r' '  ' | cut -c1-200)"
    else
        error_count=$((error_count + 1))
        log "unfreeze_failed name=$name pid=$pid sticky=$sticky_supported result=$(printf '%s' "$output" | tr '\n\r' '  ' | cut -c1-200)"
    fi
    return "$rc"
}

observe_process() {
    name="$1"
    pid=$(pid_for_name "$name")
    [ -n "$pid" ] || return 0
    process_count=$((process_count + 1))
    key=$(safe_key "$name")
    state="$STATE_DIR/$key"
    previous_pid=""
    last_action=0
    if [ -r "$state" ]; then
        read -r previous_pid last_action < "$state"
    fi
    case "$previous_pid" in ''|*[!0-9]*) previous_pid="" ;; esac
    case "$last_action" in ''|*[!0-9]*) last_action=0 ;; esac
    now=$(now_elapsed)
    evidence=$(freeze_evidence "$pid")
    due=0
    [ "$previous_pid" != "$pid" ] && due=1
    [ "$last_action" -le 0 ] 2>/dev/null && due=1
    delta=$((now - last_action))
    [ "$delta" -ge "$REASSERT_SECONDS" ] && due=1
    case "$evidence" in *':1'|*FROZEN*|*'frozen=1'*) due=1 ;; esac
    if [ "$due" -eq 1 ]; then
        if unfreeze_process "$name" "$pid"; then
            last_action="$now"
        fi
    fi
    printf '%s %s\n' "$pid" "$last_action" > "$state"
    log "process_observed name=$name pid=$pid evidence=$evidence acted=$due"
}

start_event_watcher() {
    rm -f "$EVENT_TRIGGER_FILE"
    (
        logcat -b events -v brief 2>/dev/null | while IFS= read -r line; do
            case "$line" in
                *am_app_frozen*) ;;
                *) continue ;;
            esac
            matched=""
            for target in $PROCESS_TARGETS; do
                case "$line" in *"$target"*) matched="$target"; break ;; esac
            done
            [ -n "$matched" ] || continue
            printf '%s\n' "$line" > "$EVENT_TRIGGER_FILE"
            compact=$(printf '%s' "$line" | tr '\n\r' '  ' | cut -c1-260)
            log "freezer_event_observed target=$matched line=$compact"
        done
    ) &
    watcher_pid=$!
    log "event_watcher_started pid=$watcher_pid"
}

stop_event_watcher() {
    [ -n "$watcher_pid" ] && kill "$watcher_pid" 2>/dev/null
    watcher_pid=""
    rm -f "$EVENT_TRIGGER_FILE"
}

wait_for_next_cycle() {
    waited=0
    while [ "$waited" -lt "$POLL_SECONDS" ]; do
        if [ -s "$EVENT_TRIGGER_FILE" ]; then
            event_trigger_count=$((event_trigger_count + 1))
            rm -f "$EVENT_TRIGGER_FILE"
            return 0
        fi
        sleep 1 || return 0
        waited=$((waited + 1))
    done
}

write_status() {
    tmp="$STATUS_FILE.tmp"
    cat > "$tmp" <<JSON
{"schema":1,"engine":"LuonnotarShellGuardian","running":true,"pid":$$,"uid":$(id -u 2>/dev/null || echo 2000),"identity":"$identity","stickySupported":$sticky_supported,"hibernationSupported":$hibernation_supported,"eventWatcherAlive":$(is_alive "$watcher_pid" && echo true || echo false),"eventTriggerCount":$event_trigger_count,"cycleCount":$cycle_count,"actionCount":$action_count,"errorCount":$error_count,"processCount":$process_count,"lastCycleElapsed":$last_cycle,"lastTuneElapsed":$last_tune,"pollSeconds":$POLL_SECONDS,"reassertSeconds":$REASSERT_SECONDS}
JSON
    mv "$tmp" "$STATUS_FILE"
}

run_cycle() {
    process_count=0
    now=$(now_elapsed)
    for name in $PROCESS_TARGETS; do observe_process "$name"; done
    if [ "$last_tune" -le 0 ] || [ $((now - last_tune)) -ge "$TUNE_SECONDS" ]; then tune_all; fi
    cycle_count=$((cycle_count + 1))
    last_cycle="$now"
    write_status
}

daemon() {
    old=$(read_pid_file)
    if is_guardian_pid "$old" && [ "$old" != "$$" ]; then
        echo "already running pid=$old" >&2
        exit 2
    fi
    echo $$ > "$PID_FILE"
    trap '' HUP
    cleanup_daemon() {
        trap - INT TERM EXIT
        stop_event_watcher
        rm -f "$PID_FILE"
        log "engine_stopped signal"
    }
    trap cleanup_daemon INT TERM EXIT
    check_capabilities
    start_event_watcher
    log "engine_started pid=$$ poll=${POLL_SECONDS}s reassert=${REASSERT_SECONDS}s"
    while :; do
        run_cycle
        is_alive "$watcher_pid" || start_event_watcher
        wait_for_next_cycle
    done
}

start_engine() {
    old=$(read_pid_file)
    if is_guardian_pid "$old"; then
        echo "Luonnotar shell guardian already running: $old"
        exit 0
    fi
    rm -f "$PID_FILE"
    if command -v nohup >/dev/null 2>&1; then
        nohup "$0" daemon </dev/null >>"$LOG_FILE" 2>&1 &
    else
        "$0" daemon </dev/null >>"$LOG_FILE" 2>&1 &
    fi
    child=$!
    sleep 1
    running=$(read_pid_file)
    if is_guardian_pid "$running"; then
        echo "Luonnotar shell guardian started: $running"
    else
        echo "start failed; child=$child" >&2
        exit 1
    fi
}

stop_engine() {
    old=$(read_pid_file)
    if is_guardian_pid "$old"; then
        kill "$old" 2>/dev/null
        sleep 1
    fi
    rm -f "$PID_FILE"
    echo "Luonnotar shell guardian stopped"
}

status_engine() {
    old=$(read_pid_file)
    if is_guardian_pid "$old"; then
        [ -r "$STATUS_FILE" ] && cat "$STATUS_FILE" || echo "{\"running\":true,\"pid\":$old}"
    else
        echo "{\"running\":false}"
        exit 1
    fi
}

case "${1:-status}" in
    daemon) daemon ;;
    start) start_engine ;;
    stop) stop_engine ;;
    restart) stop_engine; start_engine ;;
    cycle) check_capabilities; run_cycle; cat "$STATUS_FILE" ;;
    status) status_engine ;;
    log) tail -n "${2:-120}" "$LOG_FILE" 2>/dev/null ;;
    *) echo "usage: $0 {start|stop|restart|cycle|status|log|daemon}" >&2; exit 64 ;;
esac
