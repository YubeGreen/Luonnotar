package com.yubegreen.luonnotar.privileged

/**
 * Wire protocol used by the shell-resident GMS freezer fast lane.
 *
 * The shell process owns the logcat pipe and performs the first unfreeze before
 * the Kotlin policy engine sees the event.  Structured records are deliberately
 * line based so a sidecar failure can fall back to the legacy Kotlin watcher.
 */
internal sealed interface GmsFreezerFastLaneRecord {
    data class Ready(
        val backend: String,
        val sticky: Boolean,
        val timeout: Boolean
    ) : GmsFreezerFastLaneRecord

    data class Signal(
        val sequence: Long,
        val elapsedCentiseconds: Long,
        val target: String
    ) : GmsFreezerFastLaneRecord

    data class FirstThaw(
        val sequence: Long,
        val target: String,
        val backend: String,
        val exitCode: Int,
        val skipped: Boolean,
        val completedCentiseconds: Long,
        val durationCentiseconds: Long
    ) : GmsFreezerFastLaneRecord

    data class ProbeResult(
        val sequence: Long,
        val state: String,
        val commandCount: Int,
        val acceptedCount: Int,
        val frozenPollCount: Int,
        val verifiedThawCount: Int,
        val blindReassertCount: Int
    ) : GmsFreezerFastLaneRecord

    data class ShieldResult(
        val sequence: Long,
        val episode: Long,
        val state: String,
        val commandCount: Int,
        val acceptedCount: Int,
        val frozenPollCount: Int,
        val verifiedThawCount: Int,
        val blindReassertCount: Int,
        val durationCentiseconds: Long,
        val exhausted: Boolean
    ) : GmsFreezerFastLaneRecord

    data class RawLog(val line: String) : GmsFreezerFastLaneRecord
    data class Diagnostic(val type: String, val detail: String) : GmsFreezerFastLaneRecord
}

internal object GmsFreezerFastLaneProtocol {
    private const val PREFIX = "__LUONNOTAR_FAST_LANE_"
    private const val READY = "${PREFIX}READY__\t"
    private const val SIGNAL = "${PREFIX}SIGNAL__\t"
    private const val FIRST = "${PREFIX}FIRST__\t"
    private const val PROBE = "${PREFIX}PROBE__\t"
    private const val SHIELD = "${PREFIX}SHIELD__\t"
    private const val LOG = "${PREFIX}LOG__\t"
    private const val DIAGNOSTIC = "${PREFIX}DIAG__\t"

    fun parse(line: String): GmsFreezerFastLaneRecord? = when {
        line.startsWith(READY) -> {
            val values = fields(line.removePrefix(READY))
            GmsFreezerFastLaneRecord.Ready(
                backend = values["backend"].orEmpty().ifBlank { "unknown" },
                sticky = values["sticky"] == "1",
                timeout = values["timeout"] == "1"
            )
        }
        line.startsWith(SIGNAL) -> {
            val values = fields(line.removePrefix(SIGNAL))
            GmsFreezerFastLaneRecord.Signal(
                sequence = values["seq"].toLongOrZero(),
                elapsedCentiseconds = values["atCs"].toLongOrZero(),
                target = values["target"].orEmpty().ifBlank { "unknown" }
            )
        }
        line.startsWith(FIRST) -> {
            val values = fields(line.removePrefix(FIRST))
            GmsFreezerFastLaneRecord.FirstThaw(
                sequence = values["seq"].toLongOrZero(),
                target = values["target"].orEmpty().ifBlank { "unknown" },
                backend = values["backend"].orEmpty().ifBlank { "unknown" },
                exitCode = values["rc"]?.toIntOrNull() ?: -1,
                skipped = values["skipped"] == "1",
                completedCentiseconds = values["doneCs"].toLongOrZero(),
                durationCentiseconds = values["durationCs"].toLongOrZero()
            )
        }
        line.startsWith(PROBE) -> {
            val values = fields(line.removePrefix(PROBE))
            GmsFreezerFastLaneRecord.ProbeResult(
                sequence = values["seq"].toLongOrZero(),
                state = values["state"].orEmpty().ifBlank { "unknown" },
                commandCount = values["commands"].toIntOrZero(),
                acceptedCount = values["accepted"].toIntOrZero(),
                frozenPollCount = values["frozenPolls"].toIntOrZero(),
                verifiedThawCount = values["verified"].toIntOrZero(),
                blindReassertCount = values["blind"].toIntOrZero()
            )
        }
        line.startsWith(SHIELD) -> {
            val values = fields(line.removePrefix(SHIELD))
            GmsFreezerFastLaneRecord.ShieldResult(
                sequence = values["seq"].toLongOrZero(),
                episode = values["episode"].toLongOrZero(),
                state = values["state"].orEmpty().ifBlank { "unknown" },
                commandCount = values["commands"].toIntOrZero(),
                acceptedCount = values["accepted"].toIntOrZero(),
                frozenPollCount = values["frozenPolls"].toIntOrZero(),
                verifiedThawCount = values["verified"].toIntOrZero(),
                blindReassertCount = values["blind"].toIntOrZero(),
                durationCentiseconds = values["durationCs"].toLongOrZero(),
                exhausted = values["exhausted"] == "1"
            )
        }
        line.startsWith(LOG) -> GmsFreezerFastLaneRecord.RawLog(line.removePrefix(LOG))
        line.startsWith(DIAGNOSTIC) -> {
            val values = fields(line.removePrefix(DIAGNOSTIC))
            GmsFreezerFastLaneRecord.Diagnostic(
                type = values["type"].orEmpty().ifBlank { "unknown" },
                detail = values["detail"].orEmpty()
            )
        }
        else -> null
    }

