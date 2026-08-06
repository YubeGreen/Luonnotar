package com.yubegreen.luonnotar.privileged

/**
 * Direct cgroup watcher for ROMs that freeze GMS outside AOSP's
 * CachedAppOptimizer bookkeeping (notably vivo/OriginOS PEM).
 *
 * The bridge first tries the ordinary framework unfreeze command. If the
 * process remains frozen, it performs a bounded "adopt -> release" cycle:
 * system_server is asked to force-freeze the exact ProcessRecord and is then
 * asked to force-unfreeze it. This lets system_server synchronize its internal
 * freezer state with a process that the vendor layer put in a frozen cgroup.
 */
internal sealed interface GmsVendorFreezeBridgeRecord {
    data class Ready(
        val timeout: Boolean,
        val sticky: Boolean
    ) : GmsVendorFreezeBridgeRecord

    data class Heartbeat(
        val elapsedCentiseconds: Long,
        val mainPid: Int,
        val mainState: String,
        val persistentPid: Int,
        val persistentState: String
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
        val mode: String,
        val plainExitCode: Int,
        val freezeExitCode: Int,
        val releaseExitCode: Int,
        val stickyExitCode: Int,
        val verified: Boolean,
        val durationCentiseconds: Long,
        val consecutive: Int
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
    private const val LOCK = "${PREFIX}LOCK__\t"
    private const val DIAGNOSTIC = "${PREFIX}DIAG__\t"

    fun parse(line: String): GmsVendorFreezeBridgeRecord? = when {
        line.startsWith(READY) -> {
            val values = fields(line.removePrefix(READY))
            GmsVendorFreezeBridgeRecord.Ready(
                timeout = values["timeout"] == "1",
                sticky = values["sticky"] == "1"
            )
        }
        line.startsWith(HEARTBEAT) -> {
            val values = fields(line.removePrefix(HEARTBEAT))
            GmsVendorFreezeBridgeRecord.Heartbeat(
                elapsedCentiseconds = values["atCs"].toLongOrZero(),
                mainPid = values["mainPid"].toIntOrZero(),
                mainState = values["mainState"].orEmpty().ifBlank { "unknown" },
                persistentPid = values["persistentPid"].toIntOrZero(),
                persistentState = values["persistentState"].orEmpty().ifBlank { "unknown" }
            )
        }
        line.startsWith(FROZEN) -> {
            val values = fields(line.removePrefix(FROZEN))
            GmsVendorFreezeBridgeRecord.Frozen(
                sequence = values["seq"].toLongOrZero(),
                target = values["target"].orEmpty().ifBlank { "unknown" },
                pid = values["pid"].toIntOrZero(),
                cgroupPath = values["path"].orEmpty(),
                consecutive = values["consecutive"].toIntOrZero()
            )
        }
        line.startsWith(RECOVERY) -> {
            val values = fields(line.removePrefix(RECOVERY))
            GmsVendorFreezeBridgeRecord.Recovery(
                sequence = values["seq"].toLongOrZero(),
                target = values["target"].orEmpty().ifBlank { "unknown" },
                pid = values["pid"].toIntOrZero(),
                mode = values["mode"].orEmpty().ifBlank { "unknown" },
                plainExitCode = values["plainRc"]?.toIntOrNull() ?: -1,
                freezeExitCode = values["freezeRc"]?.toIntOrNull() ?: -1,
                releaseExitCode = values["releaseRc"]?.toIntOrNull() ?: -1,
                stickyExitCode = values["stickyRc"]?.toIntOrNull() ?: -1,
                verified = values["verified"] == "1",
                durationCentiseconds = values["durationCs"].toLongOrZero(),
                consecutive = values["consecutive"].toIntOrZero()
            )
        }
        line.startsWith(LOCK) -> {
            val values = fields(line.removePrefix(LOCK))
            GmsVendorFreezeBridgeRecord.VendorLock(
                sequence = values["seq"].toLongOrZero(),
                target = values["target"].orEmpty().ifBlank { "unknown" },
                pid = values["pid"].toIntOrZero(),
                failures = values["failures"].toIntOrZero(),
                cooldownCentiseconds = values["cooldownCs"].toLongOrZero()
            )
        }
        line.startsWith(DIAGNOSTIC) -> {
            val values = fields(line.removePrefix(DIAGNOSTIC))
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

internal object GmsVendorFreezeBridgeScript {
    private const val DEFAULT_BASE_ROOT = "/data/local/tmp"
    private val SAFE_BASE_ROOT = Regex("^/[A-Za-z0-9_./-]{1,180}$")

    fun build(
        parentPid: Int,
        stickyUnfreeze: Boolean,
        baseRoot: String = DEFAULT_BASE_ROOT
    ): String {
        require(parentPid > 1) { "invalid parent pid" }
        require(SAFE_BASE_ROOT.matches(baseRoot)) { "invalid vendor bridge base root" }
        val sticky = if (stickyUnfreeze) 1 else 0
        val baseRootForParent = "$baseRoot/luonnotar-gms-vendor-bridge-$parentPid"
        return """
            umask 077
            parent_pid=$parentPid
            shell_pid=${'$'}${'$'}
            base_root=${shellQuote(baseRootForParent)}
            base="${'$'}base_root-${'$'}shell_pid"
            sticky_enabled=$sticky
            has_timeout_command=0
            sequence=0
            heartbeat_due_cs=0
            storm_until_cs=0
            monitor_pid=""

            main_target="com.google.android.gms"
            persistent_target="com.google.android.gms.persistent"
            main_pid=0
            main_file=""
            main_path=""
            main_last_state="unknown"
            main_last_recovery_cs=0
            main_failures=0
            main_cooldown_until_cs=0
            persistent_pid=0
            persistent_file=""
            persistent_path=""
            persistent_last_state="unknown"
            persistent_last_recovery_cs=0
            persistent_failures=0
            persistent_cooldown_until_cs=0

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

            run_limited() {
                if [ "${'$'}has_timeout_command" -eq 1 ]; then
                    timeout 2 "${'$'}@"
                    return ${'$'}?
                fi
                "${'$'}@" &
                _limited_pid=${'$'}!
                _limited_tick=0
                while kill -0 "${'$'}_limited_pid" >/dev/null 2>&1 && \
                    [ "${'$'}_limited_tick" -lt 20 ]; do
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

            resolve_target() {
                _target="${'$'}1"
                RESOLVED_PID=0
                RESOLVED_FILE=""
                RESOLVED_PATH=""
                _pids="${'$'}(pidof "${'$'}_target" 2>/dev/null || true)"
                for _pid in ${'$'}_pids; do
                    case "${'$'}_pid" in ''|*[!0-9]*) continue ;; esac
                    [ -r "/proc/${'$'}_pid/cgroup" ] || continue
                    _path="${'$'}(awk -F: '${'$'}1 == "0" { print ${'$'}3; exit }' "/proc/${'$'}_pid/cgroup" 2>/dev/null)"
                    if [ -n "${'$'}_path" ] && [ -r "/sys/fs/cgroup${'$'}_path/cgroup.freeze" ]; then
                        RESOLVED_PID="${'$'}_pid"
                        RESOLVED_FILE="/sys/fs/cgroup${'$'}_path/cgroup.freeze"
                        RESOLVED_PATH="${'$'}_path"
                        return 0
                    fi
                    _v1_path="${'$'}(awk -F: '${'$'}2 ~ /(^|,)freezer(,|${'$'})/ { print ${'$'}3; exit }' "/proc/${'$'}_pid/cgroup" 2>/dev/null)"
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
                if [ "${'$'}_cached_pid" -gt 0 ] && [ -r "/proc/${'$'}_cached_pid/cgroup" ] && \
                   [ -r "${'$'}_cached_file" ]; then
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

            framework_release() {
                _subject="${'$'}1"
                # Release without --sticky first. On AOSP, `unfreeze --sticky`
                # records a sticky decision before executing the physical
                # release, so it can intentionally refuse to thaw a process
                # whose ProcessCachedOptimizerRecord is already sticky.
                run_limited cmd activity unfreeze "${'$'}_subject" >/dev/null 2>&1
                _rc=${'$'}?
                if [ "${'$'}_rc" -ne 0 ]; then
                    run_limited am unfreeze "${'$'}_subject" --user 0 >/dev/null 2>&1
                    _rc=${'$'}?
                fi
                return "${'$'}_rc"
            }

            mark_unfrozen_sticky() {
                _subject="${'$'}1"
                [ "${'$'}sticky_enabled" -eq 1 ] || return 125
                # Once the cgroup is physically thawed, this second command
                # installs AOSP's sticky *unfrozen* decision. It is not relied
                # upon for the physical thaw itself.
                run_limited cmd activity unfreeze --sticky "${'$'}_subject" >/dev/null 2>&1
                _rc=${'$'}?
                if [ "${'$'}_rc" -ne 0 ]; then
                    run_limited am unfreeze --sticky "${'$'}_subject" --user 0 >/dev/null 2>&1
                    _rc=${'$'}?
                fi
                return "${'$'}_rc"
            }

            force_freeze() {
                _subject="${'$'}1"
                run_limited cmd activity freeze "${'$'}_subject" >/dev/null 2>&1
                _rc=${'$'}?
                if [ "${'$'}_rc" -ne 0 ]; then
                    run_limited am freeze "${'$'}_subject" --user 0 >/dev/null 2>&1
                    _rc=${'$'}?
                fi
                return "${'$'}_rc"
            }

            recover_target() {
                _target="${'$'}1"
                _pid="${'$'}2"
                _path="${'$'}3"
                _consecutive="${'$'}4"
                read_uptime_cs; _started_cs="${'$'}NOW_CS"
                _plain_rc=125
                _freeze_rc=125
                _release_rc=125
                _sticky_rc=125
                _mode="plain"
                _verified=0

                framework_release "${'$'}_target"
                _plain_rc=${'$'}?
                sleep 0.12
                read_target_state "${'$'}_target" "${'$'}_pid" "" "${'$'}_path"
                if [ "${'$'}STATE" = "thawed" ]; then
                    _verified=1
                    mark_unfrozen_sticky "${'$'}_target"
                    _sticky_rc=${'$'}?
                else
                    # A vendor can place the process in a cgroup without setting
                    # ProcessCachedOptimizerRecord.isFrozen. A forced framework
                    # freeze (without a sticky frozen decision) makes
                    # system_server adopt the ProcessRecord; a non-sticky
                    # unfreeze then releases binder + cgroup from one coherent
                    # framework state. A final sticky command protects the
                    # resulting unfrozen decision from AOSP's own freezer.
                    _mode="adopt_release"
                    force_freeze "${'$'}_target"
                    _freeze_rc=${'$'}?
                    sleep 0.45
                    framework_release "${'$'}_target"
                    _release_rc=${'$'}?
                    sleep 0.18
                    read_target_state "${'$'}_target" "${'$'}_pid" "" "${'$'}_path"
                    if [ "${'$'}STATE" = "thawed" ]; then
                        _verified=1
                        mark_unfrozen_sticky "${'$'}_target"
                        _sticky_rc=${'$'}?
                    fi
                fi
                read_uptime_cs; _ended_cs="${'$'}NOW_CS"
                printf '__LUONNOTAR_VENDOR_BRIDGE_RECOVERY__\tseq=%s\ttarget=%s\tpid=%s\tmode=%s\tplainRc=%s\tfreezeRc=%s\treleaseRc=%s\tstickyRc=%s\tverified=%s\tdurationCs=%s\tconsecutive=%s\n' \
                    "${'$'}sequence" "${'$'}_target" "${'$'}_pid" "${'$'}_mode" "${'$'}_plain_rc" \
                    "${'$'}_freeze_rc" "${'$'}_release_rc" "${'$'}_sticky_rc" "${'$'}_verified" \
                    "${'$'}((_ended_cs - _started_cs))" "${'$'}_consecutive"
                RECOVERY_VERIFIED="${'$'}_verified"
            }

            inspect_target() {
                _slot="${'$'}1"
                _target="${'$'}2"
                eval '_cached_pid=${'$'}'"${'$'}{_slot}"'_pid'
                eval '_cached_file=${'$'}'"${'$'}{_slot}"'_file'
                eval '_cached_path=${'$'}'"${'$'}{_slot}"'_path'
                eval '_last_state=${'$'}'"${'$'}{_slot}"'_last_state'
                eval '_last_recovery_cs=${'$'}'"${'$'}{_slot}"'_last_recovery_cs'
                eval '_failures=${'$'}'"${'$'}{_slot}"'_failures'
                eval '_cooldown_until_cs=${'$'}'"${'$'}{_slot}"'_cooldown_until_cs'

                read_target_state "${'$'}_target" "${'$'}_cached_pid" "${'$'}_cached_file" "${'$'}_cached_path"
                eval "${'$'}{_slot}_pid=${'$'}STATE_PID"
                eval "${'$'}{_slot}_file='${'$'}STATE_FILE'"
                eval "${'$'}{_slot}_path='${'$'}STATE_PATH'"
                CURRENT_STATE="${'$'}STATE"

                if [ "${'$'}STATE" = "frozen" ]; then
                    storm_until_cs=${'$'}((NOW_CS + 1000))
                    _consecutive=${'$'}((_failures + 1))
                    _recovery_due=0
                    if [ "${'$'}NOW_CS" -ge "${'$'}_cooldown_until_cs" ] && \
                       { [ "${'$'}_last_recovery_cs" -eq 0 ] || [ ${'$'}((NOW_CS - _last_recovery_cs)) -ge 80 ]; }; then
                        _recovery_due=1
                    fi

                    # One FROZEN record per physical freeze episode. Recovery
                    # records carry later retries; this avoids 6-7 log entries
                    # per second while OriginOS holds the cgroup frozen.
                    if [ "${'$'}_last_state" != "frozen" ]; then
                        sequence=${'$'}((sequence + 1))
                        printf '__LUONNOTAR_VENDOR_BRIDGE_FROZEN__\tseq=%s\ttarget=%s\tpid=%s\tpath=%s\tconsecutive=%s\n' \
                            "${'$'}sequence" "${'$'}_target" "${'$'}STATE_PID" "${'$'}STATE_PATH" "${'$'}_consecutive"
                    fi

                    if [ "${'$'}_recovery_due" -eq 1 ]; then
                        recover_target "${'$'}_target" "${'$'}STATE_PID" "${'$'}STATE_PATH" "${'$'}_consecutive"
                        _last_recovery_cs="${'$'}NOW_CS"
                        if [ "${'$'}RECOVERY_VERIFIED" -eq 1 ]; then
                            _failures=0
                            _last_state="thawed"
                            CURRENT_STATE="thawed"
                        else
                            _failures=${'$'}((_failures + 1))
                            _last_state="frozen"
                            if [ "${'$'}_failures" -ge 3 ]; then
                                _cooldown_until_cs=${'$'}((NOW_CS + 1500))
                                printf '__LUONNOTAR_VENDOR_BRIDGE_LOCK__\tseq=%s\ttarget=%s\tpid=%s\tfailures=%s\tcooldownCs=%s\n' \
                                    "${'$'}sequence" "${'$'}_target" "${'$'}STATE_PID" "${'$'}_failures" 1500
                                _failures=0
                            fi
                        fi
                    else
                        _last_state="frozen"
                    fi
                else
                    [ "${'$'}STATE" = "thawed" ] && _failures=0
                    _last_state="${'$'}STATE"
                fi

                eval "${'$'}{_slot}_last_state='${'$'}_last_state'"
                eval "${'$'}{_slot}_last_recovery_cs=${'$'}_last_recovery_cs"
                eval "${'$'}{_slot}_failures=${'$'}_failures"
                eval "${'$'}{_slot}_cooldown_until_cs=${'$'}_cooldown_until_cs"
            }

            cleanup() {
                [ -n "${'$'}monitor_pid" ] && kill "${'$'}monitor_pid" >/dev/null 2>&1 || true
                rm -rf "${'$'}base"
            }

            (
                while kill -0 "${'$'}parent_pid" >/dev/null 2>&1; do sleep 2; done
                kill "${'$'}shell_pid" >/dev/null 2>&1 || true
            ) &
            monitor_pid=${'$'}!
            trap cleanup EXIT HUP INT TERM

            printf '__LUONNOTAR_VENDOR_BRIDGE_READY__\ttimeout=%s\tsticky=%s\tstrategy=adopt_release\n' \
                "${'$'}has_timeout_command" "${'$'}sticky_enabled"

            while kill -0 "${'$'}parent_pid" >/dev/null 2>&1; do
                read_uptime_cs
                inspect_target main "${'$'}main_target"
                main_state="${'$'}CURRENT_STATE"
                inspect_target persistent "${'$'}persistent_target"
                persistent_state="${'$'}CURRENT_STATE"

                if [ "${'$'}NOW_CS" -ge "${'$'}heartbeat_due_cs" ]; then
                    printf '__LUONNOTAR_VENDOR_BRIDGE_HEARTBEAT__\tatCs=%s\tmainPid=%s\tmainState=%s\tpersistentPid=%s\tpersistentState=%s\n' \
                        "${'$'}NOW_CS" "${'$'}main_pid" "${'$'}main_state" \
                        "${'$'}persistent_pid" "${'$'}persistent_state"
                    heartbeat_due_cs=${'$'}((NOW_CS + 500))
                fi

                if [ "${'$'}NOW_CS" -lt "${'$'}storm_until_cs" ]; then
                    sleep 0.15
                else
                    sleep 1
                fi
            done
            exit 0
        """.trimIndent() + "\n"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
