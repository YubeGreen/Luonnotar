package com.yubegreen.luonnotar.privileged

/**
 * Direct cgroup watcher for ROMs that freeze protected push processes outside
 * AOSP's CachedAppOptimizer bookkeeping.
 *
 * r259 keeps the r258 single-owner atomic group, but turns GMS recovery into
 * one sustained defense episode. A transient cgroup thaw is not reported as
 * success: both core processes must remain physically thawed for a stability
 * window, while reconnect is pulsed at most once per process generation.
 */
internal sealed interface GmsVendorFreezeBridgeRecord {
    data class Ready(
        val timeout: Boolean,
        val sticky: Boolean,
        val strategy: String,
        val shellPid: Int,
        val parentStartTimeTicks: String,
        val shellStartTimeTicks: String,
        val heartbeatPath: String,
        val ownerPath: String
    ) : GmsVendorFreezeBridgeRecord

    data class Heartbeat(
        val elapsedCentiseconds: Long,
        val mainPid: Int,
        val mainState: String,
        val persistentPid: Int,
        val persistentState: String,
        val whatsappPid: Int,
        val whatsappState: String,
        val signalPid: Int,
        val signalState: String
    ) : GmsVendorFreezeBridgeRecord

    data class Frozen(
        val sequence: Long,
        val target: String,
        val pid: Int,
        val cgroupPath: String,
        val consecutive: Int
    ) : GmsVendorFreezeBridgeRecord

    data class Recovery(
        val sequence: Long,
        val target: String,
        val pid: Int,
        val peerPid: Int,
        val group: Boolean,
        val mode: String,
        val plainExitCode: Int,
        val freezeExitCode: Int,
        val releaseExitCode: Int,
        val stickyExitCode: Int,
        val verified: Boolean,
        val adoptObserved: Boolean,
        val durationCentiseconds: Long,
        val consecutive: Int,
        val commandCount: Int,
        val detail: String
    ) : GmsVendorFreezeBridgeRecord

    data class Defense(
        val sequence: Long,
        val phase: String,
        val elapsedCentiseconds: Long,
        val stableCentiseconds: Long,
        val refreezes: Int,
        val attempts: Int,
        val commandCount: Int,
        val mainPid: Int,
        val persistentPid: Int,
        val detail: String
    ) : GmsVendorFreezeBridgeRecord

    data class Shield(
        val phase: String,
        val generation: Long,
        val atCentiseconds: Long,
        val untilCentiseconds: Long,
        val latencyCentiseconds: Long,
        val commandCount: Int,
        val mainPid: Int,
        val persistentPid: Int,
        val detail: String
    ) : GmsVendorFreezeBridgeRecord

    data class VendorLock(
        val sequence: Long,
        val target: String,
        val pid: Int,
        val failures: Int,
        val cooldownCentiseconds: Long
    ) : GmsVendorFreezeBridgeRecord

    data class Diagnostic(val type: String, val detail: String) : GmsVendorFreezeBridgeRecord
}

internal object GmsVendorFreezeBridgeProtocol {
    private const val PREFIX = "__LUONNOTAR_VENDOR_BRIDGE_"
    private const val READY = "${PREFIX}READY__\t"
    private const val HEARTBEAT = "${PREFIX}HEARTBEAT__\t"
    private const val FROZEN = "${PREFIX}FROZEN__\t"
    private const val RECOVERY = "${PREFIX}RECOVERY__\t"
    private const val DEFENSE = "${PREFIX}DEFENSE__\t"
    private const val SHIELD = "${PREFIX}SHIELD__\t"
    private const val LOCK = "${PREFIX}LOCK__\t"
    private const val DIAGNOSTIC = "${PREFIX}DIAG__\t"