    private fun fields(raw: String): Map<String, String> = buildMap {
        raw.split('\t').forEach { field ->
            val separator = field.indexOf('=')
            if (separator > 0) put(field.substring(0, separator), field.substring(separator + 1))
        }
    }

    private fun String?.toLongOrZero(): Long = this?.toLongOrNull() ?: 0L
    private fun String?.toIntOrZero(): Int = this?.toIntOrNull() ?: 0
}

/** Builds the fixed shell program loaded into /system/bin/sh through stdin. */
internal object GmsFreezerFastLaneScript {
    private const val DEFAULT_BASE_ROOT = "/data/local/tmp"
    private val SAFE_BASE_ROOT = Regex("^/[A-Za-z0-9_./-]{1,180}$")

    fun build(
        parentPid: Int,
        stickyUnfreeze: Boolean,
        baseRoot: String = DEFAULT_BASE_ROOT
    ): String {
        require(parentPid > 1) { "invalid parent pid" }
        require(SAFE_BASE_ROOT.matches(baseRoot)) { "invalid fast lane base root" }
        val sticky = if (stickyUnfreeze) 1 else 0
        val baseRootForParent = "$baseRoot/luonnotar-gms-fast-lane-$parentPid"
        return """
            umask 077
            parent_pid=$parentPid
            shell_pid=${'$'}${'$'}
            base_root=${shellQuote(baseRootForParent)}
            base="${'$'}base_root-${'$'}shell_pid"
            logcat_pid_file="${'$'}base/logcat.pid"
            lock_dir="${'$'}base/shield.lock"
            worker_pid_file="${'$'}base/worker.pid"
            lock_owner_file="${'$'}lock_dir/owner"
            episode_file="${'$'}base/episode"
            hard_until_file="${'$'}base/hard_until_cs"
            soft_until_file="${'$'}base/soft_until_cs"
            last_signal_file="${'$'}base/last_signal_cs"
            latest_seq_file="${'$'}base/latest_seq"
            immediate_count_file="${'$'}base/immediate_count"
            sticky_enabled=$sticky
            sticky_arg=""
            [ "${'$'}sticky_enabled" -eq 1 ] && sticky_arg="--sticky"
            backend="cmd_activity"
            has_timeout_command=0
            sequence=0
            last_main_immediate_cs=0
            last_persistent_immediate_cs=0
            stopping=0
            monitor_pid=""
            pipeline_pid=""

            rm -rf "${'$'}base"
            mkdir -p "${'$'}base" || exit 70
            rm -f "${'$'}logcat_pid_file" "${'$'}worker_pid_file"
            rmdir "${'$'}lock_dir" >/dev/null 2>&1 || true

            read_uptime_cs() {
                _sec=0
                _frac=0
                IFS='. ' read -r _sec _frac _rest < /proc/uptime || true
                _frac="${'$'}{_frac}00"
                _frac="${'$'}{_frac%${'$'}{_frac#??}}"
                case "${'$'}_frac" in
                    0?) _frac="${'$'}{_frac#0}" ;;
                    '') _frac=0 ;;
                esac
                NOW_CS=${'$'}((_sec * 100 + _frac))
            }

            read_number() {
                READ_NUMBER=0
                [ -r "${'$'}1" ] && IFS= read -r READ_NUMBER < "${'$'}1"
                case "${'$'}READ_NUMBER" in ''|*[!0-9]*) READ_NUMBER=0 ;; esac
            }

            write_number() {
                printf '%s\n' "${'$'}2" > "${'$'}1.tmp" && mv "${'$'}1.tmp" "${'$'}1"
            }

            if command -v timeout >/dev/null 2>&1; then has_timeout_command=1; fi

            run_limited() {
                if [ "${'$'}has_timeout_command" -eq 1 ]; then
                    timeout 1 "${'$'}@"
                    return ${'$'}?
                fi
                "${'$'}@" &
                _limited_pid=${'$'}!
                _limited_tick=0
                while kill -0 "${'$'}_limited_pid" >/dev/null 2>&1 && \
                    [ "${'$'}_limited_tick" -lt 10 ]; do
                    sleep 0.10
                    _limited_tick=${'$'}((_limited_tick + 1))
                done
                if kill -0 "${'$'}_limited_pid" >/dev/null 2>&1; then
                    kill "${'$'}_limited_pid" >/dev/null 2>&1 || true
                    wait "${'$'}_limited_pid" >/dev/null 2>&1 || true
                    return 124
                fi
                wait "${'$'}_limited_pid"
            }

            unfreeze_process() {
                _target="${'$'}1"
                case "${'$'}_target" in
                    com.google.android.gms|com.google.android.gms.persistent) ;;
                    *) return 64 ;;
                esac
                if [ "${'$'}backend" = "cmd_activity" ]; then
                    run_limited cmd activity unfreeze ${'$'}sticky_arg "${'$'}_target" \
                        >/dev/null 2>&1
                    _rc=${'$'}?
                    if [ "${'$'}_rc" -eq 0 ]; then return 0; fi
                    backend="am"
                fi
                run_limited am unfreeze ${'$'}sticky_arg "${'$'}_target" --user 0 \
                    >/dev/null 2>&1
            }

            unfreeze_observed_gms() {
                _budget="${'$'}{1:-2}"
                case "${'$'}_budget" in ''|*[!0-9]*) _budget=0 ;; esac
                UNFREEZE_COMMAND_COUNT=0
                UNFREEZE_ACCEPTED_COUNT=0
                _found_target=0
                for _target in com.google.android.gms com.google.android.gms.persistent; do
                    [ "${'$'}UNFREEZE_COMMAND_COUNT" -lt "${'$'}_budget" ] || break
                    _target_pids="${'$'}(pidof "${'$'}_target" 2>/dev/null || true)"
                    [ -n "${'$'}_target_pids" ] || continue
                    _found_target=1
                    unfreeze_process "${'$'}_target"
                    _target_rc=${'$'}?
                    UNFREEZE_COMMAND_COUNT=${'$'}((UNFREEZE_COMMAND_COUNT + 1))
                    [ "${'$'}_target_rc" -eq 0 ] && \
                        UNFREEZE_ACCEPTED_COUNT=${'$'}((UNFREEZE_ACCEPTED_COUNT + 1))
                done
                [ "${'$'}_found_target" -eq 0 ] && return 3
                [ "${'$'}UNFREEZE_ACCEPTED_COUNT" -gt 0 ] && return 0
                return 1
            }

            probe_pid_frozen() {
                _pid="${'$'}1"
                _visible=0
                [ -r "/proc/${'$'}_pid/cgroup" ] || return 2
                while IFS=: read -r _hierarchy _controllers _relative; do
                    case "${'$'}_relative" in /*) ;; *) continue ;; esac
                    case "${'$'}_relative" in *[!A-Za-z0-9_./:@-]*) continue ;; esac
                    if [ -z "${'$'}_controllers" ]; then
                        _freeze="/sys/fs/cgroup${'$'}_relative/cgroup.freeze"
                        if [ -r "${'$'}_freeze" ]; then
                            _visible=1
                            IFS= read -r _value < "${'$'}_freeze"
                            [ "${'$'}_value" = "1" ] && return 0
                        fi
                        _events="/sys/fs/cgroup${'$'}_relative/cgroup.events"
                        if [ -r "${'$'}_events" ]; then
                            _visible=1
                            while IFS=' ' read -r _key _value; do
                                [ "${'$'}_key" = "frozen" ] && [ "${'$'}_value" = "1" ] && return 0
                            done < "${'$'}_events"
                        fi
                    fi
                    case ",${'$'}_controllers," in
                        *,freezer,*)
                            _state="/sys/fs/cgroup/freezer${'$'}_relative/freezer.state"
                            if [ -r "${'$'}_state" ]; then
                                _visible=1
                                IFS= read -r _value < "${'$'}_state"
                                case "${'$'}_value" in FROZEN|FREEZING) return 0 ;; esac
                            fi
                            ;;
                    esac
                done < "/proc/${'$'}_pid/cgroup"
                [ "${'$'}_visible" -eq 1 ] && return 1
                return 2
            }

            probe_gms() {
                _found=0
                _visible=0
                _unobservable=0
                for _name in com.google.android.gms com.google.android.gms.persistent; do
                    _pids="${'$'}(pidof "${'$'}_name" 2>/dev/null || true)"
                    for _pid in ${'$'}_pids; do
                        case "${'$'}_pid" in ''|*[!0-9]*) continue ;; esac
                        _found=1
                        probe_pid_frozen "${'$'}_pid"
                        _state=${'$'}?
                        [ "${'$'}_state" -eq 0 ] && return 0
                        [ "${'$'}_state" -eq 1 ] && _visible=1
                        [ "${'$'}_state" -eq 2 ] && _unobservable=1
                    done
                done
                [ "${'$'}_found" -eq 0 ] && return 3
                [ "${'$'}_unobservable" -eq 1 ] && return 2
                [ "${'$'}_visible" -eq 1 ] && return 1
                return 2
            }

            update_lease() {
                _seq="${'$'}1"
                _now="${'$'}2"
                read_number "${'$'}hard_until_file"; _hard="${'$'}READ_NUMBER"
                read_number "${'$'}soft_until_file"; _previous_soft="${'$'}READ_NUMBER"
                if [ "${'$'}_hard" -le "${'$'}_now" ] || \
                   [ "${'$'}_previous_soft" -lt "${'$'}_now" ]; then
                    _episode="${'$'}_seq"
                    _hard=${'$'}((_now + 12000))
                    write_number "${'$'}episode_file" "${'$'}_episode"
                    write_number "${'$'}hard_until_file" "${'$'}_hard"
                    write_number "${'$'}immediate_count_file" 0
                fi
                _soft=${'$'}((_now + 4500))
                [ "${'$'}_soft" -gt "${'$'}_hard" ] && _soft="${'$'}_hard"
                write_number "${'$'}soft_until_file" "${'$'}_soft"
                write_number "${'$'}last_signal_file" "${'$'}_now"
                write_number "${'$'}latest_seq_file" "${'$'}_seq"
            }

            shield_worker() {
                _worker_token="${'$'}1"
                _commands=0
                _accepted=0
                _frozen_polls=0
                _verified=0
                _blind=0
                _blind_stage=0
                _last_seen_seq=0
                _reported_seq=0
                _reported_state=""
                _state_name="unknown"
                _exhausted=0
                read_uptime_cs; _worker_started="${'$'}NOW_CS"
                while [ "${'$'}stopping" -eq 0 ]; do
                    read_uptime_cs; _now="${'$'}NOW_CS"
                    read_number "${'$'}soft_until_file"; _soft="${'$'}READ_NUMBER"
                    read_number "${'$'}hard_until_file"; _hard="${'$'}READ_NUMBER"
                    read_number "${'$'}latest_seq_file"; _seq="${'$'}READ_NUMBER"
                    read_number "${'$'}episode_file"; _episode="${'$'}READ_NUMBER"
                    read_number "${'$'}last_signal_file"; _last_signal="${'$'}READ_NUMBER"
                    if [ "${'$'}_now" -gt "${'$'}_soft" ] || [ "${'$'}_now" -gt "${'$'}_hard" ]; then
                        _owner=""
                        [ -r "${'$'}lock_owner_file" ] && IFS= read -r _owner < "${'$'}lock_owner_file"
                        if [ "${'$'}_owner" = "${'$'}_worker_token" ]; then
                            rm -f "${'$'}lock_owner_file"
                            rmdir "${'$'}lock_dir" >/dev/null 2>&1 || true
                        fi
                        read_uptime_cs; _now="${'$'}NOW_CS"
                        read_number "${'$'}soft_until_file"; _soft="${'$'}READ_NUMBER"
                        if [ "${'$'}_now" -le "${'$'}_soft" ] && mkdir "${'$'}lock_dir" 2>/dev/null; then
                            printf '%s\n' "${'$'}_worker_token" > "${'$'}lock_owner_file"
                            continue
                        fi
                        break
                    fi
                    if [ "${'$'}_seq" -ne "${'$'}_last_seen_seq" ]; then
                        _last_seen_seq="${'$'}_seq"
                        _blind_stage=0
                    fi

                    probe_gms
                    _probe=${'$'}?
                    case "${'$'}_probe" in
                        0)
                            _state_name="frozen"
                            _frozen_polls=${'$'}((_frozen_polls + 1))
                            if [ "${'$'}_commands" -lt 48 ]; then
                                _budget=${'$'}((48 - _commands))
                                unfreeze_observed_gms "${'$'}_budget"
                                _rc=${'$'}?
                                _commands=${'$'}((_commands + UNFREEZE_COMMAND_COUNT))
                                _accepted=${'$'}((_accepted + UNFREEZE_ACCEPTED_COUNT))
                                sleep 0.08
                                probe_gms
                                _verify_probe=${'$'}?
                                if [ "${'$'}_verify_probe" -eq 1 ]; then
                                    _verified=${'$'}((_verified + 1))
                                    _state_name="thawed"
                                fi
                            else
                                _exhausted=1
                            fi
                            ;;
                        1) _state_name="thawed" ;;
                        2)
                            _state_name="unobservable"
                            _age=${'$'}((_now - _last_signal))
                            _blind_due=0
                            case "${'$'}_blind_stage" in
                                0) _blind_due=1 ;;
                                1) [ "${'$'}_age" -ge 100 ] && _blind_due=1 ;;
                                2) [ "${'$'}_age" -ge 300 ] && _blind_due=1 ;;
                                3) [ "${'$'}_age" -ge 700 ] && _blind_due=1 ;;
                            esac
                            if [ "${'$'}_blind_due" -eq 1 ] && [ "${'$'}_blind_stage" -lt 4 ]; then
                                if [ "${'$'}_commands" -lt 48 ]; then
                                    _budget=${'$'}((48 - _commands))
                                    unfreeze_observed_gms "${'$'}_budget"
                                    _rc=${'$'}?
                                    _commands=${'$'}((_commands + UNFREEZE_COMMAND_COUNT))
                                    _accepted=${'$'}((_accepted + UNFREEZE_ACCEPTED_COUNT))
                                    _blind=${'$'}((_blind + UNFREEZE_COMMAND_COUNT))
                                else
                                    _exhausted=1
                                fi
                                _blind_stage=${'$'}((_blind_stage + 1))
                            fi
                            ;;
                        3) _state_name="absent" ;;
                        *) _state_name="unknown" ;;
                    esac

                    if [ "${'$'}_reported_seq" -ne "${'$'}_seq" ] || \
                       [ "${'$'}_reported_state" != "${'$'}_state_name" ]; then
                        printf '__LUONNOTAR_FAST_LANE_PROBE__\tseq=%s\tstate=%s\tcommands=%s\taccepted=%s\tfrozenPolls=%s\tverified=%s\tblind=%s\n' \
                            "${'$'}_seq" "${'$'}_state_name" "${'$'}_commands" "${'$'}_accepted" \
                            "${'$'}_frozen_polls" "${'$'}_verified" "${'$'}_blind"
                        _reported_seq="${'$'}_seq"
                        _reported_state="${'$'}_state_name"
                    fi

                    _age=${'$'}((_now - _last_signal))
                    if [ "${'$'}_probe" -eq 2 ] || [ "${'$'}_probe" -eq 3 ]; then
                        sleep 1
                    elif [ "${'$'}_age" -le 300 ]; then
                        sleep 0.10
                    elif [ "${'$'}_age" -le 1000 ]; then
                        sleep 0.25
                    else
                        sleep 1
                    fi
                done
                read_uptime_cs; _ended="${'$'}NOW_CS"
                read_number "${'$'}latest_seq_file"; _seq="${'$'}READ_NUMBER"
                read_number "${'$'}episode_file"; _episode="${'$'}READ_NUMBER"
                printf '__LUONNOTAR_FAST_LANE_SHIELD__\tseq=%s\tepisode=%s\tstate=%s\tcommands=%s\taccepted=%s\tfrozenPolls=%s\tverified=%s\tblind=%s\tdurationCs=%s\texhausted=%s\n' \
                    "${'$'}_seq" "${'$'}_episode" "${'$'}_state_name" "${'$'}_commands" \
                    "${'$'}_accepted" "${'$'}_frozen_polls" "${'$'}_verified" "${'$'}_blind" \
                    "${'$'}((_ended - _worker_started))" "${'$'}_exhausted"
                _pid_token=""
                _pid_value=""
                [ -r "${'$'}worker_pid_file" ] && IFS=' ' read -r _pid_token _pid_value < "${'$'}worker_pid_file"
                [ "${'$'}_pid_token" = "${'$'}_worker_token" ] && rm -f "${'$'}worker_pid_file"
                _owner=""
                [ -r "${'$'}lock_owner_file" ] && IFS= read -r _owner < "${'$'}lock_owner_file"
                if [ "${'$'}_owner" = "${'$'}_worker_token" ]; then
                    rm -f "${'$'}lock_owner_file"
                    rmdir "${'$'}lock_dir" >/dev/null 2>&1 || true
                fi
            }

            ensure_worker() {
                if mkdir "${'$'}lock_dir" 2>/dev/null; then
                    read_uptime_cs
                    _worker_token="${'$'}sequence-${'$'}NOW_CS"
                    printf '%s\n' "${'$'}_worker_token" > "${'$'}lock_owner_file"
                    shield_worker "${'$'}_worker_token" &
                    _worker_pid=${'$'}!
                    printf '%s %s\n' "${'$'}_worker_token" "${'$'}_worker_pid" > "${'$'}worker_pid_file"
                fi
            }

            cleanup() {
                stopping=1
                if [ -r "${'$'}logcat_pid_file" ]; then
                    IFS= read -r _logcat_pid < "${'$'}logcat_pid_file"
                    case "${'$'}_logcat_pid" in
                        ''|*[!0-9]*) ;;
                        *) kill "${'$'}_logcat_pid" >/dev/null 2>&1 || true ;;
                    esac
                fi
                [ -n "${'$'}pipeline_pid" ] && kill "${'$'}pipeline_pid" >/dev/null 2>&1 || true
                if [ -r "${'$'}worker_pid_file" ]; then
                    IFS=' ' read -r _worker_token _worker_pid < "${'$'}worker_pid_file"
                    case "${'$'}_worker_pid" in ''|*[!0-9]*) ;; *) kill "${'$'}_worker_pid" >/dev/null 2>&1 || true ;; esac
                fi
                [ -n "${'$'}monitor_pid" ] && kill "${'$'}monitor_pid" >/dev/null 2>&1 || true
                rm -rf "${'$'}base"
            }

            (
                while kill -0 "${'$'}parent_pid" >/dev/null 2>&1; do sleep 2; done
                kill "${'$'}shell_pid" >/dev/null 2>&1 || true
            ) &
            monitor_pid=${'$'}!
            trap cleanup EXIT HUP INT TERM

            if ! command -v logcat >/dev/null 2>&1; then
                printf '__LUONNOTAR_FAST_LANE_DIAG__\ttype=logcat_missing\tdetail=command_unavailable\n'
                exit 71
            fi

            consume_logcat() {
                while IFS= read -r line; do
                case "${'$'}line" in
                    *am_app_frozen*com.google.android.gms.persistent*)
                        _signal_target="com.google.android.gms.persistent"
                        ;;
                    *am_app_frozen*com.google.android.gms*)
                        _signal_target="com.google.android.gms"
                        ;;
                    *)
                        _signal_target=""
                        ;;
                esac
                if [ -n "${'$'}_signal_target" ]; then
                    sequence=${'$'}((sequence + 1))
                    read_uptime_cs; _now="${'$'}NOW_CS"
                    update_lease "${'$'}sequence" "${'$'}_now"
                    printf '__LUONNOTAR_FAST_LANE_SIGNAL__\tseq=%s\tatCs=%s\ttarget=%s\n' \
                        "${'$'}sequence" "${'$'}_now" "${'$'}_signal_target"
                    read_number "${'$'}immediate_count_file"; _immediate_count="${'$'}READ_NUMBER"
                    _skipped=0
                    _rc=75
                    case "${'$'}_signal_target" in
                        com.google.android.gms.persistent)
                            _last_target_immediate_cs="${'$'}last_persistent_immediate_cs"
                            ;;
                        *)
                            _last_target_immediate_cs="${'$'}last_main_immediate_cs"
                            ;;
                    esac
                    if [ "${'$'}_immediate_count" -lt 24 ] && \
                       { [ "${'$'}_last_target_immediate_cs" -eq 0 ] || \
                         [ ${'$'}((_now - _last_target_immediate_cs)) -ge 10 ]; }; then
                        unfreeze_process "${'$'}_signal_target"
                        _rc=${'$'}?
                        case "${'$'}_signal_target" in
                            com.google.android.gms.persistent)
                                last_persistent_immediate_cs="${'$'}_now"
                                ;;
                            *)
                                last_main_immediate_cs="${'$'}_now"
                                ;;
                        esac
                        _immediate_count=${'$'}((_immediate_count + 1))
                        write_number "${'$'}immediate_count_file" "${'$'}_immediate_count"
                    else
                        _skipped=1
                    fi
                    read_uptime_cs; _done="${'$'}NOW_CS"
                    _duration=${'$'}((_done - _now))
                    printf '__LUONNOTAR_FAST_LANE_FIRST__\tseq=%s\ttarget=%s\tbackend=%s\trc=%s\tskipped=%s\tdoneCs=%s\tdurationCs=%s\n' \
                        "${'$'}sequence" "${'$'}_signal_target" "${'$'}backend" "${'$'}_rc" "${'$'}_skipped" \
                        "${'$'}_done" "${'$'}_duration"
                    ensure_worker
                fi
                    printf '__LUONNOTAR_FAST_LANE_LOG__\t%s\n' "${'$'}line"
                done
            }

            printf '__LUONNOTAR_FAST_LANE_READY__\tbackend=%s\tsticky=%s\ttimeout=%s\ttransport=pipe\n' \
                "${'$'}backend" "${'$'}sticky_enabled" 1

            /system/bin/sh -c '
                _pid_file="${'$'}1"
                shift
                printf "%s\n" "${'$'}${'$'}" > "${'$'}_pid_file" || exit 73
                exec "${'$'}@"
            ' luonnotar-logcat "${'$'}logcat_pid_file" \
                logcat -b events -b system -b main -v brief -T 1 \
                'am_app_frozen:I' \
                'BroadcastQueue:W' \
                'BroadcastQueueModernImpl:W' \
                'BroadcastQueueInjector:W' \
                'PowerManagerServiceImpl:I' \
                'PowerManagerService:I' \
                'GCM:W' \
                'AuthPII:E' \
                'Linux:D' \
                '*:S' 2>&1 | consume_logcat &
            pipeline_pid=${'$'}!
            wait "${'$'}pipeline_pid"
            _pipeline_rc=${'$'}?
            pipeline_pid=""

            printf '__LUONNOTAR_FAST_LANE_DIAG__\ttype=logcat_eof\tdetail=watcher_ended_rc_%s\n' \
                "${'$'}_pipeline_rc"
            exit 72
        """.trimIndent() + "\n"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
