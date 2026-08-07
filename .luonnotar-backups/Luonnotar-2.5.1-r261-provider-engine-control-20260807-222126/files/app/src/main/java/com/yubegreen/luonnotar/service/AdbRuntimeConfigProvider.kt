package com.yubegreen.luonnotar.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.yubegreen.luonnotar.BuildConfig
import com.yubegreen.luonnotar.experiment.ExperimentSessionOperationResult
import com.yubegreen.luonnotar.experiment.ExperimentSessionRecorder
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

/**
 * Synchronous shell-only runtime configuration bridge.
 *
 * Xiaomi/HyperOS may discard explicit broadcasts addressed to a cached
 * process. A ContentProvider call is synchronous and does not depend on the
 * broadcast queue, so adb can query and mutate the keeper configuration while
 * the device remains locked.
 *
 * The manifest protects this provider with android.permission.DUMP. The UID
 * check below is defense in depth and only permits this app, root, system, or
 * adb shell.
 */
class AdbRuntimeConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        LuonnotarPreferences.initializeKeeperBoot(providerContext)
        LuonnotarPreferences.initializeKeeperProcess(providerContext)
        GuardianProfilePolicy.ensureDefaults(
            providerContext,
            LuonnotarPreferences.deviceProtected(providerContext)
        )
        return true
    }

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!callerAllowed()) {
            return resultBundle(
                ok = false,
                reason = "caller_not_allowed",
                values = emptyMap()
            )
        }
        val appContext = context?.applicationContext
            ?: return resultBundle(
                ok = false,
                reason = "context_unavailable",
                values = emptyMap()
            )
        return when (method) {
            METHOD_STATUS -> status(appContext)
            METHOD_SET -> setRuntimeConfig(appContext, extras ?: Bundle.EMPTY)
            METHOD_PROBE -> probeNow(appContext, extras ?: Bundle.EMPTY)
            METHOD_EXPERIMENT_START -> experimentStart(
                appContext,
                extras ?: Bundle.EMPTY
            )
            METHOD_EXPERIMENT_MARK -> experimentMark(
                appContext,
                extras ?: Bundle.EMPTY
            )
            METHOD_EXPERIMENT_STOP -> experimentStop(appContext)
            else -> resultBundle(
                ok = false,
                reason = "unsupported_method",
                values = emptyMap()
            )
        }
    }

    private fun status(context: android.content.Context): Bundle {
        val status = GuardianStatusClient.status(context)
            ?: return resultBundle(
                ok = false,
                reason = "status_unavailable",
                values = emptyMap()
            )
        val profile = readProfile(status)
            ?: return resultBundle(
                ok = false,
                reason = "invalid_persisted_profile",
                values = emptyMap()
            )
        return resultBundle(
            ok = true,
            reason = "",
            values = statusValues(
                status = status,
                profile = profile,
                changed = false,
                serviceReloadRequested = false
            )
        )
    }

    private fun setRuntimeConfig(
        context: android.content.Context,
        config: Bundle
    ): Bundle {
        val before = GuardianStatusClient.status(context)
            ?: return resultBundle(
                ok = false,
                reason = "status_unavailable",
                values = emptyMap()
            )
        val currentProfile = readProfile(before)
            ?: return resultBundle(
                ok = false,
                reason = "invalid_persisted_profile",
                values = emptyMap()
            )
        val requestedProfile = if (config.containsKey(EXTRA_PROFILE)) {
            runCatching {
                GuardianRuntimeProfile.valueOf(
                    config.getString(EXTRA_PROFILE).orEmpty()
                )
            }.getOrNull()
        } else {
            currentProfile
        }
        if (requestedProfile == null) {
            return resultBundle(
                ok = false,
                reason = "invalid_profile",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val currentLevel = before.getInt(
            LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
            0
        ).coerceIn(0, 4)
        val requestedLevel = if (config.containsKey(EXTRA_LAB_LEVEL)) {
            config.getInt(EXTRA_LAB_LEVEL, -1)
        } else {
            currentLevel
        }
        if (requestedLevel !in 0..4) {
            return resultBundle(
                ok = false,
                reason = "invalid_lab_level",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val desiredExperiments = GuardianProfilePolicy.sanitizeExperiments(
            requestedProfile,
            GuardianProfilePolicy.experimentKeys.associateWith { key ->
                before.getBoolean(key, false)
            }
        ).toMutableMap()
        var mutationRequested =
            config.containsKey(EXTRA_PROFILE) ||
                config.containsKey(EXTRA_LAB_LEVEL)
        GuardianProfilePolicy.adbMutableExperimentKeys.forEach { key ->
            if (config.containsKey(key)) {
                desiredExperiments[key] = config.getBoolean(
                    key,
                    desiredExperiments[key] == true
                )
                mutationRequested = true
            }
        }
        if (!mutationRequested) {
            return resultBundle(
                ok = false,
                reason = "no_mutations_requested",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val validationFailure = GuardianProfilePolicy.runtimeConfigError(
            requestedProfile,
            desiredExperiments
        )
        if (validationFailure != null) {
            return resultBundle(
                ok = false,
                reason = validationFailure,
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val changed =
            requestedProfile != currentProfile ||
                requestedLevel != currentLevel ||
                GuardianProfilePolicy.experimentKeys.any { key ->
                    desiredExperiments[key] != before.getBoolean(key, false)
                }
        val committed = GuardianStatusClient.setRuntimeConfig(
            context,
            requestedProfile,
            requestedLevel,
            desiredExperiments
        )
        if (!committed) {
            return resultBundle(
                ok = false,
                reason = "commit_failed",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val after = GuardianStatusClient.status(context)
        val verified = after != null &&
            after.getString(
                LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                ""
            ) == requestedProfile.name &&
            after.getInt(
                LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
                -1
            ) == requestedLevel &&
            GuardianProfilePolicy.experimentKeys.all { key ->
                after.getBoolean(key, false) == desiredExperiments[key]
            }
        if (!verified || after == null) {
            return resultBundle(
                ok = false,
                reason = "readback_verification_failed",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = changed,
                    serviceReloadRequested = false
                )
            )
        }

        val guardianActive =
            after.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) &&
                !after.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        val reloadResult = if (changed && guardianActive) {
            FcmGuardianService.requestInProcessRuntimeConfigReload(
                "adb_provider_runtime_config_changed"
            )
        } else {
            FcmGuardianService.Companion.InProcessReloadResult.DEFERRED
        }
        val serviceReloadRequested =
            reloadResult == FcmGuardianService.Companion.InProcessReloadResult.APPLIED
        val serviceReloadDeferred =
            changed && guardianActive &&
                reloadResult == FcmGuardianService.Companion.InProcessReloadResult.DEFERRED
        val ok =
            reloadResult != FcmGuardianService.Companion.InProcessReloadResult.FAILED
        val reason = if (ok) "" else "service_reload_failed"
        LogManager.event(
            context,
            "adb_runtime_config_provider_applied",
            mapOf(
                "profile" to requestedProfile.name,
                "labLevel" to requestedLevel,
                "changed" to changed,
                "guardianActive" to guardianActive,
                "serviceReloadRequested" to serviceReloadRequested,
                "serviceReloadDeferred" to serviceReloadDeferred,
                "reloadTransport" to "in_process",
                "ok" to ok,
                "reason" to reason
            )
        )
        return resultBundle(
            ok = ok,
            reason = reason,
            values = statusValues(
                after,
                requestedProfile,
                changed,
                serviceReloadRequested,
                serviceReloadDeferred
            )
        )
    }

    private fun probeNow(
        context: android.content.Context,
        extras: Bundle
    ): Bundle {
        val requested = extras.getString(EXTRA_PROBE).orEmpty()
        val result = FcmGuardianService.requestInProcessProbe(requested)
        val status = GuardianStatusClient.status(context)
        val profile = status?.let(::readProfile)
        val ok = result == FcmGuardianService.Companion.InProcessProbeResult.DISPATCHED
        val reason = if (ok) "" else result.name.lowercase()
        val values = if (status != null && profile != null) {
            statusValues(
                status,
                profile,
                changed = false,
                serviceReloadRequested = false
            ).toMutableMap().apply {
                put("probe", requested.lowercase())
                put("probe_dispatched", ok)
                put("probe_result", result.name)
            }
        } else {
            linkedMapOf(
                "schema" to 2,
                "versionName" to BuildConfig.VERSION_NAME,
                "transport" to "content_provider",
                "probe" to requested.lowercase(),
                "probe_dispatched" to ok,
                "probe_result" to result.name
            )
        }
        LogManager.event(
            context,
            "adb_runtime_probe_requested",
            mapOf(
                "probe" to requested,
                "result" to result.name
            )
        )
        return resultBundle(ok, reason, values)
    }

    private fun experimentStart(
        context: android.content.Context,
        extras: Bundle
    ): Bundle = experimentResult(
        context,
        ExperimentSessionRecorder.start(
            context,
            LuonnotarPreferences.deviceProtected(context),
            extras.getString(EXTRA_SESSION_NAME),
            SOURCE_ADB
        )
    )

    private fun experimentMark(
        context: android.content.Context,
        extras: Bundle
    ): Bundle = experimentResult(
        context,
        ExperimentSessionRecorder.mark(
            context,
            LuonnotarPreferences.deviceProtected(context),
            extras.getString(EXTRA_MARK_LABEL),
            SOURCE_ADB
        )
    )

    private fun experimentStop(
        context: android.content.Context
    ): Bundle = experimentResult(
        context,
        ExperimentSessionRecorder.stop(
            context,
            LuonnotarPreferences.deviceProtected(context),
            SOURCE_ADB
        )
    )

    private fun experimentResult(
        context: android.content.Context,
        operation: ExperimentSessionOperationResult
    ): Bundle {
        val status = GuardianStatusClient.status(context)
        val profile = status?.let(::readProfile)
        val values: MutableMap<String, Any> = if (
            status != null && profile != null
        ) {
            statusValues(
                status = status,
                profile = profile,
                changed = false,
                serviceReloadRequested = false
            ).toMutableMap()
        } else {
            linkedMapOf<String, Any>(
                "schema" to 4,
                "versionName" to BuildConfig.VERSION_NAME,
                "transport" to "content_provider"
            )
        }
        values.putAll(operation.values)
        return resultBundle(operation.ok, operation.reason, values)
    }

    private fun readProfile(status: Bundle): GuardianRuntimeProfile? =
        runCatching {
            GuardianRuntimeProfile.valueOf(
                status.getString(
                    LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                    GuardianProfilePolicy.defaultProfile(
                        GuardianProfilePolicy.isVivoFamily(
                            android.os.Build.MANUFACTURER,
                            android.os.Build.BRAND
                        )
                    ).name
                ).orEmpty()
            )
        }.getOrNull()

    private fun statusValues(
        status: Bundle,
        profile: GuardianRuntimeProfile,
        changed: Boolean,
        serviceReloadRequested: Boolean,
        serviceReloadDeferred: Boolean = false
    ): Map<String, Any> = linkedMapOf<String, Any>().apply {
        put("schema", 4)
        put("versionName", BuildConfig.VERSION_NAME)
        put("transport", "content_provider")
        put("profile", profile.name)
        put(
            "lab_level",
            status.getInt(LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL, 0)
        )
        put(
            "guardian_enabled",
            status.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
        )
        put(
            "guardian_paused",
            status.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        )
        put("changed", changed)
        put("service_reload_requested", serviceReloadRequested)
        put("service_reload_deferred", serviceReloadDeferred)
        put("service_reload_transport", "in_process")
        val sessionActive = status.getBoolean(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
            false
        )
        val sessionStartedElapsed = status.getLong(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_ELAPSED,
            0L
        )
        put("experiment_session_active", sessionActive)
        put(
            "experiment_session_id",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ID,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_name",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_NAME,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_source",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_SOURCE,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_boot_id",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_BOOT_ID,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_started_wall",
            status.getLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_WALL,
                0L
            )
        )
        put(
            "experiment_session_age_ms",
            if (
                sessionActive &&
                sessionStartedElapsed > 0L &&
                sessionStartedElapsed <= SystemClock.elapsedRealtime()
            ) {
                SystemClock.elapsedRealtime() - sessionStartedElapsed
            } else {
                -1L
            }
        )
        put(
            "experiment_session_mark_count",
            status.getInt(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_MARK_COUNT,
                0
            )
        )
        put(
            "experiment_session_last_mark",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_last_mark_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_EXPERIMENT_SESSION_LAST_MARK_ELAPSED,
                    0L
                )
            )
        )
        put(
            "experiment_session_last_event",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_EVENT,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_last_duration_ms",
            status.getLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_DURATION_MS,
                -1L
            )
        )
        GuardianProfilePolicy.adbMutableExperimentKeys.forEach { key ->
            put(key, status.getBoolean(key, false))
        }
        put(
            "service_generation",
            status.getLong(LuonnotarPreferences.KEY_SERVICE_GENERATION, 0L)
        )
        put(
            "keeper_pid",
            status.getInt(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID, 0)
        )
        put(
            "vpn_network_handle",
            status.getLong(LuonnotarPreferences.KEY_NETWORK_HANDLE, -1L)
        )
        put(
            "gms_binder_anchor_state",
            status.getString(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_STATE,
                ""
            ).orEmpty()
        )
        put(
            "gms_binder_anchor_pid",
            status.getInt(LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_PID, 0)
        )
        put(
            "notification_listener_connected",
            status.getBoolean(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED,
                false
            )
        )
        put(
            "notification_listener_pid",
            status.getInt(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID,
                0
            )
        )
        val listenerHeartbeat = status.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED,
            0L
        )
        put(
            "notification_listener_heartbeat_age_ms",
            if (listenerHeartbeat > 0L) {
                (SystemClock.elapsedRealtime() - listenerHeartbeat)
                    .coerceAtLeast(0L)
            } else {
                -1L
            }
        )
        put(
            "notification_listener_rebind_count",
            status.getInt(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_REBIND_COUNT,
                0
            )
        )
        put(
            "vpn_session_generation",
            status.getLong(
                LuonnotarPreferences.KEY_VPN_SESSION_GENERATION,
                0L
            )
        )
        put(
            "gms_binder_anchor_session_generation",
            status.getLong(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_SESSION_GENERATION,
                0L
            )
        )
        put(
            "persistent_network_lease_state",
            status.getString(
                LuonnotarPreferences.KEY_PERSISTENT_NETWORK_LEASE_STATE,
                "STOPPED"
            ).orEmpty()
        )
        put(
            "persistent_network_lease_handle",
            status.getLong(
                LuonnotarPreferences.KEY_PERSISTENT_NETWORK_LEASE_HANDLE,
                -1L
            )
        )
        put(
            "persistent_network_lease_last_event_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_NETWORK_LEASE_LAST_EVENT_ELAPSED,
                    0L
                )
            )
        )
        put(
            "persistent_heartbeat_socket_state",
            status.getString(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_STATE,
                "STOPPED"
            ).orEmpty()
        )
        put(
            "persistent_heartbeat_socket_handle",
            status.getLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_HANDLE,
                -1L
            )
        )
        put(
            "persistent_heartbeat_socket_last_event_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_EVENT_ELAPSED,
                    0L
                )
            )
        )
        put(
            "persistent_heartbeat_socket_reason",
            status.getString(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_REASON,
                ""
            ).orEmpty()
        )
        put(
            "persistent_heartbeat_socket_total_connect_count",
            status.getInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_CONNECT_COUNT,
                0
            )
        )
        put(
            "persistent_heartbeat_socket_session_connect_count",
            status.getInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_CONNECT_COUNT,
                0
            )
        )
        put(
            "persistent_heartbeat_socket_connection_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_HEARTBEAT_SOCKET_CONNECTION_STARTED_ELAPSED,
                    0L
                )
            )
        )
        put(
            "persistent_heartbeat_socket_last_heartbeat_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_HEARTBEAT_ELAPSED,
                    0L
                )
            )
        )
        put(
            "persistent_heartbeat_socket_total_heartbeat_count",
            status.getLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_HEARTBEAT_COUNT,
                0L
            )
        )
        put(
            "persistent_heartbeat_socket_session_heartbeat_count",
            status.getInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_HEARTBEAT_COUNT,
                0
            )
        )
        put(
            "persistent_heartbeat_socket_consecutive_failures",
            status.getInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_CONSECUTIVE_FAILURES,
                0
            )
        )
        put(
            "persistent_heartbeat_socket_backoff_ms",
            status.getLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_BACKOFF_MS,
                0L
            )
        )
    }

    private fun elapsedAge(eventElapsed: Long): Long =
        if (eventElapsed > 0L) {
            (SystemClock.elapsedRealtime() - eventElapsed).coerceAtLeast(0L)
        } else {
            -1L
        }

    private fun resultBundle(
        ok: Boolean,
        reason: String,
        values: Map<String, Any>
    ): Bundle {
        val wireValues = linkedMapOf<String, Any>(
            "ok" to ok,
            "reason" to reason
        ).apply { putAll(values) }
        val wire = wireValues.entries.joinToString(";") { (key, value) ->
            "$key=$value"
        }
        return Bundle().apply {
            putBoolean(RESULT_OK, ok)
            putString(RESULT_REASON, reason)
            putString(RESULT_WIRE, wire)
        }
    }

    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        return uid == Process.myUid() ||
            uid == Process.ROOT_UID ||
            uid == Process.SYSTEM_UID ||
            uid == Process.SHELL_UID
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = ".adb_runtime_config"
        const val METHOD_STATUS = "status"
        const val METHOD_SET = "set"
        const val METHOD_PROBE = "probe"
        const val METHOD_EXPERIMENT_START = "experiment_start"
        const val METHOD_EXPERIMENT_MARK = "experiment_mark"
        const val METHOD_EXPERIMENT_STOP = "experiment_stop"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SESSION_NAME = "session_name"
        const val EXTRA_MARK_LABEL = "mark_label"
        const val EXTRA_PROBE = "probe"
        const val EXTRA_LAB_LEVEL = "lab_level"
        const val RESULT_OK = "ok"
        const val RESULT_REASON = "reason"
        const val RESULT_WIRE = "wire"
        const val SOURCE_ADB = "adb"
    }
}