    fun parse(line: String): GmsVendorFreezeBridgeRecord? = when {
        line.startsWith(READY) -> fields(line.removePrefix(READY)).let { values ->
            GmsVendorFreezeBridgeRecord.Ready(
                timeout = values["timeout"] == "1",
                sticky = values["sticky"] == "1",
                strategy = values["strategy"].orEmpty(),
                shellPid = values["shellPid"].toIntOrZero(),
                parentStartTimeTicks = values["parentStartTicks"].orEmpty(),
                shellStartTimeTicks = values["shellStartTicks"].orEmpty(),
                heartbeatPath = values["heartbeatPath"].orEmpty(),
                ownerPath = values["ownerPath"].orEmpty()
            )
        }
        line.startsWith(HEARTBEAT) -> fields(line.removePrefix(HEARTBEAT)).let { values ->
            GmsVendorFreezeBridgeRecord.Heartbeat(
                elapsedCentiseconds = values["atCs"].toLongOrZero(),
                mainPid = values["mainPid"].toIntOrZero(),
                mainState = values["mainState"].orEmpty().ifBlank { "unknown" },
                persistentPid = values["persistentPid"].toIntOrZero(),
                persistentState = values["persistentState"].orEmpty().ifBlank { "unknown" },
                whatsappPid = values["whatsappPid"].toIntOrZero(),
                whatsappState = values["whatsappState"].orEmpty().ifBlank { "disabled" },
                signalPid = values["signalPid"].toIntOrZero(),
                signalState = values["signalState"].orEmpty().ifBlank { "disabled" }
            )
        }
        line.startsWith(FROZEN) -> fields(line.removePrefix(FROZEN)).let { values ->
            GmsVendorFreezeBridgeRecord.Frozen(
                sequence = values["seq"].toLongOrZero(),
                target = values["target"].orEmpty().ifBlank { "unknown" },
                pid = values["pid"].toIntOrZero(),
                cgroupPath = values["path"].orEmpty(),
                consecutive = values["consecutive"].toIntOrZero()
            )
        }
        line.startsWith(RECOVERY) -> fields(line.removePrefix(RECOVERY)).let { values ->
            GmsVendorFreezeBridgeRecord.Recovery(
                sequence = values["seq"].toLongOrZero(),
                target = values["target"].orEmpty().ifBlank { "unknown" },
                pid = values["pid"].toIntOrZero(),
                peerPid = values["peerPid"].toIntOrZero(),
                group = values["group"] == "1",
                mode = values["mode"].orEmpty().ifBlank { "unknown" },
                plainExitCode = values["plainRc"]?.toIntOrNull() ?: -1,
                freezeExitCode = values["freezeRc"]?.toIntOrNull() ?: -1,
                releaseExitCode = values["releaseRc"]?.toIntOrNull() ?: -1,
                stickyExitCode = values["stickyRc"]?.toIntOrNull() ?: -1,
                verified = values["verified"] == "1",
                adoptObserved = values["adoptObserved"] == "1",
                durationCentiseconds = values["durationCs"].toLongOrZero(),
                consecutive = values["consecutive"].toIntOrZero(),
                commandCount = values["commands"].toIntOrZero(),
                detail = values["detail"].orEmpty()
            )
        }
        line.startsWith(DEFENSE) -> fields(line.removePrefix(DEFENSE)).let { values ->
            GmsVendorFreezeBridgeRecord.Defense(
                sequence = values["seq"].toLongOrZero(),
                phase = values["phase"].orEmpty().ifBlank { "unknown" },
                elapsedCentiseconds = values["elapsedCs"].toLongOrZero(),
                stableCentiseconds = values["stableCs"].toLongOrZero(),
                refreezes = values["refreezes"].toIntOrZero(),
                attempts = values["attempts"].toIntOrZero(),
                commandCount = values["commands"].toIntOrZero(),
                mainPid = values["mainPid"].toIntOrZero(),
                persistentPid = values["persistentPid"].toIntOrZero(),
                detail = values["detail"].orEmpty()
            )
        }
        line.startsWith(SHIELD) -> fields(line.removePrefix(SHIELD)).let { values ->
            GmsVendorFreezeBridgeRecord.Shield(
                phase = values["phase"].orEmpty().ifBlank { "unknown" },
                generation = values["generation"].toLongOrZero(),
                atCentiseconds = values["atCs"].toLongOrZero(),
                untilCentiseconds = values["untilCs"].toLongOrZero(),
                latencyCentiseconds = values["latencyCs"].toLongOrZero(),
                commandCount = values["commands"].toIntOrZero(),
                mainPid = values["mainPid"].toIntOrZero(),
                persistentPid = values["persistentPid"].toIntOrZero(),
                detail = values["detail"].orEmpty()
            )
        }
        line.startsWith(LOCK) -> fields(line.removePrefix(LOCK)).let { values ->
            GmsVendorFreezeBridgeRecord.VendorLock(
                sequence = values["seq"].toLongOrZero(),
                target = values["target"].orEmpty().ifBlank { "unknown" },
                pid = values["pid"].toIntOrZero(),
                failures = values["failures"].toIntOrZero(),
                cooldownCentiseconds = values["cooldownCs"].toLongOrZero()
            )
        }
        line.startsWith(DIAGNOSTIC) -> fields(line.removePrefix(DIAGNOSTIC)).let { values ->
            GmsVendorFreezeBridgeRecord.Diagnostic(
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

internal data class GmsVendorDefenseReconnectPlan(
    val maxRounds: Int,
    val allowEmergencyEscalation: Boolean
)

internal object GmsVendorDefensePolicy {
    const val STRATEGY = "atomic_group_defense_edge_budget"
    const val PULSE_REQUIRED_CENTISECONDS = 1_200L
    const val STABLE_REQUIRED_CENTISECONDS = 1_200L
    const val NO_THAW_ESCALATION_CENTISECONDS = 3_000L
    const val SUSTAINED_REFREEZE_ESCALATION_CENTISECONDS = 12_000L
    const val STABLE_HOLD_CENTISECONDS = 12_000L
    const val HARD_LIMIT_CENTISECONDS = 60_000L
    const val POST_ESCALATION_GRACE_CENTISECONDS = 500L
    const val RETRY_HOLD_CENTISECONDS = 3_000L
    // r263: defense commands are event-driven. One physical freeze edge may
    // spend at most four framework commands (two GMS peers across at most two
    // release phases), and the whole PID generation may never spend more than 12.
    const val MAX_EPISODE_COMMANDS = 12
    const val MAX_EDGE_COMMANDS = 4

    const val PULSE_REQUIRED_MILLISECONDS = PULSE_REQUIRED_CENTISECONDS * 10L
    const val STABLE_REQUIRED_MILLISECONDS = STABLE_REQUIRED_CENTISECONDS * 10L
    const val STABLE_HOLD_MILLISECONDS = STABLE_HOLD_CENTISECONDS * 10L

    fun reconnectPlan(): GmsVendorDefenseReconnectPlan =
        GmsVendorDefenseReconnectPlan(
            maxRounds = 1,
            allowEmergencyEscalation = false
        )
}

internal object GmsVendorFreezeBridgeScript {
    const val DEFAULT_BASE_ROOT = "/data/local/tmp"
    const val COMMAND_OWNER_PATH = "/data/local/tmp/luonnotar-freezer-command-owner"
    const val POST_FORCE_STOP_SHIELD_PATH =
        "/data/local/tmp/luonnotar-gms-post-force-stop-shield"
    private val SAFE_BASE_ROOT = Regex("^/[A-Za-z0-9_./-]{1,180}${'$'}")

    fun heartbeatPath(parentPid: Int, baseRoot: String = DEFAULT_BASE_ROOT): String =
        "$baseRoot/luonnotar-vendor-freeze-bridge-$parentPid.heartbeat"

    fun heartbeatPath(
        parentPid: Int,
        shellPid: Int,
        baseRoot: String = DEFAULT_BASE_ROOT
    ): String = "$baseRoot/luonnotar-vendor-freeze-bridge-$parentPid-$shellPid.heartbeat"

    fun build(
        parentPid: Int,
        stickyUnfreeze: Boolean,
        monitorGms: Boolean = true,
        monitorWhatsApp: Boolean = false,
        monitorSignal: Boolean = false,
        baseRoot: String = DEFAULT_BASE_ROOT
    ): String {
        require(parentPid > 1) { "invalid parent pid" }
        require(SAFE_BASE_ROOT.matches(baseRoot)) { "invalid vendor bridge base root" }
        return SHELL_TEMPLATE
            .replace("@PARENT@", parentPid.toString())
            .replace("@BASE@", shellQuote("$baseRoot/luonnotar-vendor-freeze-bridge-$parentPid"))
            .replace("@OWNER@", shellQuote(COMMAND_OWNER_PATH))
            .replace("@SHIELD@", shellQuote(POST_FORCE_STOP_SHIELD_PATH))
            .replace("@STICKY@", if (stickyUnfreeze) "1" else "0")
            .replace("@MAIN@", shellQuote(if (monitorGms) "com.google.android.gms" else ""))
            .replace("@PERSISTENT@", shellQuote(if (monitorGms) "com.google.android.gms.persistent" else ""))
            .replace("@WHATSAPP@", shellQuote(if (monitorWhatsApp) "com.whatsapp" else ""))
            .replace("@SIGNAL@", shellQuote(if (monitorSignal) "org.thoughtcrime.securesms" else ""))
            .trimIndent() + "\n"
    }

    private val SHELL_TEMPLATE = """
umask 077
parent_pid=@PARENT@
shell_pid=${'$'}${'$'}
base_root=@BASE@
base="${'$'}base_root-${'$'}shell_pid"
heartbeat_file="${'$'}base.heartbeat"
command_owner_file=@OWNER@
command_owner_lock="${'$'}command_owner_file.lock"
post_force_shield_file=@SHIELD@
sticky_enabled=@STICKY@
has_timeout_command=0
sequence=0
recovery_command_count=0
heartbeat_due_cs=0
storm_until_cs=0
aux_due_cs=0
post_force_shield_check_due_cs=0
post_force_shield_active=0
post_force_shield_generation=0
post_force_shield_until_cs=0
post_force_shield_freeze_seen=0
post_force_shield_freeze_detected_cs=0
post_force_shield_last_until_cs=0
monitor_pid=""
heartbeat_pid=""
parent_start_ticks=""
shell_start_ticks=""

main_target=@MAIN@
persistent_target=@PERSISTENT@
whatsapp_target=@WHATSAPP@
signal_target=@SIGNAL@
main_pid=0
main_file=""
main_path=""
main_state="unknown"
persistent_pid=0
persistent_file=""
persistent_path=""
persistent_state="unknown"
gms_last_state="unknown"
gms_last_recovery_cs=0
gms_incomplete_since_cs=0
gms_last_incomplete_report_cs=0
gms_defense_active=0
gms_defense_sequence=0
gms_defense_started_cs=0
gms_defense_hard_deadline_cs=0
gms_defense_stable_since_cs=0
gms_defense_last_thawed_cs=0
gms_defense_action_armed=0
gms_defense_budget_exhausted=0
gms_defense_edge_budget_exhausted=0
gms_defense_edge_commands=0
defense_budget_enforced=0
gms_defense_pulse_sent=0
gms_defense_stable_hold_announced=0
gms_defense_escalated=0
gms_defense_refreezes=0
gms_defense_attempts=0
gms_defense_commands=0
gms_defense_last_mode="never"
gms_defense_last_detail=""
gms_defense_plain_rc=125
gms_defense_freeze_rc=125
gms_defense_release_rc=125
gms_defense_sticky_rc=125
gms_defense_adopt_observed=0
gms_defense_last_main_pid=0
gms_defense_last_persistent_pid=0
gms_defense_pulse_required_cs=${GmsVendorDefensePolicy.PULSE_REQUIRED_CENTISECONDS}
gms_defense_stable_required_cs=${GmsVendorDefensePolicy.STABLE_REQUIRED_CENTISECONDS}
gms_defense_stable_hold_cs=${GmsVendorDefensePolicy.STABLE_HOLD_CENTISECONDS}
gms_defense_stuck_required_cs=${GmsVendorDefensePolicy.NO_THAW_ESCALATION_CENTISECONDS}
gms_defense_escalation_required_cs=${GmsVendorDefensePolicy.SUSTAINED_REFREEZE_ESCALATION_CENTISECONDS}
gms_defense_hard_limit_cs=${GmsVendorDefensePolicy.HARD_LIMIT_CENTISECONDS}
gms_defense_post_escalation_grace_cs=${GmsVendorDefensePolicy.POST_ESCALATION_GRACE_CENTISECONDS}
gms_defense_hold_until_cs=0
gms_defense_command_budget=${GmsVendorDefensePolicy.MAX_EPISODE_COMMANDS}
gms_defense_edge_command_budget=${GmsVendorDefensePolicy.MAX_EDGE_COMMANDS}
framework_freezer_unsupported=0
framework_freezer_unsupported_reported=0
whatsapp_pid=0
whatsapp_file=""
whatsapp_path=""
whatsapp_last_state="disabled"
whatsapp_last_recovery_cs=0
whatsapp_failures=0
whatsapp_cooldown_until_cs=0
signal_pid=0
signal_file=""
signal_path=""
signal_last_state="disabled"
signal_last_recovery_cs=0
signal_failures=0
signal_cooldown_until_cs=0

rm -rf "${'$'}base"
mkdir -p "${'$'}base" || exit 70
if command -v timeout >/dev/null 2>&1; then has_timeout_command=1; fi

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

proc_start_time_ticks() {
    _identity_stat=${'$'}(cat "/proc/${'$'}1/stat" 2>/dev/null) || return 1
    case "${'$'}_identity_stat" in *') '*) ;; *) return 1 ;; esac
    _identity_tail="${'$'}{_identity_stat##*) }"
    _identity_start=${'$'}(printf '%s\n' "${'$'}_identity_tail" | awk '{print ${'$'}20}')
    case "${'$'}_identity_start" in ''|*[!0-9]*) return 1 ;; esac
    printf '%s\n' "${'$'}_identity_start"
}

pid_start_matches() {
    _identity_pid="${'$'}1"
    _identity_expected="${'$'}2"
    case "${'$'}_identity_pid:${'$'}_identity_expected" in *[!0-9:]*|:|0:*|*:0) return 1 ;; esac
    kill -0 "${'$'}_identity_pid" >/dev/null 2>&1 || return 1
    _identity_current=${'$'}(proc_start_time_ticks "${'$'}_identity_pid") || return 1
    [ "${'$'}_identity_current" = "${'$'}_identity_expected" ]
}

parent_start_ticks=${'$'}(proc_start_time_ticks "${'$'}parent_pid") || exit 71
shell_start_ticks=${'$'}(proc_start_time_ticks "${'$'}shell_pid") || exit 71

sanitize_detail() {
    SANITIZED_DETAIL=${'$'}(printf '%s' "${'$'}1" | tr '\t\r\n' '   ' | tr -cd '[:print:]' | cut -c1-280)
    [ -n "${'$'}SANITIZED_DETAIL" ] || SANITIZED_DETAIL="empty"
}

command_owner_record_matches_self() {
    [ -d "${'$'}command_owner_lock" ] || return 1
    [ -r "${'$'}command_owner_file" ] || return 1
    _self_owner=""; _self_parent=0; _self_shell=0; _self_parent_start=""; _self_shell_start=""; _self_heartbeat=""
    while IFS='=' read -r _key _value; do
        case "${'$'}_key" in
            owner) _self_owner="${'$'}_value" ;;
            parentPid) _self_parent="${'$'}_value" ;;
            shellPid) _self_shell="${'$'}_value" ;;
            parentStartTicks) _self_parent_start="${'$'}_value" ;;
            shellStartTicks) _self_shell_start="${'$'}_value" ;;
            heartbeatPath) _self_heartbeat="${'$'}_value" ;;
        esac
    done < "${'$'}command_owner_file"
    [ "${'$'}_self_owner" = "vendor_bridge" ] &&
        [ "${'$'}_self_parent" = "${'$'}parent_pid" ] &&
        [ "${'$'}_self_shell" = "${'$'}shell_pid" ] &&
        [ "${'$'}_self_parent_start" = "${'$'}parent_start_ticks" ] &&
        [ "${'$'}_self_shell_start" = "${'$'}shell_start_ticks" ] &&
        [ "${'$'}_self_heartbeat" = "${'$'}heartbeat_file" ]
}

command_owner_is_self() {
    command_owner_record_matches_self &&
        pid_start_matches "${'$'}parent_pid" "${'$'}parent_start_ticks" &&
        pid_start_matches "${'$'}shell_pid" "${'$'}shell_start_ticks"
}

require_command_owner() {
    command_owner_is_self && return 0
    printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=owner_lost\tdetail=parent_%s_shell_%s\n' \
        "${'$'}parent_pid" "${'$'}shell_pid"
    exit 74
}

run_limited() {
    if [ "${'$'}has_timeout_command" -eq 1 ]; then
        timeout 2 "${'$'}@"
        return ${'$'}?
    fi
    "${'$'}@" &
    _limited_pid=${'$'}!
    _limited_tick=0
    while kill -0 "${'$'}_limited_pid" >/dev/null 2>&1 && [ "${'$'}_limited_tick" -lt 20 ]; do
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

run_captured() {
    require_command_owner
    _capture_label="${'$'}1"
    recovery_command_count=${'$'}((recovery_command_count + 1))
    shift
    _capture_file="${'$'}base/command.out"
    : > "${'$'}_capture_file"
    run_limited "${'$'}@" >"${'$'}_capture_file" 2>&1
    CAPTURE_RC=${'$'}?
    _capture_raw=${'$'}(cat "${'$'}_capture_file" 2>/dev/null || true)
    sanitize_detail "${'$'}_capture_raw"
    CAPTURE_DETAIL="${'$'}SANITIZED_DETAIL"
    if [ "${'$'}CAPTURE_RC" -ne 0 ]; then
        printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=command_failed\tdetail=%s_rc_%s_%s\n' \
            "${'$'}_capture_label" "${'$'}CAPTURE_RC" "${'$'}CAPTURE_DETAIL"
    fi
}

pid_matches_target() {
    _identity_target="${'$'}1"
    _identity_pid="${'$'}2"
    case "${'$'}_identity_pid" in ''|*[!0-9]*) return 1 ;; esac
    [ -r "/proc/${'$'}_identity_pid/cmdline" ] || return 1
    _identity_name=${'$'}(tr '\000' '\n' < "/proc/${'$'}_identity_pid/cmdline" 2>/dev/null | head -n 1)
    [ "${'$'}_identity_name" = "${'$'}_identity_target" ]
}

resolve_target() {
    _target="${'$'}1"
    RESOLVED_PID=0
    RESOLVED_FILE=""
    RESOLVED_PATH=""
    _pids=${'$'}(pidof "${'$'}_target" 2>/dev/null || true)
    for _pid in ${'$'}_pids; do
        pid_matches_target "${'$'}_target" "${'$'}_pid" || continue
        [ -r "/proc/${'$'}_pid/cgroup" ] || continue
        _path=${'$'}(awk -F: '${'$'}1 == "0" { print ${'$'}3; exit }' "/proc/${'$'}_pid/cgroup" 2>/dev/null)
        if [ -n "${'$'}_path" ] && [ -r "/sys/fs/cgroup${'$'}_path/cgroup.freeze" ]; then
            RESOLVED_PID="${'$'}_pid"
            RESOLVED_FILE="/sys/fs/cgroup${'$'}_path/cgroup.freeze"
            RESOLVED_PATH="${'$'}_path"
            return 0
        fi
        _v1_path=${'$'}(awk -F: '${'$'}2 ~ /(^|,)freezer(,|${'$'})/ { print ${'$'}3; exit }' "/proc/${'$'}_pid/cgroup" 2>/dev/null)
        if [ -n "${'$'}_v1_path" ] && [ -r "/sys/fs/cgroup/freezer${'$'}_v1_path/freezer.state" ]; then
            RESOLVED_PID="${'$'}_pid"
            RESOLVED_FILE="/sys/fs/cgroup/freezer${'$'}_v1_path/freezer.state"
            RESOLVED_PATH="${'$'}_v1_path"
            return 0
        fi
    done
    return 1
}

read_target_state() {
    _target="${'$'}1"
    _cached_pid="${'$'}2"
    _cached_file="${'$'}3"
    _cached_path="${'$'}4"
    STATE="absent"
    STATE_PID=0
    STATE_FILE=""
    STATE_PATH=""
    if [ "${'$'}_cached_pid" -gt 0 ] && pid_matches_target "${'$'}_target" "${'$'}_cached_pid" && [ -r "${'$'}_cached_file" ]; then
        STATE_PID="${'$'}_cached_pid"
        STATE_FILE="${'$'}_cached_file"
        STATE_PATH="${'$'}_cached_path"
    elif resolve_target "${'$'}_target"; then
        STATE_PID="${'$'}RESOLVED_PID"
        STATE_FILE="${'$'}RESOLVED_FILE"
        STATE_PATH="${'$'}RESOLVED_PATH"
    else
        return 0
    fi
    _raw=""
    IFS= read -r _raw < "${'$'}STATE_FILE" 2>/dev/null || true
    case "${'$'}_raw" in
        1|FROZEN|FREEZING) STATE="frozen" ;;
        0|THAWED) STATE="thawed" ;;
        *) STATE="unknown" ;;
    esac
}

refresh_slot() {
    _slot="${'$'}1"
    _target="${'$'}2"
    if [ -z "${'$'}_target" ]; then
        eval "${'$'}{_slot}_pid=0"
        eval "${'$'}{_slot}_file=''"
        eval "${'$'}{_slot}_path=''"
        eval "${'$'}{_slot}_state='disabled'"
        CURRENT_STATE="disabled"
        return
    fi
    eval '_cached_pid=${'$'}'"${'$'}{_slot}"'_pid'
    eval '_cached_file=${'$'}'"${'$'}{_slot}"'_file'
    eval '_cached_path=${'$'}'"${'$'}{_slot}"'_path'
    read_target_state "${'$'}_target" "${'$'}_cached_pid" "${'$'}_cached_file" "${'$'}_cached_path"
    eval "${'$'}{_slot}_pid=${'$'}STATE_PID"
    eval "${'$'}{_slot}_file='${'$'}STATE_FILE'"
    eval "${'$'}{_slot}_path='${'$'}STATE_PATH'"
    eval "${'$'}{_slot}_state='${'$'}STATE'"
    CURRENT_STATE="${'$'}STATE"
}

framework_release_exact() {
    _target="${'$'}1"
    _pid="${'$'}2"
    RESULT_RC=125
    RESULT_DETAIL="not_run"
    pid_matches_target "${'$'}_target" "${'$'}_pid" || { RESULT_RC=66; RESULT_DETAIL="identity_mismatch"; return 66; }
    run_captured "unfreeze_pid_${'$'}{_pid}" cmd activity unfreeze "${'$'}_pid"
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    [ "${'$'}RESULT_RC" -eq 0 ] && return 0
    run_captured "unfreeze_name_${'$'}{_target}" cmd activity unfreeze "${'$'}_target"
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    [ "${'$'}RESULT_RC" -eq 0 ] && return 0
    run_captured "am_unfreeze_name_${'$'}{_target}" am unfreeze "${'$'}_target" --user 0
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    return "${'$'}RESULT_RC"
}

mark_unfrozen_sticky_exact() {
    _target="${'$'}1"
    _pid="${'$'}2"
    RESULT_RC=125
    RESULT_DETAIL="sticky_disabled"
    [ "${'$'}sticky_enabled" -eq 1 ] || return 125
    pid_matches_target "${'$'}_target" "${'$'}_pid" || { RESULT_RC=66; RESULT_DETAIL="identity_mismatch"; return 66; }
    run_captured "sticky_unfreeze_pid_${'$'}{_pid}" cmd activity unfreeze --sticky "${'$'}_pid"
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    [ "${'$'}RESULT_RC" -eq 0 ] && return 0
    run_captured "sticky_unfreeze_name_${'$'}{_target}" cmd activity unfreeze --sticky "${'$'}_target"
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    [ "${'$'}RESULT_RC" -eq 0 ] && return 0
    run_captured "am_sticky_unfreeze_name_${'$'}{_target}" am unfreeze --sticky "${'$'}_target" --user 0
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    return "${'$'}RESULT_RC"
}

mark_framework_freezer_unsupported_if_needed() {
    _freezer_detail="${'$'}1"
    case "${'$'}_freezer_detail" in
        *freezeAppAsyncInternalLSP*|*Handler.obtainMessage*|*NullPointerException*)
            framework_freezer_unsupported=1
            if [ "${'$'}framework_freezer_unsupported_reported" -eq 0 ]; then
                framework_freezer_unsupported_reported=1
                printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=framework_freezer_unsupported\tdetail=originos_cached_app_optimizer_handler_unavailable\n'
            fi
            return 0
            ;;
    esac
    return 1
}

force_freeze_exact() {
    _target="${'$'}1"
    _pid="${'$'}2"
    RESULT_RC=125
    RESULT_DETAIL="not_run"
    if [ "${'$'}framework_freezer_unsupported" -eq 1 ]; then
        RESULT_RC=126; RESULT_DETAIL="framework_freezer_unsupported"; return 126
    fi
    pid_matches_target "${'$'}_target" "${'$'}_pid" || { RESULT_RC=66; RESULT_DETAIL="identity_mismatch"; return 66; }
    run_captured "freeze_pid_${'$'}{_pid}" cmd activity freeze "${'$'}_pid"
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    mark_framework_freezer_unsupported_if_needed "${'$'}RESULT_DETAIL" && { RESULT_RC=126; RESULT_DETAIL="framework_freezer_unsupported:${'$'}RESULT_DETAIL"; return 126; }
    [ "${'$'}RESULT_RC" -eq 0 ] && return 0
    run_captured "freeze_name_${'$'}{_target}" cmd activity freeze "${'$'}_target"
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    mark_framework_freezer_unsupported_if_needed "${'$'}RESULT_DETAIL" && { RESULT_RC=126; RESULT_DETAIL="framework_freezer_unsupported:${'$'}RESULT_DETAIL"; return 126; }
    [ "${'$'}RESULT_RC" -eq 0 ] && return 0
    run_captured "am_freeze_name_${'$'}{_target}" am freeze "${'$'}_target" --user 0
    RESULT_RC=${'$'}CAPTURE_RC; RESULT_DETAIL="${'$'}CAPTURE_DETAIL"
    mark_framework_freezer_unsupported_if_needed "${'$'}RESULT_DETAIL" && { RESULT_RC=126; RESULT_DETAIL="framework_freezer_unsupported:${'$'}RESULT_DETAIL"; return 126; }
    return "${'$'}RESULT_RC"
}

capture_framework_freezer_snapshot() {
    FRAMEWORK_SNAPSHOT_FILE="${'$'}base/activity.dump"
    : > "${'$'}FRAMEWORK_SNAPSHOT_FILE"
    run_limited dumpsys activity >"${'$'}FRAMEWORK_SNAPSHOT_FILE" 2>/dev/null
    FRAMEWORK_SNAPSHOT_RC=${'$'}?
    return "${'$'}FRAMEWORK_SNAPSHOT_RC"
}

framework_snapshot_lists_pid() {
    _book_pid="${'$'}1"
    [ "${'$'}FRAMEWORK_SNAPSHOT_RC" -eq 0 ] || return 1
    awk -v pid="${'$'}_book_pid" '
        /Freezer settings/ { in_freezer=1; next }
        in_freezer && /CacheOomRanker settings/ { exit }
        in_freezer && ${'$'}0 ~ (":" "[[:space:]]*" pid "[[:space:]]") { found=1; exit }
        END { exit(found ? 0 : 1) }
    ' "${'$'}FRAMEWORK_SNAPSHOT_FILE"
}

framework_lists_pid() {
    capture_framework_freezer_snapshot || return 1
    framework_snapshot_lists_pid "${'$'}1"
}

aggregate_rc() {
    _agg_a="${'$'}1"
    _agg_b="${'$'}2"
    if [ "${'$'}_agg_a" -ne 0 ] && [ "${'$'}_agg_a" -ne 125 ]; then AGG_RC="${'$'}_agg_a"
    elif [ "${'$'}_agg_b" -ne 0 ] && [ "${'$'}_agg_b" -ne 125 ]; then AGG_RC="${'$'}_agg_b"
    elif [ "${'$'}_agg_a" -eq 0 ] || [ "${'$'}_agg_b" -eq 0 ]; then AGG_RC=0
    else AGG_RC=125
    fi
}


reserve_defense_command() {
    if [ "${'$'}defense_budget_enforced" -ne 1 ]; then
        return 0
    fi
    if [ "${'$'}gms_defense_commands" -ge "${'$'}gms_defense_command_budget" ]; then
        gms_defense_budget_exhausted=1
        return 1
    fi
    if [ "${'$'}gms_defense_edge_commands" -ge "${'$'}gms_defense_edge_command_budget" ]; then
        gms_defense_edge_budget_exhausted=1
        return 1
    fi
    # Count at reservation time, not after a nested helper returns. This is the
    # single source of truth for the episode-wide hard budget.
    gms_defense_commands=${'$'}((gms_defense_commands + 1))
    gms_defense_edge_commands=${'$'}((gms_defense_edge_commands + 1))
    return 0
}

run_parallel_pair() {
    require_command_owner
    _pair_mode="${'$'}1"
    _pair_main_arg="${'$'}2"
    _pair_persistent_arg="${'$'}3"
    _pair_label="${'$'}4"
    read_uptime_cs
    _pair_dir="${'$'}base/pair-${'$'}sequence-${'$'}_pair_label-${'$'}NOW_CS"
    rm -rf "${'$'}_pair_dir"
    mkdir -p "${'$'}_pair_dir" || {
        PAIR_MAIN_RC=70; PAIR_PERSISTENT_RC=70
        PAIR_MAIN_DETAIL="pair_dir_failed"; PAIR_PERSISTENT_DETAIL="pair_dir_failed"
        return 70
    }
    _main_job=""; _persistent_job=""
    _main_budget_denied=0; _persistent_budget_denied=0
    if [ -n "${'$'}_pair_main_arg" ]; then
        if reserve_defense_command; then
            recovery_command_count=${'$'}((recovery_command_count + 1))
        else
            _pair_main_arg=""
            _main_budget_denied=1
        fi
    fi
    if [ -n "${'$'}_pair_persistent_arg" ]; then
        if reserve_defense_command; then
            recovery_command_count=${'$'}((recovery_command_count + 1))
        else
            _pair_persistent_arg=""
            _persistent_budget_denied=1
        fi
    fi
    if [ -n "${'$'}_pair_main_arg" ]; then
        (
            case "${'$'}_pair_mode" in
                release) run_limited cmd activity unfreeze "${'$'}_pair_main_arg" ;;
                freeze) run_limited cmd activity freeze "${'$'}_pair_main_arg" ;;
                sticky) run_limited cmd activity unfreeze --sticky "${'$'}_pair_main_arg" ;;
                *) printf 'invalid_pair_mode:%s\n' "${'$'}_pair_mode"; exit 64 ;;
            esac >"${'$'}_pair_dir/main.out" 2>&1
            printf '%s\n' "${'$'}?" > "${'$'}_pair_dir/main.rc"
        ) &
        _main_job=${'$'}!
    else
        printf '125\n' > "${'$'}_pair_dir/main.rc"
        if [ "${'$'}_main_budget_denied" -eq 1 ]; then
            printf 'defense_command_budget_exhausted\n' > "${'$'}_pair_dir/main.out"
        else
            printf 'not_requested\n' > "${'$'}_pair_dir/main.out"
        fi
    fi
    if [ -n "${'$'}_pair_persistent_arg" ]; then
        (
            case "${'$'}_pair_mode" in
                release) run_limited cmd activity unfreeze "${'$'}_pair_persistent_arg" ;;
                freeze) run_limited cmd activity freeze "${'$'}_pair_persistent_arg" ;;
                sticky) run_limited cmd activity unfreeze --sticky "${'$'}_pair_persistent_arg" ;;
                *) printf 'invalid_pair_mode:%s\n' "${'$'}_pair_mode"; exit 64 ;;
            esac >"${'$'}_pair_dir/persistent.out" 2>&1
            printf '%s\n' "${'$'}?" > "${'$'}_pair_dir/persistent.rc"
        ) &
        _persistent_job=${'$'}!
    else
        printf '125\n' > "${'$'}_pair_dir/persistent.rc"
        if [ "${'$'}_persistent_budget_denied" -eq 1 ]; then
            printf 'defense_command_budget_exhausted\n' > "${'$'}_pair_dir/persistent.out"
        else
            printf 'not_requested\n' > "${'$'}_pair_dir/persistent.out"
        fi
    fi
    [ -n "${'$'}_main_job" ] && wait "${'$'}_main_job" >/dev/null 2>&1 || true
    [ -n "${'$'}_persistent_job" ] && wait "${'$'}_persistent_job" >/dev/null 2>&1 || true
    PAIR_MAIN_RC=${'$'}(cat "${'$'}_pair_dir/main.rc" 2>/dev/null || echo 125)
    PAIR_PERSISTENT_RC=${'$'}(cat "${'$'}_pair_dir/persistent.rc" 2>/dev/null || echo 125)
    sanitize_detail "${'$'}(cat "${'$'}_pair_dir/main.out" 2>/dev/null || true)"; PAIR_MAIN_DETAIL="${'$'}SANITIZED_DETAIL"
    sanitize_detail "${'$'}(cat "${'$'}_pair_dir/persistent.out" 2>/dev/null || true)"; PAIR_PERSISTENT_DETAIL="${'$'}SANITIZED_DETAIL"
    if [ "${'$'}PAIR_MAIN_RC" -ne 0 ] && [ "${'$'}PAIR_MAIN_RC" -ne 125 ]; then
        printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=command_failed\tdetail=%s_main_rc_%s_%s\n' \
            "${'$'}_pair_label" "${'$'}PAIR_MAIN_RC" "${'$'}PAIR_MAIN_DETAIL"
    fi
    if [ "${'$'}PAIR_PERSISTENT_RC" -ne 0 ] && [ "${'$'}PAIR_PERSISTENT_RC" -ne 125 ]; then
        printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=command_failed\tdetail=%s_persistent_rc_%s_%s\n' \
            "${'$'}_pair_label" "${'$'}PAIR_PERSISTENT_RC" "${'$'}PAIR_PERSISTENT_DETAIL"
    fi
    rm -rf "${'$'}_pair_dir"
}

run_group_phase() {
    _group_mode="${'$'}1"
    _group_main_enabled="${'$'}2"
    _group_persistent_enabled="${'$'}3"
    GROUP_MAIN_RC=125; GROUP_PERSISTENT_RC=125
    GROUP_MAIN_DETAIL="not_requested"; GROUP_PERSISTENT_DETAIL="not_requested"

    if [ "${'$'}_group_main_enabled" -eq 1 ] && ! pid_matches_target "${'$'}main_target" "${'$'}main_pid"; then
        GROUP_MAIN_RC=66; GROUP_MAIN_DETAIL="identity_mismatch"
        _group_main_enabled=0
    fi
    if [ "${'$'}_group_persistent_enabled" -eq 1 ] && ! pid_matches_target "${'$'}persistent_target" "${'$'}persistent_pid"; then
        GROUP_PERSISTENT_RC=66; GROUP_PERSISTENT_DETAIL="identity_mismatch"
        _group_persistent_enabled=0
    fi

    _main_pid_arg=""; _persistent_pid_arg=""
    [ "${'$'}_group_main_enabled" -eq 1 ] && _main_pid_arg="${'$'}main_pid"
    [ "${'$'}_group_persistent_enabled" -eq 1 ] && _persistent_pid_arg="${'$'}persistent_pid"
    run_parallel_pair "${'$'}_group_mode" "${'$'}_main_pid_arg" "${'$'}_persistent_pid_arg" "${'$'}{_group_mode}_pid"
    if [ "${'$'}_group_main_enabled" -eq 1 ]; then GROUP_MAIN_RC="${'$'}PAIR_MAIN_RC"; GROUP_MAIN_DETAIL="pid:${'$'}PAIR_MAIN_DETAIL"; fi
    if [ "${'$'}_group_persistent_enabled" -eq 1 ]; then GROUP_PERSISTENT_RC="${'$'}PAIR_PERSISTENT_RC"; GROUP_PERSISTENT_DETAIL="pid:${'$'}PAIR_PERSISTENT_DETAIL"; fi

    _main_name_arg=""; _persistent_name_arg=""
    if [ "${'$'}_group_main_enabled" -eq 1 ] && [ "${'$'}GROUP_MAIN_RC" -ne 0 ] && pid_matches_target "${'$'}main_target" "${'$'}main_pid"; then _main_name_arg="${'$'}main_target"; fi
    if [ "${'$'}_group_persistent_enabled" -eq 1 ] && [ "${'$'}GROUP_PERSISTENT_RC" -ne 0 ] && pid_matches_target "${'$'}persistent_target" "${'$'}persistent_pid"; then _persistent_name_arg="${'$'}persistent_target"; fi
    if [ -n "${'$'}_main_name_arg" ] || [ -n "${'$'}_persistent_name_arg" ]; then
        run_parallel_pair "${'$'}_group_mode" "${'$'}_main_name_arg" "${'$'}_persistent_name_arg" "${'$'}{_group_mode}_name"
        if [ -n "${'$'}_main_name_arg" ]; then GROUP_MAIN_RC="${'$'}PAIR_MAIN_RC"; GROUP_MAIN_DETAIL="${'$'}{GROUP_MAIN_DETAIL}|name:${'$'}PAIR_MAIN_DETAIL"; fi
        if [ -n "${'$'}_persistent_name_arg" ]; then GROUP_PERSISTENT_RC="${'$'}PAIR_PERSISTENT_RC"; GROUP_PERSISTENT_DETAIL="${'$'}{GROUP_PERSISTENT_DETAIL}|name:${'$'}PAIR_PERSISTENT_DETAIL"; fi
    fi

    if [ "${'$'}_group_main_enabled" -eq 1 ] && [ "${'$'}GROUP_MAIN_RC" -ne 0 ] && pid_matches_target "${'$'}main_target" "${'$'}main_pid"; then
        case "${'$'}_group_mode" in
            release) run_captured "am_unfreeze_main" am unfreeze "${'$'}main_target" --user 0 ;;
            freeze) run_captured "am_freeze_main" am freeze "${'$'}main_target" --user 0 ;;
            sticky) run_captured "am_sticky_main" am unfreeze --sticky "${'$'}main_target" --user 0 ;;
        esac
        GROUP_MAIN_RC="${'$'}CAPTURE_RC"; GROUP_MAIN_DETAIL="${'$'}{GROUP_MAIN_DETAIL}|am:${'$'}CAPTURE_DETAIL"
    fi
    if [ "${'$'}_group_persistent_enabled" -eq 1 ] && [ "${'$'}GROUP_PERSISTENT_RC" -ne 0 ] && pid_matches_target "${'$'}persistent_target" "${'$'}persistent_pid"; then
        case "${'$'}_group_mode" in
            release) run_captured "am_unfreeze_persistent" am unfreeze "${'$'}persistent_target" --user 0 ;;
            freeze) run_captured "am_freeze_persistent" am freeze "${'$'}persistent_target" --user 0 ;;
            sticky) run_captured "am_sticky_persistent" am unfreeze --sticky "${'$'}persistent_target" --user 0 ;;
        esac
        GROUP_PERSISTENT_RC="${'$'}CAPTURE_RC"; GROUP_PERSISTENT_DETAIL="${'$'}{GROUP_PERSISTENT_DETAIL}|am:${'$'}CAPTURE_DETAIL"
    fi
}

recover_gms_group() {
    _consecutive="${'$'}1"
    _report="${'$'}{2:-1}"
    recovery_command_count=0
    read_uptime_cs; _started_cs="${'$'}NOW_CS"
    _plain_main=125; _plain_persistent=125
    _freeze_main=125; _freeze_persistent=125
    _release_main=125; _release_persistent=125
    _sticky_main=125; _sticky_persistent=125
    _adopt_observed=0; _adopt_attempted=0
    _mode="plain_group"
    _verified=0
    _detail=""

    if [ "${'$'}main_pid" -le 0 ] || [ "${'$'}persistent_pid" -le 0 ] || \
       ! pid_matches_target "${'$'}main_target" "${'$'}main_pid" || \
       ! pid_matches_target "${'$'}persistent_target" "${'$'}persistent_pid"; then
        _detail="group_incomplete:main=${'$'}main_pid/${'$'}main_state,persistent=${'$'}persistent_pid/${'$'}persistent_state"
        printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=gms_group_incomplete\tdetail=%s\n' "${'$'}_detail"
        _plain_rc=66; _freeze_rc=125; _release_rc=125; _sticky_rc=125
    else
        run_group_phase release 1 1
        _plain_main="${'$'}GROUP_MAIN_RC"; _plain_persistent="${'$'}GROUP_PERSISTENT_RC"
        _detail="mainPlain:${'$'}GROUP_MAIN_RC:${'$'}GROUP_MAIN_DETAIL,persistentPlain:${'$'}GROUP_PERSISTENT_RC:${'$'}GROUP_PERSISTENT_DETAIL"
        aggregate_rc "${'$'}_plain_main" "${'$'}_plain_persistent"; _plain_rc="${'$'}AGG_RC"
        sleep 0.12
        refresh_slot main "${'$'}main_target"; refresh_slot persistent "${'$'}persistent_target"

        if [ "${'$'}main_pid" -gt 0 ] && [ "${'$'}main_state" = "thawed" ] && \
           [ "${'$'}persistent_pid" -gt 0 ] && [ "${'$'}persistent_state" = "thawed" ]; then
            _verified=1
        else
            _need_freeze_main=0; _need_freeze_persistent=0
            _framework_main_known=0; _framework_persistent_known=0
            _snapshot_before_ok=0
            if capture_framework_freezer_snapshot; then _snapshot_before_ok=1; fi
            if [ "${'$'}main_state" = "frozen" ]; then
                if [ "${'$'}_snapshot_before_ok" -eq 1 ] && framework_snapshot_lists_pid "${'$'}main_pid"; then
                    _framework_main_known=1
                else
                    _need_freeze_main=1
                fi
            fi
            if [ "${'$'}persistent_state" = "frozen" ]; then
                if [ "${'$'}_snapshot_before_ok" -eq 1 ] && framework_snapshot_lists_pid "${'$'}persistent_pid"; then
                    _framework_persistent_known=1
                else
                    _need_freeze_persistent=1
                fi
            fi
            _detail="${'$'}{_detail},ledgerBefore:ok=${'$'}_snapshot_before_ok/main=${'$'}_framework_main_known/persistent=${'$'}_framework_persistent_known"
            if { [ "${'$'}_need_freeze_main" -eq 1 ] || [ "${'$'}_need_freeze_persistent" -eq 1 ]; } && \
               [ "${'$'}framework_freezer_unsupported" -ne 1 ]; then
                _adopt_attempted=1
                run_group_phase freeze "${'$'}_need_freeze_main" "${'$'}_need_freeze_persistent"
                _freeze_main="${'$'}GROUP_MAIN_RC"; _freeze_persistent="${'$'}GROUP_PERSISTENT_RC"
                _detail="${'$'}{_detail},mainFreeze:${'$'}GROUP_MAIN_RC:${'$'}GROUP_MAIN_DETAIL,persistentFreeze:${'$'}GROUP_PERSISTENT_RC:${'$'}GROUP_PERSISTENT_DETAIL"
                mark_framework_freezer_unsupported_if_needed "${'$'}GROUP_MAIN_DETAIL,${'$'}GROUP_PERSISTENT_DETAIL" || true
                aggregate_rc "${'$'}_freeze_main" "${'$'}_freeze_persistent"; _freeze_rc="${'$'}AGG_RC"
                sleep 0.45
                _adopt_main_ok=1; _adopt_persistent_ok=1
                if [ "${'$'}framework_freezer_unsupported" -eq 1 ]; then
                    _adopt_main_ok=0; _adopt_persistent_ok=0
                else
                    capture_framework_freezer_snapshot || true
                    if [ "${'$'}_need_freeze_main" -eq 1 ] && ! framework_snapshot_lists_pid "${'$'}main_pid"; then _adopt_main_ok=0; fi
                    if [ "${'$'}_need_freeze_persistent" -eq 1 ] && ! framework_snapshot_lists_pid "${'$'}persistent_pid"; then _adopt_persistent_ok=0; fi
                fi
                if [ "${'$'}_adopt_main_ok" -eq 1 ] && [ "${'$'}_adopt_persistent_ok" -eq 1 ]; then _adopt_observed=1; fi
            else
                _freeze_rc=126
                if [ "${'$'}framework_freezer_unsupported" -eq 1 ]; then
                    _detail="${'$'}{_detail},freeze:skipped_framework_freezer_unsupported"
                else
                    _freeze_rc=125
                    _detail="${'$'}{_detail},freeze:skipped_framework_already_knows"
                fi
            fi

            run_group_phase release 1 1
            _release_main="${'$'}GROUP_MAIN_RC"; _release_persistent="${'$'}GROUP_PERSISTENT_RC"
            _detail="${'$'}{_detail},mainRelease:${'$'}GROUP_MAIN_RC:${'$'}GROUP_MAIN_DETAIL,persistentRelease:${'$'}GROUP_PERSISTENT_RC:${'$'}GROUP_PERSISTENT_DETAIL"
            aggregate_rc "${'$'}_release_main" "${'$'}_release_persistent"; _release_rc="${'$'}AGG_RC"
            sleep 0.18
            refresh_slot main "${'$'}main_target"; refresh_slot persistent "${'$'}persistent_target"
            if [ "${'$'}main_pid" -gt 0 ] && [ "${'$'}main_state" = "thawed" ] && \
               [ "${'$'}persistent_pid" -gt 0 ] && [ "${'$'}persistent_state" = "thawed" ]; then _verified=1; fi
            if [ "${'$'}_adopt_observed" -eq 1 ]; then
                _mode="adopt_release_group"
            elif [ "${'$'}_adopt_attempted" -eq 1 ]; then
                _mode="adopt_unconfirmed_release_group"
            else
                _mode="framework_release_retry_group"
            fi
        fi

        _freeze_rc=${'$'}{_freeze_rc:-125}
        _release_rc=${'$'}{_release_rc:-125}
        if [ "${'$'}_verified" -eq 1 ]; then
            if [ "${'$'}sticky_enabled" -eq 1 ]; then
                run_group_phase sticky 1 1
                _sticky_main="${'$'}GROUP_MAIN_RC"; _sticky_persistent="${'$'}GROUP_PERSISTENT_RC"
                _detail="${'$'}{_detail},mainSticky:${'$'}GROUP_MAIN_RC:${'$'}GROUP_MAIN_DETAIL,persistentSticky:${'$'}GROUP_PERSISTENT_RC:${'$'}GROUP_PERSISTENT_DETAIL"
            else
                _sticky_main=125; _sticky_persistent=125
                _detail="${'$'}{_detail},sticky:disabled"
            fi
            # Success means a stable physical thaw, not merely a zero exit code.
            sleep 0.20
            refresh_slot main "${'$'}main_target"; refresh_slot persistent "${'$'}persistent_target"
            if ! { [ "${'$'}main_pid" -gt 0 ] && [ "${'$'}main_state" = "thawed" ] && \
                   [ "${'$'}persistent_pid" -gt 0 ] && [ "${'$'}persistent_state" = "thawed" ]; }; then
                _verified=0
                _detail="${'$'}{_detail},postStickyState:main=${'$'}main_pid/${'$'}main_state,persistent=${'$'}persistent_pid/${'$'}persistent_state"
            fi
        fi
        aggregate_rc "${'$'}_sticky_main" "${'$'}_sticky_persistent"; _sticky_rc="${'$'}AGG_RC"
    fi

    sanitize_detail "${'$'}_detail"; _detail="${'$'}SANITIZED_DETAIL"
    read_uptime_cs; _ended_cs="${'$'}NOW_CS"
    _primary_pid="${'$'}main_pid"; [ "${'$'}_primary_pid" -gt 0 ] || _primary_pid="${'$'}persistent_pid"
    RECOVERY_MODE="${'$'}_mode"
    RECOVERY_PLAIN_RC="${'$'}_plain_rc"
    RECOVERY_FREEZE_RC="${'$'}_freeze_rc"
    RECOVERY_RELEASE_RC="${'$'}_release_rc"
    RECOVERY_STICKY_RC="${'$'}_sticky_rc"
    RECOVERY_ADOPT_OBSERVED="${'$'}_adopt_observed"
    RECOVERY_DURATION_CS="${'$'}((_ended_cs - _started_cs))"
    RECOVERY_COMMANDS="${'$'}recovery_command_count"
    RECOVERY_DETAIL="${'$'}_detail"
    if [ "${'$'}_report" -eq 1 ]; then
        printf '__LUONNOTAR_VENDOR_BRIDGE_RECOVERY__\tseq=%s\ttarget=%s\tpid=%s\tpeerPid=%s\tgroup=1\tmode=%s\tplainRc=%s\tfreezeRc=%s\treleaseRc=%s\tstickyRc=%s\tverified=%s\tadoptObserved=%s\tdurationCs=%s\tconsecutive=%s\tcommands=%s\tdetail=%s\n' \
            "${'$'}sequence" "${'$'}main_target" "${'$'}_primary_pid" "${'$'}persistent_pid" "${'$'}_mode" "${'$'}_plain_rc" "${'$'}_freeze_rc" "${'$'}_release_rc" "${'$'}_sticky_rc" "${'$'}_verified" "${'$'}_adopt_observed" "${'$'}RECOVERY_DURATION_CS" "${'$'}_consecutive" "${'$'}recovery_command_count" "${'$'}_detail"
    fi
    RECOVERY_VERIFIED="${'$'}_verified"
}

recover_single() {
    _target="${'$'}1"; _pid="${'$'}2"; _path="${'$'}3"; _consecutive="${'$'}4"
    recovery_command_count=0
    read_uptime_cs; _started_cs="${'$'}NOW_CS"
    _plain_rc=125; _freeze_rc=125; _release_rc=125; _sticky_rc=125
    _adopt_observed=0; _mode="plain"; _verified=0; _detail=""
    framework_release_exact "${'$'}_target" "${'$'}_pid"; _plain_rc=${'$'}RESULT_RC; _detail="plain:${'$'}{RESULT_RC}:${'$'}{RESULT_DETAIL}"
    sleep 0.12
    read_target_state "${'$'}_target" "${'$'}_pid" "" "${'$'}_path"
    if [ "${'$'}STATE" = "thawed" ]; then _verified=1
    else
        _framework_known=0
        if framework_lists_pid "${'$'}_pid"; then _framework_known=1; fi
        _detail="${'$'}{_detail},ledgerBefore:${'$'}_framework_known"
        if [ "${'$'}_framework_known" -eq 0 ]; then
            force_freeze_exact "${'$'}_target" "${'$'}_pid"; _freeze_rc=${'$'}RESULT_RC; _detail="${'$'}{_detail},freeze:${'$'}{RESULT_RC}:${'$'}{RESULT_DETAIL}"
            sleep 0.45
            if framework_lists_pid "${'$'}_pid"; then _adopt_observed=1; fi
        else
            _freeze_rc=125
            _detail="${'$'}{_detail},freeze:skipped_framework_already_knows"
        fi
        framework_release_exact "${'$'}_target" "${'$'}_pid"; _release_rc=${'$'}RESULT_RC; _detail="${'$'}{_detail},release:${'$'}{RESULT_RC}:${'$'}{RESULT_DETAIL}"
        sleep 0.18
        read_target_state "${'$'}_target" "${'$'}_pid" "" "${'$'}_path"
        [ "${'$'}STATE" = "thawed" ] && _verified=1
        if [ "${'$'}_adopt_observed" -eq 1 ]; then
            _mode="adopt_release"
        elif [ "${'$'}_framework_known" -eq 1 ]; then
            _mode="framework_release_retry"
        else
            _mode="adopt_unconfirmed_release"
        fi
    fi
    if [ "${'$'}_verified" -eq 1 ]; then
        mark_unfrozen_sticky_exact "${'$'}_target" "${'$'}_pid"; _sticky_rc=${'$'}RESULT_RC
        sleep 0.20
        read_target_state "${'$'}_target" "${'$'}_pid" "" "${'$'}_path"
        if [ "${'$'}STATE" != "thawed" ]; then
            _verified=0
            _detail="${'$'}{_detail},postStickyState:${'$'}STATE"
        fi
    fi
    sanitize_detail "${'$'}_detail"; _detail="${'$'}SANITIZED_DETAIL"
    read_uptime_cs; _ended_cs="${'$'}NOW_CS"
    printf '__LUONNOTAR_VENDOR_BRIDGE_RECOVERY__\tseq=%s\ttarget=%s\tpid=%s\tpeerPid=0\tgroup=0\tmode=%s\tplainRc=%s\tfreezeRc=%s\treleaseRc=%s\tstickyRc=%s\tverified=%s\tadoptObserved=%s\tdurationCs=%s\tconsecutive=%s\tcommands=%s\tdetail=%s\n' \
        "${'$'}sequence" "${'$'}_target" "${'$'}_pid" "${'$'}_mode" "${'$'}_plain_rc" "${'$'}_freeze_rc" "${'$'}_release_rc" "${'$'}_sticky_rc" "${'$'}_verified" "${'$'}_adopt_observed" "${'$'}((_ended_cs - _started_cs))" "${'$'}_consecutive" "${'$'}recovery_command_count" "${'$'}_detail"
    RECOVERY_VERIFIED="${'$'}_verified"
}

emit_gms_defense() {
    _def_phase="${'$'}1"
    _def_detail="${'$'}2"
    read_uptime_cs
    _def_elapsed=0
    _def_stable=0
    if [ "${'$'}gms_defense_started_cs" -gt 0 ] && [ "${'$'}NOW_CS" -ge "${'$'}gms_defense_started_cs" ]; then
        _def_elapsed=${'$'}((NOW_CS - gms_defense_started_cs))
    fi
    if [ "${'$'}gms_defense_stable_since_cs" -gt 0 ] && [ "${'$'}NOW_CS" -ge "${'$'}gms_defense_stable_since_cs" ]; then
        _def_stable=${'$'}((NOW_CS - gms_defense_stable_since_cs))
    fi
    sanitize_detail "${'$'}_def_detail"; _def_detail="${'$'}SANITIZED_DETAIL"
    printf '__LUONNOTAR_VENDOR_BRIDGE_DEFENSE__\tseq=%s\tphase=%s\telapsedCs=%s\tstableCs=%s\trefreezes=%s\tattempts=%s\tcommands=%s\tmainPid=%s\tpersistentPid=%s\tdetail=%s\n' \
        "${'$'}gms_defense_sequence" "${'$'}_def_phase" "${'$'}_def_elapsed" "${'$'}_def_stable" \
        "${'$'}gms_defense_refreezes" "${'$'}gms_defense_attempts" "${'$'}gms_defense_commands" \
        "${'$'}main_pid" "${'$'}persistent_pid" "${'$'}_def_detail"
}

start_gms_defense() {
    read_uptime_cs
    gms_defense_active=1
    gms_defense_sequence="${'$'}sequence"
    gms_defense_started_cs="${'$'}NOW_CS"
    gms_defense_hard_deadline_cs=${'$'}((NOW_CS + gms_defense_hard_limit_cs))
    gms_defense_stable_since_cs=0
    gms_defense_last_thawed_cs=0
    gms_defense_action_armed=1
    gms_defense_budget_exhausted=0
    gms_defense_edge_budget_exhausted=0
    gms_defense_edge_commands=0
    defense_budget_enforced=0
    gms_defense_pulse_sent=0
    gms_defense_stable_hold_announced=0
    gms_defense_escalated=0
    gms_defense_refreezes=0
    gms_defense_attempts=0
    gms_defense_commands=0
    gms_defense_last_mode="starting"
    gms_defense_last_detail="initial_freeze"
    gms_defense_plain_rc=125
    gms_defense_freeze_rc=125
    gms_defense_release_rc=125
    gms_defense_sticky_rc=125
    gms_defense_adopt_observed=0
    gms_defense_last_main_pid="${'$'}main_pid"
    gms_defense_last_persistent_pid="${'$'}persistent_pid"
    emit_gms_defense started "stableRequiredCs=${'$'}gms_defense_stable_required_cs,stableHoldCs=${'$'}gms_defense_stable_hold_cs,commandBudget=${'$'}gms_defense_command_budget,edgeBudget=${'$'}gms_defense_edge_command_budget,hardLimitCs=${'$'}gms_defense_hard_limit_cs"
}

reset_gms_defense_for_pid_change() {
    [ "${'$'}gms_defense_active" -eq 1 ] || return 0
    if [ "${'$'}main_pid" -eq "${'$'}gms_defense_last_main_pid" ] && \
       [ "${'$'}persistent_pid" -eq "${'$'}gms_defense_last_persistent_pid" ]; then
        return 0
    fi
    _old_main="${'$'}gms_defense_last_main_pid"
    _old_persistent="${'$'}gms_defense_last_persistent_pid"
    emit_gms_defense pid_changed "oldMain=${'$'}_old_main,oldPersistent=${'$'}_old_persistent,newMain=${'$'}main_pid,newPersistent=${'$'}persistent_pid"
    read_uptime_cs
    sequence=${'$'}((sequence + 1))
    gms_defense_sequence="${'$'}sequence"
    gms_defense_started_cs="${'$'}NOW_CS"
    gms_defense_hard_deadline_cs=${'$'}((NOW_CS + gms_defense_hard_limit_cs))
    gms_defense_last_main_pid="${'$'}main_pid"
    gms_defense_last_persistent_pid="${'$'}persistent_pid"
    gms_defense_stable_since_cs=0
    gms_defense_last_thawed_cs=0
    gms_defense_action_armed=1
    gms_defense_budget_exhausted=0
    gms_defense_edge_budget_exhausted=0
    gms_defense_edge_commands=0
    defense_budget_enforced=0
    gms_defense_pulse_sent=0
    gms_defense_stable_hold_announced=0
    gms_defense_escalated=0
    gms_defense_refreezes=0
    gms_defense_attempts=0
    gms_defense_commands=0
    gms_defense_last_mode="pid_generation"
    gms_defense_last_detail="oldMain=${'$'}_old_main,oldPersistent=${'$'}_old_persistent"
    emit_gms_defense started "reason=pid_generation,oldMain=${'$'}_old_main,oldPersistent=${'$'}_old_persistent"
}

emit_post_force_shield() {
    _shield_phase="${'$'}1"
    _shield_latency="${'$'}{2:-0}"
    _shield_detail="${'$'}{3:-}"
    sanitize_detail "${'$'}_shield_detail"; _shield_detail="${'$'}SANITIZED_DETAIL"
    printf '__LUONNOTAR_VENDOR_BRIDGE_SHIELD__\tphase=%s\tgeneration=%s\tatCs=%s\tuntilCs=%s\tlatencyCs=%s\tcommands=%s\tmainPid=%s\tpersistentPid=%s\tdetail=%s\n' \
        "${'$'}_shield_phase" "${'$'}post_force_shield_generation" "${'$'}NOW_CS" "${'$'}post_force_shield_until_cs" \
        "${'$'}_shield_latency" "${'$'}gms_defense_commands" "${'$'}main_pid" "${'$'}persistent_pid" "${'$'}_shield_detail"
}

refresh_post_force_shield() {
    read_uptime_cs
    if [ "${'$'}NOW_CS" -lt "${'$'}post_force_shield_check_due_cs" ]; then
        # Do not expire from the cached deadline here. A first-healthy extension
        # can land inside this 100 ms marker-poll interval; reading the marker on
        # the next poll avoids a false expiry immediately before the refresh.
        return 0
    fi
    post_force_shield_check_due_cs=${'$'}((NOW_CS + 10))

    _shield_parent=0; _shield_shell=0; _shield_parent_start=0; _shield_shell_start=0; _shield_generation=0; _shield_until=0
    if [ -r "${'$'}post_force_shield_file" ]; then
        while IFS='=' read -r _key _value; do
            case "${'$'}_key" in
                parentPid) _shield_parent="${'$'}_value" ;;
                shellPid) _shield_shell="${'$'}_value" ;;
                parentStartTicks) _shield_parent_start="${'$'}_value" ;;
                shellStartTicks) _shield_shell_start="${'$'}_value" ;;
                generation) _shield_generation="${'$'}_value" ;;
                untilCs) _shield_until="${'$'}_value" ;;
            esac
        done < "${'$'}post_force_shield_file"
    fi
    case "${'$'}_shield_parent:${'$'}_shield_shell:${'$'}_shield_parent_start:${'$'}_shield_shell_start:${'$'}_shield_generation:${'$'}_shield_until" in
        *[!0-9:]*|0:*|*:0:*|*:*:0:*|*:*:*:0:*|*:*:*:*:0:*|*:*:*:*:*:0) _shield_valid=0 ;;
        *) _shield_valid=1 ;;
    esac
    if [ "${'$'}_shield_valid" -eq 1 ] && \
       [ "${'$'}_shield_parent" -eq "${'$'}parent_pid" ] && \
       [ "${'$'}_shield_shell" -eq "${'$'}shell_pid" ] && \
       [ "${'$'}_shield_parent_start" = "${'$'}parent_start_ticks" ] && \
       [ "${'$'}_shield_shell_start" = "${'$'}shell_start_ticks" ] && \
       [ "${'$'}NOW_CS" -lt "${'$'}_shield_until" ]; then
        _shield_was_active="${'$'}post_force_shield_active"
        _shield_old_generation="${'$'}post_force_shield_generation"
        _shield_old_until="${'$'}post_force_shield_until_cs"
        post_force_shield_active=1
        post_force_shield_generation="${'$'}_shield_generation"
        post_force_shield_until_cs="${'$'}_shield_until"
        if [ "${'$'}_shield_was_active" -ne 1 ] || [ "${'$'}_shield_old_generation" -ne "${'$'}post_force_shield_generation" ]; then
            post_force_shield_freeze_seen=0
            post_force_shield_freeze_detected_cs=0
            # A verified force-stop creates a new GMS process generation. Do not
            # inherit a retry hold from the pre-reset generation; command budgets
            # still reset only through the normal PID-generation defense path.
            gms_defense_hold_until_cs=0
            emit_post_force_shield started 0 "marker_accepted,new_generation_hold_cleared"
        elif [ "${'$'}post_force_shield_until_cs" -gt "${'$'}_shield_old_until" ]; then
            emit_post_force_shield refreshed 0 "deadline_extended"
        fi
        post_force_shield_last_until_cs="${'$'}post_force_shield_until_cs"
        return 0
    fi

    if [ "${'$'}post_force_shield_active" -eq 1 ]; then
        post_force_shield_active=0
        post_force_shield_freeze_seen=0
        post_force_shield_freeze_detected_cs=0
        emit_post_force_shield expired 0 "marker_invalid_or_expired"
    fi
}

note_post_force_shield_freeze() {
    [ "${'$'}post_force_shield_active" -eq 1 ] || return 0
    [ "${'$'}post_force_shield_freeze_seen" -eq 0 ] || return 0
    post_force_shield_freeze_seen=1
    post_force_shield_freeze_detected_cs="${'$'}NOW_CS"
    emit_post_force_shield frozen 0 "physical_freeze_detected"
}

note_post_force_shield_thaw() {
    [ "${'$'}post_force_shield_active" -eq 1 ] || return 0
    [ "${'$'}post_force_shield_freeze_seen" -eq 1 ] || return 0
    _shield_latency=${'$'}((NOW_CS - post_force_shield_freeze_detected_cs))
    [ "${'$'}_shield_latency" -ge 0 ] || _shield_latency=0
    emit_post_force_shield thawed "${'$'}_shield_latency" "both_gms_cgroups_thawed"
    post_force_shield_freeze_seen=0
    post_force_shield_freeze_detected_cs=0
}

defense_release_gms_group() {
    _def_consecutive="${'$'}1"
    recovery_command_count=0
    gms_defense_edge_commands=0
    gms_defense_edge_budget_exhausted=0
    defense_budget_enforced=1
    if [ "${'$'}gms_defense_commands" -ge "${'$'}gms_defense_command_budget" ]; then
        gms_defense_budget_exhausted=1
        gms_defense_last_mode="defense_budget_exhausted"
        gms_defense_last_detail="commandBudget=${'$'}gms_defense_command_budget"
        defense_budget_enforced=0
        return 75
    fi

    gms_defense_attempts=${'$'}((gms_defense_attempts + 1))
    if [ "${'$'}sticky_enabled" -eq 1 ]; then
        run_parallel_pair sticky "${'$'}main_pid" "${'$'}persistent_pid" "defense_sticky"
        aggregate_rc "${'$'}PAIR_MAIN_RC" "${'$'}PAIR_PERSISTENT_RC"
        gms_defense_sticky_rc="${'$'}AGG_RC"
        gms_defense_last_mode="defense_sticky_pair"
        gms_defense_last_detail="mainSticky:${'$'}PAIR_MAIN_RC:${'$'}PAIR_MAIN_DETAIL,persistentSticky:${'$'}PAIR_PERSISTENT_RC:${'$'}PAIR_PERSISTENT_DETAIL"
    else
        run_parallel_pair release "${'$'}main_pid" "${'$'}persistent_pid" "defense_release"
        aggregate_rc "${'$'}PAIR_MAIN_RC" "${'$'}PAIR_PERSISTENT_RC"
        gms_defense_plain_rc="${'$'}AGG_RC"
        gms_defense_last_mode="defense_release_pair"
        gms_defense_last_detail="mainRelease:${'$'}PAIR_MAIN_RC:${'$'}PAIR_MAIN_DETAIL,persistentRelease:${'$'}PAIR_PERSISTENT_RC:${'$'}PAIR_PERSISTENT_DETAIL"
    fi
    sleep 0.12
    refresh_slot main "${'$'}main_target"; refresh_slot persistent "${'$'}persistent_target"
    if [ "${'$'}main_pid" -gt 0 ] && [ "${'$'}main_state" = "thawed" ] && \
       [ "${'$'}persistent_pid" -gt 0 ] && [ "${'$'}persistent_state" = "thawed" ]; then
        defense_budget_enforced=0
        return 0
    fi

    # At most one second release phase on the same physical freeze edge. The
    # edge budget prevents repeated release phases from multiplying this into a loop.
    if [ "${'$'}gms_defense_budget_exhausted" -eq 0 ]; then
        run_parallel_pair release "${'$'}main_pid" "${'$'}persistent_pid" "defense_release_retry"
        aggregate_rc "${'$'}PAIR_MAIN_RC" "${'$'}PAIR_PERSISTENT_RC"
        gms_defense_release_rc="${'$'}AGG_RC"
        gms_defense_last_mode="defense_edge_release_retry"
        gms_defense_last_detail="${'$'}{gms_defense_last_detail},edgeRelease:main=${'$'}PAIR_MAIN_RC:${'$'}PAIR_MAIN_DETAIL,persistent=${'$'}PAIR_PERSISTENT_RC:${'$'}PAIR_PERSISTENT_DETAIL"
        sleep 0.18
        refresh_slot main "${'$'}main_target"; refresh_slot persistent "${'$'}persistent_target"
    fi
    if ! { [ "${'$'}main_pid" -gt 0 ] && [ "${'$'}main_state" = "thawed" ] && \
           [ "${'$'}persistent_pid" -gt 0 ] && [ "${'$'}persistent_state" = "thawed" ]; } && \
       [ "${'$'}gms_defense_commands" -ge "${'$'}gms_defense_command_budget" ]; then
        gms_defense_budget_exhausted=1
    fi
    defense_budget_enforced=0
    [ "${'$'}main_pid" -gt 0 ] && [ "${'$'}main_state" = "thawed" ] && \
       [ "${'$'}persistent_pid" -gt 0 ] && [ "${'$'}persistent_state" = "thawed" ]
}

finish_gms_defense_stable() {
    read_uptime_cs
    _def_duration=${'$'}((NOW_CS - gms_defense_started_cs))
    _def_primary_pid="${'$'}main_pid"; [ "${'$'}_def_primary_pid" -gt 0 ] || _def_primary_pid="${'$'}persistent_pid"
    _def_detail="episodeStable:stableCs=${'$'}((NOW_CS - gms_defense_stable_since_cs)),refreezes=${'$'}gms_defense_refreezes,attempts=${'$'}gms_defense_attempts,lastMode=${'$'}gms_defense_last_mode,last=${'$'}gms_defense_last_detail"
    sanitize_detail "${'$'}_def_detail"; _def_detail="${'$'}SANITIZED_DETAIL"
    emit_gms_defense stable "${'$'}_def_detail"
    printf '__LUONNOTAR_VENDOR_BRIDGE_RECOVERY__\tseq=%s\ttarget=%s\tpid=%s\tpeerPid=%s\tgroup=1\tmode=defense_stable_group\tplainRc=%s\tfreezeRc=%s\treleaseRc=%s\tstickyRc=%s\tverified=1\tadoptObserved=%s\tdurationCs=%s\tconsecutive=%s\tcommands=%s\tdetail=%s\n' \
        "${'$'}gms_defense_sequence" "${'$'}main_target" "${'$'}_def_primary_pid" "${'$'}persistent_pid" \
        "${'$'}gms_defense_plain_rc" "${'$'}gms_defense_freeze_rc" "${'$'}gms_defense_release_rc" "${'$'}gms_defense_sticky_rc" \
        "${'$'}gms_defense_adopt_observed" "${'$'}_def_duration" "${'$'}((gms_defense_refreezes + 1))" "${'$'}gms_defense_commands" "${'$'}_def_detail"
    gms_defense_active=0
    gms_defense_stable_since_cs=0
}

escalate_gms_defense() {
    _def_reason="${'$'}1"
    [ "${'$'}gms_defense_escalated" -eq 0 ] || return 0
    gms_defense_escalated=1
    read_uptime_cs
    _def_escalation_deadline=${'$'}((NOW_CS + gms_defense_post_escalation_grace_cs))
    if [ "${'$'}gms_defense_hard_deadline_cs" -gt "${'$'}_def_escalation_deadline" ]; then
        gms_defense_hard_deadline_cs="${'$'}_def_escalation_deadline"
    fi
    emit_gms_defense escalating "reason=${'$'}_def_reason,lastMode=${'$'}gms_defense_last_mode,last=${'$'}gms_defense_last_detail,postEscalationGraceCs=${'$'}gms_defense_post_escalation_grace_cs"
    _def_primary_pid="${'$'}main_pid"; [ "${'$'}_def_primary_pid" -gt 0 ] || _def_primary_pid="${'$'}persistent_pid"
    printf '__LUONNOTAR_VENDOR_BRIDGE_LOCK__\tseq=%s\ttarget=%s\tpid=%s\tfailures=%s\tcooldownCs=0\n' \
        "${'$'}gms_defense_sequence" "${'$'}main_target" "${'$'}_def_primary_pid" "${'$'}gms_defense_attempts"
}

fail_gms_defense() {
    _def_reason="${'$'}1"
    read_uptime_cs
    escalate_gms_defense "${'$'}_def_reason"
    emit_gms_defense "${'$'}_def_reason" "lastMode=${'$'}gms_defense_last_mode,last=${'$'}gms_defense_last_detail"
    gms_defense_active=0
    gms_defense_stable_since_cs=0
    gms_defense_hold_until_cs=${'$'}((NOW_CS + ${GmsVendorDefensePolicy.RETRY_HOLD_CENTISECONDS}))
}

tick_gms_defense() {
    _def_any_frozen="${'$'}1"
    read_uptime_cs
    if [ "${'$'}gms_defense_active" -ne 1 ]; then
        [ "${'$'}_def_any_frozen" -eq 1 ] || return 0
        [ "${'$'}NOW_CS" -ge "${'$'}gms_defense_hold_until_cs" ] || return 0
        start_gms_defense
    fi
    if [ "${'$'}main_pid" -gt 0 ] && [ "${'$'}persistent_pid" -gt 0 ]; then
        reset_gms_defense_for_pid_change
    fi
    read_uptime_cs
    if [ "${'$'}NOW_CS" -ge "${'$'}gms_defense_hard_deadline_cs" ]; then
        fail_gms_defense expired
        return 0
    fi

    if [ "${'$'}_def_any_frozen" -eq 1 ]; then
        storm_until_cs=${'$'}((NOW_CS + 1000))
        note_post_force_shield_freeze
        if [ "${'$'}gms_defense_stable_since_cs" -gt 0 ]; then
            gms_defense_refreezes=${'$'}((gms_defense_refreezes + 1))
            gms_defense_stable_since_cs=0
            gms_defense_stable_hold_announced=0
            gms_defense_pulse_sent=0
            gms_defense_action_armed=1
            emit_gms_defense refrozen "main=${'$'}main_pid/${'$'}main_state,persistent=${'$'}persistent_pid/${'$'}persistent_state"
        fi
        if [ "${'$'}gms_defense_action_armed" -eq 1 ]; then
            gms_defense_action_armed=0
            defense_release_gms_group "${'$'}((gms_defense_refreezes + 1))" || true
            read_uptime_cs
            if [ "${'$'}main_state" = "thawed" ] && [ "${'$'}persistent_state" = "thawed" ]; then
                note_post_force_shield_thaw
                gms_defense_stable_since_cs="${'$'}NOW_CS"
                gms_defense_last_thawed_cs="${'$'}NOW_CS"
                # Re-arm only after an observed physical thaw. A subsequent
                # frozen sample is therefore a new freeze edge, not a timer retry.
                gms_defense_action_armed=1
            fi
        fi
        read_uptime_cs
        if [ "${'$'}gms_defense_budget_exhausted" -ne 0 ]; then
            if [ "${'$'}gms_defense_budget_exhausted" -eq 1 ]; then
                emit_gms_defense budget_exhausted "commandBudget=${'$'}gms_defense_command_budget,edgeBudget=${'$'}gms_defense_edge_command_budget,lastMode=${'$'}gms_defense_last_mode,last=${'$'}gms_defense_last_detail"
                gms_defense_budget_exhausted=2
            fi
            escalate_gms_defense command_budget_exhausted
            return 0
        fi
        _def_since_thaw="${'$'}gms_defense_started_cs"
        [ "${'$'}gms_defense_last_thawed_cs" -gt 0 ] && _def_since_thaw="${'$'}gms_defense_last_thawed_cs"
        if [ "${'$'}gms_defense_attempts" -ge 1 ] && \
           [ ${'$'}((NOW_CS - _def_since_thaw)) -ge "${'$'}gms_defense_stuck_required_cs" ]; then
            escalate_gms_defense no_physical_thaw
        elif [ ${'$'}((NOW_CS - gms_defense_started_cs)) -ge "${'$'}gms_defense_escalation_required_cs" ]; then
            escalate_gms_defense sustained_refreeze
        fi
        return 0
    fi

    if [ "${'$'}main_state" = "thawed" ] && [ "${'$'}persistent_state" = "thawed" ]; then
        note_post_force_shield_thaw
        gms_defense_action_armed=1
        if [ "${'$'}gms_defense_stable_since_cs" -le 0 ]; then
            gms_defense_stable_since_cs="${'$'}NOW_CS"
        fi
        gms_defense_last_thawed_cs="${'$'}NOW_CS"
        _def_stable=${'$'}((NOW_CS - gms_defense_stable_since_cs))
        if [ "${'$'}gms_defense_pulse_sent" -eq 0 ] && \
           [ "${'$'}_def_stable" -ge "${'$'}gms_defense_pulse_required_cs" ]; then
            gms_defense_pulse_sent=1
            emit_gms_defense pulse_ready "oneShotReconnect=1"
        fi
        if [ "${'$'}_def_stable" -ge "${'$'}gms_defense_stable_required_cs" ] && \
           [ "${'$'}gms_defense_stable_hold_announced" -eq 0 ]; then
            gms_defense_stable_hold_announced=1
            emit_gms_defense stable_hold "holdRequiredCs=${'$'}gms_defense_stable_hold_cs"
        fi
        _def_finish_required=${'$'}((gms_defense_stable_required_cs + gms_defense_stable_hold_cs))
        if [ "${'$'}_def_stable" -ge "${'$'}_def_finish_required" ]; then
            finish_gms_defense_stable
        fi
    else
        gms_defense_stable_since_cs=0
    fi
}

inspect_gms_group() {
    [ -n "${'$'}main_target" ] || { main_state="disabled"; persistent_state="disabled"; return; }
    refresh_slot main "${'$'}main_target"
    refresh_slot persistent "${'$'}persistent_target"
    if [ "${'$'}main_pid" -le 0 ] || [ "${'$'}persistent_pid" -le 0 ] || \
       [ "${'$'}main_state" = "unknown" ] || [ "${'$'}persistent_state" = "unknown" ]; then
        if [ "${'$'}gms_incomplete_since_cs" -le 0 ]; then gms_incomplete_since_cs="${'$'}NOW_CS"; fi
        if [ "${'$'}gms_last_incomplete_report_cs" -le 0 ] || \
           [ ${'$'}((NOW_CS - gms_last_incomplete_report_cs)) -ge 500 ]; then
            printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=gms_group_incomplete\tdetail=main_%s_%s_persistent_%s_%s_sinceCs_%s\n' \
                "${'$'}main_pid" "${'$'}main_state" "${'$'}persistent_pid" "${'$'}persistent_state" "${'$'}gms_incomplete_since_cs"
            gms_last_incomplete_report_cs="${'$'}NOW_CS"
        fi
        gms_last_state="incomplete"
        storm_until_cs=${'$'}((NOW_CS + 500))
        tick_gms_defense 0
        return
    fi
    gms_incomplete_since_cs=0
    _any_frozen=0
    [ "${'$'}main_state" = "frozen" ] && _any_frozen=1
    [ "${'$'}persistent_state" = "frozen" ] && _any_frozen=1
    if [ "${'$'}_any_frozen" -eq 1 ] && [ "${'$'}gms_defense_active" -ne 1 ] && \
       [ "${'$'}NOW_CS" -ge "${'$'}gms_defense_hold_until_cs" ]; then
        sequence=${'$'}((sequence + 1))
        _primary_pid="${'$'}main_pid"; [ "${'$'}_primary_pid" -gt 0 ] || _primary_pid="${'$'}persistent_pid"
        printf '__LUONNOTAR_VENDOR_BRIDGE_FROZEN__\tseq=%s\ttarget=%s\tpid=%s\tpath=%s\tconsecutive=%s\n' \
            "${'$'}sequence" "${'$'}main_target" "${'$'}_primary_pid" "group:main=${'$'}main_path,persistent=${'$'}persistent_path" "${'$'}((gms_defense_refreezes + 1))"
    fi

    tick_gms_defense "${'$'}_any_frozen"
    gms_last_state="${'$'}main_state/${'$'}persistent_state"
}

inspect_single() {
    _slot="${'$'}1"; _target="${'$'}2"
    if [ -z "${'$'}_target" ]; then
        eval "${'$'}{_slot}_pid=0"; eval "${'$'}{_slot}_file=''"; eval "${'$'}{_slot}_path=''"; eval "${'$'}{_slot}_state='disabled'"; CURRENT_STATE="disabled"; return
    fi
    eval '_last_state=${'$'}'"${'$'}{_slot}"'_last_state'
    eval '_last_recovery_cs=${'$'}'"${'$'}{_slot}"'_last_recovery_cs'
    eval '_failures=${'$'}'"${'$'}{_slot}"'_failures'
    eval '_cooldown_until_cs=${'$'}'"${'$'}{_slot}"'_cooldown_until_cs'
    refresh_slot "${'$'}_slot" "${'$'}_target"
    eval '_pid=${'$'}'"${'$'}{_slot}"'_pid'; eval '_path=${'$'}'"${'$'}{_slot}"'_path'; eval '_state=${'$'}'"${'$'}{_slot}"'_state'
    CURRENT_STATE="${'$'}_state"
    if [ "${'$'}_state" = "frozen" ]; then
        storm_until_cs=${'$'}((NOW_CS + 1000)); _consecutive=${'$'}((_failures + 1)); _due=0
        if [ "${'$'}NOW_CS" -ge "${'$'}_cooldown_until_cs" ] && { [ "${'$'}_last_recovery_cs" -eq 0 ] || [ ${'$'}((NOW_CS - _last_recovery_cs)) -ge 80 ]; }; then _due=1; fi
        if [ "${'$'}_last_state" != "frozen" ]; then
            sequence=${'$'}((sequence + 1))
            printf '__LUONNOTAR_VENDOR_BRIDGE_FROZEN__\tseq=%s\ttarget=%s\tpid=%s\tpath=%s\tconsecutive=%s\n' "${'$'}sequence" "${'$'}_target" "${'$'}_pid" "${'$'}_path" "${'$'}_consecutive"
        fi
        if [ "${'$'}_due" -eq 1 ]; then
            recover_single "${'$'}_target" "${'$'}_pid" "${'$'}_path" "${'$'}_consecutive"
            read_uptime_cs; _last_recovery_cs="${'$'}NOW_CS"
            if [ "${'$'}RECOVERY_VERIFIED" -eq 1 ]; then _failures=0; _last_state="thawed"; CURRENT_STATE="thawed"
            else
                _failures=${'$'}((_failures + 1)); _last_state="frozen"
                if [ "${'$'}_failures" -ge 3 ]; then
                    _cooldown_until_cs=${'$'}((NOW_CS + 1500))
                    printf '__LUONNOTAR_VENDOR_BRIDGE_LOCK__\tseq=%s\ttarget=%s\tpid=%s\tfailures=%s\tcooldownCs=%s\n' "${'$'}sequence" "${'$'}_target" "${'$'}_pid" "${'$'}_failures" 1500
                    _failures=0
                fi
            fi
        else _last_state="frozen"; fi
    else
        [ "${'$'}_state" = "thawed" ] && _failures=0
        _last_state="${'$'}_state"
    fi
    eval "${'$'}{_slot}_last_state='${'$'}_last_state'"; eval "${'$'}{_slot}_last_recovery_cs=${'$'}_last_recovery_cs"; eval "${'$'}{_slot}_failures=${'$'}_failures"; eval "${'$'}{_slot}_cooldown_until_cs=${'$'}_cooldown_until_cs"
}

write_heartbeat_once() {
    read_uptime_cs
    printf 'atCs=%s\nparentPid=%s\nshellPid=%s\nparentStartTicks=%s\nshellStartTicks=%s\nowner=vendor_bridge\n' \
        "${'$'}NOW_CS" "${'$'}parent_pid" "${'$'}shell_pid" "${'$'}parent_start_ticks" "${'$'}shell_start_ticks" > "${'$'}heartbeat_file.tmp" && \
        mv "${'$'}heartbeat_file.tmp" "${'$'}heartbeat_file"
}

owner_record_is_live() {
    [ -r "${'$'}command_owner_file" ] || return 1
    _owner=""; _owner_parent=0; _owner_shell=0; _owner_parent_start=""; _owner_shell_start=""; _owner_heartbeat=""
    while IFS='=' read -r _key _value; do
        case "${'$'}_key" in
            owner) _owner="${'$'}_value" ;;
            parentPid) _owner_parent="${'$'}_value" ;;
            shellPid) _owner_shell="${'$'}_value" ;;
            parentStartTicks) _owner_parent_start="${'$'}_value" ;;
            shellStartTicks) _owner_shell_start="${'$'}_value" ;;
            heartbeatPath) _owner_heartbeat="${'$'}_value" ;;
        esac
    done < "${'$'}command_owner_file"
    [ "${'$'}_owner" = "vendor_bridge" ] || return 1
    case "${'$'}_owner_parent:${'$'}_owner_shell" in *[!0-9:]*|:|0:*|*:0) return 1 ;; esac
    _expected_owner_heartbeat="${'$'}base_root-${'$'}_owner_shell.heartbeat"
    [ "${'$'}_owner_heartbeat" = "${'$'}_expected_owner_heartbeat" ] || return 1
    [ -r "${'$'}_owner_heartbeat" ] || return 1
    _hb_parent=0; _hb_shell=0; _hb_parent_start=""; _hb_shell_start=""; _hb_at=0; _hb_owner=""
    while IFS='=' read -r _key _value; do
        case "${'$'}_key" in
            owner) _hb_owner="${'$'}_value" ;;
            parentPid) _hb_parent="${'$'}_value" ;;
            shellPid) _hb_shell="${'$'}_value" ;;
            parentStartTicks) _hb_parent_start="${'$'}_value" ;;
            shellStartTicks) _hb_shell_start="${'$'}_value" ;;
            atCs) _hb_at="${'$'}_value" ;;
        esac
    done < "${'$'}_owner_heartbeat"
    case "${'$'}_hb_parent:${'$'}_hb_shell:${'$'}_hb_at" in *[!0-9:]*|*::*) return 1 ;; esac
    [ "${'$'}_hb_owner" = "vendor_bridge" ] || return 1
    [ "${'$'}_hb_parent" = "${'$'}_owner_parent" ] || return 1
    [ "${'$'}_hb_shell" = "${'$'}_owner_shell" ] || return 1
    [ "${'$'}_hb_parent_start" = "${'$'}_owner_parent_start" ] || return 1
    [ "${'$'}_hb_shell_start" = "${'$'}_owner_shell_start" ] || return 1
    pid_start_matches "${'$'}_owner_parent" "${'$'}_owner_parent_start" || return 1
    pid_start_matches "${'$'}_owner_shell" "${'$'}_owner_shell_start" || return 1
    read_uptime_cs
    [ "${'$'}NOW_CS" -ge "${'$'}_hb_at" ] || return 1
    [ ${'$'}((NOW_CS - _hb_at)) -le 500 ]
}

claim_command_owner() {
    _claim_try=0
    while [ "${'$'}_claim_try" -lt 12 ]; do
        if mkdir "${'$'}command_owner_lock" 2>/dev/null; then
            write_heartbeat_once || { rmdir "${'$'}command_owner_lock" 2>/dev/null || true; return 70; }
            printf 'owner=vendor_bridge\nparentPid=%s\nshellPid=%s\nparentStartTicks=%s\nshellStartTicks=%s\nheartbeatPath=%s\n' \
                "${'$'}parent_pid" "${'$'}shell_pid" "${'$'}parent_start_ticks" "${'$'}shell_start_ticks" "${'$'}heartbeat_file" > "${'$'}base/owner.tmp" && \
                mv "${'$'}base/owner.tmp" "${'$'}command_owner_file"
            return ${'$'}?
        fi
        if owner_record_is_live; then
            printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=owner_conflict\tdetail=live_owner_detected\n'
            return 73
        fi
        sleep 0.20
        _claim_try=${'$'}((_claim_try + 1))
    done
    if ! owner_record_is_live; then
        rmdir "${'$'}command_owner_lock" 2>/dev/null || true
        if mkdir "${'$'}command_owner_lock" 2>/dev/null; then
            write_heartbeat_once || { rmdir "${'$'}command_owner_lock" 2>/dev/null || true; return 70; }
            printf 'owner=vendor_bridge\nparentPid=%s\nshellPid=%s\nparentStartTicks=%s\nshellStartTicks=%s\nheartbeatPath=%s\n' \
                "${'$'}parent_pid" "${'$'}shell_pid" "${'$'}parent_start_ticks" "${'$'}shell_start_ticks" "${'$'}heartbeat_file" > "${'$'}base/owner.tmp" && \
                mv "${'$'}base/owner.tmp" "${'$'}command_owner_file"
            return ${'$'}?
        fi
    fi
    printf '__LUONNOTAR_VENDOR_BRIDGE_DIAG__\ttype=owner_claim_failed\tdetail=lock_unavailable\n'
    return 73
}

heartbeat_loop() {
    while pid_start_matches "${'$'}parent_pid" "${'$'}parent_start_ticks" && pid_start_matches "${'$'}shell_pid" "${'$'}shell_start_ticks"; do
        write_heartbeat_once
        sleep 2
    done
}

cleanup() {
    [ -n "${'$'}monitor_pid" ] && kill "${'$'}monitor_pid" >/dev/null 2>&1 || true
    [ -n "${'$'}heartbeat_pid" ] && kill "${'$'}heartbeat_pid" >/dev/null 2>&1 || true
    # Cleanup must still remove our exact lease after the parent has died.
    # Runtime command authorization remains stricter and requires both PIDs
    # to match their original /proc start times.
    if command_owner_record_matches_self; then
        rm -f "${'$'}command_owner_file"
        rmdir "${'$'}command_owner_lock" 2>/dev/null || true
    fi
    if [ -r "${'$'}heartbeat_file" ] && grep -q "shellPid=${'$'}shell_pid" "${'$'}heartbeat_file" 2>/dev/null; then rm -f "${'$'}heartbeat_file"; fi
    rm -rf "${'$'}base"
}

trap cleanup EXIT HUP INT TERM
claim_command_owner || exit ${'$'}?
heartbeat_loop &
heartbeat_pid=${'$'}!
(
    while pid_start_matches "${'$'}parent_pid" "${'$'}parent_start_ticks"; do sleep 2; done
    kill "${'$'}shell_pid" >/dev/null 2>&1 || true
) &
monitor_pid=${'$'}!

printf '__LUONNOTAR_VENDOR_BRIDGE_READY__\ttimeout=%s\tsticky=%s\tstrategy=${GmsVendorDefensePolicy.STRATEGY}\tshellPid=%s\tparentStartTicks=%s\tshellStartTicks=%s\theartbeatPath=%s\townerPath=%s\tgms=%s\twhatsapp=%s\tsignal=%s\n' \
    "${'$'}has_timeout_command" "${'$'}sticky_enabled" "${'$'}shell_pid" "${'$'}parent_start_ticks" "${'$'}shell_start_ticks" "${'$'}heartbeat_file" "${'$'}command_owner_file" \
    "${'$'}([ -n "${'$'}main_target" ] && echo 1 || echo 0)" "${'$'}([ -n "${'$'}whatsapp_target" ] && echo 1 || echo 0)" "${'$'}([ -n "${'$'}signal_target" ] && echo 1 || echo 0)"

while pid_start_matches "${'$'}parent_pid" "${'$'}parent_start_ticks"; do
    require_command_owner
    read_uptime_cs
    refresh_post_force_shield
    inspect_gms_group
    if [ "${'$'}NOW_CS" -ge "${'$'}aux_due_cs" ]; then
        inspect_single whatsapp "${'$'}whatsapp_target"; whatsapp_state="${'$'}CURRENT_STATE"
        inspect_single signal "${'$'}signal_target"; signal_state="${'$'}CURRENT_STATE"
        aux_due_cs=${'$'}((NOW_CS + 100))
    fi
    if [ "${'$'}NOW_CS" -ge "${'$'}heartbeat_due_cs" ]; then
        printf '__LUONNOTAR_VENDOR_BRIDGE_HEARTBEAT__\tatCs=%s\tmainPid=%s\tmainState=%s\tpersistentPid=%s\tpersistentState=%s\twhatsappPid=%s\twhatsappState=%s\tsignalPid=%s\tsignalState=%s\n' \
            "${'$'}NOW_CS" "${'$'}main_pid" "${'$'}main_state" "${'$'}persistent_pid" "${'$'}persistent_state" "${'$'}whatsapp_pid" "${'$'}whatsapp_state" "${'$'}signal_pid" "${'$'}signal_state"
        heartbeat_due_cs=${'$'}((NOW_CS + 500))
    fi
    if [ "${'$'}post_force_shield_active" -eq 1 ]; then
        if [ "${'$'}main_pid" -gt 0 ] && [ "${'$'}persistent_pid" -gt 0 ]; then sleep 0.05; else sleep 0.20; fi
    elif [ "${'$'}NOW_CS" -lt "${'$'}storm_until_cs" ]; then
        sleep 0.15
    else
        sleep 1
    fi
done
exit 0
    """

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
